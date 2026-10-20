package io.exoreaction.synthesis.confluence;

import com.fasterxml.jackson.databind.JsonNode;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.ConfluenceConfig;
import io.exoreaction.synthesis.db.SynthesisDatabase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Orchestrates a full Confluence workspace sync: fetches pages for each configured
 * space, converts storage-format bodies to Markdown, builds virtual paths, and
 * persists state to the database.
 *
 * <p>Mirrors {@code NotionWorkspaceSource}'s two-mode shape:
 * <ul>
 *   <li><b>Full sync</b> ({@link #sync()}) — fetches all pages in each configured
 *       space.</li>
 *   <li><b>Incremental sync</b> ({@link #incrementalSync()}) — fetches the same way,
 *       then filters to pages whose current version was created after the last sync.</li>
 * </ul>
 *
 * <p>Two genuine differences from the Notion source, both consequences of Confluence's
 * actual API shape rather than stylistic choices:
 * <ul>
 *   <li>No BFS traversal is needed to discover the page tree — listing a space's pages
 *       returns every page's {@code parentId} directly (see {@link ConfluenceClient}).</li>
 *   <li>No separate "fetch blocks" call per page is needed — requesting
 *       {@code body-format=storage} on the listing call itself returns each page's body
 *       inline, unlike Notion where block children must be fetched per-page.</li>
 * </ul>
 *
 * <p>Use the factory method {@link #fromConfig(SynthesisConfig, SynthesisDatabase)}
 * for standard construction from configuration.
 */
public class ConfluenceWorkspaceSource {

    private static final Logger LOG = Logger.getLogger(ConfluenceWorkspaceSource.class.getName());

    /** Body representation requested from the Confluence API — see RFC #541 open question 1. */
    private static final String BODY_FORMAT = "storage";

    private final ConfluenceClient client;
    private final ConfluencePageMapper mapper;
    private final ConfluenceSyncState syncState;
    private final ConfluenceContentToMarkdown contentToMarkdown;
    private final SynthesisConfig config;

    /**
     * Creates a new ConfluenceWorkspaceSource with all required collaborators.
     *
     * @param client            Confluence API client for HTTP operations
     * @param mapper            page-to-virtual-path mapper
     * @param syncState         DAO for persisting sync state and page records
     * @param contentToMarkdown converter from Confluence storage-format XHTML to Markdown
     * @param config            Synthesis configuration (provides workspace name, Confluence settings)
     */
    public ConfluenceWorkspaceSource(ConfluenceClient client, ConfluencePageMapper mapper,
                                      ConfluenceSyncState syncState,
                                      ConfluenceContentToMarkdown contentToMarkdown,
                                      SynthesisConfig config) {
        this.client = client;
        this.mapper = mapper;
        this.syncState = syncState;
        this.contentToMarkdown = contentToMarkdown;
        this.config = config;
    }

    /**
     * Factory method that constructs a ConfluenceWorkspaceSource from configuration and database.
     *
     * @param config the Synthesis configuration
     * @param db     the Synthesis database for persistence
     * @return a fully wired ConfluenceWorkspaceSource
     */
    public static ConfluenceWorkspaceSource fromConfig(SynthesisConfig config, SynthesisDatabase db) {
        ConfluenceClient client = ConfluenceClient.fromConfig(config.getConfluence());
        ConfluencePageMapper mapper = new ConfluencePageMapper();
        ConfluenceSyncState syncState = new ConfluenceSyncState(db);
        ConfluenceContentToMarkdown contentToMarkdown = new ConfluenceContentToMarkdown();
        return new ConfluenceWorkspaceSource(client, mapper, syncState, contentToMarkdown, config);
    }

    /**
     * Performs a full sync: fetches all pages in each configured space, converts
     * storage-format bodies to Markdown, builds virtual paths, and saves to the database.
     *
     * @return list of synced Confluence pages for downstream indexing
     * @throws IOException          on Confluence API errors
     * @throws InterruptedException if the thread is interrupted during API calls
     */
    public List<ConfluencePageMapper.ConfluencePage> sync() throws IOException, InterruptedException {
        ConfluenceConfig confluenceConfig = config.getConfluence();
        String workspaceName = config.getWorkspace().getName();
        int maxPages = confluenceConfig.getMaxPagesPerSync();

        FetchResult fetched = fetchAllConfiguredSpaces(confluenceConfig.getSpaceKeys(), maxPages);
        return processPages(fetched.pages, fetched.pageIdToSpaceKey, workspaceName, fetched.syncedSpaceKeys);
    }

    /**
     * Performs an incremental sync: fetches the same way as {@link #sync()}, then filters
     * to pages whose current version was created after the last sync time. If no previous
     * sync exists, delegates to a full sync.
     *
     * @return list of changed Confluence pages for downstream indexing
     * @throws IOException          on Confluence API errors
     * @throws InterruptedException if the thread is interrupted during API calls
     */
    public List<ConfluencePageMapper.ConfluencePage> incrementalSync() throws IOException, InterruptedException {
        ConfluenceConfig confluenceConfig = config.getConfluence();
        String workspaceName = config.getWorkspace().getName();

        Optional<Instant> lastSyncOpt;
        try {
            lastSyncOpt = syncState.getLastSyncTime(workspaceName);
        } catch (Exception e) {
            LOG.warning("Failed to read last sync time, falling back to full sync: " + e.getMessage());
            return sync();
        }

        if (lastSyncOpt.isEmpty()) {
            return sync();
        }

        Instant lastSyncTime = lastSyncOpt.get();
        LOG.info("Incremental sync since " + lastSyncTime);

        FetchResult fetched = fetchAllConfiguredSpaces(confluenceConfig.getSpaceKeys(),
                confluenceConfig.getMaxPagesPerSync());

        List<JsonNode> changedPages = new ArrayList<>();
        for (JsonNode page : fetched.pages) {
            Instant lastEdited = parseVersionCreatedAt(page);
            if (lastEdited != null && lastEdited.isAfter(lastSyncTime)) {
                changedPages.add(page);
            }
        }

        if (changedPages.isEmpty()) {
            LOG.info("No pages changed since last sync");
            return List.of();
        }

        LOG.info("Found " + changedPages.size() + " changed pages since last sync");
        return processPages(changedPages, fetched.pageIdToSpaceKey, workspaceName, fetched.syncedSpaceKeys);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private record FetchResult(List<JsonNode> pages, Map<String, String> pageIdToSpaceKey,
                                List<String> syncedSpaceKeys) {}

    /**
     * Fetches all pages (with inline body content) across every configured space.
     * A space that fails to resolve or fetch is logged and skipped — the sync continues
     * with whatever spaces succeeded, mirroring Notion's per-page tolerant error handling.
     */
    private FetchResult fetchAllConfiguredSpaces(List<String> spaceKeys, int maxPages)
            throws InterruptedException {
        List<JsonNode> pages = new ArrayList<>();
        Map<String, String> pageIdToSpaceKey = new LinkedHashMap<>();
        List<String> syncedSpaceKeys = new ArrayList<>();

        for (String spaceKey : spaceKeys) {
            if (pages.size() >= maxPages) break;
            try {
                JsonNode space = client.getSpaceByKey(spaceKey);
                String spaceId = space.get("id").asText();
                List<JsonNode> spacePages = client.getPagesForSpace(spaceId, BODY_FORMAT);
                for (JsonNode page : spacePages) {
                    if (pages.size() >= maxPages) break;
                    String pageId = page.has("id") ? page.get("id").asText() : "";
                    if (pageId.isEmpty()) continue;
                    pageIdToSpaceKey.put(pageId, spaceKey);
                    pages.add(page);
                }
                syncedSpaceKeys.add(spaceKey);
            } catch (Exception e) {
                // Tolerate a single space failing to resolve/fetch (missing key, permission
                // error, transient API error) and continue with the remaining configured
                // spaces — mirrors NotionWorkspaceSource's per-page tolerant error handling.
                LOG.warning("Failed to fetch Confluence space " + spaceKey + ": " + e.getMessage());
            }
        }

        return new FetchResult(pages, pageIdToSpaceKey, syncedSpaceKeys);
    }

    /**
     * Processes raw page JSON objects: extracts inline body content, converts it to
     * Markdown, builds virtual paths, resolves collisions, and persists to the database.
     */
    private List<ConfluencePageMapper.ConfluencePage> processPages(
            List<JsonNode> rawPages, Map<String, String> pageIdToSpaceKey,
            String workspaceName, List<String> syncedSpaceKeys) {

        // Step 1: Build ID-to-title and ID-to-parent maps
        Map<String, String> idToTitle = new LinkedHashMap<>();
        Map<String, String> idToParent = new LinkedHashMap<>();

        for (JsonNode page : rawPages) {
            String pageId = page.has("id") ? page.get("id").asText() : "";
            if (pageId.isEmpty()) continue;

            idToTitle.put(pageId, extractTitle(page));
            String parentId = extractParentId(page);
            if (parentId != null) {
                idToParent.put(pageId, parentId);
            }
        }

        // Step 2: For each page, extract the inline body and convert to Markdown
        List<ConfluencePageMapper.ConfluencePage> pages = new ArrayList<>();

        for (JsonNode page : rawPages) {
            String pageId = page.has("id") ? page.get("id").asText() : "";
            if (pageId.isEmpty()) continue;

            String title = idToTitle.get(pageId);
            String parentId = idToParent.get(pageId);
            String spaceKey = pageIdToSpaceKey.getOrDefault(pageId, "");
            Instant lastEdited = parseVersionCreatedAt(page);
            Instant created = parseCreatedTime(page);

            String ownPath = mapper.buildVirtualPath(pageId, idToTitle, idToParent, "");
            String virtualPath = spaceKey.isEmpty() ? ownPath : spaceKey + "/" + ownPath;

            String markdownContent = contentToMarkdown.convert(extractStorageBody(page));

            if (!title.isEmpty() && !title.equals("Untitled")) {
                markdownContent = "# " + title + "\n\n" + markdownContent;
            }

            pages.add(new ConfluencePageMapper.ConfluencePage(
                    pageId, title, spaceKey, parentId,
                    lastEdited != null ? lastEdited : Instant.now(),
                    created != null ? created : Instant.now(),
                    virtualPath, markdownContent
            ));
        }

        // Step 3: Resolve path collisions
        pages = mapper.resolveCollisions(pages);

        // Step 4: Persist to database
        String spaceKeysJoined = String.join(",", syncedSpaceKeys);
        try {
            for (ConfluencePageMapper.ConfluencePage page : pages) {
                String contentHash = sha256(page.markdownContent());

                // Labels are not fetched during sync in v1 — RFC #541 leaves "capture labels
                // now vs. defer to RFC #543" as an open question. ConfluenceClient.getLabelsForPage
                // is implemented and tested standalone so a future caller can opt in once that's
                // decided, without adding a per-page API call to every sync today.
                syncState.recordPage(workspaceName, page.id(), page.spaceKey(), page.title(),
                        page.parentId(), page.virtualPath(),
                        page.lastEditedTime(), contentHash,
                        null, null);
            }

            syncState.upsertSyncState(workspaceName, spaceKeysJoined, Instant.now(), pages.size(), "ok", null);

        } catch (Exception e) {
            LOG.warning("Failed to persist sync state: " + e.getMessage());
            try {
                syncState.upsertSyncState(workspaceName, spaceKeysJoined, Instant.now(), pages.size(),
                        "error", e.getMessage());
            } catch (Exception ignored) {
                // Best effort
            }
        }

        return pages;
    }

    /**
     * Extracts the page title from a Confluence page JSON object.
     */
    static String extractTitle(JsonNode page) {
        if (page == null) return "Untitled";
        JsonNode title = page.get("title");
        if (title != null && !title.isNull() && !title.asText().isBlank()) {
            return title.asText();
        }
        return "Untitled";
    }

    /**
     * Extracts the parent page ID from a Confluence page JSON object.
     * Returns null if the page has no parent (a space-root page).
     */
    static String extractParentId(JsonNode page) {
        if (page == null) return null;
        JsonNode parentId = page.get("parentId");
        if (parentId != null && !parentId.isNull() && !parentId.asText().isBlank()) {
            return parentId.asText();
        }
        return null;
    }

    /**
     * Extracts the storage-format XHTML body from a Confluence page JSON object
     * (requires the page to have been fetched with {@code body-format=storage}).
     */
    static String extractStorageBody(JsonNode page) {
        if (page == null) return "";
        JsonNode body = page.get("body");
        if (body == null) return "";
        JsonNode storage = body.get("storage");
        if (storage == null) return "";
        JsonNode value = storage.get("value");
        return value != null && !value.isNull() ? value.asText() : "";
    }

    /**
     * Parses {@code version.createdAt} — the Confluence v2 analog of Notion's
     * {@code last_edited_time}: the timestamp of the page's current version.
     */
    static Instant parseVersionCreatedAt(JsonNode page) {
        if (page == null) return null;
        JsonNode version = page.get("version");
        if (version == null) return null;
        JsonNode createdAt = version.get("createdAt");
        if (createdAt != null && !createdAt.isNull()) {
            try {
                return Instant.parse(createdAt.asText());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Parses the page's {@code createdAt} (when the page itself was first created).
     */
    static Instant parseCreatedTime(JsonNode page) {
        if (page == null) return null;
        JsonNode field = page.get("createdAt");
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
}
