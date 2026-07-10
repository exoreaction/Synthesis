package io.exoreaction.synthesis.tracking;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct CRUD tests for FileTrackingDatabase — insert, query, update,
 * audit log, hash lookup, and status filtering.
 */
class FileTrackingDatabaseOperationsTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private FileTrackingDatabase trackingDb;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        trackingDb = new FileTrackingDatabase(db);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null && !db.isClosed()) {
            db.close();
        }
    }

    // --- initial state ---

    @Test
    void getMovementCount_fresh_isZero() throws SQLException {
        assertEquals(0, trackingDb.getMovementCount());
    }

    @Test
    void getByStatus_fresh_isEmpty() throws SQLException {
        List<FileMovementRecord> result = trackingDb.getByStatus(MovementStatus.DETECTED);
        assertTrue(result.isEmpty());
    }

    @Test
    void getPendingDeletions_fresh_isEmpty() throws SQLException {
        assertTrue(trackingDb.getPendingDeletions().isEmpty());
    }

    @Test
    void getCleanupEligible_fresh_isEmpty() throws SQLException {
        assertTrue(trackingDb.getCleanupEligible().isEmpty());
    }

    // --- recordMovement ---

    @Test
    void recordMovement_returnsPositiveId() throws SQLException {
        long id = trackingDb.recordMovement(detected("hash1", "src.txt", "dst.txt"));
        assertTrue(id > 0, "recordMovement should return positive ID");
    }

    @Test
    void recordMovement_incrementsCount() throws SQLException {
        trackingDb.recordMovement(detected("hash1", "a.txt", "b.txt"));
        trackingDb.recordMovement(detected("hash2", "c.txt", "d.txt"));
        assertEquals(2, trackingDb.getMovementCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10})
    void recordMovement_multipleRecords_countMatches(int count) throws SQLException {
        for (int i = 0; i < count; i++) {
            trackingDb.recordMovement(detected("hash" + i, "src" + i + ".txt", "dst" + i + ".txt"));
        }
        assertEquals(count, trackingDb.getMovementCount());
    }

    @ParameterizedTest
    @EnumSource(DetectionMethod.class)
    void recordMovement_allDetectionMethods_stored(DetectionMethod method) throws SQLException {
        FileMovementRecord record = FileMovementRecord.detected(
                "hashX", "/ws1", "source.txt", "/ws2", "target.txt",
                512L, "MARKDOWN", method);

        long id = trackingDb.recordMovement(record);
        assertTrue(id > 0);

        List<FileMovementRecord> found = trackingDb.getByContentHash("hashX");
        assertEquals(1, found.size());
        assertEquals(method, found.get(0).detectionMethod());
    }

    // --- getByStatus ---

    @Test
    void getByStatus_detected_findsRecordedMovement() throws SQLException {
        trackingDb.recordMovement(detected("hash1", "src.txt", "dst.txt"));

        List<FileMovementRecord> result = trackingDb.getByStatus(MovementStatus.DETECTED);
        assertEquals(1, result.size());
        assertEquals(MovementStatus.DETECTED, result.get(0).status());
    }

    @Test
    void getByStatus_confirmed_emptyBeforeUpdate() throws SQLException {
        trackingDb.recordMovement(detected("hash1", "src.txt", "dst.txt"));

        // DETECTED record should NOT appear under CONFIRMED
        List<FileMovementRecord> confirmed = trackingDb.getByStatus(MovementStatus.CONFIRMED);
        assertTrue(confirmed.isEmpty());
    }

    // --- updateStatus ---

    @Test
    void updateStatus_changesStatusToConfirmed() throws SQLException {
        long id = trackingDb.recordMovement(detected("hash1", "src.txt", "dst.txt"));
        trackingDb.updateStatus(id, MovementStatus.CONFIRMED, "manually confirmed");

        List<FileMovementRecord> confirmed = trackingDb.getByStatus(MovementStatus.CONFIRMED);
        assertEquals(1, confirmed.size());
        assertEquals(MovementStatus.CONFIRMED, confirmed.get(0).status());
    }

    @Test
    void updateStatus_removesFromDetected() throws SQLException {
        long id = trackingDb.recordMovement(detected("hash1", "src.txt", "dst.txt"));
        trackingDb.updateStatus(id, MovementStatus.CONFIRMED, "confirmed");

        assertTrue(trackingDb.getByStatus(MovementStatus.DETECTED).isEmpty());
    }

    // --- startSafetyPeriod ---

    @Test
    void startSafetyPeriod_changesStatusToConfirmed() throws SQLException {
        long id = trackingDb.recordMovement(detected("hash1", "src.txt", "dst.txt"));
        trackingDb.startSafetyPeriod(id, 7);

        List<FileMovementRecord> confirmed = trackingDb.getByStatus(MovementStatus.CONFIRMED);
        assertEquals(1, confirmed.size());
        assertNotNull(confirmed.get(0).safetyExpiry(), "Safety expiry should be set");
    }

    @Test
    void startSafetyPeriod_safetyExpiryInFuture() throws SQLException {
        long id = trackingDb.recordMovement(detected("hash1", "src.txt", "dst.txt"));
        trackingDb.startSafetyPeriod(id, 7);

        List<FileMovementRecord> confirmed = trackingDb.getByStatus(MovementStatus.CONFIRMED);
        Instant expiry = confirmed.get(0).safetyExpiry();
        assertNotNull(expiry);
        assertTrue(expiry.isAfter(Instant.now()), "Safety expiry should be in the future for 7 days");
    }

    // --- getCleanupEligible ---

    @Test
    void getCleanupEligible_expiredSafetyPeriod_findsMovement() throws SQLException {
        // Insert a record that is CONFIRMED with safety_expiry 1 hour in the past
        FileMovementRecord expiredRecord = new FileMovementRecord(
                0, Instant.now(), "exp_hash",
                "/ws1", "src.txt", "/ws2", "dst.txt",
                1024L, "MARKDOWN",
                MovementStatus.CONFIRMED, DetectionMethod.HASH_MATCH,
                Instant.now().minusSeconds(3600), null);
        trackingDb.recordMovement(expiredRecord);

        List<FileMovementRecord> eligible = trackingDb.getCleanupEligible();
        assertFalse(eligible.isEmpty(), "Movement with expired safety period should be eligible");
    }

    @Test
    void getCleanupEligible_activeSafetyPeriod_notEligible() throws SQLException {
        long id = trackingDb.recordMovement(detected("hash1", "src.txt", "dst.txt"));
        trackingDb.startSafetyPeriod(id, 30); // 30 days in the future

        assertTrue(trackingDb.getCleanupEligible().isEmpty(),
                "Movement with active safety period should not be eligible");
    }

    // --- getByContentHash ---

    @Test
    void getByContentHash_matchingHash_returnsRecord() throws SQLException {
        trackingDb.recordMovement(detected("unique_hash", "src.txt", "dst.txt"));

        List<FileMovementRecord> found = trackingDb.getByContentHash("unique_hash");
        assertEquals(1, found.size());
        assertEquals("unique_hash", found.get(0).contentHash());
    }

    @Test
    void getByContentHash_noMatch_returnsEmpty() throws SQLException {
        trackingDb.recordMovement(detected("hash_A", "src.txt", "dst.txt"));

        assertTrue(trackingDb.getByContentHash("hash_B").isEmpty());
    }

    @Test
    void getByContentHash_duplicateHashes_returnsAll() throws SQLException {
        trackingDb.recordMovement(detected("same_hash", "src1.txt", "dst1.txt"));
        trackingDb.recordMovement(detected("same_hash", "src2.txt", "dst2.txt"));

        List<FileMovementRecord> found = trackingDb.getByContentHash("same_hash");
        assertEquals(2, found.size());
    }

    @Test
    void getByContentHash_eightCharPrefix_matchesFullHash() throws SQLException {
        // Reproduces issue #403: `track` prints an 8-char prefix, `--audit` must accept it.
        String fullHash = "d0851cfd29a5581faad85d70cdcf31fe";
        trackingDb.recordMovement(detected(fullHash, "src.txt", "dst.txt"));

        List<FileMovementRecord> found = trackingDb.getByContentHash(fullHash.substring(0, 8));
        assertEquals(1, found.size());
        assertEquals(fullHash, found.get(0).contentHash());
    }

    @Test
    void getByContentHash_prefixMustMatchStart_notMiddle() throws SQLException {
        trackingDb.recordMovement(detected("abcdef1234567890", "src.txt", "dst.txt"));

        // "cdef" is a substring but not a prefix — must not match.
        assertTrue(trackingDb.getByContentHash("cdef").isEmpty());
    }

    // --- getPendingDeletions ---

    @Test
    void getPendingDeletions_nullTargetAndDetected_isFound() throws SQLException {
        // A pending deletion has no target path
        FileMovementRecord pendingDelete = new FileMovementRecord(
                0, Instant.now(), "del_hash",
                "/ws1", "deleted.txt",
                null, null,   // no target
                1024L, "MARKDOWN",
                MovementStatus.DETECTED, DetectionMethod.HASH_MATCH,
                null, null
        );
        trackingDb.recordMovement(pendingDelete);

        List<FileMovementRecord> pending = trackingDb.getPendingDeletions();
        assertFalse(pending.isEmpty(), "Pending deletion should be found");
    }

    @Test
    void getPendingDeletions_withTargetPath_notFound() throws SQLException {
        trackingDb.recordMovement(detected("hash1", "src.txt", "dst.txt"));

        // Has a target path → not a pending deletion
        assertTrue(trackingDb.getPendingDeletions().isEmpty());
    }

    // --- getMovementsSince ---

    @Test
    void getMovementsSince_recentMovement_found() throws SQLException {
        Instant before = Instant.now().minusSeconds(1);
        trackingDb.recordMovement(detected("hash1", "src.txt", "dst.txt"));

        List<FileMovementRecord> found = trackingDb.getMovementsSince(before);
        assertEquals(1, found.size());
    }

    @Test
    void getMovementsSince_futureFilter_returnsEmpty() throws SQLException {
        trackingDb.recordMovement(detected("hash1", "src.txt", "dst.txt"));

        Instant future = Instant.now().plusSeconds(3600);
        List<FileMovementRecord> found = trackingDb.getMovementsSince(future);
        assertTrue(found.isEmpty());
    }

    // --- getAuditLog ---

    @Test
    void getAuditLog_afterRecordMovement_hasDetectedEntry() throws SQLException {
        long id = trackingDb.recordMovement(detected("hash1", "src.txt", "dst.txt"));

        List<FileTrackingDatabase.AuditEntry> log = trackingDb.getAuditLog(id);
        assertFalse(log.isEmpty(), "recordMovement should create an audit entry");
        assertEquals("detected", log.get(0).action());
    }

    @Test
    void getAuditLog_afterUpdateStatus_hasAdditionalEntry() throws SQLException {
        long id = trackingDb.recordMovement(detected("hash1", "src.txt", "dst.txt"));
        trackingDb.updateStatus(id, MovementStatus.CONFIRMED, "confirmed by test");

        List<FileTrackingDatabase.AuditEntry> log = trackingDb.getAuditLog(id);
        assertTrue(log.size() >= 2, "Should have at least 2 audit entries (detected + status change)");
    }

    @Test
    void getAuditLog_nonExistentId_returnsEmpty() throws SQLException {
        List<FileTrackingDatabase.AuditEntry> log = trackingDb.getAuditLog(9999L);
        assertTrue(log.isEmpty());
    }

    // --- helpers ---

    private FileMovementRecord detected(String hash, String src, String dst) {
        return FileMovementRecord.detected(
                hash, "/workspace1", src, "/workspace1", dst,
                1024L, "MARKDOWN", DetectionMethod.HASH_MATCH);
    }
}
