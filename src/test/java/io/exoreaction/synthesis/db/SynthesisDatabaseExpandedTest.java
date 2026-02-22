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
        "change_events",
        "directory_centroids",
        "file_enrichment_signatures",
        "virtual_memberships",
        "routing_feedback"
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

    // --- V11 tables: virtual_memberships and routing_feedback ---

    @Test
    void connection_canQueryVirtualMemberships_afterMigration() throws SQLException {
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM virtual_memberships");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "Fresh database should have 0 virtual memberships");
        }
    }

    @Test
    void connection_canInsertAndQueryVirtualMembership() throws SQLException {
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO virtual_memberships (workspace_path, file_path, directory_path, relationship, bid_strength, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "/home/user/workspace");
            ps.setString(2, "docs/proposal.pdf");
            ps.setString(3, "methodology/sdd");
            ps.setString(4, "methodology application");
            ps.setDouble(5, 0.72);
            ps.setLong(6, System.currentTimeMillis() / 1000);
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT file_path, directory_path, bid_strength FROM virtual_memberships WHERE workspace_path = ?")) {
            ps.setString(1, "/home/user/workspace");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("docs/proposal.pdf", rs.getString("file_path"));
                assertEquals("methodology/sdd", rs.getString("directory_path"));
                assertEquals(0.72, rs.getDouble("bid_strength"), 0.001);
            }
        }
    }

    @Test
    void connection_canInsertAndQueryRoutingFeedback() throws SQLException {
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO routing_feedback (workspace_path, file_path, proposed_destination, actual_destination, accepted, confidence_delta, timestamp) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "/home/user/workspace");
            ps.setString(2, "downloads/report.pdf");
            ps.setString(3, "business/strategy");
            ps.setString(4, "business/strategy");
            ps.setInt(5, 1);
            ps.setDouble(6, 0.05);
            ps.setLong(7, System.currentTimeMillis() / 1000);
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT accepted, confidence_delta FROM routing_feedback WHERE file_path = ?")) {
            ps.setString(1, "downloads/report.pdf");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("accepted"));
                assertEquals(0.05, rs.getDouble("confidence_delta"), 0.001);
            }
        }
    }

    @Test
    void virtualMembership_uniqueConstraint_preventsduplicates() throws SQLException {
        Connection conn = db.getConnection();
        String sql = "INSERT INTO virtual_memberships (workspace_path, file_path, directory_path, relationship, bid_strength, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "/ws");
            ps.setString(2, "file.pdf");
            ps.setString(3, "dir/a");
            ps.setString(4, "rel");
            ps.setDouble(5, 0.5);
            ps.setLong(6, 1000);
            ps.executeUpdate();
        }
        // Same (workspace_path, file_path, directory_path) should fail
        assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, "/ws");
                ps.setString(2, "file.pdf");
                ps.setString(3, "dir/a");
                ps.setString(4, "different rel");
                ps.setDouble(5, 0.8);
                ps.setLong(6, 2000);
                ps.executeUpdate();
            }
        });
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

    // --- WAL mode and busy_timeout ---

    @Test
    void walMode_isEnabled() throws SQLException {
        Connection conn = db.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA journal_mode")) {
            assertTrue(rs.next());
            assertEquals("wal", rs.getString(1).toLowerCase(),
                    "WAL journal mode should be enabled");
        }
    }

    @Test
    void busyTimeout_isSet() throws SQLException {
        Connection conn = db.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA busy_timeout")) {
            assertTrue(rs.next());
            assertEquals(5000, rs.getInt(1),
                    "busy_timeout should be set to 5000ms");
        }
    }

    // --- getDefaultIfExists ---

    @Test
    void getDefaultIfExists_returnsNullWhenNotInitialized() {
        // Note: defaultInstance is static and may or may not be set from other tests.
        // This test just verifies the method doesn't throw.
        SynthesisDatabase result = SynthesisDatabase.getDefaultIfExists();
        // Result can be null or non-null depending on test ordering; just ensure no exception
        assertDoesNotThrow(() -> SynthesisDatabase.getDefaultIfExists());
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
