package io.exoreaction.synthesis.kcp;

import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.analyzer.YamlAnalyzer;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for KCP v0.21 field parsing, persistence, and temporal filtering.
 *
 * <p>Covers:
 * <ul>
 *   <li>Phase A: Temporal, content_hash, not_for, content_structure, discovery extraction</li>
 *   <li>Phase B: Temporal filtering with as_of, superseded_by chains</li>
 *   <li>Health signals: dangling superseded_by, expired units, hash metadata</li>
 * </ul>
 */
class KcpV021IntegrationTest {

    @TempDir
    Path tempDir;

    private Connection conn;
    private KcpRepository repo;
    private final YamlAnalyzer analyzer = new YamlAnalyzer();

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");

            // Create V17 tables
            st.execute("""
                    CREATE TABLE kcp_manifests (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        workspace_path TEXT NOT NULL,
                        file_path TEXT NOT NULL,
                        project TEXT,
                        kcp_version TEXT,
                        unit_count INTEGER NOT NULL DEFAULT 0,
                        relationship_count INTEGER NOT NULL DEFAULT 0,
                        last_computed INTEGER NOT NULL,
                        UNIQUE(workspace_path, file_path)
                    )""");
            st.execute("""
                    CREATE TABLE kcp_units (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        workspace_path TEXT NOT NULL,
                        manifest_file TEXT NOT NULL,
                        unit_id TEXT NOT NULL,
                        path TEXT,
                        intent TEXT,
                        scope TEXT,
                        audience_json TEXT,
                        triggers_json TEXT,
                        hints_json TEXT,
                        last_computed INTEGER NOT NULL,
                        UNIQUE(workspace_path, manifest_file, unit_id)
                    )""");
            st.execute("""
                    CREATE TABLE kcp_relationships (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        workspace_path TEXT NOT NULL,
                        manifest_file TEXT NOT NULL,
                        from_unit TEXT NOT NULL,
                        to_unit TEXT NOT NULL,
                        type TEXT,
                        last_computed INTEGER NOT NULL,
                        UNIQUE(workspace_path, manifest_file, from_unit, to_unit, type)
                    )""");

            // Apply V22 migration (new columns)
            st.execute("ALTER TABLE kcp_units ADD COLUMN valid_from TEXT");
            st.execute("ALTER TABLE kcp_units ADD COLUMN valid_until TEXT");
            st.execute("ALTER TABLE kcp_units ADD COLUMN recorded_at TEXT");
            st.execute("ALTER TABLE kcp_units ADD COLUMN superseded_by TEXT");
            st.execute("ALTER TABLE kcp_units ADD COLUMN content_hash_algorithm TEXT");
            st.execute("ALTER TABLE kcp_units ADD COLUMN content_hash_value TEXT");
            st.execute("ALTER TABLE kcp_units ADD COLUMN not_for_json TEXT");
            st.execute("ALTER TABLE kcp_units ADD COLUMN not_for_strict INTEGER DEFAULT 0");
            st.execute("ALTER TABLE kcp_units ADD COLUMN content_structure_primary TEXT");
            st.execute("ALTER TABLE kcp_units ADD COLUMN content_structure_density TEXT");
            st.execute("ALTER TABLE kcp_units ADD COLUMN verification_status TEXT");
            st.execute("ALTER TABLE kcp_units ADD COLUMN confidence REAL");
            st.execute("ALTER TABLE kcp_units ADD COLUMN verified_by TEXT");
            st.execute("ALTER TABLE kcp_units ADD COLUMN evidence TEXT");

            st.execute("ALTER TABLE kcp_manifests ADD COLUMN signing_algorithm TEXT");
            st.execute("ALTER TABLE kcp_manifests ADD COLUMN signing_key_id TEXT");
            st.execute("ALTER TABLE kcp_manifests ADD COLUMN signature_file TEXT");
            st.execute("ALTER TABLE kcp_manifests ADD COLUMN verification_status TEXT");
            st.execute("ALTER TABLE kcp_manifests ADD COLUMN confidence REAL");
            st.execute("ALTER TABLE kcp_manifests ADD COLUMN verified_by TEXT");
            st.execute("ALTER TABLE kcp_manifests ADD COLUMN verified_at TEXT");
            st.execute("ALTER TABLE kcp_manifests ADD COLUMN valid_from TEXT");
            st.execute("ALTER TABLE kcp_manifests ADD COLUMN valid_until TEXT");
            st.execute("ALTER TABLE kcp_manifests ADD COLUMN not_for_json TEXT");
            st.execute("ALTER TABLE kcp_manifests ADD COLUMN content_structure_primary TEXT");
            st.execute("ALTER TABLE kcp_manifests ADD COLUMN content_structure_density TEXT");

