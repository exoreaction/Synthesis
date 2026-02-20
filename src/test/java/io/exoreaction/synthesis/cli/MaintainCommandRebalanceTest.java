package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.DirectoryIdentityRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MaintainCommand#rebalanceArchive} (issue #180).
 */
class MaintainCommandRebalanceTest {

    @TempDir
    Path workspace;

    private static final String SH_IDENTITY =
            "---\nsynthesis:\n  accepts:\n    types:\n      - \"automation\"\n      - \"scripts\"\n"
            + "    formats:\n      - \"sh\"\n      - \"py\"\n  scope:\n    level: \"WORKSPACE\"\n"
            + "    organization: null\n    entity: null\n  confidence: 0.8\n---\n";

    private static final String MD_IDENTITY =
            "---\nsynthesis:\n  accepts:\n    types:\n      - \"documentation\"\n"
            + "    formats:\n      - \"md\"\n  scope:\n    level: \"WORKSPACE\"\n"
            + "    organization: null\n    entity: null\n  confidence: 0.7\n---\n";

    @Test
    void rebalanceArchive_movesHighScoringFileToActiveDir() throws IOException {
        // Automation dir with identity
        Path automationDir = Files.createDirectories(workspace.resolve("automation"));
        Files.writeString(automationDir.resolve(".synthesis.md"), SH_IDENTITY);

        // Archive contains a shell script
        Path archiveDir = Files.createDirectories(workspace.resolve("archive/swept-2026-01-01"));
        Path archivedScript = archiveDir.resolve("deploy.sh");
        Files.writeString(archivedScript, "#!/bin/bash\necho deploy");

        MaintainCommand cmd = new MaintainCommand();
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(workspace, null);
        int moved = cmd.rebalanceArchive(archiveDir, router, workspace);

        assertEquals(1, moved, "Expected 1 file moved from archive to automation");
        assertTrue(Files.exists(automationDir.resolve("deploy.sh")),
                "deploy.sh should have been moved to automation/");
        assertFalse(Files.exists(archivedScript),
                "Original archive copy should no longer exist");
    }

    @Test
    void rebalanceArchive_doesNotMoveIfScoreBelowThreshold() throws IOException {
        // Presentations dir only accepts pptx/pdf — not matching .sh
        Path presentDir = Files.createDirectories(workspace.resolve("presentations"));
        Files.writeString(presentDir.resolve(".synthesis.md"),
                "---\nsynthesis:\n  accepts:\n    types:\n      - \"presentation\"\n"
                + "    formats:\n      - \"pptx\"\n      - \"pdf\"\n  scope:\n    level: \"WORKSPACE\"\n"
                + "    organization: null\n    entity: null\n  confidence: 0.8\n---\n");

        // Archive contains a shell script — low score against presentations dir
        Path archiveDir = Files.createDirectories(workspace.resolve("archive/swept-2026-01-01"));
        Path archivedScript = archiveDir.resolve("run-batch.sh");
        Files.writeString(archivedScript, "#!/bin/bash");

        MaintainCommand cmd = new MaintainCommand();
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(workspace, null);
        int moved = cmd.rebalanceArchive(archiveDir, router, workspace);

        assertEquals(0, moved, "Shell script should not match presentations dir");
        assertTrue(Files.exists(archivedScript), "File should remain in archive");
    }

    @Test
    void rebalanceArchive_emptyArchive_returnsZero() throws IOException {
        Path archiveDir = Files.createDirectories(workspace.resolve("archive"));
        Path docsDir = Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(docsDir.resolve(".synthesis.md"), MD_IDENTITY);

        MaintainCommand cmd = new MaintainCommand();
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(workspace, null);
        int moved = cmd.rebalanceArchive(archiveDir, router, workspace);

        assertEquals(0, moved);
    }

    @Test
    void rebalanceArchive_noIdentityDirs_allFilesStay() throws IOException {
        // Archive has files but no .synthesis.md directories exist → no routing possible
        Path archiveDir = Files.createDirectories(workspace.resolve("archive/old"));
        Files.writeString(archiveDir.resolve("old-report.md"), "# Old");
        Files.writeString(archiveDir.resolve("old-script.sh"), "#!/bin/bash");

        MaintainCommand cmd = new MaintainCommand();
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(workspace, null);
        int moved = cmd.rebalanceArchive(archiveDir, router, workspace);

        assertEquals(0, moved, "No identity dirs → nothing should move");
        assertTrue(Files.exists(archiveDir.resolve("old-report.md")));
        assertTrue(Files.exists(archiveDir.resolve("old-script.sh")));
    }

    @Test
    void rebalanceArchive_movesMultipleFiles() throws IOException {
        // Two identity dirs
        Path automationDir = Files.createDirectories(workspace.resolve("automation"));
        Files.writeString(automationDir.resolve(".synthesis.md"), SH_IDENTITY);
        Path docsDir = Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(docsDir.resolve(".synthesis.md"), MD_IDENTITY);

        // Archive contains both a .sh and a .md
        Path archiveDir = Files.createDirectories(workspace.resolve("archive/swept"));
        Files.writeString(archiveDir.resolve("cleanup.sh"), "#!/bin/bash");
        Files.writeString(archiveDir.resolve("guide.md"), "# Guide");

        MaintainCommand cmd = new MaintainCommand();
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(workspace, null);
        int moved = cmd.rebalanceArchive(archiveDir, router, workspace);

        assertEquals(2, moved, "Both .sh and .md should be rebalanced");
        assertTrue(Files.exists(automationDir.resolve("cleanup.sh")));
        assertTrue(Files.exists(docsDir.resolve("guide.md")));
    }
}
