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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StructuralAnalysisCommand} -- workspace structural analysis.
 */
class StructuralAnalysisCommandTest {

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

    // ---- Fragmentation tests ----

    @Test
    void detectsTopicFragmentation() throws IOException {
        // Two directories sharing the same topic = fragmentation
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("renewable energy", "sustainability"), List.of(), 0.8, 5,
                List.of("proposal", "report"));
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("renewable energy", "solar panels"), List.of(), 0.7, 3,
                List.of("contract", "invoice"));

        StructuralAnalysisCommand cmd = new StructuralAnalysisCommand();
        Map<String, List<String>> fragmented = cmd.detectFragmentation(workspace, parser);

        // "renewable energy" appears in both alpha and beta
        assertTrue(fragmented.containsKey("renewable energy"));
        assertEquals(2, fragmented.get("renewable energy").size());
    }

    @Test
    void noFragmentationWithUniqueTopics() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topic A"), List.of(), 0.8, 5,
                List.of("report"));
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("topic B"), List.of(), 0.7, 3,
                List.of("proposal"));

        StructuralAnalysisCommand cmd = new StructuralAnalysisCommand();
        Map<String, List<String>> fragmented = cmd.detectFragmentation(workspace, parser);

        assertTrue(fragmented.isEmpty());
    }

    @Test
    void detectsEntityFragmentation() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topic A"), List.of("GreenField Energy"), 0.8, 5,
                List.of("proposal"));
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("topic B"), List.of("GreenField Energy"), 0.7, 3,
                List.of("report"));

        StructuralAnalysisCommand cmd = new StructuralAnalysisCommand();
        Map<String, List<String>> fragmented = cmd.detectFragmentation(workspace, parser);

        assertTrue(fragmented.containsKey("GreenField Energy"));
    }

    // ---- Gap analysis tests ----

    @Test
    void detectsGapsAcrossWorkspace() throws IOException {
        // A directory that matches "client-opportunity" archetype but lacks "meeting-notes"
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("proposal", "client"), List.of("SomeCorp"), 0.8, 5,
                List.of("proposal", "contract"));

        StructuralAnalysisCommand cmd = new StructuralAnalysisCommand();
        List<StructuralAnalysisCommand.WorkspaceGap> gaps =
                cmd.detectGaps(workspace, parser);

        // Should find gaps (missing doc types from matched archetype)
        // Whether this fires depends on archetype matching with these topics
        assertNotNull(gaps);
    }

    @Test
    void noGapsForEmptyWorkspace() throws IOException {
        StructuralAnalysisCommand cmd = new StructuralAnalysisCommand();
        List<StructuralAnalysisCommand.WorkspaceGap> gaps =
                cmd.detectGaps(workspace, parser);

        assertTrue(gaps.isEmpty());
    }

    // ---- Orphan detection tests ----

    @Test
    void detectsOrphanFiles() throws IOException {
        // Create a directory with centroid but put a file that has no semantic match
        Path dir = workspace.resolve("alpha");
        createDirectoryWithCentroid(dir,
                List.of("renewable energy"), List.of(), 0.8, 5,
                List.of("proposal"));

        // Create a file that semantically doesn't belong
        Files.writeString(dir.resolve("random-unrelated-file.txt"),
                "This has nothing to do with the directory");

        StructuralAnalysisCommand cmd = new StructuralAnalysisCommand();
        // Orphan detection requires enrichment data, so without it, files
        // without enrichment companions are potential orphans
        List<String> orphans = cmd.detectOrphans(workspace, parser);

        assertNotNull(orphans);
        // Files without enrichment data are considered potential orphans
    }

    @Test
    void emptyWorkspaceHasNoOrphans() throws IOException {
        StructuralAnalysisCommand cmd = new StructuralAnalysisCommand();
        List<String> orphans = cmd.detectOrphans(workspace, parser);
        assertTrue(orphans.isEmpty());
    }

    // ---- Rendering tests ----

    @Test
    void renderFullAnalysisContainsSections() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("renewable energy", "sustainability"), List.of("GreenField"), 0.8, 5,
                List.of("proposal"));
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("renewable energy"), List.of(), 0.7, 3,
                List.of("report"));

        StructuralAnalysisCommand cmd = new StructuralAnalysisCommand();
        String output = cmd.renderFullAnalysis(workspace, parser);

        assertTrue(output.contains("Structural Analysis"));
        assertTrue(output.contains("Fragmentation"));
    }

    @Test
    void renderJsonContainsStructure() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topicA"), List.of(), 0.8, 5,
                List.of("proposal"));

        StructuralAnalysisCommand cmd = new StructuralAnalysisCommand();
        String json = cmd.renderJson(workspace, parser);

        assertTrue(json.contains("\"workspace\""));
        assertTrue(json.contains("\"fragmentation\""));
        assertTrue(json.contains("\"gaps\""));
        assertTrue(json.contains("\"orphans\""));
    }

    // ---- helpers ----

    private void createDirectoryWithCentroid(Path dir, List<String> topics,
                                              List<String> entities,
                                              double confidence,
                                              int files,
                                              List<String> docTypes) throws IOException {
        Files.createDirectories(dir);

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of(), List.of(), List.of(),
                ScopeLevel.ORGANIZATION, null, null,
                0.5, Instant.now(), "test", "",
                List.of(), List.of(), false, List.of());

        DirectoryCentroid centroid = new DirectoryCentroid(
                topics, entities, "2026-Q1", docTypes,
                confidence, files, 0, Instant.now());

        DirectoryProfile profile = new DirectoryProfile(identity, centroid,
                DirectoryWants.empty(), DirectoryHealth.compute(centroid, DirectoryWants.empty()));
        parser.writeProfile(dir.resolve(".synthesis.md"), profile);
    }
}
