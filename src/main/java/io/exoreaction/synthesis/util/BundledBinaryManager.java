package io.exoreaction.synthesis.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Manages bundled binary resources (ffprobe) that are extracted on first use.
 *
 * <p>Binaries are bundled in the JAR at:
 * <pre>
 *   /binaries/linux-x64/ffprobe
 *   /binaries/darwin-universal/ffprobe
 *   /binaries/windows-x64/ffprobe.exe
 * </pre>
 *
 * <p>On first use, the appropriate binary for the current platform is extracted
 * to {@code ~/.synthesis/bin/} and made executable (on Unix systems). The extracted
 * binary is cached and reused across JVM restarts.
 *
 * <p>If the bundled binary is not available for the current platform (e.g., the JAR
 * was built without platform binaries), extraction silently fails and the system
 * falls back to the system PATH ffprobe.
 *
 * @see FfprobeDetector
 */
public class BundledBinaryManager {

    private static final String BINARIES_RESOURCE_PATH = "/binaries/";

    /** Version marker written alongside the extracted binary. */
    private static final String VERSION_MARKER_FILE = ".ffprobe-version";

    /** Cached path to the extracted ffprobe binary. Null means not yet attempted. */
    private static volatile Path cachedFfprobePath;

    /** Whether we have already attempted extraction (to avoid repeated failures). */
    private static volatile boolean extractionAttempted;

    private BundledBinaryManager() {}

    /**
     * Gets the path to the ffprobe binary, extracting from JAR if needed.
     *
     * <p>On the first call, this method will:
     * <ol>
     *   <li>Check if a previously extracted binary exists at {@code ~/.synthesis/bin/ffprobe}</li>
     *   <li>If not, extract the platform-appropriate binary from the JAR</li>
     *   <li>Set executable permissions (Linux/macOS)</li>
     *   <li>Validate the binary runs successfully</li>
     * </ol>
     *
     * <p>Subsequent calls return the cached path immediately (volatile + DCL pattern).
     *
     * @return Path to the ffprobe executable, or null if extraction fails or
     *         the binary is not bundled for this platform
     */
    public static Path getFfprobePath() {
        // Fast path: already resolved
        if (cachedFfprobePath != null && Files.exists(cachedFfprobePath)) {
            return cachedFfprobePath;
        }

        // Avoid repeated extraction attempts
        if (extractionAttempted) {
            return null;
        }

        synchronized (BundledBinaryManager.class) {
            // Double-check after acquiring lock
            if (cachedFfprobePath != null && Files.exists(cachedFfprobePath)) {
                return cachedFfprobePath;
            }
            if (extractionAttempted) {
                return null;
            }

            extractionAttempted = true;

            try {
                // Check if already extracted from a previous JVM run
                Path existingBinary = getExistingBinary();
                if (existingBinary != null && validateBinary(existingBinary)) {
                    cachedFfprobePath = existingBinary;
                    return existingBinary;
                }

                // Extract from JAR
                Path extractedPath = extractFfprobe();
                if (extractedPath != null && validateBinary(extractedPath)) {
                    cachedFfprobePath = extractedPath;
                    return extractedPath;
                }
            } catch (IOException e) {
                System.err.println("Warning: Could not extract bundled ffprobe: " + e.getMessage());
            }

            return null;
        }
    }

    /**
     * Checks if a previously extracted binary already exists and is valid.
     *
     * @return Path to existing binary, or null if not found
     */
    private static Path getExistingBinary() {
        Path binDir = getBinDirectory();
        String binaryName = getBinaryName();
        Path binaryPath = binDir.resolve(binaryName);

        if (Files.exists(binaryPath) && Files.isRegularFile(binaryPath)) {
            return binaryPath;
        }
        return null;
    }

