package io.exoreaction.synthesis.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GitMetricsComputer decay math and utility logic.
 */
class GitMetricsComputerTest {

    @Test
    void decayScore_atAgeZero_isOne() {
        double score = GitMetricsComputer.decayScore(0.0);
        assertEquals(1.0, score, 1e-9, "Score at age 0 should be exactly 1.0");
    }

    @Test
    void decayScore_atHalfLife_isHalf() {
        double score = GitMetricsComputer.decayScore(180.0);
        assertEquals(0.5, score, 1e-6, "Score at half-life (180 days) should be ~0.5");
    }

    @Test
    void decayScore_atDoubleHalfLife_isQuarter() {
        double score = GitMetricsComputer.decayScore(360.0);
        assertEquals(0.25, score, 1e-6, "Score at 360 days should be ~0.25");
    }

    @Test
    void decayScore_atTripleHalfLife_isEighth() {
        double score = GitMetricsComputer.decayScore(540.0);
        assertEquals(0.125, score, 1e-5, "Score at 540 days should be ~0.125");
    }

    @Test
    void decayScore_isMonotonicallyDecreasing() {
        double prev = Double.MAX_VALUE;
        for (int days = 0; days <= 720; days += 30) {
            double score = GitMetricsComputer.decayScore(days);
            assertTrue(score < prev, "Score should decrease monotonically at day " + days);
            prev = score;
        }
    }

    @Test
    void decayScore_isAlwaysPositive() {
        for (int days = 0; days <= 3650; days += 90) {
            assertTrue(GitMetricsComputer.decayScore(days) > 0,
                    "Score should always be positive at day " + days);
        }
    }

    @Test
    void decayScore_sumOfTwoCommitsAtDifferentAges() {
        // A file touched at age 0 and age 180 should have score ~1.5
        double total = GitMetricsComputer.decayScore(0) + GitMetricsComputer.decayScore(180);
        assertEquals(1.5, total, 1e-6, "Sum of scores at 0 and 180 days should be ~1.5");
    }
}
