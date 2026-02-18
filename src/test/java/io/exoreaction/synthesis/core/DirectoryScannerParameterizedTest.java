package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.config.SynthesisConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized tests for DirectoryScanner — glob patterns, size limits, and exclusions.
 */
class DirectoryScannerParameterizedTest {

    // --- includePatterns: only matching files appear ---

    @ParameterizedTest
    @CsvSource({
        "**/*.java,  2",   // Main.java + src/App.java
        "**/*.py,    1",   // src/test.py
        "**/*.md,    1",   // README.md
        "**/*.yaml,  1",   // config.yaml
        "**/*.sh,    1",   // script.sh
        "src/**,     2",   // src/App.java + src/test.py
        "*.java,     1"    // only top-level Main.java
    })
    void includePatterns_limitResults(String pattern, int expectedCount, @TempDir Path tempDir)
            throws IOException {
        createStandardLayout(tempDir);

        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        config.setIncludePatterns(List.of(pattern));
        DirectoryScanner scanner = new DirectoryScanner(tempDir, config, false);

        ScanResult result = scanner.scan();
        assertEquals(expectedCount, result.fileCount(),
                "Pattern '" + pattern + "' should match " + expectedCount + " file(s)");
    }

    // --- excludePatterns: directory-level patterns prune subtrees ---

    @Test
    void excludePatterns_srcDirectory_prunesSubtree(@TempDir Path tempDir) throws IOException {
        createStandardLayout(tempDir);

        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        // Exclude the src/ directory — removes App.java + test.py
        config.setExcludePatterns(List.of("src/**"));
        DirectoryScanner scanner = new DirectoryScanner(tempDir, config, false);

        ScanResult result = scanner.scan();
        // Standard layout has 6 files: top 4 + src/App.java + src/test.py
        // After excluding src/**: expect 4
        assertEquals(4, result.fileCount(), "Excluding src/** should leave 4 top-level files");
        result.files().forEach(f ->
                assertFalse(f.relativePath().startsWith("src/"),
                        "No files from src/ should remain"));
    }

    @Test
    void excludePatterns_deepDirectory_prunesSubtree(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("data/cache"));
        Files.writeString(tempDir.resolve("data/report.txt"), "report");
        Files.writeString(tempDir.resolve("data/cache/cached.json"), "cached");
        Files.writeString(tempDir.resolve("top.txt"), "top");

        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        config.setExcludePatterns(List.of("**/cache/**"));
        DirectoryScanner scanner = new DirectoryScanner(tempDir, config, false);

        ScanResult result = scanner.scan();
        result.files().forEach(f ->
                assertFalse(f.relativePath().contains("cache/"),
                        "Cached files should be excluded"));
    }

    // --- maxFileSizeBytes ---

    @ParameterizedTest
    @CsvSource({
        "5,   0",   // 5 bytes → all files exceed this (they have real content)
        "10000, 6"  // 10KB → all 6 standard files included
    })
    void maxFileSizeBytes_filtersLargeFiles(long maxBytes, int expectedMin, @TempDir Path tempDir)
            throws IOException {
        createStandardLayout(tempDir);

        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        config.setMaxFileSizeBytes(maxBytes);
        DirectoryScanner scanner = new DirectoryScanner(tempDir, config, false);

        ScanResult result = scanner.scan();
        assertTrue(result.fileCount() >= expectedMin,
                "With maxFileSize=" + maxBytes + ", should have >= " + expectedMin + " files");
    }

    // --- hidden files excluded by default ---

    @ParameterizedTest
    @ValueSource(strings = {".hidden", ".env", ".gitignore"})
    void hiddenFiles_excludedFromScan(String hiddenFile, @TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(hiddenFile), "secret content");

        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        DirectoryScanner scanner = new DirectoryScanner(tempDir, config, false);

        ScanResult result = scanner.scan();
        result.files().forEach(f ->
                assertFalse(f.relativePath().equals(hiddenFile),
                        "Hidden file '" + hiddenFile + "' should be excluded"));
    }

    // --- nested directories scanned recursively ---

    @Test
    void nestedDirectories_allScanned(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("a/b/c"));
        Files.writeString(tempDir.resolve("a/b/c/deep.txt"), "deep file");
        Files.writeString(tempDir.resolve("a/b/middle.txt"), "middle file");
        Files.writeString(tempDir.resolve("top.txt"), "top file");

        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        DirectoryScanner scanner = new DirectoryScanner(tempDir, config, false);

        ScanResult result = scanner.scan();
        assertEquals(3, result.fileCount(), "Should recursively find all 3 files");
    }

    // --- computeHashes=false produces null hashes ---

    @Test
    void computeHashes_false_noHashesComputed(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("file.txt"), "some content");

        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        config.setComputeHashes(false);
        DirectoryScanner scanner = new DirectoryScanner(tempDir, config, false);

        ScanResult result = scanner.scan();
        result.files().forEach(f ->
                assertNull(f.contentHash(), "computeHashes=false should produce null hashes"));
    }

    // --- ScanResult metadata ---

    @Test
    void scanResult_durationNonNegative(@TempDir Path tempDir) throws IOException {
        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        DirectoryScanner scanner = new DirectoryScanner(tempDir, config, false);

        ScanResult result = scanner.scan();
        assertNotNull(result.duration());
        assertTrue(result.duration().toMillis() >= 0);
    }

    @Test
    void scanResult_scanTimeNonNull(@TempDir Path tempDir) throws IOException {
        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        DirectoryScanner scanner = new DirectoryScanner(tempDir, config, false);

        ScanResult result = scanner.scan();
        assertNotNull(result.scanTime());
    }

    // --- helper ---

    private void createStandardLayout(Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("README.md"), "# Project");
        Files.writeString(tempDir.resolve("Main.java"), "public class Main{}");
        Files.writeString(tempDir.resolve("config.yaml"), "name: test");
        Files.writeString(tempDir.resolve("script.sh"), "#!/bin/bash");
        Path src = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(src.resolve("App.java"), "package app;");
        Files.writeString(src.resolve("test.py"), "def main(): pass");
    }
}
