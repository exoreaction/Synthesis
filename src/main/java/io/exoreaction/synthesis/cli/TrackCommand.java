package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.tracking.*;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command for querying and managing file movement tracking.
 *
 * <p>Usage:
 * <pre>
 *   synthesis track                      # Show recent movements (last 7 days)
 *   synthesis track --status detected    # Show movements by status
 *   synthesis track --cleanup            # Show cleanup-eligible files
 *   synthesis track --confirm 42         # Confirm cleanup of movement #42
 *   synthesis track --audit abc123       # Audit trail for a content hash
 *   synthesis track --since 30d          # Movements in last 30 days
 * </pre>
 */
@Command(
        name = "track",
        description = "Query and manage file movement tracking",
        mixinStandardHelpOptions = true
)
public class TrackCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--status"}, description = "Filter by status: detected, confirmed, cleanup_eligible, cleaned")
    private String status;

    @Option(names = {"--cleanup"}, description = "Show cleanup-eligible files (past safety period)",
            defaultValue = "false")
    private boolean showCleanup;

    @Option(names = {"--confirm"}, description = "Confirm cleanup of a movement by ID")
    private Long confirmId;

    @Option(names = {"--audit"}, description = "Show full audit trail for a content hash")
    private String auditHash;

    @Option(names = {"--since"}, description = "Show movements since duration (e.g., 7d, 24h, 30d)",
            defaultValue = "7d")
    private String since;

    @Option(names = {"-v", "--verbose"}, description = "Show detailed output", defaultValue = "false")
    private boolean verbose;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public Integer call() {
        try {
            SynthesisDatabase db = SynthesisDatabase.getDefault();
            FileTrackingDatabase trackingDb = new FileTrackingDatabase(db);

            AnsiOutput.printHeader("Synthesis - File Movement Tracking");

            // Handle specific actions first
            if (confirmId != null) {
                return handleConfirm(trackingDb);
            }
            if (auditHash != null) {
                return handleAudit(trackingDb);
            }
            if (showCleanup) {
                return handleCleanup(trackingDb);
            }
            if (status != null) {
                return handleStatusFilter(trackingDb);
            }

            // Default: show recent movements
            return handleRecent(trackingDb);

        } catch (Exception e) {
            AnsiOutput.printError("Track command failed: " + e.getMessage());
            if (verbose) e.printStackTrace();
            return 1;
        }
    }

    private int handleRecent(FileTrackingDatabase trackingDb) throws Exception {
        Instant sinceInstant = parseSince(since);
        List<FileMovementRecord> movements = trackingDb.getMovementsSince(sinceInstant);

        if (movements.isEmpty()) {
            AnsiOutput.printInfo("No file movements detected in the specified period.");
            return 0;
        }

        System.out.println("  " + AnsiOutput.bold("Recent movements (" + movements.size() + "):"));
        System.out.println();

        for (FileMovementRecord m : movements) {
            printMovement(m);
        }

        // Summary
        System.out.println();
        long detected = movements.stream().filter(m -> m.status() == MovementStatus.DETECTED).count();
        long confirmed = movements.stream().filter(m -> m.status() == MovementStatus.CONFIRMED).count();
        long eligible = movements.stream().filter(m -> m.status() == MovementStatus.CLEANUP_ELIGIBLE).count();

        System.out.println("  Summary: " + detected + " detected, " + confirmed + " confirmed, "
                + eligible + " cleanup-eligible");
        return 0;
    }

    private int handleStatusFilter(FileTrackingDatabase trackingDb) throws Exception {
        MovementStatus ms = MovementStatus.fromDbValue(status);
        List<FileMovementRecord> movements = trackingDb.getByStatus(ms);

        if (movements.isEmpty()) {
            AnsiOutput.printInfo("No movements with status: " + status);
            return 0;
        }

        System.out.println("  " + AnsiOutput.bold("Movements with status '" + status + "' ("
                + movements.size() + "):"));
        System.out.println();

        for (FileMovementRecord m : movements) {
            printMovement(m);
        }
        return 0;
    }

    private int handleCleanup(FileTrackingDatabase trackingDb) throws Exception {
        FileMovementTracker tracker = new FileMovementTracker(trackingDb, 7);
        int processed = tracker.processExpiredSafetyPeriods();

        List<FileMovementRecord> eligible = trackingDb.getByStatus(MovementStatus.CLEANUP_ELIGIBLE);

        if (eligible.isEmpty()) {
            AnsiOutput.printInfo("No files eligible for cleanup. All movements are within safety period.");
            if (processed > 0) {
                AnsiOutput.printInfo(processed + " movements transitioned to cleanup-eligible.");
            }
            return 0;
        }

        System.out.println("  " + AnsiOutput.bold("Cleanup-eligible files (" + eligible.size() + "):"));
        System.out.println();

        for (FileMovementRecord m : eligible) {
            printMovement(m);
        }

        System.out.println();
        AnsiOutput.printInfo("Use 'synthesis track --confirm <id>' to acknowledge cleanup.");
        return 0;
    }

    private int handleConfirm(FileTrackingDatabase trackingDb) throws Exception {
        trackingDb.updateStatus(confirmId, MovementStatus.CLEANED,
                "Cleanup confirmed by user");
        AnsiOutput.printSuccess("Movement #" + confirmId + " marked as cleaned.");
        return 0;
    }

    private int handleAudit(FileTrackingDatabase trackingDb) throws Exception {
        if (auditHash == null || auditHash.isBlank()) {
            AnsiOutput.printError("--audit requires a non-empty content hash");
            return 1;
        }
        if (!auditHash.matches("(?i)[0-9a-f]{4,}")) {
            AnsiOutput.printError("--audit expects a hex hash or hex prefix (min 4 chars): " + auditHash);
            return 1;
        }

        List<FileMovementRecord> movements = trackingDb.getByContentHash(auditHash);

        if (movements.isEmpty()) {
            AnsiOutput.printInfo("No movements found for hash: " + auditHash);
            return 0;
        }

        System.out.println("  " + AnsiOutput.bold("Audit trail for hash: " + auditHash));
        System.out.println();

        long distinctHashes = movements.stream().map(FileMovementRecord::contentHash).distinct().count();
        boolean ambiguous = distinctHashes > 1;
        if (ambiguous) {
            AnsiOutput.printWarning("Prefix matched " + distinctHashes
                    + " distinct hashes -- showing full hash per movement below.");
            System.out.println();
        }

        for (FileMovementRecord m : movements) {
            printMovement(m, ambiguous);

            if (verbose) {
                List<FileTrackingDatabase.AuditEntry> auditLog = trackingDb.getAuditLog(m.id());
                for (FileTrackingDatabase.AuditEntry entry : auditLog) {
                    System.out.println("      " + formatTime(entry.timestamp())
                            + " [" + entry.action() + "] " + entry.details());
                }
                System.out.println();
            }
        }
        return 0;
    }

    private void printMovement(FileMovementRecord m) {
        printMovement(m, false);
    }

    private void printMovement(FileMovementRecord m, boolean showFullHash) {
        String statusColor = switch (m.status()) {
            case DETECTED -> AnsiOutput.yellow(m.status().dbValue());
            case CONFIRMED -> AnsiOutput.blue(m.status().dbValue());
            case CLEANUP_ELIGIBLE -> AnsiOutput.green(m.status().dbValue());
            case CLEANED -> AnsiOutput.dim(m.status().dbValue());
            case REVERTED -> AnsiOutput.red(m.status().dbValue());
        };

        System.out.println("  #" + m.id() + " [" + statusColor + "] "
                + formatTime(m.timestamp()) + " " + m.detectionMethod().dbValue());
        System.out.println("    From: " + (m.sourceWorkspace() != null ? m.sourceWorkspace() + ":" : "")
                + m.sourcePath());
        if (m.targetPath() != null) {
            System.out.println("    To:   " + (m.targetWorkspace() != null ? m.targetWorkspace() + ":" : "")
                    + m.targetPath());
        }
        String hashDisplay = m.contentHash() == null ? "n/a"
                : showFullHash ? m.contentHash() : m.contentHash().substring(0, 8) + "...";
        System.out.println("    Size: " + FileUtils.formatSize(m.fileSize()) + " | Hash: " + hashDisplay);
        if (m.safetyExpiry() != null) {
            boolean expired = m.safetyExpiry().isBefore(Instant.now());
            System.out.println("    Safety: " + (expired ? "EXPIRED" : "expires " + formatTime(m.safetyExpiry())));
        }
        System.out.println();
    }

    private String formatTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(TIME_FMT);
    }

    private Instant parseSince(String since) {
        if (since == null || since.isBlank()) {
            return Instant.now().minus(7, ChronoUnit.DAYS);
        }
        String value = since.substring(0, since.length() - 1);
        char unit = since.charAt(since.length() - 1);
        int amount = Integer.parseInt(value);
        return switch (unit) {
            case 'h' -> Instant.now().minus(amount, ChronoUnit.HOURS);
            case 'd' -> Instant.now().minus(amount, ChronoUnit.DAYS);
            case 'w' -> Instant.now().minus(amount * 7L, ChronoUnit.DAYS);
            default -> Instant.now().minus(7, ChronoUnit.DAYS);
        };
    }
}
