package io.exoreaction.synthesis.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.util.AnsiOutput;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * {@link AiClient} for OpenAI-compatible Chat Completions APIs.
 *
 * <p>Named for the protocol, not the company: this client serves the {@code openai}
 * and {@code deepseek} providers, and via the {@code ai.endpoint} config any other
 * OpenAI-compatible server. Uses {@link java.net.http.HttpClient} directly
 * (same pattern as {@link EmbeddingService}) — no extra SDK dependency.
 */
public class OpenAiClient implements AiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final long RETRY_BACKOFF_MS = 1_000;
    private static final int ERROR_BODY_SNIPPET_CHARS = 300;

    private final HttpClient http;
    private final String endpoint;
    private final String model;
    private final String apiKey;

    OpenAiClient(String endpoint, String model, String apiKey) {
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.model = model;
        this.apiKey = apiKey;
    }

    /**
     * Creates an OpenAiClient if AI is enabled and the provider's API key is available.
     */
    public static Optional<AiClient> create(SynthesisConfig.AiConfig config) {
        if (!config.isEnabled()) {
            return Optional.empty();
        }
        AiProvider provider = AiProvider.fromId(config.getProvider());
        Optional<String> apiKey = provider.resolveApiKey();
        if (apiKey.isEmpty()) {
            AnsiOutput.printWarning("AI features require an API key for provider '" + provider.id() + "'.");
            System.out.println("  Run: synthesis credentials set " + provider.apiKeyName() + " sk-...");
            System.out.println("  Or:  export " + provider.apiKeyName() + "=sk-...");
            return Optional.empty();
        }
        return apiKey.map(key -> new OpenAiClient(
                resolveEndpoint(config, provider), provider.resolveModel(config.getModel()), key));
    }

    /**
     * Creates an OpenAiClient using only the API key, bypassing the enabled flag.
     */
    public static Optional<AiClient> createIfApiKeyAvailable(SynthesisConfig.AiConfig config, String model) {
        AiProvider provider = AiProvider.fromId(config.getProvider());
        return provider.resolveApiKey()
                .map(key -> new OpenAiClient(resolveEndpoint(config, provider), provider.resolveModel(model), key));
    }

    private static String resolveEndpoint(SynthesisConfig.AiConfig config, AiProvider provider) {
        return Optional.ofNullable(config.getEndpoint())
                .filter(endpoint -> !endpoint.isBlank())
                .orElse(provider.defaultEndpoint());
    }

    @Override
    public String generate(String prompt, int maxTokens) {
        return firstChoiceContent(complete(textBody(prompt, maxTokens, OptionalDouble.empty())));
    }

    @Override
    public GenerationResult generateWithMeta(String prompt, int maxTokens, double temperature) {
        JsonNode response = complete(textBody(prompt, maxTokens, OptionalDouble.of(temperature)));
        boolean truncated = "length".equals(firstChoice(response).path("finish_reason").asText());
        return new GenerationResult(firstChoiceContent(response), truncated);
    }

    @Override
    public String generateFromImage(Path imagePath, String prompt, int maxTokens) throws IOException {
        ImagePayloads.Payload payload = ImagePayloads.read(imagePath);
        String dataUri = "data:" + payload.mediaType() + ";base64,"
                + Base64.getEncoder().encodeToString(payload.bytes());

        ObjectNode body = baseBody(maxTokens, OptionalDouble.empty());
        ArrayNode content = body.putArray("messages")
                .addObject().put("role", "user")
                .putArray("content");
        content.addObject().put("type", "text").put("text", prompt);
        content.addObject().put("type", "image_url")
                .putObject("image_url").put("url", dataUri);

        return firstChoiceContent(complete(body));
    }

    @Override
    public String getModel() {
        return model;
    }

    public String getEndpoint() {
        return endpoint;
    }

    private ObjectNode baseBody(int maxTokens, OptionalDouble temperature) {
        ObjectNode body = MAPPER.createObjectNode()
                .put("model", model)
                .put("max_tokens", maxTokens);
        temperature.ifPresent(t -> body.put("temperature", t));
        return body;
    }

    private ObjectNode textBody(String prompt, int maxTokens, OptionalDouble temperature) {
        ObjectNode body = baseBody(maxTokens, temperature);
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        return body;
    }

    private static JsonNode firstChoice(JsonNode response) {
        return response.path("choices").path(0);
    }

    private static String firstChoiceContent(JsonNode response) {
        return firstChoice(response).path("message").path("content").asText("");
    }

    private JsonNode complete(ObjectNode body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = sendWithRetry(request);
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI-compatible API at %s returned HTTP %d: %s"
                    .formatted(endpoint, response.statusCode(), snippet(response.body())));
        }
        try {
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new RuntimeException("OpenAI-compatible API at %s returned unparseable JSON: %s"
                    .formatted(endpoint, snippet(response.body())), e);
        }
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) {
        HttpResponse<String> response = send(request);
        if (!isRetryable(response.statusCode())) {
            return response;
        }
        try {
            Thread.sleep(RETRY_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return response;
        }
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new RuntimeException("Request to OpenAI-compatible API at " + endpoint
                    + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Request to OpenAI-compatible API at " + endpoint
                    + " was interrupted", e);
        }
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private static String snippet(String body) {
        return Optional.ofNullable(body)
                .map(String::strip)
                .map(text -> text.length() > ERROR_BODY_SNIPPET_CHARS
                        ? text.substring(0, ERROR_BODY_SNIPPET_CHARS) + "…" : text)
                .filter(text -> !text.isEmpty())
                .orElse("(empty body)");
    }
}
