package io.exoreaction.synthesis.org;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes how well a directory's current content (centroid) matches its
 * stated wants. The satisfaction metric is a value between 0.0 and 1.0.
 *
 * <p>Formula:
 * <pre>
 *   satisfaction = topicCoverage * 0.5 + entityCoverage * 0.3 + gapsFilled * 0.2
 * </pre>
 *
 * <p>Where:
 * <ul>
 *   <li>{@code topicCoverage} = |centroid.topics &cap; wants.topics| / |wants.topics|</li>
 *   <li>{@code entityCoverage} = |centroid.entities &cap; wants.entities| / |wants.entities|</li>
 *   <li>{@code gapsFilled} = |wants.alsoLookingFor &cap; centroid.documentTypes| / |wants.alsoLookingFor|</li>
 * </ul>
 *
 * <p>Special cases:
 * <ul>
 *   <li>Empty wants = 1.0 (no explicit wants = satisfied by definition)</li>
 *   <li>Has wants but empty centroid = 0.0 (nothing to satisfy them)</li>
 * </ul>
 *
 * @since v1.15.0 (P3-04)
 */
public class WantSatisfactionComputer {

    static final double TOPIC_WEIGHT = 0.5;
    static final double ENTITY_WEIGHT = 0.3;
    static final double GAPS_WEIGHT = 0.2;

    /**
     * Computes the satisfaction score for a directory.
     *
     * @param centroid the directory's current semantic centroid
     * @param wants    the directory's stated wants
     * @return satisfaction score 0.0-1.0
     */
    public double compute(DirectoryCentroid centroid, DirectoryWants wants) {
        // Empty wants = satisfied by definition
        if (wants == null || wants.isEmpty()) {
            return 1.0;
        }

        // Has wants but no centroid = nothing to satisfy them
        if (centroid == null || centroid.isEmpty()) {
            return 0.0;
        }

        double satisfaction = 0.0;

        // Topic coverage
        if (!wants.topics().isEmpty()) {
            double topicCoverage = coverageRatio(
                    toLowerSet(centroid.topics()),
                    toLowerSet(wants.topics())
            );
            satisfaction += topicCoverage * TOPIC_WEIGHT;
        }

        // Entity coverage
        if (!wants.entities().isEmpty()) {
            double entityCoverage = coverageRatio(
                    toLowerSet(centroid.entities()),
                    toLowerSet(wants.entities())
            );
            satisfaction += entityCoverage * ENTITY_WEIGHT;
        }

        // Gaps filled (alsoLookingFor matched by centroid.documentTypes)
        if (!wants.alsoLookingFor().isEmpty()) {
            double gapsFilled = coverageRatio(
                    toLowerSet(centroid.documentTypes()),
                    toLowerSet(wants.alsoLookingFor())
            );
            satisfaction += gapsFilled * GAPS_WEIGHT;
        }

        // Clamp to 0.0-1.0
        return Math.min(1.0, Math.max(0.0, satisfaction));
    }

    /**
     * Creates an updated wants record with the computed satisfaction score.
     *
     * @param centroid the directory's centroid
     * @param wants    the current wants
     * @return new wants with updated satisfaction
     */
    public DirectoryWants withSatisfaction(DirectoryCentroid centroid, DirectoryWants wants) {
        double satisfaction = compute(centroid, wants);
        return new DirectoryWants(
                wants.topics(),
                wants.entities(),
                wants.alsoLookingFor(),
                wants.source(),
                satisfaction
        );
    }

    /**
     * Computes coverage ratio: how much of the wanted set is present in the actual set.
     *
     * @param actual the items that are present (from centroid)
     * @param wanted the items that are wanted
     * @return ratio of wanted items that are covered, 0.0 if wanted is empty
     */
    static double coverageRatio(Set<String> actual, Set<String> wanted) {
        if (wanted.isEmpty()) return 0.0;

        Set<String> covered = new HashSet<>(wanted);
        covered.retainAll(actual);

        return (double) covered.size() / wanted.size();
    }

    /**
     * Converts a list of strings to a lowercase set for case-insensitive comparison.
     */
    static Set<String> toLowerSet(List<String> items) {
        if (items == null || items.isEmpty()) return Set.of();
        return items.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }
}
