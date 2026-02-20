package io.exoreaction.synthesis.graph;

/**
 * Represents a documented-by edge between a skill/doc file and a source file.
 */
public record KnowledgeEdge(
    String skillPath,
    String sourcePath,
    String entityName,
    String coverageType,
    long skillModifiedAt,
    long sourceModifiedAt,
    int driftDays,
    String confidence
) {
    public static String computeConfidence(int driftDays) {
        if (driftDays <= 0) return "HIGH";
        if (driftDays <= 7) return "MEDIUM";
        if (driftDays <= 30) return "LOW";
        return "STALE";
    }
}
