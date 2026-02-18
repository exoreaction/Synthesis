package io.exoreaction.synthesis.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized expansion of ChangedCommand.parseSince() —
 * all time units, ISO dates, boundaries, and invalid inputs.
 */
class ChangedCommandParameterizedTest {

    // --- valid duration strings ---

    @ParameterizedTest
    @ValueSource(strings = {"1h", "2h", "12h", "24h", "48h", "72h"})
    void parseSince_hours_returnsInstantInPast(String input) {
        Instant result = ChangedCommand.parseSince(input);
        assertNotNull(result, "Should parse hours: " + input);
        assertTrue(result.isBefore(Instant.now()), "Hours result should be in the past");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1d", "2d", "7d", "14d", "30d", "90d", "365d"})
    void parseSince_days_returnsInstantInPast(String input) {
        Instant result = ChangedCommand.parseSince(input);
        assertNotNull(result, "Should parse days: " + input);
        assertTrue(result.isBefore(Instant.now()), "Days result should be in the past");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1w", "2w", "4w", "8w", "52w"})
    void parseSince_weeks_returnsInstantInPast(String input) {
        Instant result = ChangedCommand.parseSince(input);
        assertNotNull(result, "Should parse weeks: " + input);
        assertTrue(result.isBefore(Instant.now()), "Weeks result should be in the past");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1m", "2m", "3m", "6m", "12m"})
    void parseSince_months_returnsInstantInPast(String input) {
        Instant result = ChangedCommand.parseSince(input);
        assertNotNull(result, "Should parse months: " + input);
        assertTrue(result.isBefore(Instant.now()), "Months result should be in the past");
    }

    // --- valid ISO date strings ---

    @ParameterizedTest
    @ValueSource(strings = {"2026-01-01", "2025-12-31", "2024-06-15", "2023-03-10"})
    void parseSince_isoDate_returnsInstant(String input) {
        Instant result = ChangedCommand.parseSince(input);
        assertNotNull(result, "Should parse ISO date: " + input);
        assertTrue(result.isBefore(Instant.now()), "Past date should be before now");
    }

    // --- ordering: larger window = earlier instant ---

    @ParameterizedTest
    @CsvSource({
        "1h,  24h",
        "1d,  7d",
        "1w,  4w",
        "1m,  12m"
    })
    void parseSince_largerWindow_isEarlier(String smaller, String larger) {
        Instant smallerInstant = ChangedCommand.parseSince(smaller);
        Instant largerInstant = ChangedCommand.parseSince(larger);
        assertNotNull(smallerInstant);
        assertNotNull(largerInstant);
        assertTrue(largerInstant.isBefore(smallerInstant),
                larger + " should be before " + smaller + " (larger window → earlier time)");
    }

    // --- null and empty inputs ---

    @ParameterizedTest
    @NullAndEmptySource
    void parseSince_nullOrEmpty_returnsNull(String input) {
        assertNull(ChangedCommand.parseSince(input));
    }

    @Test
    void parseSince_blankString_returnsNull() {
        assertNull(ChangedCommand.parseSince("   "));
    }

    // --- invalid inputs ---

    @ParameterizedTest
    @ValueSource(strings = {"5x", "5y", "5z", "abc", "xyz", "100q"})
    void parseSince_unsupportedUnit_returnsNull(String input) {
        assertNull(ChangedCommand.parseSince(input), "Unknown unit should return null: " + input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "not-a-date", "yesterday", "last week", "foo bar"})
    void parseSince_invalidFormat_returnsNull(String input) {
        assertNull(ChangedCommand.parseSince(input), "Invalid format should return null: " + input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"h", "d", "w", "m"})
    void parseSince_unitWithoutNumber_returnsNull(String input) {
        assertNull(ChangedCommand.parseSince(input), "Unit without number should return null");
    }

    // --- edge case: large numbers ---

    @ParameterizedTest
    @ValueSource(strings = {"1000d", "500w", "200m"})
    void parseSince_largeNumber_returnsInstant(String input) {
        Instant result = ChangedCommand.parseSince(input);
        assertNotNull(result, "Large number should still parse: " + input);
        assertTrue(result.isBefore(Instant.now()));
    }
}
