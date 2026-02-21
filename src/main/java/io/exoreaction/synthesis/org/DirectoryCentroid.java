package io.exoreaction.synthesis.org;

import java.time.Instant;
import java.util.List;

/**
 * Semantic centroid of a directory -- what the directory IS.
 *
 * <p>Derived from the enrichment signatures of the directory's files.
 * The centroid represents the semantic center of gravity of the directory's
 * content: its dominant topics, key entities, temporal range, and document types.
 *
 * <p>Confidence reflects both enrichment coverage (how many files have been
 * enriched) and cluster tightness (how semantically cohesive the content is).
 * Higher confidence means the centroid is a reliable representation of what
 * the directory contains.
 *
 * @param topics            ranked topics by frequency, e.g. {@code ["renewable energy", "SDD methodology"]}
 * @param entities          ranked entities by frequency, e.g. {@code ["GreenField Energy", "Jane Smith"]}
 * @param timeframe         temporal range, e.g. {@code "2025-Q4 / 2026-Q1"}
 * @param documentTypes     unique document types present, e.g. {@code ["proposal", "contract"]}
 * @param confidence        cluster tightness 0.0-1.0
 * @param contributingFiles count of enriched physical members
 * @param virtualMembers    count of virtual members (Phase 3)
 * @param lastUpdated       when the centroid was last recomputed
 */
public record DirectoryCentroid(
        List<String> topics,
        List<String> entities,
        String timeframe,
        List<String> documentTypes,
        double confidence,
        int contributingFiles,
        int virtualMembers,
        Instant lastUpdated
) {

    /**
     * Canonical constructor -- ensures list fields are never null.
     */
    public DirectoryCentroid {
        topics = topics != null ? topics : List.of();
        entities = entities != null ? entities : List.of();
        documentTypes = documentTypes != null ? documentTypes : List.of();
    }

    /**
     * Returns an empty centroid with no topics, entities, or document types,
     * zero confidence, and null timestamp.
     */
    public static DirectoryCentroid empty() {
        return new DirectoryCentroid(
                List.of(),
                List.of(),
                null,
                List.of(),
                0.0,
                0,
                0,
                null
        );
    }

    /**
     * Returns {@code true} if this centroid has no meaningful data.
     */
    public boolean isEmpty() {
        return topics.isEmpty()
                && entities.isEmpty()
                && documentTypes.isEmpty()
                && confidence == 0.0
                && contributingFiles == 0;
    }
}
