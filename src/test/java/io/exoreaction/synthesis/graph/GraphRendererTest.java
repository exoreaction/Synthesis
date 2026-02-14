package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.graph.GraphBuilder.*;
import io.exoreaction.synthesis.graph.GraphRenderer.Format;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the GraphRenderer (DOT, Mermaid, PNG/SVG output).
 */
class GraphRendererTest {

    @TempDir
    Path tempDir;

    @Test
    void toDotProducesValidSyntax() {
        FileGraph graph = makeSimpleGraph();
        GraphRenderer renderer = new GraphRenderer();
        String dot = renderer.toDot(graph);

        assertTrue(dot.startsWith("digraph"));
        assertTrue(dot.contains("rankdir=LR"));
        assertTrue(dot.contains("Main.java"));
        assertTrue(dot.contains("Config.java"));
        assertTrue(dot.contains("->"));
        assertTrue(dot.endsWith("}\n"));
    }

    @Test
    void toDotIncludesNodeColors() {
        FileGraph graph = makeSimpleGraph();
        GraphRenderer renderer = new GraphRenderer();
        String dot = renderer.toDot(graph);

        assertTrue(dot.contains("fillcolor"));
        assertTrue(dot.contains("#4A90D9") || dot.contains("#5CB85C"),
                "Should have type-based coloring");
    }

    @Test
    void toDotGroupsByDirectory() {
        FileGraph graph = new FileGraph(
                List.of(
                        new GraphNode("src/Main.java", "Main.java", "CODE", "Java", null, 100, "src"),
                        new GraphNode("lib/Utils.java", "Utils.java", "CODE", "Java", null, 100, "lib")
                ),
                List.of(new GraphEdge("src/Main.java", "lib/Utils.java", "references", 1)),
                "Test Graph"
        );

        GraphRenderer renderer = new GraphRenderer();
        String dot = renderer.toDot(graph);

        assertTrue(dot.contains("subgraph cluster_"), "Should group by directory");
    }

    @Test
    void toDotHandlesEdgeWeight() {
        FileGraph graph = new FileGraph(
                List.of(
                        new GraphNode("A.java", "A.java", "CODE", "Java", null, 100, "."),
                        new GraphNode("B.java", "B.java", "CODE", "Java", null, 100, ".")
                ),
                List.of(new GraphEdge("A.java", "B.java", "references", 3)),
                "Weighted Edge Test"
        );

        GraphRenderer renderer = new GraphRenderer();
        String dot = renderer.toDot(graph);

        assertTrue(dot.contains("penwidth=3"), "Should render edge weight as penwidth");
    }

    @Test
    void toMermaidProducesValidSyntax() {
        FileGraph graph = makeSimpleGraph();
        GraphRenderer renderer = new GraphRenderer();
        String mermaid = renderer.toMermaid(graph);

        assertTrue(mermaid.contains("```mermaid"));
        assertTrue(mermaid.contains("graph LR"));
        assertTrue(mermaid.contains("-->"));
        assertTrue(mermaid.endsWith("```\n"));
    }

    @Test
    void toMermaidIncludesStyles() {
        FileGraph graph = makeSimpleGraph();
        GraphRenderer renderer = new GraphRenderer();
        String mermaid = renderer.toMermaid(graph);

        assertTrue(mermaid.contains("style "), "Should include node styles");
        assertTrue(mermaid.contains("fill:"), "Should have fill colors");
    }

    @Test
    void toMermaidShowsWeightedEdges() {
        FileGraph graph = new FileGraph(
                List.of(
                        new GraphNode("A.java", "A.java", "CODE", "Java", null, 100, "."),
                        new GraphNode("B.java", "B.java", "CODE", "Java", null, 100, ".")
                ),
                List.of(new GraphEdge("A.java", "B.java", "references", 3)),
                "Test"
        );

        GraphRenderer renderer = new GraphRenderer();
        String mermaid = renderer.toMermaid(graph);

        assertTrue(mermaid.contains("==>|3|"), "Should show edge weight");
    }

