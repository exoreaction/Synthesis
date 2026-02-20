package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.org.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SyncCommand}.
 */
class SyncCommandTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // call — empty workspace
    // -------------------------------------------------------------------------

    @Test
    void call_emptyWorkspace_prints0Synced() throws Exception {
        initWorkspace(tempDir);

        String output = runSync(tempDir);

        assertTrue(output.contains("0 directories"),
                "Empty workspace should report 0 directories. Output: " + output);
    }

    // -------------------------------------------------------------------------
    // call — new directory creates file
    // -------------------------------------------------------------------------

    @Test
    void call_newDirectory_createsFile() throws Exception {
        initWorkspace(tempDir);
        Path meetings = Files.createDirectories(tempDir.resolve("meetings"));
        Files.writeString(meetings.resolve("standup-2026-02-20.md"), "# Standup\nAttendees: Alice, Bob");

        runSync(tempDir);

        Path synthesisFile = meetings.resolve(".synthesis.md");
        assertTrue(Files.exists(synthesisFile),
                ".synthesis.md should be created for meetings/ directory");
    }

    // -------------------------------------------------------------------------
    // call — dry run does not write file
    // -------------------------------------------------------------------------

    @Test
    void call_dryRun_doesNotWriteFile() throws Exception {
        initWorkspace(tempDir);
        Path meetings = Files.createDirectories(tempDir.resolve("meetings"));
        Files.writeString(meetings.resolve("standup.md"), "# Standup");

        String output = runSync(tempDir, "--dry-run");

        Path synthesisFile = meetings.resolve(".synthesis.md");
        assertFalse(Files.exists(synthesisFile),
                ".synthesis.md should NOT be created in dry-run mode");
        assertTrue(output.contains("[DRY]"),
                "Dry-run output should contain [DRY] marker. Output: " + output);
    }

    // -------------------------------------------------------------------------
    // call — recognized name includes vocabulary types
    // -------------------------------------------------------------------------

    @Test
    void call_recognizedName_includesVocabularyTypes() throws Exception {
        initWorkspace(tempDir);
        Path meetings = Files.createDirectories(tempDir.resolve("meetings"));
        Files.writeString(meetings.resolve("standup.md"), "# Standup\nNotes from today");

        runSync(tempDir);

        Path synthesisFile = meetings.resolve(".synthesis.md");
        assertTrue(Files.exists(synthesisFile));

        String content = Files.readString(synthesisFile);
        assertTrue(content.contains("meeting-notes"),
                ".synthesis.md should contain 'meeting-notes' type from vocabulary. Content: " + content);
    }

    // -------------------------------------------------------------------------
    // call — existing file merges, not overwrites
    // -------------------------------------------------------------------------

    @Test
    void call_existingFile_mergesNotOverwrites() throws Exception {
        initWorkspace(tempDir);
        Path meetings = Files.createDirectories(tempDir.resolve("meetings"));
        Files.writeString(meetings.resolve("standup.md"), "# Standup");

        // Pre-create .synthesis.md with a custom type
        DirectoryIdentityParser parser = new DirectoryIdentityParser();
        DirectoryIdentity custom = new DirectoryIdentity(
                List.of("custom-type"),
                List.of("txt"),
                List.of(),
                ScopeLevel.WORKSPACE,
                null,
                null,
                0.9,
                Instant.now(),
                "user declared",
                "My custom description"
        );
        Path synthesisFile = meetings.resolve(".synthesis.md");
        parser.write(synthesisFile, custom);

        runSync(tempDir);

        // Re-parse and verify the custom type is preserved
        DirectoryIdentity result = parser.parse(synthesisFile);
        assertTrue(result.acceptsTypes().contains("custom-type"),
                "Custom type should be preserved after merge. Types: " + result.acceptsTypes());
        // Vocabulary types should also be added
        assertTrue(result.acceptsTypes().contains("meeting-notes"),
                "Vocabulary type should be merged in. Types: " + result.acceptsTypes());
    }

    // -------------------------------------------------------------------------
    // call — force overwrites existing
    // -------------------------------------------------------------------------

    @Test
    void call_force_overwritesExisting() throws Exception {
        initWorkspace(tempDir);
        Path meetings = Files.createDirectories(tempDir.resolve("meetings"));
        Files.writeString(meetings.resolve("standup.md"), "# Standup");

        // Pre-create .synthesis.md with a custom type
        DirectoryIdentityParser parser = new DirectoryIdentityParser();
        DirectoryIdentity custom = new DirectoryIdentity(
                List.of("custom-type"),
                List.of("txt"),
                List.of(),
                ScopeLevel.WORKSPACE,
                null,
                null,
                0.9,
                Instant.now(),
                "user declared",
                "My custom description"
        );
        Path synthesisFile = meetings.resolve(".synthesis.md");
        parser.write(synthesisFile, custom);

        runSync(tempDir, "--force");

        // Re-parse — custom-type should be gone because force replaces
        DirectoryIdentity result = parser.parse(synthesisFile);
        assertFalse(result.acceptsTypes().contains("custom-type"),
                "Force should overwrite existing identity. Types: " + result.acceptsTypes());
        assertTrue(result.acceptsTypes().contains("meeting-notes"),
                "Vocabulary type should be present after force. Types: " + result.acceptsTypes());
    }

    // -------------------------------------------------------------------------
    // call — skips hidden dirs
    // -------------------------------------------------------------------------

    @Test
    void call_skipsHiddenDirs() throws Exception {
        initWorkspace(tempDir);
        Path gitDir = Files.createDirectories(tempDir.resolve(".git"));
        Files.writeString(gitDir.resolve("config"), "some git config");

        runSync(tempDir);

        assertFalse(Files.exists(gitDir.resolve(".synthesis.md")),
                ".git/ directory should not get a .synthesis.md file");
    }

    // -------------------------------------------------------------------------
    // call — skips empty unrecognized dirs
    // -------------------------------------------------------------------------

    @Test
    void call_skipsEmptyUnrecognizedDirs() throws Exception {
        initWorkspace(tempDir);
        Path randomDir = Files.createDirectories(tempDir.resolve("random-xyz"));
        // No files in the directory

        runSync(tempDir);

        assertFalse(Files.exists(randomDir.resolve(".synthesis.md")),
                "Empty unrecognized directory should not get a .synthesis.md file");
    }

    // -------------------------------------------------------------------------
    // Static helper tests
    // -------------------------------------------------------------------------

    @Test
    void buildIdentityFromSignals_mapsFields() {
        DirectorySignalExtractor.DirectorySignals signals =
                new DirectorySignalExtractor.DirectorySignals(
                        List.of("meeting-notes"),
                        List.of("md", "pdf"),
                        List.of("*meeting*"),
                        java.util.Map.of("md", 3, "pdf", 1),
                        4,
                        0.7
                );
        ScopeResolver.ResolvedScope scope =
                new ScopeResolver.ResolvedScope(ScopeLevel.ORGANIZATION, "TestOrg", null);

        DirectoryIdentity identity = SyncCommand.buildIdentityFromSignals(signals, scope);

        assertEquals(List.of("meeting-notes"), identity.acceptsTypes());
        assertEquals(List.of("md", "pdf"), identity.acceptsFormats());
        assertEquals(List.of("*meeting*"), identity.acceptsPatterns());
        assertEquals(ScopeLevel.ORGANIZATION, identity.scopeLevel());
        assertEquals("TestOrg", identity.scopeOrganization());
        assertNull(identity.scopeEntity());
        assertEquals(0.7, identity.confidence(), 0.001);
        assertTrue(identity.source().contains("4 files"));
    }

    @Test
    void isEquivalent_sameContent_returnsTrue() {
        DirectoryIdentity a = new DirectoryIdentity(
                List.of("report"), List.of("pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, Instant.parse("2026-02-20T10:00:00Z"), "test", "desc"
        );
        DirectoryIdentity b = new DirectoryIdentity(
                List.of("report"), List.of("pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, Instant.parse("2026-02-21T10:00:00Z"), "test", "desc"
        );
        // Only lastSynced differs — should be equivalent
        assertTrue(SyncCommand.isEquivalent(a, b));
    }

    @Test
    void isEquivalent_differentTypes_returnsFalse() {
        DirectoryIdentity a = new DirectoryIdentity(
                List.of("report"), List.of("pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "desc"
        );
        DirectoryIdentity b = new DirectoryIdentity(
                List.of("invoice"), List.of("pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "desc"
        );
        assertFalse(SyncCommand.isEquivalent(a, b));
    }

    // -------------------------------------------------------------------------
    // #172 — verbose dry-run shows identity detail
    // -------------------------------------------------------------------------

    @Test
    void call_dryRunVerbose_showsIdentityDetail() throws Exception {
        initWorkspace(tempDir);
        Path meetings = Files.createDirectories(tempDir.resolve("meetings"));
        Files.writeString(meetings.resolve("standup.md"), "# Standup");

        String output = runSync(tempDir, "--dry-run", "--verbose");

        assertFalse(Files.exists(meetings.resolve(".synthesis.md")),
                ".synthesis.md should NOT be created in dry-run mode");
        assertTrue(output.contains("[DRY CREATE]") || output.contains("[DRY UPDATE]"),
                "Verbose dry-run output should contain [DRY CREATE] or [DRY UPDATE]. Output: " + output);
        assertTrue(output.contains("meeting-notes"),
                "Verbose dry-run output should show inferred types. Output: " + output);
        assertTrue(output.contains("confidence="),
                "Verbose dry-run output should show confidence. Output: " + output);
    }

    @Test
    void call_dryRunNoVerbose_showsTerseLine() throws Exception {
        initWorkspace(tempDir);
        Path meetings = Files.createDirectories(tempDir.resolve("meetings"));
        Files.writeString(meetings.resolve("standup.md"), "# Standup");

        String output = runSync(tempDir, "--dry-run");

        assertTrue(output.contains("[DRY] Would create:"),
                "Non-verbose dry-run should show terse line. Output: " + output);
        assertFalse(output.contains("confidence="),
                "Non-verbose dry-run should NOT show identity detail. Output: " + output);
    }

    // -------------------------------------------------------------------------
    // #173 — skip Java package paths and deep archive subtrees
    // -------------------------------------------------------------------------

    @Test
    void isCodePackagePath_javaSourceTree_returnsTrue() throws Exception {
        Path root = tempDir;
        Path javaDir = Files.createDirectories(
                root.resolve("clients/my-app/src/main/java/com/example"));
        assertTrue(SyncCommand.isCodePackagePath(root, javaDir),
                "Deep Java package path should be excluded");
    }

    @Test
    void isCodePackagePath_srcMainJavaBoundary_returnsTrue() throws Exception {
        Path root = tempDir;
        Path javaRoot = Files.createDirectories(root.resolve("project/src/main/java"));
        assertTrue(SyncCommand.isCodePackagePath(root, javaRoot),
                "src/main/java boundary should be excluded");
    }

    @Test
    void isCodePackagePath_semanticDir_returnsFalse() throws Exception {
        Path root = tempDir;
        Path semanticDir = Files.createDirectories(root.resolve("eXOReaction/business/meetings"));
        assertFalse(SyncCommand.isCodePackagePath(root, semanticDir),
                "Semantic business directory should not be excluded");
    }

    @Test
    void isDeepInsideArchive_twoLevelsDeep_returnsFalse() throws Exception {
        Path root = tempDir;
        Path shallowArchive = Files.createDirectories(root.resolve("archive/2022-commits"));
        assertFalse(SyncCommand.isDeepInsideArchive(root, shallowArchive),
                "archive/2022-commits (1 level deep) should NOT be excluded");
    }

    @Test
    void isDeepInsideArchive_threeOrMoreLevelsDeep_returnsTrue() throws Exception {
        Path root = tempDir;
        Path deepArchive = Files.createDirectories(
                root.resolve("archive/2022-commits/some-project/deep-subdir"));
        assertTrue(SyncCommand.isDeepInsideArchive(root, deepArchive),
                "archive/2022/some-project/deep-subdir (3 levels deep) should be excluded");
    }

    @Test
    void isDeepInsideArchive_notInArchive_returnsFalse() throws Exception {
        Path root = tempDir;
        Path normal = Files.createDirectories(root.resolve("business/proposals/deep/nested"));
        assertFalse(SyncCommand.isDeepInsideArchive(root, normal),
                "Non-archive deep path should not be excluded");
    }

    @Test
    void call_skipsJavaPackageDirs() throws Exception {
        initWorkspace(tempDir);
        Path javaDir = Files.createDirectories(
                tempDir.resolve("project/src/main/java/com/example"));
        Files.writeString(javaDir.resolve("Foo.java"), "public class Foo {}");

        runSync(tempDir);

        assertFalse(Files.exists(javaDir.resolve(".synthesis.md")),
                "Java package directory should not get a .synthesis.md file");
    }

    @Test
    void call_skipsDeepArchiveDirs() throws Exception {
        initWorkspace(tempDir);
        Path deepArchive = Files.createDirectories(
                tempDir.resolve("archive/old/nested/deep"));
        Files.writeString(deepArchive.resolve("notes.md"), "Old notes");

        runSync(tempDir);

        assertFalse(Files.exists(deepArchive.resolve(".synthesis.md")),
                "Deep archive directory should not get a .synthesis.md file");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Initializes a minimal workspace structure for tests.
     */
    private void initWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
    }

    /**
     * Runs the SyncCommand against the given workspace root and returns captured stdout.
     */
    private String runSync(Path workspaceRoot, String... extraArgs) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(baos));

        try {
            SyncCommand cmd = new SyncCommand();
            SynthesisApp app = new SynthesisApp();
            // Set workspace root directly via reflection to avoid picocli subcommand sharing issues
            Field rootField = SynthesisApp.class.getDeclaredField("workspaceRoot");
            rootField.setAccessible(true);
            rootField.set(app, workspaceRoot.toAbsolutePath().normalize());
            cmd.setParent(app);

            // Parse extra args on the SyncCommand itself
            if (extraArgs.length > 0) {
                CommandLine syncCmdLine = new CommandLine(cmd);
                syncCmdLine.parseArgs(extraArgs);
            }

            cmd.call();
        } finally {
            System.setOut(originalOut);
        }

        return baos.toString();
    }
}
