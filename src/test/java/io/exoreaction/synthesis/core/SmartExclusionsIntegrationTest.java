package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.config.SynthesisConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for smart exclusion defaults.
 * Verifies that ecosystem-specific directories are automatically excluded when smart defaults are enabled.
 */
class SmartExclusionsIntegrationTest {

    @Test
    void nodeModulesAutoExcluded(@TempDir Path tempDir) throws IOException {
        // Create a JavaScript project
        Files.writeString(tempDir.resolve("package.json"), "{\"name\": \"test\"}\n");
        Files.writeString(tempDir.resolve("index.js"), "console.log('main');\n");

        // Create node_modules directory with files
        Path nodeModules = tempDir.resolve("node_modules");
        Files.createDirectories(nodeModules.resolve("lib"));
        Files.writeString(nodeModules.resolve("lib").resolve("index.js"), "console.log('lib');\n");

        // Scan with smart defaults enabled
        SynthesisConfig.ScanConfig scanConfig = new SynthesisConfig.ScanConfig();
        scanConfig.setUseSmartDefaults(true);
        DirectoryScanner scanner = new DirectoryScanner(tempDir, scanConfig, false);
        ScanResult result = scanner.scan();

        // Verify: index.js is indexed, but node_modules files are NOT
        List<String> indexedPaths = result.files().stream()
                .map(fm -> tempDir.relativize(fm.path()).toString())
                .toList();

        assertTrue(indexedPaths.contains("index.js"), "Main file should be indexed");
        assertFalse(indexedPaths.stream().anyMatch(p -> p.contains("node_modules")),
                "node_modules should be excluded");
    }

    @Test
    void venvAutoExcluded(@TempDir Path tempDir) throws IOException {
        // Create a Python project
        Files.writeString(tempDir.resolve("requirements.txt"), "requests==2.28.0\n");
        Files.writeString(tempDir.resolve("main.py"), "import requests\n");

        // Create venv directory with files
        Path venv = tempDir.resolve("venv");
        Files.createDirectories(venv.resolve("lib"));
        Files.writeString(venv.resolve("lib").resolve("test.py"), "import sys\n");

        // Scan with smart defaults enabled
        SynthesisConfig.ScanConfig scanConfig = new SynthesisConfig.ScanConfig();
        scanConfig.setUseSmartDefaults(true);
        DirectoryScanner scanner = new DirectoryScanner(tempDir, scanConfig, false);
        ScanResult result = scanner.scan();

        // Verify: main.py is indexed, but venv files are NOT
        List<String> indexedPaths = result.files().stream()
                .map(fm -> tempDir.relativize(fm.path()).toString())
                .toList();

        assertTrue(indexedPaths.contains("main.py"), "Main file should be indexed");
        assertTrue(indexedPaths.contains("requirements.txt"), "requirements.txt should be indexed");
        assertFalse(indexedPaths.stream().anyMatch(p -> p.contains("venv")),
                "venv should be excluded");
    }

    @Test
    void targetAutoExcluded(@TempDir Path tempDir) throws IOException {
        // Create a Maven project
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>\n");

        // Create src/main/java with source file
        Path srcMain = tempDir.resolve("src/main/java");
        Files.createDirectories(srcMain);
        Files.writeString(srcMain.resolve("Main.java"), "public class Main {}\n");

        // Create target directory with compiled files
        Path target = tempDir.resolve("target");
        Files.createDirectories(target.resolve("classes"));
        Files.writeString(target.resolve("classes").resolve("Main.class"), "compiled bytecode\n");

        // Scan with smart defaults enabled
        SynthesisConfig.ScanConfig scanConfig = new SynthesisConfig.ScanConfig();
        scanConfig.setUseSmartDefaults(true);
        DirectoryScanner scanner = new DirectoryScanner(tempDir, scanConfig, false);
        ScanResult result = scanner.scan();

        // Verify: source files are indexed, but target files are NOT
        List<String> indexedPaths = result.files().stream()
                .map(fm -> tempDir.relativize(fm.path()).toString())
                .toList();

        assertTrue(indexedPaths.contains("pom.xml"), "pom.xml should be indexed");
        assertTrue(indexedPaths.stream().anyMatch(p -> p.contains("Main.java")),
                "Source files should be indexed");
        assertFalse(indexedPaths.stream().anyMatch(p -> p.contains("target")),
                "target directory should be excluded");
    }

