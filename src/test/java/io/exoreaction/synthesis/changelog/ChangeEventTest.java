package io.exoreaction.synthesis.changelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ChangeEvent.ChangeType enum — dbValue, fromDbValue, round-trip,
 * and ChangeEvent record construction.
 */
class ChangeEventTest {

    // --- ChangeType enum values ---

    @Test
    void changeType_hasFourValues() {
        assertEquals(4, ChangeEvent.ChangeType.values().length);
    }

    // --- dbValue ---

    @ParameterizedTest
    @CsvSource({
        "ADDED,    added",
        "MODIFIED, modified",
        "DELETED,  deleted",
        "MOVED,    moved"
    })
    void changeType_dbValue(String enumName, String expectedDbValue) {
        ChangeEvent.ChangeType type = ChangeEvent.ChangeType.valueOf(enumName);
        assertEquals(expectedDbValue, type.dbValue());
    }

    @Test
    void changeType_dbValues_areLowercase() {
        for (ChangeEvent.ChangeType type : ChangeEvent.ChangeType.values()) {
            assertEquals(type.dbValue(), type.dbValue().toLowerCase(),
                    type + ".dbValue() should be lowercase");
        }
    }

    // --- fromDbValue round-trip ---

    @ParameterizedTest
    @EnumSource(ChangeEvent.ChangeType.class)
    void changeType_dbValue_roundTrip(ChangeEvent.ChangeType type) {
        assertEquals(type, ChangeEvent.ChangeType.fromDbValue(type.dbValue()),
                "Round-trip should recover same enum constant");
    }

    // --- fromDbValue valid ---

    @ParameterizedTest
    @CsvSource({
        "added,    ADDED",
        "modified, MODIFIED",
        "deleted,  DELETED",
        "moved,    MOVED"
    })
    void changeType_fromDbValue_validValues(String dbValue, String expectedName) {
        ChangeEvent.ChangeType result = ChangeEvent.ChangeType.fromDbValue(dbValue);
        assertEquals(ChangeEvent.ChangeType.valueOf(expectedName), result);
    }

    // --- fromDbValue invalid throws ---

    @ParameterizedTest
    @ValueSource(strings = {"ADDED", "Modified", "unknown", "", "null"})
    void changeType_fromDbValue_invalidThrows(String invalid) {
        assertThrows(IllegalArgumentException.class,
                () -> ChangeEvent.ChangeType.fromDbValue(invalid),
                "'" + invalid + "' should throw IllegalArgumentException");
    }

    // --- ChangeEvent record construction ---

    @Test
    void changeEvent_construction_preservesAllFields() {
        var now = java.time.Instant.now();
        ChangeEvent event = new ChangeEvent(
                42L, "/workspace", now,
                1L, 2L,
                ChangeEvent.ChangeType.ADDED,
                "src/Main.java", null,
                "abc123def456", 1024L,
                "CODE",
                ChangeSignificance.NORMAL
        );

        assertEquals(42L, event.id());
        assertEquals("/workspace", event.workspacePath());
        assertEquals(now, event.detectedTime());
        assertEquals(1L, event.baseSnapshotId());
        assertEquals(2L, event.compareSnapshotId());
        assertEquals(ChangeEvent.ChangeType.ADDED, event.changeType());
        assertEquals("src/Main.java", event.relativePath());
        assertNull(event.previousPath());
        assertEquals("abc123def456", event.contentHash());
        assertEquals(1024L, event.fileSize());
        assertEquals("CODE", event.fileType());
        assertEquals(ChangeSignificance.NORMAL, event.significance());
    }

    @ParameterizedTest
    @EnumSource(ChangeEvent.ChangeType.class)
    void changeEvent_allChangeTypes_canBeConstructed(ChangeEvent.ChangeType changeType) {
        ChangeEvent event = new ChangeEvent(
                1L, "/ws", java.time.Instant.now(),
                10L, 11L, changeType,
                "file.txt", null,
                "hash", 100L, "MARKDOWN",
                ChangeSignificance.NORMAL
        );
        assertEquals(changeType, event.changeType());
    }

    @ParameterizedTest
    @EnumSource(ChangeSignificance.class)
    void changeEvent_allSignificanceLevels_canBeConstructed(ChangeSignificance significance) {
        ChangeEvent event = new ChangeEvent(
                1L, "/ws", java.time.Instant.now(),
                10L, 11L, ChangeEvent.ChangeType.MODIFIED,
                "file.txt", null,
                "hash", 100L, "CODE",
                significance
        );
        assertEquals(significance, event.significance());
    }
}
