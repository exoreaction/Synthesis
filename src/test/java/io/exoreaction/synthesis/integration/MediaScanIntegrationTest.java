package io.exoreaction.synthesis.integration;

import io.exoreaction.synthesis.analyzer.*;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.index.DocumentFields;
import io.exoreaction.synthesis.index.FileIndexer;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying the full scan pipeline with media files:
 * file classification -> analysis -> indexing -> search.
 */
class MediaScanIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void testImageScanAndSearch() throws IOException {
        // Create test image
        Path imgFile = tempDir.resolve("diagram.png");
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", imgFile.toFile());

        // Classify
        assertEquals(FileUtils.FileType.IMAGE, FileUtils.classifyFile(imgFile));

        // Analyze
        FileMetadata metadata = FileMetadata.of(imgFile, tempDir, Files.size(imgFile),
                Instant.now(), "hash1");
        AnalyzerRegistry registry = new AnalyzerRegistry();
        AnalysisResult result = registry.analyze(metadata);

        assertTrue(result.summary().contains("Image"));
        assertTrue(result.keywords().contains("image"));

        // Index and search
        Path indexDir = tempDir.resolve(".synthesis/index");
        try (SearchIndex index = new SearchIndex(indexDir)) {
            FileIndexer indexer = new FileIndexer();
            index.addDocument(indexer.createDocument(metadata, result));
            index.commit();

            // Search by keyword
            List<SearchResult> results = index.search("image diagram", 10);
            assertFalse(results.isEmpty(), "Should find image by keyword search");

            // Search by file type filter
            List<SearchResult> imageResults = index.search("diagram", "IMAGE", 10);
            assertFalse(imageResults.isEmpty(), "Should find when filtered by IMAGE type");

            // Ensure it's NOT found under CODE type
            List<SearchResult> codeResults = index.search("diagram", "CODE", 10);
            assertTrue(codeResults.isEmpty(), "Should NOT find image under CODE type");
        }
    }

    @Test
    void testVideoWithTranscriptScanAndSearch() throws IOException {
        // Create test video (just a small binary file)
        Path videoFile = tempDir.resolve("demo.mp4");
        Files.write(videoFile, new byte[]{0x00, 0x00, 0x00, 0x1C, 0x66, 0x74, 0x79, 0x70});

        // Create companion transcript
        Path transcript = tempDir.resolve("demo.txt");
        Files.writeString(transcript, "Welcome to the AI development workshop. " +
                "Today we discuss Skill-Driven Development methodology.");

        // Classify
        assertEquals(FileUtils.FileType.VIDEO, FileUtils.classifyFile(videoFile));

        // Analyze
        FileMetadata metadata = FileMetadata.of(videoFile, tempDir, Files.size(videoFile),
                Instant.now(), "hash1");
        AnalyzerRegistry registry = new AnalyzerRegistry();
        AnalysisResult result = registry.analyze(metadata);

        assertTrue(result.keywords().contains("has-transcript"));
        assertTrue(result.contentPreview().contains("Skill-Driven Development"));

        // Index and search for transcript content
        Path indexDir = tempDir.resolve(".synthesis/index");
        try (SearchIndex index = new SearchIndex(indexDir)) {
            FileIndexer indexer = new FileIndexer();
            index.addDocument(indexer.createDocument(metadata, result));
            index.commit();

            // Should find video by searching for transcript content
            List<SearchResult> results = index.search("Skill-Driven Development", 10);
            assertFalse(results.isEmpty(), "Should find video via transcript content");
            assertEquals("demo.mp4", results.get(0).fileName());
        }
    }

    @Test
    void testPdfPresentationDetectionAndSearch() throws IOException {
        // Create a presentation-like PDF (PowerPoint creator)
        Path pdfFile = tempDir.resolve("quarterly-review.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("Q4 2025 Quarterly Review");
            info.setCreator("Microsoft PowerPoint");

            PDRectangle landscape = new PDRectangle(
                    PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());

            for (int i = 0; i < 10; i++) {
                PDPage page = new PDPage(landscape);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 24);
                    cs.newLineAtOffset(200, 350);
                    cs.showText("Slide " + (i + 1));
                    cs.endText();
                }
            }

            doc.save(pdfFile.toFile());
        }

        // Classify
        assertEquals(FileUtils.FileType.PDF, FileUtils.classifyFile(pdfFile));

        // Analyze
        FileMetadata metadata = FileMetadata.of(pdfFile, tempDir, Files.size(pdfFile),
                Instant.now(), "hash1");
        PdfAnalyzer analyzer = new PdfAnalyzer();
        AnalysisResult result = analyzer.analyze(metadata);

        assertTrue(result.keywords().contains("presentation"),
                "PowerPoint PDF should be detected as presentation");
        assertEquals("presentation", result.metrics().get("mediaType"));
        assertTrue(result.summary().contains("presentation"));

        // Index and search with media type filter
        Path indexDir = tempDir.resolve(".synthesis/index");
        try (SearchIndex index = new SearchIndex(indexDir)) {
            FileIndexer indexer = new FileIndexer();
            index.addDocument(indexer.createDocument(metadata, result));
            index.commit();

            // Search with media type filter
            List<SearchResult> presentations = index.searchWithMediaType(
                    "quarterly review", null, null, "presentation",
                    null, null, 10);
            assertFalse(presentations.isEmpty(),
                    "Should find PDF when filtering by media type 'presentation'");

            // Should NOT find it as a spreadsheet
            List<SearchResult> spreadsheets = index.searchWithMediaType(
                    "quarterly review", null, null, "spreadsheet",
                    null, null, 10);
            assertTrue(spreadsheets.isEmpty(),
                    "Should NOT find presentation when filtering by 'spreadsheet'");
        }
    }

    @Test
    void testSvgAnalysisInPipeline() throws IOException {
        // SVG is an IMAGE type but text-based
        Path svgFile = tempDir.resolve("architecture.svg");
        Files.writeString(svgFile, """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 800">
                    <rect width="1200" height="800" fill="#f5f5f5"/>
                    <text x="600" y="100" text-anchor="middle" font-size="32">
                        System Architecture
                    </text>
                    <rect x="100" y="200" width="200" height="100" fill="#4CAF50"/>
                    <text x="200" y="260" text-anchor="middle">API Gateway</text>
                </svg>
                """);

        // Should be classified as IMAGE
        assertEquals(FileUtils.FileType.IMAGE, FileUtils.classifyFile(svgFile));

        // Analyze
        FileMetadata metadata = FileMetadata.of(svgFile, tempDir, Files.size(svgFile),
                Instant.now(), "hash1");
        ImageAnalyzer analyzer = new ImageAnalyzer();
        AnalysisResult result = analyzer.analyze(metadata);

        assertTrue(result.summary().contains("SVG"));
        assertTrue(result.keywords().contains("vector"));

        // SVG content should be searchable (text-based)
        assertTrue(result.contentPreview().contains("System Architecture"));
        assertTrue(result.contentPreview().contains("API Gateway"));
    }

    @Test
    void testMixedMediaWorkspace() throws IOException {
        // Create multiple file types
        Files.writeString(tempDir.resolve("README.md"), "# My Project\nA test project.");

        Path imgFile = tempDir.resolve("logo.png");
        ImageIO.write(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB),
                "png", imgFile.toFile());

        Files.write(tempDir.resolve("demo.mp4"), new byte[]{0x00, 0x00});
        Files.write(tempDir.resolve("podcast.mp3"), new byte[]{(byte) 0xFF, (byte) 0xFB});

        // Verify all classify correctly
        assertEquals(FileUtils.FileType.MARKDOWN, FileUtils.classifyFile(tempDir.resolve("README.md")));
        assertEquals(FileUtils.FileType.IMAGE, FileUtils.classifyFile(tempDir.resolve("logo.png")));
        assertEquals(FileUtils.FileType.VIDEO, FileUtils.classifyFile(tempDir.resolve("demo.mp4")));
        assertEquals(FileUtils.FileType.AUDIO, FileUtils.classifyFile(tempDir.resolve("podcast.mp3")));

        // Verify all can be analyzed
        AnalyzerRegistry registry = new AnalyzerRegistry();

        for (String filename : List.of("README.md", "logo.png", "demo.mp4", "podcast.mp3")) {
            Path file = tempDir.resolve(filename);
            FileMetadata metadata = FileMetadata.of(file, tempDir, Files.size(file),
                    Instant.now(), "hash-" + filename);
            AnalysisResult result = registry.analyze(metadata);
            assertNotNull(result, "Analysis should succeed for " + filename);
            assertFalse(result.summary().isEmpty(),
                    "Summary should not be empty for " + filename);
        }
    }

    @Test
    void testCompanionFileDetection() throws IOException {
        // Test the companion file patterns
        Path videoFile = tempDir.resolve("talk.mp4");
        Files.write(videoFile, new byte[]{0x00});

        // Test different companion extensions
        String[] extensions = {".txt", ".srt", ".vtt", ".md"};
        for (String ext : extensions) {
            // Clean up previous companions
            for (String prevExt : extensions) {
                Path prev = tempDir.resolve("talk" + prevExt);
                Files.deleteIfExists(prev);
            }

            // Create this companion
            Path companion = tempDir.resolve("talk" + ext);
            Files.writeString(companion, "Companion content for " + ext);

            FileMetadata metadata = FileMetadata.of(videoFile, tempDir, Files.size(videoFile),
                    Instant.now(), "hash1");
            VideoAnalyzer analyzer = new VideoAnalyzer();
            AnalysisResult result = analyzer.analyze(metadata);

            assertTrue(result.keywords().contains("has-transcript"),
                    "Should detect companion " + ext);
            assertTrue(result.contentPreview().contains("Companion content"),
                    "Should read companion " + ext + " content");
        }
    }

    @Test
    void testFileTypeIsMediaHelper() {
        assertTrue(FileUtils.FileType.IMAGE.isMedia());
        assertTrue(FileUtils.FileType.VIDEO.isMedia());
        assertTrue(FileUtils.FileType.AUDIO.isMedia());
        assertFalse(FileUtils.FileType.CODE.isMedia());
        assertFalse(FileUtils.FileType.MARKDOWN.isMedia());
        assertFalse(FileUtils.FileType.PDF.isMedia());
        assertFalse(FileUtils.FileType.YAML.isMedia());
        assertFalse(FileUtils.FileType.JSON.isMedia());
        assertFalse(FileUtils.FileType.BINARY.isMedia());
        assertFalse(FileUtils.FileType.DOCUMENT.isMedia());
    }

    @Test
    void testFileTypeIsAnalyzable() {
        assertTrue(FileUtils.FileType.IMAGE.isAnalyzable());
        assertTrue(FileUtils.FileType.VIDEO.isAnalyzable());
        assertTrue(FileUtils.FileType.AUDIO.isAnalyzable());
        assertTrue(FileUtils.FileType.CODE.isAnalyzable());
        assertTrue(FileUtils.FileType.PDF.isAnalyzable());
        assertTrue(FileUtils.FileType.DOCUMENT.isAnalyzable());
        assertFalse(FileUtils.FileType.BINARY.isAnalyzable());
        assertFalse(FileUtils.FileType.OTHER.isAnalyzable());
    }
}
