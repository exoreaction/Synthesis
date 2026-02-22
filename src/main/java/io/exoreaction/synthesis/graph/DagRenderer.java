package io.exoreaction.synthesis.graph;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Renders the module dependency DAG from persisted module_profiles and code_dependencies.
 *
 * <p>Supports ASCII (default) and Mermaid output formats.
 * Infers architectural layers from instability metric (Martin):
 * <ul>
 *   <li>Layer 1 (Stable/Foundation):  instability 0.00-0.25</li>
 *   <li>Layer 2 (Core Services):      instability 0.26-0.50</li>
 *   <li>Layer 3 (Application):        instability 0.51-0.75</li>
 *   <li>Layer 4 (Entry/CLI):          instability 0.76-1.00</li>
 * </ul>
 *
 * @since v1.12.2 (CKG-4.01)
 */
public class DagRenderer {

    private final CodeGraphRepository repository;

    public DagRenderer(CodeGraphRepository repository) {
        this.repository = repository;
    }

    /**
     * Render full package DAG grouped by layer (ASCII).
     * In multi-repo workspaces, package labels are prefixed with the repo name.
     */
    public String renderAscii(String workspacePath, Connection conn) throws SQLException {
        List<ModuleProfile> profiles = loadModuleProfiles(conn, workspacePath);
        if (profiles.isEmpty()) {
            return "";
        }

        List<PackageEdge> edges = loadInternalPackageEdges(conn, workspacePath);
        List<CircularDep> cycles = detectCycles(edges);
        List<LayerViolation> violations = detectLayerViolations(profiles, edges);

        boolean multiRepo = isMultiRepoResult(profiles);

        int totalPackages = profiles.size();
        int totalEdges = edges.stream().mapToInt(e -> e.edgeCount).sum();

        StringBuilder sb = new StringBuilder();
        sb.append("Package dependency graph (")
                .append(totalPackages).append(" packages, ")
                .append(totalEdges).append(" edges)\n");

        // Group by layer
        Map<Integer, List<ModuleProfile>> byLayer = groupByLayer(profiles);

        String[] layerNames = {"", "Foundation", "Core Services", "Application", "Entry/CLI"};
        String[] layerRanges = {"", "0.00-0.25", "0.26-0.50", "0.51-0.75", "0.76-1.00"};

        for (int layer = 1; layer <= 4; layer++) {
            List<ModuleProfile> layerProfiles = byLayer.getOrDefault(layer, Collections.emptyList());
            if (layerProfiles.isEmpty()) continue;

            sb.append("\n  Layer ").append(layer).append(" \u2014 ")
                    .append(layerNames[layer]).append(" (instability ")
                    .append(layerRanges[layer]).append(")\n");

            for (ModuleProfile p : layerProfiles) {
                String displayPath = multiRepo && !p.repoName().isEmpty()
                        ? p.repoName() + "/" + p.modulePath()
                        : p.modulePath();
                sb.append("    ").append(displayPath)
                        .append(String.format("  fan-in: %d  fan-out: %d  instability: %.2f",
                                p.fanIn(), p.fanOut(), p.instability()));

                if (isCliPackage(p.packageName())) {
                    sb.append(" (expected)");
                } else if (p.instability() > 0.6) {
                    sb.append(" \u26a0");
                } else {
                    sb.append(" \u2713");
                }
                sb.append("\n");
            }
        }

        // Cycles summary
        sb.append("\nCycles detected: ").append(cycles.size()).append("\n");
        for (CircularDep cycle : cycles) {
            sb.append("  [!] ").append(cycle.packageA)
                    .append(" \u2194 ").append(cycle.packageB).append("\n");
        }

        // Layer violations summary
        sb.append("\nLayer violations: ").append(violations.size()).append("\n");
        for (LayerViolation v : violations) {
            sb.append("  [!] ").append(v.fromPackage)
                    .append(" \u2192 ").append(v.toPackage).append("\n");
        }

        return sb.toString();
    }

