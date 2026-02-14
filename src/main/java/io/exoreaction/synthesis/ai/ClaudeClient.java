package io.exoreaction.synthesis.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import io.exoreaction.synthesis.config.SynthesisConfig;

import java.util.Optional;

/**
 * Thin wrapper around the Anthropic Java SDK.
 *
 * <p>Provides a simplified API for Synthesis AI operations.
 * Handles client initialization, error handling, and response extraction.
 *
 * <p>Usage:
 * <pre>
 * Optional&lt;ClaudeClient&gt; client = ClaudeClient.create(config);
 * if (client.isPresent()) {
 *     String response = client.get().generate("Your prompt here", 1024);
 * }
 * </pre>
 */
public class ClaudeClient {

    private final AnthropicClient client;
    private final String model;

    private ClaudeClient(AnthropicClient client, String model) {
        this.client = client;
        this.model = model;
    }

    /**
     * Creates a ClaudeClient if AI is enabled and API key is available.
     *
     * @param config the AI configuration
     * @return the client, or empty if AI is disabled or API key is missing
     */
    public static Optional<ClaudeClient> create(SynthesisConfig.AiConfig config) {
        if (!config.isEnabled()) {
            return Optional.empty();
        }

        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }

        try {
            AnthropicClient client = AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();
            return Optional.of(new ClaudeClient(client, config.getModel()));
        } catch (Exception e) {
            System.err.println("Warning: Failed to initialize Claude client: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Generates text content from a prompt.
     *
     * @param prompt    the prompt to send
     * @param maxTokens maximum tokens in the response
     * @return the generated text
     * @throws RuntimeException if the API call fails
     */
    public String generate(String prompt, int maxTokens) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens((long) maxTokens)
                .addUserMessage(prompt)
                .build();

        Message message = client.messages().create(params);

        // Extract text from response using the Anthropic SDK's ContentBlock API
        return message.content().stream()
                .filter(block -> block.isText())
                .map(block -> block.asText().text())
                .findFirst()
                .orElse("");
    }

    /**
     * Returns the model being used.
     */
    public String getModel() {
        return model;
    }
}
