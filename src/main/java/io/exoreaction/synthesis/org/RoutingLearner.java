package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.db.SynthesisDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Long-term learning from routing feedback.
 *
 * <p>Uses accumulated routing feedback (accept/reject decisions from the
 * {@code routing_feedback} table) to compute confidence adjustments for
 * directories. Directories with consistently confirmed routing decisions
 * get higher bid confidence; directories with rejected decisions get lower.
 *
 * <p>Learning rates (conservative to avoid oscillation):
 * <ul>
 *   <li>Positive reinforcement (accept): +0.02 per confirmed decision</li>
 *   <li>Negative reinforcement (reject): -0.01 per rejected decision</li>
 * </ul>
 *
 * <p>The asymmetry (positive stronger than negative) means that a directory
 * needs roughly 2 rejections to offset 1 acceptance, encouraging stability.
 *
 * <p>Adjustments are capped at +0.20 / -0.20 to prevent runaway values.
 *
 * @since v2.0 (P4-07)
 */
public class RoutingLearner {

    /** Confidence boost per accepted routing decision. */
    static final double POSITIVE_RATE = 0.02;

    /** Confidence penalty per rejected routing decision. */
    static final double NEGATIVE_RATE = -0.01;

    /** Maximum total positive adjustment. */
    static final double MAX_ADJUSTMENT = 0.20;

    /** Maximum total negative adjustment. */
    static final double MIN_ADJUSTMENT = -0.20;

    private final SynthesisDatabase database;

    public RoutingLearner(SynthesisDatabase database) {
        this.database = database;
    }

    /**
     * Computes the cumulative confidence adjustment for a directory based on
     * all routing feedback where this directory was the proposed destination.
     *
     * @param workspacePath the workspace path
     * @param directoryPath the directory path (relative to workspace)
     * @return adjustment value, clamped between MIN_ADJUSTMENT and MAX_ADJUSTMENT
     */
    public double computeConfidenceAdjustment(String workspacePath, String directoryPath)
            throws SQLException {
        Connection conn = database.getConnection();

        int accepted = 0;
        int rejected = 0;

        String sql = "SELECT accepted, COUNT(*) as cnt FROM routing_feedback "
                + "WHERE workspace_path = ? AND proposed_destination = ? "
                + "GROUP BY accepted";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, directoryPath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getInt("accepted") == 1) {
                        accepted = rs.getInt("cnt");
                    } else {
                        rejected = rs.getInt("cnt");
                    }
                }
            }
        }

        double adjustment = (accepted * POSITIVE_RATE) + (rejected * NEGATIVE_RATE);

        // Clamp to bounds
        return Math.min(MAX_ADJUSTMENT, Math.max(MIN_ADJUSTMENT, adjustment));
    }

    /**
     * Applies the learned confidence adjustment to a base confidence value.
     * The result is clamped to [0.0, 1.0].
     *
     * @param workspacePath the workspace path
     * @param directoryPath the directory path (relative to workspace)
     * @param baseConfidence the original confidence from the centroid
     * @return adjusted confidence, clamped to [0.0, 1.0]
     */
    public double adjustConfidence(String workspacePath, String directoryPath,
                                    double baseConfidence) throws SQLException {
        double adjustment = computeConfidenceAdjustment(workspacePath, directoryPath);
        double adjusted = baseConfidence + adjustment;
        return Math.min(1.0, Math.max(0.0, adjusted));
    }
}
