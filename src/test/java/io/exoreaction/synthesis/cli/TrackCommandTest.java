package io.exoreaction.synthesis.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class SynthesisDatabaseTest {

    @TempDir
    Path tempDir;

    @Test
    void initialize_createsTables() throws SQLException {
        try (SynthesisDatabase db = new SynthesisDatabase(tempDir.resolve("test.db"))) {
            Connection conn = db.getConnection();

            // Verify core tables exist
            assertTableExists(conn, "metrics");
            assertTableExists(conn, "metadata");
            assertTableExists(conn, "file_movements");
            assertTableExists(conn, "file_audit_log");
            assertTableExists(conn, "workspace_snapshots");
            assertTableExists(conn, "snapshot_entries");
            assertTableExists(conn, "change_events");
        }
    }

    @Test
    void initialize_enablesWAL() throws SQLException {
        try (SynthesisDatabase db = new SynthesisDatabase(tempDir.resolve("test.db"))) {
            Connection conn = db.getConnection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA journal_mode")) {
                assertTrue(rs.next());
                assertEquals("wal", rs.getString(1));
            }
        }
    }

    @Test
    void close_and_reopen() throws SQLException {
        Path dbPath = tempDir.resolve("test.db");

        SynthesisDatabase db1 = new SynthesisDatabase(dbPath);
        assertFalse(db1.isClosed());
        db1.close();
        assertTrue(db1.isClosed());

        // Re-open same file
        try (SynthesisDatabase db2 = new SynthesisDatabase(dbPath)) {
            assertFalse(db2.isClosed());
            assertNotNull(db2.getConnection());
        }
    }

    @Test
    void getDatabaseSize_returnsPositive() throws SQLException {
        try (SynthesisDatabase db = new SynthesisDatabase(tempDir.resolve("test.db"))) {
            assertTrue(db.getDatabaseSize() > 0);
        }
    }

    private void assertTableExists(Connection conn, String tableName) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='" + tableName + "'")) {
            assertTrue(rs.next(), "Table '" + tableName + "' should exist");
        }
    }
}
