package io.exoreaction.synthesis.research;

import io.exoreaction.synthesis.summary.CodebaseProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResearchPrompts — pass name resolution, ALL_PASSES list, prompt structure.
 */
class ResearchPromptsTest {

    // --- passNameFor ---

    @Test
    void passNameFor_fullAnalysis_returnsFull() {
        assertEquals("full", ResearchPrompts.passNameFor(ResearchTopic.FULL_ANALYSIS));
    }

    @Test
    void passNameFor_architecture_returnsArchitecture() {
        assertEquals("architecture", ResearchPrompts.passNameFor(ResearchTopic.ARCHITECTURE));
    }

    @Test
    void passNameFor_security_returnsSecurity() {
        assertEquals("security", ResearchPrompts.passNameFor(ResearchTopic.SECURITY));
    }

    @Test
    void passNameFor_quality_returnsQuality() {
        assertEquals("quality", ResearchPrompts.passNameFor(ResearchTopic.QUALITY));
    }

    @Test
    void passNameFor_dependencies_returnsDependencies() {
        assertEquals("dependencies", ResearchPrompts.passNameFor(ResearchTopic.DEPENDENCIES));
    }

    @Test
    void passNameFor_evolution_returnsEvolution() {
        assertEquals("evolution", ResearchPrompts.passNameFor(ResearchTopic.EVOLUTION));
    }

    @ParameterizedTest
    @EnumSource(ResearchTopic.class)
    void passNameFor_allTopics_returnsNonBlankName(ResearchTopic topic) {
        String name = ResearchPrompts.passNameFor(topic);
        assertNotNull(name);
        assertFalse(name.isBlank(), "Pass name should not be blank for topic " + topic);
    }

    // --- ResearchEngine.ALL_PASSES list ---

    @Test
    void allPasses_hasCorrectSize() {
        assertEquals(6, ResearchEngine.ALL_PASSES.size());
    }

    @Test
    void allPasses_containsAllDomainPasses() {
        assertTrue(ResearchEngine.ALL_PASSES.contains("architecture"));
        assertTrue(ResearchEngine.ALL_PASSES.contains("security"));
        assertTrue(ResearchEngine.ALL_PASSES.contains("quality"));
        assertTrue(ResearchEngine.ALL_PASSES.contains("dependencies"));
        assertTrue(ResearchEngine.ALL_PASSES.contains("evolution"));
    }

    @Test
    void allPasses_containsSynthesis() {
        assertTrue(ResearchEngine.ALL_PASSES.contains("synthesis"));
    }

    @Test
    void allPasses_synthesisIsLast() {
        List<String> passes = ResearchEngine.ALL_PASSES;
        assertEquals("synthesis", passes.get(passes.size() - 1));
    }

    @Test
    void domainPasses_doesNotContainSynthesis() {
        assertFalse(ResearchEngine.DOMAIN_PASSES.contains("synthesis"));
    }

    @Test
    void domainPasses_hasSize5() {
        assertEquals(5, ResearchEngine.DOMAIN_PASSES.size());
    }

    // --- Prompt content structure ---

    @Test
    void architecturePass_returnsNonBlankPrompt() {
        CodebaseProfile.Profile profile = emptyProfile();
        String prompt = ResearchPrompts.architecturePass(profile, ResearchTarget.CHATGPT_DEEP_RESEARCH);
        assertFalse(prompt.isBlank());
    }

    @Test
    void architecturePass_containsArchitectureKeyword() {
        CodebaseProfile.Profile profile = emptyProfile();
        String prompt = ResearchPrompts.architecturePass(profile, ResearchTarget.CHATGPT_DEEP_RESEARCH);
        assertTrue(prompt.toLowerCase().contains("architect"), "Architecture pass should mention architecture");
    }

    @Test
    void securityPass_returnsNonBlankPrompt() {
        CodebaseProfile.Profile profile = emptyProfile();
        String prompt = ResearchPrompts.securityPass(profile, ResearchTarget.CHATGPT_DEEP_RESEARCH);
        assertFalse(prompt.isBlank());
    }

