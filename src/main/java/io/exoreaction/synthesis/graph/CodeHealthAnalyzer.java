package io.exoreaction.synthesis.graph;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Detects code health signals from persisted module profiles and dependency graph.
 *
 * <p>Implemented signals:
 * <ul>
 *   <li><b>C001_CIRCULAR_DEPENDENCY</b> -- packages A and B mutually import each other</li>
 *   <li><b>C010_HIGH_FAN_IN_NO_TESTS</b> -- fan_in &gt; 5 and no corresponding test package detected</li>
 *   <li><b>C012_GOD_PACKAGE</b> -- total_files &gt; 15 (package too large)</li>
 *   <li><b>C013_UNSTABLE_CORE</b> -- "core" or "model" or "domain" package with instability &gt; 0.5</li>
 *   <li><b>C014_ORPHAN_CODE</b> -- fan_in = 0 AND fan_out = 0 (isolated package, not cli/main)</li>
 *   <li><b>C020_HOTSPOT</b> -- module with instability &gt; 0.8 AND fan_in &gt; 3 (risky)</li>
 *   <li><b>C021_DOCUMENTATION_GAP</b> -- module with fan_in &gt; 5 and inferred_purpose = "General purpose"</li>
 * </ul>
 *
 * @since v1.12.2 (CKG-2.02)
 */
public class CodeHealthAnalyzer {

    private static final Logger LOG = Logger.getLogger(CodeHealthAnalyzer.class.getName());

    /**
     * Analyzes the code knowledge graph for health signals.
     *
     * @param workspacePath the workspace root path string
     * @param conn          open SQLite connection
     * @return list of detected health signals, sorted by severity (HIGH first)
     */
    public List<CodeHealthSignal> analyze(String workspacePath, Connection conn) throws SQLException {
        List<CodeHealthSignal> signals = new ArrayList<>();

        signals.addAll(detectCircularDependencies(workspacePath, conn));
        signals.addAll(detectGodPackages(workspacePath, conn));
        signals.addAll(detectUnstableCore(workspacePath, conn));
        signals.addAll(detectOrphanCode(workspacePath, conn));
        signals.addAll(detectHotspots(workspacePath, conn));
        signals.addAll(detectHighFanInNoTests(workspacePath, conn));
        signals.addAll(detectDocumentationGaps(workspacePath, conn));

        // Sort by severity: HIGH > MEDIUM > LOW
        signals.sort(Comparator.comparingInt(s -> severityOrder(s.severity())));

        return signals;
    }

    // -----------------------------------------------------------------------
    // C001_CIRCULAR_DEPENDENCY
    // -----------------------------------------------------------------------

    private List<CodeHealthSignal> detectCircularDependencies(String workspacePath, Connection conn)
            throws SQLException {
        List<CodeHealthSignal> signals = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Find all package pairs where A->B and B->A both exist
        String sql = """
            SELECT DISTINCT d1.source_package, d1.target_package,
                   COUNT(*) as edge_count
            FROM code_dependencies d1
            JOIN code_dependencies d2
              ON d1.workspace_path = d2.workspace_path
              AND d1.source_package = d2.target_package
              AND d1.target_package = d2.source_package
            WHERE d1.workspace_path = ?
              AND d1.source_package != d1.target_package
              AND d1.source_package != ''
              AND d1.target_package != ''
              AND d1.is_external = 0
              AND d2.is_external = 0
            GROUP BY d1.source_package, d1.target_package
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pkgA = rs.getString(1);
                    String pkgB = rs.getString(2);
                    int edges = rs.getInt(3);

                    // Avoid duplicate pairs (A-B and B-A)
                    String key = pkgA.compareTo(pkgB) < 0 ? pkgA + "|" + pkgB : pkgB + "|" + pkgA;
                    if (seen.contains(key)) continue;
                    seen.add(key);

                    String shortA = shortName(pkgA);
                    String shortB = shortName(pkgB);

                    signals.add(new CodeHealthSignal(
                            "C001_CIRCULAR_DEPENDENCY",
                            "HIGH",
                            pkgA.replace('.', '/'),
                            shortA + " <-> " + shortB + " mutual imports detected (" + edges + " import edges each way)",
                            "Extract shared types to a common module"
                    ));
                }
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // C010_HIGH_FAN_IN_NO_TESTS
    // -----------------------------------------------------------------------

    private List<CodeHealthSignal> detectHighFanInNoTests(String workspacePath, Connection conn)
            throws SQLException {
        List<CodeHealthSignal> signals = new ArrayList<>();

        // Find packages with fan_in > 5
        String sql = """
            SELECT module_path, package_name, fan_in
            FROM module_profiles
            WHERE workspace_path = ? AND fan_in > 5
            """;

        // Also collect all known test packages
        Set<String> testPackages = new HashSet<>();
        String testSql = """
            SELECT DISTINCT package_name FROM module_profiles
            WHERE workspace_path = ?
              AND (package_name LIKE '%test%' OR package_name LIKE '%tests%')
            """;
        try (PreparedStatement ps = conn.prepareStatement(testSql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    testPackages.add(rs.getString(1));
                }
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String modulePath = rs.getString(1);
                    String packageName = rs.getString(2);
                    int fanIn = rs.getInt(3);

                    // Skip test packages themselves
                    if (packageName.contains("test")) continue;

                    // Check if corresponding test package exists
                    boolean hasTests = testPackages.stream().anyMatch(tp ->
                            tp.contains(packageName) || packageName.contains(tp));

                    if (!hasTests) {
                        signals.add(new CodeHealthSignal(
                                "C010_HIGH_FAN_IN_NO_TESTS",
                                "MEDIUM",
                                modulePath,
                                "High fan-in package (" + fanIn + " dependents) has no corresponding test package",
                                "Add test coverage for this widely-used package"
                        ));
                    }
                }
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // C012_GOD_PACKAGE
    // -----------------------------------------------------------------------

    private List<CodeHealthSignal> detectGodPackages(String workspacePath, Connection conn)
            throws SQLException {
        List<CodeHealthSignal> signals = new ArrayList<>();

        String sql = """
            SELECT module_path, package_name, total_files
            FROM module_profiles
            WHERE workspace_path = ? AND total_files > 15
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String modulePath = rs.getString(1);
                    int totalFiles = rs.getInt(3);

                    signals.add(new CodeHealthSignal(
                            "C012_GOD_PACKAGE",
                            "MEDIUM",
                            modulePath,
                            "Package has " + totalFiles + " files (threshold: 15)",
                            "Split into sub-packages by feature area"
                    ));
                }
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // C013_UNSTABLE_CORE
    // -----------------------------------------------------------------------

