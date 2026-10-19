package io.exoreaction.synthesis.confluence;

import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfluenceWorkspaceSource} — the orchestrator for Confluence
 * workspace syncs.
 *
 * <p>Uses a stub {@link HttpClient} to avoid real Confluence API calls, and an
 * in-memory SQLite database for state persistence (mirrors {@code NotionWorkspaceSourceTest}).
 */
class ConfluenceWorkspaceSourceTest {

    private static final String BASE_URL = "https://example.atlassian.net/wiki";

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private ConfluenceSyncState syncState;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        syncState = new ConfluenceSyncState(db);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null && !db.isClosed()) db.close();
    }

    // -----------------------------------------------------------------------
    // 1. sync_singleSpace_fetchesAllPagesWithInlineBody
    // -----------------------------------------------------------------------

    @Test
    void sync_singleSpace_fetchesAllPagesWithInlineBody() throws Exception {
        RoutingHandler routing = new RoutingHandler();
        routing.addRoute("/spaces", makeSpaceResponse("ENG", "98765"));
        routing.addRoute("/pages", makePagesResponse(List.of(
                makePage("1", "Root Page", null, "<p>Root content</p>"),
                makePage("2", "Child Page", "1", "<p>Child content</p>")
        )));

        StubHttpClient stub = new StubHttpClient(routing);
        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace", List.of("ENG"));
        ConfluenceWorkspaceSource source = new ConfluenceWorkspaceSource(
                client, new ConfluencePageMapper(), syncState, new ConfluenceContentToMarkdown(), config);

        List<ConfluencePageMapper.ConfluencePage> pages = source.sync();

        assertEquals(2, pages.size());
        Set<String> ids = new HashSet<>();
        for (var p : pages) ids.add(p.id());
        assertTrue(ids.contains("1"));
        assertTrue(ids.contains("2"));

        // No separate "fetch blocks" call needed — body comes inline with the listing call.
        for (var p : pages) {
            assertTrue(p.markdownContent().contains("content"),
                    "Markdown content should be populated from the inline body: " + p.markdownContent());
        }
    }

    // -----------------------------------------------------------------------
    // 2. sync_virtualPath_isPrefixedWithSpaceKey
    // -----------------------------------------------------------------------

    @Test
    void sync_virtualPath_isPrefixedWithSpaceKey() throws Exception {
        RoutingHandler routing = new RoutingHandler();
        routing.addRoute("/spaces", makeSpaceResponse("ENG", "98765"));
        routing.addRoute("/pages", makePagesResponse(List.of(
                makePage("1", "Architecture", null, "<p>content</p>")
        )));

        StubHttpClient stub = new StubHttpClient(routing);
        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace", List.of("ENG"));
        ConfluenceWorkspaceSource source = new ConfluenceWorkspaceSource(
                client, new ConfluencePageMapper(), syncState, new ConfluenceContentToMarkdown(), config);

        List<ConfluencePageMapper.ConfluencePage> pages = source.sync();

        assertEquals(1, pages.size());
        assertEquals("ENG/Architecture.md", pages.get(0).virtualPath());
    }

    // -----------------------------------------------------------------------
    // 3. sync_spaceFetchFailure_continuesWithOtherSpaces
    // -----------------------------------------------------------------------

    @Test
    void sync_spaceFetchFailure_continuesWithOtherSpaces() throws Exception {
        RequestHandler handler = (request) -> {
            String uri = request.uri().toString();
            if (uri.contains("/spaces") && uri.contains("keys=MISSING")) {
                return new StubHttpResponse(404, "{\"message\":\"not found\"}");
            }
            if (uri.contains("/spaces")) {
                return new StubHttpResponse(200, makeSpaceResponse("ENG", "98765"));
            }
            return new StubHttpResponse(200, makePagesResponse(List.of(
                    makePage("1", "Only Page", null, "<p>content</p>"))));
        };

        StubHttpClient stub = new StubHttpClient(handler);
        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace", List.of("MISSING", "ENG"));
        ConfluenceWorkspaceSource source = new ConfluenceWorkspaceSource(
                client, new ConfluencePageMapper(), syncState, new ConfluenceContentToMarkdown(), config);

        List<ConfluencePageMapper.ConfluencePage> pages = source.sync();

        assertEquals(1, pages.size());
        assertEquals("1", pages.get(0).id());
    }

    // -----------------------------------------------------------------------
    // 4. incrementalSync_noLastSync_delegatesToFullSync
    // -----------------------------------------------------------------------

    @Test
    void incrementalSync_noLastSync_delegatesToFullSync() throws Exception {
        RoutingHandler routing = new RoutingHandler();
        routing.addRoute("/spaces", makeSpaceResponse("ENG", "98765"));
        routing.addRoute("/pages", makePagesResponse(List.of(
                makePage("1", "Test", null, "<p>content</p>")
        )));

        StubHttpClient stub = new StubHttpClient(routing);
        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace", List.of("ENG"));
        ConfluenceWorkspaceSource source = new ConfluenceWorkspaceSource(
                client, new ConfluencePageMapper(), syncState, new ConfluenceContentToMarkdown(), config);

        List<ConfluencePageMapper.ConfluencePage> pages = source.incrementalSync();

        assertEquals(1, pages.size());
        assertEquals("1", pages.get(0).id());
    }

    // -----------------------------------------------------------------------
    // 5. incrementalSync_withLastSync_filtersOldPages
    // -----------------------------------------------------------------------

    @Test
    void incrementalSync_withLastSync_filtersOldPages() throws Exception {
        Instant lastSync = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        syncState.upsertSyncState("test-workspace", "ENG", lastSync, 5, "ok", null);

        Instant recentEdit = Instant.now().minus(30, ChronoUnit.MINUTES);
        Instant oldEdit = Instant.now().minus(2, ChronoUnit.HOURS);

        RoutingHandler routing = new RoutingHandler();
        routing.addRoute("/spaces", makeSpaceResponse("ENG", "98765"));
        routing.addRoute("/pages", makePagesResponse(List.of(
                makePageWithVersionTime("recent-page", "Recent", null, "<p>r</p>", recentEdit),
                makePageWithVersionTime("old-page", "Old", null, "<p>o</p>", oldEdit)
        )));

        StubHttpClient stub = new StubHttpClient(routing);
        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace", List.of("ENG"));
        ConfluenceWorkspaceSource source = new ConfluenceWorkspaceSource(
                client, new ConfluencePageMapper(), syncState, new ConfluenceContentToMarkdown(), config);

        List<ConfluencePageMapper.ConfluencePage> pages = source.incrementalSync();

        assertEquals(1, pages.size());
        assertEquals("recent-page", pages.get(0).id());
    }

    // -----------------------------------------------------------------------
    // 6. sync_updatesSyncState
    // -----------------------------------------------------------------------

    @Test
    void sync_updatesSyncState() throws Exception {
        RoutingHandler routing = new RoutingHandler();
        routing.addRoute("/spaces", makeSpaceResponse("ENG", "98765"));
        routing.addRoute("/pages", makePagesResponse(List.of(
                makePage("p1", "Page One", null, "<p>one</p>"),
                makePage("p2", "Page Two", null, "<p>two</p>")
        )));

        StubHttpClient stub = new StubHttpClient(routing);
        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace", List.of("ENG"));
        ConfluenceWorkspaceSource source = new ConfluenceWorkspaceSource(
                client, new ConfluencePageMapper(), syncState, new ConfluenceContentToMarkdown(), config);

        source.sync();

        Optional<Instant> syncTime = syncState.getLastSyncTime("test-workspace");
        assertTrue(syncTime.isPresent(), "Sync state should be recorded after sync");

        List<String> orphans = syncState.getOrphanPageIds("test-workspace", Set.of("p1", "p2"));
        assertTrue(orphans.isEmpty(), "Synced pages should exist in the database");
    }

    // -----------------------------------------------------------------------
    // 7. sync_collisionResolution
    // -----------------------------------------------------------------------

    @Test
    void sync_collisionResolution() throws Exception {
        RoutingHandler routing = new RoutingHandler();
        routing.addRoute("/spaces", makeSpaceResponse("ENG", "98765"));
        routing.addRoute("/pages", makePagesResponse(List.of(
                makePage("page-aaaa1111", "Meeting Notes", null, "<p>a</p>"),
                makePage("page-bbbb2222", "Meeting Notes", null, "<p>b</p>")
        )));

        StubHttpClient stub = new StubHttpClient(routing);
        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace", List.of("ENG"));
        ConfluenceWorkspaceSource source = new ConfluenceWorkspaceSource(
                client, new ConfluencePageMapper(), syncState, new ConfluenceContentToMarkdown(), config);

        List<ConfluencePageMapper.ConfluencePage> pages = source.sync();

        assertEquals(2, pages.size());
        Set<String> paths = new HashSet<>();
        for (var p : pages) paths.add(p.virtualPath());
        assertEquals(2, paths.size(), "Collision resolution should produce unique paths");
        for (String path : paths) {
            assertTrue(path.contains("Meeting Notes"), "Path should contain the title: " + path);
        }
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    private String makePage(String id, String title, String parentId, String bodyHtml) {
        return makePageWithVersionTime(id, title, parentId, bodyHtml, Instant.now());
    }

    private String makePageWithVersionTime(String id, String title, String parentId,
                                            String bodyHtml, Instant versionCreatedAt) {
        String parentField = parentId != null ? "\"parentId\": \"" + parentId + "\"," : "";
        String escapedBody = bodyHtml.replace("\"", "\\\"");
        return """
                {
                    "id": "%s",
                    "title": "%s",
                    %s
                    "createdAt": "%s",
                    "version": {"number": 1, "createdAt": "%s"},
                    "body": {"storage": {"representation": "storage", "value": "%s"}}
                }
                """.formatted(id, title, parentField,
                Instant.now().minus(1, ChronoUnit.DAYS), versionCreatedAt, escapedBody);
    }

    private String makePagesResponse(List<String> pageJsons) {
        return """
                {
                    "results": [%s],
                    "_links": {}
                }
                """.formatted(String.join(",", pageJsons));
    }

    private String makeSpaceResponse(String key, String id) {
        return """
                {
                    "results": [{"id": "%s", "key": "%s", "name": "%s"}],
                    "_links": {}
                }
                """.formatted(id, key, key);
    }

    private SynthesisConfig makeConfig(String workspaceName, List<String> spaceKeys) {
        SynthesisConfig config = new SynthesisConfig();
        config.getWorkspace().setName(workspaceName);
        config.getConfluence().setEnabled(true);
        config.getConfluence().setBaseUrl(BASE_URL);
        config.getConfluence().setEmail("a@b.com");
        config.getConfluence().setApiToken("tok");
        config.getConfluence().setSpaceKeys(spaceKeys);
        config.getConfluence().setMaxPagesPerSync(500);
        return config;
    }

    // =====================================================================
    // Stub infrastructure (same pattern as ConfluenceClientTest)
    // =====================================================================

    record StubHttpResponse(int statusCode, String body) {}

    @FunctionalInterface
    interface RequestHandler {
        StubHttpResponse handle(HttpRequest request);
    }

    /** Routes by substring match against the request URI (path + query). */
    static class RoutingHandler implements RequestHandler {
        private final Map<String, String> routes = new LinkedHashMap<>();

        void addRoute(String uriSubstring, String responseBody) {
            routes.put(uriSubstring, responseBody);
        }

        @Override
        public StubHttpResponse handle(HttpRequest request) {
            String uri = request.uri().toString();
            for (var entry : routes.entrySet()) {
                if (uri.contains(entry.getKey())) {
                    return new StubHttpResponse(200, entry.getValue());
                }
            }
            return new StubHttpResponse(200, "{\"results\":[],\"_links\":{}}");
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
            if (stubResponse.statusCode() >= 400) {
                return (HttpResponse<T>) new StubStringResponse(request, stubResponse.statusCode(), stubResponse.body());
            }
            return (HttpResponse<T>) new StubStringResponse(request, stubResponse.statusCode(), stubResponse.body());
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<java.time.Duration> connectTimeout() { return Optional.empty(); }
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
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> bodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
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
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public String body() { return body; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public java.net.URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
    }
}
