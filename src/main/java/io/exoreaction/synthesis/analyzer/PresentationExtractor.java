package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.ai.AiClient;
import io.exoreaction.synthesis.ai.PromptTemplates;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Extracts slides from presentation PDFs as individual images.
 *
 * <p>Uses PDFBox's PDFRenderer to render each page as a PNG image at
 * configurable DPI. Optionally generates AI descriptions for each slide
 * and creates a README with a slide overview.
 *
 * <p>Output structure:
 * <pre>
 *   output-dir/
 *     slide-001.png
 *     slide-002.png
 *     ...
 *     README.md  (if --with-readme)
 * </pre>
 */
public class PresentationExtractor {

    /** Default DPI for rendering slides (150 = good balance of quality and size). */
    public static final int DEFAULT_DPI = 150;

    /** High DPI for rendering slides (300 = presentation quality). */
    public static final int HIGH_DPI = 300;

    /**
     * Result of a slide extraction operation.
     */
    public record ExtractionResult(
            Path outputDirectory,
            int slidesExtracted,
            int slidesDescribed,
            List<SlideInfo> slides,
            String presentationTitle
    ) {}

    /**
     * Information about a single extracted slide.
     */
    public record SlideInfo(
            int slideNumber,
            Path imagePath,
            String description,
            List<String> keywords
    ) {}

    /**
     * Extracts slides from a PDF file as PNG images.
     *
     * @param pdfPath   path to the PDF file
     * @param outputDir directory to write slide images to
     * @param dpi       rendering DPI (150 for web, 300 for print)
     * @param client    optional Claude client for vision descriptions
     * @return extraction result with slide details
     * @throws IOException if the PDF cannot be read or images cannot be written
     */
    public ExtractionResult extractSlides(Path pdfPath, Path outputDir, int dpi,
                                           AiClient client) throws IOException {
        Files.createDirectories(outputDir);

        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            PDDocumentInformation info = document.getDocumentInformation();
            String title = info.getTitle() != null ? info.getTitle().trim() : pdfPath.getFileName().toString();
            int pageCount = document.getNumberOfPages();

            PDFRenderer renderer = new PDFRenderer(document);
            List<SlideInfo> slides = new ArrayList<>();

            for (int i = 0; i < pageCount; i++) {
                int slideNum = i + 1;
                String filename = String.format("slide-%03d.png", slideNum);
                Path slidePath = outputDir.resolve(filename);

                // Render page as image
                BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                ImageIO.write(image, "PNG", slidePath.toFile());

                // Optionally generate AI description
                String description = "";
                List<String> keywords = new ArrayList<>();

                if (client != null) {
                    try {
                        String response = client.generateFromImage(
                                slidePath, PromptTemplates.SLIDE_DESCRIPTION, 512);
                        description = parseDescription(response);
                        keywords = parseKeywords(response);
                    } catch (Exception e) {
                        // Vision failed for this slide -- continue without description
                        description = "";
                    }
                }

                slides.add(new SlideInfo(slideNum, slidePath, description, keywords));
            }

            int described = (int) slides.stream()
                    .filter(s -> !s.description().isEmpty())
                    .count();

            return new ExtractionResult(outputDir, pageCount, described, slides, title);
        }
    }

    /**
     * Generates a README.md summarizing the extracted slides.
     *
     * @param result the extraction result
     * @param originalPdf the source PDF path
     * @return the generated README content
     */
    public String generateReadme(ExtractionResult result, Path originalPdf) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(result.presentationTitle()).append("\n\n");
        sb.append("**Source:** `").append(originalPdf.getFileName()).append("`\n");
        sb.append("**Slides:** ").append(result.slidesExtracted()).append("\n");
        if (result.slidesDescribed() > 0) {
            sb.append("**AI Descriptions:** ").append(result.slidesDescribed())
                    .append(" of ").append(result.slidesExtracted()).append(" slides\n");
        }
        sb.append("\n---\n\n");

        for (SlideInfo slide : result.slides()) {
            sb.append("## Slide ").append(slide.slideNumber()).append("\n\n");
            sb.append("![Slide ").append(slide.slideNumber()).append("](")
                    .append(slide.imagePath().getFileName()).append(")\n\n");

            if (!slide.description().isEmpty()) {
                sb.append(slide.description()).append("\n\n");
            }

            if (!slide.keywords().isEmpty()) {
                sb.append("**Keywords:** ").append(String.join(", ", slide.keywords())).append("\n\n");
            }

            sb.append("---\n\n");
        }

        sb.append("\n*Generated by Synthesis - AI operations partner for knowledge infrastructure*\n");
        return sb.toString();
    }

    /**
     * Estimates the cost of extracting and describing slides with vision.
     *
     * @param pageCount number of pages in the PDF
     * @return estimated cost in USD
     */
    public static double estimateCost(int pageCount) {
        // Each slide is rendered as a ~100KB-500KB PNG, then analyzed by vision
        return pageCount * 0.02; // ~$0.02 per slide for vision analysis
    }

    /**
     * Parses the description text from a vision response (before "Keywords:" line).
     */
    static String parseDescription(String response) {
        if (response == null || response.isEmpty()) return "";

        int keywordsIdx = response.toLowerCase().indexOf("keywords:");
        if (keywordsIdx > 0) {
            return response.substring(0, keywordsIdx).trim();
        }
        return response.trim();
    }

    /**
     * Parses keywords from a vision response (after "Keywords:" line).
     */
    static List<String> parseKeywords(String response) {
        if (response == null || response.isEmpty()) return List.of();

        int keywordsIdx = response.toLowerCase().indexOf("keywords:");
        if (keywordsIdx < 0) return List.of();

        String keywordsLine = response.substring(keywordsIdx + "keywords:".length()).trim();
        // Take only the first line of keywords
        int newlineIdx = keywordsLine.indexOf('\n');
        if (newlineIdx > 0) {
            keywordsLine = keywordsLine.substring(0, newlineIdx);
        }

        return Arrays.stream(keywordsLine.split("[,;]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .toList();
    }
}
