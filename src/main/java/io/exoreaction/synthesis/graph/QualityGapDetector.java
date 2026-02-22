package io.exoreaction.synthesis.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Detects structural quality gaps in modules by cross-referencing
 * module profiles, dependency graph, and filesystem state.
 *
 * <p>Detected gap types:
 * <ul>
 *   <li><b>MISSING_TESTS</b> (HIGH) -- module has code but no test files</li>
 *   <li><b>MISSING_PACKAGE_INFO</b> (LOW) -- important module lacks package-info.java</li>
 *   <li><b>MISSING_README</b> (LOW) -- large module lacks README.md</li>
 *   <li><b>MISSING_INTERFACE</b> (MEDIUM) -- high fan-in module lacks interface abstraction</li>
 *   <li><b>UNDOCUMENTED_HIGH_VALUE</b> (MEDIUM) -- high fan-in module with unclear purpose</li>
 * </ul>
 *
 * @since v1.12.2 (CKG-3.01)
 */
public class QualityGapDetector {

    private static final Logger LOG = Logger.getLogger(QualityGapDetector.class.getName());

    private final CodeGraphRepository repository;

    public QualityGapDetector(CodeGraphRepository repository) {
        this.repository = repository;
    }

    /**
     * Detect all quality gaps across all modules in the workspace.
     * Persists results to code_quality_gaps table (clears old gaps first).
     *
     * @param workspacePath the workspace root path string
     * @param workspaceRoot the workspace root as a Path (for filesystem checks)
     * @param conn          open SQLite connection
     * @return number of gaps detected
     */
    public int detectAndPersist(String workspacePath, Path workspaceRoot,
                                 Connection conn) throws SQLException {
        List<QualityGap> gaps = detect(workspacePath, workspaceRoot, conn);

        // Clear old gaps for this workspace, then insert new ones
        repository.deleteAllQualityGaps(conn, workspacePath);

        long now = Instant.now().getEpochSecond();
        for (QualityGap gap : gaps) {
            repository.upsertQualityGap(conn, workspacePath, gap, now);
        }

        LOG.fine("Detected " + gaps.size() + " quality gaps for " + workspacePath);
        return gaps.size();
    }

    /**
     * Detect gaps without persisting (for dry-run / testing).
     *
     * @param workspacePath the workspace root path string
     * @param workspaceRoot the workspace root as a Path (for filesystem checks)
     * @param conn          open SQLite connection
     * @return list of detected quality gaps
     */
    public List<QualityGap> detect(String workspacePath, Path workspaceRoot,
                                    Connection conn) throws SQLException {
        List<QualityGap> gaps = new ArrayList<>();

        // Load all module profiles
        List<ModuleProfileRow> profiles = loadModuleProfiles(conn, workspacePath);
        if (profiles.isEmpty()) {
            return gaps;
        }

        // Collect test-related source classes for MISSING_TESTS detection
        Set<String> testPackages = collectTestPackages(conn, workspacePath);

        for (ModuleProfileRow profile : profiles) {
            // Skip test packages themselves
            if (profile.packageName != null && profile.packageName.contains("test")) {
                continue;
            }

            gaps.addAll(detectMissingTests(profile, testPackages));
            gaps.addAll(detectMissingPackageInfo(profile, workspaceRoot));
            gaps.addAll(detectMissingReadme(profile, workspaceRoot));
            gaps.addAll(detectMissingInterface(profile, conn, workspacePath));
            gaps.addAll(detectUndocumentedHighValue(profile));
        }

        return gaps;
    }

    // -----------------------------------------------------------------------
    // MISSING_TESTS (HIGH)
    // -----------------------------------------------------------------------

