package io.exoreaction.synthesis.kcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.core.FileMetadata;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Data access object for KCP (Knowledge Context Protocol) manifest tables.
 *
 * <p>Persists {@code kcp_manifests}, {@code kcp_units}, and
 * {@code kcp_relationships} rows from {@link AnalysisResult} objects produced
 * by {@code YamlAnalyzer} when it detects a {@code knowledge.yaml} KCP
 * manifest file.
 *
 * <p>All methods accept an explicit {@link Connection} so callers control
 * transaction boundaries.
 *
 * @since v1.20.0 (KCP Phase 3)
 */
public class KcpRepository {

    private static final Logger LOG = Logger.getLogger(KcpRepository.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Writes a KCP manifest, its units, and its relationships to the database.
     *
     * <p>This method is idempotent: existing rows for the same
     * (workspace, filePath) triple are replaced.
     *
     * @param conn          open database connection
     * @param workspacePath absolute path to the workspace root
     * @param metadata      file metadata for the {@code knowledge.yaml} file
     * @param analysis      analysis result produced by {@code YamlAnalyzer}
     *                      (must have {@code yamlType == "kcp-manifest"})
     */
    public void upsertFromAnalysis(Connection conn, String workspacePath,
                                   FileMetadata metadata, AnalysisResult analysis)
            throws SQLException {

        String filePath = metadata.path().toString();
        Map<String, Object> metrics = analysis.metrics();

        String project        = getString(metrics, "project");
        String kcpVersion     = getString(metrics, "kcp_version");
        if (kcpVersion == null) kcpVersion = getString(metrics, "kcpVersion");
        int unitCount         = getInt(metrics, "unitCount");
        int relCount          = getInt(metrics, "relationshipCount");
        long now              = System.currentTimeMillis();

        // 1. Upsert manifest row
        upsertManifest(conn, workspacePath, filePath, project, kcpVersion,
                unitCount, relCount, now);

        // 2. Delete existing units + relationships for this manifest (fresh write)
        deleteUnitsForManifest(conn, workspacePath, filePath);
        deleteRelationshipsForManifest(conn, workspacePath, filePath);

        // 3. Insert units
        @SuppressWarnings("unchecked")
        List<KcpUnit> units = (List<KcpUnit>) metrics.get("kcpUnits");
        if (units != null) {
            for (KcpUnit unit : units) {
                insertUnit(conn, workspacePath, filePath, unit, now);
            }
        }

        // 4. Insert relationships
        @SuppressWarnings("unchecked")
        List<KcpRelationship> rels = (List<KcpRelationship>) metrics.get("kcpRelationships");
        if (rels != null) {
            for (KcpRelationship rel : rels) {
                insertRelationship(conn, workspacePath, filePath, rel, now);
            }
        }
    }

    /**
     * Deletes all KCP records (manifest, units, relationships) for a specific
     * manifest file. Used when the file is deleted during an incremental update.
     */
    public void deleteForManifest(Connection conn, String workspacePath, String filePath)
            throws SQLException {
        deleteRelationshipsForManifest(conn, workspacePath, filePath);
        deleteUnitsForManifest(conn, workspacePath, filePath);
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM kcp_manifests WHERE workspace_path = ? AND file_path = ?")) {
            ps.setString(1, workspacePath);
            ps.setString(2, filePath);
            ps.executeUpdate();
        }
    }

