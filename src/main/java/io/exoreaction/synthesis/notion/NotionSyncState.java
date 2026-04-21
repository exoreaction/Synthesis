package io.exoreaction.synthesis.notion;

import io.exoreaction.synthesis.db.SynthesisDatabase;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Data access object for the {@code notion_pages} and {@code notion_sync_state} tables.
 *
 * <p>All methods synchronize on the parent {@link SynthesisDatabase} instance to
 * ensure thread safety (mirrors the pattern used by {@code SessionStore}).
 */
public class NotionSyncState {

    private static final Logger LOG = Logger.getLogger(NotionSyncState.class.getName());

    private final SynthesisDatabase db;

    public NotionSyncState(SynthesisDatabase db) {
        this.db = db;
    }

    /**
     * Inserts or updates a Notion page record.
     *
     * @param workspaceName the Synthesis workspace name
     * @param pageId        the Notion page UUID
     * @param title         the page title
     * @param parentPageId  the parent page UUID (null for root-level pages)
     * @param virtualPath   the resolved virtual filesystem path
     * @param lastEditedTime when the page was last edited in Notion
     * @param contentHash   SHA-256 hash of the page content (for change detection)
     * @param pageUrl       the Notion page URL
     * @param isDatabase    whether this page is a Notion database
     */
    public synchronized void recordPage(String workspaceName, String pageId, String title,
                                        String parentPageId, String virtualPath,
                                        Instant lastEditedTime, String contentHash,
                                        String pageUrl, boolean isDatabase) throws SQLException {
        String sql = """
            INSERT INTO notion_pages (
                page_id, workspace_name, title, parent_page_id, virtual_path,
                last_edited_at, last_synced_at, content_hash, page_url, is_database
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(page_id, workspace_name) DO UPDATE SET
                title          = excluded.title,
                parent_page_id = excluded.parent_page_id,
                virtual_path   = excluded.virtual_path,
                last_edited_at = excluded.last_edited_at,
                last_synced_at = excluded.last_synced_at,
                content_hash   = excluded.content_hash,
                page_url       = excluded.page_url,
                is_database    = excluded.is_database
            """;

        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pageId);
            ps.setString(2, workspaceName);
            ps.setString(3, title);
            ps.setString(4, parentPageId);
            ps.setString(5, virtualPath);
            ps.setLong(6, lastEditedTime != null ? lastEditedTime.getEpochSecond() : 0);
            ps.setLong(7, Instant.now().getEpochSecond());
            ps.setString(8, contentHash);
            ps.setString(9, pageUrl);
            ps.setBoolean(10, isDatabase);
            ps.executeUpdate();
        }
    }

    /**
     * Inserts or updates the sync state for a workspace.
     *
     * @param workspaceName the Synthesis workspace name
     * @param notionRootId  the Notion root page ID being synced
     * @param lastSyncTime  when the last sync completed
     * @param totalPages    total number of pages synced
     * @param status        sync status ("ok", "error", "partial")
     * @param errorMessage  error message (null if status is "ok")
     */
    public synchronized void upsertSyncState(String workspaceName, String notionRootId,
                                             Instant lastSyncTime, int totalPages,
                                             String status, String errorMessage) throws SQLException {
        String sql = """
            INSERT INTO notion_sync_state (
                workspace_name, notion_root_id, last_sync_time, total_pages,
                last_sync_status, error_message
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(workspace_name) DO UPDATE SET
                notion_root_id   = excluded.notion_root_id,
                last_sync_time   = excluded.last_sync_time,
                total_pages      = excluded.total_pages,
                last_sync_status = excluded.last_sync_status,
                error_message    = excluded.error_message
            """;

        Connection conn = db.getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspaceName);
            ps.setString(2, notionRootId);
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
        String sql = "SELECT last_sync_time FROM notion_sync_state WHERE workspace_name = ?";
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
     * of live page IDs. These are "orphan" pages that have been deleted from Notion.
     *
     * @param workspaceName the Synthesis workspace name
     * @param livePageIds   the set of currently-live Notion page IDs
     * @return list of orphaned page IDs
     */
    public synchronized List<String> getOrphanPageIds(String workspaceName,
                                                      Set<String> livePageIds) throws SQLException {
        String sql = "SELECT page_id FROM notion_pages WHERE workspace_name = ?";
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
        String sql = "SELECT virtual_path FROM notion_pages WHERE workspace_name = ? AND last_synced_at < ?";
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
     * Returns virtual paths that are shared by more than one page in the workspace.
     *
     * @param workspaceName the Synthesis workspace name
     * @return set of duplicate virtual paths
     */
    public synchronized Set<String> getDuplicateVirtualPaths(String workspaceName) throws SQLException {
        String sql = """
            SELECT virtual_path FROM notion_pages
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
