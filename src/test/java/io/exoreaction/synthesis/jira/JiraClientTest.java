package io.exoreaction.synthesis.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JiraClient} — Jira Cloud REST API v3 client with pagination,
 * changelog/comment fetching, and error handling.
 *
 * <p>Uses a stub {@link HttpClient} implementation that delegates to a
 * configurable response handler, the same pattern as {@code NotionClientTest}
 * (no Mockito/WireMock dependency).
 */
class JiraClientTest {

    private static final String BASE_URL = "https://exo-test.atlassian.net";

    // -----------------------------------------------------------------------
    // 1. getIssue — happy path
    // -----------------------------------------------------------------------

    @Test
    void getIssue_happyPath_returnsParsedJson() throws Exception {
        String issueJson = """
                {
                    "id": "10002",
                    "key": "PROJ-123",
                    "fields": {
                        "summary": "Fix the thing",
                        "status": {"name": "In Progress"}
                    }
                }
                """;

        StubHttpClient stub = new StubHttpClient(request -> new StubHttpResponse(200, issueJson));

        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);
        JsonNode result = client.getIssue("PROJ-123");

        assertEquals("PROJ-123", result.get("key").asText());
        assertEquals("Fix the thing", result.get("fields").get("summary").asText());
    }

    // -----------------------------------------------------------------------
    // 2. getIssue — 404 throws IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    void getIssue_404_throwsIllegalArgumentException() {
        StubHttpClient stub = new StubHttpClient(request ->
                new StubHttpResponse(404, "{\"errorMessages\":[\"Issue does not exist\"]}"));

        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> client.getIssue("PROJ-999"));
        assertTrue(ex.getMessage().contains("Jira issue not found: PROJ-999"));
    }

    // -----------------------------------------------------------------------
    // 3. getIssue — 401 throws IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    void getIssue_401_throwsIllegalStateException() {
        StubHttpClient stub = new StubHttpClient(request ->
                new StubHttpResponse(401, "{\"errorMessages\":[\"Unauthorized\"]}"));

        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "badtok", stub);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.getIssue("PROJ-1"));
        assertTrue(ex.getMessage().contains("Invalid Jira credentials"));
    }

    // -----------------------------------------------------------------------
    // 4. searchIssues — pagination via nextPageToken
    // -----------------------------------------------------------------------

    @Test
    void searchIssues_pagination_concatenatesResults() throws Exception {
        String page1Json = """
                {
                    "issues": [
                        {"key": "PROJ-1", "fields": {"summary": "A"}},
                        {"key": "PROJ-2", "fields": {"summary": "B"}}
                    ],
                    "nextPageToken": "token-abc"
                }
                """;
        String page2Json = """
                {
                    "issues": [
                        {"key": "PROJ-3", "fields": {"summary": "C"}}
                    ]
                }
                """;

        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(200, page1Json));
        responses.add(new StubHttpResponse(200, page2Json));

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));

        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);
        List<JsonNode> issues = client.searchIssues("project = PROJ", 100);

        assertEquals(3, issues.size());
        assertEquals("PROJ-1", issues.get(0).get("key").asText());
        assertEquals("PROJ-2", issues.get(1).get("key").asText());
        assertEquals("PROJ-3", issues.get(2).get("key").asText());
    }

    // -----------------------------------------------------------------------
    // 5. searchIssues — respects maxResults cap across pages
    // -----------------------------------------------------------------------

    @Test
    void searchIssues_capsAtMaxResults() throws Exception {
        String page1Json = """
                {
                    "issues": [
                        {"key": "PROJ-1", "fields": {"summary": "A"}},
                        {"key": "PROJ-2", "fields": {"summary": "B"}}
                    ],
                    "nextPageToken": "token-abc"
                }
                """;

        StubHttpClient stub = new StubHttpClient(request -> new StubHttpResponse(200, page1Json));

        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);
        List<JsonNode> issues = client.searchIssues("project = PROJ", 2);

        assertEquals(2, issues.size());
    }

    // -----------------------------------------------------------------------
    // 6. searchIssues sends POST to /search/jql with jql in body
    // -----------------------------------------------------------------------

    @Test
    void searchIssues_sendsPostToSearchJqlEndpoint() throws Exception {
        String responseJson = "{\"issues\": []}";
        List<HttpRequest> capturedRequests = new ArrayList<>();
        List<String> capturedBodies = new ArrayList<>();

        StubHttpClient stub = new StubHttpClient(request -> {
            capturedRequests.add(request);
            capturedBodies.add(bodyOf(request));
            return new StubHttpResponse(200, responseJson);
        });

        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);
        client.searchIssues("project = PROJ ORDER BY updated", 50);

        assertEquals(1, capturedRequests.size());
        assertEquals("POST", capturedRequests.get(0).method());
        assertTrue(capturedRequests.get(0).uri().toString().endsWith("/search/jql"));
        assertTrue(capturedBodies.get(0).contains("project = PROJ ORDER BY updated"));
    }

    // -----------------------------------------------------------------------
    // 7. getChangelog — pagination across multiple pages
    // -----------------------------------------------------------------------

    @Test
    void getChangelog_pagination_concatenatesHistories() throws Exception {
        String page1Json = """
                {
                    "startAt": 0,
                    "maxResults": 2,
                    "total": 3,
                    "histories": [
                        {"id": "1", "created": "2026-01-01T00:00:00.000+0000",
                         "author": {"displayName": "Alice"},
                         "items": [{"field": "status", "fromString": "To Do", "toString": "In Progress"}]},
                        {"id": "2", "created": "2026-01-02T00:00:00.000+0000",
                         "author": {"displayName": "Bob"},
                         "items": [{"field": "assignee", "fromString": null, "toString": "Bob"}]}
                    ]
                }
                """;
        String page2Json = """
                {
                    "startAt": 2,
                    "maxResults": 2,
                    "total": 3,
                    "histories": [
                        {"id": "3", "created": "2026-01-03T00:00:00.000+0000",
                         "author": {"displayName": "Alice"},
                         "items": [{"field": "status", "fromString": "In Progress", "toString": "Done"}]}
                    ]
                }
                """;

        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(200, page1Json));
        responses.add(new StubHttpResponse(200, page2Json));

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));

        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);
        List<JsonNode> histories = client.getChangelog("PROJ-1");

        assertEquals(3, histories.size());
        assertEquals("Alice", histories.get(0).get("author").get("displayName").asText());
        assertEquals("Done", histories.get(2).get("items").get(0).get("toString").asText());
    }

    // -----------------------------------------------------------------------
    // 8. getComments — pagination across multiple pages
    // -----------------------------------------------------------------------

    @Test
    void getComments_pagination_concatenatesComments() throws Exception {
        String page1Json = """
                {
                    "startAt": 0,
                    "maxResults": 1,
                    "total": 2,
                    "comments": [
                        {"id": "100", "author": {"displayName": "Carol"},
                         "created": "2026-02-01T00:00:00.000+0000",
                         "body": {"type": "doc", "version": 1, "content": [
                             {"type": "paragraph", "content": [{"type": "text", "text": "First comment"}]}
                         ]}}
                    ]
                }
                """;
        String page2Json = """
                {
                    "startAt": 1,
                    "maxResults": 1,
                    "total": 2,
                    "comments": [
                        {"id": "101", "author": {"displayName": "Dave"},
                         "created": "2026-02-02T00:00:00.000+0000",
                         "body": {"type": "doc", "version": 1, "content": [
                             {"type": "paragraph", "content": [{"type": "text", "text": "Second comment"}]}
                         ]}}
                    ]
                }
                """;

        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(200, page1Json));
        responses.add(new StubHttpResponse(200, page2Json));

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));

        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);
        List<JsonNode> comments = client.getComments("PROJ-1");

        assertEquals(2, comments.size());
        assertEquals("Carol", comments.get(0).get("author").get("displayName").asText());
        assertEquals("Dave", comments.get(1).get("author").get("displayName").asText());
    }

    // -----------------------------------------------------------------------
    // 9. 429 retry behavior
    // -----------------------------------------------------------------------

    @Test
    void getIssue_429_retriesAndSucceeds() throws Exception {
        String successJson = "{\"key\": \"PROJ-1\", \"fields\": {\"summary\": \"ok\"}}";

        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(429, "{\"errorMessages\":[\"Rate limited\"]}"));
        responses.add(new StubHttpResponse(200, successJson));

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));

        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);
        JsonNode result = client.getIssue("PROJ-1");

        assertEquals("PROJ-1", result.get("key").asText());
    }

    @Test
    void getIssue_429_thenStill429_throwsIOException() {
        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(429, "{\"errorMessages\":[\"Rate limited\"]}"));
        responses.add(new StubHttpResponse(429, "{\"errorMessages\":[\"Still rate limited\"]}"));

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));

        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);

        IOException ex = assertThrows(IOException.class, () -> client.getIssue("PROJ-1"));
        assertTrue(ex.getMessage().contains("Jira API error: 429"));
    }

    // -----------------------------------------------------------------------
    // 10. 500 server error throws IOException
    // -----------------------------------------------------------------------

    @Test
    void getIssue_500_throwsIOException() {
        StubHttpClient stub = new StubHttpClient(request ->
                new StubHttpResponse(500, "{\"errorMessages\":[\"Internal error\"]}"));

        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);

        IOException ex = assertThrows(IOException.class, () -> client.getIssue("PROJ-1"));
        assertTrue(ex.getMessage().contains("Jira API error: 500"));
    }

    // -----------------------------------------------------------------------
    // 11. Request headers include Basic auth
    // -----------------------------------------------------------------------

    @Test
    void requestHeaders_includeBasicAuth() throws Exception {
        String responseJson = "{\"key\": \"PROJ-1\", \"fields\": {}}";
        List<HttpRequest> capturedRequests = new ArrayList<>();

        StubHttpClient stub = new StubHttpClient(request -> {
            capturedRequests.add(request);
            return new StubHttpResponse(200, responseJson);
        });

        JiraClient client = new JiraClient(BASE_URL, "me@example.com", "secret-token", stub);
        client.getIssue("PROJ-1");

        assertEquals(1, capturedRequests.size());
        Optional<String> auth = capturedRequests.get(0).headers().firstValue("Authorization");
        assertTrue(auth.isPresent());
        assertTrue(auth.get().startsWith("Basic "));

        String expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("me@example.com:secret-token".getBytes());
        assertEquals(expected, auth.get());
    }

    // -----------------------------------------------------------------------
    // 12. fromConfig — missing credentials throws
    // -----------------------------------------------------------------------

    @Test
    void fromConfig_missingCredentials_throwsIllegalStateException() {
        var config = new io.exoreaction.synthesis.config.SynthesisConfig.JiraConfig();

        if (System.getenv("JIRA_BASE_URL") == null
                && System.getenv("JIRA_EMAIL") == null
                && System.getenv("JIRA_API_TOKEN") == null) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> JiraClient.fromConfig(config));
            assertTrue(ex.getMessage().contains("jira.baseUrl"));
            assertTrue(ex.getMessage().contains("JIRA_BASE_URL"));
        }
    }

    @Test
    void fromConfig_withAllConfigValues_succeeds() {
        var config = new io.exoreaction.synthesis.config.SynthesisConfig.JiraConfig();
        config.setBaseUrl(BASE_URL);
        config.setEmail("me@example.com");
        config.setApiToken("tok");

        JiraClient client = JiraClient.fromConfig(config);
        assertNotNull(client);
    }

    // =====================================================================
    // Stub infrastructure (mirrors NotionClientTest, no Mockito required)
    // =====================================================================

    private static String bodyOf(HttpRequest request) {
        var publisher = request.bodyPublisher();
        if (publisher.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        publisher.get().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(java.nio.ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                sb.append(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            }
            @Override public void onError(Throwable throwable) { }
            @Override public void onComplete() { }
        });
        return sb.toString();
    }

    @FunctionalInterface
    interface RequestHandler {
        StubHttpResponse handle(HttpRequest request) throws IOException;
    }

    record StubHttpResponse(int statusCode, String body) {}

    static class SequentialHandler implements RequestHandler {
        private final List<StubHttpResponse> responses;
        private int index = 0;

        SequentialHandler(List<StubHttpResponse> responses) {
            this.responses = responses;
        }

        @Override
        public StubHttpResponse handle(HttpRequest request) {
            if (index < responses.size()) {
                return responses.get(index++);
            }
            return new StubHttpResponse(500, "{\"error\":\"No more stub responses\"}");
        }
    }

    static class StubHttpClient extends HttpClient {
        private final RequestHandler handler;

        StubHttpClient(RequestHandler handler) {
            this.handler = handler;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
                throws IOException, InterruptedException {
            StubHttpResponse stubResponse = handler.handle(request);
            return (HttpResponse<T>) new StubStringResponse(request, stubResponse.statusCode(), stubResponse.body());
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return null; }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_2; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> bodyHandler) {
            throw new UnsupportedOperationException("sendAsync not implemented in stub");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> bodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("sendAsync not implemented in stub");
        }
    }

    static class StubStringResponse implements HttpResponse<String> {
        private final HttpRequest request;
        private final int statusCode;
        private final String body;

        StubStringResponse(HttpRequest request, int statusCode, String body) {
            this.request = request;
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override public int statusCode() { return statusCode; }
        @Override public HttpRequest request() { return request; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
        @Override public String body() { return body; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public java.net.URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
    }
}
