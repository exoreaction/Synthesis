package io.exoreaction.synthesis.notion;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NotionWatcher} — the polling watcher for Notion workspace changes.
 */
class NotionWatcherTest {

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
        // Set up a previous sync time so incrementalSync does not fall back to full sync
        NotionSyncState syncState = new NotionSyncState(db);
        syncState.upsertSyncState("test-workspace", "", Instant.now().minus(2, ChronoUnit.HOURS), 0, "ok", null);

        Instant recentEdit = Instant.now().minus(30, ChronoUnit.MINUTES);

        // Search returns 2 recently edited pages
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
                makePageWithTime("p1", "Page One", null, recentEdit),
                makePageWithTime("p2", "Page Two", null, recentEdit)
        );

        String emptyBlocksJson = """
                {"results": [], "has_more": false, "next_cursor": null}
                """;

        List<StubHttpResponse> responses = new ArrayList<>();
        responses.add(new StubHttpResponse(200, searchJson));
        responses.add(new StubHttpResponse(200, emptyBlocksJson));
        responses.add(new StubHttpResponse(200, emptyBlocksJson));

        StubHttpClient stub = new StubHttpClient(responses);
        NotionClient client = new NotionClient("ntn_test", stub);

        SynthesisConfig config = makeConfig("test-workspace", null);
        NotionWorkspaceSource source = new NotionWorkspaceSource(
                client, new NotionPageMapper(), syncState, new NotionBlockToMarkdown(), config);

        FileIndexer indexer = new FileIndexer();

        // Use a 1-minute poll interval (watcher will be closed before the sleep finishes)
        NotionWatcher watcher = new NotionWatcher(source, indexer, indexPath, 1);

        // Run the watcher in a separate thread, close it after the first sync completes
        CountDownLatch firstPollDone = new CountDownLatch(1);

        Thread watcherThread = Thread.ofVirtual().name("test-notion-watcher").start(() -> {
            try {
                watcher.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                firstPollDone.countDown();
            }
        });

        // Give it time to do the first poll, then close
        Thread.sleep(2000);
        watcher.close();
        watcherThread.interrupt();

        assertTrue(firstPollDone.await(10, TimeUnit.SECONDS), "Watcher should have stopped");

        // Verify pages were indexed in Lucene
        try (SearchIndex index = new SearchIndex(indexPath)) {
            // The index should contain documents (we can't easily search without Lucene query,
            // but at minimum the commit should have happened without errors)
            assertTrue(index.documentCount() >= 2,
                    "At least 2 Notion pages should be indexed, got: " + index.documentCount());
        }
    }

    // -----------------------------------------------------------------------
    // 2. start_ioException_continuesRunning
    // -----------------------------------------------------------------------

    @Test
    void start_ioException_continuesRunning() throws Exception {
        // First call throws IOException, second call should succeed
        NotionSyncState syncState = new NotionSyncState(db);
        syncState.upsertSyncState("test-workspace", "", Instant.now().minus(2, ChronoUnit.HOURS), 0, "ok", null);

        AtomicInteger callCount = new AtomicInteger(0);
        Instant recentEdit = Instant.now().minus(30, ChronoUnit.MINUTES);

        // The stub returns an error on the first call, success on subsequent calls
        String searchJson = """
                {
                    "results": [%s],
                    "has_more": false,
                    "next_cursor": null
                }
                """.formatted(makePageWithTime("p1", "Page One", null, recentEdit));

        String emptyBlocksJson = """
                {"results": [], "has_more": false, "next_cursor": null}
                """;

        StubHttpClient stub = new StubHttpClient(new RequestHandler() {
            @Override
            public StubHttpResponse handle(HttpRequest request) {
                int call = callCount.getAndIncrement();
                if (call == 0) {
                    // First call: simulate a server error
                    return new StubHttpResponse(500, "{\"message\":\"Internal Server Error\"}");
                }
                // Subsequent calls: search returns one page
                String path = request.uri().getPath();
                if (path.contains("/search")) {
                    return new StubHttpResponse(200, searchJson);
                }
                if (path.contains("/blocks/")) {
                    return new StubHttpResponse(200, emptyBlocksJson);
                }
                return new StubHttpResponse(200, searchJson);
            }
        });

        NotionClient client = new NotionClient("ntn_test", stub);
        SynthesisConfig config = makeConfig("test-workspace", null);
        NotionWorkspaceSource source = new NotionWorkspaceSource(
                client, new NotionPageMapper(), syncState, new NotionBlockToMarkdown(), config);

        FileIndexer indexer = new FileIndexer();

        // Use a very short poll interval for testing (but the watcher sleeps in 10s chunks)
        NotionWatcher watcher = new NotionWatcher(source, indexer, indexPath, 1);

        Thread watcherThread = Thread.ofVirtual().name("test-notion-watcher").start(() -> {
            try {
                watcher.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Wait long enough for the first (failing) poll to complete
        Thread.sleep(2000);

        // Watcher should still be running despite the IOException
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
        // Create a source that does nothing (no prior sync = full sync needed,
        // but we use empty search results)
        NotionSyncState syncState = new NotionSyncState(db);

        String emptySearchJson = """
                {"results": [], "has_more": false, "next_cursor": null}
                """;

        StubHttpClient stub = new StubHttpClient(List.of(
                new StubHttpResponse(200, emptySearchJson)
        ));
        NotionClient client = new NotionClient("ntn_test", stub);

        SynthesisConfig config = makeConfig("test-workspace", null);
        NotionWorkspaceSource source = new NotionWorkspaceSource(
                client, new NotionPageMapper(), syncState, new NotionBlockToMarkdown(), config);

        FileIndexer indexer = new FileIndexer();
        NotionWatcher watcher = new NotionWatcher(source, indexer, indexPath, 60);

        CountDownLatch stopped = new CountDownLatch(1);
        Thread watcherThread = Thread.ofVirtual().name("test-notion-watcher").start(() -> {
            try {
                watcher.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                stopped.countDown();
            }
        });

        // Let the first poll run, then close
        Thread.sleep(1500);
        assertTrue(watcher.isRunning(), "Watcher should be running before close");

        watcher.close();
        watcherThread.interrupt();

        // start() should return within a few seconds
        boolean finished = stopped.await(15, TimeUnit.SECONDS);
        assertTrue(finished, "Watcher start() should return promptly after close()");
        assertFalse(watcher.isRunning());
    }

    // -----------------------------------------------------------------------
    // 4. start_noChanges_doesNotCallIndexVirtualFile
    // -----------------------------------------------------------------------

    @Test
    void start_noChanges_doesNotCallIndexVirtualFile() throws Exception {
        // Set up sync state with a recent sync time, and search returns no changed pages
        NotionSyncState syncState = new NotionSyncState(db);
        syncState.upsertSyncState("test-workspace", "",
                Instant.now().minus(5, ChronoUnit.MINUTES), 3, "ok", null);

        // Search returns pages that are all older than the last sync time
        Instant oldEdit = Instant.now().minus(1, ChronoUnit.HOURS);
        String searchJson = """
                {
                    "results": [%s],
                    "has_more": false,
                    "next_cursor": null
                }
                """.formatted(makePageWithTime("p-old", "Old Page", null, oldEdit));

        StubHttpClient stub = new StubHttpClient(List.of(
                new StubHttpResponse(200, searchJson)
        ));
        NotionClient client = new NotionClient("ntn_test", stub);

        SynthesisConfig config = makeConfig("test-workspace", null);
        NotionWorkspaceSource source = new NotionWorkspaceSource(
                client, new NotionPageMapper(), syncState, new NotionBlockToMarkdown(), config);

        FileIndexer indexer = new FileIndexer();
        NotionWatcher watcher = new NotionWatcher(source, indexer, indexPath, 60);

        Thread watcherThread = Thread.ofVirtual().name("test-notion-watcher").start(() -> {
            try {
                watcher.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Let the first poll complete
        Thread.sleep(2000);
        watcher.close();
        watcherThread.interrupt();
        watcherThread.join(10_000);

        // Verify no documents were indexed (no changed pages)
        try (SearchIndex index = new SearchIndex(indexPath)) {
            assertEquals(0, index.documentCount(),
                    "No documents should be indexed when there are no changed pages");
        }
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

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
    // Stub infrastructure
    // =====================================================================

    record StubHttpResponse(int statusCode, String body) {}

    @FunctionalInterface
    interface RequestHandler {
        StubHttpResponse handle(HttpRequest request);
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
            return new StubHttpResponse(200, "{\"results\":[],\"has_more\":false,\"next_cursor\":null}");
        }
    }

    static class StubHttpClient extends HttpClient {
        private final RequestHandler handler;

        StubHttpClient(List<StubHttpResponse> responses) {
            this.handler = new SequentialHandler(responses);
        }

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
