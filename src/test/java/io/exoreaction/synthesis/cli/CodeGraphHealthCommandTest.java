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
 * Integration tests for {@code synthesis code-graph health} subcommand.
 *
 * @since v1.12.2 (CKG-2.05)
 */
class CodeGraphHealthCommandTest {

    @TempDir
    Path tempDir;

    // =========================================================================
    // Helpers
    // =========================================================================

    private void setupWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
        Files.writeString(root.resolve(".synthesis/config.yaml"),
                "workspace:\n  name: \"health-test\"\n");
    }

    /**
     * Creates Java files with a circular dependency for health signal detection.
     */
    private void createCircularDependencyFiles(Path root) throws IOException {
        Path pkgA = Files.createDirectories(root.resolve("src/main/java/com/a"));
        Path pkgB = Files.createDirectories(root.resolve("src/main/java/com/b"));

        Files.writeString(pkgA.resolve("ClassA.java"), """
                package com.a;
                import com.b.ClassB;
                public class ClassA {
                    private ClassB b;
                }
                """);
        Files.writeString(pkgB.resolve("ClassB.java"), """
                package com.b;
                import com.a.ClassA;
                public class ClassB {
                    private ClassA a;
                }
                """);
    }

    /**
     * Creates a clean workspace with no health issues.
     */
    private void createCleanFiles(Path root) throws IOException {
        Path coreDir = Files.createDirectories(root.resolve("src/main/java/com/example/core"));
        Files.writeString(coreDir.resolve("Model.java"), """
                package com.example.core;
                public class Model {
                    private String name;
                }
                """);

        Path svcDir = Files.createDirectories(root.resolve("src/main/java/com/example/service"));
        Files.writeString(svcDir.resolve("Service.java"), """
                package com.example.service;
                import com.example.core.Model;
                public class Service {
                    private Model model;
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
    // Tests: health (no data)
    // =========================================================================

    @Test
    void health_empty_shows_guidance() throws Exception {
        setupWorkspace(tempDir);

        String output = runCodeGraph(tempDir, "health");

        assertTrue(output.contains("No module profiles found"),
                "Should show guidance when no profiles exist: " + output);
    }

    // =========================================================================
    // Tests: health --refresh
    // =========================================================================

    @Test
    void health_refresh_returns_zero() throws Exception {
        setupWorkspace(tempDir);
        createCleanFiles(tempDir);

        int exitCode = runCodeGraphExitCode(tempDir, "health", "--refresh");
        assertEquals(0, exitCode, "health --refresh should succeed");
    }

    @Test
    void health_refresh_shows_signals_header() throws Exception {
        setupWorkspace(tempDir);
        createCircularDependencyFiles(tempDir);

        String output = runCodeGraph(tempDir, "health", "--refresh");

        // Should show either signals or "No issues"
        assertTrue(output.contains("Code Health") || output.contains("No issues"),
                "Should show health analysis output: " + output);
    }

    @Test
    void health_detects_circular_dependency() throws Exception {
        setupWorkspace(tempDir);
        createCircularDependencyFiles(tempDir);

        String output = runCodeGraph(tempDir, "health", "--refresh");

        assertTrue(output.contains("C001_CIRCULAR_DEPENDENCY")
                || output.contains("CIRCULAR"),
                "Should detect circular dependency: " + output);
    }

    // =========================================================================
    // Tests: health --errors-only
    // =========================================================================

    @Test
    void health_errors_only_filters_non_high() throws Exception {
        setupWorkspace(tempDir);
        createCleanFiles(tempDir);

        String output = runCodeGraph(tempDir, "health", "--refresh", "--errors-only");

        // Either no issues or only HIGH severity shown
        assertFalse(output.contains("[LOW]"),
                "errors-only should not show LOW signals: " + output);
        assertFalse(output.contains("[MEDIUM]"),
                "errors-only should not show MEDIUM signals: " + output);
    }

    // =========================================================================
    // Tests: health --format json
    // =========================================================================

    @Test
    void health_json_format() throws Exception {
        setupWorkspace(tempDir);
        createCircularDependencyFiles(tempDir);

        String output = runCodeGraph(tempDir, "health", "--refresh", "--format", "json");

        assertTrue(output.contains("["),
                "JSON output should start with array: " + output);
        // If signals exist, should have signalId field
        if (output.contains("{")) {
            assertTrue(output.contains("\"signalId\""),
                    "JSON should contain signalId field: " + output);
            assertTrue(output.contains("\"severity\""),
                    "JSON should contain severity field: " + output);
        }
    }

    // =========================================================================
    // Tests: clean workspace
    // =========================================================================

    @Test
    void health_clean_workspace_shows_no_high_issues() throws Exception {
        setupWorkspace(tempDir);
        createCleanFiles(tempDir);

        String output = runCodeGraph(tempDir, "health", "--refresh");

        // A clean workspace should not have HIGH severity issues
        assertFalse(output.contains("[HIGH]"),
                "Clean workspace should not have HIGH severity issues: " + output);
    }
}
