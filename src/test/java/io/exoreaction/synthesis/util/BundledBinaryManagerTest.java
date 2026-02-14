package io.exoreaction.synthesis.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BundledBinaryManager -- platform detection, binary name resolution,
 * directory selection, and extraction lifecycle.
 *
 * <p>Note: Tests for actual binary extraction depend on whether binaries are
 * bundled in the JAR (they are not during development). The platform detection
 * and utility methods are tested deterministically.
 */
class BundledBinaryManagerTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetState() {
        BundledBinaryManager.resetCache();
    }

    @Test
    void testDetectPlatformReturnsNonNullOnSupportedPlatform() {
        String platform = BundledBinaryManager.detectPlatform();
        // On Linux x64, macOS, or Windows x64 this should return a value
        // On other platforms (e.g., ARM Linux) it may return null
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        if ((os.contains("linux") && (arch.contains("amd64") || arch.contains("x86_64")))
                || os.contains("mac") || os.contains("darwin")
                || (os.contains("windows") && (arch.contains("amd64") || arch.contains("x86_64")))) {
            assertNotNull(platform, "Should detect platform on supported OS/arch");
        }
    }

    @Test
    void testDetectPlatformLinux() {
        String platform = BundledBinaryManager.detectPlatform();
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        if (os.contains("linux") && (arch.contains("amd64") || arch.contains("x86_64"))) {
            assertEquals("linux-x64", platform);
        }
    }

    @Test
    void testDetectPlatformMac() {
        String platform = BundledBinaryManager.detectPlatform();
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("mac") || os.contains("darwin")) {
            assertEquals("darwin-universal", platform);
        }
    }

    @Test
    void testDetectPlatformWindows() {
        String platform = BundledBinaryManager.detectPlatform();
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        if (os.contains("windows") && (arch.contains("amd64") || arch.contains("x86_64"))) {
            assertEquals("windows-x64", platform);
        }
    }

    @Test
    void testGetBinaryNameUnix() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String binaryName = BundledBinaryManager.getBinaryName();

        if (os.contains("windows")) {
            assertEquals("ffprobe.exe", binaryName);
        } else {
            assertEquals("ffprobe", binaryName);
        }
    }

    @Test
    void testGetBinDirectoryDefault() {
        // Without synthesis.home override, should be ~/.synthesis/bin/
        Path binDir = BundledBinaryManager.getBinDirectory();
        assertNotNull(binDir);
        assertTrue(binDir.toString().endsWith("bin"),
                "Bin directory should end with 'bin': " + binDir);
        assertTrue(binDir.toString().contains(".synthesis"),
                "Bin directory should be under .synthesis: " + binDir);
    }

    @Test
    void testGetBinDirectoryWithOverride() {
        String original = System.getProperty("synthesis.home");
        try {
            System.setProperty("synthesis.home", tempDir.toString());
            Path binDir = BundledBinaryManager.getBinDirectory();
            assertEquals(tempDir.resolve("bin"), binDir);
        } finally {
            if (original != null) {
                System.setProperty("synthesis.home", original);
            } else {
                System.clearProperty("synthesis.home");
            }
        }
    }

    @Test
    void testGetFfprobePathReturnsCachedValue() {
        // First call -- may or may not find a bundled binary
        Path first = BundledBinaryManager.getFfprobePath();
        Path second = BundledBinaryManager.getFfprobePath();

        // Should return the same result both times (cached)
        assertEquals(first, second, "Cached result should be consistent");
    }

    @Test
    void testIsBundledAvailableConsistentWithGetPath() {
        Path path = BundledBinaryManager.getFfprobePath();
        boolean available = BundledBinaryManager.isBundledAvailable();

        if (path != null) {
            assertTrue(available, "isBundledAvailable should be true when path is non-null");
        }
        // Note: if path is null, isBundledAvailable may still be false (no binary bundled)
    }

    @Test
    void testResetCacheClearsState() {
        // Trigger detection
        BundledBinaryManager.getFfprobePath();

        // Reset
        BundledBinaryManager.resetCache();

        // After reset, isBundledAvailable should be false (cache cleared)
        assertFalse(BundledBinaryManager.isBundledAvailable(),
                "After reset, bundled should not be available until re-detected");
    }

    @Test
    void testGetBundledSourceDescription() {
        // Before any detection, should be null
        BundledBinaryManager.resetCache();
        String desc = BundledBinaryManager.getBundledSourceDescription();
        assertNull(desc, "Before detection, source description should be null");
    }

    @Test
    void testValidateBinaryWithNonexistentPath() {
        Path nonexistent = tempDir.resolve("nonexistent-ffprobe");
        boolean valid = BundledBinaryManager.validateBinary(nonexistent);
        assertFalse(valid, "Non-existent binary should not validate");
    }

    @Test
    void testValidateBinaryWithInvalidFile() throws Exception {
        // Create a file that is not a valid ffprobe binary
        Path fakeFile = tempDir.resolve("fake-ffprobe");
        Files.writeString(fakeFile, "this is not a binary");
        boolean valid = BundledBinaryManager.validateBinary(fakeFile);
        assertFalse(valid, "Non-executable file should not validate");
    }

    @Test
    void testExtractionAttemptedOnlyOnce() {
        // Reset to clear any previous state
        BundledBinaryManager.resetCache();

        // Call getFfprobePath multiple times -- extraction should only be attempted once
        // (verified by internal extractionAttempted flag; observable through consistent results)
        Path first = BundledBinaryManager.getFfprobePath();
        Path second = BundledBinaryManager.getFfprobePath();
        Path third = BundledBinaryManager.getFfprobePath();

        assertEquals(first, second);
        assertEquals(second, third);
    }
}