    /**
     * Extracts the platform-appropriate ffprobe binary from the JAR.
     *
     * @return Path to the extracted binary, or null if the resource doesn't exist
     * @throws IOException if extraction fails (I/O error)
     */
    private static Path extractFfprobe() throws IOException {
        String platform = detectPlatform();
        if (platform == null) {
            return null; // Unsupported platform
        }

        String binaryName = getBinaryName();
        String resourcePath = BINARIES_RESOURCE_PATH + platform + "/" + binaryName;

        // Check if resource exists in JAR
        InputStream is = BundledBinaryManager.class.getResourceAsStream(resourcePath);
        if (is == null) {
            // Binary not bundled for this platform (expected when JAR is built
            // without platform binaries, e.g., during development)
            return null;
        }

        // Extract to ~/.synthesis/bin/
        Path binDir = getBinDirectory();
        Files.createDirectories(binDir);

        Path targetPath = binDir.resolve(binaryName);

        // Extract binary (atomic: write to temp then move)
        Path tempFile = binDir.resolve(binaryName + ".tmp");
        try (InputStream input = is) {
            Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);

        // Set executable permission on Unix systems — add execute bits only, preserve existing perms
        if (!isWindows()) {
            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(targetPath);
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(targetPath, perms);
            } catch (IOException | UnsupportedOperationException e) {
                // File system doesn't support POSIX permissions (e.g., FAT32)
                // The binary may still work if the default permissions allow execution
            }
        }

        return targetPath;
    }

    /**
     * Detects the current platform for binary selection.
     *
     * @return Platform identifier (e.g., "linux-x64", "darwin-universal", "windows-x64"),
     *         or null if the platform is not supported
     */
    static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        if (os.contains("linux") && (arch.contains("amd64") || arch.contains("x86_64"))) {
            return "linux-x64";
        } else if (os.contains("mac") || os.contains("darwin")) {
            // Universal binary works on both Intel and Apple Silicon
            return "darwin-universal";
        } else if (os.contains("windows") && (arch.contains("amd64") || arch.contains("x86_64"))) {
            return "windows-x64";
        }

        return null; // Unsupported platform
    }

    /**
     * Gets the platform-appropriate binary name.
     *
     * @return "ffprobe.exe" on Windows, "ffprobe" on Unix
     */
    static String getBinaryName() {
        return isWindows() ? "ffprobe.exe" : "ffprobe";
    }

    /**
     * Gets the directory where extracted binaries are stored.
     *
     * @return Path to {@code ~/.synthesis/bin/} (or {@code $synthesis.home/bin/} if overridden)
     */
    static Path getBinDirectory() {
        String homeStr = System.getProperty("synthesis.home");
        Path synthesisHome;
        if (homeStr != null) {
            synthesisHome = Path.of(homeStr);
        } else {
            synthesisHome = Path.of(System.getProperty("user.home"), ".synthesis");
        }
        return synthesisHome.resolve("bin");
    }

    /**
     * Validates that the extracted binary actually works by running {@code ffprobe -version}.
     *
     * @param binaryPath Path to the ffprobe binary
     * @return true if the binary executes successfully
     */
    static boolean validateBinary(Path binaryPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(binaryPath.toString(), "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // Read output to prevent blocking
            process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if a bundled binary was successfully extracted and is available.
     *
     * @return true if bundled ffprobe is available
     */
    public static boolean isBundledAvailable() {
        return cachedFfprobePath != null && Files.exists(cachedFfprobePath);
    }

    /**
     * Returns a human-readable description of the bundled binary source.
     * Used by StatusCommand for display.
     *
     * @return e.g., "Bundled" or null if not using bundled binary
     */
    public static String getBundledSourceDescription() {
        if (isBundledAvailable()) {
            return "Bundled";
        }
        return null;
    }

    /**
     * Returns true if running on Windows.
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    /**
     * Resets the cached state. Used for testing only.
     */
    static void resetCache() {
        synchronized (BundledBinaryManager.class) {
            cachedFfprobePath = null;
            extractionAttempted = false;
        }
    }
}
