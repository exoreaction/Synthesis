package io.exoreaction.synthesis.changelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ChangeSignificance enum — values, dbValue, fromDbValue, isAtLeast ordering.
 */
class ChangeSignificanceTest {

    @Test
    void enumHasFourValues() {
        assertEquals(4, ChangeSignificance.values().length);
    }

    @Test
    void noise_dbValue() {
        assertEquals("noise", ChangeSignificance.NOISE.dbValue());
    }

    @Test
    void normal_dbValue() {
        assertEquals("normal", ChangeSignificance.NORMAL.dbValue());
    }

    @Test
    void notable_dbValue() {
        assertEquals("notable", ChangeSignificance.NOTABLE.dbValue());
    }

    @Test
    void critical_dbValue() {
        assertEquals("critical", ChangeSignificance.CRITICAL.dbValue());
    }

    // --- fromDbValue ---

    @Test
    void fromDbValue_null_returnsNormal() {
        assertEquals(ChangeSignificance.NORMAL, ChangeSignificance.fromDbValue(null));
    }

    @Test
    void fromDbValue_unknown_returnsNormal() {
        assertEquals(ChangeSignificance.NORMAL, ChangeSignificance.fromDbValue("unknown"));
    }

    @ParameterizedTest
    @CsvSource({
        "noise,    NOISE",
        "normal,   NORMAL",
        "notable,  NOTABLE",
        "critical, CRITICAL"
    })
    void fromDbValue_allValues(String dbValue, String expectedName) {
        assertEquals(ChangeSignificance.valueOf(expectedName), ChangeSignificance.fromDbValue(dbValue));
    }

    // --- dbValue round-trip ---

    @ParameterizedTest
    @EnumSource(ChangeSignificance.class)
    void dbValue_roundTrip(ChangeSignificance significance) {
        String dbValue = significance.dbValue();
        assertEquals(significance, ChangeSignificance.fromDbValue(dbValue));
    }

    // --- isAtLeast ordering ---

    @Test
    void noiseIsAtLeastNoise() {
        assertTrue(ChangeSignificance.NOISE.isAtLeast(ChangeSignificance.NOISE));
    }

    @Test
    void noiseIsNotAtLeastNormal() {
        assertFalse(ChangeSignificance.NOISE.isAtLeast(ChangeSignificance.NORMAL));
    }

    @Test
    void normalIsAtLeastNoise() {
        assertTrue(ChangeSignificance.NORMAL.isAtLeast(ChangeSignificance.NOISE));
    }

    @Test
    void normalIsAtLeastNormal() {
        assertTrue(ChangeSignificance.NORMAL.isAtLeast(ChangeSignificance.NORMAL));
    }

    @Test
    void normalIsNotAtLeastNotable() {
        assertFalse(ChangeSignificance.NORMAL.isAtLeast(ChangeSignificance.NOTABLE));
    }

    @Test
    void notableIsAtLeastNormal() {
        assertTrue(ChangeSignificance.NOTABLE.isAtLeast(ChangeSignificance.NORMAL));
    }

    @Test
    void criticalIsAtLeastAll() {
        for (ChangeSignificance level : ChangeSignificance.values()) {
            assertTrue(ChangeSignificance.CRITICAL.isAtLeast(level),
                    "CRITICAL should be at least " + level);
        }
    }

    @Test
    void ordinalOrdering_noiseLowest() {
        assertTrue(ChangeSignificance.NOISE.ordinal() < ChangeSignificance.NORMAL.ordinal());
        assertTrue(ChangeSignificance.NORMAL.ordinal() < ChangeSignificance.NOTABLE.ordinal());
        assertTrue(ChangeSignificance.NOTABLE.ordinal() < ChangeSignificance.CRITICAL.ordinal());
    }
}
