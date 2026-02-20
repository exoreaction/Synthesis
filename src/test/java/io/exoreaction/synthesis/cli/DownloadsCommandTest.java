package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DownloadsCommand} — alias for staging commands.
 */
class DownloadsCommandTest {

    private static picocli.CommandLine buildCmd() {
        picocli.CommandLine cmd = new picocli.CommandLine(new SynthesisApp());
        SynthesisApp.installGroupedHelpRenderer(cmd);
        return cmd;
    }

    @Test
    void downloads_appearsInHelp() {
        picocli.CommandLine cmd = buildCmd();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        cmd.usage(new PrintStream(baos));
        String help = baos.toString();
        assertTrue(help.contains("downloads"), "downloads should appear in help output");
    }

    @Test
    void staging_stillWorksAfterAlias() {
        picocli.CommandLine cmd = buildCmd();
        assertTrue(cmd.getSubcommands().containsKey("staging"), "staging command should still exist");
        assertTrue(cmd.getSubcommands().containsKey("downloads"), "downloads alias should exist");
    }

    @Test
    void downloads_hasRequiredSubcommands() {
        picocli.CommandLine cmd = buildCmd();
        picocli.CommandLine downloads = cmd.getSubcommands().get("downloads");
        assertNotNull(downloads, "downloads command should be registered");
        assertTrue(downloads.getSubcommands().containsKey("list"), "downloads list should exist");
        assertTrue(downloads.getSubcommands().containsKey("route"), "downloads route should exist");
        assertTrue(downloads.getSubcommands().containsKey("ingest"), "downloads ingest should exist");
    }
}
