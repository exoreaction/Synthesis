package io.exoreaction.synthesis.jira;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.cli.InitCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Jira-related enhancements to {@link InitCommand}.
 *
 * <p>Verifies that {@code --source jira} produces Jira configuration hints
 * in the generated config file — the Jira equivalent of
 * {@code InitCommandNotionTest}.
 */
class InitCommandJiraTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // 1. init_jiraSource_writesJiraHints
    // -----------------------------------------------------------------------

    @Test
    void init_jiraSource_writesJiraHints() throws Exception {
        Path workspace = tempDir.resolve("jira-workspace");
        Files.createDirectories(workspace);

        SynthesisApp app = new SynthesisApp();
        CommandLine cmd = new CommandLine(app);

        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            int exitCode = cmd.execute(
                    "-d", workspace.toAbsolutePath().toString(),
                    "init",
                    "--source", "jira",
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
        assertTrue(configContent.contains("jira:"),
                "Config should contain 'jira:' section. Actual config:\n" + configContent);
        assertTrue(configContent.contains("enabled: true"),
                "Config should have jira enabled. Actual config:\n" + configContent);
        assertTrue(configContent.contains("pollIntervalMinutes"),
                "Config should contain pollIntervalMinutes. Actual config:\n" + configContent);
    }

    // -----------------------------------------------------------------------
    // 2. init_defaultSource_noJiraHints
    // -----------------------------------------------------------------------

    @Test
    void init_defaultSource_noJiraHints() throws Exception {
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
        assertFalse(configContent.contains("jira:"),
                "Default init should not contain jira: section. Actual config:\n" + configContent);
    }
}