    /**
     * Render package DAG as Mermaid graph TD.
     * In multi-repo workspaces, packages are grouped into subgraphs by repo name.
     */
    public String renderMermaid(String workspacePath, Connection conn) throws SQLException {
        List<ModuleProfile> profiles = loadModuleProfiles(conn, workspacePath);
        if (profiles.isEmpty()) {
            return "";
        }

        List<PackageEdge> edges = loadInternalPackageEdges(conn, workspacePath);
        boolean multiRepo = isMultiRepoResult(profiles);

        // Limit to top 30 packages if more (too large to render)
        if (profiles.size() > 30) {
            profiles = profiles.subList(0, 30);
        }

        // Build a set of included package names for edge filtering
        Set<String> includedPackages = profiles.stream()
                .map(p -> p.packageName())
                .collect(Collectors.toSet());

        StringBuilder sb = new StringBuilder();
        sb.append("graph TD\n");

        if (multiRepo) {
            // Group profiles by repo for subgraph rendering
            Map<String, List<ModuleProfile>> byRepo = new LinkedHashMap<>();
            for (ModuleProfile p : profiles) {
                byRepo.computeIfAbsent(p.repoName(), k -> new ArrayList<>()).add(p);
            }
            for (Map.Entry<String, List<ModuleProfile>> entry : byRepo.entrySet()) {
                String repoName = entry.getKey();
                if (!repoName.isEmpty()) {
                    sb.append("    subgraph ").append(repoName).append("\n");
                }
                for (ModuleProfile p : entry.getValue()) {
                    String nodeId = toMermaidId(p.repoName() + "." + p.packageName());
                    String label = lastSegment(p.packageName());
                    double stability = 1.0 - p.instability();
                    sb.append("        ").append(nodeId)
                            .append("[\"").append(label)
                            .append("\\nstability: ").append(String.format("%.2f", stability))
                            .append("\"]\n");
                }
                if (!repoName.isEmpty()) {
                    sb.append("    end\n");
                }
            }
        } else {
            // Node declarations (single-repo)
            for (ModuleProfile p : profiles) {
                String nodeId = toMermaidId(p.packageName());
                String label = lastSegment(p.packageName());
                double stability = 1.0 - p.instability();
                sb.append("    ").append(nodeId)
                        .append("[\"").append(label)
                        .append("\\nstability: ").append(String.format("%.2f", stability))
                        .append("\"]\n");
            }
        }

        // Edges (only internal, only between included packages)
        for (PackageEdge edge : edges) {
            if (includedPackages.contains(edge.sourcePackage())
                    && includedPackages.contains(edge.targetPackage())) {
                String fromId = multiRepo
                        ? toMermaidId(edge.repoName() + "." + edge.sourcePackage())
                        : toMermaidId(edge.sourcePackage());
                String toId = multiRepo
                        ? toMermaidId(edge.repoName() + "." + edge.targetPackage())
                        : toMermaidId(edge.targetPackage());
                sb.append("    ").append(fromId).append(" --> ").append(toId).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Return circular dependency pairs (A <-> B where both A->B and B->A exist).
     */
    public List<CircularDep> findCycles(String workspacePath, Connection conn) throws SQLException {
        List<PackageEdge> edges = loadInternalPackageEdges(conn, workspacePath);
        return detectCycles(edges);
    }

    /**
     * Return hotspot packages: instability > 0.7 AND fan_in > 2. Sorted by fan_in desc.
     */
    public List<ModuleProfile> findHotspots(String workspacePath, Connection conn)
            throws SQLException {
        List<ModuleProfile> all = loadModuleProfiles(conn, workspacePath);
        return all.stream()
                .filter(p -> p.instability() > 0.7 && p.fanIn() > 2)
                .sorted(Comparator.comparingInt((ModuleProfile p) -> p.fanIn()).reversed())
                .toList();
    }

    /**
     * Return all packages sorted by instability descending.
     */
    public List<ModuleProfile> sortedByInstability(String workspacePath, Connection conn)
            throws SQLException {
        List<ModuleProfile> all = loadModuleProfiles(conn, workspacePath);
        return all.stream()
                .sorted(Comparator.comparingDouble((ModuleProfile p) -> p.instability()).reversed())
                .toList();
    }

    /**
     * Return layer violations: edges where lower-instability imports from higher-instability.
     * This violates Robert C. Martin's Stable Dependencies Principle: stable packages
     * should not depend on unstable packages.
     */
    public List<LayerViolation> findLayerViolations(String workspacePath, Connection conn)
            throws SQLException {
        List<ModuleProfile> profiles = loadModuleProfiles(conn, workspacePath);
        List<PackageEdge> edges = loadInternalPackageEdges(conn, workspacePath);
        return detectLayerViolations(profiles, edges);
    }

    // -----------------------------------------------------------------------
    // Records
    // -----------------------------------------------------------------------

    /** Simple ModuleProfile record for rendering (subset of DB columns). */
    public record ModuleProfile(String repoName, String modulePath, String packageName,
                                String inferredPurpose, int fanIn, int fanOut,
                                double instability, int totalFiles, double completeness) {}

    /** A circular dependency pair. */
    public record CircularDep(String packageA, String packageB,
                              int edgesAtoB, int edgesBtoA) {}

    /** A layer violation: lower-instability package importing from higher-instability. */
    public record LayerViolation(String fromPackage, String toPackage,
                                 double fromInstability, double toInstability, int edgeCount) {}

    // -----------------------------------------------------------------------
    // Internal: edge aggregation record
    // -----------------------------------------------------------------------

    /** Aggregated package-to-package edge (internal only, scoped by repo). */
    record PackageEdge(String repoName, String sourcePackage, String targetPackage, int edgeCount) {}

    // -----------------------------------------------------------------------
    // Data loading
    // -----------------------------------------------------------------------

    /**
     * Load module profiles from database.
     * Uses COALESCE for completeness_score which may not exist yet (added lazily).
     */
    List<ModuleProfile> loadModuleProfiles(Connection conn, String workspacePath)
            throws SQLException {
        List<ModuleProfile> profiles = new ArrayList<>();

        // Try with completeness_score first; if column doesn't exist, fall back
        String sql;
        boolean hasCompleteness = columnExists(conn, "module_profiles", "completeness_score");
        boolean hasRepoName = columnExists(conn, "module_profiles", "repo_name");

        if (hasCompleteness) {
            sql = """
                SELECT %s module_path, package_name, inferred_purpose,
                       fan_in, fan_out, instability, total_files,
                       COALESCE(completeness_score, 1.0) as completeness
                FROM module_profiles
                WHERE workspace_path = ?
                ORDER BY %s package_name
                """.formatted(
                    hasRepoName ? "repo_name," : "",
                    hasRepoName ? "repo_name," : ""
                );
        } else {
            sql = """
                SELECT %s module_path, package_name, inferred_purpose,
                       fan_in, fan_out, instability, total_files
                FROM module_profiles
                WHERE workspace_path = ?
                ORDER BY %s package_name
                """.formatted(
                    hasRepoName ? "repo_name," : "",
                    hasRepoName ? "repo_name," : ""
                );
        }

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String repoName = hasRepoName ? rs.getString("repo_name") : "";
                    if (repoName == null) repoName = "";
                    profiles.add(new ModuleProfile(
                            repoName,
                            rs.getString("module_path"),
                            rs.getString("package_name"),
                            rs.getString("inferred_purpose"),
                            rs.getInt("fan_in"),
                            rs.getInt("fan_out"),
                            rs.getDouble("instability"),
                            rs.getInt("total_files"),
                            hasCompleteness ? rs.getDouble("completeness") : 1.0
                    ));
                }
            }
        }
        return profiles;
    }

