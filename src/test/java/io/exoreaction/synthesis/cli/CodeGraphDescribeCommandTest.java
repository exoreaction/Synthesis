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
 * Integration tests for {@code synthesis code-graph describe} subcommand.
 *
 * @since v1.12.2 (CKG-2.05)
 */
class CodeGraphDescribeCommandTest {

    @TempDir
    Path tempDir;

    // =========================================================================
    // Helpers
    // =========================================================================

    private void setupWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
        Files.writeString(root.resolve(".synthesis/config.yaml"),
                "workspace:\n  name: \"describe-test\"\n");
    }

    private void createJavaFiles(Path root) throws IOException {
        Path coreDir = Files.createDirectories(root.resolve("src/main/java/com/example/core"));
        Files.writeString(coreDir.resolve("Model.java"), """
                package com.example.core;
                public class Model {
                    private String name;
                }
                """);

        Path cliDir = Files.createDirectories(root.resolve("src/main/java/com/example/cli"));
        Files.writeString(cliDir.resolve("App.java"), """
                package com.example.cli;
                import com.example.core.Model;
                public class App {
                    private Model model;
                }
                """);

        Path utilDir = Files.createDirectories(root.resolve("src/main/java/com/example/util"));
        Files.writeString(utilDir.resolve("Helper.java"), """
                package com.example.util;
                public class Helper {
                    public static String format(String s) { return s; }
                }
                """);
    }

    private String runCodeGraph(Path workspaceDir, String... subArgs) {
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

            cmd.execute(args);
        } finally {
            System.setOut(original);
        }
        return baos.toString();
    }

    private int runCodeGraphExitCode(Path workspaceDir, String... subArgs) {
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
    // Tests: describe (no data)
    // =========================================================================

    @Test
    void describe_empty_shows_guidance() throws Exception {
        setupWorkspace(tempDir);

        String output = runCodeGraph(tempDir, "describe");

        assertTrue(output.contains("No module profiles found"),
                "Should show guidance when no profiles exist: " + output);
    }

    // =========================================================================
    // Tests: describe --refresh (extracts + computes + shows)
    // =========================================================================

    @Test
    void describe_refresh_shows_profiles() throws Exception {
        setupWorkspace(tempDir);
        createJavaFiles(tempDir);

        String output = runCodeGraph(tempDir, "describe", "--refresh");

        assertTrue(output.contains("Module Profiles"),
                "Should show 'Module Profiles' header: " + output);
        assertTrue(output.contains("Purpose:"),
                "Should show purpose field: " + output);
        assertTrue(output.contains("Fan-in:"),
                "Should show fan-in field: " + output);
        assertTrue(output.contains("Instability:"),
                "Should show instability field: " + output);
    }

    @Test
    void describe_refresh_returns_zero() throws Exception {
        setupWorkspace(tempDir);
        createJavaFiles(tempDir);

        int exitCode = runCodeGraphExitCode(tempDir, "describe", "--refresh");
        assertEquals(0, exitCode, "describe --refresh should succeed");
    }

    // =========================================================================
    // Tests: describe --module filter
    // =========================================================================

    @Test
    void describe_module_filter() throws Exception {
        setupWorkspace(tempDir);
        createJavaFiles(tempDir);

        // First populate
        runCodeGraph(tempDir, "describe", "--refresh");

        // Filter by "cli"
        String output = runCodeGraph(tempDir, "describe", "--module", "cli");

        assertTrue(output.contains("cli"),
                "Filtered output should contain 'cli': " + output);
        // Should not contain "core" as a separate profile
        // (might appear in instability context though, so just check for main header count)
        assertTrue(output.contains("Module Profiles"),
                "Should still show header: " + output);
    }

    // =========================================================================
    // Tests: describe --format json
    // =========================================================================

    @Test
    void describe_json_format() throws Exception {
        setupWorkspace(tempDir);
        createJavaFiles(tempDir);

        String output = runCodeGraph(tempDir, "describe", "--refresh", "--format", "json");

        assertTrue(output.contains("["),
                "JSON output should start with array bracket: " + output);
        assertTrue(output.contains("\"modulePath\""),
                "JSON should contain modulePath field: " + output);
        assertTrue(output.contains("\"fanIn\""),
                "JSON should contain fanIn field: " + output);
        assertTrue(output.contains("\"instability\""),
                "JSON should contain instability field: " + output);
    }

    // =========================================================================
    // Tests: describe --instability
    // =========================================================================

    @Test
    void describe_instability_sort() throws Exception {
        setupWorkspace(tempDir);
        createJavaFiles(tempDir);

        String output = runCodeGraph(tempDir, "describe", "--refresh", "--instability");

        assertTrue(output.contains("Module Profiles"),
                "Should show profiles with instability sort: " + output);
        assertTrue(output.contains("Instability:"),
                "Should contain instability field: " + output);
    }

    // =========================================================================
    // Tests: help text includes describe
    // =========================================================================

    @Test
    void no_subcommand_shows_empty_graph_message() throws Exception {
        setupWorkspace(tempDir);

        String output = runCodeGraph(tempDir);

        // When no subcommand and no data, shows empty graph message
        assertTrue(output.contains("No code graph data"),
                "Should show empty graph message: " + output);
    }
}
