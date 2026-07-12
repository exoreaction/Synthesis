package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link QualityGapDetector} -- detects structural quality gaps in modules.
 *
 * @since v1.12.2 (CKG-3.05)
 */
class QualityGapDetectorTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private Connection conn;
    private CodeGraphRepository repo;
    private ModuleProfileComputer computer;
    private QualityGapDetector detector;
    private Path workspaceRoot;
    private static final String WS = "/test/workspace";
    private static final long NOW = Instant.now().getEpochSecond();

    @BeforeEach
    void setUp() throws Exception {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        conn = db.getConnection();
        repo = new CodeGraphRepository();
        computer = new ModuleProfileComputer(repo);
        detector = new QualityGapDetector(repo);
        workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    // -----------------------------------------------------------------------
    // MISSING_TESTS
    // -----------------------------------------------------------------------

    @Test
    void detect_missing_tests_for_module_with_no_test_files() throws Exception {
        // Module com.example.core has 2 files but no test classes
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/main/java/Core.java", "Core", "com.example.core",
                null, "String", "java.lang", "import", true, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/main/java/CoreHelper.java", "CoreHelper", "com.example.core",
                null, "List", "java.util", "import", true, NOW));

        computer.computeAndPersist(WS, conn);
        List<QualityGap> gaps = detector.detect(WS, workspaceRoot, conn);

        assertTrue(gaps.stream().anyMatch(g -> "MISSING_TESTS".equals(g.gapType())
                        && g.modulePath().contains("core")),
                "Should detect MISSING_TESTS for module without test files: " + gaps);
    }

    @Test
    void detect_no_gap_when_test_files_exist() throws Exception {
        // Module com.example.core
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/main/java/Core.java", "Core", "com.example.core",
                null, "String", "java.lang", "import", true, NOW));

        // Corresponding test
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/test/java/CoreTest.java", "CoreTest", "com.example.core",
                "src/main/java/Core.java", "Core", "com.example.core", "import", false, NOW));

        computer.computeAndPersist(WS, conn);
        List<QualityGap> gaps = detector.detect(WS, workspaceRoot, conn);

        assertTrue(gaps.stream().noneMatch(g -> "MISSING_TESTS".equals(g.gapType())
                        && g.modulePath().contains("core")),
                "Should NOT detect MISSING_TESTS when test files exist: " + gaps);
    }

    // -----------------------------------------------------------------------
    // MISSING_README
    // -----------------------------------------------------------------------

    @Test
    void detect_missing_readme_for_large_module() throws Exception {
        // Create a module with 6 files (> 5 threshold) and no README.md
        for (int i = 0; i < 6; i++) {
            repo.upsertDependency(conn, new CodeDependency(WS, "",
                    "src/main/java/File" + i + ".java", "File" + i, "com.example.big",
                    null, "String", "java.lang", "import", true, NOW));
        }

        computer.computeAndPersist(WS, conn);
        List<QualityGap> gaps = detector.detect(WS, workspaceRoot, conn);

        assertTrue(gaps.stream().anyMatch(g -> "MISSING_README".equals(g.gapType())
                        && g.modulePath().contains("big")),
                "Should detect MISSING_README for module with 6 files and no README: " + gaps);
    }

    @Test
    void detect_no_readme_gap_for_small_module() throws Exception {
        // Create a module with 5 files (= 5, not > 5) and no README.md
        for (int i = 0; i < 5; i++) {
            repo.upsertDependency(conn, new CodeDependency(WS, "",
                    "src/main/java/File" + i + ".java", "File" + i, "com.example.small",
                    null, "String", "java.lang", "import", true, NOW));
        }

        computer.computeAndPersist(WS, conn);
        List<QualityGap> gaps = detector.detect(WS, workspaceRoot, conn);

        assertTrue(gaps.stream().noneMatch(g -> "MISSING_README".equals(g.gapType())
                        && g.modulePath().contains("small")),
                "Should NOT detect MISSING_README for module with only 5 files: " + gaps);
    }

    // -----------------------------------------------------------------------
    // MISSING_INTERFACE
    // -----------------------------------------------------------------------

    @Test
    void detect_missing_interface_for_high_fan_in() throws Exception {
        // Create a module imported by 6 others (fan_in > 5) with no implements edges
        String targetPkg = "com.example.shared";
        for (int i = 0; i < 6; i++) {
            repo.upsertDependency(conn, new CodeDependency(WS, "",
                    "src/main/java/User" + i + ".java", "User" + i, "com.user" + i,
                    "src/main/java/Shared.java", "Shared", targetPkg, "import", false, NOW));
        }
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/main/java/Shared.java", "Shared", targetPkg,
                null, "String", "java.lang", "import", true, NOW));

        computer.computeAndPersist(WS, conn);
        List<QualityGap> gaps = detector.detect(WS, workspaceRoot, conn);

        assertTrue(gaps.stream().anyMatch(g -> "MISSING_INTERFACE".equals(g.gapType())
                        && g.modulePath().contains("shared")),
                "Should detect MISSING_INTERFACE for high fan-in module without interfaces: " + gaps);
    }

    @Test
    void detect_no_interface_gap_for_low_fan_in() throws Exception {
        // Module with fan_in = 2 (not > 5)
        repo.upsertDependency(conn, new CodeDependency(WS, "",
                "src/main/java/A.java", "A", "com.a",
                "src/main/java/Util.java", "Util", "com.util", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "",
                "src/main/java/B.java", "B", "com.b",
                "src/main/java/Util.java", "Util", "com.util", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/main/java/Util.java", "Util", "com.util",
                null, "String", "java.lang", "import", true, NOW));

        computer.computeAndPersist(WS, conn);
        List<QualityGap> gaps = detector.detect(WS, workspaceRoot, conn);

        assertTrue(gaps.stream().noneMatch(g -> "MISSING_INTERFACE".equals(g.gapType())
                        && g.modulePath().contains("util")),
                "Should NOT detect MISSING_INTERFACE for low fan-in module: " + gaps);
    }

    @Test
    void detect_no_interface_gap_when_kotlin_supertype_edge_targets_package() throws Exception {
        // Regression test for #441: Kotlin structural edges use dependency_type "supertype"
        // (colon syntax can't distinguish extends/implements). A high fan-in Kotlin module
        // whose interfaces ARE implemented must not be flagged MISSING_INTERFACE.
        String targetPkg = "com.example.kshared";
        for (int i = 0; i < 6; i++) {
            repo.upsertDependency(conn, new CodeDependency(WS, "",
                    "src/main/kotlin/User" + i + ".kt", "User" + i, "com.user" + i,
                    "src/main/kotlin/Shared.kt", "Shared", targetPkg, "import", false, NOW));
        }
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/main/kotlin/Shared.kt", "Shared", targetPkg,
                null, "String", "java.lang", "import", true, NOW));
        // The Kotlin equivalent of an implements edge: Impl : Shared
        repo.upsertDependency(conn, new CodeDependency(WS, "",
                "src/main/kotlin/Impl.kt", "Impl", "com.impl",
                "src/main/kotlin/Shared.kt", "Shared", targetPkg, "supertype", false, NOW));

        computer.computeAndPersist(WS, conn);
        List<QualityGap> gaps = detector.detect(WS, workspaceRoot, conn);

        assertTrue(gaps.stream().noneMatch(g -> "MISSING_INTERFACE".equals(g.gapType())
                        && g.modulePath().contains("kshared")),
                "Kotlin 'supertype' edge should count as interface evidence: " + gaps);
    }

    // -----------------------------------------------------------------------
    // UNDOCUMENTED_HIGH_VALUE
    // -----------------------------------------------------------------------

    @Test
    void detect_undocumented_high_value_module() throws Exception {
        // Module with fan_in > 8 and "General purpose" (unknown package name)
        String targetPkg = "com.example.stuff";
        for (int i = 0; i < 9; i++) {
            repo.upsertDependency(conn, new CodeDependency(WS, "",
                    "src/main/java/User" + i + ".java", "User" + i, "com.pkg" + i,
                    "src/main/java/Stuff.java", "Stuff", targetPkg, "import", false, NOW));
        }
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/main/java/Stuff.java", "Stuff", targetPkg,
                null, "String", "java.lang", "import", true, NOW));

        computer.computeAndPersist(WS, conn);
        List<QualityGap> gaps = detector.detect(WS, workspaceRoot, conn);

        assertTrue(gaps.stream().anyMatch(g -> "UNDOCUMENTED_HIGH_VALUE".equals(g.gapType())
                        && g.modulePath().contains("stuff")),
                "Should detect UNDOCUMENTED_HIGH_VALUE for high fan-in module with 'General purpose': " + gaps);
    }

    // -----------------------------------------------------------------------
    // detectAndPersist idempotency
    // -----------------------------------------------------------------------

    @Test
    void detectAndPersist_is_idempotent() throws Exception {
        // Module with missing tests
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/main/java/Core.java", "Core", "com.example.core",
                null, "String", "java.lang", "import", true, NOW));

        computer.computeAndPersist(WS, conn);

        // First detection
        int count1 = detector.detectAndPersist(WS, workspaceRoot, conn);
        assertTrue(count1 > 0, "First detection should find gaps");

        // Second detection should replace, not accumulate
        int count2 = detector.detectAndPersist(WS, workspaceRoot, conn);
        assertEquals(count1, count2, "Second detection should produce same count (idempotent)");

        // Verify no duplicates in the database
        int totalGaps = repo.countQualityGaps(conn, WS);
        assertEquals(count2, totalGaps, "No duplicate gaps should exist in database");
    }

    // -----------------------------------------------------------------------
    // MISSING_PACKAGE_INFO
    // -----------------------------------------------------------------------

    @Test
    void detect_missing_package_info_for_important_module() throws Exception {
        // Module with fan_in > 3
        String targetPkg = "com.example.important";
        for (int i = 0; i < 4; i++) {
            repo.upsertDependency(conn, new CodeDependency(WS, "",
                    "src/main/java/Dep" + i + ".java", "Dep" + i, "com.dep" + i,
                    "src/main/java/Important.java", "Important", targetPkg, "import", false, NOW));
        }
        repo.upsertDependency(conn, new CodeDependency(WS, "", "src/main/java/Important.java", "Important", targetPkg,
                null, "String", "java.lang", "import", true, NOW));

        computer.computeAndPersist(WS, conn);
        List<QualityGap> gaps = detector.detect(WS, workspaceRoot, conn);

        assertTrue(gaps.stream().anyMatch(g -> "MISSING_PACKAGE_INFO".equals(g.gapType())
                        && g.modulePath().contains("important")),
                "Should detect MISSING_PACKAGE_INFO for module with fan_in > 3: " + gaps);
    }

    @Test
    void detect_no_package_info_gap_for_low_fan_in() throws Exception {
        // Module with fan_in = 2 (not > 3)
        repo.upsertDependency(conn, new CodeDependency(WS, "",
                "src/main/java/A.java", "A", "com.a",
                "src/main/java/Trivial.java", "Trivial", "com.trivial", "import", false, NOW));
        repo.upsertDependency(conn, new CodeDependency(WS, "",
                "src/main/java/B.java", "B", "com.b",
                "src/main/java/Trivial.java", "Trivial", "com.trivial", "import", false, NOW));

        computer.computeAndPersist(WS, conn);
        List<QualityGap> gaps = detector.detect(WS, workspaceRoot, conn);

        assertTrue(gaps.stream().noneMatch(g -> "MISSING_PACKAGE_INFO".equals(g.gapType())
                        && g.modulePath().contains("trivial")),
                "Should NOT detect MISSING_PACKAGE_INFO for module with fan_in <= 3: " + gaps);
    }
}
