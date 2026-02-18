package io.exoreaction.synthesis.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResearchEngine.parsePassList — comma-separated pass string parsing.
 * Also tests resolvePassList logic for topic-based defaults.
 */
class ResearchEngineParsePassListTest {

    // --- parsePassList ---

    @ParameterizedTest
    @NullAndEmptySource
    void parsePassList_nullOrEmpty_returnsNull(String input) {
        assertNull(ResearchEngine.parsePassList(input));
    }

    @Test
    void parsePassList_blankWhitespace_returnsNull() {
        assertNull(ResearchEngine.parsePassList("   "));
    }

    @Test
    void parsePassList_singlePass_returnsSingletonList() {
        List<String> result = ResearchEngine.parsePassList("architecture");
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("architecture", result.get(0));
    }

    @Test
    void parsePassList_multiplePasses_returnsAllPasses() {
        List<String> result = ResearchEngine.parsePassList("architecture,security,synthesis");
        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals(List.of("architecture", "security", "synthesis"), result);
    }

    @Test
    void parsePassList_lowercasesAllPasses() {
        List<String> result = ResearchEngine.parsePassList("ARCHITECTURE,SECURITY");
        assertNotNull(result);
        assertEquals(List.of("architecture", "security"), result);
    }

    @Test
    void parsePassList_trimsWhitespace() {
        List<String> result = ResearchEngine.parsePassList(" architecture , security , synthesis ");
        assertNotNull(result);
        assertEquals(List.of("architecture", "security", "synthesis"), result);
    }

    @Test
    void parsePassList_emptyEntries_areSkipped() {
        List<String> result = ResearchEngine.parsePassList("architecture,,security,");
        assertNotNull(result);
        // Empty entries (from trailing comma or double comma) should be filtered
        assertTrue(result.contains("architecture"));
        assertTrue(result.contains("security"));
        assertFalse(result.contains(""), "Empty entries should be filtered out");
    }

    @Test
    void parsePassList_allKnownPasses_parsesCorrectly() {
        String allPasses = "architecture,security,quality,dependencies,evolution,synthesis";
        List<String> result = ResearchEngine.parsePassList(allPasses);
        assertNotNull(result);
        assertEquals(6, result.size());
        assertTrue(result.containsAll(ResearchEngine.ALL_PASSES));
    }

    @Test
    void parsePassList_preservesOrder() {
        List<String> result = ResearchEngine.parsePassList("synthesis,security,architecture");
        assertNotNull(result);
        assertEquals("synthesis", result.get(0));
        assertEquals("security", result.get(1));
        assertEquals("architecture", result.get(2));
    }

    // --- resolvePassList via ResearchEngine ---

    @Test
    void resolvePassList_fullAnalysisTopic_returnsAllPasses() {
        ResearchEngine engine = new ResearchEngine(null, 8000);
        List<String> passes = engine.resolvePassList(ResearchTopic.FULL_ANALYSIS, null);
        assertEquals(ResearchEngine.ALL_PASSES.size(), passes.size());
        assertTrue(passes.containsAll(ResearchEngine.ALL_PASSES));
    }

    @Test
    void resolvePassList_architectureTopic_returnsArchitectureAndSynthesis() {
        ResearchEngine engine = new ResearchEngine(null, 8000);
        List<String> passes = engine.resolvePassList(ResearchTopic.ARCHITECTURE, null);
        assertTrue(passes.contains("architecture"), "Architecture topic should include architecture pass");
        assertTrue(passes.contains("synthesis"), "Architecture topic should include synthesis pass");
        assertEquals(2, passes.size());
    }

    @Test
    void resolvePassList_selectedPasses_overridesTopic() {
        ResearchEngine engine = new ResearchEngine(null, 8000);
        List<String> selected = List.of("architecture", "synthesis");
        List<String> passes = engine.resolvePassList(ResearchTopic.FULL_ANALYSIS, selected);
        // User-selected passes take precedence
        assertEquals(2, passes.size());
        assertTrue(passes.contains("architecture"));
        assertTrue(passes.contains("synthesis"));
    }

    @Test
    void resolvePassList_selectedPassesWithInvalidName_filtersInvalid() {
        ResearchEngine engine = new ResearchEngine(null, 8000);
        List<String> selected = List.of("architecture", "invalid-pass-name", "synthesis");
        List<String> passes = engine.resolvePassList(ResearchTopic.FULL_ANALYSIS, selected);
        // Invalid pass names should be filtered
        assertFalse(passes.contains("invalid-pass-name"), "Invalid pass names should be filtered");
        assertTrue(passes.contains("architecture"));
        assertTrue(passes.contains("synthesis"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"architecture", "security", "quality", "dependencies", "evolution"})
    void resolvePassList_singleDomainTopic_returnsDomainPassPlusSynthesis(String topicCliValue) {
        ResearchEngine engine = new ResearchEngine(null, 8000);
        ResearchTopic topic = ResearchTopic.fromString(topicCliValue);
        List<String> passes = engine.resolvePassList(topic, null);
        assertEquals(2, passes.size(), "Single domain topic should return 2 passes (domain + synthesis)");
        assertTrue(passes.contains("synthesis"), "Should always include synthesis for single topic");
    }
}
