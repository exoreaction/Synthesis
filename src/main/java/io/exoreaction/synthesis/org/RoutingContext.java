package io.exoreaction.synthesis.org;

/**
 * Caller-specified preferences for a routing operation.
 *
 * <p>Encapsulates the threshold, transient-directory policy, and operational
 * flags that were previously passed as separate parameters to
 * {@link DirectoryIdentityRouter#route}.
 *
 * @param threshold     minimum score to consider a match (e.g. 0.5 for rebalance)
 * @param skipTransient if true, exclude transient directories from candidates
 * @param mediaOnly     if true, only consider media files (video/audio/image)
 * @param dryRun        if true, do not perform any side effects (move, write)
 * @since v1.13.0 (P1-06)
 */
public record RoutingContext(
        double threshold,
        boolean skipTransient,
        boolean mediaOnly,
        boolean dryRun
) {

    /**
     * Creates a context with only a threshold, all flags false.
     *
     * @param threshold minimum score to consider a match
     * @return a RoutingContext with default flags
     */
    public static RoutingContext withThreshold(double threshold) {
        return new RoutingContext(threshold, false, false, false);
    }

    /**
     * Creates a context for rebalance operations: skipTransient=true, threshold 0.5.
     *
     * @return a RoutingContext for rebalance
     */
    public static RoutingContext forRebalance() {
        return new RoutingContext(0.5, true, false, false);
    }

    /**
     * Creates a context for E010 health check operations: skipTransient=true, threshold 0.25.
     *
     * @return a RoutingContext for E010 health check
     */
    public static RoutingContext forE010() {
        return new RoutingContext(0.25, true, false, false);
    }
}
