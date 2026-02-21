package io.exoreaction.synthesis.org;

import java.nio.file.Path;
import java.util.List;

/**
 * The result of a routing decision: where to route a file, with full reasoning.
 *
 * <p>Replaces the previous {@code DirectoryIdentityRouter.RouteResult} inner record
 * with a richer structure that includes the confidence level and mechanism label.
 *
 * @param destination the target directory path
 * @param score       the total routing score (content + scope bonus)
 * @param confidence  the confidence level derived from the score
 * @param mechanism   how the decision was made: "hint", "identity-score"
 * @param reasons     human-readable scoring breakdown (e.g. "type-match(+0.3)")
 * @param ambiguous   true if the top two candidates had very similar scores
 * @since v1.13.0 (P1-06)
 */
public record RoutingDecision(
        Path destination,
        double score,
        RoutingConfidence confidence,
        String mechanism,
        List<String> reasons,
        boolean ambiguous
) {

    /**
     * Returns a human-readable label summarizing the routing decision.
     *
     * @return a label like "dir-identity: media @ 0.65 (HIGH)"
     */
    public String scoreLabel() {
        return String.format("%s: %s @ %.2f (%s)",
                mechanism, destination.getFileName(), score, confidence.name());
    }

    /**
     * Creates a RoutingDecision from a {@link DirectoryScorer.ScoredCandidate}.
     *
     * @param candidate the scored candidate
     * @param ambiguous whether the match was ambiguous
     * @return a RoutingDecision
     */
    static RoutingDecision fromScoredCandidate(
            DirectoryScorer.ScoredCandidate candidate, boolean ambiguous) {
        return new RoutingDecision(
                candidate.directory(),
                candidate.totalScore(),
                RoutingConfidence.fromScore(candidate.totalScore()),
                "identity-score",
                candidate.reasons(),
                ambiguous
        );
    }

    /**
     * Creates a RoutingDecision for a routing hint match.
     *
     * @param destination the hint destination path
     * @param score       the synthetic score (typically 0.9)
     * @return a RoutingDecision with mechanism "hint"
     */
    static RoutingDecision fromHint(Path destination, double score) {
        return new RoutingDecision(
                destination,
                score,
                RoutingConfidence.fromScore(score),
                "hint",
                List.of("hint-match"),
                false
        );
    }
}
