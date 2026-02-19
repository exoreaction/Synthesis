package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.graph.ViolationDetector.*;
import io.exoreaction.synthesis.index.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ViolationDetector}.
 *
 * <p>Tests layer assignment, import extraction, layering violation detection,
 * and circular dependency detection using synthetic Java files.
 */
class ViolationDetectorTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // Layer assignment
    // -----------------------------------------------------------------------

    @Test
    void getLayer_corePackage_returnsLayer1() {
        ViolationDetector detector = new ViolationDetector();
        assertEquals(1, detector.getLayer("core"));
        assertEquals(1, detector.getLayer("config"));
        assertEquals(1, detector.getLayer("util"));
    }

    @Test
    void getLayer_graphPackage_returnsLayer2() {
        ViolationDetector detector = new ViolationDetector();
        assertEquals(2, detector.getLayer("index"));
        assertEquals(2, detector.getLayer("graph"));
        assertEquals(2, detector.getLayer("search"));
    }

    @Test
    void getLayer_servicePackage_returnsLayer3() {
        ViolationDetector detector = new ViolationDetector();
        assertEquals(3, detector.getLayer("ai"));
        assertEquals(3, detector.getLayer("mcp"));
        assertEquals(3, detector.getLayer("lsp"));
        assertEquals(3, detector.getLayer("architecture"));
    }

    @Test
    void getLayer_cliPackage_returnsLayer4() {
        ViolationDetector detector = new ViolationDetector();
        assertEquals(4, detector.getLayer("cli"));
    }

    @Test
    void getLayer_unknownPackage_returnsZero() {
        ViolationDetector detector = new ViolationDetector();
        assertEquals(0, detector.getLayer("nonexistent"));
        assertEquals(0, detector.getLayer(null));
    }

    @Test
    void customLayerAssignments_overrideDefaults() {
        Map<String, Integer> custom = Map.of("core", 1, "cli", 2);
        ViolationDetector detector = new ViolationDetector(custom);
        assertEquals(2, detector.getLayer("cli"));
        assertEquals(0, detector.getLayer("ai")); // not in custom
    }

    // -----------------------------------------------------------------------
    // Package extraction
    // -----------------------------------------------------------------------

    @Test
    void extractPackageName_synthesisPath_returnsSubPackage() {
        ViolationDetector detector = new ViolationDetector();
        assertEquals("cli",
                detector.extractPackageName("src/main/java/io/exoreaction/synthesis/cli/GraphCommand.java"));
        assertEquals("ai",
                detector.extractPackageName("src/main/java/io/exoreaction/synthesis/ai/CodeExplainer.java"));
        assertEquals("graph",
                detector.extractPackageName("src/main/java/io/exoreaction/synthesis/graph/GraphBuilder.java"));
    }

    @Test
    void extractPackageName_nullOrEmpty_returnsNull() {
        ViolationDetector detector = new ViolationDetector();
        assertNull(detector.extractPackageName(null));
    }

    @Test
    void extractPackageFromImport_synthesisImport_returnsSubPackage() {
        ViolationDetector detector = new ViolationDetector();
        assertEquals("cli",
                detector.extractPackageFromImport("io.exoreaction.synthesis.cli.RelateCommand"));
        assertEquals("graph",
                detector.extractPackageFromImport("io.exoreaction.synthesis.graph.GraphBuilder"));
        assertEquals("core",
                detector.extractPackageFromImport("io.exoreaction.synthesis.core.WorkspaceManager"));
    }

    @Test
    void extractPackageFromImport_nonSynthesisImport_returnsNull() {
        ViolationDetector detector = new ViolationDetector();
        assertNull(detector.extractPackageFromImport("java.util.List"));
        assertNull(detector.extractPackageFromImport("com.fasterxml.jackson.databind.ObjectMapper"));
    }

    @Test
    void extractPackageFromImport_rootPackageClass_returnsNull() {
        ViolationDetector detector = new ViolationDetector();
        // SynthesisApp is directly in io.exoreaction.synthesis — no sub-package
        assertNull(detector.extractPackageFromImport("io.exoreaction.synthesis.SynthesisApp"));
    }

    // -----------------------------------------------------------------------
    // Import extraction
    // -----------------------------------------------------------------------

    @Test
    void extractImports_javaFile_findsProjectImports() throws IOException {
        Path javaFile = createJavaFile("src/main/java/io/exoreaction/synthesis/ai/CodeExplainer.java",
                """
                package io.exoreaction.synthesis.ai;

                import io.exoreaction.synthesis.cli.RelateCommand;
                import io.exoreaction.synthesis.core.WorkspaceManager;
                import java.util.List;

                public class CodeExplainer {}
                """);

        ViolationDetector detector = new ViolationDetector();
        SearchResult result = makeResult(javaFile,
                "src/main/java/io/exoreaction/synthesis/ai/CodeExplainer.java",
                "CodeExplainer.java", "CODE", "Java");

        List<String> imports = detector.extractImports(result);
        assertEquals(2, imports.size());
        assertTrue(imports.contains("io.exoreaction.synthesis.cli.RelateCommand"));
        assertTrue(imports.contains("io.exoreaction.synthesis.core.WorkspaceManager"));
        // java.util.List should NOT be included (not a project import)
    }

    @Test
    void extractImports_emptyFile_returnsEmpty() throws IOException {
        Path javaFile = createJavaFile(
                "src/main/java/io/exoreaction/synthesis/core/Empty.java",
                "package io.exoreaction.synthesis.core;\n\npublic class Empty {}");

        ViolationDetector detector = new ViolationDetector();
        SearchResult result = makeResult(javaFile,
                "src/main/java/io/exoreaction/synthesis/core/Empty.java",
                "Empty.java", "CODE", "Java");

        List<String> imports = detector.extractImports(result);
        assertTrue(imports.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Layering violations
    // -----------------------------------------------------------------------

    @Test
    void detectLayeringViolations_serviceImportsCli_findsViolation() throws IOException {
        Path javaFile = createJavaFile(
                "src/main/java/io/exoreaction/synthesis/ai/ExplainerService.java",
                """
                package io.exoreaction.synthesis.ai;

                import io.exoreaction.synthesis.cli.RelateCommand;

                public class ExplainerService {}
                """);

        ViolationDetector detector = new ViolationDetector();
        SearchResult result = makeResult(javaFile,
                "src/main/java/io/exoreaction/synthesis/ai/ExplainerService.java",
                "ExplainerService.java", "CODE", "Java");

        List<LayeringViolation> violations = detector.detectLayeringViolations(List.of(result));

        assertEquals(1, violations.size());
        LayeringViolation v = violations.get(0);
        assertEquals("ai", v.sourcePackage());
        assertEquals(3, v.sourceLayer());
        assertEquals("cli", v.targetPackage());
        assertEquals(4, v.targetLayer());
        assertEquals(1, v.severity());
    }

    @Test
    void detectLayeringViolations_cliImportsCore_noViolation() throws IOException {
        // CLI (layer 4) importing core (layer 1) is allowed (downward)
        Path javaFile = createJavaFile(
                "src/main/java/io/exoreaction/synthesis/cli/SomeCommand.java",
                """
                package io.exoreaction.synthesis.cli;

                import io.exoreaction.synthesis.core.WorkspaceManager;

                public class SomeCommand {}
                """);

        ViolationDetector detector = new ViolationDetector();
        SearchResult result = makeResult(javaFile,
                "src/main/java/io/exoreaction/synthesis/cli/SomeCommand.java",
                "SomeCommand.java", "CODE", "Java");

        List<LayeringViolation> violations = detector.detectLayeringViolations(List.of(result));
        assertTrue(violations.isEmpty(), "Downward dependency should not be a violation");
    }

    @Test
    void detectLayeringViolations_sameLayer_noViolation() throws IOException {
        // graph (layer 2) importing index (layer 2) is allowed (same layer)
        Path javaFile = createJavaFile(
                "src/main/java/io/exoreaction/synthesis/graph/Builder.java",
                """
                package io.exoreaction.synthesis.graph;

                import io.exoreaction.synthesis.index.SearchResult;

                public class Builder {}
                """);

        ViolationDetector detector = new ViolationDetector();
        SearchResult result = makeResult(javaFile,
                "src/main/java/io/exoreaction/synthesis/graph/Builder.java",
                "Builder.java", "CODE", "Java");

        List<LayeringViolation> violations = detector.detectLayeringViolations(List.of(result));
        assertTrue(violations.isEmpty(), "Same-layer dependency should not be a violation");
    }

    @Test
    void detectLayeringViolations_multipleViolationsInOneFile() throws IOException {
        Path javaFile = createJavaFile(
                "src/main/java/io/exoreaction/synthesis/core/SomeCore.java",
                """
                package io.exoreaction.synthesis.core;

                import io.exoreaction.synthesis.cli.GraphCommand;
                import io.exoreaction.synthesis.ai.CodeExplainer;

                public class SomeCore {}
                """);

        ViolationDetector detector = new ViolationDetector();
        SearchResult result = makeResult(javaFile,
                "src/main/java/io/exoreaction/synthesis/core/SomeCore.java",
                "SomeCore.java", "CODE", "Java");

        List<LayeringViolation> violations = detector.detectLayeringViolations(List.of(result));
        assertEquals(2, violations.size());
        // core(1) -> cli(4) = severity 3
        // core(1) -> ai(3) = severity 2
        assertTrue(violations.stream().anyMatch(v -> v.targetPackage().equals("cli") && v.severity() == 3));
        assertTrue(violations.stream().anyMatch(v -> v.targetPackage().equals("ai") && v.severity() == 2));
    }

    // -----------------------------------------------------------------------
    // Circular dependencies
    // -----------------------------------------------------------------------

    @Test
    void detectCircularDependencies_mutualImports_findsCycle() {
        ViolationDetector detector = new ViolationDetector();

        Map<String, Map<String, List<String>>> packageImports = new LinkedHashMap<>();
        packageImports.computeIfAbsent("config", k -> new LinkedHashMap<>())
                .put("core", List.of("io.exoreaction.synthesis.core.Ecosystem"));
        packageImports.computeIfAbsent("core", k -> new LinkedHashMap<>())
                .put("config", List.of("io.exoreaction.synthesis.config.SynthesisConfig"));

        List<CircularDependency> cycles = detector.detectCircularDependencies(packageImports);

        assertEquals(1, cycles.size());
        CircularDependency cycle = cycles.get(0);
        assertTrue(cycle.isDirect());
        // Either order is fine
        assertTrue(
                (cycle.packageA().equals("config") && cycle.packageB().equals("core")) ||
                (cycle.packageA().equals("core") && cycle.packageB().equals("config"))
        );
    }

    @Test
    void detectCircularDependencies_unidirectional_noCycle() {
        ViolationDetector detector = new ViolationDetector();

        Map<String, Map<String, List<String>>> packageImports = new LinkedHashMap<>();
        packageImports.computeIfAbsent("cli", k -> new LinkedHashMap<>())
                .put("core", List.of("io.exoreaction.synthesis.core.WorkspaceManager"));
        // core does NOT import cli — no cycle

        List<CircularDependency> cycles = detector.detectCircularDependencies(packageImports);
        assertTrue(cycles.isEmpty());
    }

    @Test
    void detectCircularDependencies_noDuplicates() {
        ViolationDetector detector = new ViolationDetector();

        // A->B and B->A should produce exactly one cycle, not two
        Map<String, Map<String, List<String>>> packageImports = new LinkedHashMap<>();
        packageImports.computeIfAbsent("alpha", k -> new LinkedHashMap<>())
                .put("beta", List.of("io.exoreaction.synthesis.beta.Foo"));
        packageImports.computeIfAbsent("beta", k -> new LinkedHashMap<>())
                .put("alpha", List.of("io.exoreaction.synthesis.alpha.Bar"));

        List<CircularDependency> cycles = detector.detectCircularDependencies(packageImports);
        assertEquals(1, cycles.size(), "Should report cycle only once");
    }

    // -----------------------------------------------------------------------
    // Full detect() integration
    // -----------------------------------------------------------------------

    @Test
    void detect_fullReport_includesBothTypes() throws IOException {
        // Create a file with a layering violation
        Path violating = createJavaFile(
                "src/main/java/io/exoreaction/synthesis/ai/BadService.java",
                """
                package io.exoreaction.synthesis.ai;
                import io.exoreaction.synthesis.cli.SomeCommand;
                public class BadService {}
                """);

        // Create files that form a circular dependency
        Path configFile = createJavaFile(
                "src/main/java/io/exoreaction/synthesis/config/MyConfig.java",
                """
                package io.exoreaction.synthesis.config;
                import io.exoreaction.synthesis.core.Ecosystem;
                public class MyConfig {}
                """);

        Path coreFile = createJavaFile(
                "src/main/java/io/exoreaction/synthesis/core/MyCoreClass.java",
                """
                package io.exoreaction.synthesis.core;
                import io.exoreaction.synthesis.config.SynthesisConfig;
                public class MyCoreClass {}
                """);

        // Create a clean file (no violations)
        Path cleanFile = createJavaFile(
                "src/main/java/io/exoreaction/synthesis/cli/CleanCommand.java",
                """
                package io.exoreaction.synthesis.cli;
                import io.exoreaction.synthesis.core.WorkspaceManager;
                public class CleanCommand {}
                """);

        List<SearchResult> files = List.of(
                makeResult(violating,
                        "src/main/java/io/exoreaction/synthesis/ai/BadService.java",
                        "BadService.java", "CODE", "Java"),
                makeResult(configFile,
                        "src/main/java/io/exoreaction/synthesis/config/MyConfig.java",
                        "MyConfig.java", "CODE", "Java"),
                makeResult(coreFile,
                        "src/main/java/io/exoreaction/synthesis/core/MyCoreClass.java",
                        "MyCoreClass.java", "CODE", "Java"),
                makeResult(cleanFile,
                        "src/main/java/io/exoreaction/synthesis/cli/CleanCommand.java",
                        "CleanCommand.java", "CODE", "Java")
        );

        ViolationDetector detector = new ViolationDetector();
        ViolationReport report = detector.detect(files, tempDir);

        assertTrue(report.hasViolations());
        assertEquals(4, report.javaFiles());
        assertEquals(4, report.totalFiles());

        // Should find the ai -> cli layering violation
        assertEquals(1, report.layeringViolations().size());
        assertEquals("ai", report.layeringViolations().get(0).sourcePackage());
        assertEquals("cli", report.layeringViolations().get(0).targetPackage());

        // Should find the config <-> core circular dependency
        assertEquals(1, report.circularDependencies().size());
    }

    @Test
    void detect_cleanCode_noViolations() throws IOException {
        Path cleanFile = createJavaFile(
                "src/main/java/io/exoreaction/synthesis/cli/CleanCommand.java",
                """
                package io.exoreaction.synthesis.cli;
                import io.exoreaction.synthesis.core.WorkspaceManager;
                import io.exoreaction.synthesis.index.SearchIndex;
                public class CleanCommand {}
                """);

        List<SearchResult> files = List.of(
                makeResult(cleanFile,
                        "src/main/java/io/exoreaction/synthesis/cli/CleanCommand.java",
                        "CleanCommand.java", "CODE", "Java")
        );

        ViolationDetector detector = new ViolationDetector();
        ViolationReport report = detector.detect(files, tempDir);

        assertFalse(report.hasViolations());
        assertEquals(0, report.totalViolations());
    }

    @Test
    void detect_nonJavaFiles_ignored() throws IOException {
        // Non-Java files should be ignored
        Path mdFile = tempDir.resolve("README.md");
        Files.createDirectories(mdFile.getParent());
        Files.writeString(mdFile, "# Hello");

        SearchResult md = new SearchResult(
                mdFile, "README.md", 1.0f, "README.md",
                "MARKDOWN", null, "", "", "", 10);

        ViolationDetector detector = new ViolationDetector();
        ViolationReport report = detector.detect(List.of(md), tempDir);

        assertFalse(report.hasViolations());
        assertEquals(1, report.totalFiles());
        assertEquals(0, report.javaFiles());
    }

    // -----------------------------------------------------------------------
    // buildPackageImportMap
    // -----------------------------------------------------------------------

    @Test
    void buildPackageImportMap_buildsCorrectStructure() throws IOException {
        Path file1 = createJavaFile(
                "src/main/java/io/exoreaction/synthesis/cli/Cmd.java",
                """
                package io.exoreaction.synthesis.cli;
                import io.exoreaction.synthesis.core.WorkspaceManager;
                import io.exoreaction.synthesis.index.SearchIndex;
                public class Cmd {}
                """);

        Path file2 = createJavaFile(
                "src/main/java/io/exoreaction/synthesis/core/Mgr.java",
                """
                package io.exoreaction.synthesis.core;
                import io.exoreaction.synthesis.config.SynthesisConfig;
                public class Mgr {}
                """);

        List<SearchResult> files = List.of(
                makeResult(file1,
                        "src/main/java/io/exoreaction/synthesis/cli/Cmd.java",
                        "Cmd.java", "CODE", "Java"),
                makeResult(file2,
                        "src/main/java/io/exoreaction/synthesis/core/Mgr.java",
                        "Mgr.java", "CODE", "Java")
        );

        ViolationDetector detector = new ViolationDetector();
        Map<String, Map<String, List<String>>> map = detector.buildPackageImportMap(files);

        // cli imports from core and index
        assertTrue(map.containsKey("cli"));
        assertTrue(map.get("cli").containsKey("core"));
        assertTrue(map.get("cli").containsKey("index"));

        // core imports from config
        assertTrue(map.containsKey("core"));
        assertTrue(map.get("core").containsKey("config"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Creates a Java file at the given relative path under tempDir.
     */
    private Path createJavaFile(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    /**
     * Creates a SearchResult with the given properties.
     */
    private SearchResult makeResult(Path absolutePath, String relativePath,
                                     String fileName, String fileType, String language) {
        long size = 0;
        try { size = Files.size(absolutePath); } catch (IOException ignored) {}
        return new SearchResult(absolutePath, relativePath, 1.0f, fileName,
                fileType, language, "", "", "", size);
    }
}
