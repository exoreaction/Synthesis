package io.exoreaction.synthesis.util;

/**
 * Detects whether pdftoppm (from Poppler utilities) is available on the system.
 *
 * <p>pdftoppm converts PDF pages to image format (PPM/PNG/JPEG), enabling OCR
 * extraction from scanned PDFs and image-based PDFs. It's part of the Poppler
 * PDF rendering library's command-line utilities.
 *
 * <p>Detection approach:
 * <ol>
 *   <li><strong>System PATH:</strong> Checks for {@code pdftoppm} command on PATH</li>
 *   <li><strong>Standard locations:</strong> Falls back to common install paths:
 *       <ul>
 *         <li>Linux: {@code /usr/bin/pdftoppm}, {@code /usr/local/bin/pdftoppm}</li>
 *         <li>macOS: {@code /opt/homebrew/bin/pdftoppm}, {@code /usr/local/bin/pdftoppm}</li>
 *         <li>Windows: {@code C:\Program Files\poppler\bin\pdftoppm.exe}</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Detection is performed once and cached for the lifetime of the JVM process.
 *
 * <p>pdftoppm is an optional dependency for LOCAL enrichment tier. If not available,
 * Synthesis falls back to PDFBox text extraction only (no OCR for scanned PDFs).
 *
 * <p>Usage:
 * <pre>
 *   if (PdftoppmDetector.isAvailable()) {
 *       String path = PdftoppmDetector.getPdftoppmPath();
 *       String version = PdftoppmDetector.getVersion();
 *   }
 * </pre>
 */
public final class PdftoppmDetector {

    private PdftoppmDetector() {}

    /** Cached detection result: null = not yet checked, true/false = result. */
    private static volatile Boolean available;

    /** Cached version string from pdftoppm output (e.g., "poppler 23.09.0"). */
    private static volatile String version;

    /** Cached path to the pdftoppm executable. */
    private static volatile String pdftoppmPath;

    /** Common installation paths to check (in priority order). */
    private static final String[] COMMON_PATHS = {
            "pdftoppm",                                       // System PATH
            "/usr/bin/pdftoppm",                              // Linux standard
            "/usr/local/bin/pdftoppm",                        // Linux local install
            "/opt/homebrew/bin/pdftoppm",                     // macOS Apple Silicon Homebrew
            "/usr/local/opt/poppler/bin/pdftoppm",            // macOS Intel Homebrew
            "C:\\Program Files\\poppler\\bin\\pdftoppm.exe",  // Windows default
            "C:\\Program Files (x86)\\poppler\\bin\\pdftoppm.exe" // Windows 32-bit
    };

    /**
     * Returns true if pdftoppm is available on the system.
     * Result is cached after the first call.
     */
    public static boolean isAvailable() {
        if (available == null) {
            synchronized (PdftoppmDetector.class) {
                if (available == null) {
                    detect();
                }
            }
        }
        return available;
    }

    /**
     * Returns the pdftoppm version string, or null if pdftoppm is not available.
     * Triggers detection if not yet performed.
     */
    public static String getVersion() {
        if (available == null) {
            isAvailable(); // trigger detection
        }
        return version;
    }

    /**
     * Returns the path to the pdftoppm executable.
     * Returns null if pdftoppm is not available.
     *
     * @return pdftoppm path, or null if not available
     */
    public static String getPdftoppmPath() {
        if (available == null) {
            isAvailable(); // trigger detection
        }
        return pdftoppmPath;
    }

    /**
     * Returns a human-friendly status string for display in status/scan output.
     *
     * @return e.g., "Available (poppler 23.09.0)" or "Not installed (optional)"
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
     * Returns the platform-appropriate install command for pdftoppm/Poppler.
     */
    public static String getInstallHint() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return "brew install poppler";
        } else if (os.contains("win")) {
            return "Download from: https://github.com/oschwartz10612/poppler-windows/releases";
        } else {
            // Linux
            return "sudo apt install poppler-utils  # or: sudo yum install poppler-utils";
        }
    }

    /**
     * Resets the cached detection state. Used for testing.
     */
    static void reset() {
        synchronized (PdftoppmDetector.class) {
            available = null;
            version = null;
            pdftoppmPath = null;
        }
    }

    /**
     * Performs the actual detection by trying common installation paths.
     * Sets {@link #available}, {@link #version}, and {@link #pdftoppmPath}.
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
        pdftoppmPath = null;
    }

    /**
     * Attempts to detect pdftoppm at the given path.
     *
     * @param path path to pdftoppm executable
     * @return true if pdftoppm is detected and working at this path
     */
    private static boolean tryPath(String path) {
        try {
            String versionStr = extractVersion(path);
            if (versionStr != null) {
                available = true;
                version = versionStr;
                pdftoppmPath = path;
                return true;
            }
        } catch (Exception e) {
            // Not found at this path
        }
        return false;
    }

    /**
     * Runs {@code pdftoppm -v} and extracts the version string.
     *
     * @param command the pdftoppm command or path
     * @return version string (e.g., "poppler 23.09.0"), or null if execution fails
     */
    private static String extractVersion(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, "-v");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            // pdftoppm -v returns exit code 99, but outputs version info
            if ((exitCode == 0 || exitCode == 99) && !output.isBlank()) {
                // Extract version from output: "pdftoppm version 23.09.0"
                // or "Copyright ... poppler 23.09.0"
                for (String line : output.lines().toList()) {
                    if (line.contains("version")) {
                        // "pdftoppm version 23.09.0" -> "poppler 23.09.0"
                        String[] parts = line.split("\\s+");
                        for (int i = 0; i < parts.length - 1; i++) {
                            if (parts[i].equals("version")) {
                                return "poppler " + parts[i + 1];
                            }
                        }
                    } else if (line.toLowerCase().contains("poppler")) {
                        // Extract "poppler X.Y.Z" from copyright line
                        if (line.matches(".*poppler\\s+\\d+\\.\\d+.*")) {
                            return line.replaceAll(".*?(poppler\\s+\\d+\\.\\d+\\.?\\d*).*", "$1");
                        }
                    }
                }

                // Fallback: return first non-empty line
                String firstLine = output.lines().findFirst().orElse("");
                if (!firstLine.isEmpty()) {
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
