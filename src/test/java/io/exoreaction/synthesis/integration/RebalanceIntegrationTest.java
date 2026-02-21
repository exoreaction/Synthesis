package io.exoreaction.synthesis.integration;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.cli.MaintainCommand;
import io.exoreaction.synthesis.integration.WorkspaceIsolationExtension.IsolatedWorkspaceTest;
import io.exoreaction.synthesis.org.DirectoryIdentityRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.exoreaction.synthesis.integration.WorkspaceFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the rebalance archive flow.
 *
 * <p>Verifies that files archived under {@code archive/} can be recalled
 * (re-routed) by {@link MaintainCommand#rebalanceArchive} when a directory
 * identity is present that accepts their type.
 *
 * <p>All tests run in a {@code @TempDir}; no real workspace is touched.
 *
 * @since v1.9.9 (issue #184)
 */
@IsolatedWorkspaceTest
class RebalanceIntegrationTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // rebalanceArchive: archived .sh recalled by identity directory
    // -------------------------------------------------------------------------

    @Test
    void rebalanceArchive_shellScript_recalledToIdentityDir() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .directory("automation")
                    .withIdentity(types("automation", "scripts"), formats("sh", "bash"),
                            patterns("*.sh", "*.bash"), confidence(0.8))
                    .end()
                .build();

        // Manually place a file in the archive (simulating a previous sweep)
        Path archiveDir = fixture.resolve("archive/swept-2026-01-01");
        Files.createDirectories(archiveDir);
        Files.writeString(archiveDir.resolve("deploy.sh"), "#!/bin/bash\necho deploy");

        // Run rebalance via reflection (method is package-private)
        MaintainCommand maintain = buildMaintainCommand(fixture.getRoot());
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(fixture.getRoot(), null);
        int moved = invokeRebalanceArchive(maintain, archiveDir, router, fixture.getRoot());

        assertEquals(1, moved, "Expected exactly 1 file to be rebalanced");
        fixture.assertFileExists("automation/deploy.sh");
        assertFalse(Files.exists(archiveDir.resolve("deploy.sh")),
                "File should have been moved out of archive");
    }

    // -------------------------------------------------------------------------
    // rebalanceArchive: file with no matching identity stays in archive
    // -------------------------------------------------------------------------

    @Test
    void rebalanceArchive_noMatchingIdentity_fileRemainsInArchive() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                // Identity only accepts markdown, not PDF
                .directory("docs")
                    .withIdentity(types("documentation"), formats("md"), confidence(0.8))
                    .end()
                .build();

        Path archiveDir = fixture.resolve("archive/swept-2026-01-15");
        Files.createDirectories(archiveDir);
        Files.writeString(archiveDir.resolve("report.pdf"), "%PDF-1.4 fake pdf");

        MaintainCommand maintain = buildMaintainCommand(fixture.getRoot());
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(fixture.getRoot(), null);
        int moved = invokeRebalanceArchive(maintain, archiveDir, router, fixture.getRoot());

        assertEquals(0, moved, "PDF should not be rebalanced when no identity matches");
        assertTrue(Files.exists(archiveDir.resolve("report.pdf")),
                "PDF should remain in archive when no identity dir matches");
    }

    // -------------------------------------------------------------------------
    // rebalanceArchive: empty archive returns 0
    // -------------------------------------------------------------------------

    @Test
    void rebalanceArchive_emptyArchive_returnsZero() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .directory("automation")
                    .withIdentity(types("automation"), formats("sh"), confidence(0.8))
                    .end()
                .build();

        Path archiveDir = fixture.resolve("archive/swept-empty");
        Files.createDirectories(archiveDir);

        MaintainCommand maintain = buildMaintainCommand(fixture.getRoot());
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(fixture.getRoot(), null);
        int moved = invokeRebalanceArchive(maintain, archiveDir, router, fixture.getRoot());

        assertEquals(0, moved, "Empty archive should return 0 files moved");
    }

    // -------------------------------------------------------------------------
    // rebalanceArchive: multiple files, partial match
    // -------------------------------------------------------------------------

    @Test
    void rebalanceArchive_multipleFiles_onlyMatchingRecalled() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                // Only accepts .sh files (with pattern glob for strong scoring >= 0.7)
                .directory("automation")
                    .withIdentity(types("automation"), formats("sh"),
                            patterns("*.sh"), confidence(0.8))
                    .end()
                .build();

        Path archiveDir = fixture.resolve("archive/swept-2026-02-01");
        Files.createDirectories(archiveDir);
        Files.writeString(archiveDir.resolve("run.sh"), "#!/bin/bash");
        Files.writeString(archiveDir.resolve("notes.md"), "# Notes");
        Files.writeString(archiveDir.resolve("data.zip"), "binary data");

        MaintainCommand maintain = buildMaintainCommand(fixture.getRoot());
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(fixture.getRoot(), null);
        int moved = invokeRebalanceArchive(maintain, archiveDir, router, fixture.getRoot());

        assertEquals(1, moved, "Only the .sh file should be recalled");
        fixture.assertFileExists("automation/run.sh");
        // markdown and zip remain in archive
        assertTrue(Files.exists(archiveDir.resolve("notes.md")), "notes.md should remain in archive");
        assertTrue(Files.exists(archiveDir.resolve("data.zip")), "data.zip should remain in archive");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates a {@link MaintainCommand} instance with its {@code parent} field
     * injected to point at the given workspace root.
     *
     * <p>Uses reflection since {@code MaintainCommand} doesn't expose a
     * {@code setParent()} method.
     */
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
     *
     * <p>This avoids widening the method visibility in production code while still
     * allowing integration tests to call the actual implementation.
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