    private List<CodeHealthSignal> detectUnstableCore(String workspacePath, Connection conn)
            throws SQLException {
        List<CodeHealthSignal> signals = new ArrayList<>();

        String sql = """
            SELECT module_path, package_name, instability
            FROM module_profiles
            WHERE workspace_path = ? AND instability > 0.5
              AND (package_name LIKE '%core%' OR package_name LIKE '%model%' OR package_name LIKE '%domain%')
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String modulePath = rs.getString(1);
                    String packageName = rs.getString(2);
                    double instability = rs.getDouble(3);

                    signals.add(new CodeHealthSignal(
                            "C013_UNSTABLE_CORE",
                            "HIGH",
                            modulePath,
                            "Core package '" + shortName(packageName) + "' has instability "
                                    + String.format("%.2f", instability) + " (should be < 0.5 for stability)",
                            "Reduce outgoing dependencies -- core packages should be depended upon, not depend on others"
                    ));
                }
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // C014_ORPHAN_CODE
    // -----------------------------------------------------------------------

    private List<CodeHealthSignal> detectOrphanCode(String workspacePath, Connection conn)
            throws SQLException {
        List<CodeHealthSignal> signals = new ArrayList<>();

        String sql = """
            SELECT module_path, package_name
            FROM module_profiles
            WHERE workspace_path = ? AND fan_in = 0 AND fan_out = 0
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String modulePath = rs.getString(1);
                    String packageName = rs.getString(2);

                    // Exclude CLI/main entry points -- they are expected to have no fan-in
                    String lastSegment = getLastSegment(packageName);
                    if ("cli".equals(lastSegment) || "main".equals(lastSegment)
                            || "command".equals(lastSegment) || packageName.contains("test")) {
                        continue;
                    }

                    signals.add(new CodeHealthSignal(
                            "C014_ORPHAN_CODE",
                            "LOW",
                            modulePath,
                            "Package '" + shortName(packageName) + "' is isolated (fan_in=0, fan_out=0)",
                            "Consider removing dead code or integrating it with other modules"
                    ));
                }
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // C020_HOTSPOT
    // -----------------------------------------------------------------------

    private List<CodeHealthSignal> detectHotspots(String workspacePath, Connection conn)
            throws SQLException {
        List<CodeHealthSignal> signals = new ArrayList<>();

        String sql = """
            SELECT module_path, package_name, instability, fan_in
            FROM module_profiles
            WHERE workspace_path = ? AND instability > 0.8 AND fan_in > 3
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String modulePath = rs.getString(1);
                    String packageName = rs.getString(2);
                    double instability = rs.getDouble(3);
                    int fanIn = rs.getInt(4);

                    signals.add(new CodeHealthSignal(
                            "C020_HOTSPOT",
                            "HIGH",
                            modulePath,
                            "Package '" + shortName(packageName) + "' is a hotspot: instability="
                                    + String.format("%.2f", instability) + " with " + fanIn + " dependents",
                            "Stabilize this package -- high instability with many dependents means changes here cascade widely"
                    ));
                }
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // C021_DOCUMENTATION_GAP
    // -----------------------------------------------------------------------

    private List<CodeHealthSignal> detectDocumentationGaps(String workspacePath, Connection conn)
            throws SQLException {
        List<CodeHealthSignal> signals = new ArrayList<>();

        String sql = """
            SELECT module_path, package_name, fan_in
            FROM module_profiles
            WHERE workspace_path = ? AND fan_in > 5 AND inferred_purpose = 'General purpose'
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String modulePath = rs.getString(1);
                    int fanIn = rs.getInt(3);

                    signals.add(new CodeHealthSignal(
                            "C021_DOCUMENTATION_GAP",
                            "LOW",
                            modulePath,
                            "High fan-in package (" + fanIn + ") lacks documented purpose",
                            "Add package-info.java or README.md"
                    ));
                }
            }
        }
        return signals;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static int severityOrder(String severity) {
        return switch (severity) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            case "LOW" -> 2;
            default -> 3;
        };
    }

    private static String shortName(String packageName) {
        if (packageName == null) return "";
        int lastDot = packageName.lastIndexOf('.');
        return lastDot >= 0 ? packageName.substring(lastDot + 1) : packageName;
    }

    private static String getLastSegment(String packageName) {
        if (packageName == null) return "";
        String[] parts = packageName.split("\\.");
        return parts[parts.length - 1].toLowerCase(Locale.ROOT);
    }
}
