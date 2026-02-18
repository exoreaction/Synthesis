package io.exoreaction.synthesis.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import io.exoreaction.synthesis.config.CredentialStore;
import io.exoreaction.synthesis.config.SynthesisConfig;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;

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

        String apiKey = resolveApiKey();

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
     * Creates a ClaudeClient using only the API key, bypassing the enabled flag.
     *
     * <p>Useful for commands that need AI even when ai.enabled=false in config,
     * as long as the API key is present in the environment or credential store.
     *
     * @param model the model to use (e.g., "claude-haiku-4-5-20251001")
     * @return the client, or empty if no API key is available
     */
    public static Optional<ClaudeClient> createIfApiKeyAvailable(String model) {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        try {
            AnthropicClient client = AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();
            return Optional.of(new ClaudeClient(client, model));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Resolves the Anthropic API key using the following priority order:
     * <ol>
     *   <li>ANTHROPIC_API_KEY environment variable</li>
     *   <li>Credential store ({@code ~/.synthesis/credentials})</li>
     * </ol>
     *
     * @return the API key, or null if not found
     */
    private static String resolveApiKey() {
        // 1. Environment variable (highest priority)
        String envKey = System.getenv("ANTHROPIC_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey;
        }

        // 2. Credential store
        return CredentialStore.retrieve("ANTHROPIC_API_KEY").orElse(null);
    }

    /**
     * Result of a generation call, including truncation status.
     *
     * @see <a href="https://github.com/exoreaction/Synthesis/issues/44">#44</a>
     */
    public record GenerationResult(String content, boolean truncated) {}

    /**
     * Generates text and returns metadata including whether output was truncated.
     *
     * <p>Use this for single-pass topics where silent truncation is a bug.
     * When {@code truncated=true}, the caller should warn the user to increase
     * {@code --max-tokens} or switch to a multi-pass topic like {@code --topic weekly}.
     *
     * @param prompt      the prompt to send
     * @param maxTokens   maximum tokens in the response
     * @param temperature sampling temperature (0.0 for deterministic)
     * @return result with content and truncation flag
     * @see <a href="https://github.com/exoreaction/Synthesis/issues/44">#44</a>
     */
    public GenerationResult generateWithMeta(String prompt, int maxTokens, double temperature) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens((long) maxTokens)
                .temperature(temperature)
                .addUserMessage(prompt)
                .build();

        Message message = client.messages().create(params);
        String content = message.content().stream()
                .filter(block -> block.isText())
                .map(block -> block.asText().text())
                .findFirst()
                .orElse("");

        // Check if stop reason indicates truncation (#44)
        boolean truncated = StopReason.MAX_TOKENS.equals(message.stopReason().orElse(null));

        return new GenerationResult(content, truncated);
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
    /**
     * Maximum bytes allowed for a base64-encoded image by the Anthropic API (5 MB).
     * Raw file threshold: 5,242,880 * 3/4 ≈ 3.75 MB.
     */
    private static final long MAX_BASE64_BYTES = 5_242_880;
    private static final long MAX_RAW_BYTES = 3_932_160; // 3.75 MB
    private static final int MAX_DIMENSION = 2048;

    public String generateFromImage(Path imagePath, String prompt, int maxTokens) throws IOException {
        long fileSize = Files.size(imagePath);
        byte[] imageBytes = readImageBytes(imagePath);
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        // Determine media type: resized images are re-encoded as JPEG
        String ext = imagePath.getFileName().toString().toLowerCase();
        Base64ImageSource.MediaType mediaType;
        if (fileSize > MAX_RAW_BYTES) {
            // Was resized and re-encoded as JPEG
            mediaType = Base64ImageSource.MediaType.IMAGE_JPEG;
        } else if (ext.endsWith(".png")) {
            mediaType = Base64ImageSource.MediaType.IMAGE_PNG;
        } else if (ext.endsWith(".gif")) {
            mediaType = Base64ImageSource.MediaType.IMAGE_GIF;
        } else if (ext.endsWith(".webp")) {
            mediaType = Base64ImageSource.MediaType.IMAGE_WEBP;
        } else {
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
     * Reads image bytes, resizing the image if it would exceed the Anthropic API's
     * 5 MB base64 limit. Resized images are re-encoded as JPEG at quality 0.85.
     *
     * @param imagePath path to the image file
     * @return image bytes ready for base64 encoding (≤ 3.75 MB)
     * @throws IOException if the image cannot be read
     */
    private byte[] readImageBytes(Path imagePath) throws IOException {
        long fileSize = Files.size(imagePath);
        if (fileSize <= MAX_RAW_BYTES) {
            return Files.readAllBytes(imagePath);
        }

        // Image is too large for the API — resize to fit within MAX_DIMENSION
        BufferedImage original = ImageIO.read(imagePath.toFile());
        if (original == null) {
            // Can't decode (e.g., animated GIF) — send as-is and let API reject if needed
            return Files.readAllBytes(imagePath);
        }

        int origW = original.getWidth();
        int origH = original.getHeight();
        double scale = Math.min((double) MAX_DIMENSION / origW, (double) MAX_DIMENSION / origH);
        // Only downscale, never upscale
        if (scale >= 1.0) {
            return Files.readAllBytes(imagePath);
        }

        int newW = (int) (origW * scale);
        int newH = (int) (origH * scale);

        BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(original, 0, 0, newW, newH, null);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(resized, "JPEG", baos);
        return baos.toByteArray();
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
