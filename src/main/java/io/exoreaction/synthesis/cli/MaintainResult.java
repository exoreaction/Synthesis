package io.exoreaction.synthesis.cli;

import java.util.List;

/**
 * Aggregate result of the {@link MaintainOrchestrator} 12-phase loop.
 *
 * <p>Contains the individual {@link PhaseResult} for each phase, the total elapsed
 * wall-clock time, and a convenience method to sum up changes across all phases.
 *
 * @param phases              ordered list of phase results (always 12 entries)
 * @param elapsedMs           wall-clock time in milliseconds
 * @param gitignoredManifests relative paths of knowledge.yaml manifests found on disk but
 *                            excluded from git via .gitignore (issue #309); checked every run,
 *                            independent of whether phase 7 (Index) detected any file changes
 */
public record MaintainResult(
        List<PhaseResult> phases,
        long elapsedMs,
        List<String> gitignoredManifests
) {
    /**
     * Returns the sum of {@link PhaseResult#changeCount()} across all phases.
     */
    public int totalChanges() {
        return phases.stream().mapToInt(PhaseResult::changeCount).sum();
    }

    /**
     * Returns true if all phases succeeded (including skipped phases).
     */
    public boolean allSucceeded() {
        return phases.stream().allMatch(PhaseResult::succeeded);
    }
}
