package io.exoreaction.synthesis.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TesseractDetector -- format classification and install hints.
 * Note: Actual Tesseract detection depends on the test environment,
 * so we test the classification and hint methods which are deterministic.
 */
class TesseractDetectorTest {

    @Test
    void testSupportedImageFormats() {
        assertTrue(TesseractDetector.isSupported(".png"));
        assertTrue(TesseractDetector.isSupported(".PNG"));
        assertTrue(TesseractDetector.isSupported(".jpg"));
        assertTrue(TesseractDetector.isSupported(".jpeg"));
        assertTrue(TesseractDetector.isSupported(".tif"));
        assertTrue(TesseractDetector.isSupported(".tiff"));
        assertTrue(TesseractDetector.isSupported(".bmp"));
        assertTrue(TesseractDetector.isSupported(".gif"));
        assertTrue(TesseractDetector.isSupported(".webp"));

        assertFalse(TesseractDetector.isSupported(".pdf"));
        assertFalse(TesseractDetector.isSupported(".txt"));
        assertFalse(TesseractDetector.isSupported(".mp4"));
    }

    @Test
    void testGetInstallHint() {
        // Install hint should be a non-empty string
        String hint = TesseractDetector.getInstallHint();
        assertNotNull(hint);
        assertFalse(hint.isEmpty());
        // Should contain "tesseract" regardless of platform
        assertTrue(hint.toLowerCase().contains("tesseract"),
                "Install hint should mention tesseract: " + hint);
    }

    @Test
    void testGetStatusDisplay() {
        // Status display should be non-empty regardless of tesseract availability
        String status = TesseractDetector.getStatusDisplay();
        assertNotNull(status);
        assertFalse(status.isEmpty());
        // Should contain "Available" or "Not installed"
        assertTrue(status.contains("Available") || status.contains("Not installed"),
                "Status should indicate availability: " + status);
    }

    @Test
    void testIsAvailableIsCached() {
        // Calling isAvailable() twice should return the same result
        // (testing that caching doesn't break things)
        boolean first = TesseractDetector.isAvailable();
        boolean second = TesseractDetector.isAvailable();
        assertEquals(first, second, "Cached result should be consistent");
    }

    @Test
    void testGetVersionConsistentWithAvailability() {
        boolean available = TesseractDetector.isAvailable();
        String version = TesseractDetector.getVersion();

        if (available) {
            assertNotNull(version, "Version should be non-null when tesseract is available");
            assertFalse(version.isEmpty(), "Version should be non-empty when tesseract is available");
        }
        // Note: version can be null when tesseract is not available
    }

    @Test
    void testGetTesseractPathConsistentWithAvailability() {
        boolean available = TesseractDetector.isAvailable();
        String path = TesseractDetector.getTesseractPath();

        if (available) {
            assertNotNull(path, "Path should be non-null when tesseract is available");
            assertFalse(path.isEmpty(), "Path should be non-empty when tesseract is available");
        } else {
            assertNull(path, "Path should be null when tesseract is not available");
        }
    }

    @Test
    void testGetDataPathOptional() {
        // Data path is optional and may be null even when tesseract is available
        String dataPath = TesseractDetector.getDataPath();
        // Just verify it doesn't throw an exception
        // dataPath can be null or non-null, both are valid
    }
}
