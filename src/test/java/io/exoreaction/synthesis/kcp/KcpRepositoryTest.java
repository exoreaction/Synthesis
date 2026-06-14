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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for KcpRepository — DB persistence of KCP manifest data.
 *
 * <p>Uses an in-memory SQLite database so tests run without touching the
 * real {@code ~/.synthesis/synthesis.db}.
 */
class KcpRepositoryTest {

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

            // Create V17 tables (copy of migration SQL)
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

            // Apply V22 migration columns
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
        }
        repo = new KcpRepository();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    // -----------------------------------------------------------------------

    @Test
    void testUpsertMinimalManifest() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.5"
                project: my-project
                units:
                  - id: overview
                    path: README.md
                    intent: "What is this project?"
                    scope: global
                    audience: [developer]
                  - id: api-ref
                    path: docs/api.md
                    intent: "What endpoints does the API expose?"
                    scope: module
                    audience: [developer]
                    triggers: [api, rest, endpoints]
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        // Manifest row
        List<KcpRepository.KcpManifestRow> manifests = repo.getManifests(conn, tempDir.toString());
        assertEquals(1, manifests.size());
        KcpRepository.KcpManifestRow m = manifests.get(0);
        assertEquals("my-project", m.project());
        assertEquals("0.5", m.kcpVersion());
        assertEquals(2, m.unitCount());
        assertEquals(0, m.relationshipCount());

        // Units
        List<KcpRepository.KcpUnitRow> units =
                repo.getUnitsForManifest(conn, tempDir.toString(), yaml.toString());
        assertEquals(2, units.size());
        assertEquals("overview", units.get(0).unitId());
        assertEquals("README.md", units.get(0).path());
        assertEquals("What is this project?", units.get(0).intent());
        assertEquals("global", units.get(0).scope());
        assertNotNull(units.get(0).audienceJson());

        // api-ref unit has triggers
        KcpRepository.KcpUnitRow apiUnit = units.get(1);
        assertEquals("api-ref", apiUnit.unitId());
        assertNotNull(apiUnit.triggersJson());
        assertTrue(apiUnit.triggersJson().contains("api"));

        // No relationships
        List<KcpRelationship> rels =
                repo.getRelationshipsForManifest(conn, tempDir.toString(), yaml.toString());
        assertTrue(rels.isEmpty());
    }

    @Test
    void testUpsertManifestWithRelationships() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.5"
                project: crewai
                units:
                  - id: overview
                    path: docs/intro.md
                    intent: "What is CrewAI?"
                    scope: global
                    audience: [developer]
                  - id: agents-tldr
                    path: docs/agents-tldr.md
                    intent: "Quick reference for agents"
                    scope: focused
                    audience: [developer]
                    hints:
                      summary_of: agents
                  - id: agents
                    path: docs/agents.md
                    intent: "Complete agent reference"
                    scope: comprehensive
                    audience: [developer, advanced]
                relationships:
                  - from: agents-tldr
                    to: agents
                    type: context
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        assertEquals(1, repo.countManifests(conn, tempDir.toString()));
        assertEquals(3, repo.countUnits(conn, tempDir.toString()));

        List<KcpRepository.KcpManifestRow> manifests = repo.getManifests(conn, tempDir.toString());
        assertEquals(1, manifests.get(0).relationshipCount());

        List<KcpRelationship> rels =
                repo.getRelationshipsForManifest(conn, tempDir.toString(), yaml.toString());
        assertEquals(1, rels.size());
        assertEquals("agents-tldr", rels.get(0).fromUnit());
        assertEquals("agents", rels.get(0).toUnit());
        assertEquals("context", rels.get(0).type());

        // agents-tldr unit should have a hints_json field
        List<KcpRepository.KcpUnitRow> units =
                repo.getUnitsForManifest(conn, tempDir.toString(), yaml.toString());
        KcpRepository.KcpUnitRow tldr = units.stream()
                .filter(u -> "agents-tldr".equals(u.unitId()))
                .findFirst().orElseThrow();
        assertNotNull(tldr.hintsJson());
        assertTrue(tldr.hintsJson().contains("agents"));
    }

    @Test
    void testUpsertIsIdempotent() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.5"
                project: test-project
                units:
                  - id: intro
                    path: README.md
                    intent: "Introduction"
                    scope: global
                    audience: [developer]
                """);

        FileMetadata meta = metadata(yaml);
        AnalysisResult analysis = analyzer.analyze(meta);

        // Insert twice — should not throw and should still have 1 manifest
        repo.upsertFromAnalysis(conn, tempDir.toString(), meta, analysis);
        repo.upsertFromAnalysis(conn, tempDir.toString(), meta, analysis);

        assertEquals(1, repo.countManifests(conn, tempDir.toString()));
        assertEquals(1, repo.countUnits(conn, tempDir.toString()));
    }

    @Test
    void testDeleteForManifest() throws Exception {
        Path yaml = writeYaml("knowledge.yaml", """
                kcp_version: "0.5"
                project: deletable
                units:
                  - id: unit1
                    path: a.md
                    intent: "First unit"
                    scope: global
                    audience: [developer]
                relationships:
                  - from: unit1
                    to: unit1
                    type: self
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis);

        assertEquals(1, repo.countManifests(conn, tempDir.toString()));
        assertEquals(1, repo.countUnits(conn, tempDir.toString()));

        repo.deleteForManifest(conn, tempDir.toString(), yaml.toString());

        assertEquals(0, repo.countManifests(conn, tempDir.toString()));
        assertEquals(0, repo.countUnits(conn, tempDir.toString()));
        assertTrue(repo.getRelationshipsForManifest(
                conn, tempDir.toString(), yaml.toString()).isEmpty());
    }

    @Test
    void testDeleteAllForWorkspace() throws Exception {
        // Write two manifests in different subdirs
        Path sub1 = Files.createDirectory(tempDir.resolve("repo1"));
        Path sub2 = Files.createDirectory(tempDir.resolve("repo2"));
        Path yaml1 = writeYaml(sub1.resolve("knowledge.yaml"), """
                kcp_version: "0.5"
                project: repo1
                units:
                  - id: u1
                    path: a.md
                    intent: Unit 1
                    scope: global
                    audience: [developer]
                """);
        Path yaml2 = writeYaml(sub2.resolve("knowledge.yaml"), """
                kcp_version: "0.5"
                project: repo2
                units:
                  - id: u2
                    path: b.md
                    intent: Unit 2
                    scope: global
                    audience: [developer]
                """);

        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml1), analyzer.analyze(metadata(yaml1)));
        repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml2), analyzer.analyze(metadata(yaml2)));

        assertEquals(2, repo.countManifests(conn, tempDir.toString()));
        assertEquals(2, repo.countUnits(conn, tempDir.toString()));

        repo.deleteAllForWorkspace(conn, tempDir.toString());

        assertEquals(0, repo.countManifests(conn, tempDir.toString()));
        assertEquals(0, repo.countUnits(conn, tempDir.toString()));
    }

    @Test
    void testNonKcpYamlIsNotPersisted() throws Exception {
        Path yaml = writeYaml("config.yaml", """
                server:
                  port: 8080
                database:
                  host: localhost
                """);

        AnalysisResult analysis = analyzer.analyze(metadata(yaml));
        // Should not be "kcp-manifest"
        assertNotEquals("kcp-manifest", analysis.metrics().get("yamlType"));

        // If we try to persist it anyway (e.g. caller doesn't check), it should
        // store with null/empty project — the table row will exist but is harmless.
        // In practice callers guard with: if ("kcp-manifest".equals(...))
        // This test just confirms it doesn't throw.
        assertDoesNotThrow(() ->
                repo.upsertFromAnalysis(conn, tempDir.toString(), metadata(yaml), analysis));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Path writeYaml(String name, String content) throws IOException {
        return writeYaml(tempDir.resolve(name), content);
    }

    private Path writeYaml(Path path, String content) throws IOException {
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
