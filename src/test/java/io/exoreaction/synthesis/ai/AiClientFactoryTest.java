package io.exoreaction.synthesis.ai;

import io.exoreaction.synthesis.config.SynthesisConfig;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Factory dispatch tests for {@link AiClient#create} and friends.
 *
 * <p>Key availability depends on the machine running the tests, so dispatch
 * assertions verify that presence matches the provider's resolvable key and
 * that any created client is of the expected implementation type.
 */
class AiClientFactoryTest {

    private static SynthesisConfig.AiConfig aiConfig(String provider, boolean enabled) {
        SynthesisConfig.AiConfig config = new SynthesisConfig.AiConfig();
        config.setEnabled(enabled);
        config.setProvider(provider);
        return config;
    }

    @Test
    void disabledConfigYieldsEmptyForAllProviders() {
        assertTrue(AiClient.create(aiConfig("anthropic", false)).isEmpty());
        assertTrue(AiClient.create(aiConfig("openai", false)).isEmpty());
        assertTrue(AiClient.create(aiConfig("deepseek", false)).isEmpty());
    }

    @Test
    void deepseekProviderDispatchesToOpenAiClientWithProviderDefaults() {
        Optional<AiClient> client = AiClient.create(aiConfig("deepseek", true));

        assertEquals(AiProvider.DEEPSEEK.resolveApiKey().isPresent(), client.isPresent());
        client.ifPresent(c -> {
            OpenAiClient openAi = assertInstanceOf(OpenAiClient.class, c);
            assertEquals("deepseek-v4-flash", openAi.getModel());
            assertEquals("https://api.deepseek.com", openAi.getEndpoint());
        });
    }

    @Test
    void absentProviderDispatchesToClaudeClient() {
        SynthesisConfig.AiConfig config = new SynthesisConfig.AiConfig();
        config.setEnabled(true);
        Optional<AiClient> client = AiClient.create(config);

        assertEquals(AiProvider.ANTHROPIC.resolveApiKey().isPresent(), client.isPresent());
        client.ifPresent(c -> assertInstanceOf(ClaudeClient.class, c));
    }

    @Test
    void endpointOverrideWinsOverProviderDefault() {
        SynthesisConfig.AiConfig config = aiConfig("openai", true);
        config.setEndpoint("http://localhost:9999/v1");
        Optional<AiClient> client = OpenAiClient.createIfApiKeyAvailable(config, "any-model");

        assertEquals(AiProvider.OPENAI.resolveApiKey().isPresent(), client.isPresent());
        client.ifPresent(c -> assertEquals("http://localhost:9999/v1",
                assertInstanceOf(OpenAiClient.class, c).getEndpoint()));
    }

    @Test
    void createIfApiKeyAvailableMapsClaudeModelOntoProvider() {
        Optional<AiClient> client = AiClient.createIfApiKeyAvailable(
                aiConfig("deepseek", false), "claude-haiku-4-5-20251001");

        assertEquals(AiProvider.DEEPSEEK.resolveApiKey().isPresent(), client.isPresent());
        client.ifPresent(c -> assertEquals(AiProvider.DEEPSEEK.fastModel(), c.getModel()));
    }

    @Test
    void createFastUsesProviderFastModel() {
        Optional<AiClient> client = AiClient.createFast(aiConfig("deepseek", false));

        assertEquals(AiProvider.DEEPSEEK.resolveApiKey().isPresent(), client.isPresent());
        client.ifPresent(c -> assertEquals("deepseek-v4-flash", c.getModel()));
    }
}
