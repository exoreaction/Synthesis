package io.exoreaction.synthesis.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TesseractOcrExtractor.
 * Most tests require Tesseract to be installed and are only run if available.
 */
class TesseractOcrExtractorTest {

    @Test
    void testExtractorCreation() {
        // Should be able to create extractor with default settings
        TesseractOcrExtractor extractor = new TesseractOcrExtractor();
        assertNotNull(extractor);

        // Should be able to create extractor with custom language
        TesseractOcrExtractor customExtractor = new TesseractOcrExtractor("eng");
        assertNotNull(customExtractor);

        // Should be able to create extractor with custom PSM
        TesseractOcrExtractor psmExtractor = new TesseractOcrExtractor("eng", 6);
        assertNotNull(psmExtractor);
    }

    @Test
    void testExtractTextThrowsWhenTesseractNotAvailable() {
        if (TesseractDetector.isAvailable()) {
            // Skip this test if Tesseract is actually available
            return;
        }

        TesseractOcrExtractor extractor = new TesseractOcrExtractor();
        Path dummyFile = Path.of("/tmp/dummy.png");

        assertThrows(IllegalStateException.class, () -> {
            extractor.extractText(dummyFile);
        }, "Should throw IllegalStateException when Tesseract is not available");
    }

    @Test
    void testExtractTextThrowsOnMissingFile() {
        if (!TesseractDetector.isAvailable()) {
            return; // Skip if Tesseract not available
        }

        TesseractOcrExtractor extractor = new TesseractOcrExtractor();
        Path nonExistentFile = Path.of("/tmp/nonexistent-" + System.currentTimeMillis() + ".png");

        assertThrows(IOException.class, () -> {
            extractor.extractText(nonExistentFile);
        }, "Should throw IOException when file doesn't exist");
    }

    @Test
    void testOcrResultSuccess() {
        TesseractOcrExtractor.OcrResult result = new TesseractOcrExtractor.OcrResult(
                true,
                "This is extracted text",
                85,
                "eng",
                1000L,
                null
        );

        assertTrue(result.success());
        assertEquals("This is extracted text", result.text());
        assertEquals(85, result.confidence());
        assertEquals("eng", result.language());
        assertEquals(1000L, result.durationMs());
        assertNull(result.errorMessage());
        assertTrue(result.hasGoodConfidence());
    }

    @Test
    void testOcrResultFailed() {
        TesseractOcrExtractor.OcrResult result =
                TesseractOcrExtractor.OcrResult.failed("OCR error", 500L);

        assertFalse(result.success());
        assertNull(result.text());
        assertEquals(0, result.confidence());
        assertNull(result.language());
        assertEquals(500L, result.durationMs());
        assertEquals("OCR error", result.errorMessage());
        assertFalse(result.hasGoodConfidence());
    }

    @Test
    void testOcrResultLowConfidence() {
        TesseractOcrExtractor.OcrResult result = new TesseractOcrExtractor.OcrResult(
                true,
                "noisy text ###",
                25,  // Below MIN_CONFIDENCE threshold
                "eng",
                1000L,
                null
        );

        assertTrue(result.success());
        assertFalse(result.hasGoodConfidence(), "Low confidence should not be considered good");
    }

    @Test
    @EnabledIf("io.exoreaction.synthesis.util.TesseractDetector#isAvailable")
    void testGetAvailableLanguages() throws IOException {
        List<String> languages = TesseractOcrExtractor.getAvailableLanguages();

        assertNotNull(languages);
        assertFalse(languages.isEmpty(), "Should have at least one language installed");
        assertTrue(languages.contains("eng") || languages.contains("osd"),
                "Should have at least English or OSD installed");
    }

    @Test
    void testGetAvailableLanguagesWhenNotInstalled() throws IOException {
        if (TesseractDetector.isAvailable()) {
            return; // Skip if Tesseract is available
        }

        List<String> languages = TesseractOcrExtractor.getAvailableLanguages();
        assertNotNull(languages);
        assertTrue(languages.isEmpty(), "Should return empty list when Tesseract not available");
    }

    /**
     * Integration test that creates a simple text image and performs OCR.
     * Only runs if Tesseract and ImageMagick are available.
     */
    @Test
    @EnabledIf("io.exoreaction.synthesis.util.TesseractOcrExtractorTest#canRunIntegrationTest")
    void testExtractTextIntegration() throws IOException, InterruptedException {
        // Create a temporary text image using ImageMagick convert
        Path tempDir = Files.createTempDirectory("tesseract-test");
        Path imageFile = tempDir.resolve("test-text.png");

        try {
            // Generate a simple text image
            ProcessBuilder pb = new ProcessBuilder(
                    "convert",
                    "-size", "400x100",
                    "xc:white",
                    "-font", "DejaVu-Sans",
                    "-pointsize", "24",
                    "-fill", "black",
                    "-annotate", "+20+60", "Hello World",
                    imageFile.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes(); // consume output
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                // ImageMagick not available or failed, skip test
                return;
            }

            // Perform OCR
            TesseractOcrExtractor extractor = new TesseractOcrExtractor();
            TesseractOcrExtractor.OcrResult result = extractor.extractText(imageFile);

            // Verify result
            assertNotNull(result);
            assertTrue(result.success(), "OCR should succeed");
            assertNotNull(result.text());
            assertFalse(result.text().isEmpty(), "Should extract some text");
            assertTrue(result.text().toLowerCase().contains("hello"),
                    "Should recognize 'Hello' in the image");
            assertTrue(result.durationMs() > 0);
            assertTrue(result.confidence() > 0);

        } finally {
            // Cleanup
            Files.deleteIfExists(imageFile);
            Files.deleteIfExists(tempDir);
        }
    }

    /**
     * Helper method for @EnabledIf annotation.
     * Returns true if both Tesseract and ImageMagick are available for integration testing.
     */
    public static boolean canRunIntegrationTest() {
        if (!TesseractDetector.isAvailable()) {
            return false;
        }

        // Check if ImageMagick convert is available
        try {
            ProcessBuilder pb = new ProcessBuilder("convert", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
