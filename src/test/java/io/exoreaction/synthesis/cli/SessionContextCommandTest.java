package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SessionContextCommand}.
 */
class SessionContextCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void commandRegistersAndRunsOnInitializedWorkspace() throws Exception {
        initWorkspace(tempDir);

        SessionContextCommand cmd = createCommand(tempDir);
        cmd.setNoSecurity(true);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            int exitCode = cmd.call();
            assertEquals(0, exitCode, "Command should succeed on initialized workspace");
            String output = baos.toString();
            assertTrue(output.contains("Synthesis Session Context") || output.contains("workspace:"),
                    "Output should contain context information. Got: " + output);
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void compactFlagProducesSingleLineOutput() throws Exception {
        initWorkspace(tempDir);

        SessionContextCommand cmd = createCommand(tempDir);
        cmd.setCompact(true);
        cmd.setNoSecurity(true);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            int exitCode = cmd.call();
            assertEquals(0, exitCode, "Command should succeed");
            String output = baos.toString();
            // Compact output should be a single line (no newlines)
            assertFalse(output.contains("\n"),
                    "Compact output should not contain newlines. Got: " + output);
            assertTrue(output.contains("workspace:"),
                    "Compact output should contain workspace info. Got: " + output);
            assertTrue(output.contains("changed:"),
                    "Compact output should contain change info. Got: " + output);
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void sinceParameterIsAccepted() throws Exception {
        initWorkspace(tempDir);

        SessionContextCommand cmd = createCommand(tempDir);
        cmd.setSince("7d");
        cmd.setNoSecurity(true);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            int exitCode = cmd.call();
            assertEquals(0, exitCode, "Command should succeed with --since 7d");
            String output = baos.toString();
            assertTrue(output.contains("7d"),
                    "Output should reference the since duration. Got: " + output);
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void extractPackage_rootFile() {
        assertEquals("(root)", SessionContextCommand.extractPackage("README.md"));
    }

    @Test
    void extractPackage_nestedFile() {
        assertEquals("cli", SessionContextCommand.extractPackage("src/main/java/cli/MyCommand.java"));
    }

    @Test
    void extractPackage_singleDirFile() {
        assertEquals("docs", SessionContextCommand.extractPackage("docs/README.md"));
    }

    @Test
    void helpFlagWorks() {
        SynthesisApp app = new SynthesisApp();
        CommandLine cmd = new CommandLine(app);
        int exitCode = cmd.execute("session-context", "--help");
        assertEquals(0, exitCode, "--help should return 0");
    }

    // ---- Helpers ----

    private void initWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
    }

    private SessionContextCommand createCommand(Path workspaceRoot) throws Exception {
        SessionContextCommand cmd = new SessionContextCommand();
        SynthesisApp app = new SynthesisApp();
        Field rootField = SynthesisApp.class.getDeclaredField("workspaceRoot");
        rootField.setAccessible(true);
        rootField.set(app, workspaceRoot.toAbsolutePath().normalize());
        cmd.setParent(app);
        return cmd;
    }
}
