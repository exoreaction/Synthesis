package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.DirectoryIdentityRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MaintainCommand#rebalanceArchive} (issue #180, #209).
 *
 * <p>The rebalance threshold was raised from 0.5 to 0.7 in issue #209 to reduce
 * false positives. Tests use identity declarations with acceptsPatterns globs
 * to ensure strong scoring signals (type + format + pattern >= 0.8).
 */
class MaintainCommandRebalanceTest {

    @TempDir
    Path workspace;

    /** Shell script identity with pattern globs for strong scoring (type+format+pattern = 0.8). */
    private static final String SH_IDENTITY =
            "---\nsynthesis:\n  accepts:\n    types:\n      - \"automation\"\n      - \"scripts\"\n"
            + "    formats:\n      - \"sh\"\n      - \"py\"\n    patterns:\n      - \"*.sh\"\n      - \"*.py\"\n"
            + "  scope:\n    level: \"WORKSPACE\"\n"
            + "    organization: null\n    entity: null\n  confidence: 0.8\n---\n";

    /** Markdown documentation identity with specific type + pattern globs for strong scoring.
     *  Uses "guide" (specific) which scores +0.3 type + 0.2 format + 0.3 pattern = 0.8. */
    private static final String MD_IDENTITY =
            "---\nsynthesis:\n  accepts:\n    types:\n      - \"guide\"\n      - \"documentation\"\n"
            + "    formats:\n      - \"md\"\n    patterns:\n      - \"*.md\"\n"
            + "  scope:\n    level: \"WORKSPACE\"\n"
            + "    organization: null\n    entity: null\n  confidence: 0.7\n---\n";

    @Test
    void rebalanceArchive_movesHighScoringFileToActiveDir() throws IOException {
        // Automation dir with identity (type + format + pattern = 0.8 for .sh files)
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
        // Archive has files but no .synthesis.md directories exist -- no routing possible
        Path archiveDir = Files.createDirectories(workspace.resolve("archive/misc"));
        Files.writeString(archiveDir.resolve("old-report.md"), "# Old");
        Files.writeString(archiveDir.resolve("old-script.sh"), "#!/bin/bash");

        MaintainCommand cmd = new MaintainCommand();
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(workspace, null);
        int moved = cmd.rebalanceArchive(archiveDir, router, workspace);

        assertEquals(0, moved, "No identity dirs -> nothing should move");
        assertTrue(Files.exists(archiveDir.resolve("old-report.md")));
        assertTrue(Files.exists(archiveDir.resolve("old-script.sh")));
    }

