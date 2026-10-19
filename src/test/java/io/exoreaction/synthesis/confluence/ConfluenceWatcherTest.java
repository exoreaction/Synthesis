package io.exoreaction.synthesis.confluence;

import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.index.FileIndexer;
import io.exoreaction.synthesis.index.SearchIndex;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfluenceWatcher} — the polling watcher for Confluence
 * workspace changes. Mirrors {@code NotionWatcherTest}.
 */
class ConfluenceWatcherTest {

    private static final String BASE_URL = "https://example.atlassian.net/wiki";

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private Path indexPath;

    @BeforeEach
    void setUp() throws Exception {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        indexPath = tempDir.resolve("index");
        Files.createDirectories(indexPath);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null && !db.isClosed()) db.close();
    }

    // -----------------------------------------------------------------------
    // 1. start_runsIncrementalSync_andIndexesChangedPages
    // -----------------------------------------------------------------------

    @Test
    void start_runsIncrementalSync_andIndexesChangedPages() throws Exception {
        ConfluenceSyncState syncState = new ConfluenceSyncState(db);
        syncState.upsertSyncState("test-workspace", "ENG", Instant.now().minus(2, ChronoUnit.HOURS), 0, "ok", null);

        Instant recentEdit = Instant.now().minus(30, ChronoUnit.MINUTES);

        RoutingHandler routing = new RoutingHandler();
        routing.addRoute("/spaces", spaceJson("ENG", "98765"));
        routing.addRoute("/pages", pagesJson(List.of(
                pageJson("1", "Page One", recentEdit),
                pageJson("2", "Page Two", recentEdit)
        )));

        StubHttpClient stub = new StubHttpClient(routing);
        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace");
        ConfluenceWorkspaceSource source = new ConfluenceWorkspaceSource(
                client, new ConfluencePageMapper(), syncState, new ConfluenceContentToMarkdown(), config);

        FileIndexer indexer = new FileIndexer();
        ConfluenceWatcher watcher = new ConfluenceWatcher(source, indexer, indexPath, 1);

        CountDownLatch firstPollDone = new CountDownLatch(1);
        Thread watcherThread = Thread.ofVirtual().name("test-confluence-watcher").start(() -> {
            try {
                watcher.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                firstPollDone.countDown();
            }
        });

        Thread.sleep(2000);
        watcher.close();
        watcherThread.interrupt();

        assertTrue(firstPollDone.await(10, TimeUnit.SECONDS), "Watcher should have stopped");

        try (SearchIndex index = new SearchIndex(indexPath)) {
            assertTrue(index.documentCount() >= 2,
                    "At least 2 Confluence pages should be indexed, got: " + index.documentCount());
        }
    }

    // -----------------------------------------------------------------------
    // 2. close_stopsLoop
    // -----------------------------------------------------------------------

    @Test
    void close_stopsLoop() throws Exception {
        ConfluenceSyncState syncState = new ConfluenceSyncState(db);

        RoutingHandler routing = new RoutingHandler();
        routing.addRoute("/spaces", spaceJson("ENG", "98765"));
        routing.addRoute("/pages", pagesJson(List.of()));

        StubHttpClient stub = new StubHttpClient(routing);
        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace");
        ConfluenceWorkspaceSource source = new ConfluenceWorkspaceSource(
                client, new ConfluencePageMapper(), syncState, new ConfluenceContentToMarkdown(), config);

        FileIndexer indexer = new FileIndexer();
        ConfluenceWatcher watcher = new ConfluenceWatcher(source, indexer, indexPath, 60);

        CountDownLatch stopped = new CountDownLatch(1);
        Thread watcherThread = Thread.ofVirtual().name("test-confluence-watcher").start(() -> {
            try {
                watcher.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                stopped.countDown();
            }
        });

        Thread.sleep(1500);
        assertTrue(watcher.isRunning(), "Watcher should be running before close");

        watcher.close();
        watcherThread.interrupt();

        boolean finished = stopped.await(15, TimeUnit.SECONDS);
        assertTrue(finished, "Watcher start() should return promptly after close()");
        assertFalse(watcher.isRunning());
    }

    // -----------------------------------------------------------------------
    // 3. start_apiError_continuesRunning
    // -----------------------------------------------------------------------

    @Test
    void start_apiError_continuesRunning() throws Exception {
        ConfluenceSyncState syncState = new ConfluenceSyncState(db);
        syncState.upsertSyncState("test-workspace", "ENG", Instant.now().minus(2, ChronoUnit.HOURS), 0, "ok", null);

        RequestHandler handler = (request) -> new StubHttpResponse(500, "{\"message\":\"boom\"}");
        StubHttpClient stub = new StubHttpClient(handler);
        ConfluenceClient client = new ConfluenceClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace");
        ConfluenceWorkspaceSource source = new ConfluenceWorkspaceSource(
                client, new ConfluencePageMapper(), syncState, new ConfluenceContentToMarkdown(), config);

        FileIndexer indexer = new FileIndexer();
        ConfluenceWatcher watcher = new ConfluenceWatcher(source, indexer, indexPath, 1);

        Thread watcherThread = Thread.ofVirtual().name("test-confluence-watcher").start(() -> {
            try {
                watcher.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread.sleep(2000);
        assertTrue(watcher.isRunning(), "Watcher should continue running after a space-level API error");

        watcher.close();
        watcherThread.interrupt();
        watcherThread.join(10_000);
        assertFalse(watcher.isRunning());
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    private String pageJson(String id, String title, Instant versionCreatedAt) {
        return """
                {
                    "id": "%s",
                    "title": "%s",
                    "createdAt": "%s",
                    "version": {"number": 1, "createdAt": "%s"},
                    "body": {"storage": {"representation": "storage", "value": "<p>content</p>"}}
                }
                """.formatted(id, title, Instant.now().minus(1, ChronoUnit.DAYS), versionCreatedAt);
    }

    private String pagesJson(List<String> pages) {
        return """
                {"results": [%s], "_links": {}}
                """.formatted(String.join(",", pages));
    }

    private String spaceJson(String key, String id) {
        return """
                {"results": [{"id": "%s", "key": "%s", "name": "%s"}], "_links": {}}
                """.formatted(id, key, key);
    }

    private SynthesisConfig makeConfig(String workspaceName) {
        SynthesisConfig config = new SynthesisConfig();
        config.getWorkspace().setName(workspaceName);
        config.getConfluence().setEnabled(true);
        config.getConfluence().setBaseUrl(BASE_URL);
        config.getConfluence().setEmail("a@b.com");
        config.getConfluence().setApiToken("tok");
        config.getConfluence().setSpaceKeys(List.of("ENG"));
        config.getConfluence().setMaxPagesPerSync(500);
        return config;
    }

    // =====================================================================
    // Stub infrastructure
    // =====================================================================

    record StubHttpResponse(int statusCode, String body) {}

    @FunctionalInterface
    interface RequestHandler {
        StubHttpResponse handle(HttpRequest request);
    }

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
