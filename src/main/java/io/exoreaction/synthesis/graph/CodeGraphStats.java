package io.exoreaction.synthesis.graph;

import java.time.Instant;

/**
 * Statistics from a code graph extraction run.
 *
 * @param filesProcessed   number of source files processed
 * @param dependenciesFound number of dependency edges extracted
 * @param crossFormatLinks  number of cross-format links found
 * @param packagesFound     number of distinct packages detected
 * @param externalDeps      number of external (non-project) dependencies
 * @param elapsedMs         extraction time in milliseconds
 * @param timestamp         when the extraction was performed
 */
public record CodeGraphStats(
        int filesProcessed,
        int dependenciesFound,
        int crossFormatLinks,
        int packagesFound,
        int externalDeps,
        long elapsedMs,
        Instant timestamp
) {
    /** Empty stats for dry-run or no-op extractions. */
    public static CodeGraphStats empty() {
        return new CodeGraphStats(0, 0, 0, 0, 0, 0, Instant.now());
    }
}
