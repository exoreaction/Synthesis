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

    // ---- #480: a pass over the wrong scope must not read as a pass ----
    //
    // `synthesis validate --skills` run from any repo that is not the configured
    // workspace validated ~/Documents instead, found no .claude/skills there, checked a
    // single CLAUDE.md and printed "Result: OK" -- while the repo the user was standing
    // in had 38 skills and 15 with drift. The precedence (-d > env > ~/.synthesis/
    // workspace > cwd) is defensible; doing it silently is not.

    @Test
    void scopeWarning_defaultedRootAwayFromCwd_warns() {
        Path root = Path.of("/home/u/Documents");
        Path cwd = Path.of("/src/org/some-repo");

        var warning = ValidateCommand.scopeWarning(root, cwd, false);

        assertTrue(warning.isPresent(), "a defaulted root outside cwd must be announced");
        assertTrue(warning.get().contains("some-repo"),
                "warning should name where the user actually is: " + warning.get());
    }

    @Test
    void scopeWarning_explicitRoot_staysQuiet() {
        // The user passed -d; they know what they asked for.
        var warning = ValidateCommand.scopeWarning(
                Path.of("/home/u/Documents"), Path.of("/src/org/some-repo"), true);

        assertTrue(warning.isEmpty(), "an explicit -d needs no warning: " + warning);
    }

    @Test
    void scopeWarning_cwdInsideRoot_staysQuiet() {
        var warning = ValidateCommand.scopeWarning(
                Path.of("/home/u/Documents"), Path.of("/home/u/Documents/sub/dir"), false);

        assertTrue(warning.isEmpty(), "cwd inside the workspace is the normal case: " + warning);
    }

    // ---- #481: bounded enumeration whose bound is invisible ----
    //
    // collectSkillFiles walked with maxDepth 1, so a skill one level down was never
    // checked and its absence was never reported. ~/.claude/skills/common/ holds 19
    // such skills. Same class as #340 (subdirectory SKILL.md ignored), #320 (flat .yaml
    // skipped), and the workspace-discovery maxDepth that hid 21 workspaces from
    // `synthesis list`.

    @Test
    void validate_skillInSubdirectory_isChecked() throws Exception {
        setupWorkspace(tempDir);
        Path nested = Files.createDirectories(tempDir.resolve(".claude/skills/common"));
        Files.writeString(nested.resolve("nested-skill.yaml"),
                "name: nested-skill\ndescription: One level down\n");

        String output = runValidate(tempDir, "--skills");

        assertTrue(output.contains("nested-skill"),
                "a skill in a subdirectory must be checked, not silently skipped: " + output);
    }

    @Test
    void validate_skillAsDirectoryWithSkillMd_isChecked() throws Exception {
        setupWorkspace(tempDir);
        // The other supported layout: <name>/SKILL.md (#340).
        Path dir = Files.createDirectories(tempDir.resolve(".claude/skills/rotate-key"));
        Files.writeString(dir.resolve("SKILL.md"), "# Rotate key\n\nSteps here.\n");

        String output = runValidate(tempDir, "--skills");

        assertTrue(output.contains("SKILL.md") || output.contains("rotate-key"),
                "the <name>/SKILL.md layout must be checked too: " + output);
    }

    @Test
    void validate_skillsRequestedButNoSkillsDir_saysSo() throws Exception {
        setupWorkspace(tempDir);
        // A CLAUDE.md but no .claude/skills -- the exact shape that produced a green
        // "Checked 1 files ... Result: OK" over a library it never saw.
        Files.writeString(tempDir.resolve("CLAUDE.md"), "# Project\n\nSome context.\n");

        String output = runValidate(tempDir, "--skills");

        assertTrue(output.contains("no .claude/skills") || output.contains("No skills directory"),
                "must state that the skills directory was absent, not just report OK: " + output);
    }
}
