package io.exoreaction.synthesis.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.jira.JiraIssueMapper.JiraIssue;
import io.exoreaction.synthesis.jira.JiraIssueMapper.JiraIssueLink;
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
 * Tests for {@link JiraWorkspaceSource} — the orchestrator for Jira workspace syncs.
 *
 * <p>Uses a stub {@link HttpClient} to avoid real Jira API calls, and an in-memory
 * SQLite database for state persistence, the same pattern as
 * {@code NotionWorkspaceSourceTest}.
 */
class JiraWorkspaceSourceTest {

    private static final String BASE_URL = "https://exo-test.atlassian.net";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private JiraSyncState syncState;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        syncState = new JiraSyncState(db);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null && !db.isClosed()) db.close();
    }

    // -----------------------------------------------------------------------
    // 1. sync — basic issue with changelog, comments, and an issue link
    // -----------------------------------------------------------------------

    @Test
    void sync_basicIssue_fetchesChangelogCommentsAndLinks() throws Exception {
        RoutingHandler routing = new RoutingHandler();
        routing.search = searchResponse(List.of(
                issueJson("PROJ-1", "PROJ", "Fix the login bug", "In Progress", "Bug",
                        "Alice", "High", List.of("PROJ-2"))
        ));
        routing.changelogs.put("PROJ-1", changelogResponse());
        routing.comments.put("PROJ-1", commentsResponse());

        StubHttpClient stub = new StubHttpClient(routing);
        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace", List.of("PROJ"));
        JiraWorkspaceSource source = new JiraWorkspaceSource(
                client, new JiraIssueMapper(), syncState, new JiraAdfToMarkdown(), config);

        List<JiraIssue> issues = source.sync();

        assertEquals(1, issues.size());
        JiraIssue issue = issues.get(0);
        assertEquals("PROJ-1", issue.key());
        assertEquals("PROJ/PROJ-1.md", issue.virtualPath());
        assertTrue(issue.markdownContent().contains("# PROJ-1: Fix the login bug"));
        assertTrue(issue.markdownContent().contains("## Changelog"));
        assertTrue(issue.markdownContent().contains("## Comments"));
        assertTrue(issue.markdownContent().contains("## Issue Links"));
        assertEquals(BASE_URL + "/browse/PROJ-1", issue.issueUrl());
    }

    // -----------------------------------------------------------------------
    // 2. sync — no projectKeys/jqlFilter configured uses "ORDER BY updated"
    // -----------------------------------------------------------------------

    @Test
    void sync_noScopeConfigured_usesOrderByUpdatedOnly() throws Exception {
        List<HttpRequest> captured = new ArrayList<>();
        RoutingHandler routing = new RoutingHandler();
        routing.search = searchResponse(List.of());

        StubHttpClient stub = new StubHttpClient(request -> {
            captured.add(request);
            return routing.handle(request);
        });
        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace", List.of());
        JiraWorkspaceSource source = new JiraWorkspaceSource(
                client, new JiraIssueMapper(), syncState, new JiraAdfToMarkdown(), config);

        source.sync();

        assertEquals(1, captured.size());
        String body = bodyOf(captured.get(0));
        assertTrue(body.contains("ORDER BY updated"), "Body was: " + body);
        assertFalse(body.contains("project in"));
    }

    // -----------------------------------------------------------------------
    // 3. sync — projectKeys builds a "project in (...)" JQL clause
    // -----------------------------------------------------------------------

    @Test
    void sync_withProjectKeys_buildsProjectInClause() throws Exception {
        List<HttpRequest> captured = new ArrayList<>();
        RoutingHandler routing = new RoutingHandler();
        routing.search = searchResponse(List.of());

        StubHttpClient stub = new StubHttpClient(request -> {
            captured.add(request);
            return routing.handle(request);
        });
        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace", List.of("PROJ", "OTHER"));
        JiraWorkspaceSource source = new JiraWorkspaceSource(
                client, new JiraIssueMapper(), syncState, new JiraAdfToMarkdown(), config);

        source.sync();

        String body = bodyOf(captured.get(0));
        assertTrue(body.contains("project in (\\\"PROJ\\\", \\\"OTHER\\\")") || body.contains("project in"),
                "Body was: " + body);
    }

    // -----------------------------------------------------------------------
    // 4. incrementalSync — no prior sync delegates to full sync
    // -----------------------------------------------------------------------

    @Test
    void incrementalSync_noLastSync_delegatesToFullSync() throws Exception {
        RoutingHandler routing = new RoutingHandler();
        routing.search = searchResponse(List.of(
                issueJson("PROJ-1", "PROJ", "Only issue", "Open", "Task", null, null, List.of())
        ));

        StubHttpClient stub = new StubHttpClient(routing);
        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace", List.of("PROJ"));
        JiraWorkspaceSource source = new JiraWorkspaceSource(
                client, new JiraIssueMapper(), syncState, new JiraAdfToMarkdown(), config);

        List<JiraIssue> issues = source.incrementalSync();

        assertEquals(1, issues.size());
        assertEquals("PROJ-1", issues.get(0).key());
    }

    // -----------------------------------------------------------------------
    // 5. incrementalSync — appends a server-side "updated >=" clause
    // -----------------------------------------------------------------------

    @Test
    void incrementalSync_withLastSync_appendsUpdatedClause() throws Exception {
        Instant lastSync = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        syncState.upsertSyncState("test-workspace", "project in (\"PROJ\") ORDER BY updated",
                lastSync, 5, "ok", null);

        List<HttpRequest> captured = new ArrayList<>();
        RoutingHandler routing = new RoutingHandler();
        routing.search = searchResponse(List.of());

        StubHttpClient stub = new StubHttpClient(request -> {
            captured.add(request);
            return routing.handle(request);
        });
        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace", List.of("PROJ"));
        JiraWorkspaceSource source = new JiraWorkspaceSource(
                client, new JiraIssueMapper(), syncState, new JiraAdfToMarkdown(), config);

        source.incrementalSync();

        assertEquals(1, captured.size());
        String body = bodyOf(captured.get(0));
        assertTrue(body.contains("updated >="), "Body was: " + body);
        assertTrue(body.contains("ORDER BY updated"), "Body was: " + body);
    }

    // -----------------------------------------------------------------------
    // 6. sync — updates sync state and persists issue + issue links
    // -----------------------------------------------------------------------

    @Test
    void sync_persistsIssueAndIssueLinks() throws Exception {
        RoutingHandler routing = new RoutingHandler();
        routing.search = searchResponse(List.of(
                issueJson("PROJ-1", "PROJ", "Has a link", "Open", "Story", "Alice", "Medium", List.of("PROJ-2"))
        ));
        routing.changelogs.put("PROJ-1", emptyChangelogResponse());
        routing.comments.put("PROJ-1", emptyCommentsResponse());

        StubHttpClient stub = new StubHttpClient(routing);
        JiraClient client = new JiraClient(BASE_URL, "a@b.com", "tok", stub);

        SynthesisConfig config = makeConfig("test-workspace", List.of("PROJ"));
        JiraWorkspaceSource source = new JiraWorkspaceSource(
                client, new JiraIssueMapper(), syncState, new JiraAdfToMarkdown(), config);

        source.sync();

        Optional<Instant> syncTime = syncState.getLastSyncTime("test-workspace");
        assertTrue(syncTime.isPresent());

        Set<String> keys = syncState.getAllIssueKeys("test-workspace");
        assertTrue(keys.contains("PROJ-1"));

        List<JiraIssueLink> links = syncState.getIssueLinks("test-workspace", "PROJ-1");
        assertEquals(1, links.size());
        assertEquals("PROJ-2", links.get(0).targetKey());
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    private SynthesisConfig makeConfig(String workspaceName, List<String> projectKeys) {
        SynthesisConfig config = new SynthesisConfig();
        config.getWorkspace().setName(workspaceName);
        config.getJira().setEnabled(true);
        config.getJira().setBaseUrl(BASE_URL);
        config.getJira().setEmail("a@b.com");
        config.getJira().setApiToken("tok");
        config.getJira().setProjectKeys(projectKeys);
        config.getJira().setMaxIssuesPerSync(500);
        return config;
    }

    private String issueJson(String key, String projectKey, String summary, String status,
                              String issueType, String assignee, String priority, List<String> linkedKeys) {
        StringBuilder links = new StringBuilder();
        for (String linkedKey : linkedKeys) {
            if (links.length() > 0) links.append(",");
            links.append("""
                    {"type": {"name": "Blocks", "inward": "is blocked by", "outward": "blocks"},
                     "outwardIssue": {"key": "%s", "fields": {"summary": "Linked issue"}}}
                    """.formatted(linkedKey));
        }

        return """
                {
                    "id": "10001",
                    "key": "%s",
                    "fields": {
                        "summary": "%s",
                        "status": %s,
                        "issuetype": %s,
                        "assignee": %s,
                        "priority": %s,
                        "labels": [],
                        "components": [],
                        "project": {"key": "%s"},
                        "updated": "2026-01-15T10:00:00.000+0000",
                        "description": {"type": "doc", "version": 1, "content": [
                            {"type": "paragraph", "content": [{"type": "text", "text": "Description body"}]}
                        ]},
                        "issuelinks": [%s]
                    }
                }
                """.formatted(key, summary,
                status != null ? "{\"name\": \"" + status + "\"}" : "null",
                issueType != null ? "{\"name\": \"" + issueType + "\"}" : "null",
                assignee != null ? "{\"displayName\": \"" + assignee + "\"}" : "null",
                priority != null ? "{\"name\": \"" + priority + "\"}" : "null",
                projectKey, links);
    }

    private String searchResponse(List<String> issueJsons) {
        return "{\"issues\": [" + String.join(",", issueJsons) + "]}";
    }

    private String changelogResponse() {
        return """
                {
                    "startAt": 0, "maxResults": 100, "total": 1,
                    "histories": [
                        {"created": "2026-01-10T00:00:00.000+0000",
                         "author": {"displayName": "Alice"},
                         "items": [{"field": "status", "fromString": "To Do", "toString": "In Progress"}]}
                    ]
                }
                """;
    }

    private String emptyChangelogResponse() {
        return "{\"startAt\": 0, \"maxResults\": 100, \"total\": 0, \"histories\": []}";
    }

    private String commentsResponse() {
        return """
                {
                    "startAt": 0, "maxResults": 100, "total": 1,
                    "comments": [
                        {"author": {"displayName": "Bob"}, "created": "2026-01-12T00:00:00.000+0000",
                         "body": {"type": "doc", "version": 1, "content": [
                             {"type": "paragraph", "content": [{"type": "text", "text": "Looks good"}]}
                         ]}}
                    ]
                }
                """;
    }

    private String emptyCommentsResponse() {
        return "{\"startAt\": 0, \"maxResults\": 100, \"total\": 0, \"comments\": []}";
    }

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

    // =====================================================================
    // Stub infrastructure
    // =====================================================================

    record StubHttpResponse(int statusCode, String body) {}

    @FunctionalInterface
    interface RequestHandler {
        StubHttpResponse handle(HttpRequest request);
    }

    /** Routes by path: /search/jql -> search, /issue/{key}/changelog, /issue/{key}/comment. */
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
                String key = extractIssueKey(path);
                return new StubHttpResponse(200, changelogs.getOrDefault(key,
                        "{\"startAt\":0,\"maxResults\":100,\"total\":0,\"histories\":[]}"));
            }
            if (path.contains("/comment")) {
                String key = extractIssueKey(path);
                return new StubHttpResponse(200, comments.getOrDefault(key,
                        "{\"startAt\":0,\"maxResults\":100,\"total\":0,\"comments\":[]}"));
            }
            return new StubHttpResponse(200, "{}");
        }

        private String extractIssueKey(String path) {
            String[] parts = path.split("/");
            for (int i = 0; i < parts.length; i++) {
                if ("issue".equals(parts[i]) && i + 1 < parts.length) {
                    return parts[i + 1];
                }
            }
            return "";
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