            // Apply V23 migration (v0.25 federation + forward-compat extensions)
            st.execute("ALTER TABLE kcp_manifests ADD COLUMN root_extensions_json TEXT");
            st.execute("ALTER TABLE kcp_units ADD COLUMN extensions_json TEXT");
            st.execute("""
                    CREATE TABLE kcp_federation (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        workspace_path TEXT NOT NULL,
                        manifest_file TEXT NOT NULL,
                        entry_id TEXT,
                        url TEXT,
                        label TEXT,
                        relationship TEXT,
                        update_frequency TEXT,
                        local_mirror TEXT,
                        context TEXT,
                        version_pin TEXT,
                        version_policy TEXT,
                        valid_from TEXT,
                        valid_until TEXT,
                        superseded_by TEXT,
                        agent_identity_json TEXT,
                        extensions_json TEXT,
                        last_computed INTEGER NOT NULL,
                        UNIQUE(workspace_path, manifest_file, entry_id, url)
                    )""");
        }
        repo = new KcpRepository();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    // =======================================================================
    // Phase A: Parsing and Storage
    // =======================================================================

    @Test
    void testTemporalFieldsParsedFromRootAndUnit() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: temporal-test
                temporal:
                  valid_from: "2018-05-25"
                  valid_until: "2030-12-31"
                units:
                  - id: unit-no-override
                    path: a.md
                    intent: "Unit inherits root temporal"
                    scope: global
                    audience: [agent]
                  - id: unit-with-override
                    path: b.md
                    intent: "Unit overrides temporal"
                    scope: global
                    audience: [agent]
                    temporal:
                      valid_from: "2023-09-01"
                      valid_until: "2025-06-30"
                      recorded_at: "2023-08-28"
                      superseded_by: unit-no-override
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        List<KcpRepository.KcpUnitRow> units =
                repo.getUnitsForManifest(conn, tempDir.toString(), yaml.toString());
        assertEquals(2, units.size());

        // First unit: inherits root temporal
        KcpRepository.KcpUnitRow u1 = units.get(0);
        assertEquals("unit-no-override", u1.unitId());
        assertEquals("2018-05-25", u1.validFrom());
        assertEquals("2030-12-31", u1.validUntil());
        assertNull(u1.recordedAt());
        assertNull(u1.supersededBy());

        // Second unit: overrides root temporal
        KcpRepository.KcpUnitRow u2 = units.get(1);
        assertEquals("unit-with-override", u2.unitId());
        assertEquals("2023-09-01", u2.validFrom());
        assertEquals("2025-06-30", u2.validUntil());
        assertEquals("2023-08-28", u2.recordedAt());
        assertEquals("unit-no-override", u2.supersededBy());
    }

    @Test
    void testContentHashExtraction() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: hash-test
                units:
                  - id: gdpr-recitals
                    path: recitals.txt
                    content_hash:
                      algorithm: sha256
                      value: "68a74ca61d4cab6be4d0782c3340c42ba654c09eaff79e624d031d9ccb058418"
                    intent: "GDPR recitals"
                    scope: global
                    audience: [agent]
                  - id: no-hash
                    path: other.txt
                    intent: "No hash declared"
                    scope: global
                    audience: [agent]
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        List<KcpRepository.KcpUnitRow> units =
                repo.getUnitsForManifest(conn, tempDir.toString(), yaml.toString());
        assertEquals(2, units.size());

        KcpRepository.KcpUnitRow hashed = units.get(0);
        assertEquals("sha256", hashed.contentHashAlgorithm());
        assertEquals("68a74ca61d4cab6be4d0782c3340c42ba654c09eaff79e624d031d9ccb058418",
                hashed.contentHashValue());

        KcpRepository.KcpUnitRow noHash = units.get(1);
        assertNull(noHash.contentHashAlgorithm());
        assertNull(noHash.contentHashValue());
    }

    @Test
    void testNotForExtraction() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: notfor-test
                not_for:
                  - questions unrelated to data protection
                units:
                  - id: gdpr-unit
                    path: gdpr.txt
                    intent: "GDPR text"
                    scope: global
                    audience: [agent]
                    not_for:
                      - questions about national derogations
                      - non-EU jurisdictions
                    not_for_strict: true
                  - id: inheriting-unit
                    path: other.txt
                    intent: "Inherits root not_for"
                    scope: global
                    audience: [agent]
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        List<KcpRepository.KcpUnitRow> units =
                repo.getUnitsForManifest(conn, tempDir.toString(), yaml.toString());
        assertEquals(2, units.size());

        // Unit with own not_for
        KcpRepository.KcpUnitRow u1 = units.get(0);
        assertNotNull(u1.notForJson());
        assertTrue(u1.notForJson().contains("national derogations"));
        assertTrue(u1.notForJson().contains("non-EU jurisdictions"));
        assertTrue(u1.notForStrict());

        // Unit inheriting root not_for
        KcpRepository.KcpUnitRow u2 = units.get(1);
        assertNotNull(u2.notForJson());
        assertTrue(u2.notForJson().contains("questions unrelated to data protection"));
        assertFalse(u2.notForStrict());
    }

    @Test
    void testContentStructureExtraction() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: structure-test
                content_structure:
                  primary: prose
                  density: dense
                units:
                  - id: article
                    path: article.txt
                    intent: "Dense legal text"
                    scope: global
                    audience: [agent]
                  - id: table-unit
                    path: table.md
                    intent: "Tabular summary"
                    scope: global
                    audience: [agent]
                    content_structure:
                      primary: table
                      density: sparse
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        List<KcpRepository.KcpUnitRow> units =
                repo.getUnitsForManifest(conn, tempDir.toString(), yaml.toString());
        assertEquals(2, units.size());

        // Inherits root
        assertEquals("prose", units.get(0).contentStructurePrimary());
        assertEquals("dense", units.get(0).contentStructureDensity());

        // Overrides
        assertEquals("table", units.get(1).contentStructurePrimary());
        assertEquals("sparse", units.get(1).contentStructureDensity());
    }

    @Test
    void testDiscoveryProvenanceExtraction() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: discovery-test
                discovery:
                  verification_status: verified
                  confidence: 0.95
                  verified_by: totto@exoreaction.com
                  evidence: https://github.com/totto/audit
                  verified_at: "2026-03-20T00:00:00Z"
                units:
                  - id: verified-unit
                    path: doc.txt
                    intent: "Verified content"
                    scope: global
                    audience: [agent]
                  - id: unit-own-discovery
                    path: other.txt
                    intent: "Own discovery"
                    scope: global
                    audience: [agent]
                    discovery:
                      verification_status: declared
                      confidence: 0.6
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        // Check manifest-level discovery
        List<KcpRepository.KcpManifestRow> manifests = repo.getManifests(conn, tempDir.toString());
        assertEquals(1, manifests.size());
        KcpRepository.KcpManifestRow m = manifests.get(0);
        assertEquals("verified", m.verificationStatus());
        assertEquals(0.95, m.confidence(), 0.001);
        assertEquals("totto@exoreaction.com", m.verifiedBy());
        assertEquals("2026-03-20T00:00:00Z", m.verifiedAt());

        // Check unit-level discovery (inherits root)
        List<KcpRepository.KcpUnitRow> units =
                repo.getUnitsForManifest(conn, tempDir.toString(), yaml.toString());
        assertEquals(2, units.size());

        KcpRepository.KcpUnitRow u1 = units.get(0);
        assertEquals("verified", u1.verificationStatus());
        assertEquals(0.95, u1.confidence(), 0.001);
        assertEquals("totto@exoreaction.com", u1.verifiedBy());
        assertEquals("https://github.com/totto/audit", u1.evidence());

        // Unit with own discovery overrides
        KcpRepository.KcpUnitRow u2 = units.get(1);
        assertEquals("declared", u2.verificationStatus());
        assertEquals(0.6, u2.confidence(), 0.001);
    }

    @Test
    void testSigningMetadataExtraction() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: signing-test
                trust:
                  provenance:
                    publisher: eXOReaction AS
                  content_integrity:
                    signing:
                      algorithm: EdDSA
                      key_id: totto@exoreaction.com
                    signature_file: knowledge.yaml.sig
                units:
                  - id: unit1
                    path: a.md
                    intent: "Test unit"
                    scope: global
                    audience: [agent]
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        List<KcpRepository.KcpManifestRow> manifests = repo.getManifests(conn, tempDir.toString());
        assertEquals(1, manifests.size());
        KcpRepository.KcpManifestRow m = manifests.get(0);
        assertEquals("EdDSA", m.signingAlgorithm());
        assertEquals("totto@exoreaction.com", m.signingKeyId());
        assertEquals("knowledge.yaml.sig", m.signatureFile());
    }

    @Test
    void testManifestRootTemporalAndNotFor() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: root-fields-test
                temporal:
                  valid_from: "2020-01-01"
                  valid_until: "2030-12-31"
                not_for:
                  - hardware questions
                  - firmware debugging
                content_structure:
                  primary: code
                  density: normal
                units:
                  - id: u1
                    path: a.md
                    intent: "A unit"
                    scope: global
                    audience: [agent]
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        List<KcpRepository.KcpManifestRow> manifests = repo.getManifests(conn, tempDir.toString());
        KcpRepository.KcpManifestRow m = manifests.get(0);
        assertEquals("2020-01-01", m.validFrom());
        assertEquals("2030-12-31", m.validUntil());
        assertNotNull(m.notForJson());
        assertTrue(m.notForJson().contains("hardware questions"));
        assertEquals("code", m.contentStructurePrimary());
        assertEquals("normal", m.contentStructureDensity());
    }

    @Test
    void testBackwardCompatibilityWithV05Manifest() throws Exception {
        // A minimal v0.5 manifest should still work without errors
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.5"
                project: legacy-project
                units:
                  - id: overview
                    path: README.md
                    intent: "What is this project?"
                    scope: global
                    audience: [developer]
                    triggers: [readme, overview]
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        List<KcpRepository.KcpUnitRow> units =
                repo.getUnitsForManifest(conn, tempDir.toString(), yaml.toString());
        assertEquals(1, units.size());
        KcpRepository.KcpUnitRow u = units.get(0);
        assertEquals("overview", u.unitId());
        assertEquals("README.md", u.path());
        // All v0.21 fields should be null/default
        assertNull(u.validFrom());
        assertNull(u.validUntil());
        assertNull(u.contentHashAlgorithm());
        assertNull(u.notForJson());
        assertFalse(u.notForStrict());
        assertNull(u.contentStructurePrimary());
        assertNull(u.verificationStatus());
        assertEquals(-1.0, u.confidence(), 0.001);
    }

    // =======================================================================
    // Phase B: Temporal Filtering
    // =======================================================================

    @Test
    void testTemporalFilteringAsOfBeforeValidFrom() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: temporal-filter
                units:
                  - id: gdpr-unit
                    path: gdpr.txt
                    intent: "GDPR text"
                    scope: global
                    audience: [agent]
                    temporal:
                      valid_from: "2018-05-25"
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        // as_of before valid_from -> unit excluded
        List<KcpRepository.KcpUnitRow> before = repo.getActiveUnitsForManifest(
                conn, tempDir.toString(), yaml.toString(), "2017-01-01");
        assertTrue(before.isEmpty(), "Unit should be excluded before valid_from");
    }

    @Test
    void testTemporalFilteringAsOfWithinWindow() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: temporal-filter
                units:
                  - id: gdpr-unit
                    path: gdpr.txt
                    intent: "GDPR text"
                    scope: global
                    audience: [agent]
                    temporal:
                      valid_from: "2018-05-25"
                      valid_until: "2030-12-31"
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        // as_of within window -> unit included
        List<KcpRepository.KcpUnitRow> within = repo.getActiveUnitsForManifest(
                conn, tempDir.toString(), yaml.toString(), "2024-06-14");
        assertEquals(1, within.size());
        assertEquals("gdpr-unit", within.get(0).unitId());
    }

    @Test
    void testTemporalFilteringAsOfAfterValidUntil() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: temporal-filter
                units:
                  - id: expired-policy
                    path: old-policy.md
                    intent: "Old policy"
                    scope: global
                    audience: [agent]
                    temporal:
                      valid_from: "2020-01-01"
                      valid_until: "2023-12-31"
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        // as_of after valid_until -> unit excluded
        List<KcpRepository.KcpUnitRow> after = repo.getActiveUnitsForManifest(
                conn, tempDir.toString(), yaml.toString(), "2024-06-14");
        assertTrue(after.isEmpty(), "Unit should be excluded after valid_until");
    }

    @Test
    void testTemporalFilteringNoTemporalFieldsMeansAlwaysActive() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: temporal-filter
                units:
                  - id: always-active
                    path: readme.md
                    intent: "Always active unit"
                    scope: global
                    audience: [agent]
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        // No temporal -> always active
        List<KcpRepository.KcpUnitRow> result = repo.getActiveUnitsForManifest(
                conn, tempDir.toString(), yaml.toString(), "1900-01-01");
        assertEquals(1, result.size());
        assertEquals("always-active", result.get(0).unitId());
    }

    @Test
    void testTemporalFilteringDefaultBehaviorExcludesExpired() throws Exception {
        String today = LocalDate.now().toString();
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: temporal-filter
                units:
                  - id: expired
                    path: old.md
                    intent: "Expired"
                    scope: global
                    audience: [agent]
                    temporal:
                      valid_until: "2020-01-01"
                  - id: current
                    path: new.md
                    intent: "Current"
                    scope: global
                    audience: [agent]
                    temporal:
                      valid_from: "2020-01-01"
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        // Default (today) -> only current unit
        List<KcpRepository.KcpUnitRow> result = repo.getActiveUnitsForManifest(
                conn, tempDir.toString(), yaml.toString(), today);
        assertEquals(1, result.size());
        assertEquals("current", result.get(0).unitId());
    }

    @Test
    void testSupersededByChainWithActiveSuccessor() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: supersession-test
                units:
                  - id: old-policy
                    path: old.md
                    intent: "Old policy"
                    scope: global
                    audience: [agent]
                    temporal:
                      valid_from: "2020-01-01"
                      superseded_by: new-policy
                  - id: new-policy
                    path: new.md
                    intent: "New policy"
                    scope: global
                    audience: [agent]
                    temporal:
                      valid_from: "2023-01-01"
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        // Both units are temporally active at 2024-01-01
        List<KcpRepository.KcpUnitRow> active = repo.getActiveUnitsForManifest(
                conn, tempDir.toString(), yaml.toString(), "2024-01-01");
        assertEquals(2, active.size());

        // But old-policy is superseded because new-policy is active
        List<String> superseded = repo.getSupersededUnitIds(
                conn, tempDir.toString(), yaml.toString(), "2024-01-01");
        assertEquals(1, superseded.size());
        assertEquals("old-policy", superseded.get(0));
    }

    @Test
    void testSupersededByWithInactiveSuccessor() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: supersession-test
                units:
                  - id: old-policy
                    path: old.md
                    intent: "Old policy"
                    scope: global
                    audience: [agent]
                    temporal:
                      valid_from: "2020-01-01"
                      superseded_by: future-policy
                  - id: future-policy
                    path: future.md
                    intent: "Future policy"
                    scope: global
                    audience: [agent]
                    temporal:
                      valid_from: "2030-01-01"
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        // At 2024-01-01: future-policy is not yet active, so old-policy is NOT superseded
        List<String> superseded = repo.getSupersededUnitIds(
                conn, tempDir.toString(), yaml.toString(), "2024-01-01");
        assertTrue(superseded.isEmpty(),
                "old-policy should NOT be superseded when successor is not yet active");
    }

    @Test
    void testSupersededByDanglingReference() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: dangling-test
                units:
                  - id: orphan
                    path: orphan.md
                    intent: "Points to nonexistent successor"
                    scope: global
                    audience: [agent]
                    temporal:
                      valid_from: "2020-01-01"
                      superseded_by: nonexistent-unit
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        // Dangling superseded_by -> not treated as superseded (successor doesn't exist)
        List<String> superseded = repo.getSupersededUnitIds(
                conn, tempDir.toString(), yaml.toString(), "2024-01-01");
        assertTrue(superseded.isEmpty(),
                "Dangling superseded_by should not cause exclusion");
    }

    @Test
    void testMynderGdprFragmentStructure() throws Exception {
        // Simulates the real Mynder GDPR fragment manifest structure
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.20"
                project: mynder-regulatory-knowledge-gdpr
                version: 1.0.0
                trust:
                  provenance:
                    publisher: eXOReaction AS
                    contact: totto@exoreaction.com
                  content_integrity:
                    signing:
                      algorithm: EdDSA
                      key_id: totto@exoreaction.com
                    signature_file: knowledge.yaml.sig
                temporal:
                  valid_from: "2018-05-25"
                  recorded_at: "2026-03-20"
                content_structure:
                  primary: prose
                  density: dense
                not_for:
                  - questions unrelated to data protection
                  - questions about member state derogations not included in fragment
                discovery:
                  source: download
                  verification_status: verified
                  confidence: 1.0
                  verified_at: "2026-03-20T00:00:00Z"
                  verified_by: totto@exoreaction.com
                  evidence: https://github.com/totto/Mynder-Regulatory-Knowledge-Infrastructure
                units:
                  - id: gdpr-recitals
                    path: recitals.txt
                    content_hash:
                      algorithm: sha256
                      value: "68a74ca61d4cab6be4d0782c3340c42ba654c09eaff79e624d031d9ccb058418"
                    intent: GDPR Recitals and Preamble
                    scope: global
                    audience: [agent]
                    triggers: [gdpr, recitals, preamble]
                  - id: gdpr-art-001
                    path: art-001.txt
                    content_hash:
                      algorithm: sha256
                      value: "05a2e43eb6a6391b23e99724c5755fb0ec12a3b4d3eda394d9256e2ea435c884"
                    intent: GDPR Art. 1 - Subject-matter and objectives
                    scope: global
                    audience: [agent]
                    triggers: [gdpr, art1, subject-matter]
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        // Manifest-level checks
        List<KcpRepository.KcpManifestRow> manifests = repo.getManifests(conn, tempDir.toString());
        assertEquals(1, manifests.size());
        KcpRepository.KcpManifestRow m = manifests.get(0);
        assertEquals("mynder-regulatory-knowledge-gdpr", m.project());
        assertEquals("0.20", m.kcpVersion());
        assertEquals("EdDSA", m.signingAlgorithm());
        assertEquals("totto@exoreaction.com", m.signingKeyId());
        assertEquals("knowledge.yaml.sig", m.signatureFile());
        assertEquals("verified", m.verificationStatus());
        assertEquals(1.0, m.confidence(), 0.001);
        assertEquals("2018-05-25", m.validFrom());
        assertNull(m.validUntil());  // GDPR has no expiry
        assertTrue(m.notForJson().contains("data protection"));
        assertEquals("prose", m.contentStructurePrimary());
        assertEquals("dense", m.contentStructureDensity());

        // Unit-level checks
        List<KcpRepository.KcpUnitRow> units =
                repo.getUnitsForManifest(conn, tempDir.toString(), yaml.toString());
        assertEquals(2, units.size());

        KcpRepository.KcpUnitRow recitals = units.get(0);
        assertEquals("gdpr-recitals", recitals.unitId());
        assertEquals("sha256", recitals.contentHashAlgorithm());
        assertEquals("68a74ca61d4cab6be4d0782c3340c42ba654c09eaff79e624d031d9ccb058418",
                recitals.contentHashValue());
        assertEquals("2018-05-25", recitals.validFrom());  // inherited from root
        assertNull(recitals.validUntil());
        assertEquals("prose", recitals.contentStructurePrimary());  // inherited
        assertEquals("dense", recitals.contentStructureDensity());
        assertEquals("verified", recitals.verificationStatus());  // inherited
        assertEquals(1.0, recitals.confidence(), 0.001);
        assertEquals("totto@exoreaction.com", recitals.verifiedBy());

        // Temporal filtering: GDPR valid from 2018-05-25
        List<KcpRepository.KcpUnitRow> before = repo.getActiveUnitsForManifest(
                conn, tempDir.toString(), yaml.toString(), "2017-01-01");
        assertTrue(before.isEmpty(), "GDPR units should be excluded before 2018-05-25");

        List<KcpRepository.KcpUnitRow> after = repo.getActiveUnitsForManifest(
                conn, tempDir.toString(), yaml.toString(), "2024-06-14");
        assertEquals(2, after.size(), "GDPR units should be active after 2018-05-25");
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private Path writeYaml(String name, String content) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    private FileMetadata metadata(Path path) {
        try {
            long size = Files.exists(path) ? Files.size(path) : 0;
            return new FileMetadata(
                    path, path.getFileName().toString(), path.getFileName().toString(),
                    ".yaml", FileUtils.FileType.YAML, null, size, Instant.now(), null);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
