package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReportTarget enum — CLI parsing, display names, strict mode.
 */
class ReportTargetTest {

    @Test
    void enumHasThreeValues() {
        assertEquals(3, ReportTarget.values().length);
    }

    @Test
    void ceo_hasCorrectCliValue() {
        assertEquals("ceo", ReportTarget.CEO.cliValue());
    }

    @Test
    void board_hasCorrectCliValue() {
        assertEquals("board", ReportTarget.BOARD.cliValue());
    }

    @Test
    void investor_hasCorrectCliValue() {
        assertEquals("investor", ReportTarget.INVESTOR.cliValue());
    }

    @Test
    void allTargets_haveNonBlankDisplayName() {
        for (ReportTarget t : ReportTarget.values()) {
            assertFalse(t.displayName().isBlank(), "Display name should not be blank for " + t);
        }
    }

    @Test
    void allTargets_haveNonBlankDescription() {
        for (ReportTarget t : ReportTarget.values()) {
            assertFalse(t.description().isBlank(), "Description should not be blank for " + t);
        }
    }

    // --- fromString (lenient, defaults to CEO) ---

    @Test
    void fromString_null_returnsCeo() {
        assertEquals(ReportTarget.CEO, ReportTarget.fromString(null));
    }

    @Test
    void fromString_empty_returnsCeo() {
        assertEquals(ReportTarget.CEO, ReportTarget.fromString(""));
    }

    @Test
    void fromString_unknown_returnsCeo() {
        assertEquals(ReportTarget.CEO, ReportTarget.fromString("cto"));
    }

    @ParameterizedTest
    @CsvSource({
        "ceo,       CEO",
        "CEO,       CEO",
        "board,     BOARD",
        "BOARD,     BOARD",
        "investor,  INVESTOR",
        "INVESTOR,  INVESTOR"
    })
    void fromString_caseInsensitive(String input, String expectedName) {
        assertEquals(ReportTarget.valueOf(expectedName), ReportTarget.fromString(input.trim()));
    }

    // --- fromStringStrict ---

    @Test
    void fromStringStrict_null_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> ReportTarget.fromStringStrict(null));
    }

    @Test
    void fromStringStrict_empty_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> ReportTarget.fromStringStrict(""));
    }

    @Test
    void fromStringStrict_unknown_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> ReportTarget.fromStringStrict("cto"));
    }

    @Test
    void fromStringStrict_validInput_returnsCorrectTarget() {
        assertEquals(ReportTarget.CEO, ReportTarget.fromStringStrict("ceo"));
        assertEquals(ReportTarget.BOARD, ReportTarget.fromStringStrict("board"));
        assertEquals(ReportTarget.INVESTOR, ReportTarget.fromStringStrict("investor"));
    }

    @Test
    void fromStringStrict_errorMessageContainsValidOptions() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ReportTarget.fromStringStrict("bad"));
        String msg = ex.getMessage();
        assertTrue(msg.contains("ceo") || msg.contains("board") || msg.contains("investor"),
                "Error message should mention valid options");
    }

    // --- cliValue round-trip ---

    @ParameterizedTest
    @ValueSource(strings = {"ceo", "board", "investor"})
    void cliValue_roundTrip(String cliValue) {
        ReportTarget target = ReportTarget.fromString(cliValue);
        assertEquals(cliValue, target.cliValue());
    }
}
