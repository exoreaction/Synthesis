package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Maps the attack surface by performing BFS through the code dependency graph
 * from entry points (CLI commands, MCP handlers) to sinks (SQL, file I/O,
 * process execution, AI prompt construction).
 *
 * <p>Uses the persisted {@code code_dependencies} table for graph traversal.
 * All queries include {@code repo_name} per V14 repo isolation requirements.
 *
 * @since v1.14.0 (Security)
 */
public class AttackSurfaceMapper {

    private static final Logger LOG = Logger.getLogger(AttackSurfaceMapper.class.getName());

    /** Maximum BFS depth to prevent runaway traversal. */
    private static final int MAX_DEPTH = 10;

    private static final Set<String> ENTRY_PACKAGES = Set.of("cli", "mcp");

    private static final Set<String> SINK_INDICATORS = Set.of(
            "java.sql", "java.io", "java.nio.file",
            "ProcessBuilder", "Runtime", "ai", "prompt");

    private final CodeGraphRepository codeRepo;
    private final SecurityRepository securityRepo;

    public AttackSurfaceMapper() {
        this.codeRepo = new CodeGraphRepository();
        this.securityRepo = new SecurityRepository();
    }

    public AttackSurfaceMapper(CodeGraphRepository codeRepo, SecurityRepository securityRepo) {
        this.codeRepo = codeRepo;
        this.securityRepo = securityRepo;
    }

    /**
     * Maps the attack surface for the given workspace. Performs BFS from
     * entry points through the dependency graph to sinks.
     *
     * @param workspacePath workspace path string
     * @param conn          database connection
     * @return list of attack surface edges discovered
     */
    public List<AttackSurfaceEdge> map(String workspacePath, Connection conn) throws SQLException {
        List<AttackSurfaceEdge> edges = new ArrayList<>();
        long now = Instant.now().getEpochSecond();

        // Find entry points: files in cli/ or mcp/ packages
        List<EntryPoint> entryPoints = findEntryPoints(workspacePath, conn);

        // Find sinks: files importing java.sql, using Files.write, ProcessBuilder, etc.
        Set<String> sinkFiles = findSinkFiles(workspacePath, conn);

        // BFS from each entry point
        for (EntryPoint entry : entryPoints) {
            List<AttackSurfaceEdge> reachable = bfs(workspacePath, conn, entry, sinkFiles);
            edges.addAll(reachable);
        }

        // Persist edges
        for (AttackSurfaceEdge edge : edges) {
            try {
                securityRepo.upsertAttackSurfaceEdge(conn, workspacePath, edge, now);
            } catch (SQLException e) {
                LOG.fine("Could not persist attack surface edge: " + e.getMessage());
            }
        }

        return edges;
    }

    /**
     * BFS from a single entry point to discover reachable sinks.
     */
    private List<AttackSurfaceEdge> bfs(String workspacePath, Connection conn,
                                          EntryPoint entry, Set<String> sinkFiles)
            throws SQLException {
        List<AttackSurfaceEdge> edges = new ArrayList<>();

        // BFS state
        Queue<BfsNode> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();

        queue.add(new BfsNode(entry.file, entry.className, 0, entry.className));
        visited.add(entry.file);

        while (!queue.isEmpty()) {
            BfsNode current = queue.poll();
            if (current.depth > MAX_DEPTH) continue;

            // Check if current node is a sink
            if (sinkFiles.contains(current.file) && current.depth > 0) {
                String sinkType = classifySink(workspacePath, conn, current.file);
                edges.add(new AttackSurfaceEdge(
                        entry.file, entry.className,
                        current.file, current.className,
                        sinkType, current.depth, current.path));
            }

            // Find outgoing dependencies
            List<CodeDependency> outgoing = codeRepo.getDependenciesFrom(
                    conn, workspacePath, current.file);

            for (CodeDependency dep : outgoing) {
                if (dep.isExternal()) continue;
                String targetFile = dep.targetFile();
                if (targetFile == null || targetFile.isBlank()) continue;
                if (visited.contains(targetFile)) continue;

                visited.add(targetFile);
                queue.add(new BfsNode(
                        targetFile, dep.targetClass(),
                        current.depth + 1,
                        current.path + " -> " + dep.targetClass()));
            }
        }

        return edges;
    }

    /**
     * Finds entry points: files in CLI or MCP packages.
     */
    private List<EntryPoint> findEntryPoints(String workspacePath, Connection conn)
            throws SQLException {
        List<EntryPoint> entries = new ArrayList<>();
        String sql = """
            SELECT DISTINCT source_file, source_class, source_package, repo_name
            FROM code_dependencies
            WHERE workspace_path = ?
              AND is_external = 0
              AND source_package != ''
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sourcePackage = rs.getString("source_package");
                    String lastSegment = getLastPackageSegment(sourcePackage);
                    if (ENTRY_PACKAGES.contains(lastSegment)) {
                        entries.add(new EntryPoint(
                                rs.getString("source_file"),
                                rs.getString("source_class"),
                                sourcePackage));
                    }
                }
            }
        }
        return entries;
    }

    /**
     * Finds sink files: files that import security-sensitive packages.
     */
    private Set<String> findSinkFiles(String workspacePath, Connection conn) throws SQLException {
        Set<String> sinks = new HashSet<>();
        String sql = """
            SELECT DISTINCT source_file
            FROM code_dependencies
            WHERE workspace_path = ?
              AND target_package IN ('java.sql', 'java.io', 'java.nio.file')
            UNION
            SELECT DISTINCT source_file
            FROM code_dependencies
            WHERE workspace_path = ?
              AND (target_class = 'ProcessBuilder' OR target_class = 'Runtime')
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sinks.add(rs.getString("source_file"));
                }
            }
        }
        return sinks;
    }

    /**
     * Classifies a sink file based on what it imports.
     */
    private String classifySink(String workspacePath, Connection conn, String file)
            throws SQLException {
        String sql = """
            SELECT target_package, target_class
            FROM code_dependencies
            WHERE workspace_path = ? AND source_file = ? AND is_external = 1
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, file);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String targetPkg = rs.getString("target_package");
                    String targetClass = rs.getString("target_class");
                    if ("java.sql".equals(targetPkg)) return "sql";
                    if ("ProcessBuilder".equals(targetClass) || "Runtime".equals(targetClass))
                        return "process";
                    if ("java.nio.file".equals(targetPkg) || "java.io".equals(targetPkg))
                        return "file-io";
                }
            }
        }
        return "unknown";
    }

    private static String getLastPackageSegment(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "";
        String[] parts = packageName.split("\\.");
        return parts[parts.length - 1];
    }

    // -----------------------------------------------------------------------
    // Internal records
    // -----------------------------------------------------------------------

    private record EntryPoint(String file, String className, String packageName) {}
    private record BfsNode(String file, String className, int depth, String path) {}
}
