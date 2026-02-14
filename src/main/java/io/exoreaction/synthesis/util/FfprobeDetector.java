package io.exoreaction.synthesis.util;

/**
 * Detects whether ffprobe (from FFmpeg) is available, with bundled binary support.
 *
 * <p>Detection priority:
 * <ol>
 *   <li><strong>Bundled binary:</strong> Extracted from JAR to {@code ~/.synthesis/bin/ffprobe}
 *       on first use via {@link BundledBinaryManager}. No user installation needed.</li>
 *   <li><strong>System PATH:</strong> Falls back to {@code ffprobe} on the system PATH
 *       if bundled binary is not available.</li>
 * </ol>
 *
 * <p>Detection is performed once and cached for the lifetime of the JVM process.
 * This avoids repeated process spawning on every video file.
 *
 * <p>Usage:
 * <pre>
 *   if (FfprobeDetector.isAvailable()) {
 *       // use ffprobe for metadata extraction
 *       String path = FfprobeDetector.getFfprobePath(); // full path or "ffprobe"
 *   }
 *   String version = FfprobeDetector.getVersion(); // e.g., "FFmpeg 6.0" or null
 * </pre>
 *
 * @see BundledBinaryManager
 */
public final class FfprobeDetector {

    private FfprobeDetector() {}

    /** Cached detection result: null = not yet checked, true/false = result. */
    private static volatile Boolean available;

    /** Cached version string from ffprobe output (e.g., "ffprobe version 6.0"). */
    private static volatile String version;

    /** Cached path to the ffprobe executable. Null = not detected or use system PATH. */
    private static volatile String ffprobePath;

    /** Whether the currently detected ffprobe is from a bundled binary. */
    private static volatile boolean usingBundled;

    /** Extensions that metadata-extractor handles well (MP4, MOV, AVI containers). */
    private static final java.util.Set<String> METADATA_EXTRACTOR_EXTENSIONS = java.util.Set.of(
            ".mp4", ".m4v", ".mov", ".avi", ".3gp"
    );

    /** Extensions that require ffprobe for full metadata (MKV, WebM, etc.). */
    private static final java.util.Set<String> FFPROBE_ONLY_EXTENSIONS = java.util.Set.of(
            ".mkv", ".webm", ".flv", ".wmv", ".ogv", ".mpg", ".mpeg"
    );

    /**
     * Returns true if ffprobe is available (bundled or system PATH).
     * Result is cached after the first call.
     */
    public static boolean isAvailable() {
        if (available == null) {
            synchronized (FfprobeDetector.class) {
                if (available == null) {
                    detect();
                }
            }
        }
        return available;
    }

    /**
     * Returns the ffprobe version string, or null if ffprobe is not available.
     * Triggers detection if not yet performed.
     */
    public static String getVersion() {
        if (available == null) {
            isAvailable(); // trigger detection
        }
        return version;
    }

    /**
     * Returns the path or command to use for running ffprobe.
     *
     * <p>If a bundled binary was extracted, returns the full path to it.
     * If using system PATH, returns "ffprobe".
     * Returns null if ffprobe is not available at all.
     *
     * @return ffprobe path/command, or null if not available
     */
    public static String getFfprobePath() {
        if (available == null) {
            isAvailable(); // trigger detection
        }
        return ffprobePath;
    }

    /**
     * Returns true if the currently detected ffprobe is from a bundled binary
     * (extracted from the JAR), as opposed to a system-installed one.
     */
    public static boolean isUsingBundled() {
        if (available == null) {
            isAvailable(); // trigger detection
        }
        return usingBundled;
    }

    /**
     * Returns true if the given file extension is well-supported by the pure Java
     * metadata-extractor library (MP4, MOV, AVI, M4V, 3GP).
     *
     * @param extension file extension including the dot (e.g., ".mp4")
     */
    public static boolean isMetadataExtractorSupported(String extension) {
        return METADATA_EXTRACTOR_EXTENSIONS.contains(extension.toLowerCase());
    }

    /**
     * Returns true if the given file extension requires ffprobe for full metadata
     * extraction (MKV, WebM, FLV, WMV, OGV, MPG, MPEG).
     *
     * @param extension file extension including the dot (e.g., ".mkv")
     */
    public static boolean isFfprobeOnlyFormat(String extension) {
        return FFPROBE_ONLY_EXTENSIONS.contains(extension.toLowerCase());
    }

    /**
     * Returns a human-friendly status string for display in status/scan output.
     *
     * @return e.g., "Bundled (FFmpeg 6.0)", "Available (FFmpeg 6.0)", or "Not installed (optional)"
     */
    public static String getStatusDisplay() {
        if (isAvailable()) {
            String prefix = usingBundled ? "Bundled" : "Available";
            String ver = getVersion();
            if (ver != null && !ver.isEmpty()) {
                return prefix + " (" + ver + ")";
            }
            return prefix;
        }
        return "Not installed (optional)";
    }

    /**
     * Returns the platform-appropriate install command for ffmpeg.
     */
    public static String getInstallHint() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return "brew install ffmpeg";
        } else if (os.contains("win")) {
            return "winget install ffmpeg";
        } else {
            return "sudo apt install ffmpeg";
        }
    }

    /**
     * Resets the cached detection state. Used for testing.
     */
    static void reset() {
        synchronized (FfprobeDetector.class) {
            available = null;
            version = null;
            ffprobePath = null;
            usingBundled = false;
        }
    }

    /**
     * Performs the actual detection by trying bundled binary first, then system PATH.
     * Sets {@link #available}, {@link #version}, {@link #ffprobePath}, and {@link #usingBundled}.
     */
    private static void detect() {
        // Priority 1: Try bundled binary
        java.nio.file.Path bundledPath = BundledBinaryManager.getFfprobePath();
        if (bundledPath != null) {
            String extractedVersion = extractVersion(bundledPath.toString());
            if (extractedVersion != null) {
                available = true;
                version = extractedVersion;
                ffprobePath = bundledPath.toString();
                usingBundled = true;
                return;
            }
        }

        // Priority 2: Try system PATH
        try {
            String extractedVersion = extractVersion("ffprobe");
            if (extractedVersion != null) {
                available = true;
                version = extractedVersion;
                ffprobePath = "ffprobe";
                usingBundled = false;
                return;
            }
        } catch (Exception e) {
            // ffprobe not found on PATH
        }

        // Not available
        available = false;
        version = null;
        ffprobePath = null;
        usingBundled = false;
    }

    /**
     * Runs {@code <command> -version} and extracts the version string.
     *
     * @param command the ffprobe command or path
     * @return version string (e.g., "FFmpeg 6.0"), or null if execution fails
     */
    private static String extractVersion(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode == 0 && !output.isBlank()) {
                // Extract version: first line is typically "ffprobe version N.N.N ..."
                String firstLine = output.lines().findFirst().orElse("");
                if (firstLine.startsWith("ffprobe version ")) {
                    // Extract just "FFmpeg N.N.N" from "ffprobe version N.N.N-..."
                    String versionPart = firstLine.substring("ffprobe version ".length()).trim();
                    // Trim at first space or dash after version number
                    int dashIdx = versionPart.indexOf('-');
                    int spaceIdx = versionPart.indexOf(' ');
                    int end = versionPart.length();
                    if (dashIdx > 0) end = Math.min(end, dashIdx);
                    if (spaceIdx > 0) end = Math.min(end, spaceIdx);
                    return "FFmpeg " + versionPart.substring(0, end);
                } else {
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
