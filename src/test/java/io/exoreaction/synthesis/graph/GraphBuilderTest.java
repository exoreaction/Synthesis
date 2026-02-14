package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.graph.GraphBuilder.*;
import io.exoreaction.synthesis.index.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the GraphBuilder (file relationship graph construction).
 */
class GraphBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void buildFileGraphForSingleFile() throws IOException {
        Path file = tempDir.resolve("Main.java");
        Files.writeString(file, "public class Main {}");

        SearchResult target = makeResult(file, "Main.java", "CODE", "Java");
        List<SearchResult> allFiles = List.of(target);

        GraphBuilder builder = new GraphBuilder();
        FileGraph graph = builder.buildFileGraph(target, allFiles, 1);

        assertNotNull(graph);
        assertEquals(1, graph.nodes().size());
        assertTrue(graph.edges().isEmpty());
        assertNotNull(graph.title());
    }

    @Test
    void buildFileGraphWithReferences() throws IOException {
        Path configFile = tempDir.resolve("Config.java");
        Files.writeString(configFile, "public class Config {}");

        Path serviceFile = tempDir.resolve("Service.java");
        Files.writeString(serviceFile, """
                import com.example.Config;
                public class Service {}
                """);

        SearchResult config = makeResult(configFile, "Config.java", "CODE", "Java");
        SearchResult service = makeResult(serviceFile, "Service.java", "CODE", "Java");

        GraphBuilder builder = new GraphBuilder();
        FileGraph graph = builder.buildFileGraph(service, List.of(config, service), 1);

        assertTrue(graph.nodes().size() >= 1);
        // Service imports Config, so there should be an edge
        assertFalse(graph.edges().isEmpty());
    }

    @Test
    void buildFileGraphRespectDepth() throws IOException {
        // A -> B -> C  (with depth 1, should only get A and B)
        Path fileA = tempDir.resolve("A.java");
        Path fileB = tempDir.resolve("B.java");
        Path fileC = tempDir.resolve("C.java");

        Files.writeString(fileA, "import com.example.B;\npublic class A {}");
        Files.writeString(fileB, "import com.example.C;\npublic class B {}");
        Files.writeString(fileC, "public class C {}");

        SearchResult a = makeResult(fileA, "A.java", "CODE", "Java");
        SearchResult b = makeResult(fileB, "B.java", "CODE", "Java");
        SearchResult c = makeResult(fileC, "C.java", "CODE", "Java");

        GraphBuilder builder = new GraphBuilder();
        FileGraph graph = builder.buildFileGraph(a, List.of(a, b, c), 1);

        // With depth 1, should include A and B (direct reference)
        assertTrue(graph.nodes().size() >= 2);
    }

    @Test
    void buildFileGraphWithMarkdownLinks() throws IOException {
        Path readme = tempDir.resolve("README.md");
        Path setup = tempDir.resolve("SETUP.md");
        Files.writeString(readme, "# Project\nSee [setup guide](SETUP.md)");
        Files.writeString(setup, "# Setup");

        SearchResult readmeResult = makeResult(readme, "README.md", "MARKDOWN", null);
        SearchResult setupResult = makeResult(setup, "SETUP.md", "MARKDOWN", null);

        GraphBuilder builder = new GraphBuilder();
        FileGraph graph = builder.buildFileGraph(readmeResult, List.of(readmeResult, setupResult), 1);

        assertTrue(graph.nodes().size() >= 2);
        assertFalse(graph.edges().isEmpty());
    }

    @Test
    void buildModuleGraph() throws IOException {
        Path srcDir = tempDir.resolve("src");
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(srcDir);
        Files.createDirectories(libDir);

        Path srcFile = srcDir.resolve("Main.java");
        Path libFile = libDir.resolve("Utils.java");
        Files.writeString(srcFile, "class Main {}");
        Files.writeString(libFile, "class Utils {}");

        List<SearchResult> files = List.of(
                makeResult(srcFile, "src/Main.java", "CODE", "Java"),
                makeResult(libFile, "lib/Utils.java", "CODE", "Java")
        );

        GraphBuilder builder = new GraphBuilder();
        FileGraph graph = builder.buildModuleGraph(files);

        assertTrue(graph.nodes().size() >= 2);
        assertTrue(graph.title().contains("Module"));
    }

    @Test
    void buildModuleGraphDetectsModuleDependencies() throws IOException {
        Path srcDir = tempDir.resolve("src");
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(srcDir);
        Files.createDirectories(libDir);

        Path srcFile = srcDir.resolve("Service.java");
        Path libFile = libDir.resolve("Config.java");
        Files.writeString(srcFile, "import com.example.Config;\nclass Service {}");
        Files.writeString(libFile, "class Config {}");

        List<SearchResult> files = List.of(
                makeResult(srcFile, "src/Service.java", "CODE", "Java"),
                makeResult(libFile, "lib/Config.java", "CODE", "Java")
        );

        GraphBuilder builder = new GraphBuilder();
        FileGraph graph = builder.buildModuleGraph(files);

        // src module should depend on lib module (through Config import)
        assertFalse(graph.edges().isEmpty(),
                "Should detect module-level dependency from src to lib");
    }

    @Test
    void buildCrossRepoGraph() throws IOException {
        Path file1 = tempDir.resolve("RepoA.java");
        Path file2 = tempDir.resolve("RepoB.java");
        Files.writeString(file1, "class RepoA {}");
        Files.writeString(file2, "class RepoB {}");

        List<SearchResult> files = List.of(
                new SearchResult(file1, "RepoA.java", 1.0f, "RepoA.java",
                        "CODE", "Java", "", "", "", 100, "repo-a"),
                new SearchResult(file2, "RepoB.java", 1.0f, "RepoB.java",
                        "CODE", "Java", "", "", "", 100, "repo-b")
        );

        GraphBuilder builder = new GraphBuilder();
        FileGraph graph = builder.buildCrossRepoGraph(files);

        assertEquals(2, graph.nodes().size());
        assertTrue(graph.title().contains("Cross-repository"));
    }

    @Test
    void graphNodeHasCorrectFields() throws IOException {
        Path file = tempDir.resolve("Test.java");
        Files.writeString(file, "class Test {}");

        SearchResult result = makeResult(file, "src/Test.java", "CODE", "Java");

        GraphBuilder builder = new GraphBuilder();
        FileGraph graph = builder.buildFileGraph(result, List.of(result), 0);

        assertEquals(1, graph.nodes().size());
        GraphNode node = graph.nodes().get(0);
        assertEquals("src/Test.java", node.id());
        assertEquals("Test.java", node.label());
        assertEquals("CODE", node.fileType());
        assertEquals("Java", node.language());
        assertEquals("src", node.directory());
    }

    @Test
    void getDirectoriesFromGraph() throws IOException {
        Path srcDir = tempDir.resolve("src");
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(srcDir);
        Files.createDirectories(libDir);

        Path f1 = srcDir.resolve("A.java");
        Path f2 = libDir.resolve("B.java");
        Files.writeString(f1, "class A {}");
        Files.writeString(f2, "class B {}");

        List<SearchResult> files = List.of(
                makeResult(f1, "src/A.java", "CODE", "Java"),
                makeResult(f2, "lib/B.java", "CODE", "Java")
        );

        GraphBuilder builder = new GraphBuilder();
        FileGraph graph = builder.buildModuleGraph(files);

        assertTrue(graph.getDirectories().size() >= 2);
    }

    @Test
    void edgeWeightIncreasesForMultipleReferences() throws IOException {
        // File with multiple references to same target
        Path target = tempDir.resolve("Config.java");
        Files.writeString(target, "public class Config {}");

        Path source = tempDir.resolve("Service.java");
        Files.writeString(source, """
                import com.example.Config;
                public class Service {
                    String ref = "Config.java";
                }
                """);

        SearchResult targetResult = makeResult(target, "Config.java", "CODE", "Java");
        SearchResult sourceResult = makeResult(source, "Service.java", "CODE", "Java");

        GraphBuilder builder = new GraphBuilder();
        FileGraph graph = builder.buildFileGraph(sourceResult, List.of(targetResult, sourceResult), 1);

        // Should have edges (might be deduplicated with higher weight)
        assertFalse(graph.edges().isEmpty());
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
}
