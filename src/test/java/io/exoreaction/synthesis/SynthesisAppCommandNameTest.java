package io.exoreaction.synthesis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link SynthesisApp#extractCommandName(String[])} —
 * the helper that extracts the subcommand name from raw CLI args,
 * skipping top-level flags like {@code -d} and {@code --directory}.
 */
class SynthesisAppCommandNameTest {

    @Test
    void simple_command_returns_command() {
        assertEquals("changelog", SynthesisApp.extractCommandName(new String[]{"changelog"}));
    }

    @Test
    void d_flag_before_command_returns_command() {
        assertEquals("changelog",
                SynthesisApp.extractCommandName(new String[]{"-d", "/workspace", "changelog"}));
    }

    @Test
    void directory_flag_before_command_returns_command() {
        assertEquals("changelog",
                SynthesisApp.extractCommandName(new String[]{"--directory", "/workspace", "changelog"}));
    }

    @Test
    void other_flag_before_command_returns_command() {
        assertEquals("scan",
                SynthesisApp.extractCommandName(new String[]{"--version", "scan"}));
    }

    @Test
    void d_flag_with_subcommand_arg_returns_subcommand() {
        // synthesis -d /home/totto/Documents changelog --format mermaid
        assertEquals("changelog",
                SynthesisApp.extractCommandName(
                        new String[]{"-d", "/home/totto/Documents", "changelog", "--format", "mermaid"}));
    }

    @Test
    void empty_args_returns_help() {
        assertEquals("help", SynthesisApp.extractCommandName(new String[]{}));
    }

    @Test
    void only_flags_returns_help() {
        assertEquals("help", SynthesisApp.extractCommandName(new String[]{"--version"}));
    }

    @Test
    void scan_command_unaffected() {
        assertEquals("scan", SynthesisApp.extractCommandName(new String[]{"scan"}));
    }

    @Test
    void d_flag_at_end_with_no_command_returns_help() {
        assertEquals("help", SynthesisApp.extractCommandName(new String[]{"-d", "/workspace"}));
    }
}
