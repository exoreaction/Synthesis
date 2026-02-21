package io.exoreaction.synthesis.org;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes a {@link DirectoryCentroid} from the enrichment signatures of all
 * files in a directory.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>For each file, get its {@link EnrichmentSignature}</li>
 *   <li>Aggregate topics by frequency, rank, take top N (default 10)</li>
 *   <li>Aggregate entities by frequency, rank, take top N (default 5)</li>
 *   <li>Collect unique document types</li>
 *   <li>Compute timeframe from file timestamps as quarter range</li>
 *   <li>Compute confidence: {@code enrichedCount / totalCount * clusterTightness}</li>
 * </ol>
 *
 * <p>Cluster tightness measures topic concentration:
 * {@code 1.0 - (uniqueTopics / (totalTopicMentions * 2))}. A directory where
 * every file mentions the same 3 topics has high tightness; a directory where
 * every file has different topics has low tightness.
 */
public class CentroidComputer {

    private static final int MAX_TOPICS = 10;
    private static final int MAX_ENTITIES = 5;

    /**
     * Computes a centroid from a list of enrichment signatures.
     *
     * @param signatures  the enrichment signatures for files in the directory
     * @param totalFiles  the total number of files in the directory (including non-enriched)
     * @return the computed centroid, or {@link DirectoryCentroid#empty()} for empty input
     */
    public DirectoryCentroid compute(List<EnrichmentSignature> signatures, int totalFiles) {
        if (signatures == null || signatures.isEmpty()) {
            return DirectoryCentroid.empty();
        }

        // Filter to non-empty signatures
        List<EnrichmentSignature> enriched = signatures.stream()
                .filter(s -> !s.isEmpty())
                .toList();

        if (enriched.isEmpty()) {
            return DirectoryCentroid.empty();
        }

        // Aggregate topics by frequency
        Map<String, Integer> topicCounts = new LinkedHashMap<>();
        int totalTopicMentions = 0;
        for (EnrichmentSignature sig : enriched) {
            for (String topic : sig.topics()) {
                String normalized = topic.toLowerCase(Locale.ROOT);
                topicCounts.merge(normalized, 1, Integer::sum);
                totalTopicMentions++;
            }
        }

        // Rank topics by frequency, take top N
        List<String> rankedTopics = topicCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_TOPICS)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // Aggregate entities by frequency
        Map<String, Integer> entityCounts = new LinkedHashMap<>();
        for (EnrichmentSignature sig : enriched) {
            for (String entity : sig.entities()) {
                entityCounts.merge(entity, 1, Integer::sum);
            }
        }

        // Rank entities by frequency, take top N
        List<String> rankedEntities = entityCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_ENTITIES)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // Collect unique document types
        Set<String> docTypeSet = new LinkedHashSet<>();
        for (EnrichmentSignature sig : enriched) {
            if (sig.documentType() != null && !sig.documentType().isEmpty()) {
                docTypeSet.add(sig.documentType());
            }
        }

        // Compute timeframe from enrichment signatures
        String timeframe = computeTimeframe(enriched);

        // Compute confidence
        int enrichedCount = enriched.size();
        int effectiveTotal = Math.max(totalFiles, enrichedCount);
        double enrichmentCoverage = (double) enrichedCount / effectiveTotal;

        // Cluster tightness: how concentrated are the topics?
        // High tightness = topics repeat across files (cohesive cluster)
        // Low tightness = each file has unique topics (scattered)
        double clusterTightness;
        int uniqueTopics = topicCounts.size();
        if (totalTopicMentions == 0) {
            clusterTightness = 0.0;
        } else {
            clusterTightness = 1.0 - ((double) uniqueTopics / (totalTopicMentions * 2));
            clusterTightness = Math.max(0.0, Math.min(1.0, clusterTightness));
        }

        double confidence = enrichmentCoverage * clusterTightness;
        // Scale down for single-file centroids
        if (enrichedCount == 1) {
            confidence *= 0.5;
        }
        confidence = Math.max(0.0, Math.min(1.0, confidence));

        return new DirectoryCentroid(
                List.copyOf(rankedTopics),
                List.copyOf(rankedEntities),
                timeframe,
                List.copyOf(docTypeSet),
                confidence,
                enrichedCount,
                0, // virtualMembers (Phase 3)
                Instant.now()
        );
    }

    /**
     * Computes a timeframe string from enrichment signature timeframes.
     * Aggregates individual file timeframes into a range.
     */
    String computeTimeframe(List<EnrichmentSignature> signatures) {
        Set<String> timeframes = new LinkedHashSet<>();
        for (EnrichmentSignature sig : signatures) {
            if (sig.timeframe() != null && !sig.timeframe().isEmpty()) {
                timeframes.add(sig.timeframe());
            }
        }

        if (timeframes.isEmpty()) {
            return null;
        }

        if (timeframes.size() == 1) {
            return timeframes.iterator().next();
        }

        // Sort and return range
        List<String> sorted = new ArrayList<>(timeframes);
        Collections.sort(sorted);
        return sorted.get(0) + " / " + sorted.get(sorted.size() - 1);
    }
}
