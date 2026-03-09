package io.exoreaction.synthesis.skills;

import io.exoreaction.synthesis.sessions.ClaudeSession;
import io.exoreaction.synthesis.skills.SessionAnalyzer.ExtractedPattern;
import io.exoreaction.synthesis.skills.SessionAnalyzer.FragmentType;
import io.exoreaction.synthesis.skills.SessionAnalyzer.RawFragment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SessionAnalyzer}: fragment extraction, clustering,
 * and end-to-end analysis.
 */
class SessionAnalyzerTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ClaudeSession makeSession(String sessionId, String allUserText) {
        return makeSession(sessionId, allUserText, List.of());
    }

    private ClaudeSession makeSession(String sessionId, String allUserText, List<String> toolNames) {
        return new ClaudeSession(
                sessionId,
                "/test/project",
                Instant.now().minusSeconds(3600),
                Instant.now(),
                5,       // turnCount
                toolNames.size(),
                toolNames,
                allUserText != null ? allUserText.substring(0, Math.min(50, allUserText.length())) : null,
                allUserText
        );
    }

    // -----------------------------------------------------------------------
    // Fragment extraction tests
    // -----------------------------------------------------------------------

    @Test
    void testCorrectionExtraction() {
        ClaudeSession session = makeSession("s1",
                "No, that's wrong. Actually, use the other method instead. "
                + "The function should return a list.");

        List<RawFragment> fragments = SessionAnalyzer.extractFragments(session);

        boolean hasCorrection = fragments.stream()
                .anyMatch(f -> f.type() == FragmentType.CORRECTION);
        assertTrue(hasCorrection, "Should extract CORRECTION fragments from 'No,' and 'Actually,'");
    }

    @Test
    void testExplicitRuleExtraction() {
        ClaudeSession session = makeSession("s2",
                "You should always use immutable records. "
                + "Never use mutable state in handlers. "
                + "Important: all methods must be documented.");

        List<RawFragment> fragments = SessionAnalyzer.extractFragments(session);

        long ruleCount = fragments.stream()
                .filter(f -> f.type() == FragmentType.EXPLICIT_RULE)
                .count();
        assertTrue(ruleCount >= 2, "Should extract at least 2 EXPLICIT_RULE fragments, found " + ruleCount);
    }

    @Test
    void testWorkflowStepExtraction() {
        ClaudeSession session = makeSession("s3",
                "First, read the existing file. Then, make the changes. "
                + "Next, run the tests. Finally, commit the results.");

        List<RawFragment> fragments = SessionAnalyzer.extractFragments(session);

        long stepCount = fragments.stream()
                .filter(f -> f.type() == FragmentType.WORKFLOW_STEP)
                .count();
        assertTrue(stepCount >= 1, "Should extract WORKFLOW_STEP fragments from 'First,', 'Then,', etc.");
    }

    @Test
    void testToolPatternExtraction() {
        ClaudeSession session = makeSession("s4",
                "Let me work on this feature.",
                List.of("Read", "Edit", "Bash", "Grep"));

        List<RawFragment> fragments = SessionAnalyzer.extractFragments(session);

        boolean hasToolPattern = fragments.stream()
                .anyMatch(f -> f.type() == FragmentType.TOOL_PATTERN);
        assertTrue(hasToolPattern,
                "Should extract TOOL_PATTERN when 3+ distinct tools are used");
    }

    @Test
    void testDomainTermExtraction() {
        // The word "deployment" appears 4 times — should be extracted as a domain term
        ClaudeSession session = makeSession("s5",
                "The deployment pipeline failed. Check the deployment config. "
                + "Fix the deployment script. Verify the deployment succeeded.");

        List<RawFragment> fragments = SessionAnalyzer.extractFragments(session);

        boolean hasDomainTerm = fragments.stream()
                .anyMatch(f -> f.type() == FragmentType.DOMAIN_TERM
                        && f.keywords().contains("deployment"));
        assertTrue(hasDomainTerm,
                "Should extract 'deployment' as a DOMAIN_TERM (appears 4 times)");
    }

    // -----------------------------------------------------------------------
    // End-to-end analysis tests
    // -----------------------------------------------------------------------

    @Test
    void testAnalyzeWithMultipleSessions() {
        // Two sessions with overlapping correction content should produce patterns
        ClaudeSession s1 = makeSession("session-1",
                "No, always use snake_case for database columns. "
                + "The convention is snake_case.");
        ClaudeSession s2 = makeSession("session-2",
                "Actually, database columns should always be snake_case. "
                + "Never use camelCase for database fields.");

        List<ExtractedPattern> patterns = SessionAnalyzer.analyze(List.of(s1, s2), 0.1);

        assertFalse(patterns.isEmpty(), "Should produce at least one pattern from two sessions");

        // Patterns should be sorted by confidence descending
        for (int i = 0; i < patterns.size() - 1; i++) {
            assertTrue(patterns.get(i).confidence() >= patterns.get(i + 1).confidence(),
                    "Patterns should be sorted by confidence descending");
        }
    }

    @Test
    void testAnalyzeEmptySessions() {
        List<ExtractedPattern> patterns = SessionAnalyzer.analyze(List.of(), 0.3);
        assertTrue(patterns.isEmpty(), "Empty session list should produce no patterns");

        patterns = SessionAnalyzer.analyze(null, 0.3);
        assertTrue(patterns.isEmpty(), "Null session list should produce no patterns");
    }

    @Test
    void testMinConfidenceFiltering() {
        ClaudeSession session = makeSession("s1",
                "No, that's completely wrong. Stop doing that. "
                + "Important: always validate input first.");

        // Very high threshold should filter out most patterns
        List<ExtractedPattern> highThreshold = SessionAnalyzer.analyze(List.of(session), 0.95);
        List<ExtractedPattern> lowThreshold = SessionAnalyzer.analyze(List.of(session), 0.1);

        assertTrue(lowThreshold.size() >= highThreshold.size(),
                "Lower confidence threshold should produce more patterns");
    }

    // -----------------------------------------------------------------------
    // Utility tests
    // -----------------------------------------------------------------------

    @Test
    void testJaccardSimilarity() {
        Set<String> a = Set.of("alpha", "beta", "gamma");
        Set<String> b = Set.of("beta", "gamma", "delta");

        double sim = SessionAnalyzer.jaccardSimilarity(a, b);
        // Intersection = {beta, gamma} = 2, Union = {alpha, beta, gamma, delta} = 4
        assertEquals(0.5, sim, 0.01, "Jaccard should be 2/4 = 0.5");

        // Empty sets
        assertEquals(0.0, SessionAnalyzer.jaccardSimilarity(Set.of(), Set.of()),
                "Empty sets should have 0 similarity");
    }
}
