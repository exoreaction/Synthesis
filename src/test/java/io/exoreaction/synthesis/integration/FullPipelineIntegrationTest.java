package io.exoreaction.synthesis.integration;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.cli.MaintainCommand;
import io.exoreaction.synthesis.cli.SweepCommand;
import io.exoreaction.synthesis.integration.WorkspaceIsolationExtension.IsolatedWorkspaceTest;
import io.exoreaction.synthesis.org.DirectoryIdentityRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static io.exoreaction.synthesis.integration.WorkspaceFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the sweep → rebalance pipeline.
 *
 * <p>Each test chains two operations:
 * <ol>
 *   <li>Sweep: scans root-level stale files and moves them (to a config destination or archive)</li>
 *   <li>Rebalance: recalls archived files that now match a directory identity</li>
 * </ol>
 *
 * <p>All tests run in a {@code @TempDir}; no real workspace is touched.
 *
 * @since v1.9.9 (issue #184)
 */
@IsolatedWorkspaceTest
class FullPipelineIntegrationTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Full pipeline: sweep archives unmatched file; rebalance recalls it
    // -------------------------------------------------------------------------

    @Test
    void fullPipeline_sweepThenRebalance_recallsArchivedFile() throws Exception {
        // Set up workspace with an identity directory that accepts .sh files,
        // but no config routing rule → sweep sends the file to archive.
        // Rebalance should then recall it from archive.
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .directory("automation")
                    .withIdentity(types("automation", "scripts"), formats("sh", "bash"), confidence(0.9))
                    .end()
                .rootFile("batch-job.sh", "#!/bin/bash\necho batch", ageDays(60))
                .build();

        // Step 1: Sweep with --archive-only to bypass config routing
        // (simulates no config rules → archive fallback)
        runSweep(fixture.getRoot(), "--yes", "--archive-only");

        // The file should now be in archive, not at root
        fixture.assertFileAbsent("batch-job.sh");
        fixture.assertFileInArchive("batch-job.sh");

        // Step 2: Rebalance from the archive directory
        Path archiveDir = fixture.resolve("archive/swept-" + LocalDate.now());
        assertTrue(Files.exists(archiveDir), "Archive dir should have been created by sweep");

        MaintainCommand maintain = buildMaintainCommand(fixture.getRoot());
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(fixture.getRoot(), null);
        int recalled = invokeRebalanceArchive(maintain, archiveDir, router, fixture.getRoot());

        assertEquals(1, recalled, "Rebalance should recall exactly 1 file from archive");
        fixture.assertFileExists("automation/batch-job.sh");
        assertFalse(Files.exists(archiveDir.resolve("batch-job.sh")),
                "File should be gone from archive after rebalance");
    }

    // -------------------------------------------------------------------------
    // Full pipeline: sweep routes via config rule (no rebalance needed)
    // -------------------------------------------------------------------------

    @Test
    void fullPipeline_sweepWithConfigRule_noRebalanceNeeded() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .routingRule("Shell scripts", List.of("*.sh"), List.of(), "automation")
                .directory("automation")
                    .withIdentity(types("automation"), formats("sh"), confidence(0.8))
                    .end()
                .rootFile("nightly.sh", "#!/bin/bash\necho nightly", ageDays(45))
                .build();

        // Step 1: Sweep — config rule routes directly to automation/
        runSweep(fixture.getRoot(), "--yes");

        fixture.assertFileExists("automation/nightly.sh");
        fixture.assertFileAbsent("nightly.sh");

        // Step 2: Rebalance — nothing in archive, should be a no-op
        Path archiveDir = fixture.resolve("archive");
        if (Files.exists(archiveDir)) {
            MaintainCommand maintain = buildMaintainCommand(fixture.getRoot());
            DirectoryIdentityRouter router = new DirectoryIdentityRouter(fixture.getRoot(), null);
            // Walk all archive subdirs
            long totalMoved = Files.walk(archiveDir)
                    .filter(Files::isDirectory)
                    .filter(d -> !d.equals(archiveDir))
                    .mapToLong(dir -> {
                        try {
                            return invokeRebalanceArchive(maintain, dir, router, fixture.getRoot());
                        } catch (Exception e) {
                            return 0;
                        }
                    })
                    .sum();
            assertEquals(0, totalMoved,
                    "Rebalance should have nothing to do when sweep routed correctly");
        }
        // If archive doesn't exist, that's fine — nothing was archived
    }

    // -------------------------------------------------------------------------
    // Full pipeline: sweep + rebalance with multiple file types
    // -------------------------------------------------------------------------

    @Test
    void fullPipeline_mixedFiles_onlyShellRecalled() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .directory("automation")
                    .withIdentity(types("automation", "scripts"), formats("sh"), confidence(0.8))
                    .end()
                .rootFile("run.sh", "#!/bin/bash", ageDays(60))
                .rootFile("TONIGHT-PLAN.md", "# Plan", ageDays(60))
                .build();

        // Sweep sends both to archive (no config rules for either)
        runSweep(fixture.getRoot(), "--yes", "--archive-only");

        fixture.assertFileAbsent("run.sh");
        fixture.assertFileAbsent("TONIGHT-PLAN.md");

        // Rebalance: only run.sh matches automation identity
        Path archiveDir = fixture.resolve("archive/swept-" + LocalDate.now());
        MaintainCommand maintain = buildMaintainCommand(fixture.getRoot());
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(fixture.getRoot(), null);
        int recalled = invokeRebalanceArchive(maintain, archiveDir, router, fixture.getRoot());

        assertEquals(1, recalled, "Only run.sh should be recalled");
        fixture.assertFileExists("automation/run.sh");
        // TONIGHT-PLAN.md remains in archive
        assertTrue(Files.exists(archiveDir.resolve("TONIGHT-PLAN.md")),
                "TONIGHT-PLAN.md should remain in archive (no matching identity)");
    }

    // -------------------------------------------------------------------------
    // Full pipeline: dry run sweep leaves everything in place
    // -------------------------------------------------------------------------

    @Test
    void fullPipeline_dryRunSweep_nothingMovedForRebalance() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .directory("automation")
                    .withIdentity(types("automation"), formats("sh"), confidence(0.8))
                    .end()
                .rootFile("task.sh", "#!/bin/bash", ageDays(60))
                .build();

        // Dry run — should NOT move anything
        runSweep(fixture.getRoot(), "--dry-run");

        fixture.assertFileExists("task.sh"); // still at root
        assertFalse(Files.exists(fixture.resolve("archive")),
                "Archive should not be created on dry run");
        // Rebalance has nothing to process — no archive directory
        // (nothing to assert beyond no exceptions thrown)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void runSweep(Path workspaceRoot, String... extraArgs) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(baos));
        try {
            SweepCommand cmd = new SweepCommand();
            SynthesisApp app = new SynthesisApp();
            new CommandLine(app).parseArgs("-d", workspaceRoot.toString());

            Field parentField = SweepCommand.class.getDeclaredField("parent");
            parentField.setAccessible(true);
            parentField.set(cmd, app);

            if (extraArgs.length > 0) {
                new CommandLine(cmd).parseArgs(extraArgs);
            }

            cmd.call();
        } finally {
            System.setOut(originalOut);
        }
    }

    private MaintainCommand buildMaintainCommand(Path workspaceRoot) throws Exception {
        MaintainCommand cmd = new MaintainCommand();
        SynthesisApp app = new SynthesisApp();
        new CommandLine(app).parseArgs("-d", workspaceRoot.toString());

        Field parentField = MaintainCommand.class.getDeclaredField("parent");
        parentField.setAccessible(true);
        parentField.set(cmd, app);
        return cmd;
    }

    /**
     * Invokes the package-private {@code rebalanceArchive} method via reflection.
     */
    private int invokeRebalanceArchive(MaintainCommand cmd, Path archiveDir,
                                        DirectoryIdentityRouter router, Path workspaceRoot)
            throws Exception {
        Method method = MaintainCommand.class.getDeclaredMethod(
                "rebalanceArchive", Path.class, DirectoryIdentityRouter.class, Path.class);
        method.setAccessible(true);
        return (int) method.invoke(cmd, archiveDir, router, workspaceRoot);
    }
}
