package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryScannerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        // Create a test directory structure
        Files.writeString(tempDir.resolve("README.md"), "# Test Project\n\nA test project.");
        Files.writeString(tempDir.resolve("Main.java"), "public class Main { }");
        Files.writeString(tempDir.resolve("config.yaml"), "name: test\nversion: 1.0");
        Files.writeString(tempDir.resolve("script.sh"), "#!/bin/bash\necho hello");

        // Create a subdirectory with files
        Path subDir = tempDir.resolve("src");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("App.java"), "package src;\npublic class App { }");
        Files.writeString(subDir.resolve("test.py"), "def main():\n    pass");

        // Create an excluded directory
        Path gitDir = tempDir.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("config"), "should be excluded");

        // Create a hidden file (should be excluded)
        Files.writeString(tempDir.resolve(".hidden"), "hidden file");
    }

    @Test
    void scanDiscoversAllMatchingFiles() throws IOException {
        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        DirectoryScanner scanner = new DirectoryScanner(tempDir, config, false);

        ScanResult result = scanner.scan();

        // Should find: README.md, Main.java, config.yaml, script.sh, App.java, test.py
        // Should NOT find: .git/config, .hidden
        assertEquals(6, result.fileCount(), "Should find 6 files (excluding .git/ and hidden files)");
    }

    @Test
    void scanClassifiesFileTypesCorrectly() throws IOException {
        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        DirectoryScanner scanner = new DirectoryScanner(tempDir, config, false);

        ScanResult result = scanner.scan();

        var byType = result.countByType();
        assertEquals(1, byType.getOrDefault(FileUtils.FileType.MARKDOWN, 0L), "Should have 1 markdown file");
        assertEquals(4, byType.getOrDefault(FileUtils.FileType.CODE, 0L), "Should have 4 code files (Main.java, App.java, test.py, script.sh)");
        assertEquals(1, byType.getOrDefault(FileUtils.FileType.YAML, 0L), "Should have 1 yaml file");
    }

    @Test
    void scanDetectsLanguagesCorrectly() throws IOException {
        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        DirectoryScanner scanner = new DirectoryScanner(tempDir, config, false);

        ScanResult result = scanner.scan();

        var byLanguage = result.countByLanguage();
        assertEquals(2, byLanguage.getOrDefault("Java", 0L), "Should detect 2 Java files");
        assertEquals(1, byLanguage.getOrDefault("Python", 0L), "Should detect 1 Python file");
        assertEquals(1, byLanguage.getOrDefault("Shell", 0L), "Should detect 1 Shell file");
    }

    @Test
    void scanExcludesGitDirectory() throws IOException {
        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        DirectoryScanner scanner = new DirectoryScanner(tempDir, config, false);

        ScanResult result = scanner.scan();

        boolean hasGitFile = result.files().stream()
                .anyMatch(f -> f.relativePath().contains(".git"));
        assertFalse(hasGitFile, "Should not include files from .git directory");
    }

    @Test
    void scanComputesHashes() throws IOException {
        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        config.setComputeHashes(true);
        DirectoryScanner scanner = new DirectoryScanner(tempDir, config, false);

        ScanResult result = scanner.scan();

        long withHashes = result.files().stream()
                .filter(f -> f.contentHash() != null)
                .count();
        assertTrue(withHashes > 0, "Should compute hashes for text files");
    }

    @Test
    void scanReportsCorrectTotalSize() throws IOException {
        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        DirectoryScanner scanner = new DirectoryScanner(tempDir, config, false);

        ScanResult result = scanner.scan();

        assertTrue(result.totalSizeBytes() > 0, "Total size should be positive");
        assertTrue(result.duration().toMillis() >= 0, "Duration should be non-negative");
    }

    @Test
    void scanHandlesEmptyDirectory() throws IOException {
        Path emptyDir = tempDir.resolve("empty");
        Files.createDirectories(emptyDir);

        SynthesisConfig.ScanConfig config = new SynthesisConfig.ScanConfig();
        DirectoryScanner scanner = new DirectoryScanner(emptyDir, config, false);

        ScanResult result = scanner.scan();

        assertEquals(0, result.fileCount(), "Empty directory should have 0 files");
    }
}
