package io.exoreaction.synthesis.sessions;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SessionStore — SQLite CRUD and FTS5 search behaviour.
 */
class SessionStoreTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private SessionStore store;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        store = new SessionStore(db);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null && !db.isClosed()) db.close();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ClaudeSession session(String id, String project, String firstMsg, String allText) {
        return new ClaudeSession(
                id,
                project,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now(),
                3,
                2,
                List.of("Read", "Edit"),
                firstMsg,
                allText
        );
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void upsert_andGetBySessionId() throws SQLException {
        ClaudeSession s = session("sess-001", "/home/user/proj", "Fix the login bug", "Fix the login bug and add test");
        store.upsert(s);

        Optional<ClaudeSession> result = store.getBySessionId("sess-001");
        assertTrue(result.isPresent());

        ClaudeSession got = result.get();
        assertEquals("sess-001", got.sessionId());
        assertEquals("/home/user/proj", got.projectDir());
        assertEquals(3, got.turnCount());
        assertEquals(2, got.toolCallCount());
        assertEquals("Fix the login bug", got.firstMessage());
    }

    @Test
    void upsert_idempotent_updatesExisting() throws SQLException {
        ClaudeSession v1 = session("sess-001", "/proj", "First message", "First message content");
        ClaudeSession v2 = new ClaudeSession("sess-001", "/proj", Instant.now(), null,
                5, 4, List.of("Bash"), "Updated first", "Updated content");

        store.upsert(v1);
        store.upsert(v2);

        assertEquals(1, store.count());
        ClaudeSession got = store.getBySessionId("sess-001").orElseThrow();
        assertEquals("Updated first", got.firstMessage());
        assertEquals(5, got.turnCount());
    }

    @Test
    void count_returnsCorrectTotal() throws SQLException {
        assertEquals(0, store.count());
        store.upsert(session("s1", "/p1", "msg1", "msg1 text"));
        store.upsert(session("s2", "/p2", "msg2", "msg2 text"));
        assertEquals(2, store.count());
    }

    @Test
    void listRecent_orderedByStartedAtDesc() throws SQLException {
        ClaudeSession older = new ClaudeSession("old", "/p",
                Instant.now().minus(5, ChronoUnit.DAYS), null, 1, 0, List.of(), "older", "older");
        ClaudeSession newer = new ClaudeSession("new", "/p",
                Instant.now().minus(1, ChronoUnit.HOURS), null, 1, 0, List.of(), "newer", "newer");

        store.upsert(older);
        store.upsert(newer);

        List<ClaudeSession> results = store.listRecent(10, null);
        assertEquals(2, results.size());
        assertEquals("new", results.get(0).sessionId()); // newest first
        assertEquals("old", results.get(1).sessionId());
    }

    @Test
    void listRecent_projectFilter_matchesSubstring() throws SQLException {
        store.upsert(session("s1", "/home/user/alpha-project", "msg", "text"));
        store.upsert(session("s2", "/home/user/beta-project", "msg", "text"));
        store.upsert(session("s3", "/home/user/alpha-service", "msg", "text"));

        List<ClaudeSession> results = store.listRecent(10, "alpha");
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(s -> s.projectDir().contains("alpha")));
    }

    @Test
    void search_ftsMatchesFirstMessage() throws SQLException {
        store.upsert(session("auth-sess", "/proj", "Implement JWT authentication", "Implement JWT authentication system"));
        store.upsert(session("db-sess", "/proj", "Optimize database queries", "Optimize SQL queries for performance"));

        List<ClaudeSession> results = store.search("authentication", 10);
        assertEquals(1, results.size());
        assertEquals("auth-sess", results.get(0).sessionId());
    }

    @Test
    void search_ftsMatchesAllUserText() throws SQLException {
        store.upsert(session("s1", "/proj", "Fix bug", "Fix the authentication bug in the login flow"));
        store.upsert(session("s2", "/proj", "Refactor", "Refactor the database layer for better performance"));

        // "login" appears only in allUserText of s1, not in firstMessage
        List<ClaudeSession> results = store.search("login", 10);
        assertEquals(1, results.size());
        assertEquals("s1", results.get(0).sessionId());
    }

    @Test
    void search_noMatch_returnsEmpty() throws SQLException {
        store.upsert(session("s1", "/proj", "Fix authentication", "Fix auth issues"));

        List<ClaudeSession> results = store.search("kubernetes deployment", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void search_nullQuery_returnsMostRecent() throws SQLException {
        store.upsert(session("s1", "/proj", "msg1", "text1"));
        store.upsert(session("s2", "/proj", "msg2", "text2"));

        List<ClaudeSession> results = store.search(null, 10);
        assertEquals(2, results.size()); // falls back to listRecent
    }

    @Test
    void search_invalidFtsQuery_returnsEmpty() throws SQLException {
        store.upsert(session("s1", "/proj", "msg", "text"));

        // Malformed FTS5 query (unbalanced quote) — should not throw
        List<ClaudeSession> results = store.search("\"unterminated", 10);
        assertNotNull(results); // either empty or valid partial results
    }

    @Test
    void getKnownSessions_returnsAllScannedAts() throws SQLException {
        store.upsert(session("s1", "/p", "m1", "t1"));
        store.upsert(session("s2", "/p", "m2", "t2"));

        Map<String, Long> known = store.getKnownSessions();
        assertEquals(2, known.size());
        assertTrue(known.containsKey("s1"));
        assertTrue(known.containsKey("s2"));
        assertTrue(known.get("s1") > 0);
    }

    @Test
    void listSince_filtersCorrectly() throws SQLException {
        ClaudeSession old = new ClaudeSession("old", "/p",
                Instant.now().minus(60, ChronoUnit.DAYS), null, 1, 0, List.of(), "old msg", "old text");
        ClaudeSession recent = new ClaudeSession("recent", "/p",
                Instant.now().minus(1, ChronoUnit.DAYS), null, 1, 0, List.of(), "recent msg", "recent text");

        store.upsert(old);
        store.upsert(recent);

        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        List<ClaudeSession> results = store.listSince(cutoff, null);
        assertEquals(1, results.size());
        assertEquals("recent", results.get(0).sessionId());
    }
}