    @Test
    void userExclusionsAddedToSmartDefaults(@TempDir Path tempDir) throws IOException {
        // Create a JavaScript project
        Files.writeString(tempDir.resolve("package.json"), "{\"name\": \"test\"}\n");
        Files.writeString(tempDir.resolve("index.js"), "console.log('main');\n");

        // Create custom directory to exclude
        Path custom = tempDir.resolve("custom-excluded");
        Files.createDirectories(custom);
        Files.writeString(custom.resolve("file.js"), "console.log('custom');\n");

        // Create node_modules (should be auto-excluded)
        Path nodeModules = tempDir.resolve("node_modules");
        Files.createDirectories(nodeModules);
        Files.writeString(nodeModules.resolve("lib.js"), "console.log('lib');\n");

        // Scan with smart defaults + custom exclusion
        SynthesisConfig.ScanConfig scanConfig = new SynthesisConfig.ScanConfig();
        scanConfig.setUseSmartDefaults(true);
        scanConfig.setExcludePatterns(List.of("custom-excluded/**"));
        DirectoryScanner scanner = new DirectoryScanner(tempDir, scanConfig, false);
        ScanResult result = scanner.scan();

        // Verify: both smart defaults and user patterns are applied
        List<String> indexedPaths = result.files().stream()
                .map(fm -> tempDir.relativize(fm.path()).toString())
                .toList();

        assertTrue(indexedPaths.contains("index.js"), "Main file should be indexed");
        assertFalse(indexedPaths.stream().anyMatch(p -> p.contains("node_modules")),
                "node_modules should be auto-excluded");
        assertFalse(indexedPaths.stream().anyMatch(p -> p.contains("custom-excluded")),
                "User patterns should also be applied");
    }

    @Test
    void disableSmartDefaults(@TempDir Path tempDir) throws IOException {
        // Create a JavaScript project
        Files.writeString(tempDir.resolve("package.json"), "{\"name\": \"test\"}\n");
        Files.writeString(tempDir.resolve("index.js"), "console.log('main');\n");

        // Create node_modules directory
        Path nodeModules = tempDir.resolve("node_modules");
        Files.createDirectories(nodeModules);
        Files.writeString(nodeModules.resolve("lib.js"), "console.log('lib');\n");

        // Scan with smart defaults DISABLED
        SynthesisConfig.ScanConfig scanConfig = new SynthesisConfig.ScanConfig();
        scanConfig.setUseSmartDefaults(false);
        scanConfig.setExcludePatterns(List.of()); // Explicitly empty
        DirectoryScanner scanner = new DirectoryScanner(tempDir, scanConfig, false);
        ScanResult result = scanner.scan();

        // Verify: node_modules IS indexed when smart defaults are disabled
        List<String> indexedPaths = result.files().stream()
                .map(fm -> tempDir.relativize(fm.path()).toString())
                .toList();

        assertTrue(indexedPaths.contains("index.js"), "Main file should be indexed");
        assertTrue(indexedPaths.stream().anyMatch(p -> p.contains("node_modules")),
                "node_modules should be indexed when smart defaults disabled");
    }

    @Test
    void packageJsonIndexed(@TempDir Path tempDir) throws IOException {
        // Create a JavaScript project
        Files.writeString(tempDir.resolve("package.json"), "{\"name\": \"test\"}\n");

        // Create node_modules directory
        Path nodeModules = tempDir.resolve("node_modules");
        Files.createDirectories(nodeModules);
        Files.writeString(nodeModules.resolve("lib.js"), "console.log('lib');\n");

        // Scan with smart defaults enabled
        SynthesisConfig.ScanConfig scanConfig = new SynthesisConfig.ScanConfig();
        scanConfig.setUseSmartDefaults(true);
        DirectoryScanner scanner = new DirectoryScanner(tempDir, scanConfig, false);
        ScanResult result = scanner.scan();

        // Verify: package.json itself is indexed (not excluded)
        List<String> indexedPaths = result.files().stream()
                .map(fm -> tempDir.relativize(fm.path()).toString())
                .toList();

        assertTrue(indexedPaths.contains("package.json"), "package.json should be indexed");
        assertFalse(indexedPaths.stream().anyMatch(p -> p.contains("node_modules")),
                "node_modules should be excluded");
    }

