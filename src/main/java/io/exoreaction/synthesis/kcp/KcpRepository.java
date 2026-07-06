package io.exoreaction.synthesis.kcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.core.FileMetadata;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>Extended for KCP v0.21: temporal, content integrity, negative space,
 * content structure, and discovery provenance fields.
 *
 * @since v1.20.0 (KCP Phase 3), extended v1.29.0 (KCP v0.21 integration)
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

        // v0.21 manifest-level fields
        String signingAlgorithm = getString(metrics, "signingAlgorithm");
        String signingKeyId = getString(metrics, "signingKeyId");
        String signatureFile = getString(metrics, "signatureFile");
        String rootVerificationStatus = getString(metrics, "rootVerificationStatus");
        double rootConfidence = getDouble(metrics, "rootConfidence");
        String rootVerifiedBy = getString(metrics, "rootVerifiedBy");
        String rootVerifiedAt = getString(metrics, "rootVerifiedAt");
        String rootValidFrom = getString(metrics, "rootValidFrom");
        String rootValidUntil = getString(metrics, "rootValidUntil");
        String rootNotForJson = toJson(metrics.get("rootNotFor"));
        String rootCsPrimary = getString(metrics, "rootContentStructurePrimary");
        String rootCsDensity = getString(metrics, "rootContentStructureDensity");
        String rootExtensionsJson = getString(metrics, "rootExtensionsJson");

        // 1. Upsert manifest row
        upsertManifest(conn, workspacePath, filePath, project, kcpVersion,
                unitCount, relCount, now,
                signingAlgorithm, signingKeyId, signatureFile,
                rootVerificationStatus, rootConfidence, rootVerifiedBy, rootVerifiedAt,
                rootValidFrom, rootValidUntil, rootNotForJson,
                rootCsPrimary, rootCsDensity, rootExtensionsJson);

        // 2. Delete existing units + relationships + federation entries (fresh write)
        deleteUnitsForManifest(conn, workspacePath, filePath);
        deleteRelationshipsForManifest(conn, workspacePath, filePath);
        deleteFederationForManifest(conn, workspacePath, filePath);

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

        // 5. Insert federation entries (root manifests[] block, issue #355)
        @SuppressWarnings("unchecked")
        List<KcpFederationEntry> federation = (List<KcpFederationEntry>) metrics.get("kcpFederation");
        if (federation != null) {
            for (KcpFederationEntry entry : federation) {
                insertFederationEntry(conn, workspacePath, filePath, entry, now);
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
        deleteFederationForManifest(conn, workspacePath, filePath);
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
        for (String table : List.of("kcp_relationships", "kcp_units", "kcp_federation", "kcp_manifests")) {
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
                SELECT file_path, project, kcp_version, unit_count, relationship_count, last_computed,
                       signing_algorithm, signing_key_id, signature_file,
                       verification_status, confidence, verified_by, verified_at,
                       valid_from, valid_until, not_for_json,
                       content_structure_primary, content_structure_density,
                       root_extensions_json
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
                            rs.getLong("last_computed"),
                            rs.getString("signing_algorithm"),
                            rs.getString("signing_key_id"),
                            rs.getString("signature_file"),
                            rs.getString("verification_status"),
                            getDoubleOrNeg1(rs, "confidence"),
                            rs.getString("verified_by"),
                            rs.getString("verified_at"),
                            rs.getString("valid_from"),
                            rs.getString("valid_until"),
                            rs.getString("not_for_json"),
                            rs.getString("content_structure_primary"),
                            rs.getString("content_structure_density"),
                            rs.getString("root_extensions_json")));
                }
            }
        }
        return result;
    }

    /** Returns the federation entries (root manifests[] block) declared by a manifest. */
    public List<KcpFederationEntry> getFederationForManifest(Connection conn,
                                                              String workspacePath,
                                                              String manifestFile)
            throws SQLException {
        String sql = """
                SELECT entry_id, url, label, relationship, update_frequency, local_mirror,
                       context, version_pin, version_policy,
                       valid_from, valid_until, superseded_by,
                       agent_identity_json, extensions_json
                FROM kcp_federation
                WHERE workspace_path = ? AND manifest_file = ?
                ORDER BY id
                """;
        List<KcpFederationEntry> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, manifestFile);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new KcpFederationEntry(
                            rs.getString("entry_id"),
                            rs.getString("url"),
                            rs.getString("label"),
                            rs.getString("relationship"),
                            rs.getString("update_frequency"),
                            rs.getString("local_mirror"),
                            rs.getString("context"),
                            rs.getString("version_pin"),
                            rs.getString("version_policy"),
                            rs.getString("valid_from"),
                            rs.getString("valid_until"),
                            rs.getString("superseded_by"),
                            rs.getString("agent_identity_json"),
                            rs.getString("extensions_json")));
                }
            }
        }
        return result;
    }

    /** Returns all units for a given manifest file (extended with v0.21 fields). */
    public List<KcpUnitRow> getUnitsForManifest(Connection conn,
                                                 String workspacePath,
                                                 String manifestFile) throws SQLException {
        String sql = """
                SELECT unit_id, path, intent, scope, audience_json, triggers_json, hints_json,
                       valid_from, valid_until, recorded_at, superseded_by,
                       content_hash_algorithm, content_hash_value,
                       not_for_json, not_for_strict,
                       content_structure_primary, content_structure_density,
                       verification_status, confidence, verified_by, evidence,
                       extensions_json
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
                            rs.getString("hints_json"),
                            rs.getString("valid_from"),
                            rs.getString("valid_until"),
                            rs.getString("recorded_at"),
                            rs.getString("superseded_by"),
                            rs.getString("content_hash_algorithm"),
                            rs.getString("content_hash_value"),
                            rs.getString("not_for_json"),
                            rs.getInt("not_for_strict") == 1,
                            rs.getString("content_structure_primary"),
                            rs.getString("content_structure_density"),
                            rs.getString("verification_status"),
                            getDoubleOrNeg1(rs, "confidence"),
                            rs.getString("verified_by"),
                            rs.getString("evidence"),
                            rs.getString("extensions_json")));
                }
            }
        }
        return result;
    }

    /**
     * Returns units active at a given date (temporal filtering).
     * A unit is active if: valid_from <= asOf AND (valid_until IS NULL OR valid_until >= asOf).
     * Units with no temporal fields are always active.
     *
     * @param asOf ISO 8601 date string (YYYY-MM-DD) for point-in-time query
     */
    public List<KcpUnitRow> getActiveUnitsForManifest(Connection conn,
                                                       String workspacePath,
                                                       String manifestFile,
                                                       String asOf) throws SQLException {
        String sql = """
                SELECT unit_id, path, intent, scope, audience_json, triggers_json, hints_json,
                       valid_from, valid_until, recorded_at, superseded_by,
                       content_hash_algorithm, content_hash_value,
                       not_for_json, not_for_strict,
                       content_structure_primary, content_structure_density,
                       verification_status, confidence, verified_by, evidence,
                       extensions_json
                FROM kcp_units
                WHERE workspace_path = ? AND manifest_file = ?
                  AND (valid_from IS NULL OR valid_from <= ?)
                  AND (valid_until IS NULL OR valid_until >= ?)
                ORDER BY id
                """;
        List<KcpUnitRow> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, manifestFile);
            ps.setString(3, asOf);
            ps.setString(4, asOf);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new KcpUnitRow(
                            rs.getString("unit_id"),
                            rs.getString("path"),
                            rs.getString("intent"),
                            rs.getString("scope"),
                            rs.getString("audience_json"),
                            rs.getString("triggers_json"),
                            rs.getString("hints_json"),
                            rs.getString("valid_from"),
                            rs.getString("valid_until"),
                            rs.getString("recorded_at"),
                            rs.getString("superseded_by"),
                            rs.getString("content_hash_algorithm"),
                            rs.getString("content_hash_value"),
                            rs.getString("not_for_json"),
                            rs.getInt("not_for_strict") == 1,
                            rs.getString("content_structure_primary"),
                            rs.getString("content_structure_density"),
                            rs.getString("verification_status"),
                            getDoubleOrNeg1(rs, "confidence"),
                            rs.getString("verified_by"),
                            rs.getString("evidence"),
                            rs.getString("extensions_json")));
                }
            }
        }
        return result;
    }

    /**
     * Returns units that have been superseded (superseded_by is set and the
     * successor unit exists and is active at the given date).
     */
    public List<String> getSupersededUnitIds(Connection conn, String workspacePath,
                                             String manifestFile, String asOf) throws SQLException {
        String sql = """
                SELECT u.unit_id
                FROM kcp_units u
                WHERE u.workspace_path = ? AND u.manifest_file = ?
                  AND u.superseded_by IS NOT NULL
                  AND EXISTS (
                      SELECT 1 FROM kcp_units s
                      WHERE s.workspace_path = u.workspace_path
                        AND s.manifest_file = u.manifest_file
                        AND s.unit_id = u.superseded_by
                        AND (s.valid_from IS NULL OR s.valid_from <= ?)
                        AND (s.valid_until IS NULL OR s.valid_until >= ?)
                  )
                """;
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, manifestFile);
            ps.setString(3, asOf);
            ps.setString(4, asOf);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("unit_id"));
                }
            }
        }
        return result;
    }

    /**
     * Returns all file paths that are temporally inactive at asOf.
     * Covers both manifest files (knowledge.yaml) and unit content paths.
     * Used for post-filtering search results by temporal validity.
     */
    public Set<String> getInactiveFilePaths(Connection conn,
                                             String workspacePath,
                                             String asOf) throws SQLException {
        String sql = """
                SELECT file_path AS p FROM kcp_manifests
                WHERE workspace_path = ?
                  AND ((valid_from IS NOT NULL AND valid_from > ?)
                    OR (valid_until IS NOT NULL AND valid_until < ?))
                UNION
                SELECT path AS p FROM kcp_units
                WHERE workspace_path = ?
                  AND ((valid_from IS NOT NULL AND valid_from > ?)
                    OR (valid_until IS NOT NULL AND valid_until < ?))
                """;
        Set<String> result = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, asOf);
            ps.setString(3, asOf);
            ps.setString(4, workspacePath);
            ps.setString(5, asOf);
            ps.setString(6, asOf);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String p = rs.getString("p");
                    if (p != null) result.add(p);
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

    /**
     * Records the verdict of a {@code synthesis kcp verify} run for one unit
     * (issue #356). Idempotent per (workspace, manifest, unit).
     */
    public void upsertVerification(Connection conn, String workspacePath, String manifestFile,
                                    String unitId, String verdict, String findingsJson,
                                    String synthesisVersion, long verifiedAt) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO kcp_verification
                    (workspace_path, manifest_file, unit_id, verdict, findings_json,
                     verified_at, synthesis_version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, manifestFile);
            ps.setString(3, unitId);
            ps.setString(4, verdict);
            ps.setString(5, findingsJson);
            ps.setLong(6, verifiedAt);
            ps.setString(7, synthesisVersion);
            ps.executeUpdate();
        }
    }

    /** Returns unit_id → verdict from the most recent verify run for a manifest. */
    public Map<String, String> getVerificationVerdicts(Connection conn, String workspacePath,
                                                       String manifestFile) throws SQLException {
        String sql = """
                SELECT unit_id, verdict FROM kcp_verification
                WHERE workspace_path = ? AND manifest_file = ?
                """;
        Map<String, String> result = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, manifestFile);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("unit_id"), rs.getString("verdict"));
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
            long lastComputed,
            // v0.21 fields
            String signingAlgorithm,
            String signingKeyId,
            String signatureFile,
            String verificationStatus,
            double confidence,
            String verifiedBy,
            String verifiedAt,
            String validFrom,
            String validUntil,
            String notForJson,
            String contentStructurePrimary,
            String contentStructureDensity,
            // v0.25 forward-compatible extensions (issue #355)
            String rootExtensionsJson) {

        /** Backward-compatible constructor (6 fields). */
        public KcpManifestRow(String filePath, String project, String kcpVersion,
                              int unitCount, int relationshipCount, long lastComputed) {
            this(filePath, project, kcpVersion, unitCount, relationshipCount, lastComputed,
                    null, null, null, null, -1.0, null, null, null, null, null, null, null, null);
        }
    }

    public record KcpUnitRow(
            String unitId,
            String path,
            String intent,
            String scope,
            String audienceJson,
            String triggersJson,
            String hintsJson,
            // v0.21 temporal
            String validFrom,
            String validUntil,
            String recordedAt,
            String supersededBy,
            // v0.21 content integrity
            String contentHashAlgorithm,
            String contentHashValue,
            // v0.21 negative space
            String notForJson,
            boolean notForStrict,
            // v0.21 content structure
            String contentStructurePrimary,
            String contentStructureDensity,
            // v0.21 discovery
            String verificationStatus,
            double confidence,
            String verifiedBy,
            String evidence,
            // v0.25 forward-compatible extensions (issue #355)
            String extensionsJson) {

        /** Backward-compatible constructor (7 fields). */
        public KcpUnitRow(String unitId, String path, String intent, String scope,
                          String audienceJson, String triggersJson, String hintsJson) {
            this(unitId, path, intent, scope, audienceJson, triggersJson, hintsJson,
                    null, null, null, null, null, null, null, false, null, null,
                    null, -1.0, null, null, null);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void upsertManifest(Connection conn, String workspacePath, String filePath,
                                 String project, String kcpVersion,
                                 int unitCount, int relCount, long now,
                                 String signingAlgorithm, String signingKeyId, String signatureFile,
                                 String verificationStatus, double confidence,
                                 String verifiedBy, String verifiedAt,
                                 String validFrom, String validUntil, String notForJson,
                                 String csPrimary, String csDensity,
                                 String rootExtensionsJson) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO kcp_manifests
                    (workspace_path, file_path, project, kcp_version,
                     unit_count, relationship_count, last_computed,
                     signing_algorithm, signing_key_id, signature_file,
                     verification_status, confidence, verified_by, verified_at,
                     valid_from, valid_until, not_for_json,
                     content_structure_primary, content_structure_density,
                     root_extensions_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, filePath);
            ps.setString(3, project);
            ps.setString(4, kcpVersion);
            ps.setInt(5, unitCount);
            ps.setInt(6, relCount);
            ps.setLong(7, now);
            ps.setString(8, signingAlgorithm);
            ps.setString(9, signingKeyId);
            ps.setString(10, signatureFile);
            ps.setString(11, verificationStatus);
            if (confidence >= 0) {
                ps.setDouble(12, confidence);
            } else {
                ps.setNull(12, Types.REAL);
            }
            ps.setString(13, verifiedBy);
            ps.setString(14, verifiedAt);
            ps.setString(15, validFrom);
            ps.setString(16, validUntil);
            ps.setString(17, notForJson);
            ps.setString(18, csPrimary);
            ps.setString(19, csDensity);
            ps.setString(20, rootExtensionsJson);
            ps.executeUpdate();
        }
    }

    private void insertFederationEntry(Connection conn, String workspacePath, String manifestFile,
                                        KcpFederationEntry entry, long now) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO kcp_federation
                    (workspace_path, manifest_file, entry_id, url, label, relationship,
                     update_frequency, local_mirror, context, version_pin, version_policy,
                     valid_from, valid_until, superseded_by,
                     agent_identity_json, extensions_json, last_computed)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, manifestFile);
            ps.setString(3, entry.entryId());
            ps.setString(4, entry.url());
            ps.setString(5, entry.label());
            ps.setString(6, entry.relationship());
            ps.setString(7, entry.updateFrequency());
            ps.setString(8, entry.localMirror());
            ps.setString(9, entry.context());
            ps.setString(10, entry.versionPin());
            ps.setString(11, entry.versionPolicy());
            ps.setString(12, entry.validFrom());
            ps.setString(13, entry.validUntil());
            ps.setString(14, entry.supersededBy());
            ps.setString(15, entry.agentIdentityJson());
            ps.setString(16, entry.extensionsJson());
            ps.setLong(17, now);
            ps.executeUpdate();
        }
    }

    private void deleteFederationForManifest(Connection conn, String workspacePath,
                                              String manifestFile) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM kcp_federation WHERE workspace_path = ? AND manifest_file = ?")) {
            ps.setString(1, workspacePath);
            ps.setString(2, manifestFile);
            ps.executeUpdate();
        }
    }

    private void insertUnit(Connection conn, String workspacePath, String manifestFile,
                             KcpUnit unit, long now) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO kcp_units
                    (workspace_path, manifest_file, unit_id, path, intent, scope,
                     audience_json, triggers_json, hints_json, last_computed,
                     valid_from, valid_until, recorded_at, superseded_by,
                     content_hash_algorithm, content_hash_value,
                     not_for_json, not_for_strict,
                     content_structure_primary, content_structure_density,
                     verification_status, confidence, verified_by, evidence,
                     extensions_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            // Temporal
            ps.setString(11, unit.validFrom());
            ps.setString(12, unit.validUntil());
            ps.setString(13, unit.recordedAt());
            ps.setString(14, unit.supersededBy());
            // Content integrity
            ps.setString(15, unit.contentHashAlgorithm());
            ps.setString(16, unit.contentHashValue());
            // Negative space
            ps.setString(17, toJson(unit.notFor()));
            ps.setInt(18, unit.notForStrict() ? 1 : 0);
            // Content structure
            ps.setString(19, unit.contentStructurePrimary());
            ps.setString(20, unit.contentStructureDensity());
            // Discovery
            ps.setString(21, unit.verificationStatus());
            if (unit.confidence() >= 0) {
                ps.setDouble(22, unit.confidence());
            } else {
                ps.setNull(22, Types.REAL);
            }
            ps.setString(23, unit.verifiedBy());
            ps.setString(24, unit.evidence());
            // Forward-compatible extensions
            ps.setString(25, unit.extensionsJson());
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

    private static double getDouble(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Double d) return d;
        if (v instanceof Number n) return n.doubleValue();
        return -1.0;
    }

    private static double getDoubleOrNeg1(ResultSet rs, String col) throws SQLException {
        double val = rs.getDouble(col);
        return rs.wasNull() ? -1.0 : val;
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
