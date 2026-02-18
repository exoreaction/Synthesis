package io.exoreaction.synthesis.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResearchTarget enum — CLI parsing, display names, and strict mode.
 */
class ResearchTargetTest {

    // --- enum values and fields ---

    @Test
    void enumHasThreeValues() {
        assertEquals(3, ResearchTarget.values().length);
    }

    @Test
    void chatgpt_hasCorrectCliValue() {
        assertEquals("chatgpt", ResearchTarget.CHATGPT_DEEP_RESEARCH.cliValue());
    }

    @Test
    void notebooklmInfographic_hasCorrectCliValue() {
        assertEquals("notebooklm-infographic", ResearchTarget.NOTEBOOKLM_INFOGRAPHIC.cliValue());
    }

    @Test
    void notebooklmPresentation_hasCorrectCliValue() {
        assertEquals("notebooklm-presentation", ResearchTarget.NOTEBOOKLM_PRESENTATION.cliValue());
    }

    @Test
    void chatgpt_hasNonBlankDisplayName() {
        assertFalse(ResearchTarget.CHATGPT_DEEP_RESEARCH.displayName().isBlank());
    }

    @Test
    void notebooklmInfographic_hasNonBlankDisplayName() {
        assertFalse(ResearchTarget.NOTEBOOKLM_INFOGRAPHIC.displayName().isBlank());
    }

    @Test
    void notebooklmPresentation_hasNonBlankDisplayName() {
        assertFalse(ResearchTarget.NOTEBOOKLM_PRESENTATION.displayName().isBlank());
    }

    @Test
    void allTargets_haveNonBlankDescription() {
        for (ResearchTarget t : ResearchTarget.values()) {
            assertFalse(t.description().isBlank(), "Description should not be blank for " + t);
        }
    }

    // --- fromString (lenient, defaults to CHATGPT_DEEP_RESEARCH) ---

    @Test
    void fromString_null_returnsDefault() {
        assertEquals(ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTarget.fromString(null));
    }

    @Test
    void fromString_empty_returnsDefault() {
        assertEquals(ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTarget.fromString(""));
    }

    @Test
    void fromString_unknown_returnsDefault() {
        assertEquals(ResearchTarget.CHATGPT_DEEP_RESEARCH, ResearchTarget.fromString("gemini"));
    }

    @ParameterizedTest
    @CsvSource({
        "chatgpt,                  CHATGPT_DEEP_RESEARCH",
        "CHATGPT,                  CHATGPT_DEEP_RESEARCH",
        "notebooklm-infographic,   NOTEBOOKLM_INFOGRAPHIC",
        "NOTEBOOKLM-INFOGRAPHIC,   NOTEBOOKLM_INFOGRAPHIC",
        "notebooklm-presentation,  NOTEBOOKLM_PRESENTATION",
        "NOTEBOOKLM-PRESENTATION,  NOTEBOOKLM_PRESENTATION"
    })
    void fromString_caseInsensitive(String input, String expectedName) {
        ResearchTarget expected = ResearchTarget.valueOf(expectedName);
        assertEquals(expected, ResearchTarget.fromString(input.trim()));
    }

    // --- fromStringStrict (throws on invalid) ---

    @Test
    void fromStringStrict_null_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> ResearchTarget.fromStringStrict(null));
    }

    @Test
    void fromStringStrict_empty_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> ResearchTarget.fromStringStrict(""));
    }

    @Test
    void fromStringStrict_unknown_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> ResearchTarget.fromStringStrict("perplexity"));
    }

    @Test
    void fromStringStrict_validInput_returnsCorrectTarget() {
        assertEquals(ResearchTarget.CHATGPT_DEEP_RESEARCH,
                ResearchTarget.fromStringStrict("chatgpt"));
        assertEquals(ResearchTarget.NOTEBOOKLM_INFOGRAPHIC,
                ResearchTarget.fromStringStrict("notebooklm-infographic"));
        assertEquals(ResearchTarget.NOTEBOOKLM_PRESENTATION,
                ResearchTarget.fromStringStrict("notebooklm-presentation"));
    }

    @Test
    void fromStringStrict_errorMessageContainsValidOptions() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ResearchTarget.fromStringStrict("bad-value"));
        String msg = ex.getMessage();
        assertTrue(msg.contains("chatgpt"), "Error should mention valid targets");
        assertTrue(msg.contains("notebooklm-infographic"), "Error should mention valid targets");
    }

    // --- cliValue round-trip ---

    @ParameterizedTest
    @ValueSource(strings = {"chatgpt", "notebooklm-infographic", "notebooklm-presentation"})
    void cliValue_roundTrip(String cliValue) {
        ResearchTarget target = ResearchTarget.fromString(cliValue);
        assertEquals(cliValue, target.cliValue());
    }
}
