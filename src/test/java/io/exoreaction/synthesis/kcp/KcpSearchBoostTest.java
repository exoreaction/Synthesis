package io.exoreaction.synthesis.kcp;

import io.exoreaction.synthesis.index.SearchResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for KCP trigger-based search result boosting (#371 item 5).
 *
 * <p>When a workspace has indexed knowledge.yaml manifests, search results
 * whose paths match KCP units with query-matching triggers should be
 * boosted in rank. This applies the RFC-0007 trigger scoring to post-search
 * re-ranking without modifying the Lucene index schema.
 */
class KcpSearchBoostTest {

    // --- Trigger score map building ---

    @Test
    void buildTriggerScores_matchesQueryToTriggers() {
        var unit = unit("auth-guide", "docs/auth.md", "[\"authentication\", \"oauth\", \"security\"]");
        var scores = KcpPlanner.buildTriggerScores("authentication security", List.of(unit));

        assertTrue(scores.containsKey("docs/auth.md"));
        assertEquals(10, scores.get("docs/auth.md"),
                "Two trigger matches × 5 weight = 10");
    }

    @Test
    void buildTriggerScores_noMatchReturnsEmpty() {
        var unit = unit("api-ref", "docs/api.md", "[\"api\", \"rest\"]");
        var scores = KcpPlanner.buildTriggerScores("authentication security", List.of(unit));

        assertTrue(scores.isEmpty(), "No trigger overlap → no score entry");
    }

    @Test
    void buildTriggerScores_multipleUnitsWithSamePath() {
        // Two manifests declare units pointing to the same file
        var unit1 = unit("guide-a", "docs/auth.md", "[\"authentication\"]");
        var unit2 = unit("guide-b", "docs/auth.md", "[\"security\", \"oauth\"]");
        var scores = KcpPlanner.buildTriggerScores("authentication security",
                List.of(unit1, unit2));

        // Should take the max score, not sum
        assertTrue(scores.containsKey("docs/auth.md"));
        assertTrue(scores.get("docs/auth.md") >= 5,
                "Should have at least one trigger match");
    }

    @Test
    void buildTriggerScores_includesIntentMatches() {
        var unit = unit("guide", "docs/guide.md", "[\"onboarding\"]",
                "How to set up authentication");
        var scores = KcpPlanner.buildTriggerScores("authentication", List.of(unit));

        assertTrue(scores.containsKey("docs/guide.md"),
                "Intent match should contribute to score");
    }

    @Test
    void buildTriggerScores_nullTriggersHandled() {
        var unit = unit("plain", "docs/plain.md", null);
        var scores = KcpPlanner.buildTriggerScores("anything", List.of(unit));
        assertTrue(scores.isEmpty());
    }

    // --- Search result re-ranking ---

    @Test
    void boostResults_reranksMatchingResults() {
        var result1 = result("docs/unrelated.md", 5.0f);
        var result2 = result("docs/auth.md", 3.0f);       // lower Lucene score
        var result3 = result("docs/api.md", 4.0f);

        var unit = unit("auth-guide", "docs/auth.md", "[\"authentication\", \"security\"]");
        var triggerScores = KcpPlanner.buildTriggerScores("authentication security",
                List.of(unit));

        var boosted = KcpPlanner.boostResults(
                List.of(result1, result2, result3), triggerScores);

        // auth.md should be boosted above unrelated.md
        assertEquals("docs/auth.md", boosted.get(0).relativePath(),
                "KCP-matched result should be ranked first");
    }

    @Test
    void boostResults_preservesOrderWhenNoBoost() {
        var result1 = result("docs/a.md", 5.0f);
        var result2 = result("docs/b.md", 3.0f);

        var boosted = KcpPlanner.boostResults(List.of(result1, result2), Map.of());

        assertEquals("docs/a.md", boosted.get(0).relativePath());
        assertEquals("docs/b.md", boosted.get(1).relativePath());
    }

    @Test
    void boostResults_emptyResultsReturnsEmpty() {
        var boosted = KcpPlanner.boostResults(List.of(), Map.of("x", 10));
        assertTrue(boosted.isEmpty());
    }

    @Test
    void boostResults_nullScoresReturnsOriginalOrder() {
        var result1 = result("docs/a.md", 5.0f);
        var boosted = KcpPlanner.boostResults(List.of(result1), null);

        assertEquals(1, boosted.size());
        assertEquals("docs/a.md", boosted.get(0).relativePath());
    }

    @Test
    void boostResults_boostedScoreIsAdditive() {
        var result = result("docs/auth.md", 3.0f);
        var triggerScores = Map.of("docs/auth.md", 10);

        var boosted = KcpPlanner.boostResults(List.of(result), triggerScores);

        assertTrue(boosted.get(0).score() > 3.0f,
                "Boosted score should be higher than original");
    }

    @Test
    void boostResults_doesNotModifyOriginalList() {
        var original = List.of(result("docs/a.md", 5.0f), result("docs/b.md", 3.0f));
        var triggerScores = Map.of("docs/b.md", 100);

        var boosted = KcpPlanner.boostResults(original, triggerScores);

        // Original list should not be modified
        assertEquals("docs/a.md", original.get(0).relativePath());
        // Boosted list has different order
        assertEquals("docs/b.md", boosted.get(0).relativePath());
    }

    // --- withScore helper ---

    @Test
    void searchResult_withScore_preservesAllFields() {
        var original = new SearchResult(
                Path.of("/workspace/docs/auth.md"), "docs/auth.md", 3.0f,
                "auth.md", "MARKDOWN", "en", "Auth guide", "# Auth",
                "prose", 1234L, "my-repo", "docs");

        var boosted = original.withScore(10.0f);

        assertEquals(10.0f, boosted.score());
        assertEquals(original.path(), boosted.path());
        assertEquals(original.relativePath(), boosted.relativePath());
        assertEquals(original.fileName(), boosted.fileName());
        assertEquals(original.fileType(), boosted.fileType());
        assertEquals(original.language(), boosted.language());
        assertEquals(original.summary(), boosted.summary());
        assertEquals(original.headings(), boosted.headings());
        assertEquals(original.structure(), boosted.structure());
        assertEquals(original.sizeBytes(), boosted.sizeBytes());
        assertEquals(original.repository(), boosted.repository());
        assertEquals(original.subWorkspace(), boosted.subWorkspace());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private SearchResult result(String relativePath, float score) {
        return new SearchResult(
                Path.of("/workspace/" + relativePath), relativePath, score,
                relativePath.substring(relativePath.lastIndexOf('/') + 1),
                "MARKDOWN", null, "", "", "", 100L);
    }

    private KcpRepository.KcpUnitRow unit(String id, String path, String triggersJson) {
        return unit(id, path, triggersJson, null);
    }

    private KcpRepository.KcpUnitRow unit(String id, String path, String triggersJson,
                                           String intent) {
        return new KcpRepository.KcpUnitRow(
                id, path, intent, "module", null, triggersJson, null,
                null, null, null, null,
                null, null, null, false, null, null,
                null, -1.0, null, null, null);
    }
}
