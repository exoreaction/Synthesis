package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FeedbackCommand} routing feedback logic (P3-08).
 */
class FeedbackCommandTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private FeedbackCommand.FeedbackRecorder recorder;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        recorder = new FeedbackCommand.FeedbackRecorder(db);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (db != null && !db.isClosed()) db.close();
    }

    @Test
    void recordAccept_insertsRow() throws SQLException {
        recorder.recordAccept("/ws", "proposal.pdf", "clients/greenfield");

        List<FeedbackRow> rows = queryFeedback("/ws", "proposal.pdf");

        assertEquals(1, rows.size());
        assertTrue(rows.get(0).accepted);
        assertEquals("clients/greenfield", rows.get(0).proposedDestination);
        assertEquals("clients/greenfield", rows.get(0).actualDestination);
    }

    @Test
    void recordReject_insertsRow() throws SQLException {
        recorder.recordReject("/ws", "proposal.pdf", "marketing");

        List<FeedbackRow> rows = queryFeedback("/ws", "proposal.pdf");

        assertEquals(1, rows.size());
        assertFalse(rows.get(0).accepted);
        assertEquals("marketing", rows.get(0).proposedDestination);
        assertNull(rows.get(0).actualDestination);
    }

    @Test
    void recordRejectWithCorrection_insertsRow() throws SQLException {
        recorder.recordReject("/ws", "proposal.pdf", "marketing", "clients/greenfield");

        List<FeedbackRow> rows = queryFeedback("/ws", "proposal.pdf");

        assertEquals(1, rows.size());
        assertFalse(rows.get(0).accepted);
        assertEquals("marketing", rows.get(0).proposedDestination);
        assertEquals("clients/greenfield", rows.get(0).actualDestination);
    }

    @Test
    void multipleFeedback_tracksHistory() throws SQLException {
        recorder.recordAccept("/ws", "file1.pdf", "dir/a");
        recorder.recordReject("/ws", "file2.pdf", "dir/b");
        recorder.recordAccept("/ws", "file3.pdf", "dir/c");

        assertEquals(1, queryFeedback("/ws", "file1.pdf").size());
        assertEquals(1, queryFeedback("/ws", "file2.pdf").size());
        assertEquals(1, queryFeedback("/ws", "file3.pdf").size());
    }

    @Test
    void countFeedback_correctCounts() throws SQLException {
        recorder.recordAccept("/ws", "f1.pdf", "dir/a");
        recorder.recordAccept("/ws", "f2.pdf", "dir/a");
        recorder.recordReject("/ws", "f3.pdf", "dir/b");

        assertEquals(2, recorder.countAccepted("/ws"));
        assertEquals(1, recorder.countRejected("/ws"));
    }

    @Test
    void sameFeedbackTwice_createsMultipleRecords() throws SQLException {
        recorder.recordAccept("/ws", "proposal.pdf", "clients/greenfield");
        recorder.recordReject("/ws", "proposal.pdf", "clients/greenfield");

        List<FeedbackRow> rows = queryFeedback("/ws", "proposal.pdf");
        assertEquals(2, rows.size(), "Should create separate records (audit trail)");
    }

    @Test
    void workspaceIsolation() throws SQLException {
        recorder.recordAccept("/ws1", "file.pdf", "dir/a");
        recorder.recordAccept("/ws2", "file.pdf", "dir/a");

        assertEquals(1, recorder.countAccepted("/ws1"));
        assertEquals(1, recorder.countAccepted("/ws2"));
    }

    @Test
    void feedbackTimestamp_withinWindow() throws SQLException {
        long before = Instant.now().getEpochSecond();
        recorder.recordAccept("/ws", "file.pdf", "dir/a");
        long after = Instant.now().getEpochSecond();

        List<FeedbackRow> rows = queryFeedback("/ws", "file.pdf");
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).timestamp >= before && rows.get(0).timestamp <= after,
                "Timestamp should be within test window");
    }

    @Test
    void getRecentFeedback_returnsAllEntries() throws SQLException {
        recorder.recordAccept("/ws", "f1.pdf", "dir/a");
        recorder.recordReject("/ws", "f2.pdf", "dir/b");
        recorder.recordAccept("/ws", "f3.pdf", "dir/c");

        List<FeedbackCommand.FeedbackEntry> recent = recorder.getRecentFeedback("/ws", 10);

        assertEquals(3, recent.size());
        // Verify all files are present (order depends on timestamp resolution)
        List<String> files = recent.stream().map(FeedbackCommand.FeedbackEntry::filePath).toList();
        assertTrue(files.contains("f1.pdf"));
        assertTrue(files.contains("f2.pdf"));
        assertTrue(files.contains("f3.pdf"));
    }

    @Test
    void getRecentFeedback_respectsLimit() throws SQLException {
        for (int i = 0; i < 20; i++) {
            recorder.recordAccept("/ws", "file" + i + ".pdf", "dir/a");
        }

        List<FeedbackCommand.FeedbackEntry> recent = recorder.getRecentFeedback("/ws", 5);
        assertEquals(5, recent.size());
    }

    // ---- Helpers ----

    private List<FeedbackRow> queryFeedback(String workspacePath, String filePath)
            throws SQLException {
        Connection conn = db.getConnection();
        String sql = "SELECT proposed_destination, actual_destination, accepted, timestamp "
                + "FROM routing_feedback WHERE workspace_path = ? AND file_path = ? "
                + "ORDER BY timestamp ASC";
        List<FeedbackRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new FeedbackRow(
                            rs.getString("proposed_destination"),
                            rs.getString("actual_destination"),
                            rs.getInt("accepted") == 1,
                            rs.getLong("timestamp")));
                }
            }
        }
        return rows;
    }

    record FeedbackRow(String proposedDestination, String actualDestination,
                       boolean accepted, long timestamp) {}
}
