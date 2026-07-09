package io.exoreaction.synthesis.memory;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MemoryStore} and {@link MemoryEntry} — hash-pinned episodic
 * memory with tamper detection and FTS recall (#371 item 3).
 */
class MemoryStoreTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private MemoryStore store;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        store = new MemoryStore(db);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null && !db.isClosed()) db.close();
    }

    // --- MemoryEntry ---

    @Test
    void entry_hashDeterministic() {
        var e1 = MemoryEntry.of("plan", "deploy auth", "{\"units\":[]}", "2026-07-09T10:00:00Z", null);
        var e2 = MemoryEntry.of("plan", "deploy auth", "{\"units\":[]}", "2026-07-09T11:00:00Z", null);
        assertEquals(e1.memoryId(), e2.memoryId(), "Same artifact = same id regardless of timestamp");
    }

    @Test
    void entry_differentArtifactDifferentId() {
        var e1 = MemoryEntry.of("plan", "task", "{\"a\":1}", "2026-07-09T10:00:00Z", null);
        var e2 = MemoryEntry.of("plan", "task", "{\"a\":2}", "2026-07-09T10:00:00Z", null);
        assertNotEquals(e1.memoryId(), e2.memoryId());
    }

    @Test
    void entry_verifyPassesForUntampered() {
        var entry = MemoryEntry.of("plan", "task", "{\"units\":[]}", "2026-07-09T10:00:00Z", null);
        assertTrue(entry.verify());
    }

    @Test
    void entry_verifyFailsForTampered() {
        var entry = new MemoryEntry(
                "wrong-hash", "plan", "task",
                null, null, null,
                "2026-07-09T10:00:00Z", "{\"units\":[]}", null);
        assertFalse(entry.verify());
    }

    @Test
    void entry_ofFull_includesProvenance() {
        var entry = MemoryEntry.ofFull("grounded-answer", "check compliance",
                "{\"grounding\":{\"status\":\"grounded\"}}", "2026-07-09T10:00:00Z",
                "/workspace", "knowledge.yaml", "abc123", "opts-key");
        assertEquals("grounded-answer", entry.kind());
        assertEquals("knowledge.yaml", entry.manifestSource());
        assertEquals("abc123", entry.manifestSha());
        assertEquals("opts-key", entry.optionsKey());
        assertTrue(entry.verify());
    }

    // --- Append + idempotency ---

    @Test
    void append_storesAndRetrieves() throws SQLException {
        var entry = MemoryEntry.of("plan", "deploy auth service",
                "{\"units\":[{\"id\":\"auth\",\"path\":\"auth.md\"}]}", "2026-07-09T10:00:00Z", "/ws");

        store.append(entry);

        var found = store.get(entry.memoryId());
        assertTrue(found.isPresent());
        assertEquals("plan", found.get().kind());
        assertEquals("deploy auth service", found.get().task());
        assertEquals("/ws", found.get().workspace());
    }

    @Test
    void append_idempotent() throws SQLException {
        var entry = MemoryEntry.of("plan", "task", "{\"a\":1}", "2026-07-09T10:00:00Z", null);

        store.append(entry);
        store.append(entry); // same artifact again

        assertEquals(1, store.count());
    }

    // --- Recall (FTS search) ---

    @Test
    void recall_findsMatchingTask() throws SQLException {
        store.append(MemoryEntry.of("plan", "deploy authentication service",
                "{\"auth\":true}", "2026-07-09T10:00:00Z", null));
        store.append(MemoryEntry.of("plan", "configure monitoring dashboard",
                "{\"monitoring\":true}", "2026-07-09T10:00:00Z", null));

        var hits = store.recall("authentication", 10);
        assertEquals(1, hits.size());
        assertEquals("deploy authentication service", hits.get(0).task());
    }

    @Test
    void recall_emptyQueryReturnsEmpty() throws SQLException {
        store.append(MemoryEntry.of("plan", "task", "{}", "2026-07-09T10:00:00Z", null));
        assertTrue(store.recall("", 10).isEmpty());
        assertTrue(store.recall(null, 10).isEmpty());
    }

    @Test
    void recall_dropsTamperedEntries() throws SQLException {
        // Insert a tampered entry directly via SQL
        try (var conn = db.getConnection();
             var ps = conn.prepareStatement("""
                     INSERT INTO memories (memory_id, kind, task, recorded_at, artifact_json)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, "fake-hash");
            ps.setString(2, "plan");
            ps.setString(3, "tampered deploy task");
            ps.setString(4, "2026-07-09T10:00:00Z");
            ps.setString(5, "{\"tampered\":true}");
            ps.executeUpdate();
        }

        var hits = store.recall("tampered deploy", 10);
        assertTrue(hits.isEmpty(), "Tampered entries should be silently dropped (fail-closed)");
    }

    @Test
    void recall_respectsLimit() throws SQLException {
        for (int i = 0; i < 5; i++) {
            store.append(MemoryEntry.of("plan", "deploy service " + i,
                    "{\"i\":" + i + "}", "2026-07-09T10:00:00Z", null));
        }

        var hits = store.recall("deploy service", 2);
        assertEquals(2, hits.size());
    }

    // --- List ---

    @Test
    void list_allMemories() throws SQLException {
        store.append(MemoryEntry.of("plan", "task A", "{\"a\":1}", "2026-07-09T10:00:00Z", "/ws1"));
        store.append(MemoryEntry.of("plan", "task B", "{\"b\":2}", "2026-07-09T11:00:00Z", "/ws2"));

        var all = store.list(null, 100);
        assertEquals(2, all.size());
    }

    @Test
    void list_filteredByWorkspace() throws SQLException {
        store.append(MemoryEntry.of("plan", "task A", "{\"a\":1}", "2026-07-09T10:00:00Z", "/ws1"));
        store.append(MemoryEntry.of("plan", "task B", "{\"b\":2}", "2026-07-09T10:00:00Z", "/ws2"));

        var filtered = store.list("/ws1", 100);
        assertEquals(1, filtered.size());
        assertEquals("task A", filtered.get(0).task());
    }

    // --- Forget ---

    @Test
    void forget_removesEntry() throws SQLException {
        var entry = MemoryEntry.of("plan", "task", "{}", "2026-07-09T10:00:00Z", null);
        store.append(entry);
        assertEquals(1, store.count());

        assertTrue(store.forget(entry.memoryId()));
        assertEquals(0, store.count());
    }

    @Test
    void forget_nonExistent() throws SQLException {
        assertFalse(store.forget("does-not-exist"));
    }

    // --- Count ---

    @Test
    void count_empty() throws SQLException {
        assertEquals(0, store.count());
    }

    @Test
    void count_afterAppend() throws SQLException {
        store.append(MemoryEntry.of("plan", "t1", "{\"x\":1}", "2026-07-09T10:00:00Z", null));
        store.append(MemoryEntry.of("grounded-answer", "t2", "{\"x\":2}", "2026-07-09T10:00:00Z", null));
        assertEquals(2, store.count());
    }

    // --- sanitizeFtsQuery ---

    @Test
    void sanitizeFtsQuery_stripsStopWords() {
        String result = MemoryStore.sanitizeFtsQuery("how does the auth service work");
        assertFalse(result.contains("how"));
        assertFalse(result.contains("does"));
        assertFalse(result.contains("the"));
        assertTrue(result.contains("auth"));
        assertTrue(result.contains("service"));
        assertTrue(result.contains("work"));
    }

    @Test
    void sanitizeFtsQuery_limitsTokens() {
        String result = MemoryStore.sanitizeFtsQuery(
                "alpha bravo charlie delta echo foxtrot golf hotel");
        long tokenCount = result.split("\\s+").length;
        assertTrue(tokenCount <= 6);
    }

    @Test
    void sanitizeFtsQuery_nullReturnsEmpty() {
        assertEquals("", MemoryStore.sanitizeFtsQuery(null));
    }
}
