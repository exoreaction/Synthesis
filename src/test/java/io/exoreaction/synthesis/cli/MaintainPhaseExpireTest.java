package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.integration.WorkspaceFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 6 (Expire) of the {@link MaintainOrchestrator}.
 *
 * <p>Phase 6 uses file modification time (not rule creation date) to decide
 * which files are archived. A file matching a TTL rule pattern is expired when
 * its {@code lastModified} age exceeds {@code rule.days()}.
 *
 * @see MaintainOrchestrator#findExpiredByFileAge
 * @since v1.9.9 (issue #189)
 */
class MaintainPhaseExpireTest {

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
                "workspace:\n  name: \"expire-test\"\n");
    }

    private PhaseResult getExpirePhase(MaintainResult result) {
        return result.phases().stream()
                .filter(p -> p.name().equals("Expire"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expire phase not found in result"));
    }

    // =========================================================================
    // Core behaviour: file-age semantics
    // =========================================================================

    @Test
    void old_file_matching_ttl_pattern_is_archived() throws Exception {
        // Use ageDays(15) so sweep (threshold=30d) does NOT touch it, but TTL=7d DOES expire it
        // ageDays(3) is below the TTL threshold, so it stays
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .withTtlRule("ephemeral", List.of("TONIGHT-*.md"), 7)
                .rootFile("TONIGHT-OLD.md", "old plan", WorkspaceFixture.ageDays(15))
                .rootFile("TONIGHT-NEW.md", "new plan", WorkspaceFixture.ageDays(3))
                .build();

        SynthesisConfig config = loadConfig(tempDir);
        MaintainOrchestrator orch = new MaintainOrchestrator(
                tempDir, MaintainOptions.defaults(), config);
        MaintainResult result = orch.run();

        PhaseResult expirePhase = getExpirePhase(result);
        assertTrue(expirePhase.succeeded(), "Expire phase should succeed: " + expirePhase.error());
        assertEquals(1, expirePhase.changeCount(),
                "Only the old file (15d, TTL=7d) should be archived; new file (3d) should stay");

        // Old file is in archive/expired-{date}/
        fixture.assertFileInArchive("TONIGHT-OLD.md");

        // New file stays at root (only 3 days old, TTL is 7 days)
        fixture.assertFileExists("TONIGHT-NEW.md");
    }

    @Test
    void expired_files_land_in_archive_expired_subfolder() throws Exception {
        // Use ageDays(15): below sweep threshold (30d), above TTL (7d) -> expires, not swept
        WorkspaceFixture.builder(tempDir)
                .withTtlRule("ephemeral", List.of("TONIGHT-*.md"), 7)
                .rootFile("TONIGHT-OLD.md", "old plan", WorkspaceFixture.ageDays(15))
                .build();

        SynthesisConfig config = loadConfig(tempDir);
        new MaintainOrchestrator(tempDir, MaintainOptions.defaults(), config).run();

        // Must be in archive/expired-{date}/, NOT archive/swept-{date}/
        String today = LocalDate.now().toString();
        assertTrue(Files.exists(tempDir.resolve("archive/expired-" + today + "/TONIGHT-OLD.md")),
                "Expired file should be in archive/expired-" + today + "/");
        assertFalse(Files.exists(tempDir.resolve("archive/swept-" + today + "/TONIGHT-OLD.md")),
                "Expired file must NOT appear in swept/ subdirectory");
    }

    @Test
    void new_file_matching_pattern_but_below_age_threshold_stays() throws Exception {
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .withTtlRule("ephemeral", List.of("TONIGHT-*.md"), 7)
                .rootFile("TONIGHT-FRESH.md", "tonight plan", WorkspaceFixture.ageDays(1))
                .build();

        SynthesisConfig config = loadConfig(tempDir);
        MaintainOrchestrator orch = new MaintainOrchestrator(
                tempDir, MaintainOptions.defaults(), config);
        MaintainResult result = orch.run();

        PhaseResult expirePhase = getExpirePhase(result);
        assertEquals(0, expirePhase.changeCount(),
                "File is only 1 day old (TTL=7), should not be archived");
        fixture.assertFileExists("TONIGHT-FRESH.md");
    }

    // =========================================================================
    // Dry-run semantics
    // =========================================================================

    @Test
    void dry_run_does_not_move_files() throws Exception {
        // Use ageDays(15): below sweep threshold (30d), above TTL (7d) -> dry-run expire detects it
        WorkspaceFixture fixture = WorkspaceFixture.builder(tempDir)
                .withTtlRule("ephemeral", List.of("TONIGHT-*.md"), 7)
                .rootFile("TONIGHT-OLD.md", "old plan", WorkspaceFixture.ageDays(15))
                .build();

        SynthesisConfig config = loadConfig(tempDir);
        new MaintainOrchestrator(tempDir, MaintainOptions.forDryRun(), config).run();

        // File must still be at workspace root after dry-run
        fixture.assertFileExists("TONIGHT-OLD.md");
        // No archive directory created
        assertFalse(Files.isDirectory(tempDir.resolve("archive")),
                "archive/ should not be created during dry-run");
    }

    @Test
    void dry_run_expire_phase_reports_would_be_archived() throws Exception {
        // Use ageDays(15): below sweep threshold (30d), above TTL (7d) -> dry-run expire detects it
        WorkspaceFixture.builder(tempDir)
                .withTtlRule("ephemeral", List.of("TONIGHT-*.md"), 7)
                .rootFile("TONIGHT-OLD.md", "old plan", WorkspaceFixture.ageDays(15))
                .build();

        SynthesisConfig config = loadConfig(tempDir);
        MaintainResult result = new MaintainOrchestrator(
                tempDir, MaintainOptions.forDryRun(), config).run();

        PhaseResult expirePhase = getExpirePhase(result);
        assertTrue(expirePhase.succeeded(), "Dry-run expire should succeed");
        assertEquals(1, expirePhase.changeCount(),
                "Should report 1 file would be archived");
        assertTrue(expirePhase.summary().contains("would be archived"),
                "Summary should mention 'would be archived': " + expirePhase.summary());
        // Detail lines should have [would] prefix
        assertFalse(expirePhase.details().isEmpty(), "Should have detail lines in dry-run");
        assertTrue(expirePhase.details().get(0).startsWith("[would]"),
                "Detail line should start with [would]: " + expirePhase.details().get(0));
    }

    // =========================================================================
    // No TTL rules defined
    // =========================================================================

    @Test
    void no_ttl_rules_phase_completes_cleanly_with_zero_changes() throws Exception {
        WorkspaceFixture.builder(tempDir)
                .rootFile("any-file.md", "content")
                .build();

        SynthesisConfig config = loadConfig(tempDir);
        MaintainResult result = new MaintainOrchestrator(
                tempDir, MaintainOptions.defaults(), config).run();

        PhaseResult expirePhase = getExpirePhase(result);
        assertTrue(expirePhase.succeeded(), "Expire phase should succeed when no TTL rules");
        assertEquals(0, expirePhase.changeCount());
        assertTrue(expirePhase.summary().contains("no TTL rules"),
                "Summary should indicate no rules: " + expirePhase.summary());
    }

    // =========================================================================
    // findExpiredByFileAge unit tests
    // =========================================================================

    @Test
    void findExpiredByFileAge_returns_only_old_matching_files() throws Exception {
        setupMinimalWorkspace(tempDir);

        // Write TTL rules YAML manually for direct unit test
        Path synthDir = tempDir.resolve(".synthesis");
        Files.writeString(synthDir.resolve("ttl-rules.yaml"),
                "rules:\n" +
                "- pattern: \"TONIGHT-*.md\"\n" +
                "  days: 7\n" +
                "  createdAt: \"" + LocalDate.now().minusDays(30) + "\"\n");

        // Old file matches pattern + exceeds age
        Path oldFile = tempDir.resolve("TONIGHT-OLD.md");
        Files.writeString(oldFile, "old");
        java.nio.file.Files.setLastModifiedTime(oldFile,
                java.nio.file.attribute.FileTime.from(
                        java.time.Instant.now().minus(45, java.time.temporal.ChronoUnit.DAYS)));

        // New file matches pattern but too young
        Path newFile = tempDir.resolve("TONIGHT-NEW.md");
        Files.writeString(newFile, "new");

        // Non-matching file, old
        Path otherFile = tempDir.resolve("OTHER-OLD.txt");
        Files.writeString(otherFile, "other");
        java.nio.file.Files.setLastModifiedTime(otherFile,
                java.nio.file.attribute.FileTime.from(
                        java.time.Instant.now().minus(45, java.time.temporal.ChronoUnit.DAYS)));

        List<TtlCommand.TtlRule> rules = TtlCommand.loadRules(tempDir);
        List<Path> expired = MaintainOrchestrator.findExpiredByFileAge(tempDir, rules);

        assertEquals(1, expired.size(), "Only the old matching file should be returned");
        assertEquals("TONIGHT-OLD.md", expired.get(0).getFileName().toString());
    }

    @Test
    void findExpiredByFileAge_returns_empty_when_all_files_are_fresh() throws Exception {
        setupMinimalWorkspace(tempDir);

        Path synthDir = tempDir.resolve(".synthesis");
        Files.writeString(synthDir.resolve("ttl-rules.yaml"),
                "rules:\n" +
                "- pattern: \"TONIGHT-*.md\"\n" +
                "  days: 7\n" +
                "  createdAt: \"" + LocalDate.now().minusDays(30) + "\"\n");

        // Fresh file — should not expire
        Files.writeString(tempDir.resolve("TONIGHT-FRESH.md"), "fresh");

        List<TtlCommand.TtlRule> rules = TtlCommand.loadRules(tempDir);
        List<Path> expired = MaintainOrchestrator.findExpiredByFileAge(tempDir, rules);

        assertTrue(expired.isEmpty(), "No expired files expected when all files are fresh");
    }

    @Test
    void findExpiredByFileAge_sorted_by_filename() throws Exception {
        setupMinimalWorkspace(tempDir);

        Path synthDir = tempDir.resolve(".synthesis");
        Files.writeString(synthDir.resolve("ttl-rules.yaml"),
                "rules:\n" +
                "- pattern: \"TONIGHT-*.md\"\n" +
                "  days: 7\n" +
                "  createdAt: \"" + LocalDate.now().minusDays(30) + "\"\n");

        java.nio.file.attribute.FileTime oldTime = java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(45, java.time.temporal.ChronoUnit.DAYS));

        Path fileZ = tempDir.resolve("TONIGHT-Z.md");
        Files.writeString(fileZ, "z");
        Files.setLastModifiedTime(fileZ, oldTime);

        Path fileA = tempDir.resolve("TONIGHT-A.md");
        Files.writeString(fileA, "a");
        Files.setLastModifiedTime(fileA, oldTime);

        List<TtlCommand.TtlRule> rules = TtlCommand.loadRules(tempDir);
        List<Path> expired = MaintainOrchestrator.findExpiredByFileAge(tempDir, rules);

        assertEquals(2, expired.size());
        assertEquals("TONIGHT-A.md", expired.get(0).getFileName().toString(),
                "Results should be sorted by filename");
        assertEquals("TONIGHT-Z.md", expired.get(1).getFileName().toString());
    }
}
