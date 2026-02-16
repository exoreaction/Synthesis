package io.exoreaction.synthesis.tracking;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.core.ScanState;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Core logic for detecting and correlating file movements.
 *
 * <p>Works by comparing deleted files (by content hash) against added files
 * across workspaces. When a hash match is found, a movement is recorded.
 *
 * <p>Also handles "pending" deletions: when a file is deleted in one workspace
 * but the matching addition hasn't been detected yet (because the other
 * workspace hasn't been scanned), the deletion is recorded as pending and
 * resolved on the next scan of any workspace.
 */
public class FileMovementTracker {

    private static final Logger LOG = Logger.getLogger(FileMovementTracker.class.getName());

    private final FileTrackingDatabase trackingDb;
    private final int safetyPeriodDays;

    public FileMovementTracker(FileTrackingDatabase trackingDb, int safetyPeriodDays) {
        this.trackingDb = trackingDb;
        this.safetyPeriodDays = safetyPeriodDays;
    }

    /**
     * Detects file movements within a single workspace's change set.
     * Files deleted and added with the same hash are treated as renames/moves.
     *
     * @param workspacePath the workspace being scanned
     * @param changes       the detected changes
     * @return number of movements detected
     */
    public int detectIntraWorkspaceMovements(String workspacePath,
                                              ScanState.ChangeSet changes) throws SQLException {
        if (!changes.hasChanges()) return 0;

        // Build hash index of added files
        Map<String, FileMetadata> addedByHash = new HashMap<>();
        for (FileMetadata added : changes.added()) {
            if (added.contentHash() != null) {
                addedByHash.put(added.contentHash(), added);
            }
        }

        int detected = 0;

        // For each deleted file, check if same hash appeared as added
        for (FileMetadata deleted : findDeletedWithHashes(changes, workspacePath)) {
            if (deleted.contentHash() == null) continue;

            FileMetadata matchingAdd = addedByHash.remove(deleted.contentHash());
            if (matchingAdd != null) {
                FileMovementRecord movement = FileMovementRecord.detected(
                        deleted.contentHash(),
                        workspacePath, deleted.relativePath(),
                        workspacePath, matchingAdd.relativePath(),
                        deleted.sizeBytes(),
                        deleted.fileType() != null ? deleted.fileType().name() : null,
                        DetectionMethod.HASH_MATCH
                );

                long id = trackingDb.recordMovement(movement);
                trackingDb.startSafetyPeriod(id, safetyPeriodDays);
                detected++;

                LOG.info("Intra-workspace move detected: " + deleted.relativePath()
                        + " -> " + matchingAdd.relativePath());
            }
        }

        return detected;
    }

    /**
     * Detects cross-workspace movements by correlating deletions in one
     * workspace against additions in another.
     *
     * @param sourceWorkspace workspace where files were deleted
     * @param sourceChanges   changes in the source workspace
     * @param targetWorkspace workspace where files were added
     * @param targetChanges   changes in the target workspace
     * @return number of movements detected
     */
    public int detectCrossWorkspaceMovements(
            String sourceWorkspace, ScanState.ChangeSet sourceChanges,
            String targetWorkspace, ScanState.ChangeSet targetChanges) throws SQLException {

        // Build hash index of added files in target
        Map<String, FileMetadata> addedByHash = new HashMap<>();
        for (FileMetadata added : targetChanges.added()) {
            if (added.contentHash() != null) {
                addedByHash.put(added.contentHash(), added);
            }
        }

        int detected = 0;

        // Check deleted files from source against added files in target
        for (FileMetadata deleted : findDeletedWithHashes(sourceChanges, sourceWorkspace)) {
            if (deleted.contentHash() == null) continue;

            FileMetadata matchingAdd = addedByHash.remove(deleted.contentHash());
            if (matchingAdd != null) {
                FileMovementRecord movement = FileMovementRecord.detected(
                        deleted.contentHash(),
                        sourceWorkspace, deleted.relativePath(),
                        targetWorkspace, matchingAdd.relativePath(),
                        deleted.sizeBytes(),
                        deleted.fileType() != null ? deleted.fileType().name() : null,
                        DetectionMethod.HASH_MATCH
                );

                long id = trackingDb.recordMovement(movement);
                trackingDb.startSafetyPeriod(id, safetyPeriodDays);
                detected++;

                LOG.info("Cross-workspace move: " + sourceWorkspace + ":" + deleted.relativePath()
                        + " -> " + targetWorkspace + ":" + matchingAdd.relativePath());
            }
        }

        return detected;
    }

