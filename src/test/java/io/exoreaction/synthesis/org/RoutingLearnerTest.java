package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RoutingLearner} -- long-term learning from routing feedback.
 */
class RoutingLearnerTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private RoutingLearner learner;

    @BeforeEach
    void setup() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        db = new SynthesisDatabase(dbPath);
        learner = new RoutingLearner(db);
    }

    @AfterEach
    void teardown() throws Exception {
        if (db != null) db.close();
    }

    @Test
    void noFeedbackReturnsZeroAdjustment() throws SQLException {
        double adj = learner.computeConfidenceAdjustment("/workspace", "some/dir");
        assertEquals(0.0, adj, 0.001);
    }

    @Test
    void singleAcceptGivesPositiveAdjustment() throws SQLException {
        insertFeedback("/workspace", "file.txt", "some/dir", "some/dir", true);

        double adj = learner.computeConfidenceAdjustment("/workspace", "some/dir");
        assertEquals(RoutingLearner.POSITIVE_RATE, adj, 0.001);
    }

    @Test
    void singleRejectGivesNegativeAdjustment() throws SQLException {
        insertFeedback("/workspace", "file.txt", "some/dir", null, false);

        double adj = learner.computeConfidenceAdjustment("/workspace", "some/dir");
        assertEquals(RoutingLearner.NEGATIVE_RATE, adj, 0.001);
    }

    @Test
    void multipleAcceptsAccumulate() throws SQLException {
        insertFeedback("/workspace", "file1.txt", "some/dir", "some/dir", true);
        insertFeedback("/workspace", "file2.txt", "some/dir", "some/dir", true);
        insertFeedback("/workspace", "file3.txt", "some/dir", "some/dir", true);

        double adj = learner.computeConfidenceAdjustment("/workspace", "some/dir");
        assertEquals(3 * RoutingLearner.POSITIVE_RATE, adj, 0.001);
    }

    @Test
    void mixedFeedbackNets() throws SQLException {
        // 3 accepts + 1 reject
        insertFeedback("/workspace", "file1.txt", "some/dir", "some/dir", true);
        insertFeedback("/workspace", "file2.txt", "some/dir", "some/dir", true);
        insertFeedback("/workspace", "file3.txt", "some/dir", "some/dir", true);
        insertFeedback("/workspace", "file4.txt", "some/dir", null, false);

        double adj = learner.computeConfidenceAdjustment("/workspace", "some/dir");
        double expected = 3 * RoutingLearner.POSITIVE_RATE + RoutingLearner.NEGATIVE_RATE;
        assertEquals(expected, adj, 0.001);
    }

    @Test
    void adjustmentCappedAtMax() throws SQLException {
        // Insert many accepts to exceed cap
        for (int i = 0; i < 100; i++) {
            insertFeedback("/workspace", "file" + i + ".txt", "some/dir", "some/dir", true);
        }

        double adj = learner.computeConfidenceAdjustment("/workspace", "some/dir");
        assertTrue(adj <= RoutingLearner.MAX_ADJUSTMENT,
                "Adjustment should be capped at " + RoutingLearner.MAX_ADJUSTMENT);
    }

    @Test
    void adjustmentFlooredAtMin() throws SQLException {
        // Insert many rejects to go below floor
        for (int i = 0; i < 100; i++) {
            insertFeedback("/workspace", "file" + i + ".txt", "some/dir", null, false);
        }

        double adj = learner.computeConfidenceAdjustment("/workspace", "some/dir");
        assertTrue(adj >= RoutingLearner.MIN_ADJUSTMENT,
                "Adjustment should be floored at " + RoutingLearner.MIN_ADJUSTMENT);
    }

    @Test
    void differentDirectoriesIndependent() throws SQLException {
        insertFeedback("/workspace", "f1.txt", "dir-a", "dir-a", true);
        insertFeedback("/workspace", "f2.txt", "dir-a", "dir-a", true);
        insertFeedback("/workspace", "f3.txt", "dir-b", null, false);

        double adjA = learner.computeConfidenceAdjustment("/workspace", "dir-a");
        double adjB = learner.computeConfidenceAdjustment("/workspace", "dir-b");

        assertTrue(adjA > 0, "dir-a should have positive adjustment");
        assertTrue(adjB < 0, "dir-b should have negative adjustment");
    }

    @Test
    void differentWorkspacesIndependent() throws SQLException {
        insertFeedback("/workspace1", "f1.txt", "dir", "dir", true);
        insertFeedback("/workspace2", "f2.txt", "dir", null, false);

        double adj1 = learner.computeConfidenceAdjustment("/workspace1", "dir");
        double adj2 = learner.computeConfidenceAdjustment("/workspace2", "dir");

        assertTrue(adj1 > 0, "workspace1 should have positive adjustment");
        assertTrue(adj2 < 0, "workspace2 should have negative adjustment");
    }

    @Test
    void applyAdjustmentToConfidence() throws SQLException {
        insertFeedback("/workspace", "f1.txt", "dir", "dir", true);
        insertFeedback("/workspace", "f2.txt", "dir", "dir", true);

        double baseConfidence = 0.8;
        double adjusted = learner.adjustConfidence("/workspace", "dir", baseConfidence);
        double expected = baseConfidence + 2 * RoutingLearner.POSITIVE_RATE;

        assertEquals(expected, adjusted, 0.001);
    }

    @Test
    void adjustedConfidenceClampedToZeroOne() throws SQLException {
        // Many rejects on a low-confidence directory
        for (int i = 0; i < 50; i++) {
            insertFeedback("/workspace", "f" + i + ".txt", "dir", null, false);
        }

        double adjusted = learner.adjustConfidence("/workspace", "dir", 0.1);
        assertTrue(adjusted >= 0.0, "Adjusted confidence should not go below 0");
        assertTrue(adjusted <= 1.0, "Adjusted confidence should not exceed 1");
    }

    @Test
    void adjustedConfidenceDoesNotExceedOne() throws SQLException {
        for (int i = 0; i < 50; i++) {
            insertFeedback("/workspace", "f" + i + ".txt", "dir", "dir", true);
        }

        double adjusted = learner.adjustConfidence("/workspace", "dir", 0.95);
        assertTrue(adjusted <= 1.0, "Adjusted confidence should not exceed 1");
    }

    // ---- helper ----

    private void insertFeedback(String workspacePath, String filePath,
                                 String proposedDir, String actualDir,
                                 boolean accepted) throws SQLException {
        Connection conn = db.getConnection();
        String sql = "INSERT INTO routing_feedback "
                + "(workspace_path, file_path, proposed_destination, actual_destination, "
                + "accepted, confidence_delta, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, filePath);
            ps.setString(3, proposedDir);
            ps.setString(4, actualDir);
            ps.setInt(5, accepted ? 1 : 0);
            ps.setDouble(6, 0.0);
            ps.setLong(7, Instant.now().getEpochSecond());
            ps.executeUpdate();
        }
    }
}
