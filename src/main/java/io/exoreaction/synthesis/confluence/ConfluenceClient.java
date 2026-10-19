package io.exoreaction.synthesis.confluence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.exoreaction.synthesis.config.SynthesisConfig.ConfluenceConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

/**
 * HTTP client for the Confluence Cloud REST API v2 ({@code /wiki/api/v2/...}).
 *
 * <p>Mirrors {@code NotionClient}'s structural role (pagination, retry, error
 * handling) but differs where Confluence's actual API shape differs from Notion's:
 * <ul>
 *   <li><b>Auth</b>: Basic auth with an Atlassian account email + API token, not a
 *       single bearer integration token. This is the v1 "API-token" auth path called
 *       for in RFC #541 — OAuth 2.0 (3LO) is an explicit non-goal for v1.</li>
 *   <li><b>Pagination</b>: cursor-based via a relative {@code _links.next} URL, not
 *       Notion's {@code has_more}/{@code next_cursor} fields.</li>
 *   <li><b>Rate limiting</b>: Atlassian Cloud does not publish a fixed requests/second
 *       figure the way Notion does (3 req/s). Rather than guess a number, this client
 *       does not proactively throttle; it honors a {@code Retry-After} header on 429
 *       responses and retries once, the same single-retry shape as
 *       {@code NotionClient}.</li>
 *   <li><b>Fetch shape</b>: a single "list pages in a space" call returns each page's
 *       {@code parentId} directly, so no BFS traversal is needed to discover the page
 *       tree the way Notion's child-page block walk is (see {@code NotionWorkspaceSource}).</li>
 * </ul>
 *
 * <p>Use {@link #fromConfig(ConfluenceConfig)} for credential resolution from config
 * or environment variables.
 */
public class ConfluenceClient {

    private static final Logger LOG = Logger.getLogger(ConfluenceClient.class.getName());

    private static final int PAGE_SIZE = 100;

    /** Maximum length of error body excerpt included in exception messages. */
    private static final int ERROR_EXCERPT_LENGTH = 200;

    /** Default wait before retrying a 429 when no Retry-After header is present. */
    private static final long DEFAULT_RETRY_AFTER_MS = 1000;

