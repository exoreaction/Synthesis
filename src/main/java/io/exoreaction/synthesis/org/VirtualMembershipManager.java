package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.db.SynthesisDatabase;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages virtual membership relationships between files and directories.
 *
 * <p>When routing produces virtual membership candidates (runners-up from
 * bidding), this manager records them in the SQLite {@code virtual_memberships}
 * table and provides query methods for retrieving virtual members.
 *
 * <p>Virtual members contribute to a directory's centroid with lower weight
 * than physical members, and appear in {@code synthesis describe} output.
 *
 * @since v1.15.0 (P3-03)
 */
public class VirtualMembershipManager {

    private final SynthesisDatabase database;

    /**
     * Creates a manager using the provided database.
     *
     * @param database the Synthesis database for persistence
     */
    public VirtualMembershipManager(SynthesisDatabase database) {
        this.database = database;
    }

    /**
     * A virtual membership record.
     *
     * @param workspacePath  workspace root path
     * @param filePath       file path (relative to workspace)
     * @param directoryPath  directory path (relative to workspace)
     * @param relationship   description of the relationship
     * @param bidStrength    how strongly the directory wanted this file
     * @param createdAt      when the membership was created
     */
    public record VirtualMembership(
            String workspacePath,
            String filePath,
            String directoryPath,
            String relationship,
            double bidStrength,
            Instant createdAt
    ) {}

    /**
     * Records a virtual membership. Uses INSERT OR REPLACE to handle
     * re-routing scenarios where a file's virtual memberships change.
     *
     * @param workspacePath  the workspace root path
     * @param filePath       relative file path
     * @param directoryPath  relative directory path
     * @param relationship   human-readable relationship description
     * @param bidStrength    bid strength (0.0-1.0)
     * @throws SQLException if the database operation fails
     */
    public void recordMembership(String workspacePath, String filePath,
                                  String directoryPath, String relationship,
                                  double bidStrength) throws SQLException {
        Connection conn = database.getConnection();
        String sql = "INSERT OR REPLACE INTO virtual_memberships "
                + "(workspace_path, file_path, directory_path, relationship, bid_strength, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, filePath);
            ps.setString(3, directoryPath);
            ps.setString(4, relationship);
            ps.setDouble(5, bidStrength);
            ps.setLong(6, Instant.now().getEpochSecond());
            ps.executeUpdate();
        }
    }

    /**
     * Records virtual memberships from a list of bids.
     *
     * @param workspacePath  the workspace root path
     * @param filePath       relative file path
     * @param virtualBids    the virtual membership bids
     * @throws SQLException if the database operation fails
     */
    public void recordBids(String workspacePath, String filePath,
                            List<Bid> virtualBids) throws SQLException {
        for (Bid bid : virtualBids) {
            String dirPath = bid.directory().toString();
            String relationship = deriveRelationship(bid);
            recordMembership(workspacePath, filePath, dirPath, relationship, bid.strength());
        }
    }

    /**
     * Retrieves all virtual members of a directory.
     *
     * @param workspacePath  the workspace root path
     * @param directoryPath  relative directory path
     * @return list of virtual memberships
     * @throws SQLException if the database operation fails
     */
    public List<VirtualMembership> getVirtualMembers(String workspacePath,
                                                       String directoryPath) throws SQLException {
        Connection conn = database.getConnection();
        String sql = "SELECT file_path, relationship, bid_strength, created_at "
                + "FROM virtual_memberships "
                + "WHERE workspace_path = ? AND directory_path = ? "
                + "ORDER BY bid_strength DESC";
        List<VirtualMembership> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, directoryPath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new VirtualMembership(
                            workspacePath,
                            rs.getString("file_path"),
                            directoryPath,
                            rs.getString("relationship"),
                            rs.getDouble("bid_strength"),
                            Instant.ofEpochSecond(rs.getLong("created_at"))
                    ));
                }
            }
        }
        return result;
    }

    /**
     * Retrieves all directories that have virtual membership for a file.
     *
     * @param workspacePath  the workspace root path
     * @param filePath       relative file path
     * @return list of virtual memberships
     * @throws SQLException if the database operation fails
     */
    public List<VirtualMembership> getVirtualMembershipsForFile(String workspacePath,
                                                                  String filePath) throws SQLException {
        Connection conn = database.getConnection();
        String sql = "SELECT directory_path, relationship, bid_strength, created_at "
                + "FROM virtual_memberships "
                + "WHERE workspace_path = ? AND file_path = ? "
                + "ORDER BY bid_strength DESC";
        List<VirtualMembership> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new VirtualMembership(
                            workspacePath,
                            filePath,
                            rs.getString("directory_path"),
                            rs.getString("relationship"),
                            rs.getDouble("bid_strength"),
                            Instant.ofEpochSecond(rs.getLong("created_at"))
                    ));
                }
            }
        }
        return result;
    }

    /**
     * Counts virtual members for a directory.
     *
     * @param workspacePath  the workspace root path
     * @param directoryPath  relative directory path
     * @return count of virtual members
     * @throws SQLException if the database operation fails
     */
    public int countVirtualMembers(String workspacePath, String directoryPath)
            throws SQLException {
        Connection conn = database.getConnection();
        String sql = "SELECT COUNT(*) FROM virtual_memberships "
                + "WHERE workspace_path = ? AND directory_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, directoryPath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Removes all virtual memberships for a file (used when re-routing).
     *
     * @param workspacePath  the workspace root path
     * @param filePath       relative file path
     * @throws SQLException if the database operation fails
     */
    public void removeAllForFile(String workspacePath, String filePath) throws SQLException {
        Connection conn = database.getConnection();
        String sql = "DELETE FROM virtual_memberships WHERE workspace_path = ? AND file_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspacePath);
            ps.setString(2, filePath);
            ps.executeUpdate();
        }
    }

    /**
     * Derives a relationship description from a bid's reasoning.
     */
    private static String deriveRelationship(Bid bid) {
        if (bid.reasons().isEmpty()) return "semantic match";

        // Use first topic or entity match as relationship
        for (String reason : bid.reasons()) {
            if (reason.startsWith("topic-match")) {
                return "topic overlap";
            }
            if (reason.startsWith("entity-match")) {
                return "entity overlap";
            }
            if (reason.startsWith("type-match")) {
                return "document type match";
            }
        }
        return "semantic match";
    }
}
