package io.exoreaction.synthesis.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Transcribes audio/video files using Whisper (whisper.cpp or OpenAI Whisper).
 *
 * <p>Whisper is an automatic speech recognition (ASR) system from OpenAI that
 * provides high-quality transcription for 99 languages.
 *
 * <p>This transcriber supports two implementations:
 * <ul>
 *   <li><b>whisper.cpp</b> -- Fast C++ implementation with GGML quantized models.
 *       Preferred for local inference. (~100x faster than Python)</li>
 *   <li><b>OpenAI Whisper</b> -- Original Python implementation (pip install openai-whisper).
 *       Slower but easier to install.</li>
 * </ul>
 *
 * <p>The transcriber automatically uses the available implementation (detected by
 * {@link WhisperDetector}).
 *
 * <p>Usage:
 * <pre>
 *   if (WhisperDetector.isAvailable()) {
 *       WhisperTranscriber transcriber = new WhisperTranscriber();
 *       TranscriptionResult result = transcriber.transcribe(audioPath);
 *       if (result.success()) {
 *           String transcript = result.text();
 *           String language = result.language();
 *       }
 *   }
 * </pre>
 *
 * <p>Model selection:
 * <ul>
 *   <li><b>tiny</b> -- 39M params, ~1GB RAM, fastest (default for LOCAL tier)</li>
 *   <li><b>base</b> -- 74M params, ~1.5GB RAM, good quality/speed balance</li>
 *   <li><b>small</b> -- 244M params, ~2.5GB RAM, recommended for accuracy</li>
 *   <li><b>medium</b> -- 769M params, ~5GB RAM, high accuracy</li>
 *   <li><b>large</b> -- 1550M params, ~10GB RAM, best accuracy (requires GPU)</li>
 * </ul>
 *
 * @see WhisperDetector
 */
public class WhisperTranscriber {

    /** Default model to use if not specified (tiny = fastest, good for LOCAL tier). */
    private static final String DEFAULT_MODEL = "tiny";

    /** Maximum output tokens (prevents runaway transcription). */
    private static final int MAX_TOKENS = 10000;

    /** Timeout for transcription in milliseconds (5 minutes). */
    private static final long TIMEOUT_MS = 5 * 60 * 1000;

    private final String model;

    /**
     * Creates a transcriber using the default model (tiny).
     */
    public WhisperTranscriber() {
        this(DEFAULT_MODEL);
    }

    /**
     * Creates a transcriber using the specified model.
     *
     * @param model Whisper model name (tiny, base, small, medium, large)
     */
    public WhisperTranscriber(String model) {
        this.model = model;
    }

    /**
     * Transcribes an audio or video file to text.
     *
     * <p>This method is synchronous and may take several seconds to minutes
     * depending on the file length and model size.
     *
     * @param audioFile path to audio/video file (MP3, WAV, M4A, OGG, FLAC, etc.)
     * @return transcription result with text, language, and metadata
     * @throws IOException              if file cannot be read
     * @throws IllegalStateException    if Whisper is not available
     * @throws TranscriptionException   if transcription fails
     */
    public TranscriptionResult transcribe(Path audioFile) throws IOException {
        if (!WhisperDetector.isAvailable()) {
            throw new IllegalStateException(
                    "Whisper is not available. Install with: " + WhisperDetector.getInstallHint());
        }

        if (!Files.exists(audioFile)) {
            throw new IOException("Audio file not found: " + audioFile);
        }

        String implementation = WhisperDetector.getImplementation();
        if (implementation == null) {
            throw new IllegalStateException("Whisper implementation unknown");
        }

        // Build command based on implementation
        List<String> command;
        if (implementation.equals("whisper.cpp")) {
            command = buildWhisperCppCommand(audioFile);
        } else {
            command = buildOpenAIWhisperCommand(audioFile);
        }

        // Execute transcription
        try {
            return executeTranscription(command, audioFile);
        } catch (Exception e) {
            throw new TranscriptionException(
                    "Transcription failed for " + audioFile.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Builds the command for whisper.cpp.
     *
     * @param audioFile path to audio file
     * @return command list for ProcessBuilder
     */
    private List<String> buildWhisperCppCommand(Path audioFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(WhisperDetector.getWhisperPath());

        // Model selection
        cmd.add("-m");
        cmd.add("models/ggml-" + model + ".bin");

        // Input file
        cmd.add("-f");
        cmd.add(audioFile.toAbsolutePath().toString());

        // Output format: plain text
        cmd.add("-otxt");

        // Language detection (auto)
        cmd.add("-l");
        cmd.add("auto");

        // Max tokens
        cmd.add("-ml");
        cmd.add(String.valueOf(MAX_TOKENS));

        return cmd;
    }

    /**
     * Builds the command for OpenAI Whisper Python CLI.
     *
     * @param audioFile path to audio file
     * @return command list for ProcessBuilder
     */
    private List<String> buildOpenAIWhisperCommand(Path audioFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(WhisperDetector.getWhisperPath());

        // Input file
        cmd.add(audioFile.toAbsolutePath().toString());

        // Model selection
        cmd.add("--model");
        cmd.add(model);

        // Output format: plain text
        cmd.add("--output_format");
        cmd.add("txt");

        // Output directory (same as input)
        cmd.add("--output_dir");
        cmd.add(audioFile.getParent().toAbsolutePath().toString());

        return cmd;
    }

    /**
     * Executes the transcription command and parses the result.
     *
     * @param command   command list for ProcessBuilder
     * @param audioFile path to audio file (for context)
     * @return transcription result
     * @throws IOException if execution fails
     */
    private TranscriptionResult executeTranscription(List<String> command, Path audioFile)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        long startTime = System.currentTimeMillis();
        Process process = pb.start();

        // Read output
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");

                // Check timeout
                if (System.currentTimeMillis() - startTime > TIMEOUT_MS) {
                    process.destroyForcibly();
                    throw new TranscriptionException(
                            "Transcription timed out after " + (TIMEOUT_MS / 1000) + " seconds");
                }
            }
        }

        // Wait for completion
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Transcription interrupted", e);
        }

