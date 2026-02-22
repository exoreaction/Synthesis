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
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EvolutionReportCommand} -- workspace structural evolution.
 */
class EvolutionReportCommandTest {

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
    void collectsDirectorySnapshots() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topicA"), List.of("Entity1"), 0.8, 5);
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("topicB"), List.of(), 0.6, 3);

        EvolutionReportCommand cmd = new EvolutionReportCommand();
        List<EvolutionReportCommand.DirectorySnapshot> snapshots =
                cmd.collectSnapshots(workspace, parser);

        assertEquals(2, snapshots.size());
    }

    @Test
    void emptyWorkspaceProducesEmptySnapshots() throws IOException {
        EvolutionReportCommand cmd = new EvolutionReportCommand();
        List<EvolutionReportCommand.DirectorySnapshot> snapshots =
                cmd.collectSnapshots(workspace, parser);

        assertTrue(snapshots.isEmpty());
    }

    @Test
    void identifiesGrowingDirectories() throws IOException {
        // A directory with many files and high confidence is "growing"
        createDirectoryWithCentroid(workspace.resolve("active"),
                List.of("topicA"), List.of("Entity1"), 0.85, 10);
        // A directory with few files is not clearly growing
        createDirectoryWithCentroid(workspace.resolve("sparse"),
                List.of("topicB"), List.of(), 0.3, 1);

        EvolutionReportCommand cmd = new EvolutionReportCommand();
        List<EvolutionReportCommand.DirectorySnapshot> snapshots =
                cmd.collectSnapshots(workspace, parser);

        // The one with more files should have higher activity
        EvolutionReportCommand.DirectorySnapshot active =
                snapshots.stream().filter(s -> s.path().equals("active")).findFirst().orElseThrow();
        EvolutionReportCommand.DirectorySnapshot sparse =
                snapshots.stream().filter(s -> s.path().equals("sparse")).findFirst().orElseThrow();

        assertTrue(active.contributingFiles() > sparse.contributingFiles());
    }

    @Test
    void identifiesStarvingDirectories() throws IOException {
        // A directory with centroid >= 0.3 and wants satisfaction < 0.1 is starving
        Path dir = workspace.resolve("starving");
        Files.createDirectories(dir);

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of(), List.of(), List.of(),
                ScopeLevel.ORGANIZATION, null, null,
                0.5, Instant.now(), "test", "",
                List.of(), List.of(), false, List.of());

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("some topic"), List.of(), "2026-Q1",
                List.of("document"), 0.5, 3, 0, Instant.now());
        DirectoryWants wants = new DirectoryWants(
                List.of("renewable energy"), List.of(), List.of(),
                "readme", 0.0);
        DirectoryHealth health = DirectoryHealth.compute(centroid, wants);

        DirectoryProfile profile = new DirectoryProfile(identity, centroid, wants, health);
        parser.writeProfile(dir.resolve(".synthesis.md"), profile);

        EvolutionReportCommand cmd = new EvolutionReportCommand();
        List<EvolutionReportCommand.DirectorySnapshot> snapshots =
                cmd.collectSnapshots(workspace, parser);

        assertEquals(1, snapshots.size());
        assertEquals("starving", snapshots.get(0).status());
    }

    @Test
    void renderAsciiContainsSections() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topicA"), List.of("Entity1"), 0.8, 5);
        createDirectoryWithCentroid(workspace.resolve("beta"),
                List.of("topicB"), List.of(), 0.75, 4);

        EvolutionReportCommand cmd = new EvolutionReportCommand();
        String report = cmd.renderAscii(workspace, parser);

        assertTrue(report.contains("Evolution Report"));
        // Both directories are high confidence with enough files to appear in Growing
        assertTrue(report.contains("alpha"), "Report should contain 'alpha'");
        assertTrue(report.contains("beta"), "Report should contain 'beta'");
    }

    @Test
    void renderJsonContainsStructure() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("alpha"),
                List.of("topicA"), List.of(), 0.8, 5);

        EvolutionReportCommand cmd = new EvolutionReportCommand();
        String json = cmd.renderJson(workspace, parser);

        assertTrue(json.contains("\"workspace\""));
        assertTrue(json.contains("\"directories\""));
        assertTrue(json.contains("\"summary\""));
    }

    @Test
    void summaryCountsCategories() throws IOException {
        createDirectoryWithCentroid(workspace.resolve("healthy"),
                List.of("topicA"), List.of(), 0.85, 10);
        createDirectoryWithCentroid(workspace.resolve("bootstrap"),
                List.of("topicB"), List.of(), 0.2, 1);

        EvolutionReportCommand cmd = new EvolutionReportCommand();
        EvolutionReportCommand.EvolutionSummary summary =
                cmd.computeSummary(workspace, parser);

        assertTrue(summary.totalDirectories() >= 2);
        assertTrue(summary.withCentroids() >= 2);
    }

    @Test
    void satisfiedDirectoriesIdentified() throws IOException {
        // A directory with high satisfaction wants
        Path dir = workspace.resolve("satisfied");
        Files.createDirectories(dir);

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of(), List.of(), List.of(),
                ScopeLevel.ORGANIZATION, null, null,
                0.5, Instant.now(), "test", "",
                List.of(), List.of(), false, List.of());

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("renewable energy"), List.of(), "2026-Q1",
                List.of("report"), 0.8, 5, 0, Instant.now());
        DirectoryWants wants = new DirectoryWants(
                List.of("renewable energy"), List.of(), List.of(),
                "readme", 0.9);
        DirectoryHealth health = DirectoryHealth.compute(centroid, wants);

        DirectoryProfile profile = new DirectoryProfile(identity, centroid, wants, health);
        parser.writeProfile(dir.resolve(".synthesis.md"), profile);

        EvolutionReportCommand cmd = new EvolutionReportCommand();
        List<EvolutionReportCommand.DirectorySnapshot> snapshots =
                cmd.collectSnapshots(workspace, parser);

        assertEquals(1, snapshots.size());
        assertEquals("healthy", snapshots.get(0).status());
        assertTrue(snapshots.get(0).wantSatisfaction() > 0.5);
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
