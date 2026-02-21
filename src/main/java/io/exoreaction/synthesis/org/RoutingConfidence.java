package io.exoreaction.synthesis.org;

/**
 * Confidence level for a routing decision, mapping score ranges to
 * human-understandable labels.
 *
 * <p>Thresholds are calibrated against the {@link DirectoryScorer} output
 * range (0.0-1.0 content score, plus scope bonus).
 *
 * <ul>
 *   <li>{@link #CERTAIN} (>= 0.75): Auto-route safe, no confirmation needed</li>
 *   <li>{@link #HIGH} (>= 0.55): Single-line confirmation</li>
 *   <li>{@link #MODERATE} (>= 0.35): Show suggestion with reasoning</li>
 *   <li>{@link #LOW} (>= 0.20): Mention as possibility</li>
 *   <li>{@link #NONE} (< 0.20): No meaningful match</li>
 * </ul>
 *
 * @since v1.13.0 (P1-06)
 */
public enum RoutingConfidence {

    /** Auto-route safe -- no confirmation needed. */
    CERTAIN(0.75),

    /** Single-line confirmation suggested. */
    HIGH(0.55),

    /** Show suggestion with reasoning. */
    MODERATE(0.35),

    /** Mention as possibility only. */
    LOW(0.20),

    /** No meaningful match. */
    NONE(0.0);

    private final double threshold;

    RoutingConfidence(double threshold) {
        this.threshold = threshold;
    }

    /**
     * Returns the minimum score threshold for this confidence level.
     *
     * @return the threshold (inclusive)
     */
    public double threshold() {
        return threshold;
    }

    /**
     * Returns the confidence level for a given score.
     *
     * @param score the routing score
     * @return the matching confidence level
     */
    public static RoutingConfidence fromScore(double score) {
        if (score >= CERTAIN.threshold) return CERTAIN;
        if (score >= HIGH.threshold) return HIGH;
        if (score >= MODERATE.threshold) return MODERATE;
        if (score >= LOW.threshold) return LOW;
        return NONE;
    }
}