    /**
     * Deletes all KCP records for an entire workspace. Used before a full re-scan.
     */
    public void deleteAllForWorkspace(Connection conn, String workspacePath) throws SQLException {
        for (String table : List.of("kcp_relationships", "kcp_units", "kcp_manifests")) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM " + table + " WHERE workspace_path = ?")) {
                ps.setString(1, workspacePath);
                ps.executeUpdate();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Query methods
    // -----------------------------------------------------------------------

    /** Returns all manifests indexed for the given workspace. */
    public List<KcpManifestRow> getManifests(Connection conn, String workspacePath)
            throws SQLException {
        String sql = """
                SELECT file_path, project, kcp_version, unit_count, relationship_count, last_computed
                FROM kcp_manifests
                WHERE workspace_path = ?
                ORDER BY project, file_path
                """;
        List<KcpManifestRow> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new KcpManifestRow(
                            rs.getString("file_path"),
                            rs.getString("project"),
                            rs.getString("kcp_version"),
                            rs.getInt("unit_count"),
                            rs.getInt("relationship_count"),
                            rs.getLong("last_computed")));
                }
            }
        }
        return result;
    }

    /** Returns all units for a given manifest file. */
    public List<KcpUnitRow> getUnitsForManifest(Connection conn,
                                                 String workspacePath,
                                                 String manifestFile) throws SQLException {
        String sql = """
                SELECT unit_id, path, intent, scope, audience_json, triggers_json, hints_json
                FROM kcp_units
                WHERE workspace_path = ? AND manifest_file = ?
                ORDER BY id
                """;
        List<KcpUnitRow> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, manifestFile);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new KcpUnitRow(
                            rs.getString("unit_id"),
                            rs.getString("path"),
                            rs.getString("intent"),
                            rs.getString("scope"),
                            rs.getString("audience_json"),
                            rs.getString("triggers_json"),
                            rs.getString("hints_json")));
                }
            }
        }
        return result;
    }

    /** Returns all relationships for a given manifest file. */
    public List<KcpRelationship> getRelationshipsForManifest(Connection conn,
                                                              String workspacePath,
                                                              String manifestFile)
            throws SQLException {
        String sql = """
                SELECT from_unit, to_unit, type
                FROM kcp_relationships
                WHERE workspace_path = ? AND manifest_file = ?
                ORDER BY id
                """;
        List<KcpRelationship> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, manifestFile);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new KcpRelationship(
                            rs.getString("from_unit"),
                            rs.getString("to_unit"),
                            rs.getString("type")));
                }
            }
        }
        return result;
    }

    /** Returns the number of indexed manifests for the workspace. */
    public int countManifests(Connection conn, String workspacePath) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM kcp_manifests WHERE workspace_path = ?")) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Returns the number of indexed units for the workspace. */
    public int countUnits(Connection conn, String workspacePath) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM kcp_units WHERE workspace_path = ?")) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // -----------------------------------------------------------------------
    // DTO records (returned from query methods)
    // -----------------------------------------------------------------------

    public record KcpManifestRow(
            String filePath,
            String project,
            String kcpVersion,
            int unitCount,
            int relationshipCount,
            long lastComputed) {}

    public record KcpUnitRow(
            String unitId,
            String path,
            String intent,
            String scope,
            String audienceJson,
            String triggersJson,
            String hintsJson) {}

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void upsertManifest(Connection conn, String workspacePath, String filePath,
                                 String project, String kcpVersion,
                                 int unitCount, int relCount, long now) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO kcp_manifests
                    (workspace_path, file_path, project, kcp_version,
                     unit_count, relationship_count, last_computed)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, filePath);
            ps.setString(3, project);
            ps.setString(4, kcpVersion);
            ps.setInt(5, unitCount);
            ps.setInt(6, relCount);
            ps.setLong(7, now);
            ps.executeUpdate();
        }
    }

    private void insertUnit(Connection conn, String workspacePath, String manifestFile,
                             KcpUnit unit, long now) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO kcp_units
                    (workspace_path, manifest_file, unit_id, path, intent, scope,
                     audience_json, triggers_json, hints_json, last_computed)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, manifestFile);
            ps.setString(3, unit.unitId());
            ps.setString(4, unit.path());
            ps.setString(5, unit.intent());
            ps.setString(6, unit.scope());
            ps.setString(7, toJson(unit.audience()));
            ps.setString(8, toJson(unit.triggers()));
            ps.setString(9, unit.hints() != null && !unit.hints().isEmpty()
                    ? toJson(unit.hints()) : null);
            ps.setLong(10, now);
            ps.executeUpdate();
        }
    }

    private void insertRelationship(Connection conn, String workspacePath, String manifestFile,
                                     KcpRelationship rel, long now) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO kcp_relationships
                    (workspace_path, manifest_file, from_unit, to_unit, type, last_computed)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, manifestFile);
            ps.setString(3, rel.fromUnit());
            ps.setString(4, rel.toUnit());
            ps.setString(5, rel.type());
            ps.setLong(6, now);
            ps.executeUpdate();
        }
    }

    private void deleteUnitsForManifest(Connection conn, String workspacePath,
                                         String manifestFile) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM kcp_units WHERE workspace_path = ? AND manifest_file = ?")) {
            ps.setString(1, workspacePath);
            ps.setString(2, manifestFile);
            ps.executeUpdate();
        }
    }

    private void deleteRelationshipsForManifest(Connection conn, String workspacePath,
                                                  String manifestFile) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM kcp_relationships WHERE workspace_path = ? AND manifest_file = ?")) {
            ps.setString(1, workspacePath);
            ps.setString(2, manifestFile);
            ps.executeUpdate();
        }
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private static int getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    private static String toJson(Object value) {
        if (value == null) return null;
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            LOG.warning("Failed to serialise KCP field to JSON: " + e.getMessage());
            return null;
        }
    }
}
