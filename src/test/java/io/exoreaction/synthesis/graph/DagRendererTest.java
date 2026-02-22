package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DagRenderer} -- ASCII + Mermaid DAG rendering,
 * cycle detection, hotspot identification, and layer violation detection.
 *
 * @since v1.12.2 (CKG-4.01)
 */
class DagRendererTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private Connection conn;
    private CodeGraphRepository repo;
    private DagRenderer renderer;
    private static final String WS = "/test/workspace";
    private static final long NOW = Instant.now().getEpochSecond();

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        conn = db.getConnection();
        repo = new CodeGraphRepository();
        renderer = new DagRenderer(repo);
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Insert a dependency edge.
     */
    private void insertDep(String srcFile, String srcClass, String srcPkg,
                           String tgtFile, String tgtClass, String tgtPkg,
                           boolean external) throws SQLException {
        repo.upsertDependency(conn, new CodeDependency(
                WS, "", srcFile, srcClass, srcPkg,
                tgtFile, tgtClass, tgtPkg,
                "import", external, NOW));
    }

    /**
     * Insert a module profile directly.
     */
    private void insertProfile(String modulePath, String packageName,
                               int fanIn, int fanOut, double instability,
                               int totalFiles) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO module_profiles (
                workspace_path, repo_name, module_path, package_name, inferred_purpose,
                fan_in, fan_out, instability, total_files, confidence, last_computed
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, WS);
            ps.setString(2, "");
            ps.setString(3, modulePath);
            ps.setString(4, packageName);
            ps.setString(5, "Test purpose");
            ps.setInt(6, fanIn);
            ps.setInt(7, fanOut);
            ps.setDouble(8, instability);
            ps.setInt(9, totalFiles);
            ps.setDouble(10, 0.8);
            ps.setLong(11, NOW);
            ps.executeUpdate();
        }
    }

    // -----------------------------------------------------------------------
    // renderAscii tests
    // -----------------------------------------------------------------------

    @Test
    void renderAscii_empty_graph_returns_empty_message() throws SQLException {
        String result = renderer.renderAscii(WS, conn);
        assertEquals("", result, "Empty graph should return empty string");
    }

    @Test
    void renderAscii_single_package_shows_layer() throws SQLException {
        insertProfile("com/example/core", "com.example.core",
                5, 0, 0.00, 3);

        String result = renderer.renderAscii(WS, conn);

        assertTrue(result.contains("Package dependency graph"),
                "Should have header: " + result);
        assertTrue(result.contains("1 packages"),
                "Should show 1 package: " + result);
        assertTrue(result.contains("Layer 1"),
                "Should show Layer 1 for stable package: " + result);
        assertTrue(result.contains("Foundation"),
                "Should show Foundation label: " + result);
        assertTrue(result.contains("com/example/core"),
                "Should show module path: " + result);
    }

    @Test
    void renderAscii_multiple_layers_shows_correct_grouping() throws SQLException {
        // Layer 1: stable (instability 0.00)
        insertProfile("com/example/core", "com.example.core",
                10, 0, 0.00, 5);
        // Layer 2: core services (instability 0.40)
        insertProfile("com/example/service", "com.example.service",
                3, 2, 0.40, 4);
        // Layer 3: application (instability 0.67)
        insertProfile("com/example/app", "com.example.app",
                1, 2, 0.67, 3);
        // Layer 4: CLI (instability 1.00)
        insertProfile("com/example/cli", "com.example.cli",
                0, 5, 1.00, 2);

        String result = renderer.renderAscii(WS, conn);

        assertTrue(result.contains("4 packages"),
                "Should show 4 packages: " + result);
        assertTrue(result.contains("Layer 1"),
                "Should have Layer 1: " + result);
        assertTrue(result.contains("Layer 2"),
                "Should have Layer 2: " + result);
        assertTrue(result.contains("Layer 3"),
                "Should have Layer 3: " + result);
        assertTrue(result.contains("Layer 4"),
                "Should have Layer 4: " + result);
        assertTrue(result.contains("Foundation"),
                "Layer 1 should be Foundation: " + result);
        assertTrue(result.contains("Core Services"),
                "Layer 2 should be Core Services: " + result);
        assertTrue(result.contains("Application"),
                "Layer 3 should be Application: " + result);
        assertTrue(result.contains("Entry/CLI"),
                "Layer 4 should be Entry/CLI: " + result);
    }

    @Test
    void renderAscii_shows_warning_for_high_instability_non_cli() throws SQLException {
        // Non-CLI package with instability > 0.6 should get warning marker
        insertProfile("com/example/staging", "com.example.staging",
                2, 4, 0.67, 3);

        String result = renderer.renderAscii(WS, conn);

        // Unicode warning sign
        assertTrue(result.contains("\u26a0"),
                "High instability non-CLI should show warning: " + result);
    }

    @Test
    void renderAscii_shows_expected_for_cli_package() throws SQLException {
        insertProfile("com/example/cli", "com.example.cli",
                0, 5, 1.00, 2);

        String result = renderer.renderAscii(WS, conn);

        assertTrue(result.contains("(expected)"),
                "CLI package should show (expected): " + result);
    }

    // -----------------------------------------------------------------------
    // renderMermaid tests
    // -----------------------------------------------------------------------

    @Test
    void renderMermaid_produces_valid_mermaid_syntax() throws SQLException {
        insertProfile("com/example/core", "com.example.core",
                5, 0, 0.00, 3);
        insertProfile("com/example/cli", "com.example.cli",
                0, 1, 1.00, 2);

        // Add an edge from cli to core
        insertDep("src/App.java", "App", "com.example.cli",
                "src/Model.java", "Model", "com.example.core", false);

        String result = renderer.renderMermaid(WS, conn);

        assertTrue(result.startsWith("graph TD"),
                "Should start with 'graph TD': " + result);
        assertTrue(result.contains("com_example_core"),
                "Should contain node ID for core: " + result);
        assertTrue(result.contains("com_example_cli"),
                "Should contain node ID for cli: " + result);
        assertTrue(result.contains("-->"),
                "Should contain edge arrows: " + result);
        assertTrue(result.contains("stability:"),
                "Should contain stability label: " + result);
    }

    @Test
    void renderMermaid_limits_to_30_packages() throws SQLException {
        // Insert 35 packages
        for (int i = 0; i < 35; i++) {
            String pkg = "com.example.pkg" + String.format("%02d", i);
            String mod = pkg.replace('.', '/');
            insertProfile(mod, pkg, 1, 1, 0.5, 1);
        }

        String result = renderer.renderMermaid(WS, conn);

        // Count node declarations (lines containing "[" and "]")
        long nodeCount = result.lines()
                .filter(line -> line.contains("[\"") && line.contains("\"]"))
                .count();
        assertTrue(nodeCount <= 30,
                "Should limit to 30 packages, found " + nodeCount);
    }

    @Test
    void renderMermaid_empty_graph_returns_empty() throws SQLException {
        String result = renderer.renderMermaid(WS, conn);
        assertEquals("", result, "Empty graph should return empty string");
    }

    // -----------------------------------------------------------------------
    // findCycles tests
    // -----------------------------------------------------------------------

    @Test
    void findCycles_detects_mutual_dependency() throws SQLException {
        // A -> B
        insertDep("src/A.java", "A", "com.example.a",
                "src/B.java", "B", "com.example.b", false);
        // B -> A
        insertDep("src/B.java", "B", "com.example.b",
                "src/A.java", "A", "com.example.a", false);

        List<DagRenderer.CircularDep> cycles = renderer.findCycles(WS, conn);

        assertEquals(1, cycles.size(), "Should detect 1 cycle");
        DagRenderer.CircularDep cycle = cycles.get(0);
        // Packages should be in alphabetical order
        assertEquals("com.example.a", cycle.packageA());
        assertEquals("com.example.b", cycle.packageB());
        assertEquals(1, cycle.edgesAtoB());
        assertEquals(1, cycle.edgesBtoA());
    }

    @Test
    void findCycles_no_cycles_returns_empty() throws SQLException {
        // Only A -> B, no reverse
        insertDep("src/A.java", "A", "com.example.a",
                "src/B.java", "B", "com.example.b", false);

        List<DagRenderer.CircularDep> cycles = renderer.findCycles(WS, conn);
        assertTrue(cycles.isEmpty(), "Should find no cycles");
    }

    @Test
    void findCycles_ignores_external_dependencies() throws SQLException {
        // A -> B (internal)
        insertDep("src/A.java", "A", "com.example.a",
                "src/B.java", "B", "com.example.b", false);
        // B -> A (but marked as external)
        insertDep("src/B.java", "B", "com.example.b",
                "src/A.java", "A", "com.example.a", true);

        List<DagRenderer.CircularDep> cycles = renderer.findCycles(WS, conn);
        assertTrue(cycles.isEmpty(), "Should ignore external edges in cycle detection");
    }

    // -----------------------------------------------------------------------
    // findHotspots tests
    // -----------------------------------------------------------------------

    @Test
    void findHotspots_returns_unstable_high_fanin() throws SQLException {
        // Hotspot: instability > 0.7 AND fan_in > 2
        insertProfile("com/example/hotspot", "com.example.hotspot",
                5, 15, 0.75, 3);
        // Not a hotspot: low instability
        insertProfile("com/example/stable", "com.example.stable",
                10, 0, 0.00, 5);

        List<DagRenderer.ModuleProfile> hotspots = renderer.findHotspots(WS, conn);

        assertEquals(1, hotspots.size(), "Should find 1 hotspot");
        assertEquals("com/example/hotspot", hotspots.get(0).modulePath());
    }

    @Test
    void findHotspots_excludes_stable_packages() throws SQLException {
        // Stable package with high fan-in
        insertProfile("com/example/core", "com.example.core",
                20, 0, 0.00, 5);
        // Unstable but low fan-in
        insertProfile("com/example/leaf", "com.example.leaf",
                1, 5, 0.83, 2);

        List<DagRenderer.ModuleProfile> hotspots = renderer.findHotspots(WS, conn);
        assertTrue(hotspots.isEmpty(),
                "Should exclude: stable (low instability) and low fan-in packages");
    }

    @Test
    void findHotspots_sorted_by_fanin_descending() throws SQLException {
        insertProfile("com/example/h1", "com.example.h1",
                3, 10, 0.77, 2);
        insertProfile("com/example/h2", "com.example.h2",
                8, 20, 0.71, 3);
        insertProfile("com/example/h3", "com.example.h3",
                5, 12, 0.71, 4);

        List<DagRenderer.ModuleProfile> hotspots = renderer.findHotspots(WS, conn);

        assertEquals(3, hotspots.size());
        assertEquals(8, hotspots.get(0).fanIn(), "First should have highest fan-in");
        assertEquals(5, hotspots.get(1).fanIn(), "Second should have middle fan-in");
        assertEquals(3, hotspots.get(2).fanIn(), "Third should have lowest fan-in");
    }

    // -----------------------------------------------------------------------
    // findLayerViolations tests
    // -----------------------------------------------------------------------

    @Test
    void findLayerViolations_detects_stable_importing_unstable() throws SQLException {
        // Stable package (low instability)
        insertProfile("com/example/core", "com.example.core",
                10, 1, 0.09, 5);
        // Unstable package (high instability)
        insertProfile("com/example/cli", "com.example.cli",
                0, 5, 1.00, 2);

        // core imports from cli (violation: stable depends on unstable)
        insertDep("src/Model.java", "Model", "com.example.core",
                "src/App.java", "App", "com.example.cli", false);

        List<DagRenderer.LayerViolation> violations = renderer.findLayerViolations(WS, conn);

        assertEquals(1, violations.size(), "Should detect 1 violation");
        DagRenderer.LayerViolation v = violations.get(0);
        assertEquals("com.example.core", v.fromPackage());
        assertEquals("com.example.cli", v.toPackage());
        assertTrue(v.fromInstability() < v.toInstability(),
                "From should be more stable (lower) than to");
    }

    @Test
    void findLayerViolations_no_violation_when_unstable_imports_stable() throws SQLException {
        insertProfile("com/example/core", "com.example.core",
                10, 0, 0.00, 5);
        insertProfile("com/example/cli", "com.example.cli",
                0, 5, 1.00, 2);

        // cli imports from core (not a violation: unstable depends on stable)
        insertDep("src/App.java", "App", "com.example.cli",
                "src/Model.java", "Model", "com.example.core", false);

        List<DagRenderer.LayerViolation> violations = renderer.findLayerViolations(WS, conn);
        assertTrue(violations.isEmpty(),
                "Unstable importing stable is not a violation");
    }

    // -----------------------------------------------------------------------
    // sortedByInstability tests
    // -----------------------------------------------------------------------

    @Test
    void sortedByInstability_descending_order() throws SQLException {
        insertProfile("com/example/core", "com.example.core",
                10, 0, 0.00, 5);
        insertProfile("com/example/service", "com.example.service",
                3, 2, 0.40, 3);
        insertProfile("com/example/cli", "com.example.cli",
                0, 5, 1.00, 2);

        List<DagRenderer.ModuleProfile> sorted = renderer.sortedByInstability(WS, conn);

        assertEquals(3, sorted.size());
        assertEquals(1.00, sorted.get(0).instability(), 0.01, "First should be most unstable");
        assertEquals(0.40, sorted.get(1).instability(), 0.01, "Second should be middle");
        assertEquals(0.00, sorted.get(2).instability(), 0.01, "Third should be most stable");
    }

    // -----------------------------------------------------------------------
    // Layer inference tests
    // -----------------------------------------------------------------------

    @Test
    void layerForInstability_assigns_correct_layers() {
        assertEquals(1, DagRenderer.layerForInstability(0.00));
        assertEquals(1, DagRenderer.layerForInstability(0.25));
        assertEquals(2, DagRenderer.layerForInstability(0.26));
        assertEquals(2, DagRenderer.layerForInstability(0.50));
        assertEquals(3, DagRenderer.layerForInstability(0.51));
        assertEquals(3, DagRenderer.layerForInstability(0.75));
        assertEquals(4, DagRenderer.layerForInstability(0.76));
        assertEquals(4, DagRenderer.layerForInstability(1.00));
    }
}
