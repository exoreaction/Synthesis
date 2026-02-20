package io.exoreaction.synthesis.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StagingCommand utility methods — formatInstant and formatDuration.
 */
class StagingCommandUtilsTest {

    // --- formatInstant ---

    @Test
    void formatInstant_null_returnsNever() {
        assertEquals("never", StagingCommand.formatInstant(null));
    }

    @Test
    void formatInstant_nonNull_returnsFormattedString() {
        String result = StagingCommand.formatInstant(Instant.now());
        assertNotNull(result);
        assertFalse(result.equals("never"), "Non-null instant should not return 'never'");
    }

    @Test
    void formatInstant_formattedAs_yyyyMMddHHmmss() {
        // 2026-02-18 at some specific UTC time
        Instant testInstant = Instant.parse("2026-02-18T10:00:00Z");
        String result = StagingCommand.formatInstant(testInstant);
        assertNotNull(result);
        // Format should be yyyy-MM-dd HH:mm:ss
        assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Format should be yyyy-MM-dd HH:mm:ss, got: " + result);
    }

    @Test
    void formatInstant_containsYear() {
        String result = StagingCommand.formatInstant(Instant.now());
        // Should contain current year (at least 4 digit year)
        assertTrue(result.matches(".*\\d{4}.*"), "Should contain 4-digit year");
    }

    // --- formatDuration ---

    @Test
    void formatDuration_zeroMinutes_returnsZeroM() {
        String result = StagingCommand.formatDuration(Duration.ZERO);
        assertEquals("0m", result);
    }

    @ParameterizedTest
    @CsvSource({
        "PT30M,   30m",
        "PT45M,   45m",
        "PT59M,   59m"
    })
    void formatDuration_minutesOnly_returnsMinutes(String iso, String expected) {
        assertEquals(expected, StagingCommand.formatDuration(Duration.parse(iso)));
    }

    @ParameterizedTest
    @CsvSource({
        "PT1H,    1h 0m",
        "PT1H30M, 1h 30m",
        "PT2H15M, 2h 15m",
        "PT23H59M,23h 59m"
    })
    void formatDuration_hours_returnsHoursAndMinutes(String iso, String expected) {
        assertEquals(expected, StagingCommand.formatDuration(Duration.parse(iso)));
    }

    @ParameterizedTest
    @CsvSource({
        "P1D,     1d 0h",
        "P1DT2H,  1d 2h",
        "P7D,     7d 0h",
        "P30D,    30d 0h"
    })
    void formatDuration_days_returnsDaysAndHours(String iso, String expected) {
        assertEquals(expected, StagingCommand.formatDuration(Duration.parse(iso)));
    }

    @Test
    void formatDuration_moreThanDay_neverIncludesMinutes() {
        // When days > 0, format should be "Xd Yh" not "Xd Yh Zm"
        String result = StagingCommand.formatDuration(Duration.ofDays(2).plusHours(3).plusMinutes(45));
        assertTrue(result.matches("\\d+d \\d+h"),
                "Days format should be 'Xd Yh', got: " + result);
    }

    @Test
    void formatDuration_moreThanHour_neverIncludesSeconds() {
        // When hours > 0 (but no days), format should be "Xh Ym"
        String result = StagingCommand.formatDuration(Duration.ofHours(3).plusMinutes(20));
        assertTrue(result.matches("\\d+h \\d+m"),
                "Hours format should be 'Xh Ym', got: " + result);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10, 30})
    void formatDuration_multipleMinutes_allReturnMinutes(int minutes) {
        String result = StagingCommand.formatDuration(Duration.ofMinutes(minutes));
        assertEquals(minutes + "m", result);
    }

    // --- isCompanionFile ---

    @ParameterizedTest
    @ValueSource(strings = {
        "report.pdf.synthesis.md",
        "photo.png.synthesis.md",
        "data.csv.synthesis.md",
        "document.docx.synthesis.md",
        ".synthesis.md"
    })
    void isCompanionFile_returnsTrueForSynthesisMdSuffix(String name) {
        assertTrue(StagingCommand.isCompanionFile(name),
                name + " should be identified as a companion file");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "report.pdf",
        "photo.png",
        "notes.md",
        "synthesis.md",
        "report.pdf.synthesis",
        "file.synthesis.md.bak",
        ""
    })
    void isCompanionFile_returnsFalseForNonCompanionFiles(String name) {
        assertFalse(StagingCommand.isCompanionFile(name),
                name + " should NOT be identified as a companion file");
    }

    // --- companionMatchesKeywords ---

    @Test
    void companionMatchesKeywords_returnsTrueWhenKeywordFound(@TempDir Path tmp) throws IOException {
        Path companion = tmp.resolve("image.png.synthesis.md");
        Files.writeString(companion,
                "Type: diagram\nOrganizations: Synthesis, eXOReaction\nKeywords: synthesis, architecture");

        assertTrue(StagingCommand.companionMatchesKeywords(companion, List.of("Synthesis")),
                "Should match keyword 'Synthesis' (exact case)");
    }

    @Test
    void companionMatchesKeywords_isCaseInsensitive(@TempDir Path tmp) throws IOException {
        Path companion = tmp.resolve("image.png.synthesis.md");
        Files.writeString(companion, "Organizations: Synthesis, eXOReaction");

        assertTrue(StagingCommand.companionMatchesKeywords(companion, List.of("synthesis")),
                "Should match 'synthesis' against 'Synthesis' (case-insensitive)");
        assertTrue(StagingCommand.companionMatchesKeywords(companion, List.of("EXOREACTION")),
                "Should match 'EXOREACTION' against 'eXOReaction' (case-insensitive)");
    }

    @Test
    void companionMatchesKeywords_orLogic_anyMatchSuffices(@TempDir Path tmp) throws IOException {
        Path companion = tmp.resolve("doc.pdf.synthesis.md");
        Files.writeString(companion, "Organizations: Merkabit\nTopic: consulting");

        assertTrue(StagingCommand.companionMatchesKeywords(companion,
                List.of("synthesis", "merkabit", "quadim")),
                "Should return true when any keyword matches (OR logic)");
    }

    @Test
    void companionMatchesKeywords_returnsFalseWhenNoKeywordFound(@TempDir Path tmp) throws IOException {
        Path companion = tmp.resolve("photo.png.synthesis.md");
        Files.writeString(companion, "Type: photo\nOrganizations: none\nKeywords: nature, landscape");

        assertFalse(StagingCommand.companionMatchesKeywords(companion,
                List.of("synthesis", "exoreaction")),
                "Should return false when no keyword is present in companion");
    }

    @Test
    void companionMatchesKeywords_returnsFalseWhenCompanionMissing(@TempDir Path tmp) {
        Path missingCompanion = tmp.resolve("no-companion.png.synthesis.md");

        assertFalse(StagingCommand.companionMatchesKeywords(missingCompanion,
                List.of("synthesis")),
                "Should return false when companion file does not exist");
    }

    @Test
    void companionMatchesKeywords_handlesMultiwordKeyword(@TempDir Path tmp) throws IOException {
        Path companion = tmp.resolve("infographic.png.synthesis.md");
        Files.writeString(companion,
                "Description: Skill-Driven Development infographic showing human-AI collaboration.");

        assertTrue(StagingCommand.companionMatchesKeywords(companion,
                List.of("Skill-Driven Development")),
                "Should match multi-word keyword phrase");
    }
}
