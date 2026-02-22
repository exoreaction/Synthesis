package io.exoreaction.synthesis.graph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

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

    /** Minimum number of files in a package to trigger the C012 god package signal. */
    static final int GOD_PACKAGE_THRESHOLD = 30;

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

        // Aggregate class-level edges to package-level first, scoped by repo_name,
        // then detect mutual pairs within the same repo
        String sql = """
            SELECT repo_name, source_package, target_package, COUNT(*) as edge_count
            FROM code_dependencies
            WHERE workspace_path = ?
              AND is_external = 0
              AND source_package != ''
              AND target_package != ''
              AND source_package != target_package
            GROUP BY repo_name, source_package, target_package
            """;

        // Build lookup: (repo|source -> target) -> edgeCount at the package level
        Map<String, Integer> edgeMap = new HashMap<>();
        List<String[]> allEdges = new ArrayList<>(); // [repo, src, tgt]

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String repo = rs.getString(1);
                    if (repo == null) repo = "";
                    String src = rs.getString(2);
                    String tgt = rs.getString(3);
                    int count = rs.getInt(4);
                    edgeMap.put(repo + "|" + src + " -> " + tgt, count);
                    allEdges.add(new String[]{repo, src, tgt});
                }
            }
        }

        // Find mutual pairs within the same repo
        Set<String> seen = new HashSet<>();
        for (String[] edge : allEdges) {
            String repo = edge[0];
            String src = edge[1];
            String tgt = edge[2];
            String reverseKey = repo + "|" + tgt + " -> " + src;
            if (edgeMap.containsKey(reverseKey)) {
                // Normalize to avoid duplicate pairs (alphabetical order)
                String pkgA, pkgB;
                int aToB, bToA;
                if (src.compareTo(tgt) <= 0) {
                    pkgA = src;
                    pkgB = tgt;
                    aToB = edgeMap.get(repo + "|" + src + " -> " + tgt);
                    bToA = edgeMap.get(reverseKey);
                } else {
                    pkgA = tgt;
                    pkgB = src;
                    aToB = edgeMap.get(repo + "|" + tgt + " -> " + src);
                    bToA = edgeMap.get(repo + "|" + src + " -> " + tgt);
                }
                String pairKey = repo + "|" + pkgA + "|" + pkgB;
                if (seen.add(pairKey)) {
                    String shortA = shortName(pkgA);
                    String shortB = shortName(pkgB);
                    String repoPrefix = repo.isEmpty() ? "" : "[" + repo + "] ";

                    signals.add(new CodeHealthSignal(
                            "C001_CIRCULAR_DEPENDENCY",
                            "HIGH",
                            pkgA.replace('.', '/'),
                            repoPrefix + shortA + " <-> " + shortB + " mutual imports detected ("
                                    + aToB + " edges " + shortA + "->" + shortB
                                    + ", " + bToA + " edges " + shortB + "->" + shortA + ")",
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

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String modulePath = rs.getString(1);
                    String packageName = rs.getString(2);
                    int fanIn = rs.getInt(3);

                    // Skip test packages themselves
                    if (packageName.contains("test")) continue;

                    // Check if corresponding test files exist on the filesystem
                    // Standard Maven layout: src/test/java/<package-as-path>/*Test.java
                    boolean hasTests = hasTestFilesOnDisk(workspacePath, packageName);

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

    /**
     * Checks the filesystem for test files corresponding to the given package.
     * Looks in standard Maven layout: src/test/java/&lt;package-as-path&gt;/
     * for files matching *Test.java.
     *
     * <p>Also walks up to find git repo roots (directories containing src/test/java/)
     * to handle multi-module workspaces.
     */
    private boolean hasTestFilesOnDisk(String workspacePath, String packageName) {
        Path wsRoot = Path.of(workspacePath);
        String packagePath = packageName.replace('.', '/');

        // Check common test directory locations
        List<Path> testDirCandidates = List.of(
                wsRoot.resolve("src/test/java").resolve(packagePath),
                wsRoot.resolve("test").resolve(packagePath)
        );

        // Also search for any src/test/java directories under the workspace
        // (handles multi-module Maven projects like Synthesis itself)
        try (Stream<Path> walk = Files.walk(wsRoot, 4)) {
            List<Path> srcTestJavaDirs = walk
                    .filter(Files::isDirectory)
                    .filter(p -> p.endsWith("src/test/java"))
                    .toList();
            for (Path testJavaDir : srcTestJavaDirs) {
                Path candidate = testJavaDir.resolve(packagePath);
                if (hasTestJavaFiles(candidate)) {
                    return true;
                }
            }
        } catch (IOException e) {
            LOG.fine("Could not walk workspace for test dirs: " + e.getMessage());
        }

        for (Path testDir : testDirCandidates) {
            if (hasTestJavaFiles(testDir)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if a directory exists and contains at least one *Test.java file.
     */
    private boolean hasTestJavaFiles(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.endsWith("Test.java") || name.endsWith("Tests.java");
            });
        } catch (IOException e) {
            return false;
        }
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
            WHERE workspace_path = ? AND total_files > ?
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setInt(2, GOD_PACKAGE_THRESHOLD);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String modulePath = rs.getString(1);
                    int totalFiles = rs.getInt(3);

                    signals.add(new CodeHealthSignal(
                            "C012_GOD_PACKAGE",
                            "MEDIUM",
                            modulePath,
                            "Package has " + totalFiles + " files (threshold: " + GOD_PACKAGE_THRESHOLD + ")",
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
