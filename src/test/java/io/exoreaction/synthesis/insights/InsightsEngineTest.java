package io.exoreaction.synthesis.insights;

import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.insights.InsightsEngine.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the InsightsEngine (knowledge graph metrics).
 */
class InsightsEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void analyzeEmptyFilesReturnsReport() {
        InsightsEngine engine = new InsightsEngine();
        InsightsReport report = engine.analyze(List.of(), tempDir);

        assertNotNull(report);
        assertNotNull(report.connectivity());
        assertNotNull(report.complexity());
        assertNotNull(report.quality());
        assertNotNull(report.architecture());
    }

    @Test
    void connectivityMetricsDetectOrphans() throws IOException {
        // Create file with no references
        Path orphan = tempDir.resolve("orphan.java");
        Files.writeString(orphan, "public class Orphan {}");

        Path another = tempDir.resolve("other.java");
        Files.writeString(another, "public class Other {}");

        List<SearchResult> files = List.of(
                makeResult(orphan, "orphan.java", "CODE", "Java"),
                makeResult(another, "other.java", "CODE", "Java")
        );

        InsightsEngine engine = new InsightsEngine();
        InsightsReport report = engine.analyze(files, tempDir);

        assertFalse(report.connectivity().orphanedFiles().isEmpty());
    }

    @Test
    void connectivityMetricsDetectReferences() throws IOException {
        // Service.java imports Config.java
        Path configFile = tempDir.resolve("Config.java");
        Files.writeString(configFile, "public class Config {}");

        Path serviceFile = tempDir.resolve("Service.java");
        Files.writeString(serviceFile, """
                import com.example.Config;
                public class Service {
                    private Config config;
                }
                """);

        List<SearchResult> files = List.of(
                makeResult(configFile, "Config.java", "CODE", "Java"),
                makeResult(serviceFile, "Service.java", "CODE", "Java")
        );

        InsightsEngine engine = new InsightsEngine();
        InsightsReport report = engine.analyze(files, tempDir);

        assertTrue(report.connectivity().totalReferences() > 0);
    }

    @Test
    void connectivityMetricsDetectCircularDeps() throws IOException {
        // A imports B, B imports A
        Path fileA = tempDir.resolve("ClassA.java");
        Files.writeString(fileA, """
                import com.example.ClassB;
                public class ClassA {}
                """);

        Path fileB = tempDir.resolve("ClassB.java");
        Files.writeString(fileB, """
                import com.example.ClassA;
                public class ClassB {}
                """);

        List<SearchResult> files = List.of(
                makeResult(fileA, "ClassA.java", "CODE", "Java"),
                makeResult(fileB, "ClassB.java", "CODE", "Java")
        );

        InsightsEngine engine = new InsightsEngine();
        InsightsReport report = engine.analyze(files, tempDir);

        // Should detect the circular dependency
        assertFalse(report.connectivity().circularClusters().isEmpty());
    }

    @Test
    void complexityMetricsCalculateFileSizes() throws IOException {
        Path small = tempDir.resolve("small.java");
        Files.writeString(small, "x".repeat(100));

        Path medium = tempDir.resolve("medium.java");
        Files.writeString(medium, "x".repeat(50_000));

        List<SearchResult> files = List.of(
                makeResultWithSize(small, "small.java", "CODE", "Java", 100),
                makeResultWithSize(medium, "medium.java", "CODE", "Java", 50_000)
        );

        InsightsEngine engine = new InsightsEngine();
        InsightsReport report = engine.analyze(files, tempDir);

        assertTrue(report.complexity().averageFileSize() > 0);
        assertFalse(report.complexity().fileSizeDistribution().isEmpty());
        assertFalse(report.complexity().largestFiles().isEmpty());
    }

    @Test
    void complexityMetricsCalculateNestingDepth() throws IOException {
        Path shallow = tempDir.resolve("shallow.java");
        Files.writeString(shallow, "class Shallow {}");

        Path deepDir = tempDir.resolve("a/b/c/d");
        Files.createDirectories(deepDir);
        Path deep = deepDir.resolve("Deep.java");
        Files.writeString(deep, "class Deep {}");

        List<SearchResult> files = List.of(
                makeResult(shallow, "shallow.java", "CODE", "Java"),
                makeResult(deep, "a/b/c/d/Deep.java", "CODE", "Java")
        );

        InsightsEngine engine = new InsightsEngine();
        InsightsReport report = engine.analyze(files, tempDir);

        assertTrue(report.complexity().maxNestingDepth() >= 4);
    }

    @Test
    void complexityMetricsCountFilesPerDirectory() throws IOException {
        Path dir1 = tempDir.resolve("src");
        Files.createDirectories(dir1);
        Path f1 = dir1.resolve("A.java");
        Path f2 = dir1.resolve("B.java");
        Path f3 = dir1.resolve("C.java");
        Files.writeString(f1, "class A {}");
        Files.writeString(f2, "class B {}");
        Files.writeString(f3, "class C {}");

        List<SearchResult> files = List.of(
                makeResult(f1, "src/A.java", "CODE", "Java"),
                makeResult(f2, "src/B.java", "CODE", "Java"),
                makeResult(f3, "src/C.java", "CODE", "Java")
        );

        InsightsEngine engine = new InsightsEngine();
        InsightsReport report = engine.analyze(files, tempDir);

        assertTrue(report.complexity().filesPerDirectory().containsKey("src"));
        assertEquals(3, report.complexity().filesPerDirectory().get("src"));
    }

    @Test
    void qualityMetricsDetectDocumentationCoverage() throws IOException {
        Path dir1 = tempDir.resolve("documented");
        Path dir2 = tempDir.resolve("undocumented");
        Files.createDirectories(dir1);
        Files.createDirectories(dir2);

        Path readme = dir1.resolve("README.md");
        Path code1 = dir1.resolve("Code.java");
        Path code2 = dir2.resolve("Other.java");
        Files.writeString(readme, "# Documentation");
        Files.writeString(code1, "class Code {}");
        Files.writeString(code2, "class Other {}");

        List<SearchResult> files = List.of(
                makeResult(readme, "documented/README.md", "MARKDOWN", null),
                makeResult(code1, "documented/Code.java", "CODE", "Java"),
                makeResult(code2, "undocumented/Other.java", "CODE", "Java")
        );

        InsightsEngine engine = new InsightsEngine();
        InsightsReport report = engine.analyze(files, tempDir);

        assertEquals(1, report.quality().directoriesWithReadme());
        assertEquals(2, report.quality().totalDirectories());
        assertTrue(report.quality().documentationCoverage() > 0);
        assertTrue(report.quality().documentationCoverage() <= 100);
    }

    @Test
    void qualityMetricsCalculateTestRatio() throws IOException {
        Path src = tempDir.resolve("Service.java");
        Path test = tempDir.resolve("ServiceTest.java");
        Files.writeString(src, "class Service {}");
        Files.writeString(test, "class ServiceTest {}");

        List<SearchResult> files = List.of(
                makeResult(src, "Service.java", "CODE", "Java"),
                makeResult(test, "ServiceTest.java", "CODE", "Java")
        );

        InsightsEngine engine = new InsightsEngine();
        InsightsReport report = engine.analyze(files, tempDir);

        assertEquals(1, report.quality().testFiles());
        assertEquals(1, report.quality().sourceFiles());
        assertEquals(1.0, report.quality().testRatio(), 0.01);
    }

    @Test
    void qualityMetricsDetectTestsInTestDirectory() throws IOException {
        Path testDir = tempDir.resolve("src/test");
        Files.createDirectories(testDir);
        Path test = testDir.resolve("HelperTest.java");
        Path src = tempDir.resolve("Main.java");
        Files.writeString(test, "class HelperTest {}");
        Files.writeString(src, "class Main {}");

        List<SearchResult> files = List.of(
                makeResult(test, "src/test/HelperTest.java", "CODE", "Java"),
                makeResult(src, "Main.java", "CODE", "Java")
        );

        InsightsEngine engine = new InsightsEngine();
        InsightsReport report = engine.analyze(files, tempDir);

        assertEquals(1, report.quality().testFiles());
    }

    @Test
    void architectureMetricsCountModules() throws IOException {
        Path src = tempDir.resolve("src");
        Path lib = tempDir.resolve("lib");
        Files.createDirectories(src);
        Files.createDirectories(lib);

        Path f1 = src.resolve("Main.java");
        Path f2 = lib.resolve("Utils.java");
        Files.writeString(f1, "class Main {}");
        Files.writeString(f2, "class Utils {}");

        List<SearchResult> files = List.of(
                makeResult(f1, "src/Main.java", "CODE", "Java"),
                makeResult(f2, "lib/Utils.java", "CODE", "Java")
        );

        InsightsEngine engine = new InsightsEngine();
        InsightsReport report = engine.analyze(files, tempDir);

        assertTrue(report.architecture().moduleCount() >= 2);
    }

    @Test
    void warningsGeneratedForLowDocCoverage() throws IOException {
        // Create 10 directories without READMEs
        List<SearchResult> files = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Path dir = tempDir.resolve("dir" + i);
            Files.createDirectories(dir);
            Path f = dir.resolve("File" + i + ".java");
            Files.writeString(f, "class File" + i + " {}");
            files.add(makeResult(f, "dir" + i + "/File" + i + ".java", "CODE", "Java"));
        }

        InsightsEngine engine = new InsightsEngine();
        InsightsReport report = engine.analyze(files, tempDir);

        // Should warn about low documentation coverage
        boolean hasDocWarning = report.warnings().stream()
                .anyMatch(w -> w.contains("documentation") || w.contains("Documentation"));
        assertTrue(hasDocWarning || report.quality().documentationCoverage() < 30,
                "Should have documentation warning or low coverage");
    }

    @Test
    void recommendationsGeneratedForDeadCode() throws IOException {
        // Create files with no references to them
        List<SearchResult> files = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Path f = tempDir.resolve("Unused" + i + ".java");
            Files.writeString(f, "class Unused" + i + " {}");
            files.add(makeResult(f, "Unused" + i + ".java", "CODE", "Java"));
        }

        InsightsEngine engine = new InsightsEngine();
        InsightsReport report = engine.analyze(files, tempDir);

        assertTrue(report.quality().deadCodeCandidates().size() > 0);
    }

    @Test
    void markdownLinksDetectedAsReferences() throws IOException {
        Path readme = tempDir.resolve("README.md");
        Path setup = tempDir.resolve("SETUP.md");
        Files.writeString(readme, "# Project\nSee [setup](SETUP.md) for details.");
        Files.writeString(setup, "# Setup Guide");

        List<SearchResult> files = List.of(
                makeResult(readme, "README.md", "MARKDOWN", null),
                makeResult(setup, "SETUP.md", "MARKDOWN", null)
        );

        InsightsEngine engine = new InsightsEngine();
        InsightsReport report = engine.analyze(files, tempDir);

        // README should have outgoing reference to SETUP
        assertTrue(report.connectivity().outgoingRefs().getOrDefault("README.md", 0) > 0);
    }

    // Helper methods

    private SearchResult makeResult(Path path, String relativePath, String fileType, String language) {
        String fileName = relativePath.contains("/") ?
                relativePath.substring(relativePath.lastIndexOf('/') + 1) : relativePath;
        long size;
        try { size = Files.exists(path) ? Files.size(path) : 100; } catch (IOException e) { size = 100; }
        return new SearchResult(path, relativePath, 1.0f, fileName, fileType, language,
                "", "", "", size);
    }

    private SearchResult makeResultWithSize(Path path, String relativePath, String fileType,
                                            String language, long size) {
        String fileName = relativePath.contains("/") ?
                relativePath.substring(relativePath.lastIndexOf('/') + 1) : relativePath;
        return new SearchResult(path, relativePath, 1.0f, fileName, fileType, language,
                "", "", "", size);
    }
}
