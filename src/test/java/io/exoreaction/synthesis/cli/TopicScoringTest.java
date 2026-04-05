package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.skills.ConsolidateState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for topic health and triage scoring logic.
 *
 * <p>Tests are pure-function: no DB, no file system (except ConsolidateState persistence),
 * no SessionStore. Covers hotness, recency, recurrence, composite, and keyword extraction.
 */
class TopicScoringTest {

    // =========================================================================
    // TopicHealthCommand.computeHotness
    // =========================================================================

    @Test
    void testComputeHotness_maxHitsAndAgeZero() {
        // Full hits, brand new file → 0.6 * 1.0 + 0.4 * 1.0 = 1.0
        assertEquals(1.0, TopicHealthCommand.computeHotness(10, 10, 0), 0.001);
    }

    @Test
    void testComputeHotness_noHitsOldFile() {
        // 0 hits, 60+ days old → 0.6 * 0 + 0.4 * 0 = 0.0
        assertEquals(0.0, TopicHealthCommand.computeHotness(0, 10, 60), 0.001);
    }

    @Test
    void testComputeHotness_halfHitsHalfAge() {
        // 5/10 hits, 30 days old → 0.6 * 0.5 + 0.4 * 0.5 = 0.5
        assertEquals(0.5, TopicHealthCommand.computeHotness(5, 10, 30), 0.001);
    }

    @Test
    void testComputeHotness_ageClampedAt60() {
        // Age beyond 60 should clamp to same as 60
        double at60  = TopicHealthCommand.computeHotness(0, 10, 60);
        double at120 = TopicHealthCommand.computeHotness(0, 10, 120);
        assertEquals(at60, at120, 0.001, "Age should clamp at 60 days");
    }

    @Test
    void testComputeHotness_clampsAboveOne() {
        // Should never exceed 1.0
        double result = TopicHealthCommand.computeHotness(10, 10, 0);
        assertTrue(result <= 1.0);
    }

    @Test
    void testComputeHotness_clampsBelowZero() {
        // Should never go below 0.0
        double result = TopicHealthCommand.computeHotness(0, 10, 999);
        assertTrue(result >= 0.0);
    }

    // =========================================================================
    // TopicTriageCommand.scoreRecency
    // =========================================================================

    @Test
    void testScoreRecency_null() {
        assertEquals(0.1, TopicTriageCommand.scoreRecency(null), 0.001);
    }

    @Test
    void testScoreRecency_withinSevenDays() {
        Instant recent = Instant.now().minus(3, ChronoUnit.DAYS);
        assertEquals(1.0, TopicTriageCommand.scoreRecency(recent), 0.001);
    }

    @Test
    void testScoreRecency_withinFourteenDays() {
        Instant recent = Instant.now().minus(10, ChronoUnit.DAYS);
        assertEquals(0.7, TopicTriageCommand.scoreRecency(recent), 0.001);
    }

    @Test
    void testScoreRecency_withinThirtyDays() {
        Instant recent = Instant.now().minus(20, ChronoUnit.DAYS);
        assertEquals(0.4, TopicTriageCommand.scoreRecency(recent), 0.001);
    }

    @Test
    void testScoreRecency_beyondThirtyDays() {
        Instant old = Instant.now().minus(45, ChronoUnit.DAYS);
        assertEquals(0.1, TopicTriageCommand.scoreRecency(old), 0.001);
    }

    // =========================================================================
    // TopicTriageCommand.scoreRecurrence
    // =========================================================================

    @Test
    void testScoreRecurrence_zero() {
        assertEquals(0.0, TopicTriageCommand.scoreRecurrence(0), 0.001);
    }

    @Test
    void testScoreRecurrence_three() {
        // 3 sessions / 3 = 1.0 (threshold met)
        assertEquals(1.0, TopicTriageCommand.scoreRecurrence(3), 0.001);
    }

    @Test
    void testScoreRecurrence_clampedAboveThree() {
        // 6 sessions / 3 = 2.0 → clamped to 1.0
        assertEquals(1.0, TopicTriageCommand.scoreRecurrence(6), 0.001);
    }

    @Test
    void testScoreRecurrence_one() {
        assertEquals(1.0 / 3.0, TopicTriageCommand.scoreRecurrence(1), 0.001);
    }

    // =========================================================================
    // TopicTriageCommand.computeComposite
    // =========================================================================

    @Test
    void testComputeComposite_maxEverything() {
        // recency=1, recurrence=1, actionability=1, staleness=0 → 0.3+0.25+0.25+0.2=1.0
        assertEquals(1.0, TopicTriageCommand.computeComposite(1.0, 1.0, 1.0, 0.0), 0.001);
    }

    @Test
    void testComputeComposite_minEverything() {
        // recency=0, recurrence=0, actionability=0, staleness=1 → 0+0+0+0=0.0
        assertEquals(0.0, TopicTriageCommand.computeComposite(0.0, 0.0, 0.0, 1.0), 0.001);
    }

