package io.exoreaction.synthesis.enrichment;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of an enrichment operation.
 *
 * @param filesProcessed       total files examined
 * @param companionsGenerated  companion files created
 * @param companionsSkipped    companion files skipped (already exist)
 * @param errors               files that failed enrichment
 * @param generatedPaths       paths of all generated companion files
 * @param durationMs           total processing time in milliseconds
 * @param enrichmentLevel      the enrichment level used
 */
public record EnrichmentResult(
        int filesProcessed,
        int companionsGenerated,
        int companionsSkipped,
        int errors,
        List<Path> generatedPaths,
        long durationMs,
        EnrichmentLevel enrichmentLevel
) {
    /**
     * Returns a summary string for CLI output.
     */
    public String summary() {
        return String.format(
                "Enrichment complete: %d processed, %d generated, %d skipped, %d errors (%.1fs, level: %s)",
                filesProcessed, companionsGenerated, companionsSkipped, errors,
                durationMs / 1000.0, enrichmentLevel.name());
    }

    /**
     * Returns true if the enrichment had no errors.
     */
    public boolean isSuccess() {
        return errors == 0;
    }
}
