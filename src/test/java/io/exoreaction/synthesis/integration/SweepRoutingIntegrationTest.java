package io.exoreaction.synthesis.integration;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.cli.SweepCommand;
import io.exoreaction.synthesis.integration.WorkspaceIsolationExtension.IsolatedWorkspaceTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.exoreaction.synthesis.integration.WorkspaceFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the sweep routing pipeline using {@link WorkspaceFixture}.
 *
 * <p>Tests run entirely inside a {@code @TempDir}; no real workspace is touched.
 *
 * @since v1.9.9 (issue #184)
 */
@IsolatedWorkspaceTest
class SweepRoutingIntegrationTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Config rule: routes matching file to named destination
    // -------------------------------------------------------------------------

    @Test
    void configRule_routesShellScript() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .routingRule("Shell scripts", List.of("*.sh"), List.of(), "automation")
                .directory("automation")
                    .withIdentity(types("automation"), formats("sh"), confidence(0.8))
                    .end()
                .rootFile("start.sh", "#!/bin/bash\necho hello", ageDays(60))
                .build();

        runSweep(fixture.getRoot(), "--yes");

        fixture.assertFileExists("automation/start.sh");
        fixture.assertFileAbsent("start.sh");
    }

    // -------------------------------------------------------------------------
    // Unmatched file goes to archive
    // -------------------------------------------------------------------------

    @Test
    void unmatchedFile_goesToArchive() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                // No routing rules → everything goes to archive
                .rootFile("TONIGHT-PLAN.md", "# Session plan", ageDays(60))
                .build();

        runSweep(fixture.getRoot(), "--yes");

        fixture.assertFileInArchive("TONIGHT-PLAN.md");
        fixture.assertFileAbsent("TONIGHT-PLAN.md");
    }

    // -------------------------------------------------------------------------
    // Identity routing: .synthesis.md declares accepted format
    // -------------------------------------------------------------------------

    @Test
    void identityRouting_matchingFile_routesToIdentityDir() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                // No config rules — rely on directory identity routing
                .directory("scripts")
                    .withIdentity(types("automation", "scripts"), formats("sh", "bash"), confidence(0.8))
                    .end()
                .rootFile("batch-job.sh", "#!/bin/bash\necho run", ageDays(60))
                .build();

        runSweep(fixture.getRoot(), "--yes");

        fixture.assertFileExists("scripts/batch-job.sh");
        fixture.assertFileAbsent("batch-job.sh");
    }

    // -------------------------------------------------------------------------
    // Dry run: no filesystem changes
    // -------------------------------------------------------------------------

    @Test
    void dryRun_makesNoFilesystemChanges() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .routingRule("Shell scripts", List.of("*.sh"), List.of(), "automation")
                .directory("automation")
                    .withIdentity(types("automation"), formats("sh"), confidence(0.8))
                    .end()
                .rootFile("cleanup.sh", "#!/bin/bash", ageDays(60))
                .build();

        runSweep(fixture.getRoot(), "--dry-run");

        // File must still be at root (dry run = no moves)
        fixture.assertFileExists("cleanup.sh");
        // Destination must NOT have it yet
        assertFalse(Files.exists(fixture.resolve("automation/cleanup.sh")),
                "Dry run must not create automation/cleanup.sh");
    }

    // -------------------------------------------------------------------------
    // Multiple rules: first matching rule wins
    // -------------------------------------------------------------------------

    @Test
    void multipleRules_firstMatchWins() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .routingRule("Shell scripts", List.of("*.sh"), List.of(), "automation")
                .routingRule("All files", List.of("*"), List.of(), "catch-all")
                .directory("automation")
                    .withIdentity(types("automation"), formats("sh"), confidence(0.8))
                    .end()
                .directory("catch-all")
                    .end()
                .rootFile("deploy.sh", "#!/bin/bash", ageDays(60))
                .build();

        runSweep(fixture.getRoot(), "--yes");

        // Should be routed by the first rule, not the second
        fixture.assertFileExists("automation/deploy.sh");
        fixture.assertFileAbsent("catch-all/deploy.sh");
    }

    // -------------------------------------------------------------------------
    // No candidates: sweep reports nothing to do
    // -------------------------------------------------------------------------

    @Test
    void noSweepCandidates_commandSucceeds() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                // A fresh (non-old) file should not be a sweep candidate
                .rootFile("README.md", "# My workspace", 0)
                .build();

        // Should exit normally (exit code 0) with no files to move
        int exitCode = runSweepForCode(fixture.getRoot(), "--yes");
        assertEquals(0, exitCode, "Sweep with no candidates should return 0");

        // File untouched
        fixture.assertFileExists("README.md");
    }

    // -------------------------------------------------------------------------
    // Safety: no files written outside TempDir
    // -------------------------------------------------------------------------

    @Test
    void sweep_doesNotWriteOutsideTempDir() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .rootFile("old-script.sh", "#!/bin/bash", ageDays(60))
                .build();

        runSweep(fixture.getRoot(), "--yes");

        // The swept file must end up somewhere inside tempDir (archive or routed),
        // never escape to the real filesystem outside the test sandbox.
        assertFalse(Files.exists(Path.of("/src/exoreaction/Synthesis/old-script.sh")),
                "Swept file must not be placed in the real project source tree");
        // The file should have moved from root to archive (no config rules)
        fixture.assertFileAbsent("old-script.sh");
        fixture.assertFileInArchive("old-script.sh");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Runs {@code synthesis sweep} with the given args against the workspace at
     * {@code workspaceRoot}, capturing stdout.
     *
     * <p>Uses the same picocli injection pattern as {@code SyncCommandTest}.
     */
    private String runSweep(Path workspaceRoot, String... extraArgs) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(baos));
        try {
            int code = runSweepForCode(workspaceRoot, extraArgs);
            // Allow 0 (success) — some tests check output, not code
            // exit code intentionally ignored — tests check file system state
        } finally {
            System.setOut(originalOut);
        }
        return baos.toString();
    }

    /**
     * Runs {@code synthesis sweep} and returns the exit code, without redirecting stdout.
     */
    private int runSweepForCode(Path workspaceRoot, String... extraArgs) throws Exception {
        SweepCommand cmd = new SweepCommand();
        SynthesisApp app = new SynthesisApp();

        // Inject parent via picocli (same pattern as SyncCommandTest)
        CommandLine appCmd = new CommandLine(app);
        appCmd.parseArgs("-d", workspaceRoot.toString());

        // SweepCommand uses @ParentCommand but does not expose setParent(), so inject via reflection
        Field parentField = SweepCommand.class.getDeclaredField("parent");
        parentField.setAccessible(true);
        parentField.set(cmd, app);

        // Parse sweep-specific args
        if (extraArgs.length > 0) {
            new CommandLine(cmd).parseArgs(extraArgs);
        }

        return cmd.call();
    }
}
