package io.exoreaction.synthesis.sessions;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClaudeSessionScanner — JSONL parsing and incremental scan behaviour.
 */
class ClaudeSessionScannerTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private SessionStore store;
    private Path projectsDir;

    @BeforeEach
    void setUp() throws SQLException, IOException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        store = new SessionStore(db);
        projectsDir = tempDir.resolve("projects");
        Files.createDirectories(projectsDir);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null && !db.isClosed()) db.close();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Path writeSession(String sessionId, String... lines) throws IOException {
        Path file = projectsDir.resolve(sessionId + ".jsonl");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    private static final String SESSION_UUID = "3f8b1234-abcd-4321-ef12-000000000001";

    private static final String SNAPSHOT_LINE =
            "{\"type\":\"file-history-snapshot\",\"files\":[]}";

    private static String userLine(String sessionId, String cwd, String timestamp, String content) {
        return "{\"type\":\"user\",\"sessionId\":\"" + sessionId + "\",\"cwd\":\"" + cwd
                + "\",\"timestamp\":\"" + timestamp + "\","
                + "\"message\":{\"role\":\"user\",\"content\":\"" + content + "\"}}";
    }

    private static String assistantLineWithTool(String timestamp, String toolName) {
        return "{\"type\":\"assistant\",\"timestamp\":\"" + timestamp + "\","
                + "\"message\":{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"tool_use\",\"name\":\"" + toolName + "\",\"id\":\"t1\","
                + "\"input\":{}}]}}";
    }

    private static String assistantLineText(String timestamp, String text) {
        return "{\"type\":\"assistant\",\"timestamp\":\"" + timestamp + "\","
                + "\"message\":{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"" + text + "\"}]}}";
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void scan_emptyDirectory_returnsZero() throws Exception {
        ClaudeSessionScanner scanner = new ClaudeSessionScanner(store, projectsDir);
        assertEquals(0, scanner.scan());
    }

    @Test
    void scan_singleSession_parsesCorrectly() throws Exception {
        writeSession(SESSION_UUID,
                SNAPSHOT_LINE,
                userLine(SESSION_UUID, "/home/user/myproject", "2026-02-01T10:00:00Z",
                        "Implement authentication"),
                assistantLineWithTool("2026-02-01T10:00:05Z", "Read"),
                assistantLineWithTool("2026-02-01T10:00:10Z", "Edit"),
                userLine(SESSION_UUID, "/home/user/myproject", "2026-02-01T10:01:00Z",
                        "Also add tests")
        );

        ClaudeSessionScanner scanner = new ClaudeSessionScanner(store, projectsDir);
        int processed = scanner.scan();

        assertEquals(1, processed);

        Optional<ClaudeSession> result = store.getBySessionId(SESSION_UUID);
        assertTrue(result.isPresent());

        ClaudeSession session = result.get();
        assertEquals(SESSION_UUID, session.sessionId());
        assertEquals("/home/user/myproject", session.projectDir());
        assertEquals(2, session.turnCount());
        assertEquals(2, session.toolCallCount());
        assertTrue(session.toolNames().contains("Read"));
        assertTrue(session.toolNames().contains("Edit"));
        assertEquals("Implement authentication", session.firstMessage());
        assertTrue(session.allUserText().contains("authentication"));
        assertTrue(session.allUserText().contains("tests"));
    }

    @Test
    void scan_skipsSnapshotLines() throws Exception {
        writeSession(SESSION_UUID,
                SNAPSHOT_LINE,
                SNAPSHOT_LINE, // duplicate snapshot — still should be ignored
                userLine(SESSION_UUID, "/home/user/proj", "2026-02-01T10:00:00Z", "hello world")
        );

        ClaudeSessionScanner scanner = new ClaudeSessionScanner(store, projectsDir);
        scanner.scan();

        Optional<ClaudeSession> session = store.getBySessionId(SESSION_UUID);
        assertTrue(session.isPresent());
        assertEquals(1, session.get().turnCount());
    }

    @Test
    void scan_skipsNonJsonlFiles() throws Exception {
        // Write a non-.jsonl file in the projects dir
        Files.writeString(projectsDir.resolve("notajsonl.txt"), "some content");

        ClaudeSessionScanner scanner = new ClaudeSessionScanner(store, projectsDir);
        assertEquals(0, scanner.scan());
        assertEquals(0, store.count());
    }

    @Test
    void scan_emptySession_notIndexed() throws Exception {
        // File with only snapshot line and no user messages
        writeSession(SESSION_UUID, SNAPSHOT_LINE);

        ClaudeSessionScanner scanner = new ClaudeSessionScanner(store, projectsDir);
        int processed = scanner.scan();

        assertEquals(0, processed);
        assertEquals(0, store.count());
    }

    @Test
    void scan_multipleDistinctTools_deduplicated() throws Exception {
        writeSession(SESSION_UUID,
                SNAPSHOT_LINE,
                userLine(SESSION_UUID, "/proj", "2026-02-01T10:00:00Z", "refactor"),
                assistantLineWithTool("2026-02-01T10:00:05Z", "Read"),
                assistantLineWithTool("2026-02-01T10:00:06Z", "Read"),  // duplicate
                assistantLineWithTool("2026-02-01T10:00:07Z", "Edit")
        );

        ClaudeSessionScanner scanner = new ClaudeSessionScanner(store, projectsDir);
        scanner.scan();

        ClaudeSession session = store.getBySessionId(SESSION_UUID).orElseThrow();
        // toolNames should be deduplicated (Read appears once, Edit once)
        assertEquals(2, session.toolNames().size());
        assertTrue(session.toolNames().contains("Read"));
        assertTrue(session.toolNames().contains("Edit"));
        // But toolCallCount counts raw invocations
        assertEquals(3, session.toolCallCount());
    }

    @Test
    void scan_incrementalSkipsUnchangedFiles() throws Exception {
        Path file = writeSession(SESSION_UUID,
                SNAPSHOT_LINE,
                userLine(SESSION_UUID, "/proj", "2026-02-01T10:00:00Z", "first scan")
        );

        ClaudeSessionScanner scanner = new ClaudeSessionScanner(store, projectsDir);
        assertEquals(1, scanner.scan()); // first scan
        assertEquals(0, scanner.scan()); // second scan — file unchanged, skip
    }

    @Test
    void parseFile_arrayContentExtractsText() throws Exception {
        // Content as array of typed blocks
        String arrayContentSession = "{\"type\":\"user\",\"sessionId\":\"" + SESSION_UUID + "\","
                + "\"cwd\":\"/proj\",\"timestamp\":\"2026-02-01T10:00:00Z\","
                + "\"message\":{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"Array content message\"},"
                + "{\"type\":\"tool_result\",\"content\":\"ignore this\"}"
                + "]}}";

        writeSession(SESSION_UUID, SNAPSHOT_LINE, arrayContentSession);

        ClaudeSessionScanner scanner = new ClaudeSessionScanner(store, projectsDir);
        scanner.scan();

        ClaudeSession session = store.getBySessionId(SESSION_UUID).orElseThrow();
        assertEquals("Array content message", session.firstMessage());
    }

    // -----------------------------------------------------------------------
    // Subagent tests
    // -----------------------------------------------------------------------

    private static final String PARENT_UUID = "624a7854-a7d2-4331-8ddb-7dab21e7064c";
    private static final String AGENT_ID = "ae5fb06d98fb195b2";
    private static final String AGENT_SLUG = "tingly-soaring-naur";

    private Path writeSubagentSession(String parentSessionId, String agentId,
                                       String slug, String... extraLines) throws IOException {
        Path subagentsDir = projectsDir.resolve(parentSessionId).resolve("subagents");
        Files.createDirectories(subagentsDir);
        Path file = subagentsDir.resolve("agent-" + agentId + ".jsonl");
        StringBuilder content = new StringBuilder();
        // First line: a user message with subagent metadata
        content.append("{\"type\":\"user\",\"isSidechain\":true,\"sessionId\":\"")
                .append(parentSessionId)
                .append("\",\"agentId\":\"").append(agentId)
                .append("\",\"slug\":\"").append(slug)
                .append("\",\"cwd\":\"/src/test/subagent-project\"")
                .append(",\"timestamp\":\"2026-03-14T10:00:00Z\"")
                .append(",\"message\":{\"role\":\"user\",\"content\":\"Subagent task: implement feature X\"}}")
                .append("\n");
        for (String line : extraLines) {
            content.append(line).append("\n");
        }
        Files.writeString(file, content.toString());
        return file;
    }

    @Test
    void scan_subagentFile_detectsSubagentMetadata() throws Exception {
        writeSubagentSession(PARENT_UUID, AGENT_ID, AGENT_SLUG);

        ClaudeSessionScanner scanner = new ClaudeSessionScanner(store, projectsDir);
        int processed = scanner.scan();

        assertEquals(1, processed);

        // The session is stored with key "agent-<agentId>"
        Optional<ClaudeSession> result = store.getBySessionId("agent-" + AGENT_ID);
        assertTrue(result.isPresent(), "Subagent session should be stored");

        ClaudeSession session = result.get();
        assertTrue(session.isSubagent(), "isSubagent should be true");
        assertEquals(PARENT_UUID, session.parentSessionId());
        assertEquals(AGENT_ID, session.agentId());
        assertEquals(AGENT_SLUG, session.agentSlug());
        assertEquals("/src/test/subagent-project", session.projectDir());
        assertEquals("Subagent task: implement feature X", session.firstMessage());
    }

    @Test
    void scan_regularSession_isNotSubagent() throws Exception {
        writeSession(SESSION_UUID,
                SNAPSHOT_LINE,
                userLine(SESSION_UUID, "/home/user/myproject", "2026-02-01T10:00:00Z",
                        "Regular session task")
        );

        ClaudeSessionScanner scanner = new ClaudeSessionScanner(store, projectsDir);
        scanner.scan();

        ClaudeSession session = store.getBySessionId(SESSION_UUID).orElseThrow();
        assertFalse(session.isSubagent(), "Regular session should not be subagent");
        assertNull(session.parentSessionId(), "Regular session should have no parent");
        assertNull(session.agentId(), "Regular session should have no agentId");
        assertNull(session.agentSlug(), "Regular session should have no agentSlug");
    }

    @Test
    void isSubagentPath_detectsCorrectly() {
        assertTrue(ClaudeSessionScanner.isSubagentPath(
                Path.of("/home/user/.claude/projects/proj/abc-123/subagents/agent-xyz.jsonl")));
        assertFalse(ClaudeSessionScanner.isSubagentPath(
                Path.of("/home/user/.claude/projects/proj/abc-123.jsonl")));
        assertFalse(ClaudeSessionScanner.isSubagentPath(
                Path.of("/home/user/.claude/projects/proj/subagents/not-agent.jsonl")));
    }
}
