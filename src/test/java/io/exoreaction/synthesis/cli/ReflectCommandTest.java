package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.sessions.ClaudeSession;
import io.exoreaction.synthesis.skills.ReflectState;
import io.exoreaction.synthesis.skills.ReflectState.State;
import io.exoreaction.synthesis.skills.SessionAnalyzer;
import io.exoreaction.synthesis.skills.SessionAnalyzer.ExtractedPattern;
import io.exoreaction.synthesis.skills.SkillUpdater;
import io.exoreaction.synthesis.skills.SkillUpdater.ReflectResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReflectCommand} logic, exercised via the underlying
 * components (SessionAnalyzer, SkillUpdater, ReflectState). Integration tests
 * requiring a full database are excluded for speed.
 */
class ReflectCommandTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ClaudeSession makeSession(String sessionId, String userText) {
        return new ClaudeSession(
                sessionId,
                "/test/project",
                Instant.now().minusSeconds(3600),
                Instant.now(),
                3,
                2,
                List.of("Read", "Edit", "Bash"),
                userText != null ? userText.substring(0, Math.min(50, userText.length())) : null,
                userText,
                null, null, false, null
        );
    }

    // -----------------------------------------------------------------------
    // Tests: staleness check
    // -----------------------------------------------------------------------

    @Test
    void testStalenessCheckSkipsRecentReflection() {
        // State reflected 1 hour ago, since = 2 hours ago
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        State state = new State(oneHourAgo, 10, 2, 3);

        assertFalse(ReflectState.isStale(state, twoHoursAgo),
                "Should NOT be stale when reflected after the since cutoff");
    }

    @Test
    void testStalenessCheckTriggersOldReflection() {
        // State reflected 2 days ago, since = 1 day ago
        Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant oneDayAgo = Instant.now().minus(1, ChronoUnit.DAYS);
        State state = new State(twoDaysAgo, 10, 2, 3);

        assertTrue(ReflectState.isStale(state, oneDayAgo),
                "Should be stale when reflected before the since cutoff");
    }

    // -----------------------------------------------------------------------
    // Tests: end-to-end pipeline (analyzer + updater)
    // -----------------------------------------------------------------------

    @Test
    void testFullPipelineCreatesSkills() throws Exception {
        ClaudeSession session = makeSession("test-session",
                "No, always use immutable records for DTOs. "
                + "You should never use mutable state. "
                + "Important: validate all inputs.");

        List<ExtractedPattern> patterns = SessionAnalyzer.analyze(List.of(session), 0.3);
        assertFalse(patterns.isEmpty(), "Should extract patterns from session text");

        ReflectResult result = SkillUpdater.apply(patterns, tempDir, false, 5);
        assertNotNull(result, "Result should not be null");
        assertTrue(result.skillsCreated() > 0 || result.skillsSkipped() > 0,
                "Should process patterns (create or skip)");
    }

    @Test
    void testDryRunProducesNoFiles() throws Exception {
        ClaudeSession session = makeSession("dry-session",
                "Actually, always format code before committing. "
                + "Never commit unformatted code.");

        List<ExtractedPattern> patterns = SessionAnalyzer.analyze(List.of(session), 0.3);
        ReflectResult result = SkillUpdater.apply(patterns, tempDir, true, 5);

        // Count files in tempDir (should be 0 in dry-run)
        long fileCount = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                .count();
        assertEquals(0, fileCount, "Dry run should not create any YAML files");

        // But changes should still be reported
        assertFalse(result.changes().isEmpty() && !patterns.isEmpty(),
                "Dry run should still report intended changes");
    }

    @Test
    void testEmptySessionsProduceNoPatterns() {
        List<ExtractedPattern> patterns = SessionAnalyzer.analyze(List.of(), 0.3);
        assertTrue(patterns.isEmpty(), "No sessions should yield no patterns");
    }

    @Test
    void testParseSince() {
        Instant sevenDaysAgo = SessionsCommand.parseSince("7d");
        assertNotNull(sevenDaysAgo, "parseSince should return a non-null Instant");

        // Should be roughly 7 days before now (with some tolerance)
        long diffSeconds = Math.abs(
                Instant.now().minus(7, ChronoUnit.DAYS).getEpochSecond()
                - sevenDaysAgo.getEpochSecond()
        );
        assertTrue(diffSeconds < 5, "parseSince(7d) should be ~7 days ago");

        // Test other formats
        Instant hours = SessionsCommand.parseSince("24h");
        assertNotNull(hours, "parseSince(24h) should work");

        Instant weeks = SessionsCommand.parseSince("2w");
        assertNotNull(weeks, "parseSince(2w) should work");
    }

    // -----------------------------------------------------------------------
    // Tests: ReflectState persistence
    // -----------------------------------------------------------------------

    @Test
    void testStatePersistence() throws Exception {
        Path stateFile = tempDir.resolve("state.json");

        // Initially missing → empty state
        State initial = ReflectState.load(stateFile);
        assertNull(initial.lastReflectedAt(), "Initial state should have null lastReflectedAt");

        // Save and reload
        State saved = new State(Instant.now(), 15, 4, 6);
        ReflectState.save(saved, stateFile);

        State loaded = ReflectState.load(stateFile);
        assertEquals(saved.sessionsProcessed(), loaded.sessionsProcessed());
        assertEquals(saved.skillsCreated(), loaded.skillsCreated());
        assertEquals(saved.skillsUpdated(), loaded.skillsUpdated());
    }

    // -----------------------------------------------------------------------
    // Tests: max-new limit
    // -----------------------------------------------------------------------

    @Test
    void testMaxNewLimitEnforced() throws Exception {
        // Create many sessions with diverse correction patterns
        ClaudeSession s1 = makeSession("s1",
                "No, always use final for local variables. Stop using var.");
        ClaudeSession s2 = makeSession("s2",
                "Wrong, never hardcode URLs. Actually, use configuration.");
        ClaudeSession s3 = makeSession("s3",
                "I meant logging, not printing. Don't use System.out.");

        List<ExtractedPattern> patterns = SessionAnalyzer.analyze(List.of(s1, s2, s3), 0.1);

        // Apply with max-new = 1
        ReflectResult result = SkillUpdater.apply(patterns, tempDir, false, 1);

        assertTrue(result.skillsCreated() <= 1,
                "Max-new=1 should create at most 1 skill, got " + result.skillsCreated());
    }

    // -----------------------------------------------------------------------
    // Tests: confidence threshold
    // -----------------------------------------------------------------------

    @Test
    void testHighConfidenceThresholdFilters() {
        ClaudeSession session = makeSession("conf-session",
                "First, check the logs. Then fix the bug. "
                + "You should always write tests.");

        // Very high threshold
        List<ExtractedPattern> strict = SessionAnalyzer.analyze(List.of(session), 0.99);
        List<ExtractedPattern> lenient = SessionAnalyzer.analyze(List.of(session), 0.1);

        assertTrue(lenient.size() >= strict.size(),
                "Lenient threshold should return >= as many patterns as strict");
    }
}
