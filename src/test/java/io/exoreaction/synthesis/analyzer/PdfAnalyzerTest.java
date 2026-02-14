package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PdfAnalyzer -- PDF analysis and presentation detection.
 */
class PdfAnalyzerTest {

    @TempDir
    Path tempDir;

    private final PdfAnalyzer analyzer = new PdfAnalyzer();

    @Test
    void testCanAnalyzePdfFiles() {
        FileMetadata pdfMd = createMetadata("report.pdf", FileUtils.FileType.PDF);
        FileMetadata codeMd = createMetadata("App.java", FileUtils.FileType.CODE);
        FileMetadata imgMd = createMetadata("photo.jpg", FileUtils.FileType.IMAGE);

        assertTrue(analyzer.canAnalyze(pdfMd));
        assertFalse(analyzer.canAnalyze(codeMd));
        assertFalse(analyzer.canAnalyze(imgMd));
    }

    @Test
    void testDocumentPdfAnalysis() throws IOException {
        // Create a simple document PDF (portrait, lots of text)
        Path pdfFile = createDocumentPdf("report.pdf", "Annual Report 2026", "John Doe",
                PDRectangle.A4, 5, true);

        FileMetadata fm = FileMetadata.of(pdfFile, tempDir, Files.size(pdfFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("PDF document"));
        assertTrue(result.summary().contains("Annual Report 2026"));
        assertTrue(result.summary().contains("5 pages"));
        assertTrue(result.keywords().contains("pdf"));
        assertTrue(result.keywords().contains("document"));
        assertEquals("document", result.metrics().get("mediaType"));
    }

    @Test
    void testPresentationDetectionByCreator() throws IOException {
        // Create a PDF with PowerPoint as creator
        Path pdfFile = createPdfWithCreator("slides.pdf", "Microsoft PowerPoint", 10);

        FileMetadata fm = FileMetadata.of(pdfFile, tempDir, Files.size(pdfFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("presentation"),
                "Should detect as presentation: " + result.summary());
        assertTrue(result.keywords().contains("presentation"));
        assertEquals("presentation", result.metrics().get("mediaType"));
    }

    @Test
    void testPresentationDetectionByKeynote() throws IOException {
        Path pdfFile = createPdfWithCreator("keynote-slides.pdf", "Keynote", 15);

        FileMetadata fm = FileMetadata.of(pdfFile, tempDir, Files.size(pdfFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertEquals("presentation", result.metrics().get("mediaType"));
    }

    @Test
    void testPresentationDetectionByGoogleSlides() throws IOException {
        Path pdfFile = createPdfWithCreator("google-deck.pdf", "Google Slides", 20);

        FileMetadata fm = FileMetadata.of(pdfFile, tempDir, Files.size(pdfFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertEquals("presentation", result.metrics().get("mediaType"));
    }

    @Test
    void testPresentationDetectionByLandscapeAndLowText() throws IOException {
        // Create a landscape PDF with minimal text (typical presentation)
        Path pdfFile = createLandscapePdf("slides-heuristic.pdf", 10, false);

        FileMetadata fm = FileMetadata.of(pdfFile, tempDir, Files.size(pdfFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertEquals("presentation", result.metrics().get("mediaType"),
                "Landscape + low text should be detected as presentation");
        assertTrue(result.metrics().containsKey("landscape"));
    }

    @Test
    void testSpreadsheetDetectionByCreator() throws IOException {
        Path pdfFile = createPdfWithCreator("data.pdf", "Microsoft Excel", 3);

        FileMetadata fm = FileMetadata.of(pdfFile, tempDir, Files.size(pdfFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertEquals("spreadsheet", result.metrics().get("mediaType"));
    }

    @Test
    void testPageAnalysisPortrait() throws IOException {
        Path pdfFile = createDocumentPdf("portrait.pdf", "Test", "",
                PDRectangle.A4, 3, false);

        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdfFile.toFile())) {
            PdfAnalyzer.PageInfo info = analyzer.analyzePages(doc);
            assertFalse(info.isLandscape(), "A4 portrait should not be landscape");
            assertTrue(info.width() > 0);
            assertTrue(info.height() > 0);
            assertTrue(info.height() > info.width(), "A4 portrait: height > width");
        }
    }

    @Test
    void testPageAnalysisLandscape() throws IOException {
        Path pdfFile = createLandscapePdf("landscape.pdf", 3, false);

        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdfFile.toFile())) {
            PdfAnalyzer.PageInfo info = analyzer.analyzePages(doc);
            assertTrue(info.isLandscape(), "Should detect landscape orientation");
            assertTrue(info.width() > info.height(), "Landscape: width > height");
        }
    }

    @Test
    void testStructureIncludesMediaType() throws IOException {
        Path pdfFile = createDocumentPdf("structured.pdf", "Report", "",
                PDRectangle.A4, 2, true);

        FileMetadata fm = FileMetadata.of(pdfFile, tempDir, Files.size(pdfFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.structure().contains("PDF document"),
                "Structure should include media type: " + result.structure());
        assertTrue(result.structure().contains("2 pages"));
    }

    @Test
    void testUnreadablePdf() throws IOException {
        // Write garbage bytes as a "PDF"
        Path badPdf = tempDir.resolve("corrupted.pdf");
        Files.write(badPdf, new byte[]{0x00, 0x01, 0x02, 0x03});

        FileMetadata fm = FileMetadata.of(badPdf, tempDir, Files.size(badPdf),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertNotNull(result);
        assertTrue(result.summary().contains("unreadable"));
    }

    // --- Helper methods to create test PDFs ---

    private Path createDocumentPdf(String filename, String title, String author,
                                    PDRectangle pageSize, int pages, boolean withText)
            throws IOException {
        Path pdfFile = tempDir.resolve(filename);

        try (PDDocument doc = new PDDocument()) {
            PDDocumentInformation info = doc.getDocumentInformation();
            if (!title.isEmpty()) info.setTitle(title);
            if (!author.isEmpty()) info.setAuthor(author);

            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(pageSize);
                doc.addPage(page);

                if (withText) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        cs.newLineAtOffset(50, pageSize.getHeight() - 50);
                        cs.showText("This is page " + (i + 1) + " of " + pages +
                                ". It contains substantial text content that makes this look like a document " +
                                "rather than a presentation slide. Documents typically have longer paragraphs " +
                                "with more words per page than slides.");
                        cs.endText();
                    }
                }
            }

            doc.save(pdfFile.toFile());
        }
        return pdfFile;
    }

    private Path createPdfWithCreator(String filename, String creator, int pages)
            throws IOException {
        Path pdfFile = tempDir.resolve(filename);

        try (PDDocument doc = new PDDocument()) {
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setCreator(creator);

            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage(PDRectangle.A4));
            }

            doc.save(pdfFile.toFile());
        }
        return pdfFile;
    }

    private Path createLandscapePdf(String filename, int pages, boolean withText)
            throws IOException {
        Path pdfFile = tempDir.resolve(filename);

        // Landscape: width > height (swap A4 dimensions)
        PDRectangle landscape = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());

        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(landscape);
                doc.addPage(page);

                if (withText) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                        cs.newLineAtOffset(100, 300);
                        cs.showText("Slide " + (i + 1));
                        cs.endText();
                    }
                }
            }

            doc.save(pdfFile.toFile());
        }
        return pdfFile;
    }

    private FileMetadata createMetadata(String name, FileUtils.FileType type) {
        return new FileMetadata(
                tempDir.resolve(name), name, name,
                name.contains(".") ? name.substring(name.lastIndexOf('.')) : "",
                type, null, 100, Instant.now(), null
        );
    }
}
