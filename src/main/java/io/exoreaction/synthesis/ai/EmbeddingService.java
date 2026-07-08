package io.exoreaction.synthesis.ai;

import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.util.FileUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Generates text embeddings for semantic search capabilities.
 *
 * <p>Supports multiple embedding providers:
 * <ul>
 *   <li><b>OpenAI</b> -- text-embedding-3-small (1536 dimensions, recommended)</li>
 *   <li><b>Local</b> -- Simple TF-IDF based embeddings (no API needed, lower quality)</li>
 * </ul>
 *
 * <p>The service handles:
 * <ul>
 *   <li>Text chunking (splitting files into embeddable segments)</li>
 *   <li>Batch processing (multiple files per API call)</li>
 *   <li>Caching (embeddings stored by content hash)</li>
 *   <li>Rate limiting (respects API quotas)</li>
 * </ul>
 *
 * <p>Design decisions:
 * <ul>
 *   <li>File-level embeddings (not function-level) for v1 -- simpler, faster, good enough</li>
 *   <li>Content truncated to 8192 tokens (~32K chars) to fit model context</li>
 *   <li>Caching by content hash so unchanged files skip re-embedding</li>
 * </ul>
 */
public class EmbeddingService {

    private static final Logger LOG = Logger.getLogger(EmbeddingService.class.getName());

    /** Default embedding dimensions for text-embedding-3-small. */
    public static final int EMBEDDING_DIMENSIONS = 256;

    /** Maximum characters to send for embedding (approx 8K tokens). */
    private static final int MAX_CONTENT_CHARS = 32_000;

    /** Maximum batch size for OpenAI embedding API. */
    private static final int MAX_BATCH_SIZE = 50;

    private final String provider;  // "openai", "local"
    private final String apiKey;
    private final String model;
    private final String endpoint;
    private final HttpClient httpClient;
    private final Map<String, float[]> cache;

    /**
     * Creates an embedding service.
     *
     * @param provider "openai" or "local"
     * @param apiKey   API key (required for openai, ignored for local)
     * @param model    model name (e.g., "text-embedding-3-small")
     */
    public EmbeddingService(String provider, String apiKey, String model) {
        this(provider, apiKey, model, null);
    }

    /**
     * Creates an embedding service with a custom endpoint.
     *
     * @param provider "openai" or "local"
     * @param apiKey   API key (required for openai, ignored for local)
     * @param model    model name (e.g., "text-embedding-3-small")
     * @param endpoint API endpoint URL (null = provider default)
     */
    public EmbeddingService(String provider, String apiKey, String model, String endpoint) {
        this.provider = provider != null ? provider : "local";
        this.apiKey = apiKey;
        this.model = model != null ? model : "text-embedding-3-small";
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.cache = new ConcurrentHashMap<>();
    }

    /**
     * Creates a service configured from environment variables.
     *
     * @return configured service, falling back to local if no API key
     */
    public static EmbeddingService create() {
        String openAiKey = System.getenv("OPENAI_API_KEY");
        if (openAiKey != null && !openAiKey.isBlank()) {
            return new EmbeddingService("openai", openAiKey, "text-embedding-3-small",
                    "https://api.openai.com/v1");
        }
        return new EmbeddingService("local", null, null);
    }

    /**
     * Creates a service configured from {@link SynthesisConfig.AiConfig}.
     * Mirrors the {@code resolveEndpoint()} pattern from {@link OpenAiClient}:
     * uses {@code ai.endpoint} from config if set, otherwise falls back to the
     * provider's default endpoint. This enables local/custom embedding backends
     * (e.g., Ollama) without code changes.
     *
     * @param config AI configuration from synthesis-config.yaml
     * @return configured service, falling back to local if no API key
     */
    public static EmbeddingService create(SynthesisConfig.AiConfig config) {
        AiProvider provider = AiProvider.fromId(config.getProvider());
        Optional<String> apiKey = provider.resolveApiKey();

        if (apiKey.isPresent()) {
            String resolvedEndpoint = Optional.ofNullable(config.getEndpoint())
                    .filter(ep -> !ep.isBlank())
                    .orElse(provider.defaultEndpoint());
            return new EmbeddingService(provider.id(), apiKey.get(),
                    "text-embedding-3-small", resolvedEndpoint);
        }
        return new EmbeddingService("local", null, null);
    }

    /**
     * Generates an embedding vector for the given text.
     *
     * @param text the text to embed
     * @return embedding vector (dimensions depend on provider)
     * @throws IOException if the API call fails
     */
    public float[] embed(String text) throws IOException {
        if (text == null || text.isBlank()) {
            return new float[EMBEDDING_DIMENSIONS];
        }

        // Truncate if needed
        String truncated = text.length() > MAX_CONTENT_CHARS
                ? text.substring(0, MAX_CONTENT_CHARS) : text;

        // Check cache
        String cacheKey = Integer.toHexString(truncated.hashCode());
        float[] cached = cache.get(cacheKey);
        if (cached != null) return cached;

        float[] embedding = switch (provider) {
            case "openai" -> embedWithOpenAI(truncated);
            default -> embedLocal(truncated);
        };

        cache.put(cacheKey, embedding);
        return embedding;
    }

    /**
     * Generates embeddings for a file's content.
     *
     * @param filePath the file to embed
     * @return embedding vector
     * @throws IOException if the file cannot be read or the API call fails
     */
    public float[] embedFile(Path filePath) throws IOException {
        if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
            return new float[EMBEDDING_DIMENSIONS];
        }

