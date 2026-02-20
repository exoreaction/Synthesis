package io.exoreaction.synthesis.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeReconcilerTest {

    private Connection conn;
    private final KnowledgeReconciler reconciler = new KnowledgeReconciler();

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        conn.createStatement().execute(
            "CREATE TABLE knowledge_edges (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  skill_path TEXT NOT NULL," +
            "  source_path TEXT NOT NULL," +
            "  entity_name TEXT," +
            "  coverage_type TEXT DEFAULT 'mentioned'," +
            "  skill_modified_at INTEGER," +
            "  source_modified_at INTEGER," +
            "  drift_days INTEGER," +
            "  confidence TEXT DEFAULT 'HIGH'," +
            "  last_reconciled_at INTEGER," +
            "  UNIQUE(skill_path, source_path, entity_name)" +
            ")"
        );
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    private void insertEdge(String skillPath, String sourcePath, String entity,
                            long skillMod, long sourceMod, int drift, String confidence)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO knowledge_edges " +
                "(skill_path, source_path, entity_name, skill_modified_at, " +
                " source_modified_at, drift_days, confidence) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, skillPath);
            ps.setString(2, sourcePath);
            ps.setString(3, entity);
            ps.setLong(4, skillMod);
            ps.setLong(5, sourceMod);
            ps.setInt(6, drift);
            ps.setString(7, confidence);
            ps.executeUpdate();
        }
    }

    private String queryConfidence(String sourcePath) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT confidence FROM knowledge_edges WHERE source_path=?")) {
            ps.setString(1, sourcePath);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        }
    }

    // -----------------------------------------------------------------------
    // reconcile() tests
    // -----------------------------------------------------------------------

    @Test
    void reconcile_returnsEmptyForNoChangedPaths(@TempDir Path tmp) throws Exception {
        List<KnowledgeReconciler.ReconcileResult> r =
            reconciler.reconcile(Collections.emptyList(), conn, tmp);
        assertTrue(r.isEmpty());
    }

    @Test
    void reconcile_returnsEmptyWhenNoEdgesForPath(@TempDir Path tmp) throws Exception {
        List<KnowledgeReconciler.ReconcileResult> r =
            reconciler.reconcile(List.of("src/main/Missing.java"), conn, tmp);
        assertTrue(r.isEmpty());
    }

    @Test
    void reconcile_noChangeWhenSkillIsNewerThanSource(@TempDir Path tmp) throws Exception {
        // Skill was written TODAY (skill is fresh), source hasn't changed in 40 days.
        // drift = sourceModAt - skillModAt = fortyDaysAgo - now = negative → HIGH
        long now = System.currentTimeMillis();
        long fortyDaysAgo = now - 40L * 86_400_000L;
        insertEdge("docs/synthesis-dev.md", "src/main/Alpha.java", "Alpha",
            now,          // skill_modified_at (skill just written)
            fortyDaysAgo, // source_modified_at (not recently changed)
            0, "HIGH");

        Path sourceFile = tmp.resolve("src/main/Alpha.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "class Alpha {}");
        sourceFile.toFile().setLastModified(fortyDaysAgo); // source is old

        List<KnowledgeReconciler.ReconcileResult> results =
            reconciler.reconcile(List.of("src/main/Alpha.java"), conn, tmp);

        // drift < 0 → still HIGH — no degradation
        assertTrue(results.isEmpty(), "No degradation when skill is newer than source");
        assertEquals("HIGH", queryConfidence("src/main/Alpha.java"));
    }

    @Test
    void reconcile_detectsDegradationFromHighToStale(@TempDir Path tmp) throws Exception {
        long now = System.currentTimeMillis();
        // Skill was written 40 days ago; source was modified TODAY → drift = +40d → STALE
        long fortyDaysAgo = now - 40L * 86_400_000L;
        insertEdge("docs/guide.md", "src/main/Beta.java", "Beta",
            fortyDaysAgo, // skill_modified_at (skill is old)
            fortyDaysAgo, // source_modified_at (will be updated from disk)
            0, "HIGH");

        // Source file on disk was just modified
        Path sourceFile = tmp.resolve("src/main/Beta.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "class Beta {}");
        sourceFile.toFile().setLastModified(now); // source is fresh

        List<KnowledgeReconciler.ReconcileResult> results =
            reconciler.reconcile(List.of("src/main/Beta.java"), conn, tmp);

        assertFalse(results.isEmpty(), "Should detect degradation");
        KnowledgeReconciler.ReconcileResult r = results.get(0);
        assertEquals("HIGH", r.oldConfidence());
        assertEquals("STALE", r.newConfidence());
        assertTrue(r.isDegraded());
    }

    @Test
    void reconcile_noResultWhenConfidenceUnchanged(@TempDir Path tmp) throws Exception {
        // Edge already marked STALE — stays STALE → no degradation result
        long now = System.currentTimeMillis();
        long fortyDaysAgo = now - 40L * 86_400_000L;
        insertEdge("docs/guide.md", "src/main/Gamma.java", "Gamma",
            now, fortyDaysAgo, 40, "STALE");

        Path sourceFile = tmp.resolve("src/main/Gamma.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "class Gamma {}");
        sourceFile.toFile().setLastModified(fortyDaysAgo);

        List<KnowledgeReconciler.ReconcileResult> results =
            reconciler.reconcile(List.of("src/main/Gamma.java"), conn, tmp);

        assertTrue(results.isEmpty(), "No degradation if already at lowest confidence");
    }

    @Test
    void reconcile_handlesMultipleEdgesForSamePath(@TempDir Path tmp) throws Exception {
        long now = System.currentTimeMillis();
        long fortyDaysAgo = now - 40L * 86_400_000L;

        // Two skill files both written 40 days ago; source is fresh → both degrade to STALE
        insertEdge("docs/skill-a.md", "src/main/Delta.java", "Delta",
            fortyDaysAgo, fortyDaysAgo, 0, "HIGH");
        insertEdge("docs/skill-b.md", "src/main/Delta.java", "Delta",
            fortyDaysAgo, fortyDaysAgo, 0, "MEDIUM");

        Path sourceFile = tmp.resolve("src/main/Delta.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "class Delta {}");
        sourceFile.toFile().setLastModified(now); // source is current

        List<KnowledgeReconciler.ReconcileResult> results =
            reconciler.reconcile(List.of("src/main/Delta.java"), conn, tmp);

        // Both HIGH and MEDIUM degrade to STALE
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(KnowledgeReconciler.ReconcileResult::isDegraded));
    }

    @Test
    void reconcile_updatesDriftDaysInDatabase(@TempDir Path tmp) throws Exception {
        long now = System.currentTimeMillis();
        long eightDaysAgo = now - 8L * 86_400_000L;
        // Skill written 8 days ago, source updated NOW → drift = +8d → LOW
        insertEdge("docs/skill.md", "src/main/Eta.java", "Eta",
            eightDaysAgo, // skill_modified_at (written 8 days ago)
            eightDaysAgo, 0, "HIGH");

        Path sourceFile = tmp.resolve("src/main/Eta.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "class Eta {}");
        sourceFile.toFile().setLastModified(now); // source just updated

        reconciler.reconcile(List.of("src/main/Eta.java"), conn, tmp);

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT drift_days, confidence FROM knowledge_edges WHERE source_path=?")) {
            ps.setString(1, "src/main/Eta.java");
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            // 8 days drift → LOW confidence (7 < 8 ≤ 30)
            assertEquals("LOW", rs.getString("confidence"));
        }
    }

    // -----------------------------------------------------------------------
    // queryStaleEdges() tests
    // -----------------------------------------------------------------------

    @Test
    void queryStaleEdges_returnsLowAndStaleOnly() throws Exception {
        long now = System.currentTimeMillis();
        insertEdge("docs/a.md", "src/main/A.java", "A", now, now, 0, "HIGH");
        insertEdge("docs/b.md", "src/main/B.java", "B", now, now, 10, "LOW");
        insertEdge("docs/c.md", "src/main/C.java", "C", now, now, 35, "STALE");

        List<Map<String, Object>> stale = reconciler.queryStaleEdges(conn);
        assertEquals(2, stale.size());
        assertTrue(stale.stream().allMatch(m ->
            m.get("confidence").equals("LOW") || m.get("confidence").equals("STALE")));
    }

    @Test
    void queryStaleEdges_returnsEmptyWhenNoneStale() throws Exception {
        long now = System.currentTimeMillis();
        insertEdge("docs/a.md", "src/main/A.java", "A", now, now, 0, "HIGH");

        List<Map<String, Object>> stale = reconciler.queryStaleEdges(conn);
        assertTrue(stale.isEmpty());
    }

    // -----------------------------------------------------------------------
    // ReconcileResult tests
    // -----------------------------------------------------------------------

    @Test
    void isDegraded_trueWhenConfidenceLowers() {
        var r = new KnowledgeReconciler.ReconcileResult(
            "s", "p", "e", "HIGH", "LOW", 20);
        assertTrue(r.isDegraded());
    }

    @Test
    void isDegraded_falseWhenConfidenceUnchanged() {
        var r = new KnowledgeReconciler.ReconcileResult(
            "s", "p", "e", "MEDIUM", "MEDIUM", 5);
        assertFalse(r.isDegraded());
    }
}