    /**
     * A module has total_files > 0 but no corresponding test files exist.
     * Checks if any test class references classes from this module's package.
     */
    private List<QualityGap> detectMissingTests(ModuleProfileRow profile,
                                                  Set<String> testPackages) {
        if (profile.totalFiles <= 0) {
            return List.of();
        }

        // Check if any test package corresponds to this module
        boolean hasTests = testPackages.stream().anyMatch(tp ->
                tp.contains(profile.packageName) || profile.packageName.contains(tp));

        if (hasTests) {
            return List.of();
        }

        return List.of(new QualityGap(
                profile.modulePath,
                "MISSING_TESTS",
                "HIGH",
                "Module has " + profile.totalFiles + " source file(s) but no corresponding test files",
                null,
                "Add test classes in src/test/ for this module's classes"
        ));
    }

    // -----------------------------------------------------------------------
    // MISSING_PACKAGE_INFO (LOW)
    // -----------------------------------------------------------------------

    /**
     * No package-info.java found for the module path. Only flag for modules with fan_in > 3.
     */
    private List<QualityGap> detectMissingPackageInfo(ModuleProfileRow profile,
                                                        Path workspaceRoot) {
        if (profile.fanIn <= 3) {
            return List.of();
        }

        // Convert module path (slash-separated) to filesystem path
        // Check common source locations
        Path srcMain = workspaceRoot.resolve("src/main/java").resolve(profile.modulePath)
                .resolve("package-info.java");

        if (Files.exists(srcMain)) {
            return List.of();
        }

        // Also check direct path under workspace root
        Path directPath = workspaceRoot.resolve(profile.modulePath).resolve("package-info.java");
        if (Files.exists(directPath)) {
            return List.of();
        }

        return List.of(new QualityGap(
                profile.modulePath,
                "MISSING_PACKAGE_INFO",
                "LOW",
                "Important module (fan-in " + profile.fanIn + ") lacks package-info.java",
                profile.modulePath + "/package-info.java",
                "Add package-info.java with Javadoc describing this package's responsibility"
        ));
    }

    // -----------------------------------------------------------------------
    // MISSING_README (LOW)
    // -----------------------------------------------------------------------

    /**
     * No README.md in the module's directory. Only flag for modules with total_files > 5.
     */
    private List<QualityGap> detectMissingReadme(ModuleProfileRow profile,
                                                   Path workspaceRoot) {
        if (profile.totalFiles <= 5) {
            return List.of();
        }

        // Check common source locations
        Path srcMain = workspaceRoot.resolve("src/main/java").resolve(profile.modulePath)
                .resolve("README.md");
        if (Files.exists(srcMain)) {
            return List.of();
        }

        Path directPath = workspaceRoot.resolve(profile.modulePath).resolve("README.md");
        if (Files.exists(directPath)) {
            return List.of();
        }

        return List.of(new QualityGap(
                profile.modulePath,
                "MISSING_README",
                "LOW",
                "Module has " + profile.totalFiles + " files but no README.md",
                profile.modulePath + "/README.md",
                "Add README.md explaining this module's responsibilities"
        ));
    }

    // -----------------------------------------------------------------------
    // MISSING_INTERFACE (MEDIUM)
    // -----------------------------------------------------------------------

    /**
     * A module has fan_in > 5 but no interface classes detected.
     * Checks for classes with "implements" edges pointing to something in the same package.
     */
    private List<QualityGap> detectMissingInterface(ModuleProfileRow profile,
                                                      Connection conn,
                                                      String workspacePath) throws SQLException {
        if (profile.fanIn <= 5) {
            return List.of();
        }

        // Check if any class in this package has an "implements" edge from a class in the same package
        // Or if any class in this package is the target of an "implements" edge
        boolean hasInterface = checkForInterfaces(conn, workspacePath, profile.packageName);

        if (hasInterface) {
            return List.of();
        }

        return List.of(new QualityGap(
                profile.modulePath,
                "MISSING_INTERFACE",
                "MEDIUM",
                "High fan-in module (" + profile.fanIn + " dependents) lacks interface abstraction",
                null,
                "Extract interface for top-level classes to reduce coupling"
        ));
    }

