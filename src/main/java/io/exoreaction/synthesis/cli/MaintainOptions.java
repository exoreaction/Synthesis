package io.exoreaction.synthesis.cli;

/**
 * Immutable options for the {@link MaintainOrchestrator} 9-phase loop.
 *
 * <p>Captures all flags that influence which phases run and how they behave.
 * The {@code sync} and {@code rebalance} flags are retained for backward
 * compatibility with the existing {@code --sync} and {@code --rebalance} CLI flags.
 *
 * @param dryRun            preview all phases without making changes
 * @param verbose           show per-item detail lines
 * @param skipDownloads     skip phases 1 (Ingest) and 2 (Route)
 * @param skipGit           skip git fetch for client codebases
 * @param quiet             show summary line only (for cron)
 * @param json              machine-readable JSON output (for monitoring)
 * @param updateActivityLog auto-append a draft activity-log entry
 * @param sync              run directory identity sync (legacy flag; always true in orchestrator)
 * @param rebalance         run archive rebalance (legacy flag; always true in orchestrator)
 */
public record MaintainOptions(
        boolean dryRun,
        boolean verbose,
        boolean skipDownloads,
        boolean skipGit,
        boolean quiet,
        boolean json,
        boolean updateActivityLog,
        boolean sync,
        boolean rebalance
) {
    /** Sensible defaults: everything off. */
    public static MaintainOptions defaults() {
        return new MaintainOptions(false, false, false, false, false, false, false, false, false);
    }

    /** Dry-run mode with everything else off. */
    public static MaintainOptions forDryRun() {
        return new MaintainOptions(true, false, false, false, false, false, false, false, false);
    }

    /** Quiet mode: one summary line only. */
    public static MaintainOptions quietMode() {
        return new MaintainOptions(false, false, false, false, true, false, false, false, false);
    }
}
