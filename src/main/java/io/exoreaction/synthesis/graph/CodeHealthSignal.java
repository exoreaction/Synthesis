package io.exoreaction.synthesis.graph;

/**
 * A health signal detected in the code knowledge graph.
 *
 * <p>Signals represent potential code quality issues found by analyzing
 * module profiles and dependency edges. Each signal has a unique ID
 * (e.g., "C001_CIRCULAR_DEPENDENCY"), a severity level, and actionable
 * suggestions for resolution.
 *
 * @param signalId    unique signal identifier, e.g. "C001_CIRCULAR_DEPENDENCY"
 * @param severity    severity level: HIGH, MEDIUM, or LOW
 * @param modulePath  affected module path (e.g., "io/exoreaction/synthesis/cli")
 * @param description human-readable description of the issue
 * @param suggestion  actionable fix suggestion
 * @since v1.12.2 (CKG-2.02)
 */
public record CodeHealthSignal(
        String signalId,
        String severity,
        String modulePath,
        String description,
        String suggestion
) {}
