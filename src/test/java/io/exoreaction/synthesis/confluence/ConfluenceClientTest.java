package io.exoreaction.synthesis.confluence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.exoreaction.synthesis.config.SynthesisConfig.ConfluenceConfig;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfluenceClient} — Confluence Cloud REST API v2 client with
 * Basic auth, cursor pagination, and 429/4xx/5xx error handling.
 *
 * <p>Uses the same stub {@link HttpClient} pattern as {@code NotionClientTest}
 * (a configurable response handler, no Mockito/WireMock dependency).
 */
class ConfluenceClientTest {

    private static final String BASE_URL = "https://example.atlassian.net/wiki";

    // -----------------------------------------------------------------------
    // 1. getPage — happy path
    // -----------------------------------------------------------------------

    @Test
    void getPage_happyPath_returnsParsedJson() throws Exception {
        String pageJson = """
                {
                    "id": "131073",
                    "title": "Architecture",
                    "spaceId": "98765",
                    "body": {"storage": {"representation": "storage", "value": "<p>Hi</p>"}}
                }
                """;

        StubHttpClient stub = new StubHttpClient((request) -> new StubHttpResponse(200, pageJson));

        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);
        JsonNode result = client.getPage("131073", "storage");

        assertEquals("131073", result.get("id").asText());
        assertEquals("Architecture", result.get("title").asText());
    }

    // -----------------------------------------------------------------------
    // 2. getPage — 404 throws IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    void getPage_404_throwsIllegalArgumentException() {
        StubHttpClient stub = new StubHttpClient((request) ->
                new StubHttpResponse(404, "{\"message\":\"Not found\"}"));

        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> client.getPage("nonexistent-id", "storage"));
        assertTrue(ex.getMessage().contains("nonexistent-id"));
    }

    // -----------------------------------------------------------------------
    // 3. getPage — 401 throws IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    void getPage_401_throwsIllegalStateException() {
        StubHttpClient stub = new StubHttpClient((request) ->
                new StubHttpResponse(401, "{\"message\":\"Unauthorized\"}"));

        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "bad-tok", stub);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.getPage("some-page", "storage"));
        assertTrue(ex.getMessage().contains("Invalid Confluence credentials"));
    }

    // -----------------------------------------------------------------------
    // 4. getPagesForSpace — pagination (two pages via _links.next)
    // -----------------------------------------------------------------------

    @Test
    void getPagesForSpace_pagination_concatenatesResults() throws Exception {
        String page1Json = """
                {
                    "results": [
                        {"id": "1", "title": "Page One"},
                        {"id": "2", "title": "Page Two"}
                    ],
                    "_links": {"next": "/wiki/api/v2/pages?cursor=abc&space-id=98765"}
                }
                """;
        String page2Json = """
                {
                    "results": [
                        {"id": "3", "title": "Page Three"}
                    ],
                    "_links": {}
                }
                """;

        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(200, page1Json));
        responses.add(new StubHttpResponse(200, page2Json));

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));

        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);
        List<JsonNode> pages = client.getPagesForSpace("98765", "storage");

        assertEquals(3, pages.size());
        assertEquals("1", pages.get(0).get("id").asText());
        assertEquals("2", pages.get(1).get("id").asText());
        assertEquals("3", pages.get(2).get("id").asText());
    }

    // -----------------------------------------------------------------------
    // 5. getPagesForSpace — resolves relative next URL against site origin
    // -----------------------------------------------------------------------

    @Test
    void getPagesForSpace_resolvesNextUrlAgainstSiteOrigin() throws Exception {
        String page1Json = """
                {
                    "results": [{"id": "1", "title": "Page One"}],
                    "_links": {"next": "/wiki/api/v2/pages?cursor=abc"}
                }
                """;
        String page2Json = """
                {"results": [], "_links": {}}
                """;

        List<HttpRequest> capturedRequests = new ArrayList<>();
        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(200, page1Json));
        responses.add(new StubHttpResponse(200, page2Json));
        SequentialHandler sequential = new SequentialHandler(responses);

        StubHttpClient stub = new StubHttpClient((request) -> {
            capturedRequests.add(request);
            return sequential.handle(request);
        });

        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);
        client.getPagesForSpace("98765", "storage");

        assertEquals(2, capturedRequests.size());
        assertTrue(capturedRequests.get(1).uri().toString().startsWith("https://example.atlassian.net/wiki/api/v2/pages?cursor=abc"),
                "Second request should resolve the relative next URL against the site origin, got: "
                        + capturedRequests.get(1).uri());
    }

    // -----------------------------------------------------------------------
    // 6. getSpaceByKey — happy path
    // -----------------------------------------------------------------------

    @Test
    void getSpaceByKey_happyPath_returnsFirstResult() throws Exception {
        String responseJson = """
                {
                    "results": [
                        {"id": "98765", "key": "ENG", "name": "Engineering"}
                    ],
                    "_links": {}
                }
                """;

        StubHttpClient stub = new StubHttpClient((request) -> new StubHttpResponse(200, responseJson));

        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);
        JsonNode space = client.getSpaceByKey("ENG");

        assertEquals("98765", space.get("id").asText());
        assertEquals("ENG", space.get("key").asText());
    }

    // -----------------------------------------------------------------------
    // 7. getSpaceByKey — no results throws IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    void getSpaceByKey_noResults_throwsIllegalArgumentException() {
        String responseJson = """
                {"results": [], "_links": {}}
                """;

        StubHttpClient stub = new StubHttpClient((request) -> new StubHttpResponse(200, responseJson));

        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> client.getSpaceByKey("MISSING"));
        assertTrue(ex.getMessage().contains("MISSING"));
    }

    // -----------------------------------------------------------------------
    // 8. 429 retry behavior — honors Retry-After header
    // -----------------------------------------------------------------------

    @Test
    void getPage_429_retriesOnceAndSucceeds() throws Exception {
        String successJson = "{\"id\": \"page-retry\"}";

        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(429, "{\"message\":\"Rate limited\"}",
                Map.of("Retry-After", "0")));
        responses.add(new StubHttpResponse(200, successJson));

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));

        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);
        JsonNode result = client.getPage("page-retry", "storage");

        assertEquals("page-retry", result.get("id").asText());
    }

    @Test
    void getPage_429_thenStill429_throwsIOException() {
        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(429, "{\"message\":\"Rate limited\"}", Map.of("Retry-After", "0")));
        responses.add(new StubHttpResponse(429, "{\"message\":\"Still rate limited\"}", Map.of()));

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));

        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);

        IOException ex = assertThrows(IOException.class, () -> client.getPage("page-id", "storage"));
        assertTrue(ex.getMessage().contains("Confluence API error: 429"));
    }

    // -----------------------------------------------------------------------
    // 9. 500 server error throws IOException
    // -----------------------------------------------------------------------

    @Test
    void getPage_500_throwsIOException() {
        StubHttpClient stub = new StubHttpClient((request) ->
                new StubHttpResponse(500, "{\"message\":\"Internal server error\"}"));

        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);

        IOException ex = assertThrows(IOException.class, () -> client.getPage("page-id", "storage"));
        assertTrue(ex.getMessage().contains("Confluence API error: 500"));
    }

    // -----------------------------------------------------------------------
    // 10. fromConfig — missing credentials throws IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    void fromConfig_noCredentials_throwsIllegalStateException() {
        ConfluenceConfig config = new ConfluenceConfig();
        // baseUrl/email/apiToken are null by default

        if (System.getenv("CONFLUENCE_BASE_URL") == null && System.getenv("CONFLUENCE_EMAIL") == null
                && System.getenv("CONFLUENCE_API_TOKEN") == null) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> ConfluenceClient.fromConfig(config));
            assertTrue(ex.getMessage().contains("Incomplete Confluence credentials"));
        }
    }

    // -----------------------------------------------------------------------
    // 11. Request headers include Basic auth and Accept
    // -----------------------------------------------------------------------

    @Test
    void requestHeaders_includeBasicAuthAndAccept() throws Exception {
        String responseJson = "{\"id\": \"p1\"}";
        List<HttpRequest> capturedRequests = new ArrayList<>();

        StubHttpClient stub = new StubHttpClient((request) -> {
            capturedRequests.add(request);
            return new StubHttpResponse(200, responseJson);
        });

        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "my-secret-token", stub);
        client.getPage("p1", "storage");

        assertEquals(1, capturedRequests.size());
        HttpRequest captured = capturedRequests.get(0);

        Optional<String> auth = captured.headers().firstValue("Authorization");
        assertTrue(auth.isPresent());
        String expected = "Basic " + Base64.getEncoder().encodeToString("a@b.com:my-secret-token".getBytes());
        assertEquals(expected, auth.get());

        Optional<String> accept = captured.headers().firstValue("Accept");
        assertTrue(accept.isPresent());
        assertEquals("application/json", accept.get());

        assertTrue(captured.uri().toString().contains("body-format=storage"));
    }

    // -----------------------------------------------------------------------
    // 12. getLabelsForPage — returns label names
    // -----------------------------------------------------------------------

    @Test
    void getLabelsForPage_returnsLabelNames() throws Exception {
        String responseJson = """
                {
                    "results": [
                        {"id": "l1", "name": "architecture", "prefix": "global"},
                        {"id": "l2", "name": "draft", "prefix": "global"}
                    ],
                    "_links": {}
                }
                """;

        StubHttpClient stub = new StubHttpClient((request) -> new StubHttpResponse(200, responseJson));

        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);
        List<String> labels = client.getLabelsForPage("131073");

        assertEquals(List.of("architecture", "draft"), labels);
    }

    // =====================================================================
    // Stub infrastructure (no Mockito required)
    // =====================================================================

    @FunctionalInterface
    interface RequestHandler {
        StubHttpResponse handle(HttpRequest request) throws IOException;
    }

    record StubHttpResponse(int statusCode, String body, Map<String, String> headers) {
        StubHttpResponse(int statusCode, String body) {
            this(statusCode, body, Map.of());
        }
    }

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
            return (HttpResponse<T>) new StubStringResponse(request, stubResponse.statusCode(),
                    stubResponse.body(), stubResponse.headers());
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
        private final Map<String, String> headerMap;

        StubStringResponse(HttpRequest request, int statusCode, String body, Map<String, String> headerMap) {
            this.request = request;
            this.statusCode = statusCode;
            this.body = body;
            this.headerMap = headerMap;
        }

        @Override public int statusCode() { return statusCode; }
        @Override public HttpRequest request() { return request; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }

        @Override
        public HttpHeaders headers() {
            Map<String, List<String>> multi = new java.util.HashMap<>();
            for (var entry : headerMap.entrySet()) {
                multi.put(entry.getKey(), List.of(entry.getValue()));
            }
            return HttpHeaders.of(multi, (a, b) -> true);
        }

        @Override public String body() { return body; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public java.net.URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
    }
}
