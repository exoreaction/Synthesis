package io.exoreaction.synthesis.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.exoreaction.synthesis.config.SynthesisConfig.JiraConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

/**
 * HTTP client for the Jira Cloud REST API v3.
 *
 * <p>Provides methods to search issues via JQL, fetch a single issue, fetch an
 * issue's changelog (field transition history), and fetch an issue's comments.
 * Handles pagination and error responses (401, 404, 429, other 4xx/5xx) the
 * same way {@code NotionClient} does.
 *
 * <p>Unlike Notion, Jira Cloud is tenant-specific (no fixed API host) and uses
 * HTTP Basic auth with an Atlassian account email + API token, not a bearer
 * token. Use {@link #fromConfig(JiraConfig)} for credential resolution from
 * config or {@code JIRA_BASE_URL} / {@code JIRA_EMAIL} / {@code JIRA_API_TOKEN}
 * environment variables.
 *
 * <p>The issue search endpoint standardizes on {@code POST /rest/api/3/search/jql}
 * (the {@code nextPageToken}-based successor to the deprecated {@code /search}
 * endpoint, per Atlassian's migration guidance) rather than the older
 * {@code startAt}-based endpoint.
 */
public class JiraClient {

    private static final Logger LOG = Logger.getLogger(JiraClient.class.getName());

    private static final String API_PATH = "/rest/api/3";
    private static final int PAGE_SIZE = 100;

    /** Maximum length of error body excerpt included in exception messages. */
    private static final int ERROR_EXCERPT_LENGTH = 200;

    /** Default fields requested for search/get-issue calls. */
    private static final List<String> DEFAULT_FIELDS = List.of(
            "summary", "description", "status", "assignee", "reporter", "priority",
            "labels", "components", "issuetype", "project", "created", "updated", "issuelinks");

