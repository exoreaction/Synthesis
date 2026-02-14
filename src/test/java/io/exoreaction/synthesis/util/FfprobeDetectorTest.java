package io.exoreaction.synthesis.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FfprobeDetector -- format classification and install hints.
 * Note: Actual ffprobe detection depends on the test environment,
 * so we test the classification and hint methods which are deterministic.
 */
class FfprobeDetectorTest {

    @Test
    void testMetadataExtractorSupportedFormats() {
        assertTrue(FfprobeDetector.isMetadataExtractorSupported(".mp4"));
        assertTrue(FfprobeDetector.isMetadataExtractorSupported(".MP4"));
        assertTrue(FfprobeDetector.isMetadataExtractorSupported(".mov"));
        assertTrue(FfprobeDetector.isMetadataExtractorSupported(".avi"));
        assertTrue(FfprobeDetector.isMetadataExtractorSupported(".m4v"));
        assertTrue(FfprobeDetector.isMetadataExtractorSupported(".3gp"));

        assertFalse(FfprobeDetector.isMetadataExtractorSupported(".mkv"));
        assertFalse(FfprobeDetector.isMetadataExtractorSupported(".webm"));
        assertFalse(FfprobeDetector.isMetadataExtractorSupported(".flv"));
    }

    @Test
    void testFfprobeOnlyFormats() {
        assertTrue(FfprobeDetector.isFfprobeOnlyFormat(".mkv"));
        assertTrue(FfprobeDetector.isFfprobeOnlyFormat(".MKV"));
        assertTrue(FfprobeDetector.isFfprobeOnlyFormat(".webm"));
        assertTrue(FfprobeDetector.isFfprobeOnlyFormat(".flv"));
        assertTrue(FfprobeDetector.isFfprobeOnlyFormat(".wmv"));
        assertTrue(FfprobeDetector.isFfprobeOnlyFormat(".ogv"));
        assertTrue(FfprobeDetector.isFfprobeOnlyFormat(".mpg"));
        assertTrue(FfprobeDetector.isFfprobeOnlyFormat(".mpeg"));

        assertFalse(FfprobeDetector.isFfprobeOnlyFormat(".mp4"));
        assertFalse(FfprobeDetector.isFfprobeOnlyFormat(".mov"));
        assertFalse(FfprobeDetector.isFfprobeOnlyFormat(".avi"));
    }

    @Test
    void testGetInstallHint() {
        // Install hint should be a non-empty string
        String hint = FfprobeDetector.getInstallHint();
        assertNotNull(hint);
        assertFalse(hint.isEmpty());
        // Should contain "ffmpeg" regardless of platform
        assertTrue(hint.contains("ffmpeg"),
                "Install hint should mention ffmpeg: " + hint);
    }

    @Test
    void testGetStatusDisplay() {
        // Status display should be non-empty regardless of ffprobe availability
        String status = FfprobeDetector.getStatusDisplay();
        assertNotNull(status);
        assertFalse(status.isEmpty());
        // Should contain "Bundled", "Available", or "Not installed"
        assertTrue(status.contains("Bundled") || status.contains("Available") || status.contains("Not installed"),
                "Status should indicate availability: " + status);
    }

    @Test
    void testIsAvailableIsCached() {
        // Calling isAvailable() twice should return the same result
        // (testing that caching doesn't break things)
        boolean first = FfprobeDetector.isAvailable();
        boolean second = FfprobeDetector.isAvailable();
        assertEquals(first, second, "Cached result should be consistent");
    }

    @Test
    void testGetVersionConsistentWithAvailability() {
        boolean available = FfprobeDetector.isAvailable();
        String version = FfprobeDetector.getVersion();

        if (available) {
            assertNotNull(version, "Version should be non-null when ffprobe is available");
            assertFalse(version.isEmpty(), "Version should be non-empty when ffprobe is available");
        }
        // Note: version can be null when ffprobe is not available
    }
}
