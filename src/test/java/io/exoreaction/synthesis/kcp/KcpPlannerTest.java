package io.exoreaction.synthesis.kcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KcpPlanner} — deterministic RFC-0007 read planning (issue #359).
 */
class KcpPlannerTest {

    private static final String TODAY = "2026-07-06";

    @TempDir
    Path tempDir;

    @Test
    void triggerMatchOutranksIntentMatch() {
        // "api" is a trigger on one unit, only intent text on another; ids/paths
        // deliberately avoid the query term so scoring isolates trigger vs intent.
        var triggerUnit = unit("reference", "docs/reference.md",
                "How do endpoints behave", "[\"api\", \"rest\"]", null, null);
        var intentUnit = unit("guide", "docs/guide.md",
                "Explains the api at a high level", "[\"onboarding\"]", null, null);

        var plan = KcpPlanner.plan("api", List.of(cand(intentUnit), cand(triggerUnit)), TODAY, 0);

        assertEquals(2, plan.units().size());
        assertEquals("reference", plan.units().get(0).unitId(), "Trigger match (5) beats intent match (3)");
        assertEquals(5, plan.units().get(0).score());
        assertEquals(3, plan.units().get(1).score());
    }

    @Test
    void unmatchedUnitsAreSkippedWithReason() {
        var plan = KcpPlanner.plan("kubernetes",
                List.of(cand(unit("api", "api.md", "REST endpoints", "[\"api\"]", null, null))),
                TODAY, 0);
        assertTrue(plan.units().isEmpty());
        assertEquals(1, plan.skipped().size());
        assertTrue(plan.skipped().get(0).reason().contains("no query-term match"));
    }

    @Test
    void expiredUnitsAreSkipped() {
        var plan = KcpPlanner.plan("api",
                List.of(cand(unit("old", "old.md", "old api", "[\"api\"]", "2020-01-01", null))),
                TODAY, 0);
        assertTrue(plan.units().isEmpty());
        assertEquals(1, plan.skipped().size());
        assertTrue(plan.skipped().get(0).reason().contains("expired"));
    }

    @Test
    void supersededUnitsAreSkipped() {
        var plan = KcpPlanner.plan("api",
                List.of(cand(unit("v1", "v1.md", "api v1", "[\"api\"]", null, "v2"))),
                TODAY, 0);
        assertTrue(plan.units().isEmpty());
        assertTrue(plan.skipped().get(0).reason().contains("superseded by v2"));
    }

    @Test
    void tokenBudgetGreedilyCapsThePlan() throws Exception {
        // Two matching units with real files; budget admits only the top-scored one
        Files.writeString(tempDir.resolve("big.md"), "x".repeat(4000));   // ~1000 tokens
        Files.writeString(tempDir.resolve("small.md"), "y".repeat(400));  // ~100 tokens
        var strong = unit("strong", "big.md", "api endpoints rest", "[\"api\", \"rest\"]", null, null);
        var weak = unit("weak", "small.md", "mentions api", "[\"onboarding\"]", null, null);

        var plan = KcpPlanner.plan("api rest", List.of(cand(weak), cand(strong)), TODAY, 500);

        // strong scores higher (2 triggers = 10) and is admitted first even though its
        // ~1000-token cost exceeds the budget (the first unit is always admitted);
        // weak then exceeds the remaining budget and is skipped as over_budget.
        assertEquals(1, plan.units().size());
        assertEquals("strong", plan.units().get(0).unitId());
        assertTrue(plan.skipped().stream().anyMatch(s -> s.reason().contains("over_budget")));
    }

    @Test
    void planIsDeterministicAcrossEqualScores() {
        var a = unit("bbb", "b.md", "", "[\"api\"]", null, null);
        var b = unit("aaa", "a.md", "", "[\"api\"]", null, null);
        var plan = KcpPlanner.plan("api", List.of(cand(a), cand(b)), TODAY, 0);
        // Equal scores → tie-break by unit id ascending
        assertEquals("aaa", plan.units().get(0).unitId());
        assertEquals("bbb", plan.units().get(1).unitId());
    }

    @Test
    void tokenizerDropsSingleChars() {
        assertEquals(java.util.Set.of("api", "rest"), KcpPlanner.tokenize("a API, REST!"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private KcpPlanner.Candidate cand(KcpRepository.KcpUnitRow u) {
        return new KcpPlanner.Candidate(u, tempDir.resolve("knowledge.yaml").toString(), tempDir);
    }

    private KcpRepository.KcpUnitRow unit(String id, String path, String intent, String triggersJson,
                                          String validUntil, String supersededBy) {
        return new KcpRepository.KcpUnitRow(
                id, path, intent, "module", null, triggersJson, null,
                null, validUntil, null, supersededBy,
                null, null, null, false, null, null,
                null, -1.0, null, null, null);
    }
}
