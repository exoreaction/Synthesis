package io.exoreaction.synthesis.graph;

/**
 * A detected quality gap in a module.
 *
 * <p>Quality gaps represent structural deficiencies in a module -- things
 * that should exist but do not (tests, interfaces, documentation, etc.).
 * Detected by {@link QualityGapDetector} and scored by {@link CompletenessScorer}.
 *
 * @param modulePath  module path (slash-separated, e.g., "io/exoreaction/synthesis/cli")
 * @param gapType     gap type identifier (e.g., "MISSING_TESTS", "MISSING_INTERFACE")
 * @param severity    severity level: HIGH, MEDIUM, or LOW
 * @param description human-readable description of the gap
 * @param filePath    optional file path related to the gap (may be null)
 * @param suggestion  actionable suggestion for fixing the gap
 * @since v1.12.2 (CKG-3.01)
 */
public record QualityGap(
        String modulePath,
        String gapType,
        String severity,
        String description,
        String filePath,
        String suggestion
) {}
