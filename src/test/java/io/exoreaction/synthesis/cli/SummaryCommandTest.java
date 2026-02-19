package io.exoreaction.synthesis.cli;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SummaryCommand#parseSince}.
 */
class SummaryCommandTest {

    private final SummaryCommand cmd = new SummaryCommand();

    @Test
    void parseSince_isoDate_parsedCorrectly() {
        Instant result = cmd.parseSince("2026-01-15");

        assertNotNull(result, "Should parse ISO date");
        // Must be before now
        assertTrue(result.isBefore(Instant.now()), "Parsed date should be in the past");
    }

    @Test
    void parseSince_daysDuration_parsedCorrectly() {
        Instant before = Instant.now().minus(7, ChronoUnit.DAYS).minus(1, ChronoUnit.MINUTES);
        Instant result = cmd.parseSince("7d");
        Instant after = Instant.now().minus(7, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES);

        assertNotNull(result, "Should parse days duration");
        assertTrue(result.isAfter(before) && result.isBefore(after),
                "7d should resolve to approximately 7 days ago");
    }

    @Test
    void parseSince_hoursDuration_parsedCorrectly() {
        Instant before = Instant.now().minus(24, ChronoUnit.HOURS).minus(1, ChronoUnit.MINUTES);
        Instant result = cmd.parseSince("24h");
        Instant after = Instant.now().minus(24, ChronoUnit.HOURS).plus(1, ChronoUnit.MINUTES);

        assertNotNull(result, "Should parse hours duration");
        assertTrue(result.isAfter(before) && result.isBefore(after),
                "24h should resolve to approximately 24 hours ago");
    }

    @Test
    void parseSince_weeksDuration_parsedCorrectly() {
        Instant before = Instant.now().minus(14, ChronoUnit.DAYS).minus(1, ChronoUnit.MINUTES);
        Instant result = cmd.parseSince("2w");
        Instant after = Instant.now().minus(14, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES);

        assertNotNull(result, "Should parse weeks duration");
        assertTrue(result.isAfter(before) && result.isBefore(after),
                "2w should resolve to approximately 14 days ago");
    }

    @Test
    void parseSince_monthsDuration_parsedCorrectly() {
        Instant result = cmd.parseSince("3m");

        assertNotNull(result, "Should parse months duration");
        // 3m = ~90 days
        Instant expected = Instant.now().minus(90, ChronoUnit.DAYS);
        assertTrue(Math.abs(result.getEpochSecond() - expected.getEpochSecond()) < 60,
                "3m should resolve to approximately 90 days ago");
    }

    @Test
    void parseSince_invalid_returnsNull() {
        assertNull(cmd.parseSince("invalid"), "Should return null for invalid input");
        assertNull(cmd.parseSince("xyz"), "Should return null for non-numeric");
    }

    @Test
    void parseSince_nullOrBlank_returnsNull() {
        assertNull(cmd.parseSince(null), "Should return null for null");
        assertNull(cmd.parseSince(""), "Should return null for empty string");
        assertNull(cmd.parseSince("  "), "Should return null for blank string");
    }

    @Test
    void parseSince_unknownUnit_returnsNull() {
        assertNull(cmd.parseSince("5x"), "Should return null for unknown unit");
        assertNull(cmd.parseSince("5y"), "Should return null for unsupported unit");
    }

    // --- parseSince delegates to ChangedCommand ---

    @Test
    void parseSince_delegatesToChangedCommand_deterministicInputs() {
        // Null, blank and invalid inputs all return null from both callers
        assertNull(cmd.parseSince(null));
        assertNull(cmd.parseSince(""));
        assertNull(cmd.parseSince("invalid"));
        assertNull(cmd.parseSince("5x"));

        // ISO date is deterministic — both calls must return the same Instant
        assertEquals(
            io.exoreaction.synthesis.cli.ChangedCommand.parseSince("2026-01-15"),
            cmd.parseSince("2026-01-15"),
            "ISO date parse must agree with ChangedCommand"
        );
    }
}
