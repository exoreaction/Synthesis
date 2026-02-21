package io.exoreaction.synthesis.org;

import java.util.List;

/**
 * What a directory is TRYING TO BECOME -- its aspirational purpose.
 *
 * <p>Present in two scenarios:
 * <ol>
 *   <li><b>Cold start:</b> centroid is absent or weak; wants are bootstrapped
 *       from directory name, README, or parent directory.</li>
 *   <li><b>Drift:</b> wants diverge from centroid, capturing original purpose
 *       that the current content no longer reflects.</li>
 * </ol>
 *
 * <p>Absent in mature directories where the centroid IS the wants expression.
 * The {@code satisfaction} field measures how well current content matches
 * stated wants (0.0 = completely unmet, 1.0 = fully satisfied).
 *
 * @param topics         desired topic keywords, e.g. {@code ["GreenField opportunity lifecycle"]}
 * @param entities       desired entity names, e.g. {@code ["GreenField Energy"]}
 * @param alsoLookingFor aspirational gaps -- document types that should be present (Phase 4)
 * @param source         provenance description, e.g. {@code "inferred from directory name"}
 * @param satisfaction   want fulfillment score 0.0-1.0 (Phase 3, initially 0.0)
 */
public record DirectoryWants(
        List<String> topics,
        List<String> entities,
        List<String> alsoLookingFor,
        String source,
        double satisfaction
) {

    /**
     * Canonical constructor -- ensures list fields are never null.
     */
    public DirectoryWants {
        topics = topics != null ? topics : List.of();
        entities = entities != null ? entities : List.of();
        alsoLookingFor = alsoLookingFor != null ? alsoLookingFor : List.of();
    }

    /**
     * Returns an empty wants record with no topics, entities, or gaps,
     * null source, and zero satisfaction.
     */
    public static DirectoryWants empty() {
        return new DirectoryWants(
                List.of(),
                List.of(),
                List.of(),
                null,
                0.0
        );
    }

    /**
     * Returns {@code true} if this wants record has no meaningful data.
     */
    public boolean isEmpty() {
        return topics.isEmpty()
                && entities.isEmpty()
                && alsoLookingFor.isEmpty()
                && (source == null || source.isEmpty());
    }
}
