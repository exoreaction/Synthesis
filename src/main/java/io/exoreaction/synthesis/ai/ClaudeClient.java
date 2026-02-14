package io.exoreaction.synthesis.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import io.exoreaction.synthesis.config.SynthesisConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
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
     * Generates a text description from an image file using Claude's vision capabilities.
     *
     * <p>Reads the image, encodes it as base64, and sends it to Claude with a
     * description prompt. Supports JPEG, PNG, GIF, and WebP formats.
     *
     * @param imagePath  path to the image file
     * @param prompt     the prompt describing what to extract/describe
     * @param maxTokens  maximum tokens in the response
     * @return the generated description
     * @throws IOException if the image cannot be read
     */
    public String generateFromImage(Path imagePath, String prompt, int maxTokens) throws IOException {
        byte[] imageBytes = Files.readAllBytes(imagePath);
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        // Determine media type from extension
        String ext = imagePath.getFileName().toString().toLowerCase();
        Base64ImageSource.MediaType mediaType;
        if (ext.endsWith(".png")) {
            mediaType = Base64ImageSource.MediaType.IMAGE_PNG;
        } else if (ext.endsWith(".gif")) {
            mediaType = Base64ImageSource.MediaType.IMAGE_GIF;
        } else if (ext.endsWith(".webp")) {
            mediaType = Base64ImageSource.MediaType.IMAGE_WEBP;
        } else {
            // Default to JPEG for .jpg, .jpeg, and other formats
            mediaType = Base64ImageSource.MediaType.IMAGE_JPEG;
        }

        // Build the image source
        Base64ImageSource imageSource = Base64ImageSource.builder()
                .mediaType(mediaType)
                .data(base64Image)
                .build();

        // Build the image block
        ImageBlockParam imageBlock = ImageBlockParam.builder()
                .source(ImageBlockParam.Source.ofBase64(imageSource))
                .build();

        // Build the text block with the prompt
        TextBlockParam textBlock = TextBlockParam.builder()
                .text(prompt)
                .build();

        // Build the message with image + text content blocks
        ContentBlockParam imageContent = ContentBlockParam.ofImage(imageBlock);
        ContentBlockParam textContent = ContentBlockParam.ofText(textBlock);

        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens((long) maxTokens)
                .addUserMessageOfBlockParams(List.of(imageContent, textContent))
                .build();

        Message message = client.messages().create(params);

        return message.content().stream()
                .filter(block -> block.isText())
                .map(block -> block.asText().text())
                .findFirst()
                .orElse("");
    }

    /**
     * Checks if the given image format is supported for vision analysis.
     *
     * @param extension file extension including dot (e.g., ".jpg")
     * @return true if the format is supported
     */
    public static boolean isVisionSupported(String extension) {
        String ext = extension.toLowerCase();
        return ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".png")
                || ext.equals(".gif") || ext.equals(".webp");
    }

    /**
     * Estimates the cost of analyzing an image based on its file size.
     * Rough estimate: ~$0.02 per image for typical sizes.
     *
     * @param fileSizeBytes the image file size
     * @return estimated cost in USD
     */
    public static double estimateVisionCost(long fileSizeBytes) {
        // Base cost for an image analysis (~750 input tokens for the image
        // + ~100 output tokens for the description)
        // At Claude Sonnet pricing: ~$0.003/1K input + $0.015/1K output
        // Average image: ~1000 tokens input, ~200 tokens output
        // Cost: $0.003 + $0.003 = ~$0.006 per image
        // But larger images use more tokens
        if (fileSizeBytes > 5_000_000) return 0.04; // Large images
        if (fileSizeBytes > 1_000_000) return 0.02; // Medium images
        return 0.01; // Small images
    }

    /**
     * Returns the model being used.
     */
    public String getModel() {
        return model;
    }
}
