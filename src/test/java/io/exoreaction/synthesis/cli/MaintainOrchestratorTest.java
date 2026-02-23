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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link MaintainOrchestrator} 9-phase workspace loop.
 *
 * <p>Uses {@link WorkspaceFixture} to create realistic test workspaces
 * with minimal config and file structures.
 */
class MaintainOrchestratorTest {

    @TempDir
    Path tempDir;

    // =========================================================================
    // Helper: create a minimal valid Synthesis workspace
    // =========================================================================

    /**
     * Creates a minimal workspace with .synthesis/ dir and config.yaml.
     * This is the minimum required for the orchestrator to run.
     */
    private Path createMinimalWorkspace() throws IOException {
        Path synthDir = tempDir.resolve(".synthesis");
        Files.createDirectories(synthDir);
        Files.writeString(synthDir.resolve("config.yaml"),
                "workspace:\n  name: \"test-workspace\"\n");
        return tempDir;
    }

    /**
     * Creates a workspace with some files for indexing.
     */
    private Path createWorkspaceWithFiles() throws IOException {
        Path root = createMinimalWorkspace();
        Files.writeString(root.resolve("README.md"), "# Test Project\nSome content here.");
        Files.writeString(root.resolve("notes.txt"), "Some notes");
        Path docs = Files.createDirectories(root.resolve("docs"));
        Files.writeString(docs.resolve("guide.md"), "# Guide\nDetailed guide content.");
        return root;
    }

    /**
     * Loads config from a workspace root.
     */
    private SynthesisConfig loadConfig(Path workspaceRoot) throws IOException {
        return ConfigLoader.load(workspaceRoot);
    }

    // =========================================================================
    // Phase count and ordering
    // =========================================================================

    @Test
    void all_9_phases_run_in_sequence() throws Exception {
        Path root = createMinimalWorkspace();
        SynthesisConfig config = loadConfig(root);
        MaintainOptions opts = MaintainOptions.defaults();

        MaintainOrchestrator orchestrator = new MaintainOrchestrator(root, opts, config);
        MaintainResult result = orchestrator.run();

        assertEquals(11, result.phases().size(),
                "Orchestrator must produce exactly 11 phase results");

        // Verify phase numbers are sequential 1-11
        for (int i = 0; i < 11; i++) {
            assertEquals(i + 1, result.phases().get(i).phaseNumber(),
                    "Phase " + (i + 1) + " has wrong phase number");
        }
    }

    @Test
    void phase_results_have_correct_names() throws Exception {
        Path root = createMinimalWorkspace();
        SynthesisConfig config = loadConfig(root);
        MaintainOptions opts = MaintainOptions.defaults();

        MaintainOrchestrator orchestrator = new MaintainOrchestrator(root, opts, config);
        MaintainResult result = orchestrator.run();

        List<String> expectedNames = List.of(
                "Ingest", "Route", "Sync", "Sweep", "Rebalance",
                "Expire", "Index", "Track", "Prune", "Code Graph", "Security");

        for (int i = 0; i < 11; i++) {
            assertEquals(expectedNames.get(i), result.phases().get(i).name(),
                    "Phase " + (i + 1) + " has wrong name");
        }
    }

    @Test
    void elapsed_time_is_positive() throws Exception {
        Path root = createMinimalWorkspace();
        SynthesisConfig config = loadConfig(root);

        MaintainOrchestrator orchestrator =
                new MaintainOrchestrator(root, MaintainOptions.defaults(), config);
        MaintainResult result = orchestrator.run();

        assertTrue(result.elapsedMs() >= 0, "Elapsed time should be non-negative");
    }

    // =========================================================================
    // Phase failure isolation
    // =========================================================================

    @Test
    void phase_failure_does_not_abort_remaining_phases() throws Exception {
        Path root = createMinimalWorkspace();
        SynthesisConfig config = loadConfig(root);

        // Even with a minimal workspace where some phases may encounter issues,
        // all 11 phases should produce a result (no exception thrown)
        MaintainOrchestrator orchestrator =
                new MaintainOrchestrator(root, MaintainOptions.defaults(), config);
        MaintainResult result = orchestrator.run();

        assertEquals(11, result.phases().size(),
                "All 11 phases must produce results even if some fail");

        // Every phase result should be non-null
        for (PhaseResult phase : result.phases()) {
            assertNotNull(phase, "Phase result must not be null");
            assertNotNull(phase.name(), "Phase name must not be null");
            assertNotNull(phase.summary(), "Phase summary must not be null");
        }
    }