    @Test
    void multipleEcosystems(@TempDir Path tempDir) throws IOException {
        // Create a monorepo with multiple ecosystems
        Files.writeString(tempDir.resolve("package.json"), "{\"name\": \"monorepo\"}\n");
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>\n");
        Files.writeString(tempDir.resolve("index.js"), "console.log('js');\n");

        // Create ecosystem-specific directories
        Path nodeModules = tempDir.resolve("node_modules");
        Files.createDirectories(nodeModules);
        Files.writeString(nodeModules.resolve("lib.js"), "console.log('lib');\n");

        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("Main.class"), "compiled\n");

        // Scan with smart defaults enabled
        SynthesisConfig.ScanConfig scanConfig = new SynthesisConfig.ScanConfig();
        scanConfig.setUseSmartDefaults(true);
        DirectoryScanner scanner = new DirectoryScanner(tempDir, scanConfig, false);
        ScanResult result = scanner.scan();

        // Verify: both node_modules and target are excluded
        List<String> indexedPaths = result.files().stream()
                .map(fm -> tempDir.relativize(fm.path()).toString())
                .toList();

        assertTrue(indexedPaths.contains("package.json"), "package.json should be indexed");
        assertTrue(indexedPaths.contains("pom.xml"), "pom.xml should be indexed");
        assertTrue(indexedPaths.contains("index.js"), "index.js should be indexed");
        assertFalse(indexedPaths.stream().anyMatch(p -> p.contains("node_modules")),
                "node_modules should be excluded");
        assertFalse(indexedPaths.stream().anyMatch(p -> p.contains("target")),
                "target should be excluded");
    }

    @Test
    void gitAlwaysExcluded(@TempDir Path tempDir) throws IOException {
        // Create a project with no specific ecosystem
        Files.writeString(tempDir.resolve("README.md"), "# Test\n");

        // Create .git directory (universal exclusion)
        Path git = tempDir.resolve(".git");
        Files.createDirectories(git);
        Files.writeString(git.resolve("config"), "[core]\n");

        // Scan with smart defaults enabled
        SynthesisConfig.ScanConfig scanConfig = new SynthesisConfig.ScanConfig();
        scanConfig.setUseSmartDefaults(true);
        DirectoryScanner scanner = new DirectoryScanner(tempDir, scanConfig, false);
        ScanResult result = scanner.scan();

        // Verify: .git is always excluded (universal pattern)
        List<String> indexedPaths = result.files().stream()
                .map(fm -> tempDir.relativize(fm.path()).toString())
                .toList();

        assertTrue(indexedPaths.contains("README.md"), "README.md should be indexed");
        assertFalse(indexedPaths.stream().anyMatch(p -> p.contains(".git")),
                ".git should be excluded (universal)");
    }

    @Test
    void effectiveExcludePatternsReturnsCorrectList(@TempDir Path tempDir) throws IOException {
        // Create a JavaScript + Python project
        Files.writeString(tempDir.resolve("package.json"), "{\"name\": \"test\"}\n");
        Files.writeString(tempDir.resolve("requirements.txt"), "requests==2.28.0\n");

        SynthesisConfig.ScanConfig scanConfig = new SynthesisConfig.ScanConfig();
        scanConfig.setUseSmartDefaults(true);
        scanConfig.setExcludePatterns(List.of("custom/**"));

        List<String> effective = scanConfig.getEffectiveExcludePatterns(tempDir);

        // Verify: contains universal, JavaScript, Python, and user patterns
        assertTrue(effective.contains(".git/**") || effective.contains("**/.git/**"),
                "Should include universal patterns");
        assertTrue(effective.contains("node_modules/**") || effective.contains("**/node_modules/**"),
                "Should include JavaScript patterns");
        assertTrue(effective.contains("venv/**") || effective.contains("**/venv/**"),
                "Should include Python patterns");
        assertTrue(effective.contains("custom/**"), "Should include user patterns");
    }

    @Test
    void effectiveExcludePatternsWithSmartDefaultsDisabled(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("package.json"), "{\"name\": \"test\"}\n");

        SynthesisConfig.ScanConfig scanConfig = new SynthesisConfig.ScanConfig();
        scanConfig.setUseSmartDefaults(false);
        scanConfig.setExcludePatterns(List.of("custom/**"));

        List<String> effective = scanConfig.getEffectiveExcludePatterns(tempDir);

        // Verify: only user patterns, no smart defaults
        assertEquals(List.of("custom/**"), effective,
                "Should only return user patterns when smart defaults disabled");
    }
}
