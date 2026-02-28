package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HooksCommand} and its {@code generate} subcommand.
 */
class HooksCommandTest {

    @TempDir
    Path tempDir;

    // ---- mergeHookEntry tests ----

    @Test
    void dryRunOutputsValidJsonWithCorrectStructure() throws Exception {
        // Empty existing file -> should produce valid JSON
        String result = HooksCommand.GenerateSubcommand.mergeHookEntry(
                "", "UserPromptSubmit", "synthesis session-context --compact");

        assertNotNull(result, "Result should not be null for empty input");
        assertTrue(result.contains("\"hooks\""), "Should contain hooks key");
        assertTrue(result.contains("\"UserPromptSubmit\""), "Should contain hook type");
        assertTrue(result.contains("\"synthesis session-context --compact\""), "Should contain command");
        assertTrue(result.contains("\"matcher\""), "Should contain matcher field");
        assertTrue(result.contains("\"type\": \"command\""), "Should contain type field");

        // Verify it starts and ends like valid JSON
        String trimmed = result.trim();
        assertTrue(trimmed.startsWith("{"), "Should start with {");
        assertTrue(trimmed.endsWith("}"), "Should end with }");
    }

    @Test
    void emptyOrMissingSettingsCreatesCorrectStructure() throws Exception {
        // null input
        String resultNull = HooksCommand.GenerateSubcommand.mergeHookEntry(
                null, "UserPromptSubmit", "synthesis session-context --compact");
        assertNotNull(resultNull);
        assertTrue(resultNull.contains("\"hooks\""));
        assertTrue(resultNull.contains("\"UserPromptSubmit\""));

        // empty string input
        String resultEmpty = HooksCommand.GenerateSubcommand.mergeHookEntry(
                "", "UserPromptSubmit", "synthesis session-context --compact");
        assertNotNull(resultEmpty);
        assertTrue(resultEmpty.contains("\"hooks\""));

        // blank string input
        String resultBlank = HooksCommand.GenerateSubcommand.mergeHookEntry(
                "   ", "UserPromptSubmit", "synthesis session-context --compact");
        assertNotNull(resultBlank);
        assertTrue(resultBlank.contains("\"hooks\""));
    }

    @Test
    void runningTwiceIsIdempotent() throws Exception {
        String command = "synthesis session-context --compact";

        // First merge
        String first = HooksCommand.GenerateSubcommand.mergeHookEntry(
                "", "UserPromptSubmit", command);
        assertNotNull(first, "First merge should produce output");

        // Second merge with the first result -> should be idempotent (return null)
        String second = HooksCommand.GenerateSubcommand.mergeHookEntry(
                first, "UserPromptSubmit", command);
        assertNull(second, "Second merge should return null (hook already exists)");
    }

    @Test
    void malformedJsonAbortsWithError() {
        // Missing closing brace
        assertThrows(HooksCommand.GenerateSubcommand.MalformedJsonException.class, () ->
                HooksCommand.GenerateSubcommand.mergeHookEntry(
                        "{ \"key\": \"value\"",
                        "UserPromptSubmit",
                        "synthesis session-context --compact")
        );

        // Not starting with brace
        assertThrows(HooksCommand.GenerateSubcommand.MalformedJsonException.class, () ->
                HooksCommand.GenerateSubcommand.mergeHookEntry(
                        "not json at all",
                        "UserPromptSubmit",
                        "synthesis session-context --compact")
        );
    }

    @Test
    void mergeIntoExistingJsonPreservesOtherKeys() throws Exception {
        String existing = "{\n  \"permissions\": { \"allow\": [\"read\"] }\n}";
        String result = HooksCommand.GenerateSubcommand.mergeHookEntry(
                existing, "UserPromptSubmit", "synthesis session-context --compact");

        assertNotNull(result);
        assertTrue(result.contains("\"permissions\""), "Should preserve existing permissions key");
        assertTrue(result.contains("\"hooks\""), "Should add hooks key");
        assertTrue(result.contains("\"UserPromptSubmit\""), "Should add hook type");
    }

    @Test
    void preToolUseTypeIsAccepted() throws Exception {
        String result = HooksCommand.GenerateSubcommand.mergeHookEntry(
                "", "PreToolUse", "synthesis session-context --compact");

        assertNotNull(result);
        assertTrue(result.contains("\"PreToolUse\""), "Should contain PreToolUse hook type");
    }

    @Test
    void generateWritesToFileOnDisk() throws Exception {
        Path settingsFile = tempDir.resolve("settings.json");

        HooksCommand.GenerateSubcommand gen = new HooksCommand.GenerateSubcommand();
        HooksCommand parent = new HooksCommand();
        gen.setParent(parent);
        gen.setOutput(settingsFile);
        gen.setDryRun(false);
        gen.setHookType("UserPromptSubmit");

        // Redirect stdout to suppress output
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        try {
            int exitCode = gen.call();
            assertEquals(0, exitCode, "Should succeed");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertTrue(Files.exists(settingsFile), "Settings file should be created");
        String content = Files.readString(settingsFile);
        assertTrue(content.contains("\"hooks\""), "File should contain hooks");
        assertTrue(content.contains("\"UserPromptSubmit\""), "File should contain hook type");
    }

    @Test
    void dryRunDoesNotWriteFile() throws Exception {
        Path settingsFile = tempDir.resolve("settings-dryrun.json");

        HooksCommand.GenerateSubcommand gen = new HooksCommand.GenerateSubcommand();
        HooksCommand parent = new HooksCommand();
        gen.setParent(parent);
        gen.setOutput(settingsFile);
        gen.setDryRun(true);
        gen.setHookType("UserPromptSubmit");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            int exitCode = gen.call();
            assertEquals(0, exitCode, "Should succeed");
        } finally {
            System.setOut(originalOut);
        }

        assertFalse(Files.exists(settingsFile), "Settings file should not be created in dry-run mode");
        String output = baos.toString();
        assertTrue(output.contains("\"hooks\""), "Dry-run should print JSON to stdout");
    }
}
