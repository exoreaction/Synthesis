package io.exoreaction.synthesis.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.exoreaction.synthesis.config.SynthesisConfig.NotionConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * HTTP client for the Notion API v2022-06-28.
 *
 * <p>Provides methods to fetch pages, block children, and search all pages
 * in a Notion workspace. Handles pagination, rate limiting (3 req/s), and
 * error responses (401, 404, 429, other 4xx/5xx).
 *
 * <p>Use {@link #fromConfig(NotionConfig)} for credential resolution from
 * config or the {@code NOTION_TOKEN} environment variable.
 */
public class NotionClient {

    private static final Logger LOG = Logger.getLogger(NotionClient.class.getName());

    private static final String BASE_URL = "https://api.notion.com/v1";
    private static final String NOTION_VERSION = "2022-06-28";
    private static final int PAGE_SIZE = 100;

    /** Minimum interval between requests in milliseconds (3 req/s = 334ms). */
    private static final long MIN_REQUEST_INTERVAL_MS = 334;

    /** Maximum length of error body excerpt included in exception messages. */
    private static final int ERROR_EXCERPT_LENGTH = 200;

    private final String token;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** Timestamp of the last request, used for rate limiting. */
    private volatile long lastRequestTimeMs;

    /**
     * Creates a new Notion API client.
     *
     * @param token      Notion integration token (starts with {@code ntn_} or {@code secret_})
     * @param httpClient Java HttpClient instance for making requests
     */
    public NotionClient(String token, HttpClient httpClient) {
        this.token = token;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.lastRequestTimeMs = 0;
    }

    /**
     * Creates a new Notion API client with a custom ObjectMapper.
     *
     * @param token         Notion integration token
     * @param httpClient    Java HttpClient instance
     * @param objectMapper  Jackson ObjectMapper for JSON parsing
     */
    public NotionClient(String token, HttpClient httpClient, ObjectMapper objectMapper) {
        this.token = token;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.lastRequestTimeMs = 0;
    }

    /**
     * Factory method that resolves the Notion token from config or environment.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@link NotionConfig#getToken()} from the Synthesis config file</li>
     *   <li>{@code NOTION_TOKEN} environment variable</li>
     * </ol>
     *
     * @param config the Notion configuration section
     * @return a new NotionClient instance
     * @throws IllegalStateException if no token is found
     */
    public static NotionClient fromConfig(NotionConfig config) {
        String token = config.getToken();
        if (token == null || token.isBlank()) {
            token = System.getenv("NOTION_TOKEN");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "No Notion token configured. Set notion.token in config or NOTION_TOKEN env var.");
        }
        return new NotionClient(token, HttpClient.newHttpClient());
    }

    /**
     * Fetches a single Notion page by ID.
     *
     * @param pageId the Notion page UUID (with or without dashes)
     * @return the page JSON object
     * @throws IOException              on HTTP errors (4xx/5xx)
     * @throws InterruptedException     if the thread is interrupted
     * @throws IllegalStateException    if the token is invalid (401)
     * @throws IllegalArgumentException if the page is not found (404)
     */
    public JsonNode getPage(String pageId) throws IOException, InterruptedException {
        String url = BASE_URL + "/pages/" + pageId;
        HttpRequest request = buildGetRequest(url);
        return executeWithRetry(request, pageId);
    }

    /**
     * Fetches all block children of a given block, handling pagination automatically.
     *
     * <p>Iterates through all pages of results until {@code has_more} is false,
     * concatenating all block objects into a single list.
     *
     * @param blockId the parent block UUID
     * @return all child blocks concatenated across pages
     * @throws IOException          on HTTP errors
     * @throws InterruptedException if the thread is interrupted
     */
    public List<JsonNode> getBlockChildren(String blockId) throws IOException, InterruptedException {
        List<JsonNode> allBlocks = new ArrayList<>();
        String cursor = null;

        do {
            String url = BASE_URL + "/blocks/" + blockId + "/children?page_size=" + PAGE_SIZE;
            if (cursor != null) {
                url += "&start_cursor=" + cursor;
            }

            HttpRequest request = buildGetRequest(url);
            JsonNode response = executeWithRetry(request, blockId);

            JsonNode results = response.get("results");
            if (results != null && results.isArray()) {
                for (JsonNode block : results) {
                    allBlocks.add(block);
                }
            }

            boolean hasMore = response.has("has_more") && response.get("has_more").asBoolean();
            cursor = hasMore && response.has("next_cursor") && !response.get("next_cursor").isNull()
                    ? response.get("next_cursor").asText()
                    : null;

        } while (cursor != null);

        return allBlocks;
    }

    /**
     * Searches for all pages accessible to the integration token.
     *
     * <p>Uses the Notion search endpoint with an empty query and page filter.
     * Handles pagination automatically, returning all pages across all result pages.
     *
     * @return all accessible pages as JSON objects
     * @throws IOException          on HTTP errors
     * @throws InterruptedException if the thread is interrupted
     */
    public List<JsonNode> searchAllPages() throws IOException, InterruptedException {
        List<JsonNode> allPages = new ArrayList<>();
        String cursor = null;

        do {
            String body = buildSearchBody(cursor);
            HttpRequest request = buildPostRequest(BASE_URL + "/search", body);
            JsonNode response = executeWithRetry(request, null);

            JsonNode results = response.get("results");
            if (results != null && results.isArray()) {
                for (JsonNode page : results) {
                    allPages.add(page);
                }
            }

            boolean hasMore = response.has("has_more") && response.get("has_more").asBoolean();
            cursor = hasMore && response.has("next_cursor") && !response.get("next_cursor").isNull()
                    ? response.get("next_cursor").asText()
                    : null;

        } while (cursor != null);

        return allPages;
    }

    /**
     * Fetches the first page of child blocks for a given parent, filtering for child pages.
     *
     * <p>Unlike {@link #getBlockChildren(String)}, this method does NOT paginate;
     * it returns only the first page of results (up to 100 blocks). This is intended
     * for lightweight child page discovery where exhaustive enumeration is not needed.
     *
     * @param parentId the parent block UUID
     * @return child blocks from the first result page
     * @throws IOException          on HTTP errors
     * @throws InterruptedException if the thread is interrupted
     */
    public List<JsonNode> getChildPages(String parentId) throws IOException, InterruptedException {
        String url = BASE_URL + "/blocks/" + parentId + "/children?page_size=" + PAGE_SIZE;
        HttpRequest request = buildGetRequest(url);
        JsonNode response = executeWithRetry(request, parentId);

        List<JsonNode> children = new ArrayList<>();
        JsonNode results = response.get("results");
        if (results != null && results.isArray()) {
            for (JsonNode block : results) {
                children.add(block);
            }
        }
        return children;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private HttpRequest buildGetRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", NOTION_VERSION)
                .header("Content-Type", "application/json")
                .GET()
                .build();
    }

    private HttpRequest buildPostRequest(String url, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", NOTION_VERSION)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private String buildSearchBody(String startCursor) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"query\":\"\",");
        sb.append("\"filter\":{\"property\":\"object\",\"value\":\"page\"},");
        sb.append("\"page_size\":").append(PAGE_SIZE);
        if (startCursor != null) {
            sb.append(",\"start_cursor\":\"").append(startCursor).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Executes an HTTP request with rate limiting and one retry on 429.
     *
     * @param request the HTTP request
     * @param contextId optional page/block ID for error messages
     * @return the parsed JSON response body
     */
    private JsonNode executeWithRetry(HttpRequest request, String contextId)
            throws IOException, InterruptedException {
        throttle();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        // Retry once on 429 (rate limited)
        if (status == 429) {
            LOG.fine("Notion API rate limited (429), retrying after 1 second");
            Thread.sleep(1000);
            throttle();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
        }

        return handleResponse(response, status, contextId);
    }

    /**
     * Handles the HTTP response, throwing appropriate exceptions for error status codes.
     */
    private JsonNode handleResponse(HttpResponse<String> response, int status, String contextId)
            throws IOException {
        String body = response.body();

        if (status == 401) {
            throw new IllegalStateException("Invalid Notion token");
        }
        if (status == 404) {
            String id = contextId != null ? contextId : "unknown";
            throw new IllegalArgumentException("Notion page not found: " + id);
        }
        if (status >= 400) {
            String excerpt = body != null && body.length() > ERROR_EXCERPT_LENGTH
                    ? body.substring(0, ERROR_EXCERPT_LENGTH)
                    : (body != null ? body : "");
            throw new IOException("Notion API error: " + status + " " + excerpt);
        }

        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IOException("Failed to parse Notion API response: " + e.getMessage(), e);
        }
    }

    /**
     * Enforces the 3 requests/second rate limit by sleeping if needed.
     */
    private synchronized void throttle() throws InterruptedException {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTimeMs;
        if (elapsed < MIN_REQUEST_INTERVAL_MS && lastRequestTimeMs > 0) {
            long sleepMs = MIN_REQUEST_INTERVAL_MS - elapsed;
            Thread.sleep(sleepMs);
        }
        lastRequestTimeMs = System.currentTimeMillis();
    }
}