    private final String apiBase;
    private final String siteOrigin;
    private final String authHeader;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new Confluence API client.
     *
     * @param baseUrl    the Confluence Cloud site base URL (e.g. {@code https://yoursite.atlassian.net/wiki})
     * @param email      Atlassian account email (Basic auth username)
     * @param apiToken   Confluence/Atlassian API token (Basic auth password)
     * @param httpClient Java HttpClient instance for making requests
     */
    public ConfluenceClient(String baseUrl, String email, String apiToken, HttpClient httpClient) {
        this(baseUrl, email, apiToken, httpClient, new ObjectMapper());
    }

    /**
     * Creates a new Confluence API client with a custom ObjectMapper.
     */
    public ConfluenceClient(String baseUrl, String email, String apiToken,
                             HttpClient httpClient, ObjectMapper objectMapper) {
        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.apiBase = normalizedBase + "/api/v2";
        URI parsed = URI.create(normalizedBase);
        this.siteOrigin = parsed.getScheme() + "://" + parsed.getAuthority();
        String credentials = Base64.getEncoder().encodeToString((email + ":" + apiToken).getBytes());
        this.authHeader = "Basic " + credentials;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Factory method that resolves Confluence credentials from config or environment.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@link ConfluenceConfig#getBaseUrl()}/{@code getEmail()}/{@code getApiToken()}
     *       from the Synthesis config file</li>
     *   <li>{@code CONFLUENCE_BASE_URL}/{@code CONFLUENCE_EMAIL}/{@code CONFLUENCE_API_TOKEN}
     *       environment variables</li>
     * </ol>
     *
     * <p>Unlike {@code NotionClient.fromConfig}, there is no OAuth token store fallback —
     * v1 Confluence auth is API-token only (RFC #541 non-goal: no OAuth 2.0 in the first cut).
     *
     * @param config the Confluence configuration section
     * @return a new ConfluenceClient instance
     * @throws IllegalStateException if the base URL, email, or API token cannot be resolved
     */
    public static ConfluenceClient fromConfig(ConfluenceConfig config) {
        String baseUrl = firstNonBlank(config.getBaseUrl(), System.getenv("CONFLUENCE_BASE_URL"));
        String email = firstNonBlank(config.getEmail(), System.getenv("CONFLUENCE_EMAIL"));
        String apiToken = firstNonBlank(config.getApiToken(), System.getenv("CONFLUENCE_API_TOKEN"));

        if (baseUrl == null || email == null || apiToken == null) {
            throw new IllegalStateException(
                    "Incomplete Confluence credentials. Set confluence.baseUrl, confluence.email, "
                    + "and confluence.apiToken in config, or the CONFLUENCE_BASE_URL, CONFLUENCE_EMAIL, "
                    + "and CONFLUENCE_API_TOKEN environment variables. Generate an API token at: "
                    + "https://id.atlassian.com/manage-profile/security/api-tokens");
        }
        return new ConfluenceClient(baseUrl, email, apiToken, HttpClient.newHttpClient());
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    /**
     * Resolves a Confluence space by its key.
     *
     * @param spaceKey the space key (e.g. {@code "ENG"})
     * @return the space JSON object
     * @throws IOException              on HTTP errors
     * @throws InterruptedException     if the thread is interrupted
     * @throws IllegalArgumentException if no space with that key exists
     */
    public JsonNode getSpaceByKey(String spaceKey) throws IOException, InterruptedException {
        String url = apiBase + "/spaces?keys=" + spaceKey + "&limit=1";
        HttpRequest request = buildGetRequest(url);
        JsonNode response = executeWithRetry(request, spaceKey);

        JsonNode results = response.get("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            throw new IllegalArgumentException("Confluence space not found: " + spaceKey);
        }
        return results.get(0);
    }

    /**
     * Fetches a single Confluence page by ID, including its body content.
     *
     * @param pageId     the Confluence page ID
     * @param bodyFormat the body representation to request ({@code "storage"} or
     *                   {@code "atlas_doc_format"})
     * @return the page JSON object
     * @throws IOException              on HTTP errors (4xx/5xx)
     * @throws InterruptedException     if the thread is interrupted
     * @throws IllegalStateException    if the credentials are invalid (401)
     * @throws IllegalArgumentException if the page is not found (404)
     */
    public JsonNode getPage(String pageId, String bodyFormat) throws IOException, InterruptedException {
        String url = apiBase + "/pages/" + pageId + "?body-format=" + bodyFormat;
        HttpRequest request = buildGetRequest(url);
        return executeWithRetry(request, pageId);
    }

    /**
     * Fetches all pages in a space, including body content, handling pagination automatically.
     *
     * <p>Unlike Notion (where discovering the page tree requires BFS over child-page
     * blocks), each page returned here already carries its own {@code parentId}, so a
     * single paginated listing call is enough to reconstruct the full hierarchy.
     *
     * @param spaceId    the Confluence space ID (not the space key — resolve via {@link #getSpaceByKey})
     * @param bodyFormat the body representation to request ({@code "storage"} or
     *                   {@code "atlas_doc_format"})
     * @return all pages in the space, concatenated across result pages
     * @throws IOException          on HTTP errors
     * @throws InterruptedException if the thread is interrupted
     */
    public List<JsonNode> getPagesForSpace(String spaceId, String bodyFormat)
            throws IOException, InterruptedException {
        List<JsonNode> allPages = new ArrayList<>();
        String url = apiBase + "/pages?space-id=" + spaceId
                + "&body-format=" + bodyFormat + "&limit=" + PAGE_SIZE;

        while (url != null) {
            HttpRequest request = buildGetRequest(url);
            JsonNode response = executeWithRetry(request, spaceId);

            JsonNode results = response.get("results");
            if (results != null && results.isArray()) {
                for (JsonNode page : results) {
                    allPages.add(page);
                }
            }

            url = nextUrl(response);
        }

        return allPages;
    }

    /**
     * Fetches the labels attached to a page.
     *
     * @param pageId the Confluence page ID
     * @return label names (e.g. {@code ["architecture", "draft"]})
     * @throws IOException          on HTTP errors
     * @throws InterruptedException if the thread is interrupted
     */
    public List<String> getLabelsForPage(String pageId) throws IOException, InterruptedException {
        List<String> labels = new ArrayList<>();
        String url = apiBase + "/pages/" + pageId + "/labels?limit=" + PAGE_SIZE;

        while (url != null) {
            HttpRequest request = buildGetRequest(url);
            JsonNode response = executeWithRetry(request, pageId);

            JsonNode results = response.get("results");
            if (results != null && results.isArray()) {
                for (JsonNode label : results) {
                    if (label.has("name")) {
                        labels.add(label.get("name").asText());
                    }
                }
            }

            url = nextUrl(response);
        }

        return labels;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Extracts and resolves the {@code _links.next} cursor URL from a paginated response,
     * or {@code null} if there are no more pages.
     *
     * <p>{@code _links.next} is a relative path (e.g. {@code "/wiki/api/v2/pages?cursor=..."}),
     * not an absolute URL — it must be resolved against the site origin.
     */
    private String nextUrl(JsonNode response) {
        JsonNode links = response.get("_links");
        if (links == null) return null;
        JsonNode next = links.get("next");
        if (next == null || next.isNull()) return null;

        String nextPath = next.asText();
        if (nextPath.isBlank()) return null;
        return nextPath.startsWith("http") ? nextPath : siteOrigin + nextPath;
    }

    private HttpRequest buildGetRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    /**
     * Executes an HTTP request with one retry on 429, honoring {@code Retry-After} if present.
     *
     * @param request   the HTTP request
     * @param contextId optional page/space ID for error messages
     * @return the parsed JSON response body
     */
    private JsonNode executeWithRetry(HttpRequest request, String contextId)
            throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 429) {
            long retryAfterMs = retryAfterMs(response);
            LOG.fine("Confluence API rate limited (429), retrying after " + retryAfterMs + "ms");
            Thread.sleep(retryAfterMs);
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
        }

        return handleResponse(response, status, contextId);
    }

    private long retryAfterMs(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Long.parseLong(value.trim()) * 1000L;
                    } catch (NumberFormatException e) {
                        return DEFAULT_RETRY_AFTER_MS;
                    }
                })
                .orElse(DEFAULT_RETRY_AFTER_MS);
    }

    /**
     * Handles the HTTP response, throwing appropriate exceptions for error status codes.
     */
    private JsonNode handleResponse(HttpResponse<String> response, int status, String contextId)
            throws IOException {
        String body = response.body();

        if (status == 401) {
            throw new IllegalStateException("Invalid Confluence credentials");
        }
        if (status == 404) {
            String id = contextId != null ? contextId : "unknown";
            throw new IllegalArgumentException("Confluence resource not found: " + id);
        }
        if (status >= 400) {
            String excerpt = body != null && body.length() > ERROR_EXCERPT_LENGTH
                    ? body.substring(0, ERROR_EXCERPT_LENGTH)
                    : (body != null ? body : "");
            throw new IOException("Confluence API error: " + status + " " + excerpt);
        }

        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IOException("Failed to parse Confluence API response: " + e.getMessage(), e);
        }
    }
}
