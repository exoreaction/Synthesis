package io.exoreaction.synthesis.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Flyway V10 migration: directory_centroids and file_enrichment_signatures tables.
 */
class V10DirectoryCentroidsMigrationTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null && !db.isClosed()) {
            db.close();
        }
    }

    @Test
    void directoryCentroidsTableExists() throws SQLException {
        Connection conn = db.getConnection();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, "directory_centroids", new String[]{"TABLE"})) {
            assertTrue(rs.next(), "Table 'directory_centroids' should exist after V10 migration");
        }
    }

    @Test
    void fileEnrichmentSignaturesTableExists() throws SQLException {
        Connection conn = db.getConnection();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, "file_enrichment_signatures", new String[]{"TABLE"})) {
            assertTrue(rs.next(), "Table 'file_enrichment_signatures' should exist after V10 migration");
        }
    }

    @Test
    void directoryCentroidsInsertAndQuery() throws SQLException {
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO directory_centroids " +
                "(workspace_path, directory_path, topics_json, entities_json, timeframe, " +
                "document_types_json, confidence, contributing_files, virtual_members, last_updated) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "/home/user/Documents");
            ps.setString(2, "clients/greenfield");
            ps.setString(3, "[\"renewable energy\", \"SDD\"]");
            ps.setString(4, "[\"GreenField Energy\"]");
            ps.setString(5, "2025-Q4 / 2026-Q1");
            ps.setString(6, "[\"proposal\", \"contract\"]");
            ps.setDouble(7, 0.87);
            ps.setInt(8, 8);
            ps.setInt(9, 2);
            ps.setLong(10, System.currentTimeMillis());
            assertEquals(1, ps.executeUpdate());
        }

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM directory_centroids WHERE directory_path = 'clients/greenfield'")) {
            assertTrue(rs.next());
            assertEquals(0.87, rs.getDouble("confidence"), 0.001);
            assertEquals(8, rs.getInt("contributing_files"));
            assertEquals("[\"renewable energy\", \"SDD\"]", rs.getString("topics_json"));
        }
    }

    @Test
    void fileEnrichmentSignaturesInsertAndQuery() throws SQLException {
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO file_enrichment_signatures " +
                "(workspace_path, file_path, topics_json, entities_json, document_type, " +
                "timeframe, enrichment_source, last_enriched) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "/home/user/Documents");
            ps.setString(2, "clients/greenfield/proposal.pdf");
            ps.setString(3, "[\"renewable energy\"]");
            ps.setString(4, "[\"GreenField Energy\"]");
            ps.setString(5, "proposal");
            ps.setString(6, "2026-Q1");
            ps.setString(7, "companion");
            ps.setLong(8, System.currentTimeMillis());
            assertEquals(1, ps.executeUpdate());
        }

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM file_enrichment_signatures WHERE file_path = 'clients/greenfield/proposal.pdf'")) {
            assertTrue(rs.next());
            assertEquals("proposal", rs.getString("document_type"));
            assertEquals("companion", rs.getString("enrichment_source"));
        }
    }

    @Test
    void directoryCentroidsUniqueConstraint() throws SQLException {
        Connection conn = db.getConnection();
        String sql = "INSERT INTO directory_centroids " +
                "(workspace_path, directory_path, confidence, contributing_files, virtual_members, last_updated) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "/ws");
            ps.setString(2, "dir/a");
            ps.setDouble(3, 0.5);
            ps.setInt(4, 3);
            ps.setInt(5, 0);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        }

        // Second insert with same workspace+directory should fail
        assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, "/ws");
                ps.setString(2, "dir/a");
                ps.setDouble(3, 0.8);
                ps.setInt(4, 5);
                ps.setInt(5, 1);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            }
        });
    }
}
