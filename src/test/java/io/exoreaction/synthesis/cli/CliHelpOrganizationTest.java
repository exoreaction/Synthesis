package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the grouped command help renderer in {@link SynthesisApp}.
 */
class CliHelpOrganizationTest {

    private static picocli.CommandLine buildCmd() {
        picocli.CommandLine cmd = new picocli.CommandLine(new SynthesisApp());
        SynthesisApp.installGroupedHelpRenderer(cmd);
        return cmd;
    }

    @Test
    void helpContainsGroupHeaders() {
        picocli.CommandLine cmd = buildCmd();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        cmd.usage(new PrintStream(baos));
        String help = baos.toString();
        assertTrue(help.contains("Core:"), "help should contain Core: group");
        assertTrue(help.contains("Analysis:"), "help should contain Analysis: group");
    }

    @Test
    void coreCommandsAppearBeforeStagingInHelp() {
        picocli.CommandLine cmd = buildCmd();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        cmd.usage(new PrintStream(baos));
        String help = baos.toString();
        int searchPos = help.indexOf("search");
        int stagingPos = help.indexOf("staging");
        assertTrue(searchPos < stagingPos, "search should appear before staging");
        assertTrue(searchPos > 0, "search should be in help");
    }

    @Test
    void allExistingCommandsStillReachable() {
        picocli.CommandLine cmd = buildCmd();
        // These commands must still be present after grouping
        for (String name : List.of("search", "maintain", "health", "staging", "sweep", "prune", "ttl", "sync")) {
            assertTrue(cmd.getSubcommands().containsKey(name), name + " should still be registered");
        }
    }
}
