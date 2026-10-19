package io.exoreaction.synthesis.confluence;

import io.exoreaction.synthesis.SynthesisApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Confluence-related enhancements to {@code InitCommand}.
 * Mirrors {@code InitCommandNotionTest}.
 */
class InitCommandConfluenceTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // 1. init_confluenceSource_writesConfluenceHints
    // -----------------------------------------------------------------------

    @Test
    void init_confluenceSource_writesConfluenceHints() throws Exception {
        Path workspace = tempDir.resolve("confluence-workspace");
        Files.createDirectories(workspace);

        SynthesisApp app = new SynthesisApp();
        CommandLine cmd = new CommandLine(app);

        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            int exitCode = cmd.execute(
                    "-d", workspace.toAbsolutePath().toString(),
                    "init",
                    "--source", "confluence",
                    "--no-interactive",
                    "--skip-org-scan"
            );

            assertEquals(0, exitCode, "Init should succeed");
        } finally {
            System.setOut(originalOut);
        }

        Path configFile = workspace.resolve(".synthesis/config.yaml");
        assertTrue(Files.exists(configFile), "Config file should exist at " + configFile);

        String configContent = Files.readString(configFile);
        assertTrue(configContent.contains("confluence:"),
                "Config should contain 'confluence:' section. Actual config:\n" + configContent);
        assertTrue(configContent.contains("enabled: true"),
                "Config should have confluence enabled. Actual config:\n" + configContent);
        assertTrue(configContent.contains("spaceKeys"),
                "Config should contain spaceKeys. Actual config:\n" + configContent);
        assertTrue(configContent.contains("pollIntervalMinutes"),
                "Config should contain pollIntervalMinutes. Actual config:\n" + configContent);
    }

    // -----------------------------------------------------------------------
    // 2. init_defaultSource_noConfluenceHints
    // -----------------------------------------------------------------------

    @Test
    void init_defaultSource_noConfluenceHints() throws Exception {
        Path workspace = tempDir.resolve("fs-workspace");
        Files.createDirectories(workspace);

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

        Path configFile = workspace.resolve(".synthesis/config.yaml");
        assertTrue(Files.exists(configFile), "Config file should exist at " + configFile);

        String configContent = Files.readString(configFile);
        assertFalse(configContent.contains("confluence:"),
                "Default init should not contain confluence: section. Actual config:\n" + configContent);
    }
}
