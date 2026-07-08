package io.exoreaction.synthesis.kcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for plan_context session dedup — the {@code known} parameter that lets
 * callers declare units they already hold (id → sha256), so unchanged units
 * are returned as compact stubs instead of full plan entries.
 *
 * <p>Mirrors kcp-agent's {@code dedupeLoaded()} pattern: exact sha256 match →
 * unchanged stub; sha drift or unknown id → full entry.
 */
class PlanContextSessionDedupTest {

    private static final String TODAY = "2026-07-08";

    @TempDir
    Path tempDir;

    // --- Planned record carries sha256 ---

    @Test
    void planned_includesSha256FromUnit() {
        var unit = unitWithHash("auth-guide", "docs/auth.md", "auth", "[\"auth\"]",
                "sha256", "abc123def456");
        var plan = KcpPlanner.plan("auth", List.of(cand(unit)), TODAY, 0);

        assertEquals(1, plan.units().size());
        assertEquals("abc123def456", plan.units().get(0).sha256(),
                "Planned record should carry the unit's content hash value");
    }

    @Test
    void planned_sha256IsNullWhenUnitHasNoHash() {
        var unit = unitWithHash("guide", "docs/guide.md", "auth guide", "[\"auth\"]",
                null, null);
        var plan = KcpPlanner.plan("auth", List.of(cand(unit)), TODAY, 0);

        assertEquals(1, plan.units().size());
        assertNull(plan.units().get(0).sha256(),
                "sha256 should be null when unit has no content hash");
    }

    // --- Dedup: unchanged units become stubs ---

    @Test
    void dedup_unchangedUnitBecomesStub() {
        var unit = unitWithHash("auth-guide", "docs/auth.md", "auth", "[\"auth\"]",
                "sha256", "abc123");
        var plan = KcpPlanner.plan("auth", List.of(cand(unit)), TODAY, 0);

        // Caller declares they already have auth-guide at sha abc123
        var known = Map.of("auth-guide", "abc123");
        var result = KcpPlanner.dedup(plan, known);

        assertEquals(0, result.units().size(), "Unchanged unit should not appear in units");
        assertEquals(1, result.unchanged().size(), "Should have one unchanged stub");
        assertEquals("auth-guide", result.unchanged().get(0).unitId());
        assertTrue(result.unchanged().get(0).note().contains("unchanged"),
                "Stub should mention 'unchanged'");
    }

    @Test
    void dedup_changedShaServesFullEntry() {
        var unit = unitWithHash("auth-guide", "docs/auth.md", "auth", "[\"auth\"]",
                "sha256", "new_hash_789");
        var plan = KcpPlanner.plan("auth", List.of(cand(unit)), TODAY, 0);

        // Caller has stale hash
        var known = Map.of("auth-guide", "old_hash_123");
        var result = KcpPlanner.dedup(plan, known);

        assertEquals(1, result.units().size(), "Changed unit should be served in full");
        assertEquals(0, result.unchanged().size(), "No unchanged stubs");
        assertEquals("auth-guide", result.units().get(0).unitId());
    }

    @Test
    void dedup_unknownIdIsIgnored() {
        var unit = unitWithHash("auth-guide", "docs/auth.md", "auth", "[\"auth\"]",
                "sha256", "abc123");
        var plan = KcpPlanner.plan("auth", List.of(cand(unit)), TODAY, 0);

        // Caller claims a unit not in the plan
        var known = Map.of("unknown-unit", "xyz789");
        var result = KcpPlanner.dedup(plan, known);

        assertEquals(1, result.units().size(), "Known ID not in plan doesn't affect results");
        assertEquals(0, result.unchanged().size());
    }

    @Test
    void dedup_unitWithoutHashIsAlwaysServed() {
        var unit = unitWithHash("guide", "docs/guide.md", "auth", "[\"auth\"]",
                null, null);
        var plan = KcpPlanner.plan("auth", List.of(cand(unit)), TODAY, 0);

        // Even if caller claims the unit, no hash means we can't verify → full serve
        var known = Map.of("guide", "some_hash");
        var result = KcpPlanner.dedup(plan, known);

        assertEquals(1, result.units().size(), "Unit without hash can't be deduped");
        assertEquals(0, result.unchanged().size());
    }

    @Test
    void dedup_emptyKnownServesAll() {
        var unit = unitWithHash("auth-guide", "docs/auth.md", "auth", "[\"auth\"]",
                "sha256", "abc123");
        var plan = KcpPlanner.plan("auth", List.of(cand(unit)), TODAY, 0);

        var result = KcpPlanner.dedup(plan, Map.of());

        assertEquals(1, result.units().size());
        assertEquals(0, result.unchanged().size());
    }

