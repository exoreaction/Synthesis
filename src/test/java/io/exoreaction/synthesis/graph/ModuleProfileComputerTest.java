package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ModuleProfileComputer} -- aggregates code_dependencies into module_profiles.
 *
 * @since v1.12.2 (CKG-2.05)
 */
class ModuleProfileComputerTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private Connection conn;
    private CodeGraphRepository repo;
    private ModuleProfileComputer computer;
    private static final String WS = "/test/workspace";
    private static final long NOW = Instant.now().getEpochSecond();

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        conn = db.getConnection();
        repo = new CodeGraphRepository();
        computer = new ModuleProfileComputer(repo);
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    // -----------------------------------------------------------------------
    // computeAndPersist
    // -----------------------------------------------------------------------

    @Test
    void computeAndPersist_single_package() throws SQLException {
        // Package com.example.core has one file importing from com.example.util
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/Core.java", "Core", "com.example.core",
                "src/Util.java", "Util", "com.example.util", "import", false, NOW));

        // Package com.example.util has a file
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/Util.java", "Util", "com.example.util",
                null, "List", "java.util", "import", true, NOW));

        int count = computer.computeAndPersist(WS, conn);
        assertTrue(count >= 2, "Should compute at least 2 profiles: " + count);

        // Verify com.example.core: fan_out=1 (imports com.example.util), fan_in=0
        ModuleProfileRow core = loadProfile(conn, WS, "com/example/core");
        assertNotNull(core, "core profile should exist");
        assertEquals(0, core.fanIn, "core fan_in should be 0");
        assertEquals(1, core.fanOut, "core fan_out should be 1");

        // Verify com.example.util: fan_in=1 (imported by com.example.core), fan_out=0 (java.util is external, excluded)
        ModuleProfileRow util = loadProfile(conn, WS, "com/example/util");
        assertNotNull(util, "util profile should exist");
        assertEquals(1, util.fanIn, "util fan_in should be 1");
    }

    @Test
    void computeAndPersist_multiple_packages() throws SQLException {
        // Set up 3 packages with various dependencies
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/A.java", "A", "com.a",
                "src/B.java", "B", "com.b", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/A.java", "A", "com.a",
                "src/C.java", "C", "com.c", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/B.java", "B", "com.b",
                "src/C.java", "C", "com.c", "import", false, NOW));

        int count = computer.computeAndPersist(WS, conn);
        assertEquals(3, count, "Should compute 3 profiles");

        // com.c: fan_in=2 (from com.a and com.b)
        ModuleProfileRow c = loadProfile(conn, WS, "com/c");
        assertNotNull(c);
        assertEquals(2, c.fanIn);
        assertEquals(0, c.fanOut);
    }

    @Test
    void computeAndPersist_replaces_stale_data() throws SQLException {
        // First run
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/A.java", "A", "com.a",
                "src/B.java", "B", "com.b", "import", false, NOW));
        int count1 = computer.computeAndPersist(WS, conn);
        assertTrue(count1 > 0);

        // Second run (idempotent)
        int count2 = computer.computeAndPersist(WS, conn);
        assertEquals(count1, count2, "Second run should produce same count (idempotent)");

        // Verify no duplicates
        int totalProfiles = countAllProfiles(conn, WS);
        assertEquals(count2, totalProfiles, "No duplicate profiles should exist");
    }

    @Test
    void computeAndPersist_empty_workspace_returns_zero() throws SQLException {
        int count = computer.computeAndPersist(WS, conn);
        assertEquals(0, count, "Empty workspace should produce 0 profiles");
    }

    // -----------------------------------------------------------------------
    // Instability calculations
    // -----------------------------------------------------------------------

    @Test
    void instability_fully_stable() throws SQLException {
        // Package com.core is imported by 3 others but imports nothing
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/A.java", "A", "com.a",
                "src/Core.java", "Core", "com.core", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/B.java", "B", "com.b",
                "src/Core.java", "Core", "com.core", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/C.java", "C", "com.c",
                "src/Core.java", "Core", "com.core", "import", false, NOW));
        // Core itself only imports external stuff
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/Core.java", "Core", "com.core",
                null, "String", "java.lang", "import", true, NOW));

        computer.computeAndPersist(WS, conn);

        ModuleProfileRow core = loadProfile(conn, WS, "com/core");
        assertNotNull(core);
        assertEquals(0.0, core.instability, 0.01,
                "Package with only fan-in should have instability 0.0");
    }

    @Test
    void instability_fully_unstable() throws SQLException {
        // Package com.cli imports others but nobody imports it
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/Cli.java", "Cli", "com.cli",
                "src/Core.java", "Core", "com.core", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/Cli.java", "Cli", "com.cli",
                "src/Util.java", "Util", "com.util", "import", false, NOW));

        computer.computeAndPersist(WS, conn);

        ModuleProfileRow cli = loadProfile(conn, WS, "com/cli");
        assertNotNull(cli);
        assertEquals(1.0, cli.instability, 0.01,
                "Package with only fan-out should have instability 1.0");
    }

    @Test
    void instability_division_by_zero_guard() throws SQLException {
        // Package with no connections at all (only has internal self-imports or something weird)
        // We create a package that appears as a source but only with self-referential imports
        // Actually, for this test: a package that exists but has fan_in=0 and fan_out=0
        // We need a package that appears in code_dependencies but with no cross-package deps
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/Orphan.java", "Orphan", "com.orphan",
                null, "String", "java.lang", "import", true, NOW));

        computer.computeAndPersist(WS, conn);

        ModuleProfileRow orphan = loadProfile(conn, WS, "com/orphan");
        assertNotNull(orphan);
        assertEquals(0.5, orphan.instability, 0.01,
                "Orphan package (fan_in=0, fan_out=0) should have neutral instability 0.5");
    }

    // -----------------------------------------------------------------------
    // inferPurpose
    // -----------------------------------------------------------------------

    @Test
    void inferPurpose_cli_package() {
        assertEquals("CLI command implementations", computer.inferPurpose("io.exoreaction.synthesis.cli"));
    }

    @Test
    void inferPurpose_core_package() {
        assertEquals("Core domain model", computer.inferPurpose("io.exoreaction.synthesis.core"));
    }

    @Test
    void inferPurpose_unknown_package() {
        assertEquals("General purpose", computer.inferPurpose("com.example.whatever"));
    }

    @Test
    void inferPurpose_db_package() {
        assertEquals("Data persistence", computer.inferPurpose("io.exoreaction.synthesis.db"));
    }

    @Test
    void inferPurpose_graph_package() {
        assertEquals("Graph analysis and visualization", computer.inferPurpose("io.exoreaction.synthesis.graph"));
    }

    @Test
    void inferPurpose_null_returns_general() {
        assertEquals("General purpose", computer.inferPurpose(null));
    }

    @Test
    void inferPurpose_empty_returns_general() {
        assertEquals("General purpose", computer.inferPurpose(""));
    }

    @Test
    void inferPurpose_changelog_package() {
        assertEquals("Change tracking", computer.inferPurpose("io.exoreaction.synthesis.changelog"));
    }

    @Test
    void inferPurpose_tracking_package() {
        assertEquals("Change tracking", computer.inferPurpose("io.exoreaction.synthesis.tracking"));
    }

    @Test
    void inferPurpose_enrichment_package() {
        assertEquals("Media enrichment", computer.inferPurpose("io.exoreaction.synthesis.enrichment"));
    }

    @Test
    void inferPurpose_summary_package() {
        assertEquals("Reporting / summarization", computer.inferPurpose("io.exoreaction.synthesis.summary"));
    }

    @Test
    void inferPurpose_report_package() {
        assertEquals("Reporting / summarization", computer.inferPurpose("io.exoreaction.synthesis.report"));
    }

    @Test
    void inferPurpose_research_package() {
        assertEquals("Research engine", computer.inferPurpose("io.exoreaction.synthesis.research"));
    }

    @Test
    void inferPurpose_staging_package() {
        assertEquals("Staging pipeline", computer.inferPurpose("io.exoreaction.synthesis.staging"));
    }

    @Test
    void inferPurpose_metrics_package() {
        assertEquals("Operational metrics", computer.inferPurpose("io.exoreaction.synthesis.metrics"));
    }

    @Test
    void inferPurpose_telemetry_package() {
        assertEquals("Operational metrics", computer.inferPurpose("io.exoreaction.synthesis.telemetry"));
    }

    @Test
    void inferPurpose_validate_package() {
        assertEquals("Validation", computer.inferPurpose("io.exoreaction.synthesis.validate"));
    }

    @Test
    void inferPurpose_workspace_package() {
        assertEquals("Workspace management", computer.inferPurpose("io.exoreaction.synthesis.workspace"));
    }

    @Test
    void inferPurpose_update_package() {
        assertEquals("Update management", computer.inferPurpose("io.exoreaction.synthesis.update"));
    }

    @Test
    void inferPurpose_config_package() {
        assertEquals("Configuration management", computer.inferPurpose("io.exoreaction.synthesis.config"));
    }

    @Test
    void inferPurpose_utils_package() {
        assertEquals("Shared utilities", computer.inferPurpose("com.example.utils"));
    }

    @Test
    void inferPurpose_ai_package() {
        assertEquals("AI service integration", computer.inferPurpose("io.exoreaction.synthesis.ai"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    record ModuleProfileRow(String modulePath, String packageName, String purpose,
                            int fanIn, int fanOut, double instability, int totalFiles,
                            double confidence) {}

    private ModuleProfileRow loadProfile(Connection conn, String wsPath, String modulePath)
            throws SQLException {
        String sql = """
            SELECT module_path, package_name, inferred_purpose,
                   fan_in, fan_out, instability, total_files, confidence
            FROM module_profiles
            WHERE workspace_path = ? AND module_path = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, wsPath);
            ps.setString(2, modulePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ModuleProfileRow(
                            rs.getString("module_path"),
                            rs.getString("package_name"),
                            rs.getString("inferred_purpose"),
                            rs.getInt("fan_in"),
                            rs.getInt("fan_out"),
                            rs.getDouble("instability"),
                            rs.getInt("total_files"),
                            rs.getDouble("confidence")
                    );
                }
            }
        }
        return null;
    }

    // inferPurposeResult confidence tiers

    @Test
    void inferPurposeResult_exactLastSegment_confidence090() {
        var result = computer.inferPurposeResult("io.exoreaction.synthesis.cli");
        assertEquals("CLI command implementations", result.purpose());
        assertEquals(0.90, result.confidence(), 0.001);
    }

    @Test
    void inferPurposeResult_ancestorSegment_confidence075() {
        var result = computer.inferPurposeResult("io.exoreaction.synthesis.cli.subcommand");
        assertEquals("CLI command implementations", result.purpose());
        assertEquals(0.75, result.confidence(), 0.001);
    }

    @Test
    void inferPurposeResult_noMatch_confidence040() {
        var result = computer.inferPurposeResult("com.example.whatever");
        assertEquals("General purpose", result.purpose());
        assertEquals(0.40, result.confidence(), 0.001);
    }

    @Test
    void inferPurposeResult_null_confidence040() {
        var result = computer.inferPurposeResult(null);
        assertEquals("General purpose", result.purpose());
        assertEquals(0.40, result.confidence(), 0.001);
    }

    @Test
    void inferPurposeResult_isolatedPackage_confidence030_overriddenByCaller() {
        // Verify that inferPurpose() (via inferPurposeResult) still returns 0.40 for unknown;
        // the caller overrides to 0.30 when fanIn+fanOut==0.
        var result = computer.inferPurposeResult("com.example.obscure");
        assertEquals(0.40, result.confidence(), 0.001);
    }

    // -----------------------------------------------------------------------
    // Multi-repo isolation (V14)
    // -----------------------------------------------------------------------

    @Test
    void computeAndPersist_multi_repo_separate_profiles() throws SQLException {
        // Two repos in the same workspace sharing the package namespace "com.shared"
        // Repo A: com.shared -> com.a.util (internal)
        repo.upsertDependency(conn, new CodeDependency(WS, "RepoA",
                "RepoA/src/Core.java", "Core", "com.shared",
                "RepoA/src/Util.java", "Util", "com.a.util", "import", false, NOW));
        // Repo B: com.shared -> com.b.util (internal)
        repo.upsertDependency(conn, new CodeDependency(WS, "RepoB",
                "RepoB/src/Core.java", "Core", "com.shared",
                "RepoB/src/Helper.java", "Helper", "com.b.util", "import", false, NOW));

        int count = computer.computeAndPersist(WS, conn);

        // Should produce separate profiles for RepoA/com.shared and RepoB/com.shared
        assertTrue(count >= 4, "Should produce at least 4 profiles (2 repos x 2 packages): " + count);

        // Verify each repo's com.shared has fan_out=1, NOT fan_out=2 (merged)
        ModuleProfileRow repoAShared = loadProfileByRepo(conn, WS, "RepoA", "com/shared");
        ModuleProfileRow repoBShared = loadProfileByRepo(conn, WS, "RepoB", "com/shared");
        assertNotNull(repoAShared, "RepoA com/shared profile should exist");
        assertNotNull(repoBShared, "RepoB com/shared profile should exist");
        assertEquals(1, repoAShared.fanOut, "RepoA com/shared fan_out should be 1");
        assertEquals(1, repoBShared.fanOut, "RepoB com/shared fan_out should be 1");
    }

    @Test
    void computeAndPersist_multi_repo_fanin_not_cross_contaminated() throws SQLException {
        // RepoA: com.a.app imports com.shared
        repo.upsertDependency(conn, new CodeDependency(WS, "RepoA",
                "RepoA/src/App.java", "App", "com.a.app",
                "RepoA/src/Shared.java", "Shared", "com.shared", "import", false, NOW));
        // RepoB: com.b.app imports com.shared
        repo.upsertDependency(conn, new CodeDependency(WS, "RepoB",
                "RepoB/src/App.java", "App", "com.b.app",
                "RepoB/src/Shared.java", "Shared", "com.shared", "import", false, NOW));

        computer.computeAndPersist(WS, conn);

        // Each repo's com.shared should have fan_in=1, NOT fan_in=2
        ModuleProfileRow repoAShared = loadProfileByRepo(conn, WS, "RepoA", "com/shared");
        ModuleProfileRow repoBShared = loadProfileByRepo(conn, WS, "RepoB", "com/shared");
        assertNotNull(repoAShared);
        assertNotNull(repoBShared);
        assertEquals(1, repoAShared.fanIn, "RepoA com/shared fan_in should be 1 (not 2)");
        assertEquals(1, repoBShared.fanIn, "RepoB com/shared fan_in should be 1 (not 2)");
    }

    private ModuleProfileRow loadProfileByRepo(Connection conn, String wsPath, String repoName, String modulePath)
            throws SQLException {
        String sql = """
            SELECT module_path, package_name, inferred_purpose,
                   fan_in, fan_out, instability, total_files, confidence
            FROM module_profiles
            WHERE workspace_path = ? AND repo_name = ? AND module_path = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, wsPath);
            ps.setString(2, repoName);
            ps.setString(3, modulePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ModuleProfileRow(
                            rs.getString("module_path"),
                            rs.getString("package_name"),
                            rs.getString("inferred_purpose"),
                            rs.getInt("fan_in"),
                            rs.getInt("fan_out"),
                            rs.getDouble("instability"),
                            rs.getInt("total_files"),
                            rs.getDouble("confidence")
                    );
                }
            }
        }
        return null;
    }

    private int countAllProfiles(Connection conn, String wsPath) throws SQLException {
        String sql = "SELECT COUNT(*) FROM module_profiles WHERE workspace_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, wsPath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