    /**
     * Records unmatched deletions as pending, and tries to resolve
     * previously pending deletions against new additions.
     *
     * @param workspacePath workspace being scanned
     * @param changes       the changes detected
     * @return number of pending deletions resolved
     */
    public int resolvePendingDeletions(String workspacePath,
                                        ScanState.ChangeSet changes) throws SQLException {
        // Try to match new additions against pending deletions
        int resolved = 0;
        List<FileMovementRecord> pending = trackingDb.getPendingDeletions();

        Map<String, FileMovementRecord> pendingByHash = new HashMap<>();
        for (FileMovementRecord p : pending) {
            pendingByHash.put(p.contentHash(), p);
        }

        for (FileMetadata added : changes.added()) {
            if (added.contentHash() == null) continue;

            FileMovementRecord match = pendingByHash.remove(added.contentHash());
            if (match != null) {
                // Update the pending deletion with target information
                trackingDb.updateStatus(match.id(), MovementStatus.CONFIRMED,
                        "Resolved: target found at " + workspacePath + ":" + added.relativePath());
                trackingDb.startSafetyPeriod(match.id(), safetyPeriodDays);
                resolved++;

                LOG.info("Pending deletion resolved: " + match.sourcePath()
                        + " -> " + workspacePath + ":" + added.relativePath());
            }
        }

        return resolved;
    }

    /**
     * Checks all confirmed movements with expired safety periods
     * and transitions them to CLEANUP_ELIGIBLE.
     *
     * @return number of movements now eligible for cleanup
     */
    public int processExpiredSafetyPeriods() throws SQLException {
        List<FileMovementRecord> eligible = trackingDb.getCleanupEligible();
        int count = 0;
        for (FileMovementRecord movement : eligible) {
            trackingDb.updateStatus(movement.id(), MovementStatus.CLEANUP_ELIGIBLE,
                    "Safety period expired. Source eligible for cleanup.");
            count++;
        }
        return count;
    }

    /**
     * Helper: extract FileMetadata for deleted files that have hashes.
     * Since ChangeSet.deleted() only contains paths (not metadata),
     * we need to reconstruct metadata from the previous scan state.
     * For now, we work with what's available from the added/modified lists
     * which do have FileMetadata objects. Deleted files only have paths,
     * so we create minimal FileMetadata stubs for hash correlation.
     *
     * In practice, deleted files' hashes come from the previous ScanState.
     */
    private List<FileMetadata> findDeletedWithHashes(ScanState.ChangeSet changes,
                                                      String workspace) {
        // ChangeSet.deleted() returns List<String> (paths only).
        // The hashes for deleted files must come from the previous ScanState.
        // This method is a placeholder -- the actual integration in MaintainCommand
        // passes the previous ScanState's FileEntry map for hash lookup.
        return List.of();
    }

    /**
     * Detects movements using the previous scan state to look up hashes of deleted files.
     *
     * @param previousEntries the entries from the previous scan state (path -> FileEntry)
     * @param deletedPaths    paths of deleted files
     * @param addedFiles      newly added files (with hashes)
     * @param sourceWorkspace source workspace path
     * @param targetWorkspace target workspace path (same as source for intra-workspace)
     * @return number of movements detected
     */
    public int detectMovementsWithHistory(
            Map<String, ScanState.FileEntry> previousEntries,
            List<String> deletedPaths,
            List<FileMetadata> addedFiles,
            String sourceWorkspace,
            String targetWorkspace) throws SQLException {

        // Build hash->added lookup
        Map<String, FileMetadata> addedByHash = new HashMap<>();
        for (FileMetadata added : addedFiles) {
            if (added.contentHash() != null) {
                addedByHash.put(added.contentHash(), added);
            }
        }

        int detected = 0;

        for (String deletedPath : deletedPaths) {
            ScanState.FileEntry prevEntry = previousEntries.get(deletedPath);
            if (prevEntry == null || prevEntry.hash() == null) continue;

            FileMetadata matchingAdd = addedByHash.remove(prevEntry.hash());
            if (matchingAdd != null) {
                FileMovementRecord movement = FileMovementRecord.detected(
                        prevEntry.hash(),
                        sourceWorkspace, deletedPath,
                        targetWorkspace, matchingAdd.relativePath(),
                        prevEntry.size(),
                        matchingAdd.fileType() != null ? matchingAdd.fileType().name() : null,
                        DetectionMethod.HASH_MATCH
                );

                long id = trackingDb.recordMovement(movement);
                trackingDb.startSafetyPeriod(id, safetyPeriodDays);
                detected++;
            }
        }

        return detected;
    }
}
