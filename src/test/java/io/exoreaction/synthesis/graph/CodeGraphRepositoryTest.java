package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CrossFormatLinkRecord;
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
 * Tests for {@link CodeGraphRepository} -- DAO for code knowledge graph tables.
 */
class CodeGraphRepositoryTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private Connection conn;
    private CodeGraphRepository repo;
    private static final String WS = "/test/workspace";
    private static final long NOW = Instant.now().getEpochSecond();

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        conn = db.getConnection();
        repo = new CodeGraphRepository();
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    // -----------------------------------------------------------------------
    // code_dependencies: upsert and query
    // -----------------------------------------------------------------------

    @Test
    void upsertDependency_and_queryFrom() throws SQLException {
        CodeDependency dep = new CodeDependency(WS, "", "src/Foo.java", "Foo", "com.example",
                "src/Bar.java", "Bar", "com.example.util", "import", false, NOW);

        repo.upsertDependency(conn, dep);

        List<CodeDependency> results = repo.getDependenciesFrom(conn, WS, "src/Foo.java");
        assertEquals(1, results.size());
        assertEquals("Bar", results.get(0).targetClass());
        assertEquals("import", results.get(0).dependencyType());
        assertFalse(results.get(0).isExternal());
    }

    @Test
    void upsertDependency_replaces_on_duplicate() throws SQLException {
        CodeDependency dep1 = new CodeDependency(WS, "", "src/Foo.java", "Foo", "com.example",
                null, "Bar", "com.example.util", "import", true, NOW);
        CodeDependency dep2 = new CodeDependency(WS, "", "src/Foo.java", "Foo", "com.example",
                "src/Bar.java", "Bar", "com.example.util", "import", false, NOW + 100);

        repo.upsertDependency(conn, dep1);
        repo.upsertDependency(conn, dep2);

        List<CodeDependency> results = repo.getDependenciesFrom(conn, WS, "src/Foo.java");
        assertEquals(1, results.size());
        assertFalse(results.get(0).isExternal(), "Should be updated to non-external");
        assertEquals("src/Bar.java", results.get(0).targetFile());
    }

    @Test
    void getDependenciesTo_finds_callers() throws SQLException {
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/A.java", "A", "com.a",
                "src/Bar.java", "Bar", "com.bar", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/B.java", "B", "com.b",
                "src/Bar.java", "Bar", "com.bar", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/C.java", "C", "com.c",
                "src/Baz.java", "Baz", "com.baz", "import", false, NOW));

        List<CodeDependency> callers = repo.getDependenciesTo(conn, WS, "Bar", "com.bar");
        assertEquals(2, callers.size());
    }

    @Test
    void getDependenciesTo_without_package() throws SQLException {
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/A.java", "A", "com.a",
                null, "Bar", "com.bar", "import", false, NOW));

        List<CodeDependency> callers = repo.getDependenciesTo(conn, WS, "Bar", null);
        assertEquals(1, callers.size());
    }

    @Test
    void getIncomingForFile_finds_by_target_file() throws SQLException {
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/A.java", "A", "com.a",
                "src/Bar.java", "Bar", "com.bar", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/B.java", "B", "com.b",
                "src/Bar.java", "Bar", "com.bar", "extends", false, NOW));

        List<CodeDependency> incoming = repo.getIncomingForFile(conn, WS, "src/Bar.java");
        assertEquals(2, incoming.size());
    }

    @Test
    void deleteDependenciesForFile_removes_only_that_file() throws SQLException {
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/A.java", "A", "com.a",
                "src/Bar.java", "Bar", "com.bar", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/B.java", "B", "com.b",
                "src/Bar.java", "Bar", "com.bar", "import", false, NOW));

        int deleted = repo.deleteDependenciesForFile(conn, WS, "src/A.java");
        assertEquals(1, deleted);

        assertEquals(0, repo.getDependenciesFrom(conn, WS, "src/A.java").size());
        assertEquals(1, repo.getDependenciesFrom(conn, WS, "src/B.java").size());
    }

    @Test
    void deleteAllDependencies_clears_workspace() throws SQLException {
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/A.java", "A", "com.a",
                null, "Bar", "com.bar", "import", true, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/B.java", "B", "com.b",
                null, "Baz", "com.baz", "import", true, NOW));

        int deleted = repo.deleteAllDependencies(conn, WS);
        assertEquals(2, deleted);
        assertEquals(0, repo.countDependencies(conn, WS));
    }

    @Test
    void countDependencies_returns_correct_count() throws SQLException {
        assertEquals(0, repo.countDependencies(conn, WS));

        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/A.java", "A", "com.a",
                null, "Bar", "com.bar", "import", true, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/A.java", "A", "com.a",
                null, "Baz", "com.baz", "import", true, NOW));

        assertEquals(2, repo.countDependencies(conn, WS));
    }

    @Test
    void isPopulated_returns_false_when_empty() throws SQLException {
        assertFalse(repo.isPopulated(conn, WS));
    }

    @Test
    void isPopulated_returns_true_when_data_exists() throws SQLException {
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/A.java", "A", "com.a",
                null, "Bar", "com.bar", "import", true, NOW));

        assertTrue(repo.isPopulated(conn, WS));
    }

    // -----------------------------------------------------------------------
    // cross_format_links
    // -----------------------------------------------------------------------

    @Test
    void upsertCrossFormatLink_and_query() throws SQLException {
        CrossFormatLinkRecord link = new CrossFormatLinkRecord(
                WS, "db/V1__init.sql", "src/Dao.java",
                "table-reference", "users", NOW);

        repo.upsertCrossFormatLink(conn, link);

        List<CrossFormatLinkRecord> results = repo.getCrossFormatLinks(conn, WS);
        assertEquals(1, results.size());
        assertEquals("users", results.get(0).entityName());
        assertEquals("table-reference", results.get(0).linkType());
    }

    @Test
    void batchInsertCrossFormatLinks_inserts_in_batches() throws SQLException {
        List<CrossFormatLinkRecord> links = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            links.add(new CrossFormatLinkRecord(
                    WS, "V" + i + ".sql", "Dao" + i + ".java",
                    "table-reference", "table_" + i, NOW));
        }

        int inserted = repo.batchInsertCrossFormatLinks(conn, links, 10);

        // Should insert all 50 across 5 batches of 10
        assertTrue(inserted > 0, "Should insert at least some links");
        assertEquals(50, repo.countCrossFormatLinks(conn, WS));
    }

    @Test
    void batchInsertCrossFormatLinks_ignores_duplicates() throws SQLException {
        // Insert a link first
        repo.upsertCrossFormatLink(conn, new CrossFormatLinkRecord(
                WS, "V1.sql", "Dao.java", "table-reference", "users", NOW));

        // Try to batch-insert with the same link plus a new one
        List<CrossFormatLinkRecord> links = List.of(
                new CrossFormatLinkRecord(WS, "V1.sql", "Dao.java",
                        "table-reference", "users", NOW),
                new CrossFormatLinkRecord(WS, "V2.sql", "Repo.java",
                        "table-reference", "orders", NOW)
        );

        repo.batchInsertCrossFormatLinks(conn, links, 100);

        // Should have 2 total (duplicate ignored, new one added)
        assertEquals(2, repo.countCrossFormatLinks(conn, WS));
    }

    @Test
    void batchInsertCrossFormatLinks_empty_list_returns_zero() throws SQLException {
        int inserted = repo.batchInsertCrossFormatLinks(conn, List.of(), 100);
        assertEquals(0, inserted);
    }

    @Test
    void deleteAllCrossFormatLinks_clears_workspace() throws SQLException {
        repo.upsertCrossFormatLink(conn, new CrossFormatLinkRecord(
                WS, "V1.sql", "Dao.java", "table-reference", "users", NOW));
        repo.upsertCrossFormatLink(conn, new CrossFormatLinkRecord(
                WS, "V2.sql", "Repo.java", "table-reference", "orders", NOW));

        int deleted = repo.deleteAllCrossFormatLinks(conn, WS);
        assertEquals(2, deleted);
        assertEquals(0, repo.countCrossFormatLinks(conn, WS));
    }

    // -----------------------------------------------------------------------
    // workspace isolation
    // -----------------------------------------------------------------------

    @Test
    void different_workspaces_are_isolated() throws SQLException {
        String ws1 = "/workspace1";
        String ws2 = "/workspace2";

        repo.upsertDependency(conn, new CodeDependency(ws1, "", "src/A.java", "A", "com.a",
                null, "Bar", "com.bar", "import", true, NOW));
        repo.upsertDependency(conn, new CodeDependency(ws2, "", "src/X.java", "X", "com.x",
                null, "Yak", "com.yak", "import", true, NOW));

        assertEquals(1, repo.countDependencies(conn, ws1));
        assertEquals(1, repo.countDependencies(conn, ws2));

        repo.deleteAllDependencies(conn, ws1);
        assertEquals(0, repo.countDependencies(conn, ws1));
        assertEquals(1, repo.countDependencies(conn, ws2));
    }
}
