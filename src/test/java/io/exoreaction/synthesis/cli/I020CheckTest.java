package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.*;
import io.exoreaction.synthesis.org.ScopeLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link I020Check} -- want fulfillment detection.
 */
class I020CheckTest {

    @TempDir
    Path workspace;

    private DirectoryIdentityParser parser;

    @BeforeEach
    void setup() {
        parser = new DirectoryIdentityParser();
    }

    @Test
    void noFindingsWhenNoDirectories() {
        I020Check check = new I020Check();
        List<I020Check.I020Finding> findings = check.check(workspace);
        assertTrue(findings.isEmpty());
    }

    @Test
    void detectsHighSatisfaction() throws IOException {
        // Create directory with high satisfaction
        Path dir = workspace.resolve("fulfilled");
        Files.createDirectories(dir);

        DirectoryIdentity identity = createIdentity();

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("renewable energy"), List.of("GreenField"),
                "2026-Q1", List.of("proposal"),
                0.85, 5, 0, Instant.now());

        DirectoryWants wants = new DirectoryWants(
                List.of("renewable energy"), List.of("GreenField"),
                List.of(), "test", 0.85);

        DirectoryProfile profile = new DirectoryProfile(identity, centroid, wants,
                DirectoryHealth.empty());
        parser.writeProfile(dir.resolve(".synthesis.md"), profile);

        I020Check check = new I020Check();
        List<I020Check.I020Finding> findings = check.check(workspace);
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).satisfaction() >= 0.7);
        assertTrue(findings.get(0).message().contains("[I020]"));
    }

    @Test
    void detectsConfidentCentroid() throws IOException {
        // Directory with confident centroid but no explicit wants
        Path dir = workspace.resolve("confident");
        Files.createDirectories(dir);

        DirectoryIdentity identity = createIdentity();

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("project management"), List.of(),
                "2026-Q1", List.of("report"),
                0.75, 5, 0, Instant.now());

        DirectoryProfile profile = new DirectoryProfile(identity, centroid,
                DirectoryWants.empty(), DirectoryHealth.empty());
        parser.writeProfile(dir.resolve(".synthesis.md"), profile);

        I020Check check = new I020Check();
        List<I020Check.I020Finding> findings = check.check(workspace);
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).centroidConfidence() >= 0.6);
        assertTrue(findings.get(0).contributingFiles() >= 3);
    }

    @Test
    void skipLowSatisfactionAndLowConfidence() throws IOException {
        // Directory with low satisfaction AND low centroid confidence
        Path dir = workspace.resolve("struggling");
        Files.createDirectories(dir);

        DirectoryIdentity identity = createIdentity();

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("something"), List.of(),
                null, List.of(),
                0.3, 1, 0, Instant.now());

        DirectoryWants wants = new DirectoryWants(
                List.of("different"), List.of(),
                List.of(), "test", 0.2);

        DirectoryProfile profile = new DirectoryProfile(identity, centroid, wants,
                DirectoryHealth.empty());
        parser.writeProfile(dir.resolve(".synthesis.md"), profile);

        I020Check check = new I020Check();
        List<I020Check.I020Finding> findings = check.check(workspace);
        assertTrue(findings.isEmpty());
    }

    @Test
    void skipDirectoryWithNoSynthesisFile() throws IOException {
        Path dir = workspace.resolve("nofile");
        Files.createDirectories(dir);

        I020Check check = new I020Check();
        List<I020Check.I020Finding> findings = check.check(workspace);
        assertTrue(findings.isEmpty());
    }

    @Test
    void skipHiddenDirectories() throws IOException {
        Path dir = workspace.resolve(".hidden");
        Files.createDirectories(dir);

        DirectoryIdentity identity = createIdentity();
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("topic"), List.of(), null, List.of(),
                0.9, 10, 0, Instant.now());
        DirectoryProfile profile = new DirectoryProfile(identity, centroid,
                DirectoryWants.empty(), DirectoryHealth.empty());
        parser.writeProfile(dir.resolve(".synthesis.md"), profile);

        I020Check check = new I020Check();
        List<I020Check.I020Finding> findings = check.check(workspace);
        assertTrue(findings.isEmpty());
    }

    @Test
    void messageContainsTopics() throws IOException {
        Path dir = workspace.resolve("topical");
        Files.createDirectories(dir);

        DirectoryIdentity identity = createIdentity();

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("AI strategy", "machine learning"),
                List.of("DeepTech"),
                "2026-Q1",
                List.of("strategy"),
                0.80, 6, 0, Instant.now());

        DirectoryWants wants = new DirectoryWants(
                List.of("AI strategy"), List.of("DeepTech"),
                List.of(), "test", 0.9);

        DirectoryProfile profile = new DirectoryProfile(identity, centroid, wants,
                DirectoryHealth.empty());
        parser.writeProfile(dir.resolve(".synthesis.md"), profile);

        I020Check check = new I020Check();
        List<I020Check.I020Finding> findings = check.check(workspace);
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).message().contains("AI strategy"));
    }

    @Test
    void centroidBelowFileThresholdNotReported() throws IOException {
        // Confident centroid but too few files
        Path dir = workspace.resolve("fewfiles");
        Files.createDirectories(dir);

        DirectoryIdentity identity = createIdentity();

        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("topic"), List.of(), null, List.of(),
                0.8, 2, 0, Instant.now());  // Only 2 files, below threshold of 3

        DirectoryProfile profile = new DirectoryProfile(identity, centroid,
                DirectoryWants.empty(), DirectoryHealth.empty());
        parser.writeProfile(dir.resolve(".synthesis.md"), profile);

        I020Check check = new I020Check();
        List<I020Check.I020Finding> findings = check.check(workspace);
        // No explicit wants satisfied, and centroid has < 3 files
        assertTrue(findings.isEmpty());
    }

    @Test
    void multipleDirectoriesBothReported() throws IOException {
        createFulfilledDirectory(workspace.resolve("dirA"),
                List.of("topicA"), List.of("EntityA"), 0.85);
        createFulfilledDirectory(workspace.resolve("dirB"),
                List.of("topicB"), List.of("EntityB"), 0.90);

        I020Check check = new I020Check();
        List<I020Check.I020Finding> findings = check.check(workspace);
        assertEquals(2, findings.size());
    }

    // ---- helpers ----

    private DirectoryIdentity createIdentity() {
        return new DirectoryIdentity(
                List.of(), List.of(), List.of(),
                ScopeLevel.ORGANIZATION, null, null,
                0.5, Instant.now(), "test", "",
                List.of(), List.of(), false, List.of());
    }

    private void createFulfilledDirectory(Path dir, List<String> topics,
                                           List<String> entities,
                                           double satisfaction) throws IOException {
        Files.createDirectories(dir);

        DirectoryIdentity identity = createIdentity();
        DirectoryCentroid centroid = new DirectoryCentroid(
                topics, entities, "2026-Q1", List.of("report"),
                0.8, 5, 0, Instant.now());

        DirectoryWants wants = new DirectoryWants(
                topics, entities, List.of(), "test", satisfaction);

        DirectoryProfile profile = new DirectoryProfile(identity, centroid, wants,
                DirectoryHealth.empty());
        parser.writeProfile(dir.resolve(".synthesis.md"), profile);
    }
}
