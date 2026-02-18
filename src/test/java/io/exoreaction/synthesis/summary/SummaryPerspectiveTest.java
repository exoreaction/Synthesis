package io.exoreaction.synthesis.summary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SummaryPerspective enum — values, parsing, CLI values.
 */
class SummaryPerspectiveTest {

    @Test
    void enumHasEightValues() {
        assertEquals(8, SummaryPerspective.values().length);
    }

    @Test
    void general_hasCorrectCliValue() {
        assertEquals("general", SummaryPerspective.GENERAL.cliValue());
    }

    @Test
    void executive_hasCorrectCliValue() {
        assertEquals("executive", SummaryPerspective.EXECUTIVE.cliValue());
    }

    @Test
    void engineeringManager_hasCorrectCliValue() {
        assertEquals("engineering_manager", SummaryPerspective.ENGINEERING_MANAGER.cliValue());
    }

    @Test
    void architect_hasCorrectCliValue() {
        assertEquals("architect", SummaryPerspective.ARCHITECT.cliValue());
    }

    @Test
    void security_hasCorrectCliValue() {
        assertEquals("security", SummaryPerspective.SECURITY.cliValue());
    }

    @Test
    void devops_hasCorrectCliValue() {
        assertEquals("devops", SummaryPerspective.DEVOPS.cliValue());
    }

    @Test
    void productManager_hasCorrectCliValue() {
        assertEquals("product_manager", SummaryPerspective.PRODUCT_MANAGER.cliValue());
    }

    @Test
    void developer_hasCorrectCliValue() {
        assertEquals("developer", SummaryPerspective.DEVELOPER.cliValue());
    }

    @ParameterizedTest
    @EnumSource(SummaryPerspective.class)
    void allPerspectives_haveNonBlankDescription(SummaryPerspective perspective) {
        assertFalse(perspective.description().isBlank(),
                "Description should not be blank for " + perspective);
    }

    // --- fromString (lenient, defaults to GENERAL) ---

    @Test
    void fromString_null_returnsGeneral() {
        assertEquals(SummaryPerspective.GENERAL, SummaryPerspective.fromString(null));
    }

    @Test
    void fromString_empty_returnsGeneral() {
        assertEquals(SummaryPerspective.GENERAL, SummaryPerspective.fromString(""));
    }

    @Test
    void fromString_unknown_returnsGeneral() {
        assertEquals(SummaryPerspective.GENERAL, SummaryPerspective.fromString("cto"));
    }

    @ParameterizedTest
    @CsvSource({
        "general,             GENERAL",
        "GENERAL,             GENERAL",
        "executive,           EXECUTIVE",
        "EXECUTIVE,           EXECUTIVE",
        "engineering_manager, ENGINEERING_MANAGER",
        "ENGINEERING_MANAGER, ENGINEERING_MANAGER",
        "architect,           ARCHITECT",
        "security,            SECURITY",
        "devops,              DEVOPS",
        "product_manager,     PRODUCT_MANAGER",
        "developer,           DEVELOPER"
    })
    void fromString_caseInsensitive(String input, String expectedName) {
        SummaryPerspective expected = SummaryPerspective.valueOf(expectedName);
        assertEquals(expected, SummaryPerspective.fromString(input.trim()));
    }

    // --- cliValue round-trip ---

    @ParameterizedTest
    @ValueSource(strings = {"general", "executive", "engineering_manager", "architect",
            "security", "devops", "product_manager", "developer"})
    void cliValue_roundTrip(String cliValue) {
        SummaryPerspective perspective = SummaryPerspective.fromString(cliValue);
        assertEquals(cliValue, perspective.cliValue());
    }
}
