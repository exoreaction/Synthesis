package io.exoreaction.synthesis.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WorkspaceType enum — fromConfigValue parsing, toString, defaults.
 */
class WorkspaceTypeTest {

    @Test
    void enumHasFourValues() {
        assertEquals(4, WorkspaceType.values().length);
    }

    @Test
    void sourceCode_hasCorrectConfigValue() {
        assertEquals("source-code", WorkspaceType.SOURCE_CODE.getConfigValue());
    }

    @Test
    void documents_hasCorrectConfigValue() {
        assertEquals("documents", WorkspaceType.DOCUMENTS.getConfigValue());
    }

    @Test
    void staging_hasCorrectConfigValue() {
        assertEquals("staging", WorkspaceType.STAGING.getConfigValue());
    }

    @Test
    void mixed_hasCorrectConfigValue() {
        assertEquals("mixed", WorkspaceType.MIXED.getConfigValue());
    }

    @Test
    void allTypes_haveNonBlankDescription() {
        for (WorkspaceType type : WorkspaceType.values()) {
            assertFalse(type.getDescription().isBlank(), "Description should not be blank for " + type);
        }
    }

    // --- fromConfigValue ---

    @ParameterizedTest
    @NullAndEmptySource
    void fromConfigValue_nullOrEmpty_returnsMixed(String input) {
        assertEquals(WorkspaceType.MIXED, WorkspaceType.fromConfigValue(input));
    }

    @Test
    void fromConfigValue_blankWhitespace_returnsMixed() {
        assertEquals(WorkspaceType.MIXED, WorkspaceType.fromConfigValue("   "));
    }

    @Test
    void fromConfigValue_unknown_returnsMixed() {
        assertEquals(WorkspaceType.MIXED, WorkspaceType.fromConfigValue("unknown-type"));
    }

    @ParameterizedTest
    @CsvSource({
        "source-code,   SOURCE_CODE",
        "SOURCE-CODE,   SOURCE_CODE",
        "source_code,   SOURCE_CODE",
        "SOURCE_CODE,   SOURCE_CODE",
        "documents,     DOCUMENTS",
        "DOCUMENTS,     DOCUMENTS",
        "staging,       STAGING",
        "STAGING,       STAGING",
        "mixed,         MIXED",
        "MIXED,         MIXED"
    })
    void fromConfigValue_allVariants(String input, String expectedName) {
        WorkspaceType expected = WorkspaceType.valueOf(expectedName);
        assertEquals(expected, WorkspaceType.fromConfigValue(input.trim()));
    }

    // --- Legacy compatibility values ---

    @ParameterizedTest
    @ValueSource(strings = {"general", "plugin-ecosystem", "monorepo", "multi-project"})
    void fromConfigValue_legacyValues_returnsMixed(String legacyValue) {
        assertEquals(WorkspaceType.MIXED, WorkspaceType.fromConfigValue(legacyValue),
                "Legacy value '" + legacyValue + "' should map to MIXED");
    }

    // --- toString ---

    @Test
    void toString_returnsConfigValue() {
        assertEquals("source-code", WorkspaceType.SOURCE_CODE.toString());
        assertEquals("documents", WorkspaceType.DOCUMENTS.toString());
        assertEquals("staging", WorkspaceType.STAGING.toString());
        assertEquals("mixed", WorkspaceType.MIXED.toString());
    }

    // --- round-trip ---

    @ParameterizedTest
    @ValueSource(strings = {"source-code", "documents", "staging", "mixed"})
    void configValue_roundTrip(String configValue) {
        WorkspaceType type = WorkspaceType.fromConfigValue(configValue);
        assertEquals(configValue, type.getConfigValue());
    }
}
