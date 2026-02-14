package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.*;

/**
 * Analyzes PDF files to extract text content, metadata, structure,
 * and detect presentation-type PDFs.
 *
 * <p>Uses Apache PDFBox to extract:
 * <ul>
 *   <li>Text content (for full-text search indexing)</li>
 *   <li>Document metadata (title, author, subject, creation date)</li>
 *   <li>Page count and structural information</li>
 *   <li>Presentation detection (landscape, low text density, creator tools)</li>
 * </ul>
 *
 * <p>Handles encrypted and corrupted PDFs gracefully by returning
 * minimal metadata without text content.
 */
public class PdfAnalyzer implements FileAnalyzer {

    /** Maximum characters to extract from PDF text for indexing. */
    private static final int MAX_TEXT_LENGTH = 50_000;

    /** Maximum pages to attempt text extraction from. */
    private static final int MAX_PAGES_TO_EXTRACT = 100;

    /** Creator tools that indicate a presentation. */
    private static final Set<String> PRESENTATION_CREATORS = Set.of(
            "impress", "keynote", "powerpoint", "google slides",
            "libreoffice impress", "microsoft powerpoint",
            "prezi", "canva", "slides", "beamer"
    );

    /** Creator tools that indicate a spreadsheet. */
    private static final Set<String> SPREADSHEET_CREATORS = Set.of(
            "excel", "calc", "google sheets", "libreoffice calc",
            "microsoft excel", "numbers"
    );

    @Override
    public boolean canAnalyze(FileMetadata metadata) {
        return metadata.fileType() == FileUtils.FileType.PDF;
    }

    @Override
    public AnalysisResult analyze(FileMetadata metadata) throws IOException {
        try (PDDocument document = Loader.loadPDF(metadata.path().toFile())) {
            PDDocumentInformation info = document.getDocumentInformation();
            int pageCount = document.getNumberOfPages();

            // Extract metadata
            String title = safeString(info.getTitle());
            String author = safeString(info.getAuthor());
            String subject = safeString(info.getSubject());
            String creator = safeString(info.getCreator());
            String producer = safeString(info.getProducer());

            // Extract text content for indexing
            String textContent = extractText(document, pageCount);

            // Detect media type (presentation, document, spreadsheet)
            String mediaType = detectMediaType(document, pageCount, textContent, creator, producer);

            // Build summary
            StringBuilder summaryBuilder = new StringBuilder();
            if (!"document".equals(mediaType)) {
                summaryBuilder.append("PDF ").append(mediaType);
            } else {
                summaryBuilder.append("PDF document");
            }
            if (!title.isEmpty()) {
                summaryBuilder.append(": ").append(truncate(title, 120));
            }
            summaryBuilder.append(" (").append(pageCount).append(pageCount == 1 ? " page" : " pages").append(")");
            if (!author.isEmpty()) {
                summaryBuilder.append(" by ").append(truncate(author, 60));
            }
            String summary = summaryBuilder.toString();

            // Keywords from metadata
            List<String> keywords = new ArrayList<>();
            keywords.add("pdf");
            keywords.add(mediaType);
            if (!title.isEmpty()) {
                Arrays.stream(title.split("\\s+"))
                        .filter(w -> w.length() > 2)
                        .map(String::toLowerCase)
                        .limit(10)
                        .forEach(keywords::add);
            }
            if (!subject.isEmpty()) {
                keywords.add(subject.toLowerCase());
            }
            if (!author.isEmpty()) {
                keywords.add(author.toLowerCase());
            }

            // Headings: title + first few lines that look like headings
            List<String> headings = new ArrayList<>();
            if (!title.isEmpty()) {
                headings.add(title);
            }
            extractPotentialHeadings(textContent, headings);

            // Structure
            StringBuilder structBuilder = new StringBuilder();
            structBuilder.append("PDF ").append(mediaType);
            structBuilder.append(", ").append(pageCount).append(" pages");
            structBuilder.append(", ").append(FileUtils.formatSize(metadata.sizeBytes()));
            if (!author.isEmpty()) structBuilder.append(", author: ").append(author);
            if (!creator.isEmpty()) structBuilder.append(", creator: ").append(creator);

            // Page dimensions
            PageInfo pageInfo = analyzePages(document);
            if (pageInfo.isLandscape) {
                structBuilder.append(", landscape");
            }
            if (pageInfo.width > 0 && pageInfo.height > 0) {
                structBuilder.append(String.format(", %.0fx%.0fpt", pageInfo.width, pageInfo.height));
            }

            String structure = structBuilder.toString();

            // Metrics
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("pageCount", pageCount);
            metrics.put("textLength", textContent.length());
            metrics.put("mediaType", mediaType);
            if (!title.isEmpty()) metrics.put("title", title);
            if (!author.isEmpty()) metrics.put("author", author);
            if (!creator.isEmpty()) metrics.put("creator", creator);
            if (pageInfo.isLandscape) metrics.put("landscape", true);
            if (pageInfo.textDensity > 0) {
                metrics.put("textDensityPerPage", Math.round(pageInfo.textDensity));
            }

            return AnalysisResult.builder()
                    .summary(summary)
                    .headings(headings)
                    .keywords(keywords)
                    .structure(structure)
                    .metrics(metrics)
                    .contentPreview(textContent)
                    .build();

        } catch (IOException e) {
            // PDF is encrypted, corrupted, or password-protected
            return AnalysisResult.builder()
                    .summary("PDF document (unreadable: " + e.getMessage() + ")")
                    .keywords(List.of("pdf"))
                    .structure("PDF, unreadable")
                    .build();
        }
    }

