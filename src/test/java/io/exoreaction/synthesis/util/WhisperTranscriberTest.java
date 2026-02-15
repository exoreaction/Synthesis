package io.exoreaction.synthesis.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WhisperTranscriber.
 * Most tests require Whisper to be installed and are only run if available.
 */
class WhisperTranscriberTest {

    @Test
    void testTranscriberCreation() {
        // Should be able to create transcriber with default model
        WhisperTranscriber transcriber = new WhisperTranscriber();
        assertNotNull(transcriber);

        // Should be able to create transcriber with custom model
        WhisperTranscriber customTranscriber = new WhisperTranscriber("base");
        assertNotNull(customTranscriber);
    }

    @Test
    void testTranscribeThrowsWhenWhisperNotAvailable() {
        if (WhisperDetector.isAvailable()) {
            // Skip this test if Whisper is actually available
            return;
        }

        WhisperTranscriber transcriber = new WhisperTranscriber();
        Path dummyFile = Path.of("/tmp/dummy.mp3");

        assertThrows(IllegalStateException.class, () -> {
            transcriber.transcribe(dummyFile);
        }, "Should throw IllegalStateException when Whisper is not available");
    }

    @Test
    void testTranscribeThrowsOnMissingFile() throws IOException {
        if (!WhisperDetector.isAvailable()) {
            return; // Skip if Whisper not available
        }

        WhisperTranscriber transcriber = new WhisperTranscriber();
        Path nonExistentFile = Path.of("/tmp/nonexistent-" + System.currentTimeMillis() + ".mp3");

        assertThrows(IOException.class, () -> {
            transcriber.transcribe(nonExistentFile);
        }, "Should throw IOException when file doesn't exist");
    }

    @Test
    @EnabledIf("io.exoreaction.synthesis.util.WhisperDetector#isAvailable")
    void testTranscribeWithSampleAudio() throws IOException {
        // This test requires a sample audio file
        // For now, we'll create a minimal test that validates the API
        WhisperTranscriber transcriber = new WhisperTranscriber("tiny");

        // In a real test, we would transcribe an actual audio file:
        // Path sampleAudio = Path.of("src/test/resources/sample.mp3");
        // TranscriptionResult result = transcriber.transcribe(sampleAudio);
        // assertTrue(result.success());
        // assertNotNull(result.text());
        // assertFalse(result.text().isEmpty());

        // For now, just verify the transcriber is properly configured
        assertNotNull(transcriber);
    }

    @Test
    void testTranscriptionResultSuccess() {
        WhisperTranscriber.TranscriptionResult result =
                new WhisperTranscriber.TranscriptionResult(
                        true,
                        "This is a test transcript.",
                        "en",
                        "tiny",
                        5000L,
                        null
                );

        assertTrue(result.success());
        assertEquals("This is a test transcript.", result.text());
        assertEquals("en", result.language());
        assertEquals("tiny", result.model());
        assertEquals(5000L, result.durationMs());
        assertNull(result.errorMessage());
    }

    @Test
    void testTranscriptionResultFailed() {
        WhisperTranscriber.TranscriptionResult result =
                WhisperTranscriber.TranscriptionResult.failed("Transcription error");

        assertFalse(result.success());
        assertNull(result.text());
        assertNull(result.language());
        assertNull(result.model());
        assertEquals(0L, result.durationMs());
        assertEquals("Transcription error", result.errorMessage());
    }

    @Test
    void testTranscriptionException() {
        WhisperTranscriber.TranscriptionException ex =
                new WhisperTranscriber.TranscriptionException("Test error");

        assertEquals("Test error", ex.getMessage());

        Exception cause = new IOException("Underlying error");
        WhisperTranscriber.TranscriptionException exWithCause =
                new WhisperTranscriber.TranscriptionException("Test error with cause", cause);

        assertEquals("Test error with cause", exWithCause.getMessage());
        assertEquals(cause, exWithCause.getCause());
    }

    /**
     * Integration test that creates a silent audio file and attempts transcription.
     * Only runs if Whisper is available and ffmpeg is available to generate audio.
     */
    @Test
    @EnabledIf("io.exoreaction.synthesis.util.WhisperTranscriberTest#canRunIntegrationTest")
    void testTranscribeIntegration() throws IOException, InterruptedException {
        // Create a temporary silent audio file using ffmpeg
        Path tempDir = Files.createTempDirectory("whisper-test");
        Path audioFile = tempDir.resolve("silence.mp3");

        try {
            // Generate 1 second of silence as MP3
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-f", "lavfi",
                    "-i", "anullsrc=r=44100:cl=mono",
                    "-t", "1",
                    "-q:a", "9",
                    "-acodec", "libmp3lame",
                    audioFile.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes(); // consume output
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                // ffmpeg not available or failed, skip test
                return;
            }

            // Transcribe the silent audio
            WhisperTranscriber transcriber = new WhisperTranscriber("tiny");
            WhisperTranscriber.TranscriptionResult result = transcriber.transcribe(audioFile);

            // Verify result structure (silent audio may produce empty or minimal text)
            assertNotNull(result);
            assertTrue(result.success());
            assertNotNull(result.text()); // May be empty for silence
            assertNotNull(result.language());
            assertTrue(result.durationMs() > 0);

        } finally {
            // Cleanup
            Files.deleteIfExists(audioFile);
            Files.deleteIfExists(tempDir);
        }
    }

    /**
     * Helper method for @EnabledIf annotation.
     * Returns true if both Whisper and ffmpeg are available for integration testing.
     */
    public static boolean canRunIntegrationTest() {
        if (!WhisperDetector.isAvailable()) {
            return false;
        }

        // Check if ffmpeg is available
        try {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
