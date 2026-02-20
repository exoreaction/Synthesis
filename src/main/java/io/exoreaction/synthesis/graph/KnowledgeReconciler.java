package io.exoreaction.synthesis.graph;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Reconciles {@code knowledge_edges} after source files change.
 *
 * <p>When {@code maintain} re-indexes a source file, this class:
 * <ol>
 *   <li>Queries {@code knowledge_edges} for all edges pointing to that source</li>
 *   <li>Recomputes {@code drift_days} and {@code confidence} using current timestamps</li>
 *   <li>Updates the database</li>
 *   <li>Returns a list of edges whose confidence degraded (for warning output)</li>
 * </ol>
 */
public class KnowledgeReconciler {

    public record ReconcileResult(
        String skillPath,
        String sourcePath,
        String entityName,
        String oldConfidence,
        String newConfidence,
        int driftDays
    ) {
        /** True when the confidence level dropped (e.g. HIGH → MEDIUM). */
        public boolean isDegraded() {
            return confidenceOrdinal(newConfidence) < confidenceOrdinal(oldConfidence);
        }

        private static int confidenceOrdinal(String c) {
            return switch (c) {
                case "HIGH"   -> 3;
                case "MEDIUM" -> 2;
                case "LOW"    -> 1;
                default       -> 0; // STALE
            };
        }
    }

    /**
     * Reconcile all edges that reference any of the given source paths.
     *
     * @param changedSourcePaths relative paths of recently-changed or newly-added source files
     * @param conn               open SQLite connection
     * @param workspaceRoot      used to read file modification timestamps
     * @return edges whose confidence degraded (for warning output)
     */
    public List<ReconcileResult> reconcile(List<String> changedSourcePaths,
                                           Connection conn,
                                           Path workspaceRoot) throws SQLException {
        if (changedSourcePaths.isEmpty()) return List.of();

        List<ReconcileResult> degraded = new ArrayList<>();
        long now = System.currentTimeMillis();

        String selectSql =
            "SELECT id, skill_path, source_path, entity_name, skill_modified_at, confidence " +
            "FROM knowledge_edges WHERE source_path = ?";
        String updateSql =
            "UPDATE knowledge_edges SET source_modified_at=?, drift_days=?, confidence=?, " +
            "last_reconciled_at=? WHERE id=?";

        try (PreparedStatement sel = conn.prepareStatement(selectSql);
             PreparedStatement upd = conn.prepareStatement(updateSql)) {

            for (String sourcePath : changedSourcePaths) {
                sel.setString(1, sourcePath);
                try (ResultSet rs = sel.executeQuery()) {
                    while (rs.next()) {
                        long id             = rs.getLong("id");
                        String skillPath    = rs.getString("skill_path");
                        String entityName   = rs.getString("entity_name");
                        String oldConf      = rs.getString("confidence");
                        long skillModAt     = rs.getLong("skill_modified_at");

                        long sourceModAt = currentModTime(workspaceRoot.resolve(sourcePath), now);
                        long diffMs      = sourceModAt - skillModAt;
                        int  driftDays   = (int) (diffMs / 86_400_000L);
                        String newConf   = KnowledgeEdge.computeConfidence(driftDays);

                        upd.setLong(1, sourceModAt);
                        upd.setInt(2, driftDays);
                        upd.setString(3, newConf);
                        upd.setLong(4, now);
                        upd.setLong(5, id);
                        upd.addBatch();

                        ReconcileResult result = new ReconcileResult(
                            skillPath, sourcePath, entityName, oldConf, newConf, driftDays);
                        if (result.isDegraded()) {
                            degraded.add(result);
                        }
                    }
                }
            }
            upd.executeBatch();
        }
        return degraded;
    }

    /**
     * Return edges with LOW or STALE confidence, ordered by drift (most stale first).
     * Limited to 20 for display purposes.
     */
    public List<Map<String, Object>> queryStaleEdges(Connection conn) throws SQLException {
        String sql =
            "SELECT skill_path, source_path, entity_name, confidence, drift_days " +
            "FROM knowledge_edges " +
            "WHERE confidence IN ('LOW','STALE') " +
            "ORDER BY drift_days DESC LIMIT 20";
        List<Map<String, Object>> results = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("skillPath",  rs.getString("skill_path"));
                row.put("sourcePath", rs.getString("source_path"));
                row.put("entityName", rs.getString("entity_name"));
                row.put("confidence", rs.getString("confidence"));
                row.put("driftDays",  rs.getInt("drift_days"));
                results.add(row);
            }
        }
        return results;
    }

    private long currentModTime(Path file, long fallback) {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : fallback;
        } catch (IOException e) {
            return fallback;
        }
    }
}
