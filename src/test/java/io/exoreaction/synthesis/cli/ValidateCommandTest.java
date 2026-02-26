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
 * Tests for {@link ValidateCommand} -- drift detection, gap analysis, integrity checks.
 *
 * <p>Focuses on #278: validate should always produce meaningful stdout output,
 * even when no skill/doc files exist in the workspace.
 */
class ValidateCommandTest {

    @TempDir
    Path tempDir;

    // ---- Helpers ----

    private void setupWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
        Files.writeString(root.resolve(".synthesis/config.yaml"),
                "workspace:\n  name: \"validate-test\"\n");
    }

    private String runValidate(Path workspaceDir, String... extraArgs) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(baos));
        try {
            CommandLine cmd = new CommandLine(new SynthesisApp());
            SynthesisApp.installGroupedHelpRenderer(cmd);

            String[] args = new String[extraArgs.length + 3];
            args[0] = "-d";
            args[1] = workspaceDir.toString();
            args[2] = "validate";
            System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);

            cmd.execute(args);
        } finally {
            System.setOut(original);
        }
        return baos.toString();
    }

    // ---- #278: meaningful stdout regardless of workspace content ----

    @Test
    void validate_emptyWorkspace_producesHeader() throws Exception {
        setupWorkspace(tempDir);

        String output = runValidate(tempDir);

        // Should contain a header identifying the command
        assertTrue(output.contains("Validate"),
                "Output should contain 'Validate' header: " + output);
    }

    @Test
    void validate_emptyWorkspace_producesWorkspacePath() throws Exception {
        setupWorkspace(tempDir);

        String output = runValidate(tempDir);

        // Should mention the workspace being validated
        assertTrue(output.contains(tempDir.getFileName().toString())
                        || output.contains("Workspace"),
                "Output should reference workspace: " + output);
    }

    @Test
    void validate_noSkillFiles_showsSummary() throws Exception {
        setupWorkspace(tempDir);

        String output = runValidate(tempDir);

        // Should still produce multi-line output with a summary,
        // not just the bare "No documentation files found" line
        String[] lines = output.split("\n");
        assertTrue(lines.length >= 3,
                "Output should have at least 3 lines (header + content + summary), got "
                        + lines.length + ": " + output);
    }

    @Test
    void validate_withSkillFiles_producesHeader() throws Exception {
        setupWorkspace(tempDir);
        // Create a skill file
        Path skillsDir = Files.createDirectories(tempDir.resolve(".claude/skills"));
        Files.writeString(skillsDir.resolve("test-skill.yaml"),
                "name: test-skill\ndescription: A test skill\n");

        String output = runValidate(tempDir, "--skills");

        assertTrue(output.contains("Validate"),
                "Output should contain header even with skill files: " + output);
    }
}