    @Test
    void renderDotFormat() throws IOException {
        FileGraph graph = makeSimpleGraph();
        GraphRenderer renderer = new GraphRenderer();
        Path output = tempDir.resolve("test.dot");

        boolean success = renderer.render(graph, Format.DOT, output);

        assertTrue(success);
        assertTrue(Files.exists(output));
        String content = Files.readString(output);
        assertTrue(content.contains("digraph"));
    }

    @Test
    void renderMermaidFormat() throws IOException {
        FileGraph graph = makeSimpleGraph();
        GraphRenderer renderer = new GraphRenderer();
        Path output = tempDir.resolve("test.md");

        boolean success = renderer.render(graph, Format.MERMAID, output);

        assertTrue(success);
        assertTrue(Files.exists(output));
        String content = Files.readString(output);
        assertTrue(content.contains("mermaid"));
    }

    @Test
    void renderPngFallsBackToMermaidWhenNoGraphviz() throws IOException {
        // This test verifies the fallback behavior
        // If Graphviz is not installed, it should write a Mermaid file instead
        FileGraph graph = makeSimpleGraph();
        GraphRenderer renderer = new GraphRenderer();
        Path output = tempDir.resolve("test.png");

        boolean success = renderer.render(graph, Format.PNG, output);

        if (GraphRenderer.isGraphvizAvailable()) {
            assertTrue(success);
            assertTrue(Files.exists(output));
        } else {
            // Fallback creates .md file
            assertFalse(success);
            Path mermaidFallback = tempDir.resolve("test.md");
            assertTrue(Files.exists(mermaidFallback));
        }
    }

    @Test
    void dotEscapesSpecialCharacters() {
        FileGraph graph = new FileGraph(
                List.of(new GraphNode("path/to/\"file\".java", "\"file\".java",
                        "CODE", "Java", null, 100, "path/to")),
                List.of(),
                "Test with \"quotes\""
        );

        GraphRenderer renderer = new GraphRenderer();
        String dot = renderer.toDot(graph);

        // Should not crash and should properly escape quotes
        assertNotNull(dot);
        assertTrue(dot.contains("\\\""));
    }

    @Test
    void mermaidSanitizesNodeIds() {
        FileGraph graph = new FileGraph(
                List.of(new GraphNode("src/main/Config.java", "Config.java",
                        "CODE", "Java", null, 100, "src/main")),
                List.of(),
                "Test"
        );

        GraphRenderer renderer = new GraphRenderer();
        String mermaid = renderer.toMermaid(graph);

        // IDs should only contain alphanumeric and underscore
        assertTrue(mermaid.contains("src_main_Config_java"),
                "Should sanitize special characters in IDs");
    }

    @Test
    void emptyGraphProducesValidOutput() {
        FileGraph graph = new FileGraph(List.of(), List.of(), "Empty Graph");
        GraphRenderer renderer = new GraphRenderer();

        String dot = renderer.toDot(graph);
        assertTrue(dot.contains("digraph"));

        String mermaid = renderer.toMermaid(graph);
        assertTrue(mermaid.contains("mermaid"));
    }

    @Test
    void graphWithRepositoryNodes() {
        FileGraph graph = new FileGraph(
                List.of(
                        new GraphNode("repo-a", "repo-a (10 files)", "REPOSITORY", null, "repo-a", 50000, "repo-a"),
                        new GraphNode("repo-b", "repo-b (5 files)", "REPOSITORY", null, "repo-b", 25000, "repo-b")
                ),
                List.of(new GraphEdge("repo-a", "repo-b", "depends", 2)),
                "Cross-repo graph"
        );

        GraphRenderer renderer = new GraphRenderer();
        String dot = renderer.toDot(graph);

        assertTrue(dot.contains("repo-a"));
        assertTrue(dot.contains("repo-b"));
        assertTrue(dot.contains("#E74C3C") || dot.contains("REPOSITORY"),
                "Should use repository color");
    }

    // Helper methods

    private FileGraph makeSimpleGraph() {
        return new FileGraph(
                List.of(
                        new GraphNode("Main.java", "Main.java", "CODE", "Java", null, 200, "."),
                        new GraphNode("Config.java", "Config.java", "CODE", "Java", null, 100, ".")
                ),
                List.of(new GraphEdge("Main.java", "Config.java", "references", 1)),
                "Test Graph"
        );
    }
}
