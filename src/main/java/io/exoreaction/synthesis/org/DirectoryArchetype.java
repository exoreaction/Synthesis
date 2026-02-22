package io.exoreaction.synthesis.org;

import java.util.List;

/**
 * Defines a known directory archetype -- a pattern of what a complete directory
 * of a given type should contain.
 *
 * <p>Archetypes are used for aspirational gap detection (P4-02): when a directory's
 * centroid matches an archetype, the archetype's expected document types are compared
 * against the centroid's actual document types. Missing types become aspirational gaps
 * that populate {@link DirectoryWants#alsoLookingFor()}.
 *
 * <p>For example, a "client-opportunity" archetype expects proposals, contracts,
 * meeting notes, and invoices. If a client directory has proposals and meeting notes
 * but no invoice, the invoice becomes an aspirational gap.
 *
 * @param name              archetype identifier, e.g. "client-opportunity", "project"
 * @param expectedTopics    topic keywords typical for this archetype type
 * @param expectedDocTypes  document types that a complete directory of this type should have
 * @param matchThreshold    minimum centroid-archetype similarity to trigger gap detection (0.0-1.0)
 * @since v2.0 (P4-01)
 */
public record DirectoryArchetype(
        String name,
        List<String> expectedTopics,
        List<String> expectedDocTypes,
        double matchThreshold
) {

    /**
     * Canonical constructor -- ensures list fields are never null.
     */
    public DirectoryArchetype {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Archetype name must not be null or blank");
        }
        expectedTopics = expectedTopics != null ? expectedTopics : List.of();
        expectedDocTypes = expectedDocTypes != null ? expectedDocTypes : List.of();
        if (matchThreshold < 0.0 || matchThreshold > 1.0) {
            throw new IllegalArgumentException("matchThreshold must be between 0.0 and 1.0");
        }
    }

    /**
     * Computes how well a centroid matches this archetype by comparing topics.
     *
     * <p>Uses Jaccard-like similarity: the ratio of matching topic tokens between
     * the centroid's topics and this archetype's expected topics. Comparison is
     * case-insensitive and uses token-level matching (splitting multi-word topics
     * into individual tokens for broader matching).
     *
     * @param centroid the centroid to match against
     * @return similarity score 0.0-1.0
     */
    public double matchScore(DirectoryCentroid centroid) {
        if (centroid == null || centroid.isEmpty() || expectedTopics.isEmpty()) {
            return 0.0;
        }

        // Tokenize archetype topics into individual lowercase words
        java.util.Set<String> archetypeTokens = new java.util.HashSet<>();
        for (String topic : expectedTopics) {
            for (String token : topic.toLowerCase().split("[\\s\\-_]+")) {
                if (!token.isEmpty()) archetypeTokens.add(token);
            }
        }

        // Tokenize centroid topics into individual lowercase words
        java.util.Set<String> centroidTokens = new java.util.HashSet<>();
        for (String topic : centroid.topics()) {
            for (String token : topic.toLowerCase().split("[\\s\\-_]+")) {
                if (!token.isEmpty()) centroidTokens.add(token);
            }
        }

        if (archetypeTokens.isEmpty() || centroidTokens.isEmpty()) {
            return 0.0;
        }

        // Compute Jaccard similarity
        java.util.Set<String> intersection = new java.util.HashSet<>(archetypeTokens);
        intersection.retainAll(centroidTokens);

        java.util.Set<String> union = new java.util.HashSet<>(archetypeTokens);
        union.addAll(centroidTokens);

        return (double) intersection.size() / union.size();
    }

    /**
     * Returns the document types expected by this archetype that are missing
     * from the given centroid.
     *
     * @param centroid the centroid to check against
     * @return list of missing document types (case-insensitive comparison)
     */
    public List<String> findMissingDocTypes(DirectoryCentroid centroid) {
        if (centroid == null || expectedDocTypes.isEmpty()) {
            return List.copyOf(expectedDocTypes);
        }

        java.util.Set<String> presentTypes = new java.util.HashSet<>();
        for (String dt : centroid.documentTypes()) {
            presentTypes.add(dt.toLowerCase());
        }

        return expectedDocTypes.stream()
                .filter(expected -> !presentTypes.contains(expected.toLowerCase()))
                .toList();
    }
}
