package io.exoreaction.synthesis.db;

import org.flywaydb.core.Flyway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Shared SQLite database for all Synthesis persistent state:
 * metrics, file tracking, and change snapshots.
 *
 * <p>Uses Flyway for schema migrations and WAL mode for concurrent
 * read performance. Thread-safe: all public methods are synchronized.
 *
 * <p>Singleton per JVM -- use {@link #getDefault()} for the standard
 * location ({@code ~/.synthesis/synthesis.db}).
 */
public class SynthesisDatabase implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SynthesisDatabase.class.getName());
    private static final int RETENTION_DAYS = 90;

    private static volatile SynthesisDatabase defaultInstance;

    private final Path dbPath;
    private Connection connection;

    public SynthesisDatabase(Path dbPath) throws SQLException {
        this.dbPath = dbPath;
        initialize();
    }

    /**
     * Returns the default database instance at ~/.synthesis/synthesis.db.
     * Creates the database if it does not exist.
     */
    public static synchronized SynthesisDatabase getDefault() throws SQLException {
        if (defaultInstance == null || defaultInstance.isClosed()) {
            defaultInstance = new SynthesisDatabase(getDefaultPath());
        }
        return defaultInstance;
    }

    public static Path getDefaultPath() {
        return Path.of(System.getProperty("user.home"), ".synthesis", "synthesis.db");
    }

    private void initialize() throws SQLException {
        try {
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }

            Class.forName("org.sqlite.JDBC");

            String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();

            Flyway flyway = Flyway.configure()
                    .dataSource(url, null, null)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load();
            flyway.migrate();

            connection = DriverManager.getConnection(url);
            connection.setAutoCommit(true);

            // Enable WAL mode for better concurrent read performance
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }

            LOG.info("Synthesis database initialized: " + dbPath);
        } catch (IOException | ClassNotFoundException e) {
            throw new SQLException("Failed to initialize Synthesis database", e);
        }
    }

    /**
     * Returns the underlying JDBC connection for DAO use.
     * Callers must synchronize on this SynthesisDatabase instance
     * when performing multi-statement transactions.
     */
    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            initialize();
        }
        return connection;
    }

    public synchronized boolean isClosed() {
        try {
            return connection == null || connection.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    /**
     * Executes a cleanup of all tables with retention policy.
     */
    public synchronized void cleanupOldRecords() throws SQLException {
        long cutoff = Instant.now().minusSeconds(RETENTION_DAYS * 24L * 3600).getEpochSecond();

        String[] tables = {"metrics", "file_movements", "file_audit_log", "change_events"};
        for (String table : tables) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE timestamp < ?")) {
                ps.setLong(1, cutoff);
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    LOG.info("Cleaned " + deleted + " records from " + table);
                }
            } catch (SQLException e) {
                // Table may not exist yet if migrations haven't run
                LOG.fine("Cleanup skipped for " + table + ": " + e.getMessage());
            }
        }

        // Snapshots: delete old snapshots (CASCADE deletes entries)
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM workspace_snapshots WHERE snapshot_time < ?")) {
            ps.setLong(1, cutoff);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                LOG.info("Cleaned " + deleted + " old snapshots");
            }
        } catch (SQLException e) {
            LOG.fine("Snapshot cleanup skipped: " + e.getMessage());
        }
    }

    public Path getDbPath() {
        return dbPath;
    }

    public long getDatabaseSize() {
        try {
            return Files.size(dbPath);
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
            LOG.info("Synthesis database closed");
        }
    }
}