    private final String baseUrl;
    private final String basicAuthHeader;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new Jira API client.
     *
     * @param baseUrl    the Jira Cloud site base URL, e.g. {@code https://yourcompany.atlassian.net}
     * @param email      the Atlassian account email used for Basic auth
     * @param apiToken   the Jira API token (the Basic auth password)
     * @param httpClient Java HttpClient instance for making requests
     */
    public JiraClient(String baseUrl, String email, String apiToken, HttpClient httpClient) {
        this(baseUrl, email, apiToken, httpClient, new ObjectMapper());
    }

    /**
     * Creates a new Jira API client with a custom ObjectMapper.
     */
    public JiraClient(String baseUrl, String email, String apiToken, HttpClient httpClient,
                       ObjectMapper objectMapper) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.basicAuthHeader = "Basic " + Base64.getEncoder().encodeToString(
                (email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return null;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Factory method that resolves Jira credentials from config or environment.
     *
     * <p>Resolution order per field:
     * <ol>
     *   <li>{@link JiraConfig#getBaseUrl()} / {@code JIRA_BASE_URL} env var</li>
     *   <li>{@link JiraConfig#getEmail()} / {@code JIRA_EMAIL} env var</li>
     *   <li>{@link JiraConfig#getApiToken()} / {@code JIRA_API_TOKEN} env var</li>
     * </ol>
     *
     * @param config the Jira configuration section
     * @return a new JiraClient instance
     * @throws IllegalStateException if any required credential is missing
     */
    public static JiraClient fromConfig(JiraConfig config) {
        String baseUrl = firstNonBlank(config.getBaseUrl(), System.getenv("JIRA_BASE_URL"));
        String email = firstNonBlank(config.getEmail(), System.getenv("JIRA_EMAIL"));
        String apiToken = firstNonBlank(config.getApiToken(), System.getenv("JIRA_API_TOKEN"));

        if (baseUrl == null || email == null || apiToken == null) {
            throw new IllegalStateException(
                    "Incomplete Jira configuration. Set jira.baseUrl, jira.email, and jira.apiToken "
                    + "in config, or JIRA_BASE_URL / JIRA_EMAIL / JIRA_API_TOKEN environment variables. "
                    + "Create an API token at https://id.atlassian.com/manage-profile/security/api-tokens");
        }

        return new JiraClient(baseUrl, email, apiToken, HttpClient.newHttpClient());
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    /**
     * Searches for issues matching the given JQL query, handling pagination
     * automatically via {@code nextPageToken}.
     *
     * @param jql        the JQL query string
     * @param maxResults overall cap on the number of issues returned
     * @return matching issues as JSON objects (each with {@code key}, {@code fields}, etc.)
     * @throws IOException          on HTTP errors
     * @throws InterruptedException if the thread is interrupted
     */
    public List<JsonNode> searchIssues(String jql, int maxResults) throws IOException, InterruptedException {
        List<JsonNode> allIssues = new ArrayList<>();
        String nextPageToken = null;

        do {
            int remaining = maxResults - allIssues.size();
            if (remaining <= 0) break;

            String body = buildSearchBody(jql, Math.min(PAGE_SIZE, remaining), nextPageToken);
            HttpRequest request = buildPostRequest(baseUrl + API_PATH + "/search/jql", body);
            JsonNode response = executeWithRetry(request, null);

            JsonNode issues = response.get("issues");
            if (issues != null && issues.isArray()) {
                for (JsonNode issue : issues) {
                    allIssues.add(issue);
                }
            }

            JsonNode tokenNode = response.get("nextPageToken");
            nextPageToken = tokenNode != null && !tokenNode.isNull() ? tokenNode.asText() : null;

        } while (nextPageToken != null);

        return allIssues;
    }

    /**
     * Fetches a single issue by key, including its {@code fields} (summary,
     * description, status, issuelinks, etc.).
     *
     * @param issueKey the Jira issue key, e.g. {@code PROJ-123}
     * @return the issue JSON object
     * @throws IOException              on HTTP errors (4xx/5xx)
     * @throws InterruptedException     if the thread is interrupted
     * @throws IllegalStateException    if auth is invalid (401)
     * @throws IllegalArgumentException if the issue is not found (404)
     */
    public JsonNode getIssue(String issueKey) throws IOException, InterruptedException {
        String fields = String.join(",", DEFAULT_FIELDS);
        String url = baseUrl + API_PATH + "/issue/" + issueKey + "?fields=" + fields;
        HttpRequest request = buildGetRequest(url);
        return executeWithRetry(request, issueKey);
    }

    /**
     * Fetches the full changelog (field transition history) for an issue,
     * handling pagination automatically.
     *
     * @param issueKey the Jira issue key
     * @return all changelog history entries, each with {@code created}, {@code author},
     *         and an {@code items} array of field transitions
     * @throws IOException          on HTTP errors
     * @throws InterruptedException if the thread is interrupted
     */
    public List<JsonNode> getChangelog(String issueKey) throws IOException, InterruptedException {
        List<JsonNode> allHistories = new ArrayList<>();
        int startAt = 0;

        while (true) {
            String url = baseUrl + API_PATH + "/issue/" + issueKey + "/changelog"
                    + "?startAt=" + startAt + "&maxResults=" + PAGE_SIZE;
            HttpRequest request = buildGetRequest(url);
            JsonNode response = executeWithRetry(request, issueKey);

            JsonNode histories = response.get("histories");
            int pageCount = 0;
            if (histories != null && histories.isArray()) {
                for (JsonNode history : histories) {
                    allHistories.add(history);
                }
                pageCount = histories.size();
            }

            int total = response.has("total") ? response.get("total").asInt(allHistories.size()) : allHistories.size();
            startAt += pageCount;
            if (pageCount == 0 || startAt >= total) {
                break;
            }
        }

        return allHistories;
    }

    /**
     * Fetches all comments for an issue, handling pagination automatically.
     *
     * @param issueKey the Jira issue key
     * @return all comments, each with {@code author}, {@code body} (ADF), {@code created}
     * @throws IOException          on HTTP errors
     * @throws InterruptedException if the thread is interrupted
     */
    public List<JsonNode> getComments(String issueKey) throws IOException, InterruptedException {
        List<JsonNode> allComments = new ArrayList<>();
        int startAt = 0;

        while (true) {
            String url = baseUrl + API_PATH + "/issue/" + issueKey + "/comment"
                    + "?startAt=" + startAt + "&maxResults=" + PAGE_SIZE;
            HttpRequest request = buildGetRequest(url);
            JsonNode response = executeWithRetry(request, issueKey);

            JsonNode comments = response.get("comments");
            int pageCount = 0;
            if (comments != null && comments.isArray()) {
                for (JsonNode comment : comments) {
                    allComments.add(comment);
                }
                pageCount = comments.size();
            }

            int total = response.has("total") ? response.get("total").asInt(allComments.size()) : allComments.size();
            startAt += pageCount;
            if (pageCount == 0 || startAt >= total) {
                break;
            }
        }

        return allComments;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private HttpRequest buildGetRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", basicAuthHeader)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private HttpRequest buildPostRequest(String url, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", basicAuthHeader)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private String buildSearchBody(String jql, int maxResults, String nextPageToken) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jql", jql);
        body.put("maxResults", maxResults);
        ArrayNode fields = body.putArray("fields");
        for (String field : DEFAULT_FIELDS) {
            fields.add(field);
        }
        if (nextPageToken != null) {
            body.put("nextPageToken", nextPageToken);
        }
        return body.toString();
    }

    /**
     * Executes an HTTP request with one retry on 429, honoring {@code Retry-After}
     * if present (falls back to 1 second).
     */
    private JsonNode executeWithRetry(HttpRequest request, String contextId)
            throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 429) {
            long retryAfterMs = response.headers().firstValueAsLong("Retry-After").orElse(1) * 1000L;
            LOG.fine("Jira API rate limited (429), retrying after " + retryAfterMs + "ms");
            Thread.sleep(retryAfterMs);
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
        }

        return handleResponse(response, status, contextId);
    }

    private JsonNode handleResponse(HttpResponse<String> response, int status, String contextId)
            throws IOException {
        String body = response.body();

        if (status == 401) {
            throw new IllegalStateException("Invalid Jira credentials (email/API token)");
        }
        if (status == 404) {
            String id = contextId != null ? contextId : "unknown";
            throw new IllegalArgumentException("Jira issue not found: " + id);
        }
        if (status >= 400) {
            String excerpt = body != null && body.length() > ERROR_EXCERPT_LENGTH
                    ? body.substring(0, ERROR_EXCERPT_LENGTH)
                    : (body != null ? body : "");
            throw new IOException("Jira API error: " + status + " " + excerpt);
        }

        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IOException("Failed to parse Jira API response: " + e.getMessage(), e);
        }
    }
}
