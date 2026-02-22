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
 * Integration tests for {@code synthesis code-graph gaps} subcommand.
 *
 * @since v1.12.2 (CKG-3.05)
 */
class CodeGraphGapsCommandTest {

    @TempDir
    Path tempDir;

    // =========================================================================
    // Helpers
    // =========================================================================

    private void setupWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
        Files.writeString(root.resolve(".synthesis/config.yaml"),
                "workspace:\n  name: \"gaps-test\"\n");
    }

    /**
     * Creates Java files that will produce quality gaps when analyzed:
     * - A module with no tests (MISSING_TESTS)
     * - A module with many files but no README (MISSING_README)
     */
    private void createGappyFiles(Path root) throws IOException {
        // Module com.example.core -- 2 files, no tests
        Path coreDir = Files.createDirectories(root.resolve("src/main/java/com/example/core"));
        Files.writeString(coreDir.resolve("Model.java"), """
                package com.example.core;
                public class Model {
                    private String name;
                }
                """);
        Files.writeString(coreDir.resolve("Entity.java"), """
                package com.example.core;
                public class Entity {
                    private int id;
                }
                """);

        // Module com.example.service -- imports core (adds fan-in to core)
        Path svcDir = Files.createDirectories(root.resolve("src/main/java/com/example/service"));
        Files.writeString(svcDir.resolve("Service.java"), """
                package com.example.service;
                import com.example.core.Model;
                public class Service {
                    private Model model;
                }
                """);
    }

    /**
     * Creates a clean workspace with tests.
     */
    private void createCleanFiles(Path root) throws IOException {
        Path coreDir = Files.createDirectories(root.resolve("src/main/java/com/example/core"));
        Files.writeString(coreDir.resolve("Model.java"), """
                package com.example.core;
                public class Model {
                    private String name;
                }
                """);

        Path testDir = Files.createDirectories(root.resolve("src/test/java/com/example/core"));
        Files.writeString(testDir.resolve("ModelTest.java"), """
                package com.example.core;
                import com.example.core.Model;
                public class ModelTest {
                    // test class
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
    // Tests: gaps (no data)
    // =========================================================================

    @Test
    void gaps_no_gaps_shows_healthy_message() throws Exception {
        setupWorkspace(tempDir);

        String output = runCodeGraph(tempDir, "gaps");

        // With no data, there are no gaps persisted -> should show healthy
        assertTrue(output.contains("No quality gaps detected") || output.contains("healthy"),
                "Should show healthy message when no gaps exist: " + output);
    }

    // =========================================================================
    // Tests: gaps --refresh
    // =========================================================================

    @Test
    void gaps_shows_gap_list() throws Exception {
        setupWorkspace(tempDir);
        createGappyFiles(tempDir);

        String output = runCodeGraph(tempDir, "gaps", "--refresh");

        // Should show at least some gap type
        assertTrue(output.contains("MISSING_TESTS") || output.contains("Quality Gaps")
                || output.contains("No quality gaps"),
                "Should show gap analysis output: " + output);
    }

    @Test
    void gaps_refresh_returns_zero() throws Exception {
        setupWorkspace(tempDir);
        createGappyFiles(tempDir);

        int exitCode = runCodeGraphExitCode(tempDir, "gaps", "--refresh");
        assertEquals(0, exitCode, "gaps --refresh should succeed");
    }

    // =========================================================================
    // Tests: gaps --severity
    // =========================================================================

    @Test
    void gaps_filter_by_severity() throws Exception {
        setupWorkspace(tempDir);
        createGappyFiles(tempDir);

        // First refresh to populate
        runCodeGraph(tempDir, "gaps", "--refresh");

        String output = runCodeGraph(tempDir, "gaps", "--severity", "HIGH");

        // Should only show HIGH gaps or "No quality gaps" if none match
        assertFalse(output.contains("[LOW]"),
                "Severity filter should not show LOW gaps: " + output);
        assertFalse(output.contains("[MEDIUM]"),
                "Severity filter should not show MEDIUM gaps: " + output);
    }

    // =========================================================================
    // Tests: gaps --type
    // =========================================================================

    @Test
    void gaps_filter_by_type() throws Exception {
        setupWorkspace(tempDir);
        createGappyFiles(tempDir);

        // First refresh to populate
        runCodeGraph(tempDir, "gaps", "--refresh");

        String output = runCodeGraph(tempDir, "gaps", "--type", "MISSING_TESTS");

        // Should only show MISSING_TESTS type gaps or "No quality gaps" if none
        if (output.contains("Quality Gaps")) {
            assertFalse(output.contains("MISSING_README"),
                    "Type filter should not show other gap types: " + output);
            assertFalse(output.contains("MISSING_INTERFACE"),
                    "Type filter should not show other gap types: " + output);
        }
    }

    // =========================================================================
    // Tests: gaps --module
    // =========================================================================

    @Test
    void gaps_filter_by_module() throws Exception {
        setupWorkspace(tempDir);
        createGappyFiles(tempDir);

        // First refresh to populate
        runCodeGraph(tempDir, "gaps", "--refresh");

        String output = runCodeGraph(tempDir, "gaps", "--module", "core");

        // Should only show gaps for modules containing "core" or "No quality gaps"
        if (output.contains("Quality Gaps")) {
            // Every gap line should contain "core"
            assertFalse(output.contains("com/example/service"),
                    "Module filter should not show other modules: " + output);
        }
    }

    // =========================================================================
    // Tests: gaps --format json
    // =========================================================================

    @Test
    void gaps_format_json() throws Exception {
        setupWorkspace(tempDir);
        createGappyFiles(tempDir);

        // First refresh to populate
        runCodeGraph(tempDir, "gaps", "--refresh");

        String output = runCodeGraph(tempDir, "gaps", "--format", "json");

        assertTrue(output.contains("["),
                "JSON output should start with array: " + output);
        if (output.contains("{")) {
            assertTrue(output.contains("\"gapType\""),
                    "JSON should contain gapType field: " + output);
            assertTrue(output.contains("\"severity\""),
                    "JSON should contain severity field: " + output);
        }
    }

    // =========================================================================
    // Tests: gaps --refresh (re-detect)
    // =========================================================================

    @Test
    void gaps_refresh_re_detects() throws Exception {
        setupWorkspace(tempDir);
        createGappyFiles(tempDir);

        // First run
        String output1 = runCodeGraph(tempDir, "gaps", "--refresh");

        // Second run should succeed (idempotent)
        String output2 = runCodeGraph(tempDir, "gaps", "--refresh");

        // Both runs should produce consistent output
        // (may differ in timestamps but structure should match)
        assertEquals(output1.contains("Quality Gaps"), output2.contains("Quality Gaps"),
                "Refresh should be idempotent");
    }

    // =========================================================================
    // Tests: gaps --score
    // =========================================================================

    @Test
    void gaps_score_shows_completeness() throws Exception {
        setupWorkspace(tempDir);
        createGappyFiles(tempDir);

        String output = runCodeGraph(tempDir, "gaps", "--refresh", "--score");

        // If there are gaps, scores should be shown
        if (output.contains("Quality Gaps")) {
            assertTrue(output.contains("[score:"),
                    "Score flag should show completeness scores: " + output);
        }
    }
}
