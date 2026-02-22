package io.exoreaction.synthesis.graph;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Computes module profiles by aggregating code_dependencies rows.
 * A "module" corresponds to a Java package (e.g., "io.exoreaction.synthesis.cli").
 *
 * <p>For each discovered package, the computer calculates:
 * <ul>
 *   <li><b>Fan-in</b> = number of distinct external packages that import FROM this package</li>
 *   <li><b>Fan-out</b> = number of distinct external packages this package imports INTO</li>
 *   <li><b>Instability</b> = fanOut / (fanIn + fanOut) -- Martin metric
 *       (0.0 = maximally stable, 1.0 = maximally unstable)</li>
 *   <li><b>Inferred purpose</b> from package name heuristics</li>
 * </ul>
 *
 * <p>Results are persisted to the {@code module_profiles} table using
 * {@code INSERT OR REPLACE} for idempotency.
 *
 * @since v1.12.2 (CKG-2.01)
 */
public class ModuleProfileComputer {

    private static final Logger LOG = Logger.getLogger(ModuleProfileComputer.class.getName());

    private final CodeGraphRepository repository;

    public ModuleProfileComputer(CodeGraphRepository repository) {
        this.repository = repository;
    }

    /**
     * Compute and persist module profiles for all packages found in code_dependencies.
     *
     * @param workspacePath the workspace root path string
     * @param conn          open SQLite connection
     * @return number of module profiles computed
     */
    public int computeAndPersist(String workspacePath, Connection conn) throws SQLException {
        // 1. Collect all unique source packages
        Set<String> allPackages = collectAllPackages(conn, workspacePath);

        if (allPackages.isEmpty()) {
            return 0;
        }

        long now = Instant.now().getEpochSecond();
        int count = 0;

        for (String packageName : allPackages) {
            // 2. Compute fan-in: distinct external packages that import something from this package
            int fanIn = computeFanIn(conn, workspacePath, packageName);

            // 3. Compute fan-out: distinct external packages this package imports
            int fanOut = computeFanOut(conn, workspacePath, packageName);

            // 4. Compute instability = fanOut / (fanIn + fanOut), guarded against /0
            double instability;
            if (fanIn + fanOut == 0) {
                instability = 0.5; // neutral for orphan packages
            } else {
                instability = (double) fanOut / (fanIn + fanOut);
            }

            // 5. Count total files in this package
            int totalFiles = countFilesInPackage(conn, workspacePath, packageName);

            // 6. Infer purpose
            PurposeResult purposeResult = inferPurposeResult(packageName);
            String purpose = purposeResult.purpose();

            // 7. Compute module_path (convert dots to slashes)
            String modulePath = packageName.replace('.', '/');

            // 8. Confidence: reflects inferPurpose match quality, then connectivity
            double confidence;
            if (fanIn + fanOut == 0) {
                confidence = 0.30; // isolated package — minimal confidence regardless of label
            } else {
                confidence = purposeResult.confidence();
            }

            // 9. Upsert into module_profiles
            upsertModuleProfile(conn, workspacePath, modulePath, packageName,
                    purpose, fanIn, fanOut, instability, totalFiles, confidence, now);
            count++;
        }

        LOG.fine("Computed " + count + " module profiles for " + workspacePath);
        return count;
    }

    /** Pair of inferred purpose label and confidence score. */
    public record PurposeResult(String purpose, double confidence) {}

    /**
     * Infer human-readable purpose from package path segment.
     *
     * <p>Uses a heuristic table matching the last segment or any segment of the
     * package name to a known category.
     */
    public String inferPurpose(String packageName) {
        return inferPurposeResult(packageName).purpose();
    }

    /**
     * Infer purpose with confidence score reflecting match quality.
     *
     * <ul>
     *   <li>0.90 — exact last-segment match (e.g. {@code cli}, {@code db})</li>
     *   <li>0.75 — ancestor-segment match (e.g. {@code cli} in {@code com.example.cli.sub})</li>
     *   <li>0.40 — no match, fell back to "General purpose" but package is connected</li>
     *   <li>0.30 — isolated package (caller overrides to this when fanIn+fanOut==0)</li>
     * </ul>
     */
    public PurposeResult inferPurposeResult(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return new PurposeResult("General purpose", 0.40);
        }

        String[] segments = packageName.split("\\.");
        String lastSegment = segments[segments.length - 1].toLowerCase(Locale.ROOT);

        // Check last segment first (most specific → highest confidence)
        String purpose = matchSegment(lastSegment);
        if (purpose != null) {
            return new PurposeResult(purpose, 0.90);
        }

