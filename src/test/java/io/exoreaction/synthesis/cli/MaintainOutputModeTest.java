package io.exoreaction.synthesis.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
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
 * Tests for {@code --quiet} and {@code --json} output modes of {@link MaintainCommand}.
 *
 * <p>These tests run the full picocli command stack to verify that output formatting
 * is correct when the relevant flags are active.
 *
 * @since v1.9.9 (issue #191)
 */
class MaintainOutputModeTest {

    @TempDir
    Path tempDir;

    // =========================================================================
    // Helpers
    // =========================================================================

    private void setupMinimalWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
        Files.writeString(root.resolve(".synthesis/config.yaml"),
                "workspace:\n  name: \"output-mode-test\"\n");
    }

    /**
     * Runs {@code synthesis maintain <flags>} in the given directory,
     * captures stdout, and returns the captured output.
     */
    private String runMaintain(Path workspaceDir, String... extraArgs) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(baos));
        try {
            CommandLine cmd = new CommandLine(new SynthesisApp());
            SynthesisApp.installGroupedHelpRenderer(cmd);

            // Build args: -d <dir> maintain [extraArgs...]
            // The workspace root is set on SynthesisApp via -d/--directory flag
            String[] args = new String[extraArgs.length + 3];
            args[0] = "-d";
            args[1] = workspaceDir.toString();
            args[2] = "maintain";
            System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);

            cmd.execute(args);
        } finally {
            System.setOut(original);
        }
        return baos.toString();
    }

    // =========================================================================
    // MaintainOptions.quiet() factory
    // =========================================================================

    @Test
    void maintainOptions_quiet_factory_sets_quiet_true() {
        MaintainOptions opts = MaintainOptions.quietMode();
        assertTrue(opts.quiet(), "quiet() factory should set quiet=true");
        assertFalse(opts.dryRun());
        assertFalse(opts.json());
        assertFalse(opts.verbose());
    }

    // =========================================================================
    // --quiet mode: single line output
    // =========================================================================

    @Test
    void quiet_mode_outputs_exactly_one_non_blank_line() throws Exception {
        setupMinimalWorkspace(tempDir);
        String output = runMaintain(tempDir, "--quiet");

        long lineCount = output.lines()
                .filter(l -> !l.isBlank())
                .count();
        assertEquals(1, lineCount,
                "quiet mode should output exactly 1 non-blank line, got:\n" + output);
    }

    @Test
    void quiet_line_contains_ok_status() throws Exception {
        setupMinimalWorkspace(tempDir);
        String output = runMaintain(tempDir, "--quiet");

        String line = output.lines()
                .filter(l -> !l.isBlank())
                .findFirst()
                .orElse("");
        assertTrue(line.contains("OK"),
                "quiet line should contain OK for a successful run: " + line);
    }

    @Test
    void quiet_line_contains_phases_count() throws Exception {
        setupMinimalWorkspace(tempDir);
        String output = runMaintain(tempDir, "--quiet");

        String line = output.lines()
                .filter(l -> !l.isBlank())
                .findFirst()
                .orElse("");
        assertTrue(line.contains("9 phases"),
                "quiet line should contain '9 phases': " + line);
    }

    @Test
    void quiet_line_contains_health_placeholder() throws Exception {
        setupMinimalWorkspace(tempDir);
        String output = runMaintain(tempDir, "--quiet");

        String line = output.lines()
                .filter(l -> !l.isBlank())
                .findFirst()
                .orElse("");
        assertTrue(line.contains("health=-1"),
                "quiet line should contain 'health=-1': " + line);
    }

    @Test
    void quiet_mode_does_not_contain_ansi_codes() throws Exception {
        setupMinimalWorkspace(tempDir);
        String output = runMaintain(tempDir, "--quiet");

        assertFalse(output.contains("\u001B["),
                "quiet mode output should not contain ANSI escape codes");
    }

    // =========================================================================
    // --json mode: valid JSON output
    // =========================================================================

    @Test
    void json_mode_produces_valid_json() throws Exception {
        setupMinimalWorkspace(tempDir);
        String output = runMaintain(tempDir, "--json");

        // Find the JSON line (first non-blank line)
        String jsonLine = output.lines()
                .filter(l -> !l.isBlank())
                .findFirst()
                .orElse("");
        assertFalse(jsonLine.isEmpty(), "JSON mode should produce output");

        // Parse as JSON — should not throw
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = assertDoesNotThrow(
                () -> mapper.readTree(jsonLine),
                "JSON mode output must be valid JSON: " + jsonLine);
        assertNotNull(root, "Parsed JSON should not be null");
    }

    @Test
    void json_has_status_field() throws Exception {
        setupMinimalWorkspace(tempDir);
        String output = runMaintain(tempDir, "--json");

        String jsonLine = output.lines().filter(l -> !l.isBlank()).findFirst().orElse("{}");
        JsonNode root = new ObjectMapper().readTree(jsonLine);
        assertTrue(root.has("status"), "JSON must have 'status' field");
        assertTrue(root.get("status").asText().equals("OK") ||
                   root.get("status").asText().equals("ERROR"),
                "status must be OK or ERROR: " + root.get("status").asText());
    }

    @Test
    void json_has_all_9_phases() throws Exception {
        setupMinimalWorkspace(tempDir);
        String output = runMaintain(tempDir, "--json");

        String jsonLine = output.lines().filter(l -> !l.isBlank()).findFirst().orElse("{}");
        JsonNode root = new ObjectMapper().readTree(jsonLine);

        assertTrue(root.has("phases"), "JSON must have 'phases' array");
        assertEquals(9, root.get("phases").size(),
                "phases array must have exactly 9 elements");
    }

    @Test
    void json_phases_have_name_and_status() throws Exception {
        setupMinimalWorkspace(tempDir);
        String output = runMaintain(tempDir, "--json");

        String jsonLine = output.lines().filter(l -> !l.isBlank()).findFirst().orElse("{}");
        JsonNode root = new ObjectMapper().readTree(jsonLine);
        JsonNode phases = root.get("phases");

        for (JsonNode phase : phases) {
            assertTrue(phase.has("name"),
                    "Each phase must have 'name' field");
            assertTrue(phase.has("status"),
                    "Each phase must have 'status' field");
            assertTrue(phase.has("changes"),
                    "Each phase must have 'changes' field");
        }
    }

    @Test
    void json_has_timestamp_and_duration() throws Exception {
        setupMinimalWorkspace(tempDir);
        String output = runMaintain(tempDir, "--json");

        String jsonLine = output.lines().filter(l -> !l.isBlank()).findFirst().orElse("{}");
        JsonNode root = new ObjectMapper().readTree(jsonLine);

        assertTrue(root.has("timestamp"), "JSON must have 'timestamp' field");
        assertTrue(root.has("durationMs"), "JSON must have 'durationMs' field");
        assertTrue(root.get("durationMs").asLong() >= 0,
                "durationMs should be non-negative");
    }

    @Test
    void json_phases_contain_expected_names() throws Exception {
        setupMinimalWorkspace(tempDir);
        String output = runMaintain(tempDir, "--json");

        String jsonLine = output.lines().filter(l -> !l.isBlank()).findFirst().orElse("{}");
        JsonNode root = new ObjectMapper().readTree(jsonLine);
        JsonNode phases = root.get("phases");

        java.util.List<String> names = new java.util.ArrayList<>();
        for (JsonNode phase : phases) {
            names.add(phase.get("name").asText());
        }

        assertTrue(names.contains("Ingest"), "phases should include Ingest: " + names);
        assertTrue(names.contains("Route"),   "phases should include Route: " + names);
        assertTrue(names.contains("Sweep"),   "phases should include Sweep: " + names);
        assertTrue(names.contains("Expire"),  "phases should include Expire: " + names);
        assertTrue(names.contains("Index"),   "phases should include Index: " + names);
        assertTrue(names.contains("Prune"),   "phases should include Prune: " + names);
    }

    // =========================================================================
    // --dry-run footer
    // =========================================================================

    @Test
    void dry_run_output_contains_footer_message() throws Exception {
        setupMinimalWorkspace(tempDir);
        String output = runMaintain(tempDir, "--dry-run");

        assertTrue(output.contains("No changes made. Remove --dry-run to apply."),
                "dry-run output must contain the footer message, got:\n" + output);
    }

    @Test
    void dry_run_output_contains_preview_labels() throws Exception {
        setupMinimalWorkspace(tempDir);
        String output = runMaintain(tempDir, "--dry-run");

        assertTrue(output.contains("preview"),
                "dry-run output should contain 'preview' labels: " + output);
    }
}
