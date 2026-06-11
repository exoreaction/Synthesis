package io.exoreaction.synthesis.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiProviderTest {

    @Test
    void fromIdResolvesKnownProviders() {
        assertEquals(AiProvider.ANTHROPIC, AiProvider.fromId("anthropic"));
        assertEquals(AiProvider.OPENAI, AiProvider.fromId("openai"));
        assertEquals(AiProvider.DEEPSEEK, AiProvider.fromId("deepseek"));
        assertEquals(AiProvider.DEEPSEEK, AiProvider.fromId("DeepSeek"));
        assertEquals(AiProvider.DEEPSEEK, AiProvider.fromId("  deepseek  "));
    }

    @Test
    void fromIdFallsBackToAnthropic() {
        assertEquals(AiProvider.ANTHROPIC, AiProvider.fromId(null));
        assertEquals(AiProvider.ANTHROPIC, AiProvider.fromId(""));
        assertEquals(AiProvider.ANTHROPIC, AiProvider.fromId("unknown-provider"));
    }

    @Test
    void deepseekDefaults() {
        assertEquals("https://api.deepseek.com", AiProvider.DEEPSEEK.defaultEndpoint());
        assertEquals("deepseek-v4-flash", AiProvider.DEEPSEEK.defaultModel());
        assertEquals("deepseek-v4-flash", AiProvider.DEEPSEEK.fastModel());
        assertEquals("DEEPSEEK_API_KEY", AiProvider.DEEPSEEK.apiKeyName());
    }

    @Test
    void openAiDefaults() {
        assertEquals("https://api.openai.com/v1", AiProvider.OPENAI.defaultEndpoint());
        assertEquals("OPENAI_API_KEY", AiProvider.OPENAI.apiKeyName());
    }

    @Test
    void anthropicDefaults() {
        assertNull(AiProvider.ANTHROPIC.defaultEndpoint());
        assertEquals("claude-sonnet-4-5-20250929", AiProvider.ANTHROPIC.defaultModel());
        assertEquals("claude-haiku-4-5-20251001", AiProvider.ANTHROPIC.fastModel());
        assertEquals("ANTHROPIC_API_KEY", AiProvider.ANTHROPIC.apiKeyName());
    }

    @Test
    void resolveModelSubstitutesClaudeModelsOnOpenAiCompatibleProviders() {
        assertEquals("deepseek-v4-flash",
                AiProvider.DEEPSEEK.resolveModel("claude-sonnet-4-5-20250929"));
        assertEquals(AiProvider.DEEPSEEK.fastModel(),
                AiProvider.DEEPSEEK.resolveModel("claude-haiku-4-5-20251001"));
        assertEquals(AiProvider.OPENAI.defaultModel(),
                AiProvider.OPENAI.resolveModel("claude-sonnet-4-5-20250929"));
        assertEquals(AiProvider.OPENAI.fastModel(),
                AiProvider.OPENAI.resolveModel("claude-haiku-4-5-20251001"));
    }

    @Test
    void resolveModelKeepsExplicitNonClaudeModels() {
        assertEquals("deepseek-chat", AiProvider.DEEPSEEK.resolveModel("deepseek-chat"));
        assertEquals("gpt-4o", AiProvider.OPENAI.resolveModel("gpt-4o"));
    }

    @Test
    void resolveModelDefaultsWhenBlank() {
        assertEquals("deepseek-v4-flash", AiProvider.DEEPSEEK.resolveModel(null));
        assertEquals("deepseek-v4-flash", AiProvider.DEEPSEEK.resolveModel("  "));
        assertEquals("claude-sonnet-4-5-20250929", AiProvider.ANTHROPIC.resolveModel(null));
    }

    @Test
    void resolveModelLeavesClaudeModelsOnAnthropic() {
        assertEquals("claude-haiku-4-5-20251001",
                AiProvider.ANTHROPIC.resolveModel("claude-haiku-4-5-20251001"));
    }

    @Test
    void resolveApiKeyPrefersEnvironmentVariable() {
        assertEquals("env-key",
                AiProvider.DEEPSEEK.resolveApiKey(name -> "env-key").orElseThrow());
    }
}
