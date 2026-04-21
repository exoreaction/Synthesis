package io.exoreaction.synthesis.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Tests for {@link NotionWorkspaceSource} — the orchestrator for Notion workspace syncs.
 *
 * <p>Uses a stub {@link HttpClient} to avoid real Notion API calls, and an in-memory
 * SQLite database for state persistence.
 */
class NotionWorkspaceSourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private NotionSyncState syncState;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        syncState = new NotionSyncState(db);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null && !db.isClosed()) db.close();
    }

    // -----------------------------------------------------------------------
    // 1. sync_withRootPageId_fetchesChildrenRecursively
    // -----------------------------------------------------------------------

    @Test
    void sync_withRootPageId_fetchesChildrenRecursively() throws Exception {
        // Use a URL-routing stub to handle interleaved page/block requests
        String rootPageJson = makePage("root-id", "Root Page", null);
        String child1PageJson = makePage("child-1", "Sub Page 1", "root-id");
        String child2PageJson = makePage("child-2", "Sub Page 2", "root-id");

        // Children of root: two child_page blocks
        String rootChildrenJson = """
                {
                    "results": [
                        {"type": "child_page", "id": "child-1", "child_page": {"title": "Sub Page 1"}},
                        {"type": "child_page", "id": "child-2", "child_page": {"title": "Sub Page 2"}}
                    ],
                    "has_more": false,
                    "next_cursor": null
                }
                """;

        String emptyBlocksJson = """
                {"results": [], "has_more": false, "next_cursor": null}
                """;

        // Route by URL
        RoutingHandler routing = new RoutingHandler();
        routing.addRoute("/v1/pages/root-id", rootPageJson);
        routing.addRoute("/v1/pages/child-1", child1PageJson);
        routing.addRoute("/v1/pages/child-2", child2PageJson);
        routing.addRoute("/v1/blocks/root-id/children", rootChildrenJson);
        routing.addRoute("/v1/blocks/child-1/children", emptyBlocksJson);
        routing.addRoute("/v1/blocks/child-2/children", emptyBlocksJson);

        StubHttpClient stub = new StubHttpClient(routing);

        NotionClient client = new NotionClient("ntn_test", stub);

        SynthesisConfig config = makeConfig("test-workspace", "root-id");
        NotionWorkspaceSource source = new NotionWorkspaceSource(
                client, new NotionPageMapper(), syncState, new NotionBlockToMarkdown(), config);

        List<NotionPageMapper.NotionPage> pages = source.sync();

        // Should have found 3 pages: root + 2 children
        assertEquals(3, pages.size());

        Set<String> ids = new HashSet<>();
        for (NotionPageMapper.NotionPage p : pages) {
            ids.add(p.id());
        }
        assertTrue(ids.contains("root-id"));
        assertTrue(ids.contains("child-1"));
        assertTrue(ids.contains("child-2"));
    }

    // -----------------------------------------------------------------------
    // 2. sync_noRootPageId_usesSearchAllPages
    // -----------------------------------------------------------------------

    @Test
    void sync_noRootPageId_usesSearchAllPages() throws Exception {
        // Search returns 3 pages
        String searchJson = """
                {
                    "results": [
                        %s,
                        %s,
                        %s
                    ],
                    "has_more": false,
                    "next_cursor": null
                }
                """.formatted(
                makePage("p1", "Alpha", null),
                makePage("p2", "Beta", null),
                makePage("p3", "Gamma", null)
        );

        // Block children for each page (empty)
        String emptyBlocksJson = """
                {"results": [], "has_more": false, "next_cursor": null}
                """;

        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(200, searchJson));      // searchAllPages
        responses.add(new StubHttpResponse(200, emptyBlocksJson)); // blocks for p1
        responses.add(new StubHttpResponse(200, emptyBlocksJson)); // blocks for p2
        responses.add(new StubHttpResponse(200, emptyBlocksJson)); // blocks for p3

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));
        NotionClient client = new NotionClient("ntn_test", stub);

        // No root page ID — should use search
        SynthesisConfig config = makeConfig("test-workspace", null);
        NotionWorkspaceSource source = new NotionWorkspaceSource(
                client, new NotionPageMapper(), syncState, new NotionBlockToMarkdown(), config);

        List<NotionPageMapper.NotionPage> pages = source.sync();

        assertEquals(3, pages.size());
    }

    // -----------------------------------------------------------------------
    // 3. incrementalSync_noLastSync_delegatesToFullSync
    // -----------------------------------------------------------------------

    @Test
    void incrementalSync_noLastSync_delegatesToFullSync() throws Exception {
        // No prior sync exists -> should fall back to full sync (search)
        String searchJson = """
                {
                    "results": [%s],
                    "has_more": false,
                    "next_cursor": null
                }
                """.formatted(makePage("p1", "Test", null));

        String emptyBlocksJson = """
                {"results": [], "has_more": false, "next_cursor": null}
                """;

        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(200, searchJson));
        responses.add(new StubHttpResponse(200, emptyBlocksJson));

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));
        NotionClient client = new NotionClient("ntn_test", stub);

        SynthesisConfig config = makeConfig("test-workspace", null);
        NotionWorkspaceSource source = new NotionWorkspaceSource(
                client, new NotionPageMapper(), syncState, new NotionBlockToMarkdown(), config);

        // No prior sync state → should delegate to full sync
        List<NotionPageMapper.NotionPage> pages = source.incrementalSync();

        assertEquals(1, pages.size());
        assertEquals("p1", pages.get(0).id());
    }

    // -----------------------------------------------------------------------
    // 4. incrementalSync_withLastSync_filtersOldPages
    // -----------------------------------------------------------------------

    @Test
    void incrementalSync_withLastSync_filtersOldPages() throws Exception {
        // Record a previous sync time
        Instant lastSync = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        syncState.upsertSyncState("test-workspace", "", lastSync, 5, "ok", null);

        // One page edited 30 min ago (newer than lastSync), one edited 2 hours ago (older)
        Instant recentEdit = Instant.now().minus(30, ChronoUnit.MINUTES);
        Instant oldEdit = Instant.now().minus(2, ChronoUnit.HOURS);

        String searchJson = """
                {
                    "results": [
                        %s,
                        %s
                    ],
                    "has_more": false,
                    "next_cursor": null
                }
                """.formatted(
                makePageWithTime("recent-page", "Recent", null, recentEdit),
                makePageWithTime("old-page", "Old", null, oldEdit)
        );

        String emptyBlocksJson = """
                {"results": [], "has_more": false, "next_cursor": null}
                """;

        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(200, searchJson));
        responses.add(new StubHttpResponse(200, emptyBlocksJson)); // blocks for recent-page

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));
        NotionClient client = new NotionClient("ntn_test", stub);

        SynthesisConfig config = makeConfig("test-workspace", null);
        NotionWorkspaceSource source = new NotionWorkspaceSource(
                client, new NotionPageMapper(), syncState, new NotionBlockToMarkdown(), config);

        List<NotionPageMapper.NotionPage> pages = source.incrementalSync();

        // Only the recent page should be returned
        assertEquals(1, pages.size());
        assertEquals("recent-page", pages.get(0).id());
    }

    // -----------------------------------------------------------------------
    // 5. sync_updatesSyncState
    // -----------------------------------------------------------------------

    @Test
    void sync_updatesSyncState() throws Exception {
        String searchJson = """
                {
                    "results": [
                        %s,
                        %s
                    ],
                    "has_more": false,
                    "next_cursor": null
                }
                """.formatted(
                makePage("p1", "Page One", null),
                makePage("p2", "Page Two", null)
        );

        String emptyBlocksJson = """
                {"results": [], "has_more": false, "next_cursor": null}
                """;

        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(200, searchJson));
        responses.add(new StubHttpResponse(200, emptyBlocksJson));
        responses.add(new StubHttpResponse(200, emptyBlocksJson));

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));
        NotionClient client = new NotionClient("ntn_test", stub);

        SynthesisConfig config = makeConfig("test-workspace", null);
        NotionWorkspaceSource source = new NotionWorkspaceSource(
                client, new NotionPageMapper(), syncState, new NotionBlockToMarkdown(), config);

        source.sync();

        // Verify sync state was updated
        Optional<Instant> syncTime = syncState.getLastSyncTime("test-workspace");
        assertTrue(syncTime.isPresent(), "Sync state should be recorded after sync");

        // Verify pages were recorded — they should not be orphans if we pass them as live IDs
        List<String> orphans = syncState.getOrphanPageIds("test-workspace", Set.of("p1", "p2"));
        assertTrue(orphans.isEmpty(), "Synced pages should exist in the database");
    }

    // -----------------------------------------------------------------------
    // 6. sync_collisionResolution
    // -----------------------------------------------------------------------

    @Test
    void sync_collisionResolution() throws Exception {
        // Two pages with the same title (will have same virtual path → collision)
        String searchJson = """
                {
                    "results": [
                        %s,
                        %s
                    ],
                    "has_more": false,
                    "next_cursor": null
                }
                """.formatted(
                makePage("page-aaaa1111", "Meeting Notes", null),
                makePage("page-bbbb2222", "Meeting Notes", null)
        );

        String emptyBlocksJson = """
                {"results": [], "has_more": false, "next_cursor": null}
                """;

        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(200, searchJson));
        responses.add(new StubHttpResponse(200, emptyBlocksJson));
        responses.add(new StubHttpResponse(200, emptyBlocksJson));

        StubHttpClient stub = new StubHttpClient(new SequentialHandler(responses));
        NotionClient client = new NotionClient("ntn_test", stub);

        SynthesisConfig config = makeConfig("test-workspace", null);
        NotionWorkspaceSource source = new NotionWorkspaceSource(
                client, new NotionPageMapper(), syncState, new NotionBlockToMarkdown(), config);

        List<NotionPageMapper.NotionPage> pages = source.sync();

        assertEquals(2, pages.size());

        // Virtual paths should be different (collision resolved with ID suffix)
        Set<String> paths = new HashSet<>();
        for (NotionPageMapper.NotionPage p : pages) {
            paths.add(p.virtualPath());
        }
        assertEquals(2, paths.size(), "Collision resolution should produce unique paths");

        // Both paths should contain "Meeting Notes" but be different
        for (String path : paths) {
            assertTrue(path.contains("Meeting Notes"), "Path should contain the title: " + path);
        }
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    /**
     * Creates a minimal Notion page JSON string with standard timestamps.
     */
    private String makePage(String id, String title, String parentId) {
        return makePageWithTime(id, title, parentId, Instant.now());
    }

    /**
     * Creates a minimal Notion page JSON string with a specific last_edited_time.
     */
    private String makePageWithTime(String id, String title, String parentId, Instant lastEdited) {
        String parentJson;
        if (parentId != null) {
            parentJson = """
                    "parent": {"type": "page_id", "page_id": "%s"}""".formatted(parentId);
        } else {
            parentJson = """
                    "parent": {"type": "workspace", "workspace": true}""";
        }

        return """
                {
                    "object": "page",
                    "id": "%s",
                    "created_time": "%s",
                    "last_edited_time": "%s",
                    %s,
                    "properties": {
                        "title": {
                            "title": [{"plain_text": "%s"}]
                        }
                    }
                }
                """.formatted(id, Instant.now().minus(1, ChronoUnit.DAYS).toString(),
                lastEdited.toString(), parentJson, title);
    }

    /**
     * Creates a SynthesisConfig with the given workspace name and optional root page ID.
     */
    private SynthesisConfig makeConfig(String workspaceName, String rootPageId) {
        SynthesisConfig config = new SynthesisConfig();
        config.getWorkspace().setName(workspaceName);
        config.getNotion().setEnabled(true);
        config.getNotion().setToken("ntn_test_token");
        config.getNotion().setRootPageId(rootPageId);
        config.getNotion().setMaxPagesPerSync(500);
        return config;
    }

    // =====================================================================
    // Stub infrastructure (same pattern as NotionClientTest)
    // =====================================================================

    record StubHttpResponse(int statusCode, String body) {}

    /** Functional interface for handling HTTP requests in stubs. */
    @FunctionalInterface
    interface RequestHandler {
        StubHttpResponse handle(HttpRequest request);
    }

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
            // Return empty success for any extra requests
            return new StubHttpResponse(200, "{\"results\":[],\"has_more\":false,\"next_cursor\":null}");
        }
    }

    /**
     * Handler that routes requests based on URL path prefix matching.
     * Supports multiple calls to the same path (returns the same response each time).
     */
    static class RoutingHandler implements RequestHandler {
        private final Map<String, String> routes = new LinkedHashMap<>();

        void addRoute(String pathPrefix, String responseBody) {
            routes.put(pathPrefix, responseBody);
        }

        @Override
        public StubHttpResponse handle(HttpRequest request) {
            String path = request.uri().getPath();
            for (Map.Entry<String, String> entry : routes.entrySet()) {
                if (path.equals(entry.getKey())) {
                    return new StubHttpResponse(200, entry.getValue());
                }
            }
            // Fallback: empty result
            return new StubHttpResponse(200, "{\"results\":[],\"has_more\":false,\"next_cursor\":null}");
        }
    }

    /**
     * Minimal HttpClient stub that delegates to a RequestHandler.
     */
    static class StubHttpClient extends HttpClient {
        private final RequestHandler handler;

        StubHttpClient(SequentialHandler handler) {
            this.handler = handler;
        }

        StubHttpClient(RoutingHandler handler) {
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
