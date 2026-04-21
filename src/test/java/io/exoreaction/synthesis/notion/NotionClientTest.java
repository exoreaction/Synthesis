package io.exoreaction.synthesis.notion;

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
 * Tests for {@link NotionClient} — Notion API client with rate limiting,
 * pagination, and error handling.
 *
 * <p>Uses a stub {@link HttpClient} implementation that delegates to a
 * configurable response handler, avoiding external dependencies on Mockito
 * or WireMock.
 */
class NotionClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // -----------------------------------------------------------------------
    // 1. getPage — happy path
    // -----------------------------------------------------------------------

    @Test
    void getPage_happyPath_returnsParsedJson() throws Exception {
        String pageJson = """
                {
                    "object": "page",
                    "id": "page-123",
                    "properties": {
                        "title": {
                            "title": [{"plain_text": "Test Page"}]
                        }
                    }
                }
                """;

        StubHttpClient stub = new StubHttpClient((request) ->
                new StubHttpResponse(200, pageJson));

        NotionClient client = new NotionClient("ntn_test_token", stub);
        JsonNode result = client.getPage("page-123");

        assertEquals("page", result.get("object").asText());
        assertEquals("page-123", result.get("id").asText());
    }

    // -----------------------------------------------------------------------
    // 2. getPage — 404 throws IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    void getPage_404_throwsIllegalArgumentException() {
        StubHttpClient stub = new StubHttpClient((request) ->
                new StubHttpResponse(404, "{\"object\":\"error\",\"message\":\"Not found\"}"));

        NotionClient client = new NotionClient("ntn_test_token", stub);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> client.getPage("nonexistent-id"));
        assertTrue(ex.getMessage().contains("Notion page not found: nonexistent-id"));
    }

    // -----------------------------------------------------------------------
    // 3. getPage — 401 throws IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    void getPage_401_throwsIllegalStateException() {
        StubHttpClient stub = new StubHttpClient((request) ->
                new StubHttpResponse(401, "{\"object\":\"error\",\"message\":\"Unauthorized\"}"));

        NotionClient client = new NotionClient("ntn_bad_token", stub);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.getPage("some-page"));
        assertTrue(ex.getMessage().contains("Invalid Notion token"));
    }

    // -----------------------------------------------------------------------
    // 4. getBlockChildren — pagination (two pages)
    // -----------------------------------------------------------------------

    @Test
    void getBlockChildren_pagination_concatenatesResults() throws Exception {
        String page1Json = """
                {
                    "results": [
                        {"type": "paragraph", "id": "block-1"},
                        {"type": "heading_1", "id": "block-2"}
                    ],
                    "has_more": true,
                    "next_cursor": "cursor-abc"
                }
                """;
        String page2Json = """
                {
                    "results": [
                        {"type": "paragraph", "id": "block-3"}
                    ],
                    "has_more": false,
                    "next_cursor": null
                }
                """;

        // First call returns page1, second call returns page2
        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(200, page1Json));
        responses.add(new StubHttpResponse(200, page2Json));

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));

        NotionClient client = new NotionClient("ntn_test_token", stub);
        List<JsonNode> blocks = client.getBlockChildren("parent-block-id");

        assertEquals(3, blocks.size());
        assertEquals("block-1", blocks.get(0).get("id").asText());
        assertEquals("block-2", blocks.get(1).get("id").asText());
        assertEquals("block-3", blocks.get(2).get("id").asText());
    }

    // -----------------------------------------------------------------------
    // 5. searchAllPages — pagination (two pages)
    // -----------------------------------------------------------------------

    @Test
    void searchAllPages_pagination_concatenatesResults() throws Exception {
        String page1Json = """
                {
                    "results": [
                        {"object": "page", "id": "page-A"},
                        {"object": "page", "id": "page-B"}
                    ],
                    "has_more": true,
                    "next_cursor": "cursor-xyz"
                }
                """;
        String page2Json = """
                {
                    "results": [
                        {"object": "page", "id": "page-C"}
                    ],
                    "has_more": false,
                    "next_cursor": null
                }
                """;

        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(200, page1Json));
        responses.add(new StubHttpResponse(200, page2Json));

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));

        NotionClient client = new NotionClient("ntn_test_token", stub);
        List<JsonNode> pages = client.searchAllPages();

        assertEquals(3, pages.size());
        assertEquals("page-A", pages.get(0).get("id").asText());
        assertEquals("page-B", pages.get(1).get("id").asText());
        assertEquals("page-C", pages.get(2).get("id").asText());
    }

    // -----------------------------------------------------------------------
    // 6. getChildPages — happy path (non-paginated)
    // -----------------------------------------------------------------------

    @Test
    void getChildPages_happyPath_returnsFirstPage() throws Exception {
        String responseJson = """
                {
                    "results": [
                        {"type": "child_page", "id": "child-1", "child_page": {"title": "Sub Page"}},
                        {"type": "paragraph", "id": "block-99"}
                    ],
                    "has_more": true,
                    "next_cursor": "cursor-ignored"
                }
                """;

        StubHttpClient stub = new StubHttpClient((request) ->
                new StubHttpResponse(200, responseJson));

        NotionClient client = new NotionClient("ntn_test_token", stub);
        List<JsonNode> children = client.getChildPages("parent-id");

        // Returns all blocks from first page (does NOT paginate)
        assertEquals(2, children.size());
        assertEquals("child-1", children.get(0).get("id").asText());
        assertEquals("block-99", children.get(1).get("id").asText());
    }

    // -----------------------------------------------------------------------
    // 7. 429 retry behavior
    // -----------------------------------------------------------------------

    @Test
    void getPage_429_retriesOnceAndSucceeds() throws Exception {
        String successJson = """
                {
                    "object": "page",
                    "id": "page-retry"
                }
                """;

        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(429, "{\"message\":\"Rate limited\"}"));
        responses.add(new StubHttpResponse(200, successJson));

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));

        NotionClient client = new NotionClient("ntn_test_token", stub);
        JsonNode result = client.getPage("page-retry");

        assertEquals("page-retry", result.get("id").asText());
    }

    @Test
    void getPage_429_thenStill429_throwsIOException() {
        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(429, "{\"message\":\"Rate limited\"}"));
        responses.add(new StubHttpResponse(429, "{\"message\":\"Still rate limited\"}"));

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));

        NotionClient client = new NotionClient("ntn_test_token", stub);

        IOException ex = assertThrows(IOException.class,
                () -> client.getPage("page-id"));
        assertTrue(ex.getMessage().contains("Notion API error: 429"));
    }

    // -----------------------------------------------------------------------
    // 8. Rate limiting: two rapid calls have >= 334ms gap
    // -----------------------------------------------------------------------

    @Test
    void rateLimiting_twoRapidCalls_enforceMinimumGap() throws Exception {
        String responseJson = """
                {
                    "object": "page",
                    "id": "page-rl"
                }
                """;

        // Track request timestamps
        List<Long> requestTimestamps = new ArrayList<>();

        StubHttpClient stub = new StubHttpClient((request) -> {
            requestTimestamps.add(System.currentTimeMillis());
            return new StubHttpResponse(200, responseJson);
        });

        NotionClient client = new NotionClient("ntn_test_token", stub);

        // Make two rapid calls
        client.getPage("page-1");
        client.getPage("page-2");

        assertEquals(2, requestTimestamps.size());
        long gap = requestTimestamps.get(1) - requestTimestamps.get(0);
        // Allow 30ms tolerance for timing imprecision
        assertTrue(gap >= 300,
                "Expected >= 300ms gap between requests (3 req/s throttle), got " + gap + "ms");
    }

    // -----------------------------------------------------------------------
    // 9. 500 server error throws IOException
    // -----------------------------------------------------------------------

    @Test
    void getPage_500_throwsIOException() {
        StubHttpClient stub = new StubHttpClient((request) ->
                new StubHttpResponse(500, "{\"object\":\"error\",\"message\":\"Internal server error\"}"));

        NotionClient client = new NotionClient("ntn_test_token", stub);

        IOException ex = assertThrows(IOException.class,
                () -> client.getPage("page-id"));
        assertTrue(ex.getMessage().contains("Notion API error: 500"));
    }

    // -----------------------------------------------------------------------
    // 10. fromConfig — missing token throws
    // -----------------------------------------------------------------------

    @Test
    void fromConfig_noToken_throwsIllegalStateException() {
        io.exoreaction.synthesis.config.SynthesisConfig.NotionConfig config =
                new io.exoreaction.synthesis.config.SynthesisConfig.NotionConfig();
        // token is null by default, and NOTION_TOKEN env var is not set in CI

        // Only assert if NOTION_TOKEN is not set in the environment
        if (System.getenv("NOTION_TOKEN") == null) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> NotionClient.fromConfig(config));
            assertTrue(ex.getMessage().contains("No Notion token configured"));
        }
    }

    // -----------------------------------------------------------------------
    // 11. Request headers include Authorization and Notion-Version
    // -----------------------------------------------------------------------

    @Test
    void requestHeaders_includeAuthAndVersion() throws Exception {
        String responseJson = "{\"object\":\"page\",\"id\":\"p1\"}";
        List<HttpRequest> capturedRequests = new ArrayList<>();

        StubHttpClient stub = new StubHttpClient((request) -> {
            capturedRequests.add(request);
            return new StubHttpResponse(200, responseJson);
        });

        NotionClient client = new NotionClient("ntn_my_secret", stub);
        client.getPage("p1");

        assertEquals(1, capturedRequests.size());
        HttpRequest captured = capturedRequests.get(0);

        Optional<String> auth = captured.headers().firstValue("Authorization");
        assertTrue(auth.isPresent());
        assertEquals("Bearer ntn_my_secret", auth.get());

        Optional<String> version = captured.headers().firstValue("Notion-Version");
        assertTrue(version.isPresent());
        assertEquals("2022-06-28", version.get());
    }

    // -----------------------------------------------------------------------
    // 12. searchAllPages sends POST request to /search
    // -----------------------------------------------------------------------

    @Test
    void searchAllPages_sendsPostToSearchEndpoint() throws Exception {
        String responseJson = """
                {
                    "results": [],
                    "has_more": false,
                    "next_cursor": null
                }
                """;
        List<HttpRequest> capturedRequests = new ArrayList<>();

        StubHttpClient stub = new StubHttpClient((request) -> {
            capturedRequests.add(request);
            return new StubHttpResponse(200, responseJson);
        });

        NotionClient client = new NotionClient("ntn_test_token", stub);
        List<JsonNode> pages = client.searchAllPages();

        assertEquals(0, pages.size());
        assertEquals(1, capturedRequests.size());
        assertEquals("POST", capturedRequests.get(0).method());
        assertTrue(capturedRequests.get(0).uri().toString().contains("/search"));
    }

    // =====================================================================
    // Stub infrastructure (no Mockito required)
    // =====================================================================

    /** Functional interface for defining HTTP response behavior. */
    @FunctionalInterface
    interface RequestHandler {
        StubHttpResponse handle(HttpRequest request) throws IOException;
    }

    /** Simple response record holding status code and body. */
    record StubHttpResponse(int statusCode, String body) {}

    /** Handler that returns responses sequentially from a list. */
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

    /**
     * Minimal {@link HttpClient} stub that delegates to a {@link RequestHandler}.
     *
     * <p>Only {@link #send(HttpRequest, HttpResponse.BodyHandler)} is implemented;
     * all other methods throw {@link UnsupportedOperationException}.
     */
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

        // -- Required abstract method implementations (unused in tests) --

        @Override
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }

        @Override
        public Optional<Duration> connectTimeout() { return Optional.empty(); }

        @Override
        public Redirect followRedirects() { return Redirect.NEVER; }

        @Override
        public Optional<ProxySelector> proxy() { return Optional.empty(); }

        @Override
        public SSLContext sslContext() { return null; }

        @Override
        public SSLParameters sslParameters() { return null; }

        @Override
        public Optional<Authenticator> authenticator() { return Optional.empty(); }

        @Override
        public Version version() { return Version.HTTP_2; }

        @Override
        public Optional<Executor> executor() { return Optional.empty(); }

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

    /** Minimal {@link HttpResponse} implementation that returns a String body. */
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
