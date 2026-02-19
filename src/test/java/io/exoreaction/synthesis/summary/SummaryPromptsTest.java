package io.exoreaction.synthesis.summary;

import io.exoreaction.synthesis.summary.CodebaseProfile.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SummaryPrompts} — prompt generation with and without temporal context.
 */
class SummaryPromptsTest {

    private static Profile minimalProfile() {
        ScaleMetrics scale = new ScaleMetrics(100, 512_000, Map.of(), Map.of("Java", 100L), List.of(), 10);
        QualityMetrics quality = new QualityMetrics(0.7, 1.2, 30, 70, 2, List.of());
        ArchitectureMetrics arch = new ArchitectureMetrics(5, 0, 0, Map.of(), 2.0);
        return new Profile(scale, quality, arch, List.of(), List.of(), List.of(), Instant.now());
    }

    // --- No temporal context ---

    @Test
    void generatePrompt_noTemporalContext_containsMetrics() {
        String prompt = SummaryPrompts.generatePrompt(minimalProfile(),
                SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL);

        assertTrue(prompt.contains("Total files"), "Should include scale metrics");
        assertFalse(prompt.contains("Recent Changes"), "Should not include temporal section");
    }

    @Test
    void generatePrompt_nullTemporalContext_noTemporalSection() {
        String prompt = SummaryPrompts.generatePrompt(minimalProfile(),
                SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, null);

        assertFalse(prompt.contains("Recent Changes"), "Null temporal context → no section");
    }

    @Test
    void generatePrompt_blankTemporalContext_noTemporalSection() {
        String prompt = SummaryPrompts.generatePrompt(minimalProfile(),
                SummaryLevel.MANAGER, SummaryPerspective.GENERAL, "   ");

        assertFalse(prompt.contains("Recent Changes"), "Blank temporal context → no section");
    }

    // --- With temporal context ---

    @Test
    void generatePrompt_withTemporalContext_includesSection() {
        String temporalContext = "Changes since 7d: 12 changes (3 added, 8 modified, 1 deleted) | 2 notable";
        String prompt = SummaryPrompts.generatePrompt(minimalProfile(),
                SummaryLevel.EXECUTIVE, SummaryPerspective.GENERAL, temporalContext);

        assertTrue(prompt.contains("Recent Changes"), "Should include 'Recent Changes' heading");
        assertTrue(prompt.contains("12 changes"), "Should include the change summary text");
        assertTrue(prompt.contains("factor these recent changes"), "Should instruct AI to use the data");
    }

    @Test
    void generatePrompt_temporalContextAppearsBeforePerspective() {
        String temporalContext = "Changes since 7d: 5 changes";
        String prompt = SummaryPrompts.generatePrompt(minimalProfile(),
                SummaryLevel.EXECUTIVE, SummaryPerspective.EXECUTIVE, temporalContext);

        int temporalIdx = prompt.indexOf("Recent Changes");
        int perspectiveIdx = prompt.indexOf("Your Role");

        assertTrue(temporalIdx > 0, "Temporal section should be present");
        assertTrue(perspectiveIdx > 0, "Perspective section should be present");
        assertTrue(temporalIdx < perspectiveIdx,
                "Temporal context should appear before perspective instructions");
    }

    @Test
    void generatePrompt_allPerspectives_acceptTemporalContext() {
        String temporalContext = "Changes since 3d: 3 changes";
        for (SummaryPerspective perspective : SummaryPerspective.values()) {
            String prompt = SummaryPrompts.generatePrompt(minimalProfile(),
                    SummaryLevel.MANAGER, perspective, temporalContext);
            assertTrue(prompt.contains("Recent Changes"),
                    "Perspective " + perspective + " should include temporal section");
        }
    }

    @Test
    void generatePrompt_allLevels_acceptTemporalContext() {
        String temporalContext = "Changes since 1d: 1 change";
        for (SummaryLevel level : SummaryLevel.values()) {
            String prompt = SummaryPrompts.generatePrompt(minimalProfile(),
                    level, SummaryPerspective.GENERAL, temporalContext);
            assertTrue(prompt.contains("Recent Changes"),
                    "Level " + level + " should include temporal section");
        }
    }

    // --- Backward compatibility ---

    @Test
    void generatePrompt_twoArgOverload_matchesNullTemporalOverload() {
        Profile profile = minimalProfile();
        String twoArg = SummaryPrompts.generatePrompt(profile, SummaryLevel.DEVELOPER, SummaryPerspective.DEVELOPER);
        String nullTemporal = SummaryPrompts.generatePrompt(profile, SummaryLevel.DEVELOPER, SummaryPerspective.DEVELOPER, null);

        assertEquals(twoArg, nullTemporal,
                "Two-arg overload should produce same result as null temporal context");
    }
}
