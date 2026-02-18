package io.exoreaction.synthesis.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResearchTopic enum — parsing, display names, and CLI values.
 */
class ResearchTopicTest {

    // --- enum values ---

    @Test
    void enumHasSixValues() {
        assertEquals(6, ResearchTopic.values().length);
    }

    @Test
    void fullAnalysis_hasCorrectCliValue() {
        assertEquals("full", ResearchTopic.FULL_ANALYSIS.cliValue());
    }

    @Test
    void architecture_hasCorrectCliValue() {
        assertEquals("architecture", ResearchTopic.ARCHITECTURE.cliValue());
    }

    @Test
    void security_hasCorrectCliValue() {
        assertEquals("security", ResearchTopic.SECURITY.cliValue());
    }

    @Test
    void quality_hasCorrectCliValue() {
        assertEquals("quality", ResearchTopic.QUALITY.cliValue());
    }

    @Test
    void dependencies_hasCorrectCliValue() {
        assertEquals("dependencies", ResearchTopic.DEPENDENCIES.cliValue());
    }

    @Test
    void evolution_hasCorrectCliValue() {
        assertEquals("evolution", ResearchTopic.EVOLUTION.cliValue());
    }

    @Test
    void allTopics_haveNonBlankDisplayName() {
        for (ResearchTopic t : ResearchTopic.values()) {
            assertFalse(t.displayName().isBlank(), "Display name should not be blank for " + t);
        }
    }

    // --- fromString (lenient, defaults to FULL_ANALYSIS) ---

    @Test
    void fromString_null_returnsDefault() {
        assertEquals(ResearchTopic.FULL_ANALYSIS, ResearchTopic.fromString(null));
    }

    @Test
    void fromString_empty_returnsDefault() {
        assertEquals(ResearchTopic.FULL_ANALYSIS, ResearchTopic.fromString(""));
    }

    @Test
    void fromString_unknown_returnsDefault() {
        assertEquals(ResearchTopic.FULL_ANALYSIS, ResearchTopic.fromString("compliance"));
    }

    @ParameterizedTest
    @CsvSource({
        "full,          FULL_ANALYSIS",
        "FULL,          FULL_ANALYSIS",
        "architecture,  ARCHITECTURE",
        "ARCHITECTURE,  ARCHITECTURE",
        "security,      SECURITY",
        "SECURITY,      SECURITY",
        "quality,       QUALITY",
        "QUALITY,       QUALITY",
        "dependencies,  DEPENDENCIES",
        "DEPENDENCIES,  DEPENDENCIES",
        "evolution,     EVOLUTION",
        "EVOLUTION,     EVOLUTION"
    })
    void fromString_caseInsensitive(String input, String expectedName) {
        ResearchTopic expected = ResearchTopic.valueOf(expectedName);
        assertEquals(expected, ResearchTopic.fromString(input.trim()));
    }

    // --- cliValue round-trip ---

    @ParameterizedTest
    @ValueSource(strings = {"full", "architecture", "security", "quality", "dependencies", "evolution"})
    void cliValue_roundTrip(String cliValue) {
        ResearchTopic topic = ResearchTopic.fromString(cliValue);
        assertEquals(cliValue, topic.cliValue());
    }
}
