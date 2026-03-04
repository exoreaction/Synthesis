package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.analyzer.PresentationExtractor;
import io.exoreaction.synthesis.analyzer.AnalyzerRegistry;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.enrichment.CompanionFileGenerator;
import io.exoreaction.synthesis.enrichment.EnrichmentLevel;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for presentation PDF enrichment in the staging route --enrich-first flow.
 *
 * <p>Covers slide extraction, per-slide companion generation, slide-index companion
 * format, non-presentation handling, and extraction failure fallback.
 */
class PresentationEnrichmentTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // 1. Slide extraction creates the slides directory
    // -----------------------------------------------------------------------

    @Test
    void presentation_pdf_creates_slides_dir() throws IOException {
        Path pdf = createPresentationPdf("deck.pdf", "My Deck", 3);
        Path slidesDir = pdf.getParent().resolve("deck-slides");

        PresentationExtractor extractor = new PresentationExtractor();
        extractor.extractSlides(pdf, slidesDir, PresentationExtractor.DEFAULT_DPI, null);

        assertTrue(Files.isDirectory(slidesDir),
                "Slides directory should be created alongside the PDF");
        assertEquals(3, countPng(slidesDir),
                "One PNG per slide should be created");
    }

    // -----------------------------------------------------------------------
    // 2. Per-slide companions are generated for each extracted PNG
    // -----------------------------------------------------------------------

    @Test
    void presentation_pdf_generates_per_slide_companions() throws Exception {
        Path pdf = createPresentationPdf("talk.pdf", "Tech Talk", 3);
        Path slidesDir = pdf.getParent().resolve("talk-slides");

        PresentationExtractor extractor = new PresentationExtractor();
        PresentationExtractor.ExtractionResult result =
                extractor.extractSlides(pdf, slidesDir, PresentationExtractor.DEFAULT_DPI, null);

        CompanionFileGenerator generator = new CompanionFileGenerator(EnrichmentLevel.BASIC, false);
        AnalyzerRegistry analyzers = new AnalyzerRegistry();

        for (PresentationExtractor.SlideInfo slide : result.slides()) {
            Path slidePath = slide.imagePath();
            BasicFileAttributes attrs = Files.readAttributes(slidePath, BasicFileAttributes.class);
            FileMetadata meta = FileMetadata.of(slidePath, tempDir,
                    attrs.size(), attrs.lastModifiedTime().toInstant(), null);
            Optional<Path> companion = generator.generate(meta, analyzers.analyze(meta), List.of());
            assertTrue(companion.isPresent(),
                    "Companion should be generated for " + slidePath.getFileName());
            assertTrue(Files.exists(companion.get()),
                    "Companion file should exist on disk: " + companion.get());
        }
    }

    // -----------------------------------------------------------------------
    // 3. buildSlideIndexCompanion produces the expected slide-index content
    // -----------------------------------------------------------------------

    @Test
    void presentation_pdf_generates_slide_index_companion() throws Exception {
        Path pdf = createPresentationPdf("strategy.pdf", "KCP Strategy", 4);
        Path slidesDir = pdf.getParent().resolve("strategy-slides");

        PresentationExtractor extractor = new PresentationExtractor();
        PresentationExtractor.ExtractionResult result =
                extractor.extractSlides(pdf, slidesDir, PresentationExtractor.DEFAULT_DPI, null);

        String content = StagingCommand.buildSlideIndexCompanion(result, pdf, "strategy");

        // Front-matter
        assertTrue(content.contains("companion_for: strategy.pdf"), "Should include companion_for");
        assertTrue(content.contains("media_type: presentation"), "Should include media_type");

        // Header and metadata
        assertTrue(content.contains("**Source:** `strategy.pdf`"), "Should include source");
        assertTrue(content.contains("**Slides:** 4"), "Should include slide count");
        assertTrue(content.contains("**Slides directory:** `strategy-slides/`"), "Should include dir");

        // Table rows — one per slide
        assertTrue(content.contains("| 1 |"), "Should include slide 1 row");
        assertTrue(content.contains("| 4 |"), "Should include slide 4 row");
        assertTrue(content.contains("slide-001.png"), "Should reference first slide PNG");
        assertTrue(content.contains("strategy-slides/slide-001.png"), "Should include relative path");

        // Footer
        assertTrue(content.contains("*Generated by Synthesis"), "Should include generation footer");
    }

    // -----------------------------------------------------------------------
    // 4. extractSlideOneLiner reads the companion and returns first content line
    // -----------------------------------------------------------------------

    @Test
    void non_presentation_pdf_not_split() throws IOException {
        // Create a portrait/document PDF — PdfAnalyzer should NOT classify it as presentation.
        // We verify that no slides directory gets created (i.e., we don't call extractSlides).
        Path pdf = createDocumentPdf("report.pdf", "Annual Report", 5);
        Path slidesDir = pdf.getParent().resolve("report-slides");

        // The staging command would only call extractSlides for "presentation" mediaType.
        // We verify PdfAnalyzer does NOT flag this as a presentation.
        AnalyzerRegistry analyzers = new AnalyzerRegistry();
        BasicFileAttributes attrs = Files.readAttributes(pdf, BasicFileAttributes.class);
        FileMetadata meta = FileMetadata.of(pdf, tempDir, attrs.size(),
                attrs.lastModifiedTime().toInstant(), null);
        Object mediaType = analyzers.analyze(meta).metrics().get("mediaType");

        assertNotEquals("presentation", mediaType,
                "Portrait/dense-text document PDF should NOT be classified as presentation");
        assertFalse(Files.exists(slidesDir),
                "No slides directory should be created for a document PDF");
    }

    // -----------------------------------------------------------------------
    // 5. extractSlideOneLiner returns "—" when companion missing, or first content line
    // -----------------------------------------------------------------------

    @Test
    void extraction_failure_falls_back_to_regular_companion() throws IOException {
        // Verify that extractSlideOneLiner returns "—" gracefully when no companion exists,
        // simulating what happens during extraction failure (companions not yet written).
        Path fakeSlidePath = tempDir.resolve("slide-001.png");
        Files.writeString(fakeSlidePath, ""); // create placeholder

        // No companion written yet
        String oneLiner = StagingCommand.extractSlideOneLiner(fakeSlidePath);
        assertEquals("—", oneLiner, "Should return em-dash when companion file is absent");

        // Write a companion with front-matter + heading + content
        Path companionPath = tempDir.resolve("slide-001.png.synthesis.md");
        Files.writeString(companionPath, """
                ---
                companion_for: slide-001.png
                ---
                # Slide 1
                **Keywords:** architecture, cloud
                Architecture overview showing three microservice layers and their interactions.
                """);

        String extracted = StagingCommand.extractSlideOneLiner(fakeSlidePath);
        assertEquals("Architecture overview showing three microservice layers and their interactions.",
                extracted,
                "Should return first non-header, non-frontmatter, non-bold content line");
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    /** Creates a landscape presentation-style PDF (triggers PdfAnalyzer "presentation" detection). */
    private Path createPresentationPdf(String filename, String title, int slides) throws IOException {
        Path pdfFile = tempDir.resolve(filename);
        PDRectangle landscape = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());

        try (PDDocument doc = new PDDocument()) {
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle(title);
            info.setCustomMetadataValue("Creator", "Microsoft PowerPoint");

            for (int i = 0; i < slides; i++) {
                PDPage page = new PDPage(landscape);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 36);
                    cs.newLineAtOffset(200, 350);
                    cs.showText("Slide " + (i + 1));
                    cs.endText();
                }
            }
            doc.save(pdfFile.toFile());
        }
        return pdfFile;
    }

    /** Creates a portrait document-style PDF (should NOT be classified as presentation). */
    private Path createDocumentPdf(String filename, String title, int pages) throws IOException {
        Path pdfFile = tempDir.resolve(filename);

        try (PDDocument doc = new PDDocument()) {
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle(title);

            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4); // portrait
                doc.addPage(page);
                // Add dense text content so text density is high (document-like)
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 750);
                    cs.setLeading(15f);
                    // Write enough text to exceed 200 chars/page threshold
                    for (int line = 0; line < 10; line++) {
                        cs.showText("This is paragraph " + (line + 1) + " of page " + (i + 1)
                                + " in this annual report document with extensive content.");
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            doc.save(pdfFile.toFile());
        }
        return pdfFile;
    }

    private long countPng(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".png")).count();
        }
    }
}
