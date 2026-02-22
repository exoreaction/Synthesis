package io.exoreaction.synthesis.graph;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Data access object for the code knowledge graph tables.
 *
 * <p>Provides CRUD operations for {@code code_dependencies} and
 * {@code cross_format_links} tables. All methods accept an explicit
 * {@link Connection} so callers control transaction boundaries.
 *
 * @see CodeGraphExtractor
 */
public class CodeGraphRepository {

    private static final Logger LOG = Logger.getLogger(CodeGraphRepository.class.getName());

    // -----------------------------------------------------------------------
    // code_dependencies
    // -----------------------------------------------------------------------

    /**
     * Represents a single class-level dependency edge.
     */
    public record CodeDependency(
            String workspacePath,
            String sourceFile,
            String sourceClass,
            String sourcePackage,
            String targetFile,
            String targetClass,
            String targetPackage,
            String dependencyType,
            boolean isExternal,
            long lastComputed
    ) {}

    /**
     * Represents a cross-format link between a non-Java file and a Java file.
     */
    public record CrossFormatLinkRecord(
            String workspacePath,
            String sourceFile,
            String targetFile,
            String linkType,
            String entityName,
            long lastComputed
    ) {}

    /**
     * Inserts or replaces a dependency edge.
     * Uses INSERT OR REPLACE (SQLite UPSERT) to handle the UNIQUE constraint.
     */
    public void upsertDependency(Connection conn, CodeDependency dep) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO code_dependencies (
                workspace_path, source_file, source_class, source_package,
                target_file, target_class, target_package,
                dependency_type, is_external, last_computed
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dep.workspacePath());
            ps.setString(2, dep.sourceFile());
            ps.setString(3, dep.sourceClass());
            ps.setString(4, dep.sourcePackage());
            ps.setString(5, dep.targetFile());
            ps.setString(6, dep.targetClass());
            ps.setString(7, dep.targetPackage());
            ps.setString(8, dep.dependencyType());
            ps.setInt(9, dep.isExternal() ? 1 : 0);
            ps.setLong(10, dep.lastComputed());
            ps.executeUpdate();
        }
    }

    /**
     * Inserts or replaces a cross-format link.
     */
    public void upsertCrossFormatLink(Connection conn, CrossFormatLinkRecord link) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO cross_format_links (
                workspace_path, source_file, target_file,
                link_type, entity_name, last_computed
            ) VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, link.workspacePath());
            ps.setString(2, link.sourceFile());
            ps.setString(3, link.targetFile());
            ps.setString(4, link.linkType());
            ps.setString(5, link.entityName());
            ps.setLong(6, link.lastComputed());
            ps.executeUpdate();
        }
    }

    /**
     * Returns all dependencies originating from a specific file.
     */
    public List<CodeDependency> getDependenciesFrom(Connection conn, String workspacePath,
                                                      String sourceFile) throws SQLException {
        String sql = """
            SELECT * FROM code_dependencies
            WHERE workspace_path = ? AND source_file = ?
            ORDER BY target_class
            """;
        return queryDependencies(conn, sql, workspacePath, sourceFile);
    }

    /**
     * Returns all dependencies targeting a specific class/package.
     * Finds all files that depend on the given target.
     */
    public List<CodeDependency> getDependenciesTo(Connection conn, String workspacePath,
                                                    String targetClass,
                                                    String targetPackage) throws SQLException {
        String sql;
        List<CodeDependency> results = new ArrayList<>();

        if (targetPackage != null && !targetPackage.isBlank()) {
            sql = """
                SELECT * FROM code_dependencies
                WHERE workspace_path = ? AND target_class = ? AND target_package = ?
                ORDER BY source_file
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, workspacePath);
                ps.setString(2, targetClass);
                ps.setString(3, targetPackage);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        results.add(mapDependency(rs));
                    }
                }
            }
        } else {
            sql = """
                SELECT * FROM code_dependencies
                WHERE workspace_path = ? AND target_class = ?
                ORDER BY source_file
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, workspacePath);
                ps.setString(2, targetClass);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        results.add(mapDependency(rs));
                    }
                }
            }
        }
        return results;
    }

    /**
     * Returns all files that import/reference anything from the given file path.
     * This supports BFS traversal for impact analysis.
     */
    public List<CodeDependency> getIncomingForFile(Connection conn, String workspacePath,
                                                     String targetFile) throws SQLException {
        String sql = """
            SELECT * FROM code_dependencies
            WHERE workspace_path = ? AND target_file = ?
            ORDER BY source_file
            """;
        return queryDependencies(conn, sql, workspacePath, targetFile);
    }

    /**
     * Returns all dependencies in the workspace (for full graph queries).
     */
    public List<CodeDependency> getAllDependencies(Connection conn, String workspacePath)
            throws SQLException {
        String sql = "SELECT * FROM code_dependencies WHERE workspace_path = ?";
        List<CodeDependency> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapDependency(rs));
                }
            }
        }
        return results;
    }

    /**
     * Deletes all dependencies originating from a specific file.
     * Used for incremental updates: delete old edges, then re-extract.
     */
    public int deleteDependenciesForFile(Connection conn, String workspacePath,
                                           String filePath) throws SQLException {
        String sql = "DELETE FROM code_dependencies WHERE workspace_path = ? AND source_file = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, filePath);
            return ps.executeUpdate();
        }
    }

    /**
     * Deletes all dependencies for a workspace. Used for full re-extraction.
     */
    public int deleteAllDependencies(Connection conn, String workspacePath) throws SQLException {
        String sql = "DELETE FROM code_dependencies WHERE workspace_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            return ps.executeUpdate();
        }
    }

    /**
     * Deletes all cross-format links for a workspace.
     */
    public int deleteAllCrossFormatLinks(Connection conn, String workspacePath) throws SQLException {
        String sql = "DELETE FROM cross_format_links WHERE workspace_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            return ps.executeUpdate();
        }
    }

    /**
     * Returns all cross-format links for a workspace.
     */
    public List<CrossFormatLinkRecord> getCrossFormatLinks(Connection conn,
                                                            String workspacePath) throws SQLException {
        String sql = "SELECT * FROM cross_format_links WHERE workspace_path = ?";
        List<CrossFormatLinkRecord> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new CrossFormatLinkRecord(
                            rs.getString("workspace_path"),
                            rs.getString("source_file"),
                            rs.getString("target_file"),
                            rs.getString("link_type"),
                            rs.getString("entity_name"),
                            rs.getLong("last_computed")
                    ));
                }
            }
        }
        return results;
    }

    /**
     * Counts total dependency edges for a workspace.
     */
    public int countDependencies(Connection conn, String workspacePath) throws SQLException {
        String sql = "SELECT COUNT(*) FROM code_dependencies WHERE workspace_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Counts total cross-format links for a workspace.
     */
    public int countCrossFormatLinks(Connection conn, String workspacePath) throws SQLException {
        String sql = "SELECT COUNT(*) FROM cross_format_links WHERE workspace_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Returns true if the graph has been populated for the given workspace.
     */
    public boolean isPopulated(Connection conn, String workspacePath) throws SQLException {
        return countDependencies(conn, workspacePath) > 0;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private List<CodeDependency> queryDependencies(Connection conn, String sql,
                                                     String param1, String param2)
            throws SQLException {
        List<CodeDependency> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param1);
            ps.setString(2, param2);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapDependency(rs));
                }
            }
        }
        return results;
    }

    private CodeDependency mapDependency(ResultSet rs) throws SQLException {
        return new CodeDependency(
                rs.getString("workspace_path"),
                rs.getString("source_file"),
                rs.getString("source_class"),
                rs.getString("source_package"),
                rs.getString("target_file"),
                rs.getString("target_class"),
                rs.getString("target_package"),
                rs.getString("dependency_type"),
                rs.getInt("is_external") == 1,
                rs.getLong("last_computed")
        );
    }
}
