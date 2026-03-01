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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
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

        assertTrue(ascii.contains("enrich-centroids"),
                "Should suggest 'synthesis sync --enrich-centroids' when most nodes show [??]: " + ascii);
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

        assertFalse(ascii.contains("enrich-centroids"),
                "Should NOT show hint when nodes are healthy");
    }

    // ---- Feature A: recursive markdown scanning in collectCrossReferenceEdges ----

    @Test
    void collectCrossReferenceEdges_findsLinksInSubdirectories() throws IOException {
        // Create two node directories
        Path alpha = workspace.resolve("alpha");
        Path beta = workspace.resolve("beta");
        createDirectoryWithCentroid(alpha, List.of("topicA"), List.of(), 0.7, 3);
        createDirectoryWithCentroid(beta, List.of("topicB"), List.of(), 0.6, 2);

        // Create a markdown file 2 levels deep inside alpha that links to beta
        Path deepDir = alpha.resolve("sub1").resolve("sub2");
        Files.createDirectories(deepDir);
        Files.writeString(deepDir.resolve("deep-doc.md"),
                "Check [beta info](../../../beta/README.md) for context.\n");

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);
        List<KnowledgeGraphCommand.KnowledgeEdge> crossRefEdges =
                cmd.collectCrossReferenceEdges(workspace, nodes);

        assertFalse(crossRefEdges.isEmpty(),
                "Should find cross-reference edge from deep markdown file");
        assertTrue(crossRefEdges.stream().anyMatch(
                e -> e.filePath().contains("deep-doc.md") && e.directoryPath().equals("beta")),
                "Should have edge from alpha's deep file to beta: " + crossRefEdges);
    }

    @Test
    void collectCrossReferenceEdges_doesNotIncludeSynthesisMdFiles() throws IOException {
        // Create two node directories
        Path alpha = workspace.resolve("alpha");
        Path beta = workspace.resolve("beta");
        createDirectoryWithCentroid(alpha, List.of("topicA"), List.of(), 0.7, 3);
        createDirectoryWithCentroid(beta, List.of("topicB"), List.of(), 0.6, 2);

        // Place a .synthesis.md file in a subdirectory that links to beta
        Path subDir = alpha.resolve("nested");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve(".synthesis.md"),
                "See [beta](../../beta/overview.md) for details.\n");

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);
        List<KnowledgeGraphCommand.KnowledgeEdge> crossRefEdges =
                cmd.collectCrossReferenceEdges(workspace, nodes);

        assertTrue(crossRefEdges.isEmpty(),
                "Should exclude .synthesis.md files even in subdirectories: " + crossRefEdges);
    }

    // ---- Feature B: entity-based implicit edges ----

    @Test
    void collectEntityEdges_connectsNodesWithSharedEntities() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topicA"), List.of("Acme Corp"), 0.8, 5);
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("topicB"), List.of("Acme Corp"), 0.7, 3);

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);
        List<KnowledgeGraphCommand.KnowledgeEdge> entityEdges =
                cmd.collectEntityEdges(nodes);

        assertFalse(entityEdges.isEmpty(),
                "Should create edge between nodes sharing entity 'Acme Corp'");
        assertTrue(entityEdges.stream().anyMatch(
                e -> "entity-match".equals(e.relationship())),
                "Edge type should be 'entity-match': " + entityEdges);
    }

    @Test
    void collectEntityEdges_noEdgeWhenNoSharedEntities() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topicA"), List.of("Entity One"), 0.8, 5);
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("topicB"), List.of("Entity Two"), 0.7, 3);

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);
        List<KnowledgeGraphCommand.KnowledgeEdge> entityEdges =
                cmd.collectEntityEdges(nodes);

        assertTrue(entityEdges.isEmpty(),
                "Should not create edges when no entities are shared: " + entityEdges);
    }

    @Test
    void collectEntityEdges_confidenceScalesWithSharedCount() throws IOException {
        // A shares 3 entities with B, but only 1 with C
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topicA"), List.of("Ent1", "Ent2", "Ent3"), 0.8, 5);
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("topicB"), List.of("Ent1", "Ent2", "Ent3"), 0.7, 3);
        createDirectoryWithCentroid(workspace.resolve("gamma"),
                List.of("topicC"), List.of("Ent1", "Other"), 0.6, 2);

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);
        List<KnowledgeGraphCommand.KnowledgeEdge> entityEdges =
                cmd.collectEntityEdges(nodes);

        // Find edge between alpha and beta (3 shared)
        KnowledgeGraphCommand.KnowledgeEdge abEdge = entityEdges.stream()
                .filter(e -> (e.filePath().equals("alpha") && e.directoryPath().equals("beta"))
                          || (e.filePath().equals("beta") && e.directoryPath().equals("alpha")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected edge between alpha and beta"));

        // Find edge between alpha and gamma (1 shared)
        KnowledgeGraphCommand.KnowledgeEdge agEdge = entityEdges.stream()
                .filter(e -> (e.filePath().equals("alpha") && e.directoryPath().equals("gamma"))
                          || (e.filePath().equals("gamma") && e.directoryPath().equals("alpha")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected edge between alpha and gamma"));

        assertTrue(abEdge.bidStrength() > agEdge.bidStrength(),
                "Edge A-B (3 shared) should have higher confidence than A-C (1 shared): "
                        + abEdge.bidStrength() + " vs " + agEdge.bidStrength());
        assertTrue(abEdge.bidStrength() <= 0.8,
                "Confidence should be capped at 0.8: " + abEdge.bidStrength());
    }

    @Test
    void collectEntityEdges_excludesGenericEntities() throws IOException {
        // Both nodes share only generic entities that should be excluded
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topicA"), List.of("Media Type", "AI Summary", "AI Description"), 0.8, 5);
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("topicB"), List.of("Media Type", "AI Summary", "AI Title"), 0.7, 3);

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);
        List<KnowledgeGraphCommand.KnowledgeEdge> entityEdges =
                cmd.collectEntityEdges(nodes);

        assertTrue(entityEdges.isEmpty(),
                "Should not create edges from generic/noise entities: " + entityEdges);
    }

    // ---- Feature C: declared edges from related: field ----

    @Test
    void collectDeclaredEdges_createsEdgesFromRelatedField() throws IOException {
        // Create two node directories
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topicA"), List.of(), 0.7, 3);
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("topicB"), List.of(), 0.6, 2);

        // Append related: field to alpha's .synthesis.md
        appendRelatedField(workspace.resolve("alpha").resolve(".synthesis.md"),
                List.of("beta"));

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);
        List<KnowledgeGraphCommand.KnowledgeEdge> declaredEdges =
                cmd.collectDeclaredEdges(workspace, nodes);

        assertFalse(declaredEdges.isEmpty(),
                "Should create declared edge from related: field");
        KnowledgeGraphCommand.KnowledgeEdge edge = declaredEdges.get(0);
        assertEquals("declared", edge.relationship());
        assertEquals(1.0, edge.bidStrength(), 0.001,
                "Declared edges should have confidence 1.0");
    }

    @Test
    void collectDeclaredEdges_toleratesMissingTargetNodes() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topicA"), List.of(), 0.7, 3);

        // Declare a relationship to a non-existent node
        appendRelatedField(workspace.resolve("alpha").resolve(".synthesis.md"),
                List.of("nonexistent/dir"));

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);

        // Should not throw, and should produce no edge (target doesn't exist as node)
        List<KnowledgeGraphCommand.KnowledgeEdge> declaredEdges =
                cmd.collectDeclaredEdges(workspace, nodes);

        assertTrue(declaredEdges.isEmpty(),
                "Should not create edge when target node doesn't exist: " + declaredEdges);
    }

    @Test
    void collectDeclaredEdges_emptyWhenNoRelatedField() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topicA"), List.of(), 0.7, 3);
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("topicB"), List.of(), 0.6, 2);

        // No related: field added

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);
        List<KnowledgeGraphCommand.KnowledgeEdge> declaredEdges =
                cmd.collectDeclaredEdges(workspace, nodes);

        assertTrue(declaredEdges.isEmpty(),
                "Should produce no edges when no related: field exists: " + declaredEdges);
    }

    // ---- #282: --scope flag, tightness, global breakdown ----

    @Test
    void testScopeFilterShowsOnlyNodesInSubtree() throws IOException {
        // Create nodes under two top-level dirs
        createDirectoryWithCentroid(workspace.resolve("eXOReaction").resolve("alpha"),
                List.of("PCB"), List.of(), 0.8, 5);
        createDirectoryWithCentroid(workspace.resolve("eXOReaction").resolve("beta"),
                List.of("testing"), List.of(), 0.7, 3);
        createDirectoryWithCentroid(workspace.resolve("Quadim").resolve("gamma"),
                List.of("SaaS"), List.of(), 0.6, 2);

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        cmd.setScope("eXOReaction");

        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);
        List<KnowledgeGraphCommand.KnowledgeEdge> edges = List.of();

        String ascii = cmd.renderAscii(nodes, edges, workspace);

        // Only eXOReaction nodes should appear
        assertTrue(ascii.contains("eXOReaction"), "Should show eXOReaction path");
        assertFalse(ascii.contains("Quadim"), "Should NOT show Quadim nodes when scoped to eXOReaction");
    }

    @Test
    void testScopeFilterCountsOnlyInternalEdges() throws IOException {
        Path exo = workspace.resolve("eXOReaction");
        Path quad = workspace.resolve("Quadim");
        createDirectoryWithCentroid(exo.resolve("alpha"),
                List.of("PCB"), List.of("SharedEnt"), 0.8, 5);
        createDirectoryWithCentroid(exo.resolve("beta"),
                List.of("testing"), List.of("SharedEnt"), 0.7, 3);
        createDirectoryWithCentroid(quad.resolve("gamma"),
                List.of("SaaS"), List.of("SharedEnt"), 0.6, 2);

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        cmd.setScope("eXOReaction");

        List<KnowledgeGraphCommand.KnowledgeNode> allNodes =
                cmd.collectNodes(workspace, parser);

        // Collect entity edges from all nodes, then filter with scope
        List<KnowledgeGraphCommand.KnowledgeEdge> entityEdges = cmd.collectEntityEdges(allNodes);

        String ascii = cmd.renderAscii(allNodes, entityEdges, workspace);

        // Should distinguish internal vs external links
        assertTrue(ascii.contains("Internal links:") || ascii.contains("internal"),
                "Scoped output should show internal link count: " + ascii);
        assertTrue(ascii.contains("External links:") || ascii.contains("external"),
                "Scoped output should show external link count: " + ascii);
    }

    @Test
    void testTightnessScoreInSummary() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("eXOReaction").resolve("alpha"),
                List.of("PCB"), List.of("SharedEnt"), 0.8, 5);
        createDirectoryWithCentroid(workspace.resolve("eXOReaction").resolve("beta"),
                List.of("testing"), List.of("SharedEnt"), 0.7, 3);

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        cmd.setScope("eXOReaction");

        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);
        List<KnowledgeGraphCommand.KnowledgeEdge> entityEdges = cmd.collectEntityEdges(nodes);

        String ascii = cmd.renderAscii(nodes, entityEdges, workspace);

        assertTrue(ascii.contains("Tightness:"),
                "Scoped output should contain 'Tightness:' metric: " + ascii);
    }

    @Test
    void testTightnessIsZeroWithNoEdges() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("eXOReaction").resolve("alpha"),
                List.of("PCB"), List.of(), 0.8, 5);
        createDirectoryWithCentroid(workspace.resolve("eXOReaction").resolve("beta"),
                List.of("testing"), List.of(), 0.7, 3);

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        cmd.setScope("eXOReaction");

        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);

        String ascii = cmd.renderAscii(nodes, List.of(), workspace);

        assertTrue(ascii.contains("Tightness: 0.00"),
                "Tightness should be 0.00 when no edges: " + ascii);
    }

    @Test
    void testGlobalSubworkspaceBreakdown() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("eXOReaction").resolve("alpha"),
                List.of("PCB"), List.of(), 0.8, 5);
        createDirectoryWithCentroid(workspace.resolve("eXOReaction").resolve("beta"),
                List.of("testing"), List.of(), 0.7, 3);
        createDirectoryWithCentroid(workspace.resolve("Quadim").resolve("gamma"),
                List.of("SaaS"), List.of(), 0.6, 2);

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        // No scope set (global view)

        List<KnowledgeGraphCommand.KnowledgeNode> nodes =
                cmd.collectNodes(workspace, parser);

        String ascii = cmd.renderAscii(nodes, List.of(), workspace);

        assertTrue(ascii.contains("Sub-workspace tightness:"),
                "Global output should contain sub-workspace breakdown: " + ascii);
        assertTrue(ascii.contains("eXOReaction"),
                "Breakdown should show eXOReaction: " + ascii);
        assertTrue(ascii.contains("Quadim"),
                "Breakdown should show Quadim: " + ascii);
    }

    @Test
    void testScopeWithTrailingSlash() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("eXOReaction").resolve("alpha"),
                List.of("PCB"), List.of(), 0.8, 5);
        createDirectoryWithCentroid(workspace.resolve("Quadim").resolve("gamma"),
                List.of("SaaS"), List.of(), 0.6, 2);

        // Run with trailing slash
        KnowledgeGraphCommand cmdWithSlash = new KnowledgeGraphCommand();
        cmdWithSlash.setScope("eXOReaction/");
        List<KnowledgeGraphCommand.KnowledgeNode> nodesWithSlash =
                cmdWithSlash.collectNodes(workspace, parser);
        String asciiWithSlash = cmdWithSlash.renderAscii(nodesWithSlash, List.of(), workspace);

        // Run without trailing slash
        KnowledgeGraphCommand cmdNoSlash = new KnowledgeGraphCommand();
        cmdNoSlash.setScope("eXOReaction");
        List<KnowledgeGraphCommand.KnowledgeNode> nodesNoSlash =
                cmdNoSlash.collectNodes(workspace, parser);
        String asciiNoSlash = cmdNoSlash.renderAscii(nodesNoSlash, List.of(), workspace);

        // Both should show eXOReaction nodes, not Quadim
        assertFalse(asciiWithSlash.contains("Quadim"),
                "Scope with trailing slash should exclude Quadim: " + asciiWithSlash);
        assertFalse(asciiNoSlash.contains("Quadim"),
                "Scope without trailing slash should exclude Quadim: " + asciiNoSlash);
        assertEquals(nodesWithSlash.size(), nodesNoSlash.size(),
                "Trailing slash should not change node count");
    }

    // ---- helpers ----

    /**
     * Appends a {@code related:} field to an existing .synthesis.md file
     * by rewriting the YAML front matter.
     */
    private void appendRelatedField(Path synthesisFile, List<String> related) throws IOException {
        String content = Files.readString(synthesisFile);
        // Insert related: block before the closing ---
        StringBuilder relatedBlock = new StringBuilder();
        relatedBlock.append("  related:\n");
        for (String r : related) {
            relatedBlock.append("    - \"").append(r).append("\"\n");
        }
        // Replace the last --- with the related block + ---
        int lastDash = content.lastIndexOf("---");
        String newContent = content.substring(0, lastDash) + relatedBlock + "---"
                + content.substring(lastDash + 3);
        Files.writeString(synthesisFile, newContent);
    }

    // ---- Phase 5: KCP units as first-class graph nodes ----

    @Test
    void collectKcpUnitsEmptyWhenNoDatabase() {
        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        assertTrue(cmd.collectKcpUnits(workspace).isEmpty(),
                "Should return empty list when no database exists");
    }

    @Test
    void collectKcpRelEdgesEmptyWhenNoDatabase() {
        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        assertTrue(cmd.collectKcpRelEdges(workspace).isEmpty(),
                "Should return empty list when no database exists");
    }

    @Test
    void parseTriggersHandlesVariousInputs() {
        assertEquals(List.of(), KnowledgeGraphCommand.parseTriggers(null));
        assertEquals(List.of(), KnowledgeGraphCommand.parseTriggers(""));
        assertEquals(List.of(), KnowledgeGraphCommand.parseTriggers("[]"));
        assertEquals(List.of("api"), KnowledgeGraphCommand.parseTriggers("[\"api\"]"));
        assertEquals(List.of("api", "rest"),
                KnowledgeGraphCommand.parseTriggers("[\"api\",\"rest\"]"));
        assertEquals(List.of("api", "rest", "endpoints"),
                KnowledgeGraphCommand.parseTriggers("[\"api\", \"rest\", \"endpoints\"]"));
    }

    @Test
    void renderAsciiShowsKcpUnitsSection() {
        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KcpUnitNode> units = List.of(
                new KnowledgeGraphCommand.KcpUnitNode(
                        "overview", "/ws/knowledge.yaml", "my-project",
                        "README.md", "What is this project?", "global", List.of("intro"))
        );

        String ascii = cmd.renderAscii(List.of(), List.of(), units, List.of(), workspace);

        assertTrue(ascii.contains("KCP Knowledge Units"), "Should show KCP units section");
        assertTrue(ascii.contains("my-project"), "Should show project name");
        assertTrue(ascii.contains("overview"), "Should show unit ID");
        assertTrue(ascii.contains("What is this project?"), "Should show intent");
        assertTrue(ascii.contains("triggers: intro"), "Should show triggers");
    }

    @Test
    void renderAsciiShowsKcpRelationships() {
        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KcpUnitNode> units = List.of(
                new KnowledgeGraphCommand.KcpUnitNode(
                        "tldr", "/ws/kcp.yaml", "proj", "a.md",
                        "Quick reference", "focused", List.of()),
                new KnowledgeGraphCommand.KcpUnitNode(
                        "full", "/ws/kcp.yaml", "proj", "b.md",
                        "Full reference", "comprehensive", List.of())
        );
        List<KnowledgeGraphCommand.KcpUnitEdge> edges = List.of(
                new KnowledgeGraphCommand.KcpUnitEdge("tldr", "full", "context", "/ws/kcp.yaml")
        );

        String ascii = cmd.renderAscii(List.of(), List.of(), units, edges, workspace);

        assertTrue(ascii.contains("Relationships:"), "Should show Relationships section");
        assertTrue(ascii.contains("tldr"), "Should show from unit");
        assertTrue(ascii.contains("full"), "Should show to unit");
        assertTrue(ascii.contains("[context]"), "Should show relationship type");
    }

    @Test
    void renderAsciiDoesNotShowKcpSectionWhenEmpty() {
        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        String ascii = cmd.renderAscii(List.of(), List.of(), List.of(), List.of(), workspace);
        assertFalse(ascii.contains("KCP Knowledge Units"),
                "Should not show KCP section when no units");
    }

    @Test
    void renderMermaidIncludesKcpUnitNodes() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("docs"),
                List.of("guide"), List.of(), 0.7, 2);
        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KnowledgeNode> nodes = cmd.collectNodes(workspace, parser);

        List<KnowledgeGraphCommand.KcpUnitNode> units = List.of(
                new KnowledgeGraphCommand.KcpUnitNode(
                        "overview", workspace.resolve("knowledge.yaml").toString(), "myproj",
                        "docs/README.md", "Overview of the project", "global", List.of())
        );

        String mermaid = cmd.renderMermaid(nodes, List.of(), units, List.of(), workspace);

        assertTrue(mermaid.contains("kcp0"), "Should have a KCP unit node ID");
        assertTrue(mermaid.contains("myproj/overview"), "Should show project/unitId label");
        assertTrue(mermaid.contains("kcp-unit"), "Should have kcp-unit edge label");
    }

    @Test
    void renderMermaidIncludesKcpRelationshipEdges() {
        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        String manifestFile = "/ws/knowledge.yaml";
        List<KnowledgeGraphCommand.KcpUnitNode> units = List.of(
                new KnowledgeGraphCommand.KcpUnitNode(
                        "tldr", manifestFile, "proj", null, "Quick ref", "focused", List.of()),
                new KnowledgeGraphCommand.KcpUnitNode(
                        "full", manifestFile, "proj", null, "Full ref", "comprehensive", List.of())
        );
        List<KnowledgeGraphCommand.KcpUnitEdge> edges = List.of(
                new KnowledgeGraphCommand.KcpUnitEdge("tldr", "full", "context", manifestFile)
        );

        String mermaid = cmd.renderMermaid(List.of(), List.of(), units, edges, workspace);

        assertTrue(mermaid.contains("-->|context|"), "Should have typed relationship edge: " + mermaid);
    }

    @Test
    void renderJsonIncludesKcpUnitsAndRelationships() {
        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KcpUnitNode> units = List.of(
                new KnowledgeGraphCommand.KcpUnitNode(
                        "api-ref", "/ws/knowledge.yaml", "crewai",
                        "docs/api.md", "API reference", "module", List.of("api", "rest"))
        );
        List<KnowledgeGraphCommand.KcpUnitEdge> edges = List.of(
                new KnowledgeGraphCommand.KcpUnitEdge(
                        "overview", "api-ref", "context", "/ws/knowledge.yaml")
        );

        String json = cmd.renderJson(List.of(), List.of(), units, edges, workspace);

        assertTrue(json.contains("\"kcpUnits\""), "JSON should have kcpUnits section");
        assertTrue(json.contains("\"kcpRelationships\""), "JSON should have kcpRelationships section");
        assertTrue(json.contains("\"api-ref\""), "JSON should include unit ID");
        assertTrue(json.contains("\"crewai\""), "JSON should include project name");
        assertTrue(json.contains("\"context\""), "JSON should include relationship type");
        assertTrue(json.contains("\"api\""), "JSON should include triggers");
    }

    @Test
    void collectKcpUnitsFromDatabase() throws Exception {
        Files.createDirectories(workspace.resolve(".synthesis"));
        String dbUrl = "jdbc:sqlite:" + workspace.resolve(".synthesis/synthesis.db");
        String manifestFile = workspace.resolve("knowledge.yaml").toString();
        String ws = workspace.toString();

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            createKcpTables(conn);
            insertManifest(conn, ws, manifestFile, "test-proj", "0.5", 1, 0);
            insertUnit(conn, ws, manifestFile, "overview", "README.md",
                    "What is this?", "global", null, "[\"intro\"]", null);
        }

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KcpUnitNode> units = cmd.collectKcpUnits(workspace);

        assertEquals(1, units.size(), "Should load 1 unit from DB");
        assertEquals("overview", units.get(0).unitId());
        assertEquals("test-proj", units.get(0).project());
        assertEquals("README.md", units.get(0).path());
        assertEquals("What is this?", units.get(0).intent());
        assertEquals("global", units.get(0).scope());
        assertTrue(units.get(0).triggers().contains("intro"), "Should parse triggers from JSON");
    }

    @Test
    void collectKcpRelEdgesFromDatabase() throws Exception {
        Files.createDirectories(workspace.resolve(".synthesis"));
        String dbUrl = "jdbc:sqlite:" + workspace.resolve(".synthesis/synthesis.db");
        String manifestFile = workspace.resolve("knowledge.yaml").toString();
        String ws = workspace.toString();

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            createKcpTables(conn);
            insertManifest(conn, ws, manifestFile, "proj", "0.5", 2, 1);
            insertUnit(conn, ws, manifestFile, "overview", "README.md", "Intro", "global", null, null, null);
            insertUnit(conn, ws, manifestFile, "api-ref", "docs/api.md", "API", "module", null, null, null);
            insertRelationship(conn, ws, manifestFile, "overview", "api-ref", "context");
        }

        KnowledgeGraphCommand cmd = new KnowledgeGraphCommand();
        List<KnowledgeGraphCommand.KcpUnitEdge> rels = cmd.collectKcpRelEdges(workspace);

        assertEquals(1, rels.size(), "Should load 1 relationship from DB");
        assertEquals("overview", rels.get(0).fromUnit());
        assertEquals("api-ref", rels.get(0).toUnit());
        assertEquals("context", rels.get(0).type());
    }

    // ---- DB helpers for Phase 5 tests ----

    private static void createKcpTables(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE kcp_manifests (id INTEGER PRIMARY KEY, " +
                    "workspace_path TEXT, file_path TEXT, project TEXT, kcp_version TEXT, " +
                    "unit_count INTEGER DEFAULT 0, relationship_count INTEGER DEFAULT 0, " +
                    "last_computed INTEGER, UNIQUE(workspace_path, file_path))");
            st.execute("CREATE TABLE kcp_units (id INTEGER PRIMARY KEY, " +
                    "workspace_path TEXT, manifest_file TEXT, unit_id TEXT, path TEXT, " +
                    "intent TEXT, scope TEXT, audience_json TEXT, triggers_json TEXT, " +
                    "hints_json TEXT, last_computed INTEGER, " +
                    "UNIQUE(workspace_path, manifest_file, unit_id))");
            st.execute("CREATE TABLE kcp_relationships (id INTEGER PRIMARY KEY, " +
                    "workspace_path TEXT, manifest_file TEXT, from_unit TEXT, to_unit TEXT, " +
                    "type TEXT, last_computed INTEGER, " +
                    "UNIQUE(workspace_path, manifest_file, from_unit, to_unit, type))");
        }
    }

    private static void insertManifest(Connection conn, String ws, String filePath,
            String project, String kcpVersion, int unitCount, int relCount) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO kcp_manifests VALUES (null,?,?,?,?,?,?,?)")) {
            ps.setString(1, ws); ps.setString(2, filePath);
            ps.setString(3, project); ps.setString(4, kcpVersion);
            ps.setInt(5, unitCount); ps.setInt(6, relCount);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private static void insertUnit(Connection conn, String ws, String manifestFile,
            String unitId, String path, String intent, String scope,
            String audienceJson, String triggersJson, String hintsJson) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO kcp_units VALUES (null,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, ws); ps.setString(2, manifestFile);
            ps.setString(3, unitId); ps.setString(4, path);
            ps.setString(5, intent); ps.setString(6, scope);
            ps.setString(7, audienceJson); ps.setString(8, triggersJson);
            ps.setString(9, hintsJson); ps.setLong(10, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private static void insertRelationship(Connection conn, String ws, String manifestFile,
            String fromUnit, String toUnit, String type) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO kcp_relationships VALUES (null,?,?,?,?,?,?)")) {
            ps.setString(1, ws); ps.setString(2, manifestFile);
            ps.setString(3, fromUnit); ps.setString(4, toUnit);
            ps.setString(5, type); ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

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
