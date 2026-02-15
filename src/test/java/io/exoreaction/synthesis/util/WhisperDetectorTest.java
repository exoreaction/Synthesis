package io.exoreaction.synthesis.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WhisperDetector -- format classification and install hints.
 * Note: Actual Whisper detection depends on the test environment,
 * so we test the classification and hint methods which are deterministic.
 */
class WhisperDetectorTest {

    @Test
    void testSupportedAudioFormats() {
        assertTrue(WhisperDetector.isSupported(".mp3"));
        assertTrue(WhisperDetector.isSupported(".MP3"));
        assertTrue(WhisperDetector.isSupported(".wav"));
        assertTrue(WhisperDetector.isSupported(".m4a"));
        assertTrue(WhisperDetector.isSupported(".ogg"));
        assertTrue(WhisperDetector.isSupported(".flac"));
        assertTrue(WhisperDetector.isSupported(".opus"));
        assertTrue(WhisperDetector.isSupported(".aac"));

        assertFalse(WhisperDetector.isSupported(".mp4"));
        assertFalse(WhisperDetector.isSupported(".mkv"));
        assertFalse(WhisperDetector.isSupported(".txt"));
    }

    @Test
    void testGetInstallHint() {
        // Install hint should be a non-empty string
        String hint = WhisperDetector.getInstallHint();
        assertNotNull(hint);
        assertFalse(hint.isEmpty());
        // Should contain "whisper" regardless of platform
        assertTrue(hint.toLowerCase().contains("whisper"),
                "Install hint should mention whisper: " + hint);
    }

    @Test
    void testGetStatusDisplay() {
        // Status display should be non-empty regardless of whisper availability
        String status = WhisperDetector.getStatusDisplay();
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
        boolean first = WhisperDetector.isAvailable();
        boolean second = WhisperDetector.isAvailable();
        assertEquals(first, second, "Cached result should be consistent");
    }

    @Test
    void testGetVersionConsistentWithAvailability() {
        boolean available = WhisperDetector.isAvailable();
        String version = WhisperDetector.getVersion();

        if (available) {
            assertNotNull(version, "Version should be non-null when whisper is available");
            assertFalse(version.isEmpty(), "Version should be non-empty when whisper is available");
        }
        // Note: version can be null when whisper is not available
    }

    @Test
    void testGetImplementationConsistentWithAvailability() {
        boolean available = WhisperDetector.isAvailable();
        String implementation = WhisperDetector.getImplementation();

        if (available) {
            assertNotNull(implementation, "Implementation should be non-null when whisper is available");
            assertTrue(implementation.equals("whisper.cpp") || implementation.equals("OpenAI Whisper"),
                    "Implementation should be either whisper.cpp or OpenAI Whisper: " + implementation);
        }
        // Note: implementation can be null when whisper is not available
    }

    @Test
    void testGetWhisperPathConsistentWithAvailability() {
        boolean available = WhisperDetector.isAvailable();
        String path = WhisperDetector.getWhisperPath();

        if (available) {
            assertNotNull(path, "Path should be non-null when whisper is available");
            assertEquals("whisper", path, "Path should be 'whisper' for system PATH");
        } else {
            assertNull(path, "Path should be null when whisper is not available");
        }
    }
}
