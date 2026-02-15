package io.exoreaction.synthesis.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PdftoppmDetector -- classification and install hints.
 * Note: Actual pdftoppm detection depends on the test environment,
 * so we test the methods which are deterministic.
 */
class PdftoppmDetectorTest {

    @Test
    void testGetInstallHint() {
        // Install hint should be a non-empty string
        String hint = PdftoppmDetector.getInstallHint();
        assertNotNull(hint);
        assertFalse(hint.isEmpty());
        // Should contain "poppler" regardless of platform
        assertTrue(hint.toLowerCase().contains("poppler"),
                "Install hint should mention poppler: " + hint);
    }

    @Test
    void testGetStatusDisplay() {
        // Status display should be non-empty regardless of pdftoppm availability
        String status = PdftoppmDetector.getStatusDisplay();
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
        boolean first = PdftoppmDetector.isAvailable();
        boolean second = PdftoppmDetector.isAvailable();
        assertEquals(first, second, "Cached result should be consistent");
    }

    @Test
    void testGetVersionConsistentWithAvailability() {
        boolean available = PdftoppmDetector.isAvailable();
        String version = PdftoppmDetector.getVersion();

        if (available) {
            assertNotNull(version, "Version should be non-null when pdftoppm is available");
            assertFalse(version.isEmpty(), "Version should be non-empty when pdftoppm is available");
        }
        // Note: version can be null when pdftoppm is not available
    }

    @Test
    void testGetPdftoppmPathConsistentWithAvailability() {
        boolean available = PdftoppmDetector.isAvailable();
        String path = PdftoppmDetector.getPdftoppmPath();

        if (available) {
            assertNotNull(path, "Path should be non-null when pdftoppm is available");
            assertFalse(path.isEmpty(), "Path should be non-empty when pdftoppm is available");
        } else {
            assertNull(path, "Path should be null when pdftoppm is not available");
        }
    }
}