    // =========================================================================
    // --dry-run
    // =========================================================================

    @Test
    void dry_run_makes_zero_filesystem_changes() throws Exception {
        Path root = createWorkspaceWithFiles();
        SynthesisConfig config = loadConfig(root);

        // Create a stale root-level file that sweep would normally move
        Path staleScript = root.resolve("old-script.sh");
        Files.writeString(staleScript, "#!/bin/bash\necho old");
        Instant old = Instant.now().minus(60, ChronoUnit.DAYS);
        Files.setLastModifiedTime(staleScript, FileTime.from(old));

        // Create an empty directory that prune would normally remove
        Path emptyDir = Files.createDirectories(root.resolve("empty-dir"));

        // Snapshot original state
        boolean staleScriptExists = Files.exists(staleScript);
        boolean emptyDirExists = Files.isDirectory(emptyDir);
        assertTrue(staleScriptExists, "Stale script should exist before dry-run");
        assertTrue(emptyDirExists, "Empty dir should exist before dry-run");

        // Run with dry-run
        MaintainOptions opts = MaintainOptions.forDryRun();
        MaintainOrchestrator orchestrator = new MaintainOrchestrator(root, opts, config);
        MaintainResult result = orchestrator.run();

        assertEquals(11, result.phases().size());

        // Verify no filesystem changes
        assertTrue(Files.exists(staleScript),
                "Stale script should still exist after dry-run");
        assertTrue(Files.isDirectory(emptyDir),
                "Empty dir should still exist after dry-run");

        // Original files should be untouched
        assertTrue(Files.exists(root.resolve("README.md")),
                "README.md should still exist");
        assertTrue(Files.exists(root.resolve("notes.txt")),
                "notes.txt should still exist");
    }

    @Test
    void dry_run_all_phases_succeed() throws Exception {
        Path root = createMinimalWorkspace();
        SynthesisConfig config = loadConfig(root);

        MaintainOrchestrator orchestrator =
                new MaintainOrchestrator(root, MaintainOptions.forDryRun(), config);
        MaintainResult result = orchestrator.run();

        for (PhaseResult phase : result.phases()) {
            assertTrue(phase.succeeded(),
                    "Phase " + phase.phaseNumber() + " (" + phase.name()
                            + ") should succeed in dry-run: " + phase.error());
        }
    }

    // =========================================================================
    // --skip-downloads
    // =========================================================================

    @Test
    void skip_downloads_omits_phases_1_and_2() throws Exception {
        Path root = createMinimalWorkspace();
        SynthesisConfig config = loadConfig(root);

        MaintainOptions opts = new MaintainOptions(
                false, false, true, false, false, false, false, false, false);

        MaintainOrchestrator orchestrator = new MaintainOrchestrator(root, opts, config);
        MaintainResult result = orchestrator.run();

        assertEquals(11, result.phases().size());

        // Phase 1 should be skipped
        PhaseResult phase1 = result.phases().get(0);
        assertEquals("Ingest", phase1.name());
        assertTrue(phase1.succeeded());
        assertTrue(phase1.summary().contains("skipped"),
                "Phase 1 should be skipped: " + phase1.summary());
        assertEquals(0, phase1.changeCount());

        // Phase 2 should be skipped
        PhaseResult phase2 = result.phases().get(1);
        assertEquals("Route", phase2.name());
        assertTrue(phase2.succeeded());
        assertTrue(phase2.summary().contains("skipped"),
                "Phase 2 should be skipped: " + phase2.summary());
        assertEquals(0, phase2.changeCount());

        // Phase 3+ should still run
        PhaseResult phase3 = result.phases().get(2);
        assertEquals("Sync", phase3.name());
        assertFalse(phase3.summary().contains("--skip-downloads"),
                "Phase 3 should not mention --skip-downloads");
    }

    // =========================================================================
    // Staging phases (1+2) skip gracefully when staging not enabled
    // =========================================================================

