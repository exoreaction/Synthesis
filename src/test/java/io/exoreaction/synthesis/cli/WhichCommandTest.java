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
 * Tests for {@link WhichCommand} -- #404: -d/--directory was declared but never read,
 * so `which` always called MultiWorkspaceSearch.discoverAllWorkspaces() (hardcoded
 * default roots) regardless of what -d was set to.
 */
class WhichCommandTest {

    @TempDir
    Path tempDir;

    private void setupWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
        Files.writeString(root.resolve(".synthesis/config.yaml"),
                "workspace:\n  name: \"which-test\"\n");
    }

    /**
     * Runs `which` sandboxed against a fake user.home so
     * MultiWorkspaceSearch.discoverAllWorkspaces() -- which reads
     * ~/.synthesis/config/workspace-discovery.yaml and defaults to
     * ${user.home}/Documents, ${user.home}/Downloads, ${user.home}/Pictures, /src,
     * ${user.home}/src -- can't pick up real workspaces on the machine running the test.
     */
    private String runWhich(Path fakeHome, String... args) {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        // AnsiOutput.printError() writes to System.err, not System.out
        // (AnsiOutput.java:102) -- capture both into one stream so assertions
        // see the full combined output a terminal user would.
        PrintStream capture = new PrintStream(baos);
        System.setOut(capture);
        System.setErr(capture);
        try {
            CommandLine cmd = new CommandLine(new SynthesisApp());
            SynthesisApp.installGroupedHelpRenderer(cmd);
            cmd.execute(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            System.setProperty("user.home", originalHome);
        }
        return baos.toString();
    }

    @Test
    void which_explicitDirectoryOutsideDefaultRoots_isScoped_notGenericNoWorkspacesError() throws Exception {
        // #404 repro shape: `synthesis which Foo.java -d <workspace>` -- -d comes AFTER
        // the subcommand, matching the issue's exact invocation.
        setupWorkspace(tempDir);
        Path fakeHome = Files.createTempDirectory("which-404-fakehome");

        String output = runWhich(fakeHome, "which", "Foo.java", "-d", tempDir.toString());

        assertFalse(output.contains("No Synthesis workspaces found"),
                "-d workspace exists and is valid; must not fall through to the "
                        + "default-roots-discovery error path. Output: " + output);
    }

    @Test
    void which_noDirectoryFlag_stillUsesDefaultDiscovery() throws Exception {
        // Baseline: unchanged behavior when -d is not passed at all.
        Path fakeHome = Files.createTempDirectory("which-404-fakehome-nodefault");

        String output = runWhich(fakeHome, "which", "Foo.java");

        assertTrue(output.contains("No Synthesis workspaces found"),
                "No -d and no default-root workspaces exist under the sandboxed "
                        + "home -- should still hit the no-workspaces error. Output: " + output);
    }

    @Test
    void which_explicitNonexistentDirectory_reportsWorkspaceValidationError() throws Exception {
        Path fakeHome = Files.createTempDirectory("which-404-fakehome-badpath");
        Path nonexistent = fakeHome.resolve("does-not-exist");

        String output = runWhich(fakeHome, "which", "Foo.java", "-d", nonexistent.toString());

        assertTrue(output.contains("Workspace directory does not exist"),
                "Explicit -d to a nonexistent path should surface WorkspaceManager's "
                        + "specific validation error, not the generic "
                        + "'No Synthesis workspaces found' message. Output: " + output);
        assertFalse(output.contains("No Synthesis workspaces found"),
                "Should not silently fall back to default-roots discovery. Output: " + output);
    }

    @Test
    void which_explicitDirectoryWithMatchingType_isScoped() throws Exception {
        Files.createDirectories(tempDir.resolve(".synthesis"));
        Files.writeString(tempDir.resolve(".synthesis/config.yaml"),
                "workspace:\n  name: \"which-test\"\n  type: source-code\n");
        Path fakeHome = Files.createTempDirectory("which-404-fakehome-type");

        String output = runWhich(fakeHome, "which", "Foo.java", "-d", tempDir.toString(),
                "--type", "source-code");

        assertFalse(output.contains("No Synthesis workspaces found"),
                "-d + --type together must still scope to the explicit workspace, "
                        + "not silently drop back to default-roots discovery. Output: " + output);
        assertFalse(output.contains("No workspaces found with type"),
                "Workspace type matches the filter; should not be excluded. Output: " + output);
    }
}