    /**
     * Detects the media type of a PDF: presentation, document, or spreadsheet.
     *
     * <p>Uses multiple heuristics:
     * <ol>
     *   <li>Creator tool (PowerPoint, Keynote, etc.)</li>
     *   <li>Page orientation (landscape = likely presentation)</li>
     *   <li>Text density (low text per page = likely slides)</li>
     *   <li>Page count patterns (5-60 pages with low text = presentation)</li>
     * </ol>
     *
     * @return "presentation", "spreadsheet", or "document"
     */
    String detectMediaType(PDDocument document, int pageCount,
                           String textContent, String creator, String producer) {
        int score = 0;

        // Heuristic 1: Creator tool detection
        String creatorLower = creator.toLowerCase();
        String producerLower = producer.toLowerCase();

        for (String tool : PRESENTATION_CREATORS) {
            if (creatorLower.contains(tool) || producerLower.contains(tool)) {
                return "presentation"; // Strong signal -- definitive
            }
        }
        for (String tool : SPREADSHEET_CREATORS) {
            if (creatorLower.contains(tool) || producerLower.contains(tool)) {
                return "spreadsheet";
            }
        }

        // Heuristic 2: Page orientation
        PageInfo pageInfo = analyzePages(document);
        if (pageInfo.isLandscape) {
            score += 3; // Landscape is a strong presentation signal
        }

        // Heuristic 3: Text density per page
        if (pageCount > 0 && !textContent.isEmpty()) {
            double charsPerPage = (double) textContent.length() / pageCount;

            // Presentations typically have < 300 chars per page
            if (charsPerPage < 200) {
                score += 3;
            } else if (charsPerPage < 500) {
                score += 2;
            } else if (charsPerPage < 800) {
                score += 1;
            }
            // Documents typically have > 1500 chars per page
        }

        // Heuristic 4: Page count (presentations are typically 5-100 slides)
        if (pageCount >= 5 && pageCount <= 100) {
            score += 1;
        }

        // Heuristic 5: Size per page ratio (presentations are image-heavy, larger per page)
        if (pageCount > 0) {
            // Large KB/page ratio suggests embedded images (typical for slides)
            // We don't have sizeBytes here, but text density is already captured above
        }

        // Score threshold
        if (score >= 4) {
            return "presentation";
        }

        return "document";
    }

    /**
     * Analyzes page properties of the PDF.
     */
    PageInfo analyzePages(PDDocument document) {
        int pageCount = document.getNumberOfPages();
        if (pageCount == 0) {
            return new PageInfo(false, 0, 0, 0);
        }

        int landscapeCount = 0;
        float totalWidth = 0;
        float totalHeight = 0;

        int pagesToCheck = Math.min(pageCount, 10);
        for (int i = 0; i < pagesToCheck; i++) {
            PDPage page = document.getPage(i);
            PDRectangle mediaBox = page.getMediaBox();
            float width = mediaBox.getWidth();
            float height = mediaBox.getHeight();

            totalWidth += width;
            totalHeight += height;

            if (width > height) {
                landscapeCount++;
            }
        }

        boolean isLandscape = landscapeCount > pagesToCheck / 2;
        float avgWidth = totalWidth / pagesToCheck;
        float avgHeight = totalHeight / pagesToCheck;

        return new PageInfo(isLandscape, avgWidth, avgHeight, 0);
    }

    /**
     * Extracts text content from the PDF document.
     * Limits extraction to avoid memory issues with very large PDFs.
     */
    private String extractText(PDDocument document, int pageCount) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            int maxPage = Math.min(pageCount, MAX_PAGES_TO_EXTRACT);
            stripper.setEndPage(maxPage);

            String text = stripper.getText(document);

            if (text.length() > MAX_TEXT_LENGTH) {
                text = text.substring(0, MAX_TEXT_LENGTH);
            }

            return text.strip();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Extracts potential headings from the first part of the text.
     */
    private void extractPotentialHeadings(String text, List<String> headings) {
        if (text.isEmpty()) return;

        String[] lines = text.split("\n");
        int count = 0;
        for (String line : lines) {
            if (count >= 10) break;

            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.length() > 100) continue;

            if (count < 5 && !trimmed.endsWith(".") && !trimmed.endsWith(",")
                    && trimmed.length() < 80) {
                headings.add(trimmed);
            }
            count++;
        }
    }

    private static String safeString(String value) {
        return value != null ? value.strip() : "";
    }

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * Page analysis results.
     */
    record PageInfo(boolean isLandscape, float width, float height, double textDensity) {}
}