    @Test
    void staging_phases_skip_when_not_enabled() throws Exception {
        Path root = createMinimalWorkspace();
        SynthesisConfig config = loadConfig(root);

        // Default config has staging.enabled = false
        assertFalse(config.getStaging().isEnabled(),
                "Default config should have staging disabled");

        MaintainOrchestrator orchestrator =
                new MaintainOrchestrator(root, MaintainOptions.defaults(), config);
        MaintainResult result = orchestrator.run();

        PhaseResult ingest = result.phases().get(0);
        assertTrue(ingest.succeeded());
        assertTrue(ingest.summary().contains("skipped"),
                "Ingest should be skipped when staging not enabled");

        PhaseResult route = result.phases().get(1);
        assertTrue(route.succeeded());
        assertTrue(route.summary().contains("skipped"),
                "Route should be skipped when staging not enabled");
    }

    // =========================================================================
    // Phase 3: Sync
    // =========================================================================

    @Test
    void sync_phase_runs_on_minimal_workspace() throws Exception {
        Path root = createMinimalWorkspace();
        SynthesisConfig config = loadConfig(root);

        MaintainOrchestrator orchestrator =
                new MaintainOrchestrator(root, MaintainOptions.defaults(), config);
        MaintainResult result = orchestrator.run();

        PhaseResult sync = result.phases().get(2);
        assertEquals("Sync", sync.name());
        assertTrue(sync.succeeded(),
                "Sync phase should succeed: " + sync.error());
    }

    // =========================================================================
    // Phase 4: Sweep
    // =========================================================================

    @Test
    void sweep_phase_finds_stale_root_files() throws Exception {
        Path root = createMinimalWorkspace();
        SynthesisConfig config = loadConfig(root);

        // Create a stale shell script at root
        Path script = root.resolve("old-deploy.sh");
        Files.writeString(script, "#!/bin/bash\necho deploy");
        Instant old = Instant.now().minus(60, ChronoUnit.DAYS);
        Files.setLastModifiedTime(script, FileTime.from(old));

        MaintainOrchestrator orchestrator =
                new MaintainOrchestrator(root, MaintainOptions.defaults(), config);
        MaintainResult result = orchestrator.run();

        PhaseResult sweep = result.phases().get(3);
        assertEquals("Sweep", sweep.name());
        assertTrue(sweep.succeeded(), "Sweep should succeed: " + sweep.error());
        assertTrue(sweep.changeCount() >= 1,
                "Sweep should find at least 1 stale file");
    }

    // =========================================================================
    // Phase 5: Rebalance
    // =========================================================================

    @Test
    void rebalance_phase_handles_no_archive_dir() throws Exception {
        Path root = createMinimalWorkspace();
        SynthesisConfig config = loadConfig(root);

        // No archive/ dir exists
        MaintainOrchestrator orchestrator =
                new MaintainOrchestrator(root, MaintainOptions.defaults(), config);
        MaintainResult result = orchestrator.run();

        PhaseResult rebalance = result.phases().get(4);
        assertEquals("Rebalance", rebalance.name());
        assertTrue(rebalance.succeeded());
        assertEquals(0, rebalance.changeCount());
    }

    // =========================================================================
    // Phase 6: Expire
    // =========================================================================

    @Test
    void expire_phase_succeeds_with_no_ttl_rules() throws Exception {
        Path root = createMinimalWorkspace();
        SynthesisConfig config = loadConfig(root);

        MaintainOrchestrator orchestrator =
                new MaintainOrchestrator(root, MaintainOptions.defaults(), config);
        MaintainResult result = orchestrator.run();

        PhaseResult expire = result.phases().get(5);
        assertEquals("Expire", expire.name());
        assertTrue(expire.succeeded());
        assertTrue(expire.summary().contains("no TTL rules"),
                "Expected 'no TTL rules' in summary: " + expire.summary());
    }

    // =========================================================================
    // Phase 7: Index
    // =========================================================================

