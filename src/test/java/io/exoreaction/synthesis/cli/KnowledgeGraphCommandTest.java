package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KnowledgeGraphCommand} -- knowledge graph visualization.
 */
class KnowledgeGraphCommandTest {

    @TempDir
    Path workspace;

    private DirectoryIdentityParser parser;
    private ByteArrayOutputStream outputCapture;
    private PrintStream captureStream;

    @BeforeEach
    void setup() {
        parser = new DirectoryIdentityParser();
        outputCapture = new ByteArrayOutputStream();
        captureStream = new PrintStream(outputCapture);
    }

    @Test
    void collectsNodesFromDirectories() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topic A"), List.of("EntityA"), 0.8, 5);
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("topic B"), List.of("EntityB"), 0.6, 3);

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);

        assertEquals(2, nodes.size());
        assertTrue(nodes.stream().anyMatch(n -> n.path().equals("alpha")));
        assertTrue(nodes.stream().anyMatch(n -> n.path().equals("beta")));
    }

    @Test
    void renderAsciiShowsNodes() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("mydir"),
                List.of("renewable energy"), List.of("GreenField"),
                0.85, 5);

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);

        String ascii = cmd.renderAscii(nodes, List.of(), workspace);
        assertTrue(ascii.contains("mydir"));
        assertTrue(ascii.contains("renewable energy"));
        assertTrue(ascii.contains("GreenField"));
    }

    @Test
    void renderMermaidProducesValidMarkdown() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topicA"), List.of(), 0.7, 3);
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("topicB"), List.of(), 0.6, 2);

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);

        String mermaid = cmd.renderMermaid(nodes, List.of(), workspace);
        assertTrue(mermaid.contains("```mermaid"));
        assertTrue(mermaid.contains("graph TD"));
        assertTrue(mermaid.contains("```"));
        assertTrue(mermaid.contains("dir0"));
        assertTrue(mermaid.contains("dir1"));
    }

    @Test
    void renderJsonProducesValidStructure() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topicA"), List.of("Entity1"), 0.8, 4);

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);

        String json = cmd.renderJson(nodes, List.of(), workspace);
        assertTrue(json.contains("\"workspace\""));
        assertTrue(json.contains("\"directories\""));
        assertTrue(json.contains("\"virtualMemberships\""));
        assertTrue(json.contains("\"topicA\""));
        assertTrue(json.contains("\"Entity1\""));
        assertTrue(json.contains("\"confidence\""));
    }

    @Test
    void filterByEntitySelectsRelevant() {
        List<KnowledgeGraphCommand.KnowledgeNode> nodes = List.of(
                new KnowledgeGraphCommand.KnowledgeNode(
                        "alpha", List.of("t"), List.of("GreenField Energy"),
                        0.8, 5, 0, "healthy"),
                new KnowledgeGraphCommand.KnowledgeNode(
                        "beta", List.of("t"), List.of("Other Corp"),
                        0.7, 3, 0, "healthy")
        );

        List<KnowledgeGraphCommand.KnowledgeNode> filtered =
                KnowledgeGraphCommand.filterByEntity(nodes, "GreenField");

        assertEquals(1, filtered.size());
        assertEquals("alpha", filtered.get(0).path());
    }

    @Test
    void filterByEntityCaseInsensitive() {
        List<KnowledgeGraphCommand.KnowledgeNode> nodes = List.of(
                new KnowledgeGraphCommand.KnowledgeNode(
                        "dir", List.of(), List.of("GreenField Energy"),
                        0.8, 5, 0, "healthy")
        );

        List<KnowledgeGraphCommand.KnowledgeNode> filtered =
                KnowledgeGraphCommand.filterByEntity(nodes, "greenfield");

        assertEquals(1, filtered.size());
    }

    @Test
    void emptyWorkspaceProducesEmptyNodes() throws IOException {
        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);
        assertTrue(nodes.isEmpty());
    }

    @Test
    void edgesEmptyWhenNoDatabase() {
        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeEdge> edges =
                cmd.collectEdges(workspace);
        assertTrue(edges.isEmpty());
    }

    @Test
    void asciiShowsStatusIcons() {
        List<KnowledgeGraphCommand.KnowledgeNode> nodes = List.of(
                new KnowledgeGraphCommand.KnowledgeNode(
                        "healthy-dir", List.of("t"), List.of(),
                        0.8, 5, 0, "healthy"),
                new KnowledgeGraphCommand.KnowledgeNode(
                        "starving-dir", List.of("t"), List.of(),
                        0.0, 0, 0, "starving"),
                new KnowledgeGraphCommand.KnowledgeNode(
                        "bootstrap-dir", List.of("t"), List.of(),
                        0.1, 1, 0, "bootstrapping")
        );

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        String ascii = cmd.renderAscii(nodes, List.of(), workspace);
        assertTrue(ascii.contains("[OK]"));
        assertTrue(ascii.contains("[!!]"));
        assertTrue(ascii.contains("[..]"));
    }

    @Test
    void mermaidShowsEntityCrossLinks() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topicA"), List.of("SharedEntity"), 0.7, 3);
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("topicB"), List.of("SharedEntity"), 0.6, 2);

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);

        String mermaid = cmd.renderMermaid(nodes, List.of(), workspace);
        // Should contain an entity cross-link
        assertTrue(mermaid.contains("sharedentity"),
                "Mermaid should contain entity cross-link");
    }

    @Test
    void shortenPathWorks() {
        assertEquals("short", KnowledgeGraphCommand.shortenPath("short", 30));
        String longPath = "/very/long/path/to/some/file/that/is/too/long.txt";
        String shortened = KnowledgeGraphCommand.shortenPath(longPath, 20);
        assertTrue(shortened.startsWith("..."));
        assertEquals(20, shortened.length());
    }

    @Test
    void escapeJsonHandlesSpecialChars() {
        assertEquals("hello\\\"world", KnowledgeGraphCommand.escapeJson("hello\"world"));
        assertEquals("path\\\\file", KnowledgeGraphCommand.escapeJson("path\\file"));
        assertEquals("line1\\nline2", KnowledgeGraphCommand.escapeJson("line1\nline2"));
        assertEquals("", KnowledgeGraphCommand.escapeJson(null));
    }

    // ---- #276: cross-reference edges and unknown status hint ----

    @Test
    void collectCrossReferenceEdges_findsMarkdownLinks() throws IOException {
        // Create two directories with cross-references in markdown files
        Path alpha = workspace.resolve("alpha");
        Path beta = workspace.resolve("beta");
        createDirectoryWithCentroid(alpha, List.of("topicA"), List.of(), 0.7, 3);
        createDirectoryWithCentroid(beta, List.of("topicB"), List.of(), 0.6, 2);

        // Create a markdown file in alpha that links to beta
        Files.writeString(alpha.resolve("README.md"),
                "See the [beta docs](../beta/overview.md) for details.\n");

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);
        List<KnowledgeGraphCommand.KnowledgeEdge> crossRefEdges =
                cmd.collectCrossReferenceEdges(workspace, nodes);

        assertFalse(crossRefEdges.isEmpty(),
                "Should find cross-reference edges from markdown links");
        assertTrue(crossRefEdges.stream().anyMatch(
                e -> e.filePath().contains("alpha") && e.directoryPath().contains("beta")),
                "Should have edge from alpha to beta: " + crossRefEdges);
    }

    @Test
    void collectCrossReferenceEdges_emptyWhenNoLinks() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topicA"), List.of(), 0.7, 3);
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("topicB"), List.of(), 0.6, 2);

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);
        List<KnowledgeGraphCommand.KnowledgeEdge> crossRefEdges =
                cmd.collectCrossReferenceEdges(workspace, nodes);

        assertTrue(crossRefEdges.isEmpty(),
                "Should be empty when no cross-references exist");
    }

    @Test
    void renderAscii_showsHintWhenMostNodesUnknown() {
        // All nodes have "unknown" status (no health block)
        List<KnowledgeGraphCommand.KnowledgeNode> nodes = List.of(
                new KnowledgeGraphCommand.KnowledgeNode(
                        "dir1", List.of("t"), List.of(), 0.5, 3, 0, "unknown"),
                new KnowledgeGraphCommand.KnowledgeNode(
                        "dir2", List.of("t"), List.of(), 0.5, 2, 0, "unknown"),
                new KnowledgeGraphCommand.KnowledgeNode(
                        "dir3", List.of("t"), List.of(), 0.5, 1, 0, "unknown")
        );

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        String ascii = cmd.renderAscii(nodes, List.of(), workspace);

        assertTrue(ascii.contains("maintain"),
                "Should suggest 'maintain' when most nodes show [??]: " + ascii);
    }

    @Test
    void renderAscii_noHintWhenNodesHealthy() {
        List<KnowledgeGraphCommand.KnowledgeNode> nodes = List.of(
                new KnowledgeGraphCommand.KnowledgeNode(
                        "dir1", List.of("t"), List.of(), 0.8, 5, 0, "healthy"),
                new KnowledgeGraphCommand.KnowledgeNode(
                        "dir2", List.of("t"), List.of(), 0.7, 3, 0, "healthy")
        );

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        String ascii = cmd.renderAscii(nodes, List.of(), workspace);

        assertFalse(ascii.contains("maintain"),
                "Should NOT show hint when nodes are healthy");
    }

    // ---- helpers ----

    private void createDirectoryWithCentroid(Path dir, List<String> topics,
                                              List<String> entities,
                                              double confidence,
                                              int files) throws IOException {
        Files.createDirectories(dir);

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of(), List.of(), List.of(),
                ScopeLevel.ORGANIZATION, null, null,
                0.5, Instant.now(), "test", "",
                List.of(), List.of(), false, List.of());

        DirectoryCentroid centroid = new DirectoryCentroid(
                topics, entities, "2026-Q1", List.of("document"),
                confidence, files, 0, Instant.now());

        DirectoryProfile profile = new DirectoryProfile(identity, centroid,
                DirectoryWants.empty(), DirectoryHealth.compute(centroid, DirectoryWants.empty()));
        parser.writeProfile(dir.resolve(".synthesis.md"), profile);
    }
}
