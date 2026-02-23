package io.exoreaction.synthesis.graph;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Data access object for security analysis tables.
 *
 * <p>Provides CRUD operations for {@code security_findings},
 * {@code declared_dependencies}, and {@code attack_surface_edges}.
 * All methods accept an explicit {@link Connection} so callers
 * control transaction boundaries.
 *
 * @see SecurityAnalyzer
 * @since v1.14.0 (Security)
 */
public class SecurityRepository {

    private static final Logger LOG = Logger.getLogger(SecurityRepository.class.getName());

    // -----------------------------------------------------------------------
    // security_findings
    // -----------------------------------------------------------------------

    /**
     * Inserts or replaces a security finding.
     */
    public void upsertFinding(Connection conn, String workspacePath,
                               SecuritySignal signal, long lastComputed) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO security_findings (
                workspace_path, signal_id, severity, cwe_id,
                file_path, line_number, class_name, package_name,
                description, evidence, suggestion, flow_type,
                suppressed, last_computed
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, signal.signalId());
            ps.setString(3, signal.severity());
            ps.setString(4, signal.cweId());
            ps.setString(5, signal.filePath());
            ps.setInt(6, signal.lineNumber());
            ps.setString(7, signal.className());
            ps.setString(8, signal.packageName());
            ps.setString(9, signal.description());
            ps.setString(10, signal.evidence());
            ps.setString(11, signal.suggestion());
            ps.setString(12, signal.flowType());
            ps.setLong(13, lastComputed);
            ps.executeUpdate();
        }
    }

    /**
     * Deletes all findings for a workspace. Used before re-analysis.
     */
    public int deleteAllFindings(Connection conn, String workspacePath) throws SQLException {
        String sql = "DELETE FROM security_findings WHERE workspace_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            return ps.executeUpdate();
        }
    }

    /**
     * Returns all findings for a workspace, sorted by severity (HIGH first).
     */
    public List<SecuritySignal> getFindings(Connection conn, String workspacePath) throws SQLException {
        String sql = """
            SELECT signal_id, severity, cwe_id, file_path, line_number,
                   class_name, package_name, description, evidence, suggestion, flow_type
            FROM security_findings
            WHERE workspace_path = ? AND suppressed = 0
            ORDER BY CASE severity
                WHEN 'HIGH' THEN 0
                WHEN 'MEDIUM' THEN 1
                WHEN 'LOW' THEN 2
                WHEN 'INFO' THEN 3
                ELSE 4 END,
                signal_id, file_path
            """;
        return queryFindings(conn, sql, workspacePath);
    }

    /**
     * Returns findings filtered by severity.
     */
    public List<SecuritySignal> getFindingsBySeverity(Connection conn, String workspacePath,
                                                       String severity) throws SQLException {
        String sql = """
            SELECT signal_id, severity, cwe_id, file_path, line_number,
                   class_name, package_name, description, evidence, suggestion, flow_type
            FROM security_findings
            WHERE workspace_path = ? AND severity = ? AND suppressed = 0
            ORDER BY signal_id, file_path
            """;
        List<SecuritySignal> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, severity);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapSignal(rs));
                }
            }
        }
        return results;
    }

    /**
     * Counts total non-suppressed findings for a workspace.
     */
    public int countFindings(Connection conn, String workspacePath) throws SQLException {
        String sql = "SELECT COUNT(*) FROM security_findings WHERE workspace_path = ? AND suppressed = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Counts non-suppressed findings grouped by severity for a workspace.
     *
     * @return a map from severity (HIGH, MEDIUM, LOW, INFO) to count
     */
    public java.util.Map<String, Integer> countFindingsBySeverity(Connection conn,
                                                                    String workspacePath) throws SQLException {
        String sql = """
            SELECT severity, COUNT(*) AS cnt
            FROM security_findings
            WHERE workspace_path = ? AND suppressed = 0
            GROUP BY severity
            """;
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString("severity"), rs.getInt("cnt"));
                }
            }
        }
        return counts;
    }

    /**
     * Counts non-suppressed findings grouped by flow type for a workspace.
     * Agentic flow types include "agentic"; all other values are classified as traditional.
     *
     * @return a map from flow type to count (keys: actual flow_type values from DB)
     */
    public java.util.Map<String, Integer> countFindingsByFlowType(Connection conn,
                                                                    String workspacePath) throws SQLException {
        String sql = """
            SELECT COALESCE(flow_type, 'unknown') AS ft, COUNT(*) AS cnt
            FROM security_findings
            WHERE workspace_path = ? AND suppressed = 0
            GROUP BY ft
            """;
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString("ft"), rs.getInt("cnt"));
                }
            }
        }
        return counts;
    }

    /**
     * Returns the top N signals by finding count for a workspace, ordered by count descending.
     *
     * @return list of signal summaries (signalId, count, flowType)
     */
    public List<SignalSummary> getTopSignals(Connection conn, String workspacePath,
                                              int limit) throws SQLException {
        String sql = """
            SELECT signal_id, COUNT(*) AS cnt,
                   MAX(flow_type) AS flow_type
            FROM security_findings
            WHERE workspace_path = ? AND suppressed = 0
            GROUP BY signal_id
            ORDER BY cnt DESC
            LIMIT ?
            """;
        List<SignalSummary> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new SignalSummary(
                            rs.getString("signal_id"),
                            rs.getInt("cnt"),
                            rs.getString("flow_type")));
                }
            }
        }
        return results;
    }

    /**
     * Summary of a single signal across all findings.
     */
    public record SignalSummary(String signalId, int count, String flowType) {}

    // -----------------------------------------------------------------------
    // declared_dependencies
    // -----------------------------------------------------------------------

    /**
     * Inserts or replaces a declared dependency.
     */
    public void upsertDeclaredDependency(Connection conn, String workspacePath,
                                          DeclaredDependency dep, long lastComputed) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO declared_dependencies (
                workspace_path, group_id, artifact_id, version,
                scope, build_file, last_computed
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, dep.groupId());
            ps.setString(3, dep.artifactId());
            ps.setString(4, dep.version());
            ps.setString(5, dep.scope());
            ps.setString(6, dep.buildFile());
            ps.setLong(7, lastComputed);
            ps.executeUpdate();
        }
    }

    /**
     * Returns all declared dependencies for a workspace.
     */
    public List<DeclaredDependency> getDeclaredDependencies(Connection conn,
                                                             String workspacePath) throws SQLException {
        String sql = """
            SELECT group_id, artifact_id, version, scope, build_file
            FROM declared_dependencies
            WHERE workspace_path = ?
            ORDER BY group_id, artifact_id
            """;
        List<DeclaredDependency> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new DeclaredDependency(
                            rs.getString("group_id"),
                            rs.getString("artifact_id"),
                            rs.getString("version"),
                            rs.getString("scope"),
                            rs.getString("build_file")
                    ));
                }
            }
        }
        return results;
    }

    /**
     * Counts declared dependencies for a workspace.
     */
    public int countDependencies(Connection conn, String workspacePath) throws SQLException {
        String sql = "SELECT COUNT(*) FROM declared_dependencies WHERE workspace_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // -----------------------------------------------------------------------
    // attack_surface_edges
    // -----------------------------------------------------------------------

    /**
     * Inserts or replaces an attack surface edge.
     */
    public void upsertAttackSurfaceEdge(Connection conn, String workspacePath,
                                          AttackSurfaceEdge edge, long lastComputed) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO attack_surface_edges (
                workspace_path, entry_file, entry_class,
                sink_file, sink_class, sink_type,
                hop_count, path_summary, last_computed
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, edge.entryFile());
            ps.setString(3, edge.entryClass());
            ps.setString(4, edge.sinkFile());
            ps.setString(5, edge.sinkClass());
            ps.setString(6, edge.sinkType());
            ps.setInt(7, edge.hopCount());
            ps.setString(8, edge.pathSummary());
            ps.setLong(9, lastComputed);
            ps.executeUpdate();
        }
    }

    /**
     * Returns all attack surface edges for a workspace.
     */
    public List<AttackSurfaceEdge> getAttackSurfaceEdges(Connection conn,
                                                           String workspacePath) throws SQLException {
        String sql = """
            SELECT entry_file, entry_class, sink_file, sink_class,
                   sink_type, hop_count, path_summary
            FROM attack_surface_edges
            WHERE workspace_path = ?
            ORDER BY hop_count, entry_file
            """;
        List<AttackSurfaceEdge> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new AttackSurfaceEdge(
                            rs.getString("entry_file"),
                            rs.getString("entry_class"),
                            rs.getString("sink_file"),
                            rs.getString("sink_class"),
                            rs.getString("sink_type"),
                            rs.getInt("hop_count"),
                            rs.getString("path_summary")
                    ));
                }
            }
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private List<SecuritySignal> queryFindings(Connection conn, String sql,
                                                String workspacePath) throws SQLException {
        List<SecuritySignal> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapSignal(rs));
                }
            }
        }
        return results;
    }

    private SecuritySignal mapSignal(ResultSet rs) throws SQLException {
        return new SecuritySignal(
                rs.getString("signal_id"),
                rs.getString("severity"),
                rs.getString("cwe_id"),
                rs.getString("file_path"),
                rs.getInt("line_number"),
                rs.getString("class_name"),
                rs.getString("package_name"),
                rs.getString("description"),
                rs.getString("evidence"),
                rs.getString("suggestion"),
                rs.getString("flow_type")
        );
    }
}