    @Test
    void index_phase_runs_full_scan_on_fresh_workspace() throws Exception {
        Path root = createWorkspaceWithFiles();
        SynthesisConfig config = loadConfig(root);

        MaintainOrchestrator orchestrator =
                new MaintainOrchestrator(root, MaintainOptions.defaults(), config);
        MaintainResult result = orchestrator.run();

        PhaseResult index = result.phases().get(6);
        assertEquals("Index", index.name());
        assertTrue(index.succeeded(),
                "Index should succeed: " + index.error());
        assertTrue(index.changeCount() >= 1,
                "Index should have indexed at least 1 file, got: " + index.changeCount());
        assertTrue(index.summary().contains("full scan"),
                "Should indicate full scan: " + index.summary());
    }

    // =========================================================================
    // Phase 9: Prune
    // =========================================================================

    @Test
    void prune_phase_removes_empty_directories() throws Exception {
        Path root = createMinimalWorkspace();
        SynthesisConfig config = loadConfig(root);

        // Create empty directories
        Path emptyA = Files.createDirectories(root.resolve("empty-a"));
        Path emptyB = Files.createDirectories(root.resolve("empty-b"));
        assertTrue(Files.isDirectory(emptyA));
        assertTrue(Files.isDirectory(emptyB));

        MaintainOrchestrator orchestrator =
                new MaintainOrchestrator(root, MaintainOptions.defaults(), config);
        MaintainResult result = orchestrator.run();

        PhaseResult prune = result.phases().get(8);
        assertEquals("Prune", prune.name());
        assertTrue(prune.succeeded(), "Prune should succeed: " + prune.error());
        assertTrue(prune.changeCount() >= 2,
                "Prune should remove at least 2 empty dirs, got: " + prune.changeCount());
    }

    @Test
    void prune_dry_run_does_not_delete() throws Exception {
        Path root = createMinimalWorkspace();
        SynthesisConfig config = loadConfig(root);

        Path emptyDir = Files.createDirectories(root.resolve("empty-dir"));

        MaintainOrchestrator orchestrator =
                new MaintainOrchestrator(root, MaintainOptions.forDryRun(), config);
        MaintainResult result = orchestrator.run();

        PhaseResult prune = result.phases().get(8);
        assertTrue(prune.succeeded());
        assertTrue(prune.summary().contains("would be removed"),
                "Dry-run prune should say 'would be removed': " + prune.summary());

        // Directory should still exist
        assertTrue(Files.isDirectory(emptyDir),
                "Empty dir should still exist after dry-run prune");
    }

    // =========================================================================
    // Phase 11: Security output format
    // =========================================================================

    @Test
    void security_phase_produces_severity_breakdown_or_no_findings() throws Exception {
        Path root = createMinimalWorkspace();
        SynthesisConfig config = loadConfig(root);

        MaintainOrchestrator orchestrator =
                new MaintainOrchestrator(root, MaintainOptions.defaults(), config);
        MaintainResult result = orchestrator.run();

        PhaseResult security = result.phases().get(10);
        assertEquals("Security", security.name());
        assertTrue(security.succeeded(),
                "Security phase should succeed: " + security.error());

        // On a minimal workspace with no Java files, security is skipped
        // On a workspace with Java files, it should show severity counts or "no findings"
        String summary = security.summary();
        assertTrue(
                summary.contains("no findings")
                        || summary.contains("no code files")
                        || summary.contains("HIGH")
                        || summary.contains("files scanned"),
                "Security summary should show counts or 'no findings', got: " + summary);
    }

    @Test
    void security_phase_with_java_files_shows_files_scanned() throws Exception {
        Path root = createMinimalWorkspace();
        // Add a Java file so security analysis actually runs
        Path srcDir = Files.createDirectories(root.resolve("src"));
        Files.writeString(srcDir.resolve("Main.java"),
                "package test;\npublic class Main {\n    public static void main(String[] args) {}\n}\n");

        SynthesisConfig config = loadConfig(root);

        MaintainOrchestrator orchestrator =
                new MaintainOrchestrator(root, MaintainOptions.defaults(), config);
        MaintainResult result = orchestrator.run();

        PhaseResult security = result.phases().get(10);
        assertEquals("Security", security.name());
        assertTrue(security.succeeded(),
                "Security phase should succeed: " + security.error());

        String summary = security.summary();
        // Should either show "no findings" or severity counts with files scanned
        assertTrue(
                summary.contains("no findings") || summary.contains("files scanned"),
                "Security summary should mention 'no findings' or 'files scanned', got: " + summary);
    }

