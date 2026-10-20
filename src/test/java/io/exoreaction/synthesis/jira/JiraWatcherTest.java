package io.exoreaction.synthesis.jira;

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
 * Tests for {@link JiraWatcher} — the polling watcher for Jira workspace changes.
 */
class JiraWatcherTest {

    private static final String BASE_URL = "https://exo-test.atlassian.net";

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
    // 1. start_runsIncrementalSync_andIndexesChangedIssues
    // -----------------------------------------------------------------------

    @Test
    void start_runsIncrementalSync_andIndexesChangedIssues() throws Exception {
        JiraSyncState syncState = new JiraSyncState(db);
        syncState.upsertSyncState("test-workspace", "ORDER BY updated",
                Instant.now().minus(2, ChronoUnit.HOURS), 0, "ok", null);

        RoutingHandler routing = new RoutingHandler();
        routing.search = """
                {"issues": [
                    {"id": "1", "key": "PROJ-1", "fields": {"summary": "One", "project": {"key": "PROJ"},
                     "updated": "2026-01-15T10:00:00.000+0000", "labels": [], "components": []}},
                    {"id": "2", "key": "PROJ-2", "fields": {"summary": "Two", "project": {"key": "PROJ"},
                     "updated": "2026-01-15T10:00:00.000+0000", "labels": [], "components": []}}
                ]}
                """;

        StubHttpClient stub = new StubHttpClient(routing);
        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace");
        JiraWorkspaceSource source = new JiraWorkspaceSource(
                client, new JiraIssueMapper(), syncState, new JiraAdfToMarkdown(), config);

        FileIndexer indexer = new FileIndexer();
        JiraWatcher watcher = new JiraWatcher(source, indexer, indexPath, 1);

        CountDownLatch firstPollDone = new CountDownLatch(1);
        Thread watcherThread = Thread.ofVirtual().name("test-jira-watcher").start(() -> {
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
                    "At least 2 Jira issues should be indexed, got: " + index.documentCount());
        }
    }

    // -----------------------------------------------------------------------
    // 2. start_ioException_continuesRunning
    // -----------------------------------------------------------------------

    @Test
    void start_ioException_continuesRunning() throws Exception {
        JiraSyncState syncState = new JiraSyncState(db);
        syncState.upsertSyncState("test-workspace", "ORDER BY updated",
                Instant.now().minus(2, ChronoUnit.HOURS), 0, "ok", null);

        AtomicIntCounter callCount = new AtomicIntCounter();

        StubHttpClient stub = new StubHttpClient(request -> {
            int call = callCount.getAndIncrement();
            if (call == 0) {
                return new StubHttpResponse(500, "{\"errorMessages\":[\"Internal Server Error\"]}");
            }
            return new StubHttpResponse(200, "{\"issues\": []}");
        });

        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);
        SynthesisConfig config = makeConfig("test-workspace");
        JiraWorkspaceSource source = new JiraWorkspaceSource(
                client, new JiraIssueMapper(), syncState, new JiraAdfToMarkdown(), config);

        FileIndexer indexer = new FileIndexer();
        JiraWatcher watcher = new JiraWatcher(source, indexer, indexPath, 1);

        Thread watcherThread = Thread.ofVirtual().name("test-jira-watcher").start(() -> {
            try {
                watcher.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread.sleep(2000);
        assertTrue(watcher.isRunning(), "Watcher should continue running after IOException");

        watcher.close();
        watcherThread.interrupt();
        watcherThread.join(10_000);
        assertFalse(watcher.isRunning());
    }

    // -----------------------------------------------------------------------
    // 3. close_stopsLoop
    // -----------------------------------------------------------------------

    @Test
    void close_stopsLoop() throws Exception {
        JiraSyncState syncState = new JiraSyncState(db);

        StubHttpClient stub = new StubHttpClient(request -> new StubHttpResponse(200, "{\"issues\": []}"));
        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace");
        JiraWorkspaceSource source = new JiraWorkspaceSource(
                client, new JiraIssueMapper(), syncState, new JiraAdfToMarkdown(), config);

        FileIndexer indexer = new FileIndexer();
        JiraWatcher watcher = new JiraWatcher(source, indexer, indexPath, 60);

        CountDownLatch stopped = new CountDownLatch(1);
        Thread watcherThread = Thread.ofVirtual().name("test-jira-watcher").start(() -> {
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
    // Helper methods
    // -----------------------------------------------------------------------

    private SynthesisConfig makeConfig(String workspaceName) {
        SynthesisConfig config = new SynthesisConfig();
        config.getWorkspace().setName(workspaceName);
        config.getJira().setEnabled(true);
        config.getJira().setBaseUrl(BASE_URL);
        config.getJira().setEmail("a@b.com");
        config.getJira().setApiToken("tok");
        config.getJira().setProjectKeys(List.of("PROJ"));
        config.getJira().setMaxIssuesPerSync(500);
        return config;
    }

    /** Minimal mutable counter (avoids importing AtomicInteger just for this one use). */
    static class AtomicIntCounter {
        private int value = 0;
        synchronized int getAndIncrement() { return value++; }
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
        String search = "{\"issues\": []}";
        Map<String, String> changelogs = new HashMap<>();
        Map<String, String> comments = new HashMap<>();

        @Override
        public StubHttpResponse handle(HttpRequest request) {
            String path = request.uri().getPath();
            if (path.endsWith("/search/jql")) {
                return new StubHttpResponse(200, search);
            }
            if (path.contains("/changelog")) {
                return new StubHttpResponse(200, "{\"startAt\":0,\"maxResults\":100,\"total\":0,\"histories\":[]}");
            }
            if (path.contains("/comment")) {
                return new StubHttpResponse(200, "{\"startAt\":0,\"maxResults\":100,\"total\":0,\"comments\":[]}");
            }
            return new StubHttpResponse(200, "{}");
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
            if (stubResponse.statusCode() == 500) {
                throw new IOException("Server error: " + stubResponse.body());
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
