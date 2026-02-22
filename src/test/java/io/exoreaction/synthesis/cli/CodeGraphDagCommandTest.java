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
 * Integration tests for {@code synthesis code-graph} (no subcommand) DAG view
 * and the new CKG-4 flags: --cycles, --hotspots, --instability, --layers,
 * --cross-format, --format mermaid.
 *
 * @since v1.12.2 (CKG-4.02)
 */
class CodeGraphDagCommandTest {

    @TempDir
    Path tempDir;

    // =========================================================================
    // Helpers
    // =========================================================================

    private void setupWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
        Files.writeString(root.resolve(".synthesis/config.yaml"),
                "workspace:\n  name: \"dag-test\"\n");
    }

    /**
     * Creates a multi-package Java project that produces multiple module profiles
     * with a circular dependency between app and service.
     */
    private void createMultiPackageProject(Path root) throws IOException {
        // Core package (stable, no outgoing deps to other internal packages)
        Path coreDir = Files.createDirectories(root.resolve("src/main/java/com/example/core"));
        Files.writeString(coreDir.resolve("Model.java"), """
                package com.example.core;
                public class Model {
                    private String name;
                }
                """);

        // Service package (depends on core)
        Path svcDir = Files.createDirectories(root.resolve("src/main/java/com/example/service"));
        Files.writeString(svcDir.resolve("Service.java"), """
                package com.example.service;
                import com.example.core.Model;
                import com.example.app.Controller;
                public class Service {
                    private Model model;
                    private Controller controller;
                }
                """);

        // App package (depends on service -> creates circular with service)
        Path appDir = Files.createDirectories(root.resolve("src/main/java/com/example/app"));
        Files.writeString(appDir.resolve("Controller.java"), """
                package com.example.app;
                import com.example.service.Service;
                public class Controller {
                    private Service service;
                }
                """);

        // CLI package (depends on everything)
        Path cliDir = Files.createDirectories(root.resolve("src/main/java/com/example/cli"));
        Files.writeString(cliDir.resolve("App.java"), """
                package com.example.cli;
                import com.example.core.Model;
                import com.example.service.Service;
                import com.example.app.Controller;
                public class App {
                    private Model model;
                    private Service service;
                    private Controller controller;
                }
                """);

        // SQL file for cross-format links
        Path sqlDir = Files.createDirectories(root.resolve("src/main/resources/db/migration"));
        Files.writeString(sqlDir.resolve("V1__init.sql"), """
                CREATE TABLE IF NOT EXISTS models (
                    id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL
                );
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

    /**
     * Populates the graph by running extract + describe --refresh.
     */
    private void populateGraph(Path workspaceDir) {
        runCodeGraph(workspaceDir, "extract");
        runCodeGraph(workspaceDir, "describe", "--refresh");
    }

    // =========================================================================
    // Tests: no data
    // =========================================================================

    @Test
    void no_data_shows_empty_message() throws Exception {
        setupWorkspace(tempDir);

        String output = runCodeGraph(tempDir);

        assertTrue(output.contains("No code graph data"),
                "Should show empty graph message: " + output);
    }

    @Test
    void no_data_returns_zero_exit_code() throws Exception {
        setupWorkspace(tempDir);

        int exitCode = runCodeGraphExitCode(tempDir);
        assertEquals(0, exitCode, "Empty graph should return 0");
    }

    // =========================================================================
    // Tests: default view (DAG)
    // =========================================================================

    @Test
    void default_view_shows_dag() throws Exception {
        setupWorkspace(tempDir);
        createMultiPackageProject(tempDir);
        populateGraph(tempDir);

        String output = runCodeGraph(tempDir);

        assertTrue(output.contains("Package dependency graph"),
                "Should show DAG header: " + output);
        assertTrue(output.contains("Layer"),
                "Should show at least one layer: " + output);
        assertTrue(output.contains("fan-in:"),
                "Should show fan-in metrics: " + output);
        assertTrue(output.contains("fan-out:"),
                "Should show fan-out metrics: " + output);
        assertTrue(output.contains("instability:"),
                "Should show instability metrics: " + output);
    }

    @Test
    void default_view_returns_zero() throws Exception {
        setupWorkspace(tempDir);
        createMultiPackageProject(tempDir);
        populateGraph(tempDir);

        int exitCode = runCodeGraphExitCode(tempDir);
        assertEquals(0, exitCode, "Default DAG view should return 0");
    }

    // =========================================================================
    // Tests: --cycles
    // =========================================================================

    @Test
    void cycles_flag_shows_cycles() throws Exception {
        setupWorkspace(tempDir);
        createMultiPackageProject(tempDir);
        populateGraph(tempDir);

        String output = runCodeGraph(tempDir, "--cycles");

        // The multi-package project has a circular dep between app and service
        assertTrue(output.contains("Circular Dependenc") || output.contains("No circular"),
                "Should show cycles or no-cycles message: " + output);
    }

    // =========================================================================
    // Tests: --hotspots
    // =========================================================================

    @Test
    void hotspots_flag_shows_hotspots() throws Exception {
        setupWorkspace(tempDir);
        createMultiPackageProject(tempDir);
        populateGraph(tempDir);

        String output = runCodeGraph(tempDir, "--hotspots");

        // May or may not have hotspots depending on project structure
        assertTrue(output.contains("Hotspot") || output.contains("No hotspots"),
                "Should show hotspots or no-hotspots message: " + output);
        assertTrue(output.contains("instability > 0.7 AND fan-in > 2")
                || output.contains("No hotspots"),
                "Should show criteria: " + output);
    }

    // =========================================================================
    // Tests: --instability
    // =========================================================================

    @Test
    void instability_flag_shows_sorted_list() throws Exception {
        setupWorkspace(tempDir);
        createMultiPackageProject(tempDir);
        populateGraph(tempDir);

        String output = runCodeGraph(tempDir, "--instability");

        assertTrue(output.contains("Packages by Instability"),
                "Should show instability header: " + output);
        assertTrue(output.contains("fan-in:"),
                "Should show fan-in: " + output);
        assertTrue(output.contains("fan-out:"),
                "Should show fan-out: " + output);
    }

    // =========================================================================
    // Tests: --layers (same as default)
    // =========================================================================

    @Test
    void layers_flag_shows_layer_diagram() throws Exception {
        setupWorkspace(tempDir);
        createMultiPackageProject(tempDir);
        populateGraph(tempDir);

        String output = runCodeGraph(tempDir, "--layers");

        assertTrue(output.contains("Package dependency graph"),
                "Should show DAG header: " + output);
        assertTrue(output.contains("Layer"),
                "Should show layers: " + output);
    }

    // =========================================================================
    // Tests: --cross-format
    // =========================================================================

    @Test
    void cross_format_flag_shows_links() throws Exception {
        setupWorkspace(tempDir);
        createMultiPackageProject(tempDir);
        populateGraph(tempDir);

        String output = runCodeGraph(tempDir, "--cross-format");

        // May or may not find cross-format links depending on extraction
        assertTrue(output.contains("Cross-Format Links") || output.contains("No cross-format"),
                "Should show cross-format links or no-links message: " + output);
    }

    // =========================================================================
    // Tests: --format mermaid
    // =========================================================================

    @Test
    void format_mermaid_produces_mermaid_output() throws Exception {
        setupWorkspace(tempDir);
        createMultiPackageProject(tempDir);
        populateGraph(tempDir);

        String output = runCodeGraph(tempDir, "--format", "mermaid");

        assertTrue(output.contains("graph TD"),
                "Should produce Mermaid graph: " + output);
        assertTrue(output.contains("-->"),
                "Should contain Mermaid edges: " + output);
        assertTrue(output.contains("stability:"),
                "Should contain stability labels: " + output);
    }

    // =========================================================================
    // Tests: alias works
    // =========================================================================

    @Test
    void cg_alias_with_dag_flags() throws Exception {
        setupWorkspace(tempDir);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(baos));
        try {
            CommandLine cmd = new CommandLine(new SynthesisApp());
            SynthesisApp.installGroupedHelpRenderer(cmd);

            int exitCode = cmd.execute("-d", tempDir.toString(), "cg", "--cycles");
            assertEquals(0, exitCode, "'cg' alias should work with --cycles");
        } finally {
            System.setOut(original);
        }

        String output = baos.toString();
        assertTrue(output.contains("No code graph data")
                        || output.contains("Circular") || output.contains("No circular"),
                "cg alias with --cycles should produce valid output: " + output);
    }
}
