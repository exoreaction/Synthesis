package io.exoreaction.synthesis.confluence;

import io.exoreaction.synthesis.db.SynthesisDatabase;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Data access object for the {@code confluence_pages} and {@code confluence_sync_state} tables.
 *
 * <p>Mirrors {@code NotionSyncState}'s shape exactly: page records + sync-state table,
 * content-hash change detection, orphan/stale/conflict queries. The one addition is a
 * {@code space_key} column on {@code confluence_pages} (Confluence has spaces in addition
 * to the page-parent tree Notion has) and a {@code labels} column (Confluence-native
 * concept with no Notion equivalent — captured now as a future entity-linking hint for
 * RFC #543, per RFC #541's open question 2).
 *
 * <p>All methods synchronize on the parent {@link SynthesisDatabase} instance to
 * ensure thread safety (mirrors the pattern used by {@code NotionSyncState}).
 */
public class ConfluenceSyncState {

    private static final Logger LOG = Logger.getLogger(ConfluenceSyncState.class.getName());

    private final SynthesisDatabase db;

    public ConfluenceSyncState(SynthesisDatabase db) {
        this.db = db;
    }

    /**
     * Inserts or updates a Confluence page record.
     *
     * @param workspaceName the Synthesis workspace name
     * @param pageId        the Confluence page ID
     * @param spaceKey      the key of the space this page belongs to
     * @param title         the page title
     * @param parentPageId  the parent page ID (null for space-root pages)
     * @param virtualPath   the resolved virtual filesystem path
     * @param lastEditedTime when the page's current version was created in Confluence
     * @param contentHash   SHA-256 hash of the page content (for change detection)
     * @param pageUrl       the Confluence page URL
     * @param labels        comma-joined label names (empty/null if none)
     */
    public synchronized void recordPage(String workspaceName, String pageId, String spaceKey, String title,
                                        String parentPageId, String virtualPath,
                                        Instant lastEditedTime, String contentHash,
                                        String pageUrl, String labels) throws SQLException {
        String sql = """
            INSERT INTO confluence_pages (
                page_id, workspace_name, space_key, title, parent_page_id, virtual_path,
                last_edited_at, last_synced_at, content_hash, page_url, labels
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(page_id, workspace_name) DO UPDATE SET
                space_key      = excluded.space_key,
                title          = excluded.title,
                parent_page_id = excluded.parent_page_id,
                virtual_path   = excluded.virtual_path,
                last_edited_at = excluded.last_edited_at,
                last_synced_at = excluded.last_synced_at,
                content_hash   = excluded.content_hash,
                page_url       = excluded.page_url,
                labels         = excluded.labels
            """;

        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pageId);
            ps.setString(2, workspaceName);
            ps.setString(3, spaceKey);
            ps.setString(4, title);
            ps.setString(5, parentPageId);
            ps.setString(6, virtualPath);
            ps.setLong(7, lastEditedTime != null ? lastEditedTime.getEpochSecond() : 0);
            ps.setLong(8, Instant.now().getEpochSecond());
            ps.setString(9, contentHash);
            ps.setString(10, pageUrl);
            ps.setString(11, labels);
            ps.executeUpdate();
        }
    }

    /**
     * Inserts or updates the sync state for a workspace.
     *
     * @param workspaceName   the Synthesis workspace name
     * @param spaceKeys       comma-joined Confluence space keys being synced
     * @param lastSyncTime    when the last sync completed
     * @param totalPages      total number of pages synced
     * @param status          sync status ("ok", "error", "partial")
     * @param errorMessage    error message (null if status is "ok")
     */
    public synchronized void upsertSyncState(String workspaceName, String spaceKeys,
                                             Instant lastSyncTime, int totalPages,
                                             String status, String errorMessage) throws SQLException {
        String sql = """
            INSERT INTO confluence_sync_state (
                workspace_name, confluence_space_keys, last_sync_time, total_pages,
                last_sync_status, error_message
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(workspace_name) DO UPDATE SET
                confluence_space_keys = excluded.confluence_space_keys,
                last_sync_time        = excluded.last_sync_time,
                total_pages           = excluded.total_pages,
                last_sync_status      = excluded.last_sync_status,
                error_message         = excluded.error_message
            """;

        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            ps.setString(2, spaceKeys != null ? spaceKeys : "");
            ps.setLong(3, lastSyncTime != null ? lastSyncTime.getEpochSecond() : 0);
            ps.setInt(4, totalPages);
            ps.setString(5, status != null ? status : "ok");
            ps.setString(6, errorMessage);
            ps.executeUpdate();
        }
    }

    /**
     * Returns the last sync time for a workspace.
     *
     * @param workspaceName the Synthesis workspace name
     * @return the last sync time, or empty if no sync has occurred
     */
    public synchronized Optional<Instant> getLastSyncTime(String workspaceName) throws SQLException {
        String sql = "SELECT last_sync_time FROM confluence_sync_state WHERE workspace_name = ?";
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long epoch = rs.getLong("last_sync_time");
                    return epoch > 0 ? Optional.of(Instant.ofEpochSecond(epoch)) : Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Returns page IDs that exist in the database but are not in the provided set
     * of live page IDs. These are "orphan" pages that have been deleted from Confluence.
     *
     * @param workspaceName the Synthesis workspace name
     * @param livePageIds   the set of currently-live Confluence page IDs
     * @return list of orphaned page IDs
     */
    public synchronized List<String> getOrphanPageIds(String workspaceName,
                                                      Set<String> livePageIds) throws SQLException {
        String sql = "SELECT page_id FROM confluence_pages WHERE workspace_name = ?";
        Connection conn = db.getConnection();
        List<String> orphans = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pageId = rs.getString("page_id");
                    if (!livePageIds.contains(pageId)) {
                        orphans.add(pageId);
                    }
                }
            }
        }
        return orphans;
    }

    /**
     * Returns virtual paths of pages whose last sync time exceeds the stale threshold.
     *
     * @param workspaceName  the Synthesis workspace name
     * @param staleThreshold pages not synced within this duration are considered stale
     * @return list of stale virtual paths
     */
    public synchronized List<String> getStalePaths(String workspaceName,
                                                   Duration staleThreshold) throws SQLException {
        long cutoffEpoch = Instant.now().minus(staleThreshold).getEpochSecond();
        String sql = "SELECT virtual_path FROM confluence_pages WHERE workspace_name = ? AND last_synced_at < ?";
        Connection conn = db.getConnection();
        List<String> stalePaths = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            ps.setLong(2, cutoffEpoch);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stalePaths.add(rs.getString("virtual_path"));
                }
            }
        }
        return stalePaths;
    }

    /**
     * Returns the total number of pages recorded for a workspace from the sync state table.
     *
     * @param workspaceName the Synthesis workspace name
     * @return the total pages count from the last sync, or 0 if no sync has occurred
     */
    public synchronized int getTotalPages(String workspaceName) throws SQLException {
        String sql = "SELECT total_pages FROM confluence_sync_state WHERE workspace_name = ?";
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("total_pages");
                }
            }
        }
        return 0;
    }

    /**
     * Returns the count of pages in the confluence_pages table for a workspace.
     *
     * @param workspaceName the Synthesis workspace name
     * @return the number of tracked Confluence pages
     */
    public synchronized int getPageCount(String workspaceName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM confluence_pages WHERE workspace_name = ?";
        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    /**
     * Returns all tracked page IDs for a workspace (for orphan detection without
     * requiring a live page set).
     *
     * @param workspaceName the Synthesis workspace name
     * @return set of all tracked page IDs
     */
    public synchronized Set<String> getAllPageIds(String workspaceName) throws SQLException {
        String sql = "SELECT page_id FROM confluence_pages WHERE workspace_name = ?";
        Connection conn = db.getConnection();
        Set<String> ids = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString("page_id"));
                }
            }
        }
        return ids;
    }

    /**
     * Returns virtual paths that are shared by more than one page in the workspace.
     *
     * @param workspaceName the Synthesis workspace name
     * @return set of duplicate virtual paths
     */
    public synchronized Set<String> getDuplicateVirtualPaths(String workspaceName) throws SQLException {
        String sql = """
            SELECT virtual_path FROM confluence_pages
            WHERE workspace_name = ?
            GROUP BY virtual_path
            HAVING COUNT(*) > 1
            """;
        Connection conn = db.getConnection();
        Set<String> duplicates = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    duplicates.add(rs.getString("virtual_path"));
                }
            }
        }
        return duplicates;
    }
}