    @Test
    void testComputeComposite_weightsCorrect() {
        // Only recency contributes: 0.3 * 1.0 = 0.3
        assertEquals(0.3, TopicTriageCommand.computeComposite(1.0, 0.0, 0.0, 1.0), 0.001);
        // Only recurrence: 0.25 * 1.0 = 0.25
        assertEquals(0.25, TopicTriageCommand.computeComposite(0.0, 1.0, 0.0, 1.0), 0.001);
        // Only actionability: 0.25 * 1.0 = 0.25
        assertEquals(0.25, TopicTriageCommand.computeComposite(0.0, 0.0, 1.0, 1.0), 0.001);
        // Only staleness inverse (staleness=0): 0.2 * 1.0 = 0.2
        assertEquals(0.2, TopicTriageCommand.computeComposite(0.0, 0.0, 0.0, 0.0), 0.001);
    }

    // =========================================================================
    // TopicHealthCommand.extractKeywords
    // =========================================================================

    @Test
    void testExtractKeywords_typicalFilename() {
        List<String> kws = TopicHealthCommand.extractKeywords("mistakes-to-avoid.md");
        assertTrue(kws.contains("mistakes"), "should include 'mistakes'");
        assertTrue(kws.contains("avoid"), "should include 'avoid'");
        assertFalse(kws.contains("to"), "should filter stop word 'to'");
    }

    @Test
    void testExtractKeywords_synthesisNotes() {
        List<String> kws = TopicHealthCommand.extractKeywords("synthesis-notes.md");
        assertTrue(kws.contains("synthesis"), "should include 'synthesis'");
        // "notes" is in stop words
        assertFalse(kws.contains("notes"), "should filter stop word 'notes'");
    }

    @Test
    void testExtractKeywords_shortWordsFiltered() {
        // Words < 3 chars should be excluded
        List<String> kws = TopicHealthCommand.extractKeywords("a-b-codebase.md");
        assertFalse(kws.contains("a"), "single char filtered");
        assertFalse(kws.contains("b"), "single char filtered");
        assertTrue(kws.contains("codebase"), "longer word kept");
    }

    @Test
    void testExtractKeywords_memoryMd() {
        List<String> kws = TopicHealthCommand.extractKeywords("MEMORY.md");
        assertFalse(kws.isEmpty(), "should return at least one keyword");
    }

    // =========================================================================
    // ConsolidateState persistence
    // =========================================================================

    @Test
    void testConsolidateState_emptyWhenMissing(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("nonexistent.json");
        ConsolidateState.State state = ConsolidateState.load(missing);
        assertNull(state.lastConsolidatedAt());
        assertEquals(0, state.sessionCountAtLastConsolidate());
    }

    @Test
    void testConsolidateState_roundTrip(@TempDir Path tempDir) throws Exception {
        Path stateFile = tempDir.resolve("consolidate-state.json");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ConsolidateState.State original = new ConsolidateState.State(now, 42);
        ConsolidateState.save(original, stateFile);

        ConsolidateState.State loaded = ConsolidateState.load(stateFile);
        assertEquals(original.lastConsolidatedAt(), loaded.lastConsolidatedAt());
        assertEquals(42, loaded.sessionCountAtLastConsolidate());
    }

    @Test
    void testConsolidateState_isDue_neverRun() {
        ConsolidateState.State empty = ConsolidateState.State.empty();
        assertTrue(ConsolidateState.isDue(empty, 0), "Should be due if never run");
    }

    @Test
    void testConsolidateState_isDue_recentRunFewSessions() {
        // 1 hour ago, only 2 new sessions → NOT due (neither threshold met)
        Instant recentRun = Instant.now().minus(1, ChronoUnit.HOURS);
        ConsolidateState.State state = new ConsolidateState.State(recentRun, 100);
        assertFalse(ConsolidateState.isDue(state, 2));
    }

    @Test
    void testConsolidateState_isDue_bothThresholdsMet() {
        // 25 hours ago, 7 new sessions → due
        Instant longAgo = Instant.now().minus(25, ChronoUnit.HOURS);
        ConsolidateState.State state = new ConsolidateState.State(longAgo, 100);
        assertTrue(ConsolidateState.isDue(state, 7));
    }

    @Test
    void testConsolidateState_isDue_onlyTimeMet() {
        // 25 hours ago but only 2 sessions → NOT due
        Instant longAgo = Instant.now().minus(25, ChronoUnit.HOURS);
        ConsolidateState.State state = new ConsolidateState.State(longAgo, 100);
        assertFalse(ConsolidateState.isDue(state, 2));
    }

    @Test
    void testConsolidateState_isDue_onlySessionsMet() {
        // 1 hour ago but 10 new sessions → NOT due (time not met)
        Instant recent = Instant.now().minus(1, ChronoUnit.HOURS);
        ConsolidateState.State state = new ConsolidateState.State(recent, 100);
        assertFalse(ConsolidateState.isDue(state, 10));
    }
}
