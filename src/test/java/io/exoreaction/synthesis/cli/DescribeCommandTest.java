package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.org.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DescribeCommand}.
 */
class DescribeCommandTest {

    @TempDir
    Path tempDir;

    // ---- Directory-level describe ----

    @Test
    void describe_directoryWithProfile_showsCentroidAndWants() throws Exception {
        initWorkspace(tempDir);
        Path dir = Files.createDirectories(tempDir.resolve("clients/greenfield"));

        // Write a full profile with centroid and wants
        DirectoryIdentityParser parser = new DirectoryIdentityParser();
        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("client-material"), List.of("md", "pdf"), List.of(),
                ScopeLevel.ENTITY, "eXOReaction", "GreenField",
                0.87, null, "inferred from 8 files", ""
        );
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("renewable energy", "SDD methodology"),
                List.of("GreenField Energy", "Jane Smith"),
                "2025-Q4 / 2026-Q1",
                List.of("proposal", "contract"),
                0.87, 8, 0, Instant.now()
        );
        DirectoryWants wants = new DirectoryWants(
                List.of("mentoring contract"),
                List.of("GreenField Energy"),
                List.of(),
                "inferred from directory name + 8 files",
                0.87
        );
        DirectoryProfile profile = new DirectoryProfile(identity, centroid, wants);
        parser.writeProfile(dir.resolve(".synthesis.md"), profile);

        String output = runDescribe(tempDir, dir);

        // Should show directory path
        assertTrue(output.contains("clients/greenfield"),
                "Should show directory path. Output: " + output);

        // Should show scope
        assertTrue(output.contains("ENTITY"),
                "Should show scope level. Output: " + output);
        assertTrue(output.contains("eXOReaction"),
                "Should show organization. Output: " + output);

        // Should show centroid
        assertTrue(output.contains("renewable energy"),
                "Should show centroid topics. Output: " + output);
        assertTrue(output.contains("GreenField Energy"),
                "Should show centroid entities. Output: " + output);
        assertTrue(output.contains("2025-Q4 / 2026-Q1"),
                "Should show timeframe. Output: " + output);
        assertTrue(output.contains("proposal"),
                "Should show document types. Output: " + output);

        // Should show wants
        assertTrue(output.contains("mentoring contract"),
                "Should show wants topics. Output: " + output);
        assertTrue(output.contains("87%"),
                "Should show satisfaction percentage. Output: " + output);
    }

    @Test
    void describe_directoryWithIdentityOnly_showsIdentityAndNoCentroid() throws Exception {
        initWorkspace(tempDir);
        Path dir = Files.createDirectories(tempDir.resolve("meetings"));

        DirectoryIdentityParser parser = new DirectoryIdentityParser();
        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("meeting-notes"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "inferred from 3 files", ""
        );
        parser.write(dir.resolve(".synthesis.md"), identity);

        String output = runDescribe(tempDir, dir);

        // Should show identity
        assertTrue(output.contains("meeting-notes"),
                "Should show accepted types. Output: " + output);
        assertTrue(output.contains("0.6"),
                "Should show confidence. Output: " + output);

        // Should note missing centroid
        assertTrue(output.contains("none") || output.contains("no enriched"),
                "Should note missing centroid. Output: " + output);
    }

    @Test
    void describe_directoryWithNoSynthesisMd_showsHelpMessage() throws Exception {
        initWorkspace(tempDir);
        Path dir = Files.createDirectories(tempDir.resolve("empty-dir"));

        String output = runDescribe(tempDir, dir);

        assertTrue(output.contains("No .synthesis.md"),
                "Should note missing .synthesis.md file. Output: " + output);
        assertTrue(output.contains("synthesis sync"),
                "Should suggest running sync. Output: " + output);
    }

    @Test
    void describe_nonExistentDirectory_returnsError() throws Exception {
        initWorkspace(tempDir);
        Path nonExistent = tempDir.resolve("does-not-exist");

        DescribeCommand cmd = createCommand(tempDir);
        cmd.setTargetDir(nonExistent);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        cmd.setOut(new PrintStream(baos));

        int exitCode = cmd.call();

        assertEquals(1, exitCode, "Should return error code for non-existent directory");
        assertTrue(baos.toString().contains("Not a directory"),
                "Should report not a directory. Output: " + baos);
    }

    @Test
    void describe_directoryWithTransient_showsTransientFlag() throws Exception {
        initWorkspace(tempDir);
        Path staging = Files.createDirectories(tempDir.resolve("staging"));

        DirectoryIdentityParser parser = new DirectoryIdentityParser();
        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("staging"), List.of("*"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.5, null, "test", "",
                List.of(), List.of(), true, List.of()
        );
        parser.write(staging.resolve(".synthesis.md"), identity);

        String output = runDescribe(tempDir, staging);

        assertTrue(output.contains("Transient: true"),
                "Should show transient flag. Output: " + output);
    }

    // ---- Workspace-level describe ----

    @Test
    void describe_workspaceLevel_showsSummary() throws Exception {
        initWorkspace(tempDir);

        // Create a couple of directories with profiles
        Path meetings = Files.createDirectories(tempDir.resolve("meetings"));
        DirectoryIdentityParser parser = new DirectoryIdentityParser();

        DirectoryIdentity meetingsIdentity = new DirectoryIdentity(
                List.of("meeting-notes"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", ""
        );
        DirectoryCentroid meetingsCentroid = new DirectoryCentroid(
                List.of("standup", "retrospective"),
                List.of(), null, List.of(), 0.5, 3, 0, null
        );
        parser.writeProfile(meetings.resolve(".synthesis.md"),
                new DirectoryProfile(meetingsIdentity, meetingsCentroid, DirectoryWants.empty()));

        Path proposals = Files.createDirectories(tempDir.resolve("proposals"));
        DirectoryIdentity proposalsIdentity = new DirectoryIdentity(
                List.of("proposal"), List.of("pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", ""
        );
        DirectoryWants proposalsWants = new DirectoryWants(
                List.of("renewable energy", "GreenField"),
                List.of(), List.of(),
                "inferred from directory name",
                0.0
        );
        parser.writeProfile(proposals.resolve(".synthesis.md"),
                new DirectoryProfile(proposalsIdentity, DirectoryCentroid.empty(), proposalsWants));

        // Describe workspace (no targetDir)
        String output = runDescribeWorkspace(tempDir);

        // Should show workspace path
        assertTrue(output.contains("Workspace:"),
                "Should show workspace header. Output: " + output);

        // Should show summary
        assertTrue(output.contains("directories total"),
                "Should show summary counts. Output: " + output);

        // Should show starving directory
        assertTrue(output.contains("proposals") || output.contains("Starving") || output.contains("wants"),
                "Should mention starving or wants directories. Output: " + output);
    }

    @Test
    void describe_emptyWorkspace_showsHelpMessage() throws Exception {
        initWorkspace(tempDir);

        String output = runDescribeWorkspace(tempDir);

        assertTrue(output.contains("No directories"),
                "Should note empty workspace. Output: " + output);
    }

    // ---- formatConfidence ----

    @Test
    void formatConfidence_high() {
        assertEquals("HIGH", DescribeCommand.formatConfidence(0.85));
    }

    @Test
    void formatConfidence_medium() {
        assertEquals("MEDIUM", DescribeCommand.formatConfidence(0.55));
    }

    @Test
    void formatConfidence_low() {
        assertEquals("LOW", DescribeCommand.formatConfidence(0.25));
    }

    @Test
    void formatConfidence_veryLow() {
        assertEquals("VERY LOW", DescribeCommand.formatConfidence(0.1));
    }

    // ---- Helpers ----

    private void initWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
    }

    private DescribeCommand createCommand(Path workspaceRoot) throws Exception {
        DescribeCommand cmd = new DescribeCommand();
        SynthesisApp app = new SynthesisApp();
        Field rootField = SynthesisApp.class.getDeclaredField("workspaceRoot");
        rootField.setAccessible(true);
        rootField.set(app, workspaceRoot.toAbsolutePath().normalize());
        cmd.setParent(app);
        return cmd;
    }

    private String runDescribe(Path workspaceRoot, Path directory) throws Exception {
        DescribeCommand cmd = createCommand(workspaceRoot);
        cmd.setTargetDir(directory);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        cmd.setOut(new PrintStream(baos));
        cmd.call();
        return baos.toString();
    }

    private String runDescribeWorkspace(Path workspaceRoot) throws Exception {
        DescribeCommand cmd = createCommand(workspaceRoot);
        // No targetDir set -> workspace-level
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        cmd.setOut(new PrintStream(baos));
        cmd.call();
        return baos.toString();
    }
}
