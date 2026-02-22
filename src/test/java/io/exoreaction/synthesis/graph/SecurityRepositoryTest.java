package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SecurityRepository} -- DAO for security analysis tables.
 *
 * @since v1.14.0 (Security)
 */
class SecurityRepositoryTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private Connection conn;
    private SecurityRepository repo;
    private static final String WS = "/test/workspace";
    private static final long NOW = Instant.now().getEpochSecond();

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        conn = db.getConnection();
        repo = new SecurityRepository();
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    // -----------------------------------------------------------------------
    // security_findings: CRUD
    // -----------------------------------------------------------------------

    @Test
    void upsertFinding_and_getFindings() throws SQLException {
        SecuritySignal signal = new SecuritySignal(
                "S001_SQL_INJECTION", "HIGH", "CWE-89",
                "src/Dao.java", 42, "Dao", "com.example.db",
                "String concatenation in SQL query",
                "\"SELECT * FROM \" + tableName",
                "Use PreparedStatement", null);

        repo.upsertFinding(conn, WS, signal, NOW);

        List<SecuritySignal> findings = repo.getFindings(conn, WS);
        assertEquals(1, findings.size());
        assertEquals("S001_SQL_INJECTION", findings.get(0).signalId());
        assertEquals("HIGH", findings.get(0).severity());
        assertEquals("CWE-89", findings.get(0).cweId());
        assertEquals("src/Dao.java", findings.get(0).filePath());
        assertEquals(42, findings.get(0).lineNumber());
    }

    @Test
    void upsertFinding_replaces_on_duplicate() throws SQLException {
        SecuritySignal signal1 = new SecuritySignal(
                "S001_SQL_INJECTION", "HIGH", "CWE-89",
                "src/Dao.java", 42, "Dao", "com.example.db",
                "Old description", null, "Old suggestion", null);

        SecuritySignal signal2 = new SecuritySignal(
                "S001_SQL_INJECTION", "HIGH", "CWE-89",
                "src/Dao.java", 42, "Dao", "com.example.db",
                "Updated description", "new evidence", "Updated suggestion", null);

        repo.upsertFinding(conn, WS, signal1, NOW);
        repo.upsertFinding(conn, WS, signal2, NOW + 100);

        List<SecuritySignal> findings = repo.getFindings(conn, WS);
        assertEquals(1, findings.size());
        assertEquals("Updated description", findings.get(0).description());
    }

    @Test
    void getFindingsBySeverity_filters_correctly() throws SQLException {
        repo.upsertFinding(conn, WS, new SecuritySignal(
                "S001_SQL_INJECTION", "HIGH", "CWE-89",
                "src/A.java", 1, "A", "com.a",
                "high finding", null, "fix", null), NOW);
        repo.upsertFinding(conn, WS, new SecuritySignal(
                "S003_WEAK_CRYPTO", "MEDIUM", "CWE-327",
                "src/B.java", 1, "B", "com.b",
                "medium finding", null, "fix", null), NOW);

        List<SecuritySignal> highOnly = repo.getFindingsBySeverity(conn, WS, "HIGH");
        assertEquals(1, highOnly.size());
        assertEquals("S001_SQL_INJECTION", highOnly.get(0).signalId());
    }

    @Test
    void deleteAllFindings_clears_workspace() throws SQLException {
        repo.upsertFinding(conn, WS, new SecuritySignal(
                "S001_SQL_INJECTION", "HIGH", "CWE-89",
                "src/A.java", 1, "A", "com.a",
                "finding", null, "fix", null), NOW);
        repo.upsertFinding(conn, WS, new SecuritySignal(
                "S002_HARDCODED_SECRET", "HIGH", "CWE-798",
                "src/B.java", 5, "B", "com.b",
                "secret", null, "fix", null), NOW);

        assertEquals(2, repo.countFindings(conn, WS));
        int deleted = repo.deleteAllFindings(conn, WS);
        assertEquals(2, deleted);
        assertEquals(0, repo.countFindings(conn, WS));
    }

    @Test
    void countFindings_returns_correct_count() throws SQLException {
        assertEquals(0, repo.countFindings(conn, WS));

        repo.upsertFinding(conn, WS, new SecuritySignal(
                "S001_SQL_INJECTION", "HIGH", "CWE-89",
                "src/A.java", 1, "A", "com.a",
                "finding", null, "fix", null), NOW);

        assertEquals(1, repo.countFindings(conn, WS));
    }

    // -----------------------------------------------------------------------
    // declared_dependencies: CRUD
    // -----------------------------------------------------------------------

    @Test
    void upsertDeclaredDependency_and_get() throws SQLException {
        DeclaredDependency dep = new DeclaredDependency(
                "org.apache.logging.log4j", "log4j-core",
                "2.14.0", "compile", "pom.xml");

        repo.upsertDeclaredDependency(conn, WS, dep, NOW);

        List<DeclaredDependency> deps = repo.getDeclaredDependencies(conn, WS);
        assertEquals(1, deps.size());
        assertEquals("log4j-core", deps.get(0).artifactId());
        assertEquals("2.14.0", deps.get(0).version());
    }

    @Test
    void countDependencies_returns_correct_count() throws SQLException {
        assertEquals(0, repo.countDependencies(conn, WS));

        repo.upsertDeclaredDependency(conn, WS, new DeclaredDependency(
                "org.example", "foo", "1.0", null, "pom.xml"), NOW);

        assertEquals(1, repo.countDependencies(conn, WS));
    }

    // -----------------------------------------------------------------------
    // attack_surface_edges: CRUD
    // -----------------------------------------------------------------------

    @Test
    void upsertAttackSurfaceEdge_and_get() throws SQLException {
        AttackSurfaceEdge edge = new AttackSurfaceEdge(
                "src/Cli.java", "Cli",
                "src/Dao.java", "Dao",
                "sql", 2, "Cli -> Service -> Dao");

        repo.upsertAttackSurfaceEdge(conn, WS, edge, NOW);

        List<AttackSurfaceEdge> edges = repo.getAttackSurfaceEdges(conn, WS);
        assertEquals(1, edges.size());
        assertEquals("Cli", edges.get(0).entryClass());
        assertEquals("Dao", edges.get(0).sinkClass());
        assertEquals("sql", edges.get(0).sinkType());
        assertEquals(2, edges.get(0).hopCount());
    }

    // -----------------------------------------------------------------------
    // workspace isolation
    // -----------------------------------------------------------------------

    @Test
    void different_workspaces_are_isolated() throws SQLException {
        String ws1 = "/workspace1";
        String ws2 = "/workspace2";

        repo.upsertFinding(conn, ws1, new SecuritySignal(
                "S001_SQL_INJECTION", "HIGH", "CWE-89",
                "src/A.java", 1, "A", "com.a",
                "finding", null, "fix", null), NOW);
        repo.upsertFinding(conn, ws2, new SecuritySignal(
                "S002_HARDCODED_SECRET", "HIGH", "CWE-798",
                "src/B.java", 1, "B", "com.b",
                "secret", null, "fix", null), NOW);

        assertEquals(1, repo.countFindings(conn, ws1));
        assertEquals(1, repo.countFindings(conn, ws2));

        repo.deleteAllFindings(conn, ws1);
        assertEquals(0, repo.countFindings(conn, ws1));
        assertEquals(1, repo.countFindings(conn, ws2));
    }
}
