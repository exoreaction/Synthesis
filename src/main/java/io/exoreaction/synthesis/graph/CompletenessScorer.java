package io.exoreaction.synthesis.graph;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Computes module completeness score: ratio of satisfied checkpoints to total checkpoints.
 * Analogous to DirectoryWants.satisfaction for document directories.
 *
 * <p>Score = 1.0 - (weighted gap penalty):
 * <ul>
 *   <li>HIGH gap   = -0.30 penalty</li>
 *   <li>MEDIUM gap = -0.15 penalty</li>
 *   <li>LOW gap    = -0.05 penalty</li>
 * </ul>
 * <p>Floor: 0.0 (cannot go negative)
 *
 * @since v1.12.2 (CKG-3.02)
 */
public class CompletenessScorer {

    private static final Logger LOG = Logger.getLogger(CompletenessScorer.class.getName());

    private static final double HIGH_PENALTY = 0.30;
    private static final double MEDIUM_PENALTY = 0.15;
    private static final double LOW_PENALTY = 0.05;

    /**
     * Compute completeness score for a single module based on its detected gaps.
     *
     * @param gaps list of gaps for this module (may be empty)
     * @return score 0.0 (many gaps) to 1.0 (no gaps)
     */
    public double score(List<QualityGap> gaps) {
        if (gaps == null || gaps.isEmpty()) {
            return 1.0;
        }

        double totalPenalty = 0.0;
        for (QualityGap gap : gaps) {
            totalPenalty += switch (gap.severity()) {
                case "HIGH" -> HIGH_PENALTY;
                case "MEDIUM" -> MEDIUM_PENALTY;
                case "LOW" -> LOW_PENALTY;
                default -> 0.0;
            };
        }

        return Math.max(0.0, 1.0 - totalPenalty);
    }

    /**
     * Compute completeness scores for all modules and update module_profiles.completeness_score.
     *
     * <p>Lazily adds the {@code completeness_score} column to {@code module_profiles}
     * via ALTER TABLE if it does not already exist.
     *
     * @param workspacePath the workspace root path string
     * @param conn          open SQLite connection
     * @param gapsByModule  gaps grouped by module path
     */
    public void computeAndPersistAll(String workspacePath, Connection conn,
                                      Map<String, List<QualityGap>> gapsByModule)
            throws SQLException {

        // Lazily add completeness_score column if it does not exist
        ensureCompletenessColumn(conn);

        // First, set all modules to 1.0 (no gaps = perfect score)
        String resetSql = """
            UPDATE module_profiles SET completeness_score = 1.0
            WHERE workspace_path = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(resetSql)) {
            ps.setString(1, workspacePath);
            ps.executeUpdate();
        }

        // Then update modules that have gaps
        String updateSql = """
            UPDATE module_profiles SET completeness_score = ?
            WHERE workspace_path = ? AND module_path = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            for (Map.Entry<String, List<QualityGap>> entry : gapsByModule.entrySet()) {
                double moduleScore = score(entry.getValue());
                ps.setDouble(1, moduleScore);
                ps.setString(2, workspacePath);
                ps.setString(3, entry.getKey());
                ps.executeUpdate();
            }
        }

        LOG.fine("Updated completeness scores for " + gapsByModule.size()
                + " module(s) in " + workspacePath);
    }

    /**
     * Adds the completeness_score column to module_profiles if it does not exist.
     * Uses ALTER TABLE which will fail silently if the column already exists.
     */
    private void ensureCompletenessColumn(Connection conn) throws SQLException {
        try {
            conn.createStatement().execute(
                    "ALTER TABLE module_profiles ADD COLUMN completeness_score REAL DEFAULT 1.0");
        } catch (SQLException e) {
            // Column already exists -- expected and safe to ignore
            if (!e.getMessage().contains("duplicate column")) {
                throw e;
            }
        }
    }
}
