package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.integration.WorkspaceFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@code --dry-run} behaviour of the {@link MaintainOrchestrator}.
 *
 * <p>Dry-run must:
 * <ul>
 *   <li>Return all 9 phase results</li>
 *   <li>Make zero filesystem changes</li>
 *   <li>Print a "No changes made" footer (tested via MaintainCommand integration)</li>
 * </ul>
 *
 * @since v1.9.9 (issue #185)
 */
class MaintainDryRunTest {

    @TempDir
    Path tempDir;

    // =========================================================================
    // Helpers
    // =========================================================================

    private SynthesisConfig loadConfig(Path workspaceRoot) {
        try {
            return ConfigLoader.load(workspaceRoot);
        } catch (Exception e) {
            return new SynthesisConfig();
        }
    }

    private void setupMinimalWorkspace(Path root) throws IOException {
        Files.createDirectories(root.resolve(".synthesis"));
        Files.writeString(root.resolve(".synthesis/config.yaml"),
                "workspace:\n  name: \"dryrun-test\"\n");
    }

    /**
     * Captures a snapshot of the filesystem: path → last-modified-millis for all regular files.
     */
    private Map<Path, Long> snapshotFilesystem(Path root) throws IOException {
        Map<Path, Long> snapshot = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    snapshot.put(p, Files.getLastModifiedTime(p).toMillis());
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
        return snapshot;
    }

    // =========================================================================
    // Zero filesystem changes
    // =========================================================================

    @Test
    void dry_run_makes_zero_filesystem_changes() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .rootFile("loose-file.sh", "#!/bin/bash", WorkspaceFixture.ageDays(60))
                .rootFile("stale-doc.md", "old content", WorkspaceFixture.ageDays(60))
                .build();

        // Snapshot filesystem before dry-run
        Map<Path, Long> before = snapshotFilesystem(tempDir);

        SynthesisConfig config = loadConfig(tempDir);
        new MaintainOrchestrator(tempDir, MaintainOptions.forDryRun(), config).run();

        Map<Path, Long> after = snapshotFilesystem(tempDir);
        assertEquals(before, after, "dry-run must not change any files");
    }

    @Test
    void dry_run_does_not_create_archive_directory() throws Exception {
        WorkspaceFixture.builder(tempDir)
                .rootFile("stale-script.sh", "#!/bin/bash", WorkspaceFixture.ageDays(60))
                .build();

        new MaintainOrchestrator(tempDir, MaintainOptions.forDryRun(), loadConfig(tempDir)).run();

        assertFalse(Files.isDirectory(tempDir.resolve("archive")),
                "archive/ directory must not be created during dry-run");
    }

    @Test
    void dry_run_does_not_delete_empty_directories() throws Exception {
        setupMinimalWorkspace(tempDir);
        Path emptyDir = Files.createDirectories(tempDir.resolve("empty-test-dir"));

        new MaintainOrchestrator(tempDir, MaintainOptions.forDryRun(), loadConfig(tempDir)).run();

        assertTrue(Files.isDirectory(emptyDir),
                "Empty directory should not be pruned during dry-run");
    }

    // =========================================================================
    // Phase count
    // =========================================================================

    @Test
    void dry_run_returns_all_9_phase_results() throws Exception {
        setupMinimalWorkspace(tempDir);
        SynthesisConfig config = loadConfig(tempDir);

        MaintainResult result = new MaintainOrchestrator(
                tempDir, MaintainOptions.forDryRun(), config).run();

        assertEquals(9, result.phases().size(),
                "Dry-run must return all 9 phase results");
    }

    @Test
    void dry_run_all_phases_succeed() throws Exception {
        setupMinimalWorkspace(tempDir);
        SynthesisConfig config = loadConfig(tempDir);

        MaintainResult result = new MaintainOrchestrator(
                tempDir, MaintainOptions.forDryRun(), config).run();

        for (PhaseResult phase : result.phases()) {
            assertTrue(phase.succeeded(),
                    "Phase " + phase.phaseNumber() + " (" + phase.name()
                            + ") should succeed in dry-run: " + phase.error());
        }
    }

    // =========================================================================
    // Phase-level dry-run signals
    // =========================================================================

    @Test
    void dry_run_sweep_phase_reports_would_be_swept() throws Exception {
        setupMinimalWorkspace(tempDir);

        // Stale file at root — sweep should pick it up
        Path staleScript = tempDir.resolve("old-build.sh");
        Files.writeString(staleScript, "#!/bin/bash");
        Files.setLastModifiedTime(staleScript,
                FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));

        MaintainResult result = new MaintainOrchestrator(
                tempDir, MaintainOptions.forDryRun(), loadConfig(tempDir)).run();

        PhaseResult sweep = result.phases().get(3); // phase 4 is index 3
        assertEquals("Sweep", sweep.name());
        assertTrue(sweep.succeeded(), "Sweep should succeed in dry-run: " + sweep.error());
        assertTrue(sweep.changeCount() >= 1,
                "Dry-run should report at least 1 file would be swept");
        assertTrue(sweep.summary().contains("would be swept"),
                "Dry-run sweep summary should mention 'would be swept': " + sweep.summary());

        // File must not have moved
        assertTrue(Files.exists(staleScript), "File must not be swept during dry-run");
    }

    @Test
    void dry_run_prune_phase_reports_would_be_removed() throws Exception {
        setupMinimalWorkspace(tempDir);
        Files.createDirectories(tempDir.resolve("empty-dir-to-prune"));

        MaintainResult result = new MaintainOrchestrator(
                tempDir, MaintainOptions.forDryRun(), loadConfig(tempDir)).run();

        PhaseResult prune = result.phases().get(8); // phase 9 is index 8
        assertEquals("Prune", prune.name());
        assertTrue(prune.succeeded(), "Prune should succeed in dry-run");
        assertTrue(prune.summary().contains("would be removed"),
                "Dry-run prune summary should mention 'would be removed': " + prune.summary());
    }

    @Test
    void dry_run_expire_phase_reports_would_be_archived() throws Exception {
        // Use ageDays(15): below sweep threshold (30d), above TTL (7d)
        // so the file is detected by expire but NOT pre-swept away
        WorkspaceFixture.builder(tempDir)
                .withTtlRule("ephemeral", java.util.List.of("TONIGHT-*.md"), 7)
                .rootFile("TONIGHT-OLD.md", "old plan", WorkspaceFixture.ageDays(15))
                .build();

        MaintainResult result = new MaintainOrchestrator(
                tempDir, MaintainOptions.forDryRun(), loadConfig(tempDir)).run();

        PhaseResult expire = result.phases().get(5); // phase 6 is index 5
        assertEquals("Expire", expire.name());
        assertTrue(expire.succeeded(), "Expire should succeed in dry-run");
        assertEquals(1, expire.changeCount(),
                "Should report 1 file would be archived by TTL");

        // File not moved
        assertTrue(Files.exists(tempDir.resolve("TONIGHT-OLD.md")),
                "File must not be moved during dry-run");
    }
}
