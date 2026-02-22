package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: verifies that Flyway V13 creates the 4 code knowledge graph tables.
 */
class CodeKnowledgeGraphMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void flyway_v13_creates_all_four_tables() throws SQLException {
        SynthesisDatabase db = new SynthesisDatabase(tempDir.resolve("test.db"));
        Connection conn = db.getConnection();

        Set<String> tables = getTableNames(conn);

        assertTrue(tables.contains("code_dependencies"),
                "code_dependencies table should exist. Found tables: " + tables);
        assertTrue(tables.contains("module_profiles"),
                "module_profiles table should exist. Found tables: " + tables);
        assertTrue(tables.contains("cross_format_links"),
                "cross_format_links table should exist. Found tables: " + tables);
        assertTrue(tables.contains("code_quality_gaps"),
                "code_quality_gaps table should exist. Found tables: " + tables);

        db.close();
    }

    @Test
    void code_dependencies_table_has_correct_columns() throws SQLException {
        SynthesisDatabase db = new SynthesisDatabase(tempDir.resolve("test.db"));
        Connection conn = db.getConnection();

        Set<String> columns = getColumnNames(conn, "code_dependencies");
        assertTrue(columns.contains("workspace_path"));
        assertTrue(columns.contains("repo_name"));
        assertTrue(columns.contains("source_file"));
        assertTrue(columns.contains("source_class"));
        assertTrue(columns.contains("source_package"));
        assertTrue(columns.contains("target_file"));
        assertTrue(columns.contains("target_class"));
        assertTrue(columns.contains("target_package"));
        assertTrue(columns.contains("dependency_type"));
        assertTrue(columns.contains("is_external"));
        assertTrue(columns.contains("last_computed"));

        db.close();
    }

    @Test
    void module_profiles_table_has_correct_columns() throws SQLException {
        SynthesisDatabase db = new SynthesisDatabase(tempDir.resolve("test.db"));
        Connection conn = db.getConnection();

        Set<String> columns = getColumnNames(conn, "module_profiles");
        assertTrue(columns.contains("workspace_path"));
        assertTrue(columns.contains("repo_name"));
        assertTrue(columns.contains("module_path"));
        assertTrue(columns.contains("package_name"));
        assertTrue(columns.contains("inferred_purpose"));
        assertTrue(columns.contains("fan_in"));
        assertTrue(columns.contains("fan_out"));
        assertTrue(columns.contains("instability"));
        assertTrue(columns.contains("total_files"));
        assertTrue(columns.contains("last_computed"));

        db.close();
    }

    @Test
    void cross_format_links_table_has_correct_columns() throws SQLException {
        SynthesisDatabase db = new SynthesisDatabase(tempDir.resolve("test.db"));
        Connection conn = db.getConnection();

        Set<String> columns = getColumnNames(conn, "cross_format_links");
        assertTrue(columns.contains("workspace_path"));
        assertTrue(columns.contains("source_file"));
        assertTrue(columns.contains("target_file"));
        assertTrue(columns.contains("link_type"));
        assertTrue(columns.contains("entity_name"));
        assertTrue(columns.contains("last_computed"));

        db.close();
    }

    @Test
    void code_quality_gaps_table_has_correct_columns() throws SQLException {
        SynthesisDatabase db = new SynthesisDatabase(tempDir.resolve("test.db"));
        Connection conn = db.getConnection();

        Set<String> columns = getColumnNames(conn, "code_quality_gaps");
        assertTrue(columns.contains("workspace_path"));
        assertTrue(columns.contains("repo_name"));
        assertTrue(columns.contains("module_path"));
        assertTrue(columns.contains("gap_type"));
        assertTrue(columns.contains("description"));
        assertTrue(columns.contains("severity"));
        assertTrue(columns.contains("file_path"));
        assertTrue(columns.contains("suggestion"));
        assertTrue(columns.contains("last_computed"));

        db.close();
    }

    @Test
    void unique_constraints_are_enforced_on_code_dependencies() throws SQLException {
        SynthesisDatabase db = new SynthesisDatabase(tempDir.resolve("test.db"));
        Connection conn = db.getConnection();
        long now = System.currentTimeMillis() / 1000;

        // Insert a dependency
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO code_dependencies (workspace_path, repo_name, source_file, source_class, source_package, " +
                "target_class, target_package, dependency_type, last_computed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "/workspace");
            ps.setString(2, "");
            ps.setString(3, "src/Foo.java");
            ps.setString(4, "Foo");
            ps.setString(5, "com.example");
            ps.setString(6, "Bar");
            ps.setString(7, "com.example.util");
            ps.setString(8, "import");
            ps.setLong(9, now);
            ps.executeUpdate();
        }

        // Insert a duplicate should fail
        assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO code_dependencies (workspace_path, repo_name, source_file, source_class, source_package, " +
                    "target_class, target_package, dependency_type, last_computed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, "/workspace");
                ps.setString(2, "");
                ps.setString(3, "src/Foo.java");
                ps.setString(4, "Foo");
                ps.setString(5, "com.example");
                ps.setString(6, "Bar");
                ps.setString(7, "com.example.util");
                ps.setString(8, "import");
                ps.setLong(9, now);
                ps.executeUpdate();
            }
        });

        db.close();
    }

    // ---- Helpers ----

    private Set<String> getTableNames(Connection conn) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        return tables;
    }

    private Set<String> getColumnNames(Connection conn, String tableName) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, "%")) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }
}
