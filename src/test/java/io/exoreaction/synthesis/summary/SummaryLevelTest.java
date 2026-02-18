package io.exoreaction.synthesis.summary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SummaryLevel enum — values, parsing, CLI values, display.
 */
class SummaryLevelTest {

    @Test
    void enumHasThreeValues() {
        assertEquals(3, SummaryLevel.values().length);
    }

    @Test
    void executive_hasCorrectCliValue() {
        assertEquals("executive", SummaryLevel.EXECUTIVE.cliValue());
    }

    @Test
    void manager_hasCorrectCliValue() {
        assertEquals("manager", SummaryLevel.MANAGER.cliValue());
    }

    @Test
    void developer_hasCorrectCliValue() {
        assertEquals("developer", SummaryLevel.DEVELOPER.cliValue());
    }

    @Test
    void allLevels_haveNonBlankDescription() {
        for (SummaryLevel level : SummaryLevel.values()) {
            assertFalse(level.description().isBlank(), "Description should not be blank for " + level);
        }
    }

    // --- fromString (lenient, defaults to EXECUTIVE) ---

    @Test
    void fromString_null_returnsExecutive() {
        assertEquals(SummaryLevel.EXECUTIVE, SummaryLevel.fromString(null));
    }

    @Test
    void fromString_empty_returnsExecutive() {
        assertEquals(SummaryLevel.EXECUTIVE, SummaryLevel.fromString(""));
    }

    @Test
    void fromString_unknown_returnsExecutive() {
        assertEquals(SummaryLevel.EXECUTIVE, SummaryLevel.fromString("cto"));
    }

    @ParameterizedTest
    @CsvSource({
        "executive,  EXECUTIVE",
        "EXECUTIVE,  EXECUTIVE",
        "manager,    MANAGER",
        "MANAGER,    MANAGER",
        "developer,  DEVELOPER",
        "DEVELOPER,  DEVELOPER"
    })
    void fromString_caseInsensitive(String input, String expectedName) {
        SummaryLevel expected = SummaryLevel.valueOf(expectedName);
        assertEquals(expected, SummaryLevel.fromString(input.trim()));
    }

    // --- cliValue round-trip ---

    @ParameterizedTest
    @ValueSource(strings = {"executive", "manager", "developer"})
    void cliValue_roundTrip(String cliValue) {
        SummaryLevel level = SummaryLevel.fromString(cliValue);
        assertEquals(cliValue, level.cliValue());
    }
}
