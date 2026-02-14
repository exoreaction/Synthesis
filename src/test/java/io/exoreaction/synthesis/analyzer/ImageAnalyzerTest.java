package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ImageAnalyzer -- metadata extraction, classification, and SVG handling.
 */
class ImageAnalyzerTest {

    @TempDir
    Path tempDir;

    private final ImageAnalyzer analyzer = new ImageAnalyzer();

    @Test
    void testCanAnalyzeImageFiles() {
        FileMetadata imgMd = createMetadata("photo.jpg", FileUtils.FileType.IMAGE);
        FileMetadata codeMd = createMetadata("App.java", FileUtils.FileType.CODE);
        FileMetadata pdfMd = createMetadata("doc.pdf", FileUtils.FileType.PDF);

        assertTrue(analyzer.canAnalyze(imgMd));
        assertFalse(analyzer.canAnalyze(codeMd));
        assertFalse(analyzer.canAnalyze(pdfMd));
    }

    @Test
    void testPngAnalysis() throws IOException {
        // Create a minimal PNG image
        Path pngFile = tempDir.resolve("test.png");
        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", pngFile.toFile());

        FileMetadata fm = FileMetadata.of(pngFile, tempDir, Files.size(pngFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("Image"));
        assertTrue(result.summary().contains("800x600") || result.summary().contains("PNG"),
                "Summary should contain dimensions or format: " + result.summary());
        assertTrue(result.keywords().contains("image"));
        assertTrue(result.keywords().contains("png"));
        assertFalse(result.structure().isEmpty());

        // Metrics should contain dimensions
        assertNotNull(result.metrics().get("format"));
        assertEquals("PNG", result.metrics().get("format"));
    }

    @Test
    void testJpegAnalysis() throws IOException {
        // Create a minimal JPEG image
        Path jpgFile = tempDir.resolve("photo.jpg");
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "jpg", jpgFile.toFile());

        FileMetadata fm = FileMetadata.of(jpgFile, tempDir, Files.size(jpgFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("Image"));
        assertTrue(result.keywords().contains("image"));
        assertTrue(result.keywords().contains("jpg"));
    }

    @Test
    void testSvgAnalysis() throws IOException {
        Path svgFile = tempDir.resolve("diagram.svg");
        Files.writeString(svgFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 300" width="400" height="300">
                    <rect x="10" y="10" width="380" height="280" fill="#eee" />
                    <text x="200" y="150" text-anchor="middle">Hello SVG</text>
                    <circle cx="200" cy="200" r="50" fill="blue" />
                </svg>
                """);

        FileMetadata fm = FileMetadata.of(svgFile, tempDir, Files.size(svgFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("SVG"));
        assertTrue(result.summary().contains("400x300"));
        assertTrue(result.keywords().contains("svg"));
        assertTrue(result.keywords().contains("vector"));
        assertTrue(result.keywords().contains("text"), "Should detect text elements");
        assertTrue(result.keywords().contains("shapes"), "Should detect shape elements");
        // SVG content should be in preview (text-based)
        assertFalse(result.contentPreview().isEmpty());
    }

    @Test
    void testSvgWithSingleQuoteAttributes() throws IOException {
        Path svgFile = tempDir.resolve("icon.svg");
        Files.writeString(svgFile, """
                <svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'>
                    <path d='M12 2L2 7l10 5 10-5-10-5z'/>
                </svg>
                """);

        FileMetadata fm = FileMetadata.of(svgFile, tempDir, Files.size(svgFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("SVG"));
        assertTrue(result.keywords().contains("path"));
    }

    @Test
    void testClassifyImageIcon() {
        assertEquals("icon", ImageAnalyzer.classifyImage(32, 32, 1000));
        assertEquals("icon", ImageAnalyzer.classifyImage(64, 64, 5000));
        assertEquals("icon", ImageAnalyzer.classifyImage(128, 128, 10000));
    }

    @Test
    void testClassifyImageThumbnail() {
        assertEquals("thumbnail", ImageAnalyzer.classifyImage(200, 200, 10000));
        assertEquals("thumbnail", ImageAnalyzer.classifyImage(256, 256, 20000));
    }

    @Test
    void testClassifyImageScreenshot() {
        assertEquals("screenshot", ImageAnalyzer.classifyImage(1920, 1080, 500000));
        assertEquals("screenshot", ImageAnalyzer.classifyImage(2560, 1440, 1000000));
        assertEquals("screenshot", ImageAnalyzer.classifyImage(3840, 2160, 2000000));
    }

    @Test
    void testClassifyImagePhoto() {
        assertEquals("photo", ImageAnalyzer.classifyImage(4000, 3000, 2000000));
        assertEquals("photo", ImageAnalyzer.classifyImage(6000, 4000, 5000000));
    }

    @Test
    void testClassifyImageBanner() {
        assertEquals("banner", ImageAnalyzer.classifyImage(1200, 100, 50000));
    }

    @Test
    void testClassifyImageSquare() {
        assertEquals("square", ImageAnalyzer.classifyImage(500, 500, 100000));
    }

    @Test
    void testClassifyImageDiagram() {
        assertEquals("diagram", ImageAnalyzer.classifyImage(800, 600, 100000));
    }

    @Test
    void testClassifyImageZeroDimensions() {
        assertEquals("", ImageAnalyzer.classifyImage(0, 0, 1000));
        assertEquals("", ImageAnalyzer.classifyImage(0, 600, 1000));
    }

    @Test
    void testExtractAttribute() {
        assertEquals("0 0 24 24", ImageAnalyzer.extractAttribute(
                "<svg viewBox=\"0 0 24 24\">", "viewBox"));
        assertEquals("24", ImageAnalyzer.extractAttribute(
                "<svg width=\"24\" height=\"24\">", "width"));
        assertEquals("24", ImageAnalyzer.extractAttribute(
                "<svg width='24' height='24'>", "width"));
        assertEquals("", ImageAnalyzer.extractAttribute(
                "<svg>", "viewBox"));
    }

    @Test
    void testCorruptedImageGracefulDegradation() throws IOException {
        // Write random bytes as an "image"
        Path badFile = tempDir.resolve("corrupt.jpg");
        Files.write(badFile, new byte[]{0x00, 0x01, 0x02, 0x03, 0x04});

        FileMetadata fm = FileMetadata.of(badFile, tempDir, Files.size(badFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        // Should not throw, should return minimal result
        assertNotNull(result);
        assertFalse(result.summary().isEmpty());
        assertTrue(result.keywords().contains("image"));
    }

    @Test
    void testGifAnalysis() throws IOException {
        // Create a minimal GIF image
        Path gifFile = tempDir.resolve("animation.gif");
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "gif", gifFile.toFile());

        FileMetadata fm = FileMetadata.of(gifFile, tempDir, Files.size(gifFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("Image"));
        assertTrue(result.keywords().contains("gif"));
    }

    @Test
    void testFileTypeClassificationForImages() {
        // Verify that image extensions are now classified as IMAGE, not BINARY
        assertEquals(FileUtils.FileType.IMAGE, FileUtils.classifyFile(Path.of("photo.jpg")));
        assertEquals(FileUtils.FileType.IMAGE, FileUtils.classifyFile(Path.of("icon.png")));
        assertEquals(FileUtils.FileType.IMAGE, FileUtils.classifyFile(Path.of("diagram.svg")));
        assertEquals(FileUtils.FileType.IMAGE, FileUtils.classifyFile(Path.of("banner.webp")));
        assertEquals(FileUtils.FileType.IMAGE, FileUtils.classifyFile(Path.of("scan.tiff")));
    }

    @Test
    void testFileTypeClassificationForVideo() {
        assertEquals(FileUtils.FileType.VIDEO, FileUtils.classifyFile(Path.of("demo.mp4")));
        assertEquals(FileUtils.FileType.VIDEO, FileUtils.classifyFile(Path.of("recording.mov")));
        assertEquals(FileUtils.FileType.VIDEO, FileUtils.classifyFile(Path.of("stream.webm")));
    }

    @Test
    void testFileTypeClassificationForAudio() {
        assertEquals(FileUtils.FileType.AUDIO, FileUtils.classifyFile(Path.of("podcast.mp3")));
        assertEquals(FileUtils.FileType.AUDIO, FileUtils.classifyFile(Path.of("music.flac")));
        assertEquals(FileUtils.FileType.AUDIO, FileUtils.classifyFile(Path.of("voice.wav")));
    }

    @Test
    void testFileTypeIsMedia() {
        assertTrue(FileUtils.FileType.IMAGE.isMedia());
        assertTrue(FileUtils.FileType.VIDEO.isMedia());
        assertTrue(FileUtils.FileType.AUDIO.isMedia());
        assertFalse(FileUtils.FileType.CODE.isMedia());
        assertFalse(FileUtils.FileType.PDF.isMedia());
        assertFalse(FileUtils.FileType.MARKDOWN.isMedia());
    }

    private FileMetadata createMetadata(String name, FileUtils.FileType type) {
        return new FileMetadata(
                tempDir.resolve(name), name, name,
                name.contains(".") ? name.substring(name.lastIndexOf('.')) : "",
                type, null, 100, Instant.now(), null
        );
    }
}
