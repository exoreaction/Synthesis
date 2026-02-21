package io.exoreaction.synthesis.org;

import java.util.List;

/**
 * Semantic signature extracted from a single file's enrichment data.
 *
 * <p>Represents the semantic content of one file: its topics, named entities,
 * document type, and temporal context. Used as input to {@link CentroidComputer}
 * for aggregating directory-level centroids.
 *
 * <p>Extraction sources (in priority order):
 * <ol>
 *   <li>Companion file ({@code filename.ext.synthesis.md}) -- parse YAML metadata</li>
 *   <li>Lucene index -- extract keywords, summary, headings fields</li>
 *   <li>Filename heuristic -- tokenize the filename</li>
 * </ol>
 *
 * @param topics         topic keywords extracted from the file
 * @param entities       named entities (people, organizations) extracted from the file
 * @param documentType   inferred document type, e.g. "proposal", "meeting-notes"
 * @param timeframe      temporal context, e.g. "2026-Q1"
 * @param source         how this signature was extracted: "companion", "lucene-index", "filename-heuristic"
 */
public record EnrichmentSignature(
        List<String> topics,
        List<String> entities,
        String documentType,
        String timeframe,
        String source
) {

    /**
     * Canonical constructor -- ensures list fields are never null.
     */
    public EnrichmentSignature {
        topics = topics != null ? topics : List.of();
        entities = entities != null ? entities : List.of();
    }

    /**
     * Returns an empty signature with no topics, entities, or type.
     */
    public static EnrichmentSignature empty() {
        return new EnrichmentSignature(
                List.of(),
                List.of(),
                null,
                null,
                null
        );
    }

    /**
     * Returns {@code true} if this signature has no meaningful data.
     */
    public boolean isEmpty() {
        return topics.isEmpty()
                && entities.isEmpty()
                && (documentType == null || documentType.isEmpty());
    }
}
