package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CodeHealthAnalyzer} -- detects health signals from module profiles.
 *
 * @since v1.12.2 (CKG-2.05)
 */
class CodeHealthAnalyzerTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private Connection conn;
    private CodeGraphRepository repo;
    private ModuleProfileComputer computer;
    private CodeHealthAnalyzer analyzer;
    private static final String WS = "/test/workspace";
    private static final long NOW = Instant.now().getEpochSecond();

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        conn = db.getConnection();
        repo = new CodeGraphRepository();
        computer = new ModuleProfileComputer(repo);
        analyzer = new CodeHealthAnalyzer();
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    // -----------------------------------------------------------------------
    // C001_CIRCULAR_DEPENDENCY
    // -----------------------------------------------------------------------

    @Test
    void analyze_circular_dependency() throws SQLException {
        // A -> B and B -> A (mutual import)
        repo.upsertDependency(conn, new CodeDependency(WS, "src/A.java", "A", "com.a",
                "src/B.java", "B", "com.b", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "src/B.java", "B", "com.b",
                "src/A.java", "A", "com.a", "import", false, NOW));

        computer.computeAndPersist(WS, conn);
        List<CodeHealthSignal> signals = analyzer.analyze(WS, conn);

        assertTrue(signals.stream().anyMatch(s -> s.signalId().equals("C001_CIRCULAR_DEPENDENCY")),
                "Should detect circular dependency between com.a and com.b");

        CodeHealthSignal circular = signals.stream()
                .filter(s -> s.signalId().equals("C001_CIRCULAR_DEPENDENCY"))
                .findFirst().orElse(null);
        assertNotNull(circular);
        assertEquals("HIGH", circular.severity());
        assertTrue(circular.description().contains("<->"),
                "Description should indicate bidirectional: " + circular.description());
    }

    @Test
    void analyze_circular_dependency_package_level_edge_counts() throws SQLException {
        // Multiple class-level edges between same two packages:
        // 3 edges from com.a -> com.b, 2 edges from com.b -> com.a
        repo.upsertDependency(conn, new CodeDependency(WS, "src/A1.java", "A1", "com.a",
                "src/B1.java", "B1", "com.b", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "src/A2.java", "A2", "com.a",
                "src/B1.java", "B1", "com.b", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "src/A3.java", "A3", "com.a",
                "src/B2.java", "B2", "com.b", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "src/B1.java", "B1", "com.b",
                "src/A1.java", "A1", "com.a", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "src/B2.java", "B2", "com.b",
                "src/A2.java", "A2", "com.a", "import", false, NOW));

        computer.computeAndPersist(WS, conn);
        List<CodeHealthSignal> signals = analyzer.analyze(WS, conn);

        CodeHealthSignal circular = signals.stream()
                .filter(s -> s.signalId().equals("C001_CIRCULAR_DEPENDENCY"))
                .findFirst().orElse(null);
        assertNotNull(circular, "Should detect circular dependency");
        // Should report package-level edge counts (3 and 2), not cartesian product (6)
        assertTrue(circular.description().contains("3 edges"),
                "Should report 3 edges a->b, got: " + circular.description());
        assertTrue(circular.description().contains("2 edges"),
                "Should report 2 edges b->a, got: " + circular.description());
    }

    @Test
    void analyze_no_circular_for_one_way_dependency() throws SQLException {
        // Only A -> B, not B -> A
        repo.upsertDependency(conn, new CodeDependency(WS, "src/A.java", "A", "com.a",
                "src/B.java", "B", "com.b", "import", false, NOW));

        computer.computeAndPersist(WS, conn);
        List<CodeHealthSignal> signals = analyzer.analyze(WS, conn);

        assertTrue(signals.stream().noneMatch(s -> s.signalId().equals("C001_CIRCULAR_DEPENDENCY")),
                "One-way dependency should not trigger C001");
    }

    // -----------------------------------------------------------------------
    // C012_GOD_PACKAGE
    // -----------------------------------------------------------------------

    @Test
    void analyze_god_package() throws SQLException {
        // Create a package with 16 files (> 15 threshold)
        for (int i = 0; i < 16; i++) {
            repo.upsertDependency(conn, new CodeDependency(WS,
                    "src/File" + i + ".java", "File" + i, "com.big",
                    null, "String", "java.lang", "import", true, NOW));
        }

        computer.computeAndPersist(WS, conn);
        List<CodeHealthSignal> signals = analyzer.analyze(WS, conn);

        assertTrue(signals.stream().anyMatch(s -> s.signalId().equals("C012_GOD_PACKAGE")),
                "Should detect god package with 16 files");

        CodeHealthSignal god = signals.stream()
                .filter(s -> s.signalId().equals("C012_GOD_PACKAGE"))
                .findFirst().orElse(null);
        assertNotNull(god);
        assertEquals("MEDIUM", god.severity());
        assertTrue(god.description().contains("16"), "Should mention 16 files");
    }

    @Test
    void analyze_no_god_package_under_threshold() throws SQLException {
        // Create a package with 15 files (= 15, not > 15)
        for (int i = 0; i < 15; i++) {
            repo.upsertDependency(conn, new CodeDependency(WS,
                    "src/File" + i + ".java", "File" + i, "com.normal",
                    null, "String", "java.lang", "import", true, NOW));
        }

        computer.computeAndPersist(WS, conn);
        List<CodeHealthSignal> signals = analyzer.analyze(WS, conn);

        assertTrue(signals.stream().noneMatch(s -> s.signalId().equals("C012_GOD_PACKAGE")),
                "15 files should not trigger god package (threshold is > 15)");
    }

    // -----------------------------------------------------------------------
    // C013_UNSTABLE_CORE
    // -----------------------------------------------------------------------

    @Test
    void analyze_unstable_core_package() throws SQLException {
        // Create a "core" package that imports more than it is imported (instability > 0.5)
        // core imports from 3 packages but only 1 package imports core
        repo.upsertDependency(conn, new CodeDependency(WS, "src/Core.java", "Core", "com.example.core",
                "src/Util.java", "Util", "com.example.util", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "src/Core.java", "Core", "com.example.core",
                "src/Db.java", "Db", "com.example.db", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "src/Core.java", "Core", "com.example.core",
                "src/Cfg.java", "Cfg", "com.example.config", "import", false, NOW));
        // Only one package imports core
        repo.upsertDependency(conn, new CodeDependency(WS, "src/App.java", "App", "com.example.app",
                "src/Core.java", "Core", "com.example.core", "import", false, NOW));

        computer.computeAndPersist(WS, conn);
        List<CodeHealthSignal> signals = analyzer.analyze(WS, conn);

        assertTrue(signals.stream().anyMatch(s -> s.signalId().equals("C013_UNSTABLE_CORE")),
                "Core package with instability > 0.5 should trigger C013");

        CodeHealthSignal unstable = signals.stream()
                .filter(s -> s.signalId().equals("C013_UNSTABLE_CORE"))
                .findFirst().orElse(null);
        assertNotNull(unstable);
        assertEquals("HIGH", unstable.severity());
    }

    // -----------------------------------------------------------------------
    // C014_ORPHAN_CODE
    // -----------------------------------------------------------------------

    @Test
    void analyze_orphan_code() throws SQLException {
        // Package with no internal fan-in or fan-out (only external deps)
        repo.upsertDependency(conn, new CodeDependency(WS, "src/Orphan.java", "Orphan", "com.orphan",
                null, "String", "java.lang", "import", true, NOW));

        computer.computeAndPersist(WS, conn);
        List<CodeHealthSignal> signals = analyzer.analyze(WS, conn);

        assertTrue(signals.stream().anyMatch(s -> s.signalId().equals("C014_ORPHAN_CODE")),
                "Isolated package should trigger C014");

        CodeHealthSignal orphan = signals.stream()
                .filter(s -> s.signalId().equals("C014_ORPHAN_CODE"))
                .findFirst().orElse(null);
        assertNotNull(orphan);
        assertEquals("LOW", orphan.severity());
    }

    @Test
    void analyze_orphan_code_excluded_for_cli() throws SQLException {
        // CLI packages are expected to have no fan-in -- should NOT trigger C014
        repo.upsertDependency(conn, new CodeDependency(WS, "src/Cli.java", "Cli", "com.example.cli",
                null, "String", "java.lang", "import", true, NOW));

        computer.computeAndPersist(WS, conn);
        List<CodeHealthSignal> signals = analyzer.analyze(WS, conn);

        assertTrue(signals.stream()
                .filter(s -> s.signalId().equals("C014_ORPHAN_CODE"))
                .noneMatch(s -> s.modulePath().contains("cli")),
                "CLI package should be excluded from orphan detection");
    }

    // -----------------------------------------------------------------------
    // C010_HIGH_FAN_IN_NO_TESTS
    // -----------------------------------------------------------------------

    @Test
    void analyze_high_fan_in_no_tests() throws SQLException {
        // Create a package imported by 6 others (fan_in > 5) with no test package
        String targetPkg = "com.example.shared";
        for (int i = 0; i < 6; i++) {
            repo.upsertDependency(conn, new CodeDependency(WS,
                    "src/User" + i + ".java", "User" + i, "com.user" + i,
                    "src/Shared.java", "Shared", targetPkg, "import", false, NOW));
        }
        // Shared package itself
        repo.upsertDependency(conn, new CodeDependency(WS, "src/Shared.java", "Shared", targetPkg,
                null, "String", "java.lang", "import", true, NOW));

        computer.computeAndPersist(WS, conn);
        List<CodeHealthSignal> signals = analyzer.analyze(WS, conn);

        assertTrue(signals.stream().anyMatch(s -> s.signalId().equals("C010_HIGH_FAN_IN_NO_TESTS")),
                "High fan-in package without tests should trigger C010");
    }

    @Test
    void analyze_high_fan_in_with_test_files_on_disk_no_C010() throws SQLException, IOException {
        // Use tempDir as the workspace so we can create test files on disk
        String wsPath = tempDir.toString();

        // Create a package imported by 6 others (fan_in > 5)
        String targetPkg = "com.example.shared";
        for (int i = 0; i < 6; i++) {
            repo.upsertDependency(conn, new CodeDependency(wsPath,
                    "src/User" + i + ".java", "User" + i, "com.user" + i,
                    "src/Shared.java", "Shared", targetPkg, "import", false, NOW));
        }
        repo.upsertDependency(conn, new CodeDependency(wsPath, "src/Shared.java", "Shared", targetPkg,
                null, "String", "java.lang", "import", true, NOW));

        // Create corresponding test directory with a *Test.java file
        Path testDir = tempDir.resolve("src/test/java/com/example/shared");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("SharedTest.java"), "class SharedTest {}");

        computer.computeAndPersist(wsPath, conn);
        List<CodeHealthSignal> signals = analyzer.analyze(wsPath, conn);

        assertTrue(signals.stream().noneMatch(s -> s.signalId().equals("C010_HIGH_FAN_IN_NO_TESTS")),
                "Package with test files on disk should NOT trigger C010");
    }

    // -----------------------------------------------------------------------
    // C021_DOCUMENTATION_GAP
    // -----------------------------------------------------------------------

    @Test
    void analyze_documentation_gap() throws SQLException {
        // Create a high fan-in package whose name doesn't match any known heuristic
        String targetPkg = "com.example.stuff";
        for (int i = 0; i < 6; i++) {
            repo.upsertDependency(conn, new CodeDependency(WS,
                    "src/User" + i + ".java", "User" + i, "com.user" + i,
                    "src/Stuff.java", "Stuff", targetPkg, "import", false, NOW));
        }
        repo.upsertDependency(conn, new CodeDependency(WS, "src/Stuff.java", "Stuff", targetPkg,
                null, "String", "java.lang", "import", true, NOW));

        computer.computeAndPersist(WS, conn);
        List<CodeHealthSignal> signals = analyzer.analyze(WS, conn);

        assertTrue(signals.stream().anyMatch(s -> s.signalId().equals("C021_DOCUMENTATION_GAP")),
                "High fan-in package with 'General purpose' should trigger C021");
    }

    // -----------------------------------------------------------------------
    // Healthy workspace
    // -----------------------------------------------------------------------

    @Test
    void analyze_no_signals_for_healthy_workspace() throws SQLException {
        // A simple clean dependency: core <- service <- cli
        // core has reasonable fan-in, no circulars, no god packages
        repo.upsertDependency(conn, new CodeDependency(WS, "src/Service.java", "Service", "com.service",
                "src/Core.java", "Core", "com.core", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "src/Cli.java", "Cli", "com.cli",
                "src/Service.java", "Service", "com.service", "import", false, NOW));

        computer.computeAndPersist(WS, conn);
        List<CodeHealthSignal> signals = analyzer.analyze(WS, conn);

        // Should be clean or at most LOW severity (orphan/doc gap possible for small graphs)
        assertTrue(signals.stream().noneMatch(s -> "HIGH".equals(s.severity())),
                "Healthy workspace should have no HIGH severity signals: " + signals);
    }

    // -----------------------------------------------------------------------
    // Severity ordering
    // -----------------------------------------------------------------------

    @Test
    void analyze_signals_sorted_by_severity() throws SQLException {
        // Set up conditions for multiple signal types
        // C001 (HIGH): circular dependency
        repo.upsertDependency(conn, new CodeDependency(WS, "src/A.java", "A", "com.a",
                "src/B.java", "B", "com.b", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "src/B.java", "B", "com.b",
                "src/A.java", "A", "com.a", "import", false, NOW));

        // C014 (LOW): orphan
        repo.upsertDependency(conn, new CodeDependency(WS, "src/Orphan.java", "Orphan", "com.orphan",
                null, "List", "java.util", "import", true, NOW));

        computer.computeAndPersist(WS, conn);
        List<CodeHealthSignal> signals = analyzer.analyze(WS, conn);

        if (signals.size() >= 2) {
            // Verify HIGH signals come before LOW
            int firstHighIdx = -1;
            int lastLowIdx = -1;
            for (int i = 0; i < signals.size(); i++) {
                if ("HIGH".equals(signals.get(i).severity()) && firstHighIdx < 0) {
                    firstHighIdx = i;
                }
                if ("LOW".equals(signals.get(i).severity())) {
                    lastLowIdx = i;
                }
            }
            if (firstHighIdx >= 0 && lastLowIdx >= 0) {
                assertTrue(firstHighIdx < lastLowIdx,
                        "HIGH signals should appear before LOW signals");
            }
        }
    }
}
