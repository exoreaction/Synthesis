package io.exoreaction.synthesis.cli;

import java.util.List;

/**
 * Result of executing a single phase within the {@link MaintainOrchestrator} loop.
 *
 * <p>Each phase produces a PhaseResult whether it succeeded, was skipped, or failed.
 * Failed phases do not abort the remaining loop -- the orchestrator continues to the
 * next phase and records the failure.
 *
 * @param phaseNumber  ordinal position (1-12)
 * @param name         human-readable phase name (e.g. "Ingest", "Route")
 * @param succeeded    true if the phase completed (or was skipped); false on failure
 * @param changeCount  number of filesystem or index changes made by this phase
 * @param summary      one-line summary for the output table
 * @param details      per-item lines for {@code --verbose} output
 * @param error        error message if {@code succeeded} is false; null otherwise
 */
public record PhaseResult(
        int phaseNumber,
        String name,
        boolean succeeded,
        int changeCount,
        String summary,
        List<String> details,
        String error
) {
    /**
     * Creates a successful result.
     *
     * @param num      phase number (1-12)
     * @param name     phase name
     * @param changes  number of changes applied
     * @param summary  one-line summary
     * @param details  verbose detail lines
     */
    public static PhaseResult success(int num, String name, int changes,
                                       String summary, List<String> details) {
        return new PhaseResult(num, name, true, changes, summary, details, null);
    }

    /**
     * Creates a skipped result (still counts as success with 0 changes).
     *
     * @param num    phase number (1-12)
     * @param name   phase name
     * @param reason why the phase was skipped
     */
    public static PhaseResult skipped(int num, String name, String reason) {
        return new PhaseResult(num, name, true, 0, "skipped -- " + reason, List.of(), null);
    }

    /**
     * Creates a failed result.
     *
     * @param num   phase number (1-12)
     * @param name  phase name
     * @param error error message
     */
    public static PhaseResult failed(int num, String name, String error) {
        return new PhaseResult(num, name, false, 0, "failed", List.of(), error);
    }
}
