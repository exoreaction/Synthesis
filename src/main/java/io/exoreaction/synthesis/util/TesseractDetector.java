package io.exoreaction.synthesis.util;

/**
 * Detects whether Tesseract OCR is available on the system.
 *
 * <p>Tesseract is an open-source optical character recognition (OCR) engine
 * that extracts text from images. It supports 100+ languages and is the
 * industry standard for open-source OCR.
 *
 * <p>Detection approach:
 * <ol>
 *   <li><strong>System PATH:</strong> Checks for {@code tesseract} command on PATH</li>
 *   <li><strong>Standard locations:</strong> Falls back to common install paths:
 *       <ul>
 *         <li>Linux: {@code /usr/bin/tesseract}, {@code /usr/local/bin/tesseract}</li>
 *         <li>macOS: {@code /opt/homebrew/bin/tesseract}, {@code /usr/local/bin/tesseract}</li>
 *         <li>Windows: {@code C:\Program Files\Tesseract-OCR\tesseract.exe}</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Detection is performed once and cached for the lifetime of the JVM process.
 *
 * <p>Tesseract is an optional dependency for LOCAL enrichment tier. If not available,
 * Synthesis falls back to BASIC enrichment (metadata only) for images.
 *
 * <p>Usage:
 * <pre>
 *   if (TesseractDetector.isAvailable()) {
 *       String path = TesseractDetector.getTesseractPath();
 *       String version = TesseractDetector.getVersion();
 *   }
 * </pre>
 */
public final class TesseractDetector {

    private TesseractDetector() {}

    /** Cached detection result: null = not yet checked, true/false = result. */
    private static volatile Boolean available;

    /** Cached version string from tesseract output (e.g., "tesseract 5.3.0"). */
    private static volatile String version;

    /** Cached path to the tesseract executable. */
    private static volatile String tesseractPath;

    /** Cached data directory path (TESSDATA_PREFIX). */
    private static volatile String dataPath;

    /** Image extensions that Tesseract supports for OCR. */
    private static final java.util.Set<String> SUPPORTED_EXTENSIONS = java.util.Set.of(
            ".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".gif", ".webp"
    );

    /** Common installation paths to check (in priority order). */
    private static final String[] COMMON_PATHS = {
            "tesseract",                                      // System PATH
            "/usr/bin/tesseract",                             // Linux standard
            "/usr/local/bin/tesseract",                       // Linux local install
            "/opt/homebrew/bin/tesseract",                    // macOS Apple Silicon Homebrew
            "/usr/local/opt/tesseract/bin/tesseract",         // macOS Intel Homebrew
            "C:\\Program Files\\Tesseract-OCR\\tesseract.exe", // Windows default
            "C:\\Program Files (x86)\\Tesseract-OCR\\tesseract.exe" // Windows 32-bit
    };

    /**
     * Returns true if Tesseract is available on the system.
     * Result is cached after the first call.
     */
    public static boolean isAvailable() {
        if (available == null) {
            synchronized (TesseractDetector.class) {
                if (available == null) {
                    detect();
                }
            }
        }
        return available;
    }

    /**
     * Returns the Tesseract version string, or null if Tesseract is not available.
     * Triggers detection if not yet performed.
     */
    public static String getVersion() {
        if (available == null) {
            isAvailable(); // trigger detection
        }
        return version;
    }

    /**
     * Returns the path to the tesseract executable.
     * Returns null if Tesseract is not available.
     *
     * @return tesseract path, or null if not available
     */
    public static String getTesseractPath() {
        if (available == null) {
            isAvailable(); // trigger detection
        }
        return tesseractPath;
    }

    /**
     * Returns the Tesseract data directory path (TESSDATA_PREFIX).
     * This is where language data files (.traineddata) are stored.
     * Returns null if Tesseract is not available or data path cannot be determined.
     *
     * @return tessdata directory path, or null
     */
    public static String getDataPath() {
        if (available == null) {
            isAvailable(); // trigger detection
        }
        return dataPath;
    }

    /**
     * Returns true if the given file extension is supported by Tesseract.
     *
     * @param extension file extension including the dot (e.g., ".png")
     */
    public static boolean isSupported(String extension) {
        return SUPPORTED_EXTENSIONS.contains(extension.toLowerCase());
    }

    /**
     * Returns a human-friendly status string for display in status/scan output.
     *
     * @return e.g., "Available (tesseract 5.3.0)" or "Not installed (optional)"
     */
    public static String getStatusDisplay() {
        if (isAvailable()) {
            String ver = getVersion();
            if (ver != null && !ver.isEmpty()) {
                return "Available (" + ver + ")";
            }
            return "Available";
        }
        return "Not installed (optional)";
    }

    /**
     * Returns the platform-appropriate install command for Tesseract.
     */
    public static String getInstallHint() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return "brew install tesseract";
        } else if (os.contains("win")) {
            return "Download from: https://github.com/UB-Mannheim/tesseract/wiki";
        } else {
            // Linux
            return "sudo apt install tesseract-ocr  # or: sudo yum install tesseract";
        }
    }

    /**
     * Resets the cached detection state. Used for testing.
     */
    static void reset() {
        synchronized (TesseractDetector.class) {
            available = null;
            version = null;
            tesseractPath = null;
            dataPath = null;
        }
    }

    /**
     * Performs the actual detection by trying common installation paths.
     * Sets {@link #available}, {@link #version}, {@link #tesseractPath}, and {@link #dataPath}.
     */
    private static void detect() {
        // Try each common path in priority order
        for (String path : COMMON_PATHS) {
            if (tryPath(path)) {
                return; // Successfully detected
            }
        }

        // Not available
        available = false;
        version = null;
        tesseractPath = null;
        dataPath = null;
    }

    /**
     * Attempts to detect Tesseract at the given path.
     *
     * @param path path to tesseract executable
     * @return true if Tesseract is detected and working at this path
     */
    private static boolean tryPath(String path) {
        try {
            String versionStr = extractVersion(path);
            if (versionStr != null) {
                available = true;
                version = versionStr;
                tesseractPath = path;
                dataPath = detectDataPath(path);
                return true;
            }
        } catch (Exception e) {
            // Not found at this path
        }
        return false;
    }

    /**
     * Runs {@code tesseract --version} and extracts the version string.
     *
     * @param command the tesseract command or path
     * @return version string (e.g., "tesseract 5.3.0"), or null if execution fails
     */
    private static String extractVersion(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode == 0 && !output.isBlank()) {
                // Extract version from first line: "tesseract 5.3.0"
                String firstLine = output.lines().findFirst().orElse("");
                if (firstLine.startsWith("tesseract ")) {
                    // Return just the version number part
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

    /**
     * Detects the Tesseract data directory by running {@code tesseract --list-langs}.
     * The output typically includes "tessdata prefix" path.
     *
     * @param command the tesseract command or path
     * @return tessdata directory path, or null if detection fails
     */
    private static String detectDataPath(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, "--list-langs");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                // Look for line like: "tessdata prefix: /usr/share/tesseract-ocr/5/tessdata/"
                for (String line : output.lines().toList()) {
                    if (line.contains("tessdata prefix:")) {
                        String[] parts = line.split("tessdata prefix:");
                        if (parts.length > 1) {
                            return parts[1].trim();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Data path detection failed (non-fatal)
        }
        return null;
    }
}