    /**
     * Checks if any class in the given package is an interface target
     * (i.e., some class implements a class from this package).
     */
    private boolean checkForInterfaces(Connection conn, String workspacePath,
                                         String packageName) throws SQLException {
        // Check if any dependency edge of type "implements" targets a class in this package
        String sql = """
            SELECT COUNT(*) FROM code_dependencies
            WHERE workspace_path = ? AND target_package = ? AND dependency_type = 'implements'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, packageName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return true;
                }
            }
        }

        // Also check if any source class in this package has "implements" edges
        // (meaning the package defines interfaces that are implemented)
        String sql2 = """
            SELECT COUNT(*) FROM code_dependencies
            WHERE workspace_path = ? AND source_package = ? AND dependency_type = 'implements'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql2)) {
            ps.setString(1, workspacePath);
            ps.setString(2, packageName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    // -----------------------------------------------------------------------
    // UNDOCUMENTED_HIGH_VALUE (MEDIUM)
    // -----------------------------------------------------------------------

    /**
     * Module has fan_in > 8 and inferred_purpose = "General purpose".
     * High-value module with unclear intent.
     */
    private List<QualityGap> detectUndocumentedHighValue(ModuleProfileRow profile) {
        if (profile.fanIn <= 8) {
            return List.of();
        }

        if (!"General purpose".equals(profile.inferredPurpose)) {
            return List.of();
        }

        return List.of(new QualityGap(
                profile.modulePath,
                "UNDOCUMENTED_HIGH_VALUE",
                "MEDIUM",
                "High-value module (fan-in " + profile.fanIn + ") has unclear purpose (\"General purpose\")",
                null,
                "Add package-info.java or rename to better communicate intent"
        ));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Loads all module profiles for the workspace.
     */
    private List<ModuleProfileRow> loadModuleProfiles(Connection conn, String workspacePath)
            throws SQLException {
        List<ModuleProfileRow> profiles = new ArrayList<>();
        String sql = """
            SELECT module_path, package_name, inferred_purpose,
                   fan_in, fan_out, instability, total_files
            FROM module_profiles
            WHERE workspace_path = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    profiles.add(new ModuleProfileRow(
                            rs.getString("module_path"),
                            rs.getString("package_name"),
                            rs.getString("inferred_purpose"),
                            rs.getInt("fan_in"),
                            rs.getInt("fan_out"),
                            rs.getDouble("instability"),
                            rs.getInt("total_files")
                    ));
                }
            }
        }
        return profiles;
    }

    /**
     * Collects all package names that appear as test packages.
     * Uses source_file paths containing "test" (case-insensitive) or
     * source_class names ending with "Test".
     */
    private Set<String> collectTestPackages(Connection conn, String workspacePath)
            throws SQLException {
        Set<String> testPackages = new HashSet<>();

        // Find packages with test files (source_file contains src/test or Test in class name)
        String sql = """
            SELECT DISTINCT source_package FROM code_dependencies
            WHERE workspace_path = ?
              AND (source_file LIKE '%src/test/%' OR source_class LIKE '%Test')
              AND source_package != ''
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pkg = rs.getString(1);
                    if (pkg != null && !pkg.isBlank()) {
                        testPackages.add(pkg);
                    }
                }
            }
        }

        // Also check module_profiles for test-like packages
        String sql2 = """
            SELECT DISTINCT package_name FROM module_profiles
            WHERE workspace_path = ?
              AND (package_name LIKE '%test%' OR package_name LIKE '%tests%')
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql2)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pkg = rs.getString(1);
                    if (pkg != null && !pkg.isBlank()) {
                        testPackages.add(pkg);
                    }
                }
            }
        }

        return testPackages;
    }

    /**
     * Internal record for module profile data used during detection.
     */
    record ModuleProfileRow(
            String modulePath,
            String packageName,
            String inferredPurpose,
            int fanIn,
            int fanOut,
            double instability,
            int totalFiles
    ) {}
}