        // Check ancestor segments (less specific → lower confidence)
        for (int i = segments.length - 2; i >= 0; i--) {
            purpose = matchSegment(segments[i].toLowerCase(Locale.ROOT));
            if (purpose != null) {
                return new PurposeResult(purpose, 0.75);
            }
        }

        return new PurposeResult("General purpose", 0.40);
    }

    private String matchSegment(String segment) {
        return switch (segment) {
            case "cli", "command" -> "CLI command implementations";
            case "core", "domain" -> "Core domain model";
            case "db", "persistence" -> "Data persistence";
            case "config", "configuration", "settings" -> "Configuration management";
            case "util", "utils", "utility", "common" -> "Shared utilities";
            case "api", "rest", "controller" -> "API endpoint handlers";
            case "service", "business" -> "Business logic services";
            case "graph", "analysis" -> "Graph analysis and visualization";
            case "index", "search" -> "Search and indexing";
            case "model" -> "Data model / entities";
            case "test", "tests" -> "Test support";
            case "mcp" -> "MCP server protocol";
            case "lsp" -> "LSP server protocol";
            case "org" -> "Organization and routing";
            case "staging", "stage" -> "Staging pipeline";
            case "changelog", "tracking", "track" -> "Change tracking";
            case "enrichment", "enrich" -> "Media enrichment";
            case "summary" -> "Reporting / summarization";
            case "report" -> "Reporting / summarization";
            case "research" -> "Research engine";
            case "metrics", "telemetry" -> "Operational metrics";
            case "validate", "validation" -> "Validation";
            case "workspace" -> "Workspace management";
            case "update" -> "Update management";
            case "ai" -> "AI service integration";
            default -> null;
        };
    }

    // -----------------------------------------------------------------------
    // SQL helpers
    // -----------------------------------------------------------------------

    /**
     * Collects all unique source packages from code_dependencies for the workspace.
     * Also includes target packages (for internal deps) to get full coverage.
     */
    private Set<String> collectAllPackages(Connection conn, String workspacePath) throws SQLException {
        Set<String> packages = new LinkedHashSet<>();

        String sql = """
            SELECT DISTINCT source_package FROM code_dependencies
            WHERE workspace_path = ? AND source_package != ''
            UNION
            SELECT DISTINCT target_package FROM code_dependencies
            WHERE workspace_path = ? AND target_package != '' AND is_external = 0
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pkg = rs.getString(1);
                    if (pkg != null && !pkg.isBlank()) {
                        packages.add(pkg);
                    }
                }
            }
        }
        return packages;
    }

    /**
     * Fan-in: count of distinct external packages that have edges targeting this package.
     * (i.e., other packages that import classes from this package)
     */
    private int computeFanIn(Connection conn, String workspacePath, String packageName) throws SQLException {
        String sql = """
            SELECT COUNT(DISTINCT source_package) FROM code_dependencies
            WHERE workspace_path = ? AND target_package = ? AND source_package != ?
            AND is_external = 0
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, packageName);
            ps.setString(3, packageName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Fan-out: count of distinct internal (non-external) packages this package imports from.
     * External (third-party) dependencies are excluded since they are not part of the
     * project's module graph.
     */
    private int computeFanOut(Connection conn, String workspacePath, String packageName) throws SQLException {
        String sql = """
            SELECT COUNT(DISTINCT target_package) FROM code_dependencies
            WHERE workspace_path = ? AND source_package = ? AND target_package != ?
            AND target_package != '' AND is_external = 0
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, packageName);
            ps.setString(3, packageName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Counts distinct source files in the given package.
     */
    private int countFilesInPackage(Connection conn, String workspacePath, String packageName) throws SQLException {
        String sql = """
            SELECT COUNT(DISTINCT source_file) FROM code_dependencies
            WHERE workspace_path = ? AND source_package = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, packageName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Upserts a module profile row into module_profiles.
     */
    private void upsertModuleProfile(Connection conn, String workspacePath, String modulePath,
                                      String packageName, String inferredPurpose,
                                      int fanIn, int fanOut, double instability,
                                      int totalFiles, double confidence, long now) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO module_profiles (
                workspace_path, module_path, package_name, inferred_purpose,
                fan_in, fan_out, instability, total_files, confidence, last_computed
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, modulePath);
            ps.setString(3, packageName);
            ps.setString(4, inferredPurpose);
            ps.setInt(5, fanIn);
            ps.setInt(6, fanOut);
            ps.setDouble(7, instability);
            ps.setInt(8, totalFiles);
            ps.setDouble(9, confidence);
            ps.setLong(10, now);
            ps.executeUpdate();
        }
    }
}
