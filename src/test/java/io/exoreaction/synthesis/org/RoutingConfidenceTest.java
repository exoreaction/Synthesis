package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RoutingConfidence} score-to-confidence mapping.
 *
 * @since v1.13.0 (P1-06)
 */
class RoutingConfidenceTest {

    @Test
    void fromScore_certain() {
        assertEquals(RoutingConfidence.CERTAIN, RoutingConfidence.fromScore(0.75));
        assertEquals(RoutingConfidence.CERTAIN, RoutingConfidence.fromScore(0.9));
        assertEquals(RoutingConfidence.CERTAIN, RoutingConfidence.fromScore(1.0));
    }

    @Test
    void fromScore_high() {
        assertEquals(RoutingConfidence.HIGH, RoutingConfidence.fromScore(0.55));
        assertEquals(RoutingConfidence.HIGH, RoutingConfidence.fromScore(0.65));
        assertEquals(RoutingConfidence.HIGH, RoutingConfidence.fromScore(0.74));
    }

    @Test
    void fromScore_moderate() {
        assertEquals(RoutingConfidence.MODERATE, RoutingConfidence.fromScore(0.35));
        assertEquals(RoutingConfidence.MODERATE, RoutingConfidence.fromScore(0.45));
        assertEquals(RoutingConfidence.MODERATE, RoutingConfidence.fromScore(0.54));
    }

    @Test
    void fromScore_low() {
        assertEquals(RoutingConfidence.LOW, RoutingConfidence.fromScore(0.20));
        assertEquals(RoutingConfidence.LOW, RoutingConfidence.fromScore(0.25));
        assertEquals(RoutingConfidence.LOW, RoutingConfidence.fromScore(0.34));
    }

    @Test
    void fromScore_none() {
        assertEquals(RoutingConfidence.NONE, RoutingConfidence.fromScore(0.0));
        assertEquals(RoutingConfidence.NONE, RoutingConfidence.fromScore(0.1));
        assertEquals(RoutingConfidence.NONE, RoutingConfidence.fromScore(0.19));
    }

    @Test
    void thresholds_areOrdered() {
        assertTrue(RoutingConfidence.CERTAIN.threshold() > RoutingConfidence.HIGH.threshold());
        assertTrue(RoutingConfidence.HIGH.threshold() > RoutingConfidence.MODERATE.threshold());
        assertTrue(RoutingConfidence.MODERATE.threshold() > RoutingConfidence.LOW.threshold());
        assertTrue(RoutingConfidence.LOW.threshold() > RoutingConfidence.NONE.threshold());
    }

    @Test
    void fromScore_boundaryValues() {
        // Exact boundary values should map to the level they define
        assertEquals(RoutingConfidence.CERTAIN, RoutingConfidence.fromScore(0.75));
        assertEquals(RoutingConfidence.HIGH, RoutingConfidence.fromScore(0.55));
        assertEquals(RoutingConfidence.MODERATE, RoutingConfidence.fromScore(0.35));
        assertEquals(RoutingConfidence.LOW, RoutingConfidence.fromScore(0.20));
        assertEquals(RoutingConfidence.NONE, RoutingConfidence.fromScore(0.0));
    }
}