    /**
     * Load all internal package-to-package edges (aggregated from code_dependencies),
     * scoped by repo_name for correct isolation.
     */
    List<PackageEdge> loadInternalPackageEdges(Connection conn, String workspacePath)
            throws SQLException {
        String sql = """
            SELECT repo_name, source_package, target_package, COUNT(*) as edge_count
            FROM code_dependencies
            WHERE workspace_path = ?
              AND is_external = 0
              AND source_package != ''
              AND target_package != ''
              AND source_package != target_package
            GROUP BY repo_name, source_package, target_package
            ORDER BY repo_name, source_package, target_package
            """;
        List<PackageEdge> edges = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String repoName = rs.getString("repo_name");
                    if (repoName == null) repoName = "";
                    edges.add(new PackageEdge(
                            repoName,
                            rs.getString("source_package"),
                            rs.getString("target_package"),
                            rs.getInt("edge_count")
                    ));
                }
            }
        }
        return edges;
    }

    // -----------------------------------------------------------------------
    // Internal: analysis helpers
    // -----------------------------------------------------------------------

    private List<CircularDep> detectCycles(List<PackageEdge> edges) {
        // Build lookup: (repo|source -> target) -> edgeCount, scoped by repo
        Map<String, Integer> edgeMap = new HashMap<>();
        for (PackageEdge e : edges) {
            edgeMap.put(e.repoName() + "|" + e.sourcePackage() + " -> " + e.targetPackage(), e.edgeCount());
        }

        // Find mutual pairs within the same repo
        List<CircularDep> cycles = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (PackageEdge e : edges) {
            String reverseKey = e.repoName() + "|" + e.targetPackage() + " -> " + e.sourcePackage();
            if (edgeMap.containsKey(reverseKey)) {
                // Normalize to avoid duplicate pairs (alphabetical order)
                String pairKey;
                String pkgA, pkgB;
                int aToB, bToA;
                if (e.sourcePackage().compareTo(e.targetPackage()) <= 0) {
                    pairKey = e.repoName() + "|" + e.sourcePackage() + "|" + e.targetPackage();
                    pkgA = e.sourcePackage();
                    pkgB = e.targetPackage();
                    aToB = e.edgeCount();
                    bToA = edgeMap.get(reverseKey);
                } else {
                    pairKey = e.repoName() + "|" + e.targetPackage() + "|" + e.sourcePackage();
                    pkgA = e.targetPackage();
                    pkgB = e.sourcePackage();
                    aToB = edgeMap.get(reverseKey);
                    bToA = e.edgeCount();
                }
                if (seen.add(pairKey)) {
                    cycles.add(new CircularDep(pkgA, pkgB, aToB, bToA));
                }
            }
        }

        return cycles;
    }

    private List<LayerViolation> detectLayerViolations(List<ModuleProfile> profiles,
                                                        List<PackageEdge> edges) {
        // Build instability map keyed by (repo_name|package_name)
        Map<String, Double> instabilityMap = new HashMap<>();
        for (ModuleProfile p : profiles) {
            instabilityMap.put(p.repoName() + "|" + p.packageName(), p.instability());
        }

        List<LayerViolation> violations = new ArrayList<>();
        for (PackageEdge e : edges) {
            Double fromInst = instabilityMap.get(e.repoName() + "|" + e.sourcePackage());
            Double toInst = instabilityMap.get(e.repoName() + "|" + e.targetPackage());

            if (fromInst != null && toInst != null) {
                // Violation: source is more stable (lower instability) than target (higher instability)
                // i.e., stable package depends on unstable package
                if (fromInst < toInst) {
                    violations.add(new LayerViolation(
                            e.sourcePackage(), e.targetPackage(),
                            fromInst, toInst, e.edgeCount()));
                }
            }
        }

        return violations;
    }

    private Map<Integer, List<ModuleProfile>> groupByLayer(List<ModuleProfile> profiles) {
        Map<Integer, List<ModuleProfile>> byLayer = new LinkedHashMap<>();
        for (ModuleProfile p : profiles) {
            int layer = layerForInstability(p.instability());
            byLayer.computeIfAbsent(layer, k -> new ArrayList<>()).add(p);
        }
        return byLayer;
    }

    static int layerForInstability(double instability) {
        if (instability <= 0.25) return 1;
        if (instability <= 0.50) return 2;
        if (instability <= 0.75) return 3;
        return 4;
    }

    private static boolean isCliPackage(String packageName) {
        return packageName != null
                && (packageName.endsWith(".cli") || packageName.endsWith(".command")
                || packageName.endsWith(".main"));
    }

    private static String toMermaidId(String packageName) {
        if (packageName == null) return "unknown";
        return packageName.replaceAll("[./]", "_");
    }

    private static String lastSegment(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "unknown";
        int dot = packageName.lastIndexOf('.');
        int slash = packageName.lastIndexOf('/');
        int idx = Math.max(dot, slash);
        return idx >= 0 ? packageName.substring(idx + 1) : packageName;
    }

    /**
     * Returns true if the result set contains multiple distinct repo names.
     */
    private static boolean isMultiRepoResult(List<ModuleProfile> profiles) {
        Set<String> repos = new HashSet<>();
        for (ModuleProfile p : profiles) {
            repos.add(p.repoName());
            if (repos.size() > 1) return true;
        }
        // Single repo is multi-repo only if the one repo name is non-empty
        // (but a single non-empty repo is still "single effective repo" — not multi)
        return false;
    }

    private boolean columnExists(Connection conn, String table, String column)
            throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }
}
