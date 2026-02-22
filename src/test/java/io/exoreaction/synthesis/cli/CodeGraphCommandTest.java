package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link CodeGraphCommand} and its {@code extract} subcommand.
 *
 * <p>Tests run the full picocli command stack to verify CLI behaviour,
 * output formatting, and correct interaction with the code graph database.
 *
 * @since v1.9.9 (CKG-1.06)
 */
class CodeGraphCommandTest {

    @TempDir
    Path tempDir;

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates a minimal Synthesis workspace with a config file.
     */
    private void setupWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
        Files.writeString(root.resolve(".synthesis/config.yaml"),
                "workspace:\n  name: \"code-graph-test\"\n");
    }

    /**
     * Creates Java source files in the workspace for extraction testing.
     */
    private void createJavaFiles(Path root) throws IOException {
        Path srcDir = Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Config.java"), """
                package com.example;
                public class Config {
                    private String name;
                }
                """);
        Files.writeString(srcDir.resolve("Service.java"), """
                package com.example;
                import com.example.Config;
                import java.util.List;

                public class Service {
                    private Config config;
                }
                """);
        Files.writeString(srcDir.resolve("App.java"), """
                package com.example;
                import com.example.Service;

                public class App {
                    private Service service;
                }
                """);
    }

    /**
     * Runs {@code synthesis code-graph <subArgs>} against the given workspace,
     * captures stdout, and returns the output.
     */
    private String runCodeGraph(Path workspaceDir, String... subArgs) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(baos));
        try {
            CommandLine cmd = new CommandLine(new SynthesisApp());
            SynthesisApp.installGroupedHelpRenderer(cmd);

            // Build args: -d <dir> code-graph [subArgs...]
            String[] args = new String[subArgs.length + 3];
            args[0] = "-d";
            args[1] = workspaceDir.toString();
            args[2] = "code-graph";
            System.arraycopy(subArgs, 0, args, 3, subArgs.length);

            cmd.execute(args);
        } finally {
            System.setOut(original);
        }
        return baos.toString();
    }

    /**
     * Runs and returns the exit code.
     */
    private int runCodeGraphWithExitCode(Path workspaceDir, String... subArgs) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(baos));
        try {
            CommandLine cmd = new CommandLine(new SynthesisApp());
            SynthesisApp.installGroupedHelpRenderer(cmd);

            String[] args = new String[subArgs.length + 3];
            args[0] = "-d";
            args[1] = workspaceDir.toString();
            args[2] = "code-graph";
            System.arraycopy(subArgs, 0, args, 3, subArgs.length);

            return cmd.execute(args);
        } finally {
            System.setOut(original);
        }
    }

    // =========================================================================
    // No subcommand → help text
    // =========================================================================

    @Test
    void no_subcommand_no_data_shows_empty_message() throws Exception {
        setupWorkspace(tempDir);
        String output = runCodeGraph(tempDir);

        assertTrue(output.contains("No code graph data"),
                "Should show empty graph message when no data: " + output);
    }

    // =========================================================================
    // extract --dry-run
    // =========================================================================

    @Test
    void extract_dry_run_shows_file_counts() throws Exception {
        setupWorkspace(tempDir);
        createJavaFiles(tempDir);

        String output = runCodeGraph(tempDir, "extract", "--dry-run");

        assertTrue(output.contains("dry-run"),
                "Dry-run output should mention 'dry-run': " + output);
        assertTrue(output.contains("Java files:"),
                "Should show Java file count: " + output);
        assertTrue(output.contains("3"),
                "Should report 3 Java files: " + output);
        assertTrue(output.contains("No changes made"),
                "Should say no changes: " + output);
    }

    @Test
    void extract_dry_run_with_no_java_files() throws Exception {
        setupWorkspace(tempDir);
        // No Java files in workspace

        String output = runCodeGraph(tempDir, "extract", "--dry-run");

        assertTrue(output.contains("Java files:"),
                "Should show Java file count: " + output);
        assertTrue(output.contains("0"),
                "Should report 0 Java files: " + output);
    }

    // =========================================================================
    // extract (full)
    // =========================================================================

    @Test
    void extract_full_processes_java_files() throws Exception {
        setupWorkspace(tempDir);
        createJavaFiles(tempDir);

        String output = runCodeGraph(tempDir, "extract");

        assertTrue(output.contains("Files processed:"),
                "Should show files processed: " + output);
        assertTrue(output.contains("Dependencies found:"),
                "Should show dependencies found: " + output);
        assertTrue(output.contains("Elapsed:"),
                "Should show elapsed time: " + output);
        assertTrue(output.contains("3"),
                "Should process 3 Java files: " + output);
    }

    @Test
    void extract_full_returns_zero_exit_code() throws Exception {
        setupWorkspace(tempDir);
        createJavaFiles(tempDir);

        int exitCode = runCodeGraphWithExitCode(tempDir, "extract");
        assertEquals(0, exitCode, "Full extraction should succeed with exit code 0");
    }

    // =========================================================================
    // extract --stats
    // =========================================================================

    @Test
    void extract_stats_on_empty_graph() throws Exception {
        setupWorkspace(tempDir);

        String output = runCodeGraph(tempDir, "extract", "--stats");

        assertTrue(output.contains("empty"),
                "Empty graph should show 'empty' status: " + output);
        assertTrue(output.contains("Dependencies:"),
                "Should show dependency count: " + output);
        assertTrue(output.contains("0"),
                "Empty graph should have 0 dependencies: " + output);
    }

    @Test
    void extract_stats_after_extraction() throws Exception {
        setupWorkspace(tempDir);
        createJavaFiles(tempDir);

        // First, run extraction
        runCodeGraph(tempDir, "extract");

        // Then check stats
        String output = runCodeGraph(tempDir, "extract", "--stats");

        assertTrue(output.contains("populated"),
                "After extraction, should show 'populated': " + output);
        assertTrue(output.contains("Dependencies:"),
                "Should show dependency count: " + output);
    }

    // =========================================================================
    // extract --incremental
    // =========================================================================

    @Test
    void extract_incremental_processes_files() throws Exception {
        setupWorkspace(tempDir);
        createJavaFiles(tempDir);

        String output = runCodeGraph(tempDir, "extract", "--incremental");

        assertTrue(output.contains("incremental"),
                "Should mention 'incremental': " + output);
        assertTrue(output.contains("Files processed:"),
                "Should show files processed: " + output);
    }

    @Test
    void extract_incremental_returns_zero_exit_code() throws Exception {
        setupWorkspace(tempDir);
        createJavaFiles(tempDir);

        int exitCode = runCodeGraphWithExitCode(tempDir, "extract", "--incremental");
        assertEquals(0, exitCode, "Incremental extraction should succeed");
    }

    // =========================================================================
    // Alias: synthesis cg extract
    // =========================================================================

    @Test
    void alias_cg_works() throws Exception {
        setupWorkspace(tempDir);
        createJavaFiles(tempDir);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(baos));
        try {
            CommandLine cmd = new CommandLine(new SynthesisApp());
            SynthesisApp.installGroupedHelpRenderer(cmd);

            int exitCode = cmd.execute("-d", tempDir.toString(), "cg", "extract", "--dry-run");
            assertEquals(0, exitCode, "'cg' alias should work");
        } finally {
            System.setOut(original);
        }

        String output = baos.toString();
        assertTrue(output.contains("dry-run"),
                "cg alias should produce dry-run output: " + output);
    }
}