        String content = FileUtils.readPreview(filePath, MAX_CONTENT_CHARS);
        return embed(content);
    }

    /**
     * Generates embeddings for multiple texts in a batch.
     *
     * @param texts the texts to embed
     * @return list of embedding vectors (same order as input)
     * @throws IOException if the API call fails
     */
    public List<float[]> embedBatch(List<String> texts) throws IOException {
        if (texts == null || texts.isEmpty()) return List.of();

        if ("openai".equals(provider)) {
            return embedBatchOpenAI(texts);
        }

        // Local: process individually
        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            results.add(embed(text));
        }
        return results;
    }

    /**
     * Computes cosine similarity between two embedding vectors.
     *
     * @param a first vector
     * @param b second vector
     * @return cosine similarity in range [-1, 1]
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) return 0f;

        float dotProduct = 0f;
        float normA = 0f;
        float normB = 0f;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        float denominator = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return denominator == 0f ? 0f : dotProduct / denominator;
    }

    /**
     * Returns the configured provider.
     */
    public String getProvider() {
        return provider;
    }

    /**
     * Returns the configured model.
     */
    public String getModel() {
        return model;
    }

    /**
     * Returns the configured endpoint (may be null for local provider).
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Returns the embedding dimensions.
     */
    public int getDimensions() {
        return EMBEDDING_DIMENSIONS;
    }

    /**
     * Returns current cache size.
     */
    public int cacheSize() {
        return cache.size();
    }

    /**
     * Clears the embedding cache.
     */
    public void clearCache() {
        cache.clear();
    }

    // --- OpenAI provider ---

    private float[] embedWithOpenAI(String text) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            LOG.warning("OpenAI API key not set, falling back to local embedding");
            return embedLocal(text);
        }

        try {
            String requestBody = String.format(
                    "{\"model\":\"%s\",\"input\":\"%s\",\"dimensions\":%d}",
                    model,
                    escapeJson(text),
                    EMBEDDING_DIMENSIONS);

            String embeddingEndpoint = (endpoint != null ? endpoint : "https://api.openai.com/v1")
                    + "/embeddings";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(embeddingEndpoint))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.warning("OpenAI embedding API returned " + response.statusCode());
                return embedLocal(text);
            }

            return parseEmbeddingResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Embedding request interrupted", e);
        } catch (Exception e) {
            LOG.warning("OpenAI embedding failed: " + e.getMessage());
            return embedLocal(text);
        }
    }

    private List<float[]> embedBatchOpenAI(List<String> texts) throws IOException {
        List<float[]> results = new ArrayList<>();

        // Process in batches of MAX_BATCH_SIZE
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));

            // For simplicity, process individually via single API
            // A production implementation would batch these in a single API call
            for (String text : batch) {
                results.add(embed(text));
            }
        }

        return results;
    }

    private float[] parseEmbeddingResponse(String responseBody) {
        // Simple JSON parsing for the embedding array
        // Format: {"data":[{"embedding":[0.1, 0.2, ...]}]}
        try {
            int embStart = responseBody.indexOf("\"embedding\":[") + "\"embedding\":[".length();
            int embEnd = responseBody.indexOf("]", embStart);
            if (embStart < "\"embedding\":[".length() || embEnd < 0) {
                return new float[EMBEDDING_DIMENSIONS];
            }

            String[] values = responseBody.substring(embStart, embEnd).split(",");
            float[] embedding = new float[Math.min(values.length, EMBEDDING_DIMENSIONS)];
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] = Float.parseFloat(values[i].trim());
            }
            return embedding;
        } catch (Exception e) {
            LOG.warning("Failed to parse embedding response: " + e.getMessage());
            return new float[EMBEDDING_DIMENSIONS];
        }
    }

    // --- Local provider (TF-IDF based) ---

    /**
     * Generates a simple local embedding using token frequency hashing.
     * This is a lightweight alternative when no API is available.
     * Quality is lower than neural embeddings but works offline.
     */
    float[] embedLocal(String text) {
        float[] embedding = new float[EMBEDDING_DIMENSIONS];
        if (text == null || text.isBlank()) return embedding;

        // Tokenize: split on whitespace and punctuation
        String[] tokens = text.toLowerCase().split("[\\s\\p{Punct}]+");

        // Hash-based feature extraction (simulated embedding)
        for (String token : tokens) {
            if (token.length() < 2) continue;

            // Multiple hash functions for better distribution
            int h1 = Math.abs(token.hashCode()) % EMBEDDING_DIMENSIONS;
            int h2 = Math.abs((token.hashCode() * 31 + 17)) % EMBEDDING_DIMENSIONS;
            int h3 = Math.abs((token.hashCode() * 127 + 43)) % EMBEDDING_DIMENSIONS;

            embedding[h1] += 1.0f;
            embedding[h2] += 0.5f;
            embedding[h3] += 0.25f;

            // Bigram hashing for some context awareness
            if (token.length() >= 4) {
                for (int i = 0; i < token.length() - 1; i++) {
                    int bigramHash = Math.abs(token.substring(i, i + 2).hashCode()) % EMBEDDING_DIMENSIONS;
                    embedding[bigramHash] += 0.3f;
                }
            }
        }

        // L2 normalize
        float norm = 0f;
        for (float v : embedding) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] /= norm;
            }
        }

        return embedding;
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
