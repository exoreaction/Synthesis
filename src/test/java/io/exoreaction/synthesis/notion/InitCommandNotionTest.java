package io.exoreaction.synthesis.notion;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.cli.InitCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Notion-related enhancements to {@link InitCommand}.
 *
 * <p>Verifies that {@code --source notion} produces Notion configuration hints
 * in the generated config file, and that the default source does not.
 */
class InitCommandNotionTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // 1. init_notionSource_writesNotionHints
    // -----------------------------------------------------------------------

    @Test
    void init_notionSource_writesNotionHints() throws Exception {
        Path workspace = tempDir.resolve("notion-workspace");
        Files.createDirectories(workspace);

        // Run init with --source notion --no-interactive --skip-org-scan
        SynthesisApp app = new SynthesisApp();
        CommandLine cmd = new CommandLine(app);

        // Suppress stdout
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            int exitCode = cmd.execute(
                    "-d", workspace.toAbsolutePath().toString(),
                    "init",
                    "--source", "notion",
                    "--no-interactive",
                    "--skip-org-scan"
            );

            assertEquals(0, exitCode, "Init should succeed");
        } finally {
            System.setOut(originalOut);
        }

        // Check that the config file contains Notion hints
        Path configFile = workspace.resolve(".synthesis/config.yaml");
        assertTrue(Files.exists(configFile), "Config file should exist at " + configFile);

        String configContent = Files.readString(configFile);
        assertTrue(configContent.contains("notion:"),
                "Config should contain 'notion:' section. Actual config:\n" + configContent);
        assertTrue(configContent.contains("enabled: true"),
                "Config should have notion enabled. Actual config:\n" + configContent);
        assertTrue(configContent.contains("pollIntervalMinutes"),
                "Config should contain pollIntervalMinutes. Actual config:\n" + configContent);
    }

    // -----------------------------------------------------------------------
    // 2. init_defaultSource_noNotionHints
    // -----------------------------------------------------------------------

    @Test
    void init_defaultSource_noNotionHints() throws Exception {
        Path workspace = tempDir.resolve("fs-workspace");
        Files.createDirectories(workspace);

        // Run init without --source (defaults to filesystem)
        SynthesisApp app = new SynthesisApp();
        CommandLine cmd = new CommandLine(app);

        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            int exitCode = cmd.execute(
                    "-d", workspace.toAbsolutePath().toString(),
                    "init",
                    "--no-interactive",
                    "--skip-org-scan"
            );

            assertEquals(0, exitCode, "Init should succeed");
        } finally {
            System.setOut(originalOut);
        }

        // Check that the config does NOT contain Notion-specific config
        Path configFile = workspace.resolve(".synthesis/config.yaml");
        assertTrue(Files.exists(configFile), "Config file should exist at " + configFile);

        String configContent = Files.readString(configFile);
        // The default config has ai: section but no notion: section
        assertFalse(configContent.contains("notion:"),
                "Default init should not contain notion: section. Actual config:\n" + configContent);
    }
}