    // =========================================================================
    // MaintainResult aggregate methods
    // =========================================================================

    @Test
    void totalChanges_sums_across_phases() {
        List<PhaseResult> phases = List.of(
                PhaseResult.success(1, "A", 3, "3 items", List.of()),
                PhaseResult.success(2, "B", 0, "no items", List.of()),
                PhaseResult.success(3, "C", 5, "5 items", List.of()));

        MaintainResult result = new MaintainResult(phases, 100);

        assertEquals(8, result.totalChanges());
    }

    @Test
    void allSucceeded_returns_false_when_any_phase_fails() {
        List<PhaseResult> phases = List.of(
                PhaseResult.success(1, "A", 0, "ok", List.of()),
                PhaseResult.failed(2, "B", "boom"),
                PhaseResult.success(3, "C", 0, "ok", List.of()));

        MaintainResult result = new MaintainResult(phases, 100);

        assertFalse(result.allSucceeded());
    }

    @Test
    void allSucceeded_returns_true_when_all_phases_succeed() {
        List<PhaseResult> phases = List.of(
                PhaseResult.success(1, "A", 0, "ok", List.of()),
                PhaseResult.skipped(2, "B", "reason"),
                PhaseResult.success(3, "C", 0, "ok", List.of()));

        MaintainResult result = new MaintainResult(phases, 100);

        assertTrue(result.allSucceeded());
    }

    // =========================================================================
    // PhaseResult factory methods
    // =========================================================================

    @Test
    void phaseResult_success_has_no_error() {
        PhaseResult r = PhaseResult.success(1, "Test", 5, "5 items", List.of("a", "b"));
        assertTrue(r.succeeded());
        assertNull(r.error());
        assertEquals(5, r.changeCount());
        assertEquals("5 items", r.summary());
        assertEquals(2, r.details().size());
    }

    @Test
    void phaseResult_skipped_is_success_with_zero_changes() {
        PhaseResult r = PhaseResult.skipped(2, "Test", "not needed");
        assertTrue(r.succeeded());
        assertEquals(0, r.changeCount());
        assertTrue(r.summary().contains("skipped"));
        assertTrue(r.summary().contains("not needed"));
    }

    @Test
    void phaseResult_failed_has_error() {
        PhaseResult r = PhaseResult.failed(3, "Test", "something broke");
        assertFalse(r.succeeded());
        assertEquals("something broke", r.error());
        assertEquals("failed", r.summary());
    }

    // =========================================================================
    // MaintainOptions factories
    // =========================================================================

    @Test
    void maintainOptions_defaults_all_false() {
        MaintainOptions opts = MaintainOptions.defaults();
        assertFalse(opts.dryRun());
        assertFalse(opts.verbose());
        assertFalse(opts.skipDownloads());
        assertFalse(opts.skipGit());
        assertFalse(opts.quiet());
        assertFalse(opts.json());
        assertFalse(opts.updateActivityLog());
        assertFalse(opts.sync());
        assertFalse(opts.rebalance());
    }

    @Test
    void maintainOptions_forDryRun_has_dryRun_true() {
        MaintainOptions opts = MaintainOptions.forDryRun();
        assertTrue(opts.dryRun());
        assertFalse(opts.verbose());
        assertFalse(opts.skipDownloads());
    }

    // =========================================================================
    // WorkspaceFixture integration
    // =========================================================================

    @Test
    void orchestrator_works_with_workspace_fixture() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .workspaceName("fixture-test")
                .rootFile("notes.md", "# Notes\nSome content")
                .build();

        SynthesisConfig config = loadConfig(fixture.getRoot());

        MaintainOrchestrator orchestrator =
                new MaintainOrchestrator(fixture.getRoot(), MaintainOptions.defaults(), config);
        MaintainResult result = orchestrator.run();

        assertEquals(11, result.phases().size());
        // All phases should at least succeed or be skipped
        for (PhaseResult phase : result.phases()) {
            assertNotNull(phase.summary(),
                    "Phase " + phase.phaseNumber() + " summary should not be null");
        }
    }
}
