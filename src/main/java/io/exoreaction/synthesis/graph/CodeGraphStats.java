package io.exoreaction.synthesis.graph;

import java.time.Instant;

/**
 * Statistics from a code graph extraction run.
 *
 * <p>{@code dependenciesFound} and {@code externalDeps} count persisted
 * {@code code_dependencies} rows, not extracted edges (#469): the table is
 * {@code UNIQUE(workspace_path, source_file, target_class, target_package)}, so several
 * edges may collapse onto one row. After a full extraction the count therefore equals
 * {@link CodeGraphRepository#countDependencies}; after an incremental update it is the
 * number of rows written for the changed files.
 *
 * @param filesProcessed   number of source files processed -- on an incremental update this
 *                         includes files pulled in because a change re-resolved one of their
 *                         edges (#459), not only the files in the changed set
 * @param dependenciesFound number of dependency rows persisted
 * @param crossFormatLinks  number of cross-format links found
 * @param packagesFound     number of distinct packages detected
 * @param externalDeps      number of persisted rows whose target is external (non-project)
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
