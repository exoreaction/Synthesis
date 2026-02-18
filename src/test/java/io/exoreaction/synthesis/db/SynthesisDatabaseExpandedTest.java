package io.exoreaction.synthesis.db;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Expanded tests for SynthesisDatabase — connection management, migration tables,
 * database size, cleanup, and multi-instance independence.
 */
class SynthesisDatabaseExpandedTest {

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

    // --- Connection and initialization ---

    @Test
    void getConnection_returnsNonNull() throws SQLException {
        Connection conn = db.getConnection();
        assertNotNull(conn, "getConnection should return a non-null connection");
    }

    @Test
    void getConnection_isNotClosed() throws SQLException {
        assertFalse(db.getConnection().isClosed(), "Connection should be open");
    }

    @Test
    void isClosed_beforeClose_returnsFalse() {
        assertFalse(db.isClosed(), "Database should not be closed initially");
    }

    @Test
    void close_thenIsClosed_returnsTrue() throws SQLException {
        db.close();
        assertTrue(db.isClosed(), "Database should be closed after close()");
    }

    @Test
    void getDbPath_returnsCorrectPath() {
        Path expected = tempDir.resolve("test.db");
        assertEquals(expected, db.getDbPath());
    }

    // --- Flyway migrations — all tables exist ---

    @ParameterizedTest
    @ValueSource(strings = {
        "metrics",
        "file_movements",
        "file_audit_log",
        "workspace_snapshots",
        "change_events"
    })
    void migration_tableExists(String tableName) throws SQLException {
        Connection conn = db.getConnection();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, tableName, new String[]{"TABLE"})) {
            assertTrue(rs.next(), "Table '" + tableName + "' should exist after migration");
        }
    }

    // --- Database operations work after migration ---

    @Test
    void connection_canQueryMetrics_afterMigration() throws SQLException {
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM metrics");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "Fresh database should have 0 metrics");
        }
    }

    @Test
    void connection_canQuerySnapshots_afterMigration() throws SQLException {
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM workspace_snapshots");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "Fresh database should have 0 snapshots");
        }
    }

    @Test
    void connection_canQueryFileMovements_afterMigration() throws SQLException {
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM file_movements");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "Fresh database should have 0 file movements");
        }
    }

    // --- Multiple databases in same test run ---

    @Test
    void twoDatabases_independentlyMigrated(@TempDir Path dir2) throws SQLException {
        SynthesisDatabase db2 = new SynthesisDatabase(dir2.resolve("other.db"));
        try {
            assertNotNull(db2.getConnection());
            assertNotEquals(db.getDbPath(), db2.getDbPath(),
                    "Two databases should have different paths");
        } finally {
            db2.close();
        }
    }

    // --- getDatabaseSize ---

    @Test
    void getDatabaseSize_afterInit_isPositive() {
        long size = db.getDatabaseSize();
        assertTrue(size > 0, "Database file should have positive size after initialization");
    }

    // --- cleanupOldRecords (smoke test — no exception) ---

    @Test
    void cleanupOldRecords_onFreshDatabase_doesNotThrow() throws SQLException {
        assertDoesNotThrow(() -> db.cleanupOldRecords(),
                "cleanupOldRecords on fresh database should not throw");
    }

    // --- getConnection after close — reconnects ---

    @Test
    void getConnection_afterClose_reconnects() throws SQLException {
        db.close();
        // After close, next getConnection should reinitialize
        Connection conn = db.getConnection();
        assertNotNull(conn);
        assertFalse(conn.isClosed());
    }

    // --- getDefaultPath ---

    @Test
    void getDefaultPath_isInSynthesisDirectory() {
        Path defaultPath = SynthesisDatabase.getDefaultPath();
        assertNotNull(defaultPath);
        assertTrue(defaultPath.toString().contains(".synthesis"),
                "Default path should be in .synthesis directory");
        assertTrue(defaultPath.getFileName().toString().endsWith(".db"),
                "Default path should end with .db");
    }
}
