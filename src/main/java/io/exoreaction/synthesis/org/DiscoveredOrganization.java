package io.exoreaction.synthesis.org;

/**
 * A discovered organization with its confidence score.
 *
 * <p>Used during interactive init to present scan results to the user
 * before confirming which organizations to keep.
 *
 * @param organization the discovered organization
 * @param confidence   the confidence score (higher = more likely a real organization)
 * @param signals      human-readable summary of detection signals
 */
public record DiscoveredOrganization(
        Organization organization,
        int confidence,
        String signals
) {

    /**
     * Returns a normalized confidence score on a 1-10 scale.
     * Useful for display (e.g., "confidence: 8/10").
     */
    public int normalizedConfidence() {
        // Max possible score: README(1) + CODEBASE-INDEX(3) + clients(2) + products(2)
        // + business(2) + marketing(1) + methodology(1) + codebase(1) + media(1) = 14
        int maxScore = 14;
        int normalized = (int) Math.round((double) confidence / maxScore * 10);
        return Math.max(1, Math.min(10, normalized));
    }

    /**
     * Whether this organization has high confidence (auto-accept threshold).
     */
    public boolean isHighConfidence() {
        return normalizedConfidence() >= 7;
    }

    /**
     * Whether this organization has medium confidence (present with notice).
     */
    public boolean isMediumConfidence() {
        return normalizedConfidence() >= 4 && normalizedConfidence() < 7;
    }

    /**
     * Whether this organization has low confidence (present with warning).
     */
    public boolean isLowConfidence() {
        return normalizedConfidence() < 4;
    }
}
