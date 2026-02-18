package io.exoreaction.synthesis.tracking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MovementStatus and DetectionMethod enums, and FileMovementRecord factory.
 */
class MovementStatusTest {

    // --- MovementStatus values ---

    @Test
    void movementStatus_hasFiveValues() {
        assertEquals(5, MovementStatus.values().length);
    }

    @ParameterizedTest
    @CsvSource({
        "DETECTED,         detected",
        "CONFIRMED,        confirmed",
        "CLEANUP_ELIGIBLE, cleanup_eligible",
        "CLEANED,          cleaned",
        "REVERTED,         reverted"
    })
    void movementStatus_dbValue(String enumName, String expectedDbValue) {
        MovementStatus status = MovementStatus.valueOf(enumName);
        assertEquals(expectedDbValue, status.dbValue());
    }

    @ParameterizedTest
    @EnumSource(MovementStatus.class)
    void movementStatus_dbValue_roundTrip(MovementStatus status) {
        assertEquals(status, MovementStatus.fromDbValue(status.dbValue()),
                "Round-trip should recover " + status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DETECTED", "unknown", "", "CONFIRMED_X"})
    void movementStatus_fromDbValue_invalidThrows(String invalid) {
        assertThrows(IllegalArgumentException.class,
                () -> MovementStatus.fromDbValue(invalid));
    }

    // --- DetectionMethod values ---

    @Test
    void detectionMethod_hasThreeValues() {
        assertEquals(3, DetectionMethod.values().length);
    }

    @ParameterizedTest
    @CsvSource({
        "HASH_MATCH,   hash_match",
        "WATCH_EVENT,  watch_event",
        "MANUAL,       manual"
    })
    void detectionMethod_dbValue(String enumName, String expectedDbValue) {
        DetectionMethod method = DetectionMethod.valueOf(enumName);
        assertEquals(expectedDbValue, method.dbValue());
    }

    @ParameterizedTest
    @EnumSource(DetectionMethod.class)
    void detectionMethod_dbValue_roundTrip(DetectionMethod method) {
        assertEquals(method, DetectionMethod.fromDbValue(method.dbValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HASH_MATCH", "unknown", "", "hash"})
    void detectionMethod_fromDbValue_invalidThrows(String invalid) {
        assertThrows(IllegalArgumentException.class,
                () -> DetectionMethod.fromDbValue(invalid));
    }

    // --- FileMovementRecord.detected() factory ---

    @Test
    void fileMovementRecord_detected_hasZeroId() {
        FileMovementRecord record = FileMovementRecord.detected(
                "hash123", "/src", "file.txt", "/dst", "docs/file.txt",
                512L, "MARKDOWN", DetectionMethod.HASH_MATCH);
        assertEquals(0L, record.id(), "Fresh detected record should have id=0");
    }

    @Test
    void fileMovementRecord_detected_statusIsDetected() {
        FileMovementRecord record = FileMovementRecord.detected(
                "hash", "/ws1", "a.md", "/ws2", "b.md",
                100L, "MARKDOWN", DetectionMethod.MANUAL);
        assertEquals(MovementStatus.DETECTED, record.status());
    }

    @Test
    void fileMovementRecord_detected_safetyExpiryIsNull() {
        FileMovementRecord record = FileMovementRecord.detected(
                "hash", "/ws", "x.txt", "/ws2", "y.txt",
                0L, "TEXT", DetectionMethod.WATCH_EVENT);
        assertNull(record.safetyExpiry());
    }

    @Test
    void fileMovementRecord_detected_notesIsNull() {
        FileMovementRecord record = FileMovementRecord.detected(
                "hash", "/ws", "x.txt", "/ws2", "y.txt",
                0L, "TEXT", DetectionMethod.HASH_MATCH);
        assertNull(record.notes());
    }

    @ParameterizedTest
    @EnumSource(DetectionMethod.class)
    void fileMovementRecord_detected_allDetectionMethods(DetectionMethod method) {
        FileMovementRecord record = FileMovementRecord.detected(
                "hash", "/ws1", "file.txt", "/ws2", "file.txt",
                1024L, "CODE", method);
        assertEquals(method, record.detectionMethod());
    }

    @Test
    void fileMovementRecord_detected_preservesAllFields() {
        String hash = "abc123def456";
        String srcWs = "/source/workspace";
        String srcPath = "src/Main.java";
        String tgtWs = "/target/workspace";
        String tgtPath = "archive/Main.java";
        long size = 4096L;
        String fileType = "CODE";

        FileMovementRecord record = FileMovementRecord.detected(
                hash, srcWs, srcPath, tgtWs, tgtPath, size, fileType, DetectionMethod.HASH_MATCH);

        assertEquals(hash, record.contentHash());
        assertEquals(srcWs, record.sourceWorkspace());
        assertEquals(srcPath, record.sourcePath());
        assertEquals(tgtWs, record.targetWorkspace());
        assertEquals(tgtPath, record.targetPath());
        assertEquals(size, record.fileSize());
        assertEquals(fileType, record.fileType());
        assertNotNull(record.timestamp(), "Timestamp should be set");
    }
}