    @Test
    void rebalanceArchive_movesMultipleFiles() throws IOException {
        // Two identity dirs with pattern globs
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

    // =========================================================================
    // Issue #209: .git exclusion, frozen subtree exclusion, threshold 0.7
    // =========================================================================

    @Test
    void rebalanceArchive_skipsGitInternalFiles() throws IOException {
        // Automation dir with identity
        Path automationDir = Files.createDirectories(workspace.resolve("automation"));
        Files.writeString(automationDir.resolve(".synthesis.md"), SH_IDENTITY);

        // Archive contains a .git directory with internal objects
        Path archiveDir = Files.createDirectories(workspace.resolve("archive"));
        Path gitObjectsDir = Files.createDirectories(archiveDir.resolve(".git/objects/ab"));
        Files.writeString(gitObjectsDir.resolve("cdef1234567890"), "blob data");
        Path gitRefsDir = Files.createDirectories(archiveDir.resolve(".git/refs/heads"));
        Files.writeString(gitRefsDir.resolve("main"), "abcdef1234567890");

        // Also add a legitimate shell script to verify it still gets processed
        Path sweptDir = Files.createDirectories(archiveDir.resolve("swept-2026-02-01"));
        Files.writeString(sweptDir.resolve("setup.sh"), "#!/bin/bash");

        MaintainCommand cmd = new MaintainCommand();
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(workspace, null);
        int moved = cmd.rebalanceArchive(archiveDir, router, workspace);

        // Only the legitimate .sh should be considered (git files excluded)
        assertEquals(1, moved, ".git internal files should be skipped, only setup.sh should move");
        assertTrue(Files.exists(automationDir.resolve("setup.sh")));
        // .git files should remain untouched
        assertTrue(Files.exists(gitObjectsDir.resolve("cdef1234567890")));
        assertTrue(Files.exists(gitRefsDir.resolve("main")));
    }

    @Test
    void rebalanceArchive_skipsFrozenOldSubtree() throws IOException {
        // Automation dir with identity
        Path automationDir = Files.createDirectories(workspace.resolve("automation"));
        Files.writeString(automationDir.resolve(".synthesis.md"), SH_IDENTITY);

        // Archive with a frozen snapshot subtree (old-*)
        Path archiveDir = Files.createDirectories(workspace.resolve("archive"));
        Path frozenDir = Files.createDirectories(archiveDir.resolve("old-exoreaction-structure-20260128/automation"));
        Files.writeString(frozenDir.resolve("deploy.sh"), "#!/bin/bash\necho old snapshot");
        Files.writeString(frozenDir.resolve("backup.sh"), "#!/bin/bash\necho old backup");

        // Also a non-frozen file
        Path sweptDir = Files.createDirectories(archiveDir.resolve("swept-2026-02-01"));
        Files.writeString(sweptDir.resolve("cleanup.sh"), "#!/bin/bash");

        MaintainCommand cmd = new MaintainCommand();
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(workspace, null);
        int moved = cmd.rebalanceArchive(archiveDir, router, workspace);

        // Only the non-frozen cleanup.sh should move
        assertEquals(1, moved, "Frozen subtree files should be excluded from rebalance");
        assertTrue(Files.exists(automationDir.resolve("cleanup.sh")));
        // Frozen files should remain
        assertTrue(Files.exists(frozenDir.resolve("deploy.sh")));
        assertTrue(Files.exists(frozenDir.resolve("backup.sh")));
    }

    @Test
    void rebalanceArchive_skipsSnapshotAndFrozenSubtrees() throws IOException {
        // Automation dir with identity
        Path automationDir = Files.createDirectories(workspace.resolve("automation"));
        Files.writeString(automationDir.resolve(".synthesis.md"), SH_IDENTITY);

        Path archiveDir = Files.createDirectories(workspace.resolve("archive"));

        // All three frozen prefixes
        Files.createDirectories(archiveDir.resolve("old-backup"));
        Files.writeString(archiveDir.resolve("old-backup/run.sh"), "#!/bin/bash");

        Files.createDirectories(archiveDir.resolve("snapshot-2026-01-15"));
        Files.writeString(archiveDir.resolve("snapshot-2026-01-15/start.sh"), "#!/bin/bash");

        Files.createDirectories(archiveDir.resolve("frozen-release-v1"));
        Files.writeString(archiveDir.resolve("frozen-release-v1/init.sh"), "#!/bin/bash");

        MaintainCommand cmd = new MaintainCommand();
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(workspace, null);
        int moved = cmd.rebalanceArchive(archiveDir, router, workspace);

        assertEquals(0, moved, "All frozen subtree variants (old-, snapshot-, frozen-) should be excluded");
        assertTrue(Files.exists(archiveDir.resolve("old-backup/run.sh")));
        assertTrue(Files.exists(archiveDir.resolve("snapshot-2026-01-15/start.sh")));
        assertTrue(Files.exists(archiveDir.resolve("frozen-release-v1/init.sh")));
    }

    @Test
    void rebalanceArchive_doesNotMoveGenericOnlyMatch() throws IOException {
        // Docs dir with ONLY generic type "documentation" and format "md" (no patterns)
        Path docsDir = Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(docsDir.resolve(".synthesis.md"),
                "---\nsynthesis:\n  accepts:\n    types:\n      - \"documentation\"\n"
                + "    formats:\n      - \"md\"\n  scope:\n    level: \"WORKSPACE\"\n"
                + "    organization: null\n    entity: null\n  confidence: 0.7\n---\n");

        // Archive contains a .md file -- generic type match (0.15) + format (0.2) = 0.35, below 0.7
        Path archiveDir = Files.createDirectories(workspace.resolve("archive/swept"));
        Path archivedMd = archiveDir.resolve("random-notes.md");
        Files.writeString(archivedMd, "# Some notes");

        MaintainCommand cmd = new MaintainCommand();
        DirectoryIdentityRouter router = new DirectoryIdentityRouter(workspace, null);
        int moved = cmd.rebalanceArchive(archiveDir, router, workspace);

        assertEquals(0, moved,
                "Generic-only type match (documentation) + format should not pass 0.7 threshold");
        assertTrue(Files.exists(archivedMd), "File should remain in archive");
    }

    // =========================================================================
    // Issue #209: MaintainOrchestrator static helper tests
    // =========================================================================

    @Test
    void isInsideGitDir_detectsGitObjects() {
        Path gitFile = Path.of("/workspace/archive/.git/objects/ab/cdef");
        assertTrue(MaintainOrchestrator.isInsideGitDir(gitFile));
    }

    @Test
    void isInsideGitDir_doesNotFlagNonGitPath() {
        Path normalFile = Path.of("/workspace/archive/swept/deploy.sh");
        assertFalse(MaintainOrchestrator.isInsideGitDir(normalFile));
    }

    @Test
    void isInsideGitDir_detectsNestedGit() {
        Path nestedGit = Path.of("/workspace/archive/old-project/.git/refs/heads/main");
        assertTrue(MaintainOrchestrator.isInsideGitDir(nestedGit));
    }

    @Test
    void isFrozenSubtree_detectsOldPrefix() {
        Path archiveDir = Path.of("/workspace/archive");
        Path frozenFile = Path.of("/workspace/archive/old-exoreaction-20260128/business/readme.md");
        assertTrue(MaintainOrchestrator.isFrozenSubtree(frozenFile, archiveDir));
    }

    @Test
    void isFrozenSubtree_detectsSnapshotPrefix() {
        Path archiveDir = Path.of("/workspace/archive");
        Path frozenFile = Path.of("/workspace/archive/snapshot-2026-01-15/data.csv");
        assertTrue(MaintainOrchestrator.isFrozenSubtree(frozenFile, archiveDir));
    }

    @Test
    void isFrozenSubtree_detectsFrozenPrefix() {
        Path archiveDir = Path.of("/workspace/archive");
        Path frozenFile = Path.of("/workspace/archive/frozen-release/config.yaml");
        assertTrue(MaintainOrchestrator.isFrozenSubtree(frozenFile, archiveDir));
    }

    @Test
    void isFrozenSubtree_allowsNonFrozenSubdir() {
        Path archiveDir = Path.of("/workspace/archive");
        Path normalFile = Path.of("/workspace/archive/swept-2026-01-01/deploy.sh");
        assertFalse(MaintainOrchestrator.isFrozenSubtree(normalFile, archiveDir));
    }

    @Test
    void isFrozenSubtree_allowsDirectArchiveChild() {
        Path archiveDir = Path.of("/workspace/archive");
        Path directChild = Path.of("/workspace/archive/stray-file.txt");
        assertFalse(MaintainOrchestrator.isFrozenSubtree(directChild, archiveDir),
                "A file directly in archive/ (not in a subdirectory) should not be frozen");
    }
}
