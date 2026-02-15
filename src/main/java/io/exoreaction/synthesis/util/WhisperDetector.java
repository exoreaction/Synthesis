package io.exoreaction.synthesis.util;

/**
 * Detects whether Whisper (whisper.cpp or OpenAI Whisper) is available on the system.
 *
 * <p>Detection priority:
 * <ol>
 *   <li><strong>whisper.cpp:</strong> Checks for {@code whisper} binary on system PATH.
 *       This is the preferred implementation for local inference.</li>
 *   <li><strong>OpenAI Whisper:</strong> Falls back to {@code whisper} Python CLI
 *       (from pip install openai-whisper).</li>
 * </ol>
 *
 * <p>Detection is performed once and cached for the lifetime of the JVM process.
 * This avoids repeated process spawning on every audio file.
 *
 * <p>Whisper is an optional dependency for LOCAL enrichment tier. If not available,
 * Synthesis falls back to BASIC enrichment (metadata only) for audio files.
 *
 * <p>Usage:
 * <pre>
 *   if (WhisperDetector.isAvailable()) {
 *       String path = WhisperDetector.getWhisperPath(); // "whisper"
 *       String impl = WhisperDetector.getImplementation(); // "whisper.cpp" or "OpenAI Whisper"
 *   }
 * </pre>
 */
public final class WhisperDetector {

    private WhisperDetector() {}

    /** Cached detection result: null = not yet checked, true/false = result. */
    private static volatile Boolean available;

    /** Cached version string from whisper output (e.g., "whisper.cpp v1.5.0"). */
    private static volatile String version;

    /** Cached path to the whisper executable. "whisper" for system PATH. */
    private static volatile String whisperPath;

    /** Implementation type: "whisper.cpp" or "OpenAI Whisper". */
    private static volatile String implementation;

    /** Audio extensions that Whisper supports. */
    private static final java.util.Set<String> SUPPORTED_EXTENSIONS = java.util.Set.of(
            ".mp3", ".wav", ".m4a", ".ogg", ".flac", ".opus", ".aac"
    );

    /**
     * Returns true if Whisper is available on the system PATH.
     * Result is cached after the first call.
     */
    public static boolean isAvailable() {
        if (available == null) {
            synchronized (WhisperDetector.class) {
                if (available == null) {
                    detect();
                }
            }
        }
        return available;
    }

    /**
     * Returns the Whisper version string, or null if Whisper is not available.
     * Triggers detection if not yet performed.
     */
    public static String getVersion() {
        if (available == null) {
            isAvailable(); // trigger detection
        }
        return version;
    }

    /**
     * Returns the command to use for running Whisper.
     * Returns "whisper" for system PATH, or null if not available.
     *
     * @return whisper command, or null if not available
     */
    public static String getWhisperPath() {
        if (available == null) {
            isAvailable(); // trigger detection
        }
        return whisperPath;
    }

    /**
     * Returns the detected Whisper implementation.
     *
     * @return "whisper.cpp" or "OpenAI Whisper", or null if not available
     */
    public static String getImplementation() {
        if (available == null) {
            isAvailable(); // trigger detection
        }
        return implementation;
    }

    /**
     * Returns true if the given file extension is supported by Whisper.
     *
     * @param extension file extension including the dot (e.g., ".mp3")
     */
    public static boolean isSupported(String extension) {
        return SUPPORTED_EXTENSIONS.contains(extension.toLowerCase());
    }

    /**
     * Returns a human-friendly status string for display in status/scan output.
     *
     * @return e.g., "Available (whisper.cpp v1.5.0)" or "Not installed (optional)"
     */
    public static String getStatusDisplay() {
        if (isAvailable()) {
            String impl = getImplementation();
            String ver = getVersion();
            if (impl != null && ver != null) {
                return "Available (" + impl + " " + ver + ")";
            } else if (impl != null) {
                return "Available (" + impl + ")";
            }
            return "Available";
        }
        return "Not installed (optional)";
    }

    /**
     * Returns the platform-appropriate install command for Whisper.
     */
    public static String getInstallHint() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return "brew install whisper-cpp  # or: pip install openai-whisper";
        } else if (os.contains("win")) {
            return "scoop install whisper-cpp  # or: pip install openai-whisper";
        } else {
            // Linux
            return "# Build from source: https://github.com/ggerganov/whisper.cpp\n" +
                   "#   or: pip install openai-whisper";
        }
    }

    /**
     * Resets the cached detection state. Used for testing.
     */
    static void reset() {
        synchronized (WhisperDetector.class) {
            available = null;
            version = null;
            whisperPath = null;
            implementation = null;
        }
    }

    /**
     * Performs the actual detection by trying whisper.cpp first, then OpenAI Whisper.
     * Sets {@link #available}, {@link #version}, {@link #whisperPath}, and {@link #implementation}.
     */
    private static void detect() {
        // Priority 1: Try whisper.cpp (preferred for local inference)
        if (detectWhisperCpp()) {
            return;
        }

        // Priority 2: Try OpenAI Whisper Python CLI
        if (detectOpenAIWhisper()) {
            return;
        }

        // Not available
        available = false;
        version = null;
        whisperPath = null;
        implementation = null;
    }

    /**
     * Detects whisper.cpp by running {@code whisper --version}.
     *
     * @return true if whisper.cpp is detected and working
     */
    private static boolean detectWhisperCpp() {
        try {
            String versionStr = extractVersion("whisper", "--version");
            if (versionStr != null) {
                available = true;
                version = versionStr;
                whisperPath = "whisper";
                implementation = "whisper.cpp";
                return true;
            }
        } catch (Exception e) {
            // Not found
        }
        return false;
    }

    /**
     * Detects OpenAI Whisper Python CLI by running {@code whisper --help}.
     * (OpenAI Whisper doesn't have a --version flag, so we check --help)
     *
     * @return true if OpenAI Whisper is detected and working
     */
    private static boolean detectOpenAIWhisper() {
        try {
            ProcessBuilder pb = new ProcessBuilder("whisper", "--help");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode == 0 && output.contains("Transcribe audio")) {
                // This is OpenAI Whisper (help text contains "Transcribe audio")
                available = true;
                version = "unknown"; // OpenAI Whisper doesn't expose version easily
                whisperPath = "whisper";
                implementation = "OpenAI Whisper";
                return true;
            }
        } catch (Exception e) {
            // Not found
        }
        return false;
    }

    /**
     * Runs {@code <command> <versionFlag>} and extracts the version string.
     *
     * @param command     the whisper command or path
     * @param versionFlag the version flag (e.g., "--version", "-v")
     * @return version string, or null if execution fails
     */
    private static String extractVersion(String command, String versionFlag) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, versionFlag);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode == 0 && !output.isBlank()) {
                // Extract version from first line
                String firstLine = output.lines().findFirst().orElse("");
                // whisper.cpp typically outputs: "whisper v1.5.0" or similar
                if (firstLine.contains("whisper")) {
                    return firstLine.trim();
                } else if (!firstLine.isEmpty()) {
                    return firstLine.length() > 60
                            ? firstLine.substring(0, 60)
                            : firstLine;
                }
            }
        } catch (Exception e) {
            // Execution failed
        }
        return null;
    }
}
