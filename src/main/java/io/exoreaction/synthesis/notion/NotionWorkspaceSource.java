package io.exoreaction.synthesis.notion;

import com.fasterxml.jackson.databind.JsonNode;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.NotionConfig;
import io.exoreaction.synthesis.db.SynthesisDatabase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Orchestrates a full Notion workspace sync: fetches pages, converts blocks
 * to Markdown, builds virtual paths, and persists state to the database.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><b>Full sync</b> ({@link #sync()}) — fetches all reachable pages from
 *       a root page (BFS traversal) or the entire workspace (search API).</li>
 *   <li><b>Incremental sync</b> ({@link #incrementalSync()}) — only fetches
 *       pages modified since the last sync time.</li>
 * </ul>
 *
 * <p>Use the factory method {@link #fromConfig(SynthesisConfig, SynthesisDatabase)}
 * for standard construction from configuration.
 */
public class NotionWorkspaceSource {

    private static final Logger LOG = Logger.getLogger(NotionWorkspaceSource.class.getName());

    /** Maximum BFS traversal depth when crawling from a root page. */
    private static final int MAX_BFS_DEPTH = 10;

    private final NotionClient client;
    private final NotionPageMapper mapper;
    private final NotionSyncState syncState;
    private final NotionBlockToMarkdown blockToMarkdown;
    private final SynthesisConfig config;

    /**
     * Creates a new NotionWorkspaceSource with all required collaborators.
     *
     * @param client          Notion API client for HTTP operations
     * @param mapper          page-to-virtual-path mapper
     * @param syncState       DAO for persisting sync state and page records
     * @param blockToMarkdown converter from Notion blocks to Markdown text
     * @param config          Synthesis configuration (provides workspace name, Notion settings)
     */
    public NotionWorkspaceSource(NotionClient client, NotionPageMapper mapper,
                                  NotionSyncState syncState, NotionBlockToMarkdown blockToMarkdown,
                                  SynthesisConfig config) {
        this.client = client;
        this.mapper = mapper;
        this.syncState = syncState;
        this.blockToMarkdown = blockToMarkdown;
        this.config = config;
    }

    /**
     * Factory method that constructs a NotionWorkspaceSource from configuration and database.
     *
     * @param config the Synthesis configuration
     * @param db     the Synthesis database for persistence
     * @return a fully wired NotionWorkspaceSource
     */
    public static NotionWorkspaceSource fromConfig(SynthesisConfig config, SynthesisDatabase db) {
        NotionClient client = NotionClient.fromConfig(config.getNotion());
        NotionPageMapper mapper = new NotionPageMapper();
        NotionSyncState syncState = new NotionSyncState(db);
        NotionBlockToMarkdown blockToMarkdown = new NotionBlockToMarkdown();
        return new NotionWorkspaceSource(client, mapper, syncState, blockToMarkdown, config);
    }

    /**
     * Performs a full sync: fetches all pages reachable from the configured root page ID
     * (or all workspace pages if no root page ID is set), converts blocks to Markdown,
     * builds virtual paths, and saves to the database.
     *
     * @return list of synced Notion pages for downstream indexing
     * @throws IOException          on Notion API errors
     * @throws InterruptedException if the thread is interrupted during API calls
     */
    public List<NotionPageMapper.NotionPage> sync() throws IOException, InterruptedException {
        NotionConfig notionConfig = config.getNotion();
        String rootPageId = notionConfig.getRootPageId();
        String workspaceName = config.getWorkspace().getName();
        int maxPages = notionConfig.getMaxPagesPerSync();

        List<JsonNode> rawPages;

        if (rootPageId != null && !rootPageId.isBlank()) {
            // BFS traversal from root page
            LOG.info("Syncing Notion pages from root: " + rootPageId);
            rawPages = fetchChildPagesRecursive(rootPageId, maxPages);
        } else {
            // Search all workspace pages
            LOG.info("Syncing all Notion workspace pages");
            rawPages = client.searchAllPages();
            if (rawPages.size() > maxPages) {
                rawPages = rawPages.subList(0, maxPages);
            }
        }

        return processPages(rawPages, workspaceName, rootPageId);
    }

    /**
     * Performs an incremental sync: only fetches pages modified since the last sync time.
     * If no previous sync exists, delegates to a full sync.
     *
     * @return list of changed Notion pages for downstream indexing
     * @throws IOException          on Notion API errors
     * @throws InterruptedException if the thread is interrupted during API calls
     */
    public List<NotionPageMapper.NotionPage> incrementalSync() throws IOException, InterruptedException {
        String workspaceName = config.getWorkspace().getName();
        String rootPageId = config.getNotion().getRootPageId();

        Optional<Instant> lastSyncOpt;
        try {
            lastSyncOpt = syncState.getLastSyncTime(workspaceName);
        } catch (Exception e) {
            LOG.warning("Failed to read last sync time, falling back to full sync: " + e.getMessage());
            return sync();
        }

        if (lastSyncOpt.isEmpty()) {
            // No previous sync — do a full sync
            return sync();
        }

        Instant lastSyncTime = lastSyncOpt.get();
        LOG.info("Incremental sync since " + lastSyncTime);

        // Fetch all pages via search and filter by last_edited_time > lastSyncTime
        List<JsonNode> allPages = client.searchAllPages();
        int maxPages = config.getNotion().getMaxPagesPerSync();
        if (allPages.size() > maxPages) {
            allPages = allPages.subList(0, maxPages);
        }

        // Filter to only pages edited after last sync
        List<JsonNode> changedPages = new ArrayList<>();
        for (JsonNode page : allPages) {
            Instant lastEdited = parseLastEditedTime(page);
            if (lastEdited != null && lastEdited.isAfter(lastSyncTime)) {
                changedPages.add(page);
            }
        }

        if (changedPages.isEmpty()) {
            LOG.info("No pages changed since last sync");
            return List.of();
        }

        LOG.info("Found " + changedPages.size() + " changed pages since last sync");
        return processPages(changedPages, workspaceName, rootPageId);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * BFS traversal: fetches child pages recursively from a root page,
     * with cycle detection and depth limiting.
     */
    private List<JsonNode> fetchChildPagesRecursive(String rootPageId, int maxPages)
            throws IOException, InterruptedException {
        List<JsonNode> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<DepthEntry> queue = new ArrayDeque<>();

        // Start with the root page itself
        try {
            JsonNode rootPage = client.getPage(rootPageId);
            result.add(rootPage);
            visited.add(rootPageId);
        } catch (Exception e) {
            LOG.warning("Failed to fetch root page " + rootPageId + ": " + e.getMessage());
            return result;
        }

        queue.add(new DepthEntry(rootPageId, 0));

        while (!queue.isEmpty() && result.size() < maxPages) {
            DepthEntry entry = queue.poll();
            if (entry.depth >= MAX_BFS_DEPTH) {
                continue;
            }

            List<JsonNode> children;
            try {
                children = client.getChildPages(entry.pageId);
            } catch (Exception e) {
                LOG.fine("Failed to fetch children of " + entry.pageId + ": " + e.getMessage());
                continue;
            }

            for (JsonNode child : children) {
                if (result.size() >= maxPages) break;

                String type = child.has("type") ? child.get("type").asText() : "";
                if (!"child_page".equals(type) && !"child_database".equals(type)) {
                    continue;
                }

                String childId = child.has("id") ? child.get("id").asText() : "";
                if (childId.isEmpty() || visited.contains(childId)) {
                    continue;
                }
                visited.add(childId);

                // Fetch the full page object for this child
                try {
                    JsonNode fullPage = client.getPage(childId);
                    result.add(fullPage);
                    queue.add(new DepthEntry(childId, entry.depth + 1));
                } catch (Exception e) {
                    LOG.fine("Failed to fetch page " + childId + ": " + e.getMessage());
                }
            }
        }

        return result;
    }

    /**
     * Processes raw page JSON objects: fetches blocks, converts to Markdown,
     * builds virtual paths, resolves collisions, and persists to the database.
     */
    private List<NotionPageMapper.NotionPage> processPages(List<JsonNode> rawPages,
                                                            String workspaceName,
                                                            String rootPageId)
            throws IOException, InterruptedException {

        // Step 1: Build ID-to-title and ID-to-parent maps
        Map<String, String> idToTitle = new LinkedHashMap<>();
        Map<String, String> idToParent = new LinkedHashMap<>();
        Map<String, JsonNode> idToRawPage = new LinkedHashMap<>();

        for (JsonNode page : rawPages) {
            String pageId = page.has("id") ? page.get("id").asText() : "";
            if (pageId.isEmpty()) continue;

            String title = extractTitle(page);
            String parentId = extractParentPageId(page);

            idToTitle.put(pageId, title);
            if (parentId != null) {
                idToParent.put(pageId, parentId);
            }
            idToRawPage.put(pageId, page);
        }

        // Step 2: For each page, fetch block children and convert to Markdown
        List<NotionPageMapper.NotionPage> pages = new ArrayList<>();
        String effectiveRootId = (rootPageId != null && !rootPageId.isBlank()) ? rootPageId : "";

        for (Map.Entry<String, JsonNode> entry : idToRawPage.entrySet()) {
            String pageId = entry.getKey();
            JsonNode page = entry.getValue();

            String title = idToTitle.get(pageId);
            String parentId = idToParent.get(pageId);
            Instant lastEdited = parseLastEditedTime(page);
            Instant created = parseCreatedTime(page);

            // Build virtual path
            String virtualPath = mapper.buildVirtualPath(pageId, idToTitle, idToParent, effectiveRootId);

            // Fetch blocks and convert to Markdown
            String markdownContent;
            try {
                List<JsonNode> blocks = client.getBlockChildren(pageId);
                markdownContent = blockToMarkdown.convert(blocks);
            } catch (Exception e) {
                LOG.fine("Failed to fetch blocks for page " + pageId + ": " + e.getMessage());
                markdownContent = ""; // Index the page with empty content
            }

            // Prepend title as H1
            if (!title.isEmpty() && !title.equals("Untitled")) {
                markdownContent = "# " + title + "\n\n" + markdownContent;
            }

            pages.add(new NotionPageMapper.NotionPage(
                    pageId, title, parentId,
                    lastEdited != null ? lastEdited : Instant.now(),
                    created != null ? created : Instant.now(),
                    virtualPath, markdownContent
            ));
        }

        // Step 3: Resolve path collisions
        pages = mapper.resolveCollisions(pages);

        // Step 4: Persist to database
        try {
            for (NotionPageMapper.NotionPage page : pages) {
                String contentHash = sha256(page.markdownContent());
                boolean isDatabase = false; // Could be refined later

                syncState.recordPage(workspaceName, page.id(), page.title(),
                        page.parentId(), page.virtualPath(),
                        page.lastEditedTime(), contentHash,
                        "https://notion.so/" + page.id().replace("-", ""),
                        isDatabase);
            }

            // Update sync state
            syncState.upsertSyncState(workspaceName,
                    (rootPageId != null && !rootPageId.isBlank()) ? rootPageId : "",
                    Instant.now(), pages.size(), "ok", null);

        } catch (Exception e) {
            LOG.warning("Failed to persist sync state: " + e.getMessage());
            // Still return pages so they can be indexed even if DB persistence fails
            try {
                syncState.upsertSyncState(workspaceName,
                        (rootPageId != null && !rootPageId.isBlank()) ? rootPageId : "",
                        Instant.now(), pages.size(), "error", e.getMessage());
            } catch (Exception ignored) {
                // Best effort
            }
        }

        return pages;
    }

    /**
     * Extracts the page title from a Notion page JSON object.
     */
    static String extractTitle(JsonNode page) {
        if (page == null) return "Untitled";

        // Check properties.title (for database pages) or properties.Name.title
        JsonNode properties = page.get("properties");
        if (properties != null) {
            // Try "title" property first
            JsonNode titleProp = properties.get("title");
            if (titleProp != null) {
                JsonNode titleArray = titleProp.get("title");
                if (titleArray != null && titleArray.isArray() && !titleArray.isEmpty()) {
                    return titleArray.get(0).has("plain_text")
                            ? titleArray.get(0).get("plain_text").asText()
                            : "Untitled";
                }
            }

            // Try "Name" property (common in databases)
            JsonNode nameProp = properties.get("Name");
            if (nameProp != null) {
                JsonNode titleArray = nameProp.get("title");
                if (titleArray != null && titleArray.isArray() && !titleArray.isEmpty()) {
                    return titleArray.get(0).has("plain_text")
                            ? titleArray.get(0).get("plain_text").asText()
                            : "Untitled";
                }
            }
        }

        // Fallback: check child_page block format
        JsonNode childPage = page.get("child_page");
        if (childPage != null && childPage.has("title")) {
            return childPage.get("title").asText();
        }

        return "Untitled";
    }

    /**
     * Extracts the parent page ID from a Notion page JSON object.
     * Returns null if the parent is a workspace (top-level page).
     */
    static String extractParentPageId(JsonNode page) {
        if (page == null) return null;

        JsonNode parent = page.get("parent");
        if (parent == null) return null;

        String parentType = parent.has("type") ? parent.get("type").asText() : "";
        if ("page_id".equals(parentType) && parent.has("page_id")) {
            return parent.get("page_id").asText();
        }

        return null;
    }

    /**
     * Parses the last_edited_time from a Notion page JSON object.
     */
    static Instant parseLastEditedTime(JsonNode page) {
        if (page == null) return null;
        JsonNode field = page.get("last_edited_time");
        if (field != null && !field.isNull()) {
            try {
                return Instant.parse(field.asText());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Parses the created_time from a Notion page JSON object.
     */
    static Instant parseCreatedTime(JsonNode page) {
        if (page == null) return null;
        JsonNode field = page.get("created_time");
        if (field != null && !field.isNull()) {
            try {
                return Instant.parse(field.asText());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Computes SHA-256 hash of content for change detection.
     */
    private static String sha256(String content) {
        if (content == null || content.isEmpty()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return ""; // SHA-256 is always available in Java
        }
    }

    /** Internal record for BFS queue entries with depth tracking. */
    private record DepthEntry(String pageId, int depth) {}
}
