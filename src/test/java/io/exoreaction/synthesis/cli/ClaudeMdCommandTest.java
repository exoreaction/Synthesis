package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClaudeMdCommand} and its {@code refresh} subcommand.
 */
class ClaudeMdCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void insertManagedSectionWhenNoMarkersExist() throws Exception {
        // Create a CLAUDE.md without markers
        Path claudeMd = tempDir.resolve("CLAUDE.md");
        String existingContent = "# My Project\n\nSome existing content.\n";
        Files.writeString(claudeMd, existingContent);

        initWorkspace(tempDir);

        ClaudeMdCommand.RefreshSubcommand cmd = createRefreshCommand(tempDir);
        cmd.setFile(claudeMd);

        // Suppress stderr
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        try {
            int exitCode = cmd.call();
            assertEquals(0, exitCode, "Should succeed");
        } finally {
            System.setErr(originalErr);
        }

        String result = Files.readString(claudeMd);

        // Should preserve existing content
        assertTrue(result.contains("# My Project"), "Should preserve existing heading");
        assertTrue(result.contains("Some existing content"), "Should preserve existing content");

        // Should have appended the managed section
        assertTrue(result.contains(ClaudeMdCommand.RefreshSubcommand.MARKER_START),
                "Should contain start marker. Got: " + result);
        assertTrue(result.contains(ClaudeMdCommand.RefreshSubcommand.MARKER_END),
                "Should contain end marker. Got: " + result);
        assertTrue(result.contains("## Synthesis Stats"),
                "Should contain section title. Got: " + result);
        assertTrue(result.contains("Files indexed"),
                "Should contain stats table. Got: " + result);
    }

    @Test
    void updateExistingManagedSectionLeavingSurroundingContentIntact() throws Exception {
        // Create a CLAUDE.md with markers and surrounding content
        String existingContent = "# My Project\n\nBefore section.\n\n" +
                ClaudeMdCommand.RefreshSubcommand.MARKER_START + "\n" +
                "## Synthesis Stats\nOld stats here\n" +
                ClaudeMdCommand.RefreshSubcommand.MARKER_END + "\n\n" +
                "After section.\n";
        Path claudeMd = tempDir.resolve("CLAUDE.md");
        Files.writeString(claudeMd, existingContent);

        initWorkspace(tempDir);

        ClaudeMdCommand.RefreshSubcommand cmd = createRefreshCommand(tempDir);
        cmd.setFile(claudeMd);

        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        try {
            int exitCode = cmd.call();
            assertEquals(0, exitCode, "Should succeed");
        } finally {
            System.setErr(originalErr);
        }

        String result = Files.readString(claudeMd);

        // Should preserve surrounding content
        assertTrue(result.contains("# My Project"), "Should preserve heading before section");
        assertTrue(result.contains("Before section"), "Should preserve content before section");
        assertTrue(result.contains("After section"), "Should preserve content after section");

        // Should have updated the managed section
        assertTrue(result.contains("Files indexed"), "Should contain updated stats");
        assertFalse(result.contains("Old stats here"), "Should have replaced old stats");
    }

    @Test
    void dryRunDoesNotModifyFile() throws Exception {
        Path claudeMd = tempDir.resolve("CLAUDE.md");
        String existingContent = "# My Project\n\nOriginal content.\n";
        Files.writeString(claudeMd, existingContent);

        initWorkspace(tempDir);

        ClaudeMdCommand.RefreshSubcommand cmd = createRefreshCommand(tempDir);
        cmd.setFile(claudeMd);
        cmd.setDryRun(true);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            int exitCode = cmd.call();
            assertEquals(0, exitCode, "Should succeed in dry-run mode");
        } finally {
            System.setOut(originalOut);
        }

        // File should remain unchanged
        String fileContent = Files.readString(claudeMd);
        assertEquals(existingContent, fileContent,
                "File should not be modified in dry-run mode");

        // Stdout should contain the result
        String output = baos.toString();
        assertTrue(output.contains("Files indexed"),
                "Dry-run should print result to stdout. Got: " + output);
    }

    @Test
    void malformedMarkersAbortWithError() throws Exception {
        // Create a CLAUDE.md with start marker but no end marker
        String malformedContent = "# My Project\n\n" +
                ClaudeMdCommand.RefreshSubcommand.MARKER_START + "\n" +
                "## Synthesis Stats\nDangling section...\n";
        Path claudeMd = tempDir.resolve("CLAUDE.md");
        Files.writeString(claudeMd, malformedContent);

        initWorkspace(tempDir);

        ClaudeMdCommand.RefreshSubcommand cmd = createRefreshCommand(tempDir);
        cmd.setFile(claudeMd);

        // Capture stderr for error messages
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBaos = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBaos));
        try {
            int exitCode = cmd.call();
            assertEquals(1, exitCode, "Should return error code for malformed markers");
        } finally {
            System.setErr(originalErr);
        }

        // File should remain unchanged
        String fileContent = Files.readString(claudeMd);
        assertEquals(malformedContent, fileContent,
                "File should not be modified when markers are malformed");
    }

    @Test
    void malformedMarkersEndWithoutStartAborts() throws Exception {
        // Create a CLAUDE.md with end marker but no start marker
        String malformedContent = "# My Project\n\n" +
                "Some content\n" +
                ClaudeMdCommand.RefreshSubcommand.MARKER_END + "\n";
        Path claudeMd = tempDir.resolve("CLAUDE.md");
        Files.writeString(claudeMd, malformedContent);

        initWorkspace(tempDir);

        ClaudeMdCommand.RefreshSubcommand cmd = createRefreshCommand(tempDir);
        cmd.setFile(claudeMd);

        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        try {
            int exitCode = cmd.call();
            assertEquals(1, exitCode, "Should return error code when end marker exists without start");
        } finally {
            System.setErr(originalErr);
        }

        // File should remain unchanged
        String fileContent = Files.readString(claudeMd);
        assertEquals(malformedContent, fileContent,
                "File should not be modified when markers are malformed");
    }

    @Test
    void createsFileWhenClaudeMdDoesNotExist() throws Exception {
        Path claudeMd = tempDir.resolve("new-CLAUDE.md");
        assertFalse(Files.exists(claudeMd), "File should not exist yet");

        initWorkspace(tempDir);

        ClaudeMdCommand.RefreshSubcommand cmd = createRefreshCommand(tempDir);
        cmd.setFile(claudeMd);

        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        try {
            int exitCode = cmd.call();
            assertEquals(0, exitCode, "Should succeed creating new file");
        } finally {
            System.setErr(originalErr);
        }

        assertTrue(Files.exists(claudeMd), "File should be created");
        String content = Files.readString(claudeMd);
        assertTrue(content.contains(ClaudeMdCommand.RefreshSubcommand.MARKER_START),
                "Should contain start marker");
        assertTrue(content.contains(ClaudeMdCommand.RefreshSubcommand.MARKER_END),
                "Should contain end marker");
        assertTrue(content.contains("## Synthesis Stats"),
                "Should contain section title");
    }

    @Test
    void customSectionTitleIsUsed() throws Exception {
        Path claudeMd = tempDir.resolve("CLAUDE.md");
        Files.writeString(claudeMd, "# Project\n");

        initWorkspace(tempDir);

        ClaudeMdCommand.RefreshSubcommand cmd = createRefreshCommand(tempDir);
        cmd.setFile(claudeMd);
        cmd.setSectionTitle("Custom Stats Title");

        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        try {
            int exitCode = cmd.call();
            assertEquals(0, exitCode, "Should succeed with custom section title");
        } finally {
            System.setErr(originalErr);
        }

        String content = Files.readString(claudeMd);
        assertTrue(content.contains("## Custom Stats Title"),
                "Should use custom section title. Got: " + content);
    }

    // ---- Helpers ----

    private void initWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
    }

    private ClaudeMdCommand.RefreshSubcommand createRefreshCommand(Path workspaceRoot) throws Exception {
        ClaudeMdCommand.RefreshSubcommand cmd = new ClaudeMdCommand.RefreshSubcommand();
        ClaudeMdCommand parentCmd = new ClaudeMdCommand();
        SynthesisApp app = new SynthesisApp();
        Field rootField = SynthesisApp.class.getDeclaredField("workspaceRoot");
        rootField.setAccessible(true);
        rootField.set(app, workspaceRoot.toAbsolutePath().normalize());
        parentCmd.setParent(app);
        cmd.setParent(parentCmd);
        return cmd;
    }
}