    @Test
    void dedup_nullKnownServesAll() {
        var unit = unitWithHash("auth-guide", "docs/auth.md", "auth", "[\"auth\"]",
                "sha256", "abc123");
        var plan = KcpPlanner.plan("auth", List.of(cand(unit)), TODAY, 0);

        var result = KcpPlanner.dedup(plan, null);

        assertEquals(1, result.units().size());
        assertEquals(0, result.unchanged().size());
    }

    // --- Token savings ---

    @Test
    void dedup_tracksTokensSaved() throws Exception {
        // Create real files so token estimates are non-zero
        java.nio.file.Files.createDirectories(tempDir.resolve("docs"));
        java.nio.file.Files.writeString(tempDir.resolve("docs/auth.md"), "x".repeat(400));
        java.nio.file.Files.writeString(tempDir.resolve("docs/api.md"), "y".repeat(400));

        var unit1 = unitWithHash("auth", "docs/auth.md", "auth docs", "[\"auth\"]",
                "sha256", "hash1");
        var unit2 = unitWithHash("api", "docs/api.md", "auth api", "[\"auth\"]",
                "sha256", "hash2");
        var plan = KcpPlanner.plan("auth", List.of(cand(unit1), cand(unit2)), TODAY, 0);

        // Caller already has unit1 at the right hash
        var known = Map.of("auth", "hash1");
        var result = KcpPlanner.dedup(plan, known);

        assertEquals(1, result.units().size(), "One unit served (api)");
        assertEquals(1, result.unchanged().size(), "One unchanged (auth)");
        assertTrue(result.tokensSaved() > 0, "Should report token savings from deduped unit");
    }

    // --- Mixed scenario ---

    @Test
    void dedup_mixedScenario_partialDedup() {
        var unchanged = unitWithHash("auth", "docs/auth.md", "auth docs", "[\"auth\"]",
                "sha256", "hash_a");
        var changed = unitWithHash("api", "docs/api.md", "auth api ref", "[\"auth\"]",
                "sha256", "new_hash_b");
        var noHash = unitWithHash("guide", "docs/guide.md", "auth intro", "[\"auth\"]",
                null, null);

        var plan = KcpPlanner.plan("auth",
                List.of(cand(unchanged), cand(changed), cand(noHash)), TODAY, 0);

        var known = Map.of("auth", "hash_a", "api", "old_hash_b");
        var result = KcpPlanner.dedup(plan, known);

        assertEquals(2, result.units().size(), "Changed + no-hash served");
        assertEquals(1, result.unchanged().size(), "Only the unchanged stub");
        assertEquals("auth", result.unchanged().get(0).unitId());
    }

    // --- Skipped units not affected ---

    @Test
    void dedup_skippedUnitsPassedThrough() {
        var matched = unitWithHash("auth", "docs/auth.md", "auth", "[\"auth\"]",
                "sha256", "h1");
        var unmatched = unitWithHash("db", "docs/db.md", "database ops", "[\"database\"]",
                "sha256", "h2");

        var plan = KcpPlanner.plan("auth",
                List.of(cand(matched), cand(unmatched)), TODAY, 0);

        var known = Map.of("auth", "h1");
        var result = KcpPlanner.dedup(plan, known);

        // Unmatched unit should still be in skipped (not affected by dedup)
        assertFalse(result.skipped().isEmpty(), "Skipped units should pass through unchanged");
        assertEquals(plan.skipped().size(), result.skipped().size());
    }

    // --- DedupResult preserves plan metadata ---

    @Test
    void dedup_preservesTaskAndTotalTokenEstimate() {
        var unit = unitWithHash("auth", "docs/auth.md", "auth", "[\"auth\"]",
                "sha256", "h1");
        var plan = KcpPlanner.plan("auth", List.of(cand(unit)), TODAY, 0);

        var result = KcpPlanner.dedup(plan, Map.of("auth", "h1"));

        assertEquals("auth", result.task());
        assertEquals(plan.totalTokenEstimate(), result.totalTokenEstimate(),
                "Total token estimate should reflect the full plan (not deduped)");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private KcpPlanner.Candidate cand(KcpRepository.KcpUnitRow u) {
        return new KcpPlanner.Candidate(u, tempDir.resolve("knowledge.yaml").toString(), tempDir);
    }

    private KcpRepository.KcpUnitRow unitWithHash(String id, String path, String intent,
                                                   String triggersJson, String hashAlg,
                                                   String hashValue) {
        return new KcpRepository.KcpUnitRow(
                id, path, intent, "module", null, triggersJson, null,
                null, null, null, null,
                hashAlg, hashValue,
                null, false, null, null,
                null, -1.0, null, null, null);
    }
}