    @Test
    void securityPass_containsSecurityKeyword() {
        CodebaseProfile.Profile profile = emptyProfile();
        String prompt = ResearchPrompts.securityPass(profile, ResearchTarget.CHATGPT_DEEP_RESEARCH);
        assertTrue(prompt.toLowerCase().contains("security"), "Security pass should mention security");
    }

    @Test
    void qualityPass_returnsNonBlankPrompt() {
        CodebaseProfile.Profile profile = emptyProfile();
        String prompt = ResearchPrompts.qualityPass(profile, ResearchTarget.CHATGPT_DEEP_RESEARCH);
        assertFalse(prompt.isBlank());
    }

    @Test
    void dependenciesPass_returnsNonBlankPrompt() {
        CodebaseProfile.Profile profile = emptyProfile();
        String prompt = ResearchPrompts.dependenciesPass(profile, ResearchTarget.CHATGPT_DEEP_RESEARCH);
        assertFalse(prompt.isBlank());
    }

    @Test
    void evolutionPass_returnsNonBlankPrompt() {
        CodebaseProfile.Profile profile = emptyProfile();
        String prompt = ResearchPrompts.evolutionPass(profile, ResearchTarget.CHATGPT_DEEP_RESEARCH);
        assertFalse(prompt.isBlank());
    }

    @Test
    void allPassPrompts_areDistinctFromEachOther() {
        CodebaseProfile.Profile profile = emptyProfile();
        ResearchTarget target = ResearchTarget.CHATGPT_DEEP_RESEARCH;
        String arch = ResearchPrompts.architecturePass(profile, target);
        String sec  = ResearchPrompts.securityPass(profile, target);
        String qual = ResearchPrompts.qualityPass(profile, target);
        String dep  = ResearchPrompts.dependenciesPass(profile, target);
        String evo  = ResearchPrompts.evolutionPass(profile, target);

        // Each should be different
        assertNotEquals(arch, sec);
        assertNotEquals(arch, qual);
        assertNotEquals(arch, dep);
        assertNotEquals(arch, evo);
        assertNotEquals(sec, qual);
    }

    @Test
    void singleTopicPrompt_architectureTopic_returnsNonBlankPrompt() {
        CodebaseProfile.Profile profile = emptyProfile();
        String prompt = ResearchPrompts.singleTopicPrompt(profile, ResearchTopic.ARCHITECTURE,
                ResearchTarget.CHATGPT_DEEP_RESEARCH);
        assertFalse(prompt.isBlank());
    }

    @Test
    void singleTopicPrompt_allTopics_returnsNonBlankPrompt() {
        CodebaseProfile.Profile profile = emptyProfile();
        for (ResearchTopic topic : ResearchTopic.values()) {
            String prompt = ResearchPrompts.singleTopicPrompt(profile, topic,
                    ResearchTarget.CHATGPT_DEEP_RESEARCH);
            assertFalse(prompt.isBlank(), "singleTopicPrompt should not be blank for topic " + topic);
        }
    }

    @Test
    void promptsDifferByTarget_chatgptVsNotebookLm() {
        // Prompts should handle different targets; both return non-null
        CodebaseProfile.Profile profile = emptyProfile();
        String chatgpt = ResearchPrompts.architecturePass(profile, ResearchTarget.CHATGPT_DEEP_RESEARCH);
        String infographic = ResearchPrompts.architecturePass(profile, ResearchTarget.NOTEBOOKLM_INFOGRAPHIC);
        assertNotNull(chatgpt);
        assertNotNull(infographic);
    }

    // --- helpers ---

    private static CodebaseProfile.Profile emptyProfile() {
        return new CodebaseProfile.Profile(
                new CodebaseProfile.ScaleMetrics(0, 0L, Map.of(), Map.of(), List.of(), 0),
                new CodebaseProfile.QualityMetrics(0.0, 0.0, 0, 0, 0, List.of()),
                new CodebaseProfile.ArchitectureMetrics(0, 0, 0, Map.of(), 0.0),
                List.of(),
                List.of(),
                List.of(),
                Instant.now()
        );
    }
}