        if (exitCode != 0) {
            throw new TranscriptionException(
                    "Transcription failed with exit code " + exitCode + ": " + output);
        }

        long duration = System.currentTimeMillis() - startTime;

        // Parse output
        return parseTranscriptionOutput(output.toString(), audioFile, duration);
    }

    /**
     * Parses the transcription output into a structured result.
     *
     * @param output     raw output from Whisper command
     * @param audioFile  path to audio file
     * @param durationMs transcription duration in milliseconds
     * @return parsed transcription result
     */
    private TranscriptionResult parseTranscriptionOutput(String output, Path audioFile, long durationMs) {
        String implementation = WhisperDetector.getImplementation();

        if (implementation.equals("whisper.cpp")) {
            return parseWhisperCppOutput(output, audioFile, durationMs);
        } else {
            return parseOpenAIWhisperOutput(output, audioFile, durationMs);
        }
    }

    /**
     * Parses whisper.cpp output format.
     * whisper.cpp outputs plain text with metadata in stderr.
     */
    private TranscriptionResult parseWhisperCppOutput(String output, Path audioFile, long durationMs) {
        // Extract language from output (whisper.cpp logs: "detected language: en")
        String language = "unknown";
        String text = output;

        for (String line : output.lines().toList()) {
            if (line.contains("detected language:")) {
                String[] parts = line.split("detected language:");
                if (parts.length > 1) {
                    language = parts[1].trim().split("\\s+")[0]; // First word after colon
                }
            }
        }

        // Clean up text (remove metadata lines)
        text = text.lines()
                .filter(line -> !line.startsWith("[") && !line.contains("whisper_"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        return new TranscriptionResult(true, text, language, model, durationMs, null);
    }

    /**
     * Parses OpenAI Whisper output format.
     * OpenAI Whisper writes output to a .txt file.
     */
    private TranscriptionResult parseOpenAIWhisperOutput(String output, Path audioFile, long durationMs) {
        // OpenAI Whisper writes to <filename>.txt
        String baseName = audioFile.getFileName().toString();
        int lastDot = baseName.lastIndexOf('.');
        if (lastDot > 0) {
            baseName = baseName.substring(0, lastDot);
        }

        Path outputFile = audioFile.getParent().resolve(baseName + ".txt");

        try {
            if (Files.exists(outputFile)) {
                String text = Files.readString(outputFile);

                // Extract language from output (OpenAI logs: "Detected language: English")
                String language = "unknown";
                for (String line : output.lines().toList()) {
                    if (line.contains("Detected language:")) {
                        String[] parts = line.split("Detected language:");
                        if (parts.length > 1) {
                            language = parts[1].trim().split("\\s+")[0].toLowerCase();
                        }
                    }
                }

                return new TranscriptionResult(true, text.trim(), language, model, durationMs, null);
            } else {
                throw new TranscriptionException("Output file not found: " + outputFile);
            }
        } catch (IOException e) {
            throw new TranscriptionException("Failed to read output file: " + e.getMessage(), e);
        }
    }

    /**
     * Result of a Whisper transcription.
     *
     * @param success      true if transcription succeeded
     * @param text         transcribed text
     * @param language     detected language (ISO 639-1 code, e.g., "en", "es")
     * @param model        Whisper model used
     * @param durationMs   transcription duration in milliseconds
     * @param errorMessage error message if failed
     */
    public record TranscriptionResult(
            boolean success,
            String text,
            String language,
            String model,
            long durationMs,
            String errorMessage
    ) {
        /**
         * Creates a failed transcription result.
         */
        public static TranscriptionResult failed(String errorMessage) {
            return new TranscriptionResult(false, null, null, null, 0, errorMessage);
        }
    }

    /**
     * Exception thrown when transcription fails.
     */
    public static class TranscriptionException extends RuntimeException {
        public TranscriptionException(String message) {
            super(message);
        }

        public TranscriptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
