package io.exoreaction.synthesis.ai;

import io.exoreaction.synthesis.config.SynthesisConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Provider-independent contract for Synthesis AI generation.
 *
 * <p>Implementations: {@link ClaudeClient} (Anthropic SDK) and {@link OpenAiClient}
 * (OpenAI-compatible Chat Completions — OpenAI, DeepSeek, or any compatible server).
 * The provider is selected via {@code ai.provider} in the workspace config.
 *
 * <p>Usage:
 * <pre>
 * Optional&lt;AiClient&gt; client = AiClient.create(config.getAi());
 * client.ifPresent(c -&gt; System.out.println(c.generate("Your prompt here", 1024)));
 * </pre>
 */
public interface AiClient {

    /**
     * Result of a generation call, including truncation status.
     *
     * @see <a href="https://github.com/exoreaction/Synthesis/issues/44">#44</a>
     */
    record GenerationResult(String content, boolean truncated) {}

    /**
     * Generates text content from a prompt.
     *
     * @throws RuntimeException if the API call fails
     */
    String generate(String prompt, int maxTokens);

    /**
     * Generates text and returns metadata including whether output was truncated.
     */
    GenerationResult generateWithMeta(String prompt, int maxTokens, double temperature);

    /**
     * Generates a text description from an image file using the provider's vision API.
     *
     * @throws IOException if the image cannot be read
     */
    String generateFromImage(Path imagePath, String prompt, int maxTokens) throws IOException;

    /** Returns the model being used. */
    String getModel();

    /**
     * Creates a client for the configured provider if AI is enabled and an API key
     * is available.
     *
     * @return the client, or empty if AI is disabled or the provider's API key is missing
     */
    static Optional<AiClient> create(SynthesisConfig.AiConfig config) {
        if (!config.isEnabled()) {
            return Optional.empty();
        }
        return switch (AiProvider.fromId(config.getProvider())) {
            case ANTHROPIC -> ClaudeClient.create(config).map(AiClient.class::cast);
            case OPENAI, DEEPSEEK -> OpenAiClient.create(config);
        };
    }

    /**
     * Creates a client using only the API key, bypassing the enabled flag.
     *
     * <p>Provider-aware replacement for {@code ClaudeClient.createIfApiKeyAvailable(model)}:
     * the requested model is mapped through {@link AiProvider#resolveModel(String)} so a
     * Claude model name never reaches an OpenAI-compatible endpoint.
     *
     * @param config the AI configuration (supplies provider + endpoint)
     * @param model  the requested model, mapped onto the configured provider
     * @return the client, or empty if no API key is available
     */
    static Optional<AiClient> createIfApiKeyAvailable(SynthesisConfig.AiConfig config, String model) {
        AiProvider provider = AiProvider.fromId(config.getProvider());
        String effectiveModel = provider.resolveModel(model);
        return switch (provider) {
            case ANTHROPIC -> ClaudeClient.createIfApiKeyAvailable(effectiveModel).map(AiClient.class::cast);
            case OPENAI, DEEPSEEK -> OpenAiClient.createIfApiKeyAvailable(config, effectiveModel);
        };
    }

    /**
     * Creates a client on the provider's cheap/fast model (haiku-class), bypassing the
     * enabled flag. Replaces hardcoded {@code claude-haiku-*} model strings at call sites.
     */
    static Optional<AiClient> createFast(SynthesisConfig.AiConfig config) {
        return createIfApiKeyAvailable(config, AiProvider.fromId(config.getProvider()).fastModel());
    }
}
