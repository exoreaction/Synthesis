package io.exoreaction.synthesis.cli;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ChangedCommand (Git history date-based changes).
 */
class ChangedCommandTest {

    @Test
    void parseSinceIsoDate() {
        Instant result = ChangedCommand.parseSince("2026-02-01");

        assertNotNull(result, "Should parse ISO date");

        LocalDate parsed = LocalDate.ofInstant(result, ZoneId.systemDefault());
        assertEquals(2026, parsed.getYear());
        assertEquals(2, parsed.getMonthValue());
        assertEquals(1, parsed.getDayOfMonth());
    }

    @Test
    void parseSinceDaysDuration() {
        Instant before = Instant.now().minus(7, ChronoUnit.DAYS).minus(1, ChronoUnit.MINUTES);
        Instant result = ChangedCommand.parseSince("7d");
        Instant after = Instant.now().minus(7, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES);

        assertNotNull(result, "Should parse days duration");
        assertTrue(result.isAfter(before), "7d should be approximately 7 days ago");
        assertTrue(result.isBefore(after), "7d should be approximately 7 days ago");
    }

    @Test
    void parseSinceHoursDuration() {
        Instant before = Instant.now().minus(24, ChronoUnit.HOURS).minus(1, ChronoUnit.MINUTES);
        Instant result = ChangedCommand.parseSince("24h");
        Instant after = Instant.now().minus(24, ChronoUnit.HOURS).plus(1, ChronoUnit.MINUTES);

        assertNotNull(result, "Should parse hours duration");
        assertTrue(result.isAfter(before));
        assertTrue(result.isBefore(after));
    }

    @Test
    void parseSinceWeeksDuration() {
        Instant before = Instant.now().minus(14, ChronoUnit.DAYS).minus(1, ChronoUnit.MINUTES);
        Instant result = ChangedCommand.parseSince("2w");
        Instant after = Instant.now().minus(14, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES);

        assertNotNull(result, "Should parse weeks duration");
        assertTrue(result.isAfter(before));
        assertTrue(result.isBefore(after));
    }

    @Test
    void parseSinceMonthsDuration() {
        Instant result = ChangedCommand.parseSince("3m");

        assertNotNull(result, "Should parse months duration");
        // 3 months = ~90 days
        Instant approx = Instant.now().minus(90, ChronoUnit.DAYS);
        long diffSeconds = Math.abs(result.getEpochSecond() - approx.getEpochSecond());
        assertTrue(diffSeconds < 120, "3m should be approximately 90 days ago");
    }

    @Test
    void parseSinceReturnsNullForInvalid() {
        assertNull(ChangedCommand.parseSince("invalid"), "Should return null for invalid input");
        assertNull(ChangedCommand.parseSince(""), "Should return null for empty string");
        assertNull(ChangedCommand.parseSince(null), "Should return null for null");
        assertNull(ChangedCommand.parseSince("xyz"), "Should return null for non-numeric");
    }

    @Test
    void parseSinceHandlesEdgeCases() {
        assertNotNull(ChangedCommand.parseSince("1d"), "Should handle single day");
        assertNotNull(ChangedCommand.parseSince("1h"), "Should handle single hour");
        assertNotNull(ChangedCommand.parseSince("100d"), "Should handle large numbers");
    }

    @Test
    void parseSinceReturnsNullForInvalidUnit() {
        assertNull(ChangedCommand.parseSince("5x"), "Should return null for unknown unit");
        assertNull(ChangedCommand.parseSince("5y"), "Should return null for unsupported unit");
    }
}
