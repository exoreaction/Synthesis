package io.exoreaction.synthesis.jira;

import com.fasterxml.jackson.databind.JsonNode;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.JiraConfig;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.jira.JiraIssueMapper.JiraChangelogEntry;
import io.exoreaction.synthesis.jira.JiraIssueMapper.JiraComment;
import io.exoreaction.synthesis.jira.JiraIssueMapper.JiraIssue;
import io.exoreaction.synthesis.jira.JiraIssueMapper.JiraIssueLink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Orchestrates a full Jira workspace sync: searches issues via JQL, fetches
 * changelog/comments per issue, converts ADF fields to Markdown, builds virtual
 * paths, and persists state to the database — the Jira equivalent of
 * {@code NotionWorkspaceSource}.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><b>Full sync</b> ({@link #sync()}) — fetches all issues matching the
 *       configured {@code jqlFilter} or {@code projectKeys}.</li>
 *   <li><b>Incremental sync</b> ({@link #incrementalSync()}) — appends a server-side
 *       {@code updated >= "..."} clause to the JQL, per the RFC's guidance that
 *       Jira's JQL filtering should be used instead of Notion's client-side
 *       last-edited-time filter.</li>
 * </ul>
 *
 * <p>Issue {@code fields} (summary, description, status, issuelinks, etc.) come
 * directly from the search response — no separate {@code getIssue} call is needed,
 * since the search request already asks for the fields this connector needs.
 * {@code changelog} and {@code comments} are fetched per-issue, since neither is
 * returned by the search endpoint.
 */
public class JiraWorkspaceSource {

    private static final Logger LOG = Logger.getLogger(JiraWorkspaceSource.class.getName());

    /** Jira's REST API timestamp format, e.g. "2026-01-01T10:00:00.000+0000". */
    private static final DateTimeFormatter JIRA_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    /** JQL date literal format (instance-local, no offset): {@code "yyyy-MM-dd HH:mm"}. */
    private static final DateTimeFormatter JQL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JiraClient client;
    private final JiraIssueMapper mapper;
    private final JiraSyncState syncState;
    private final JiraAdfToMarkdown adfToMarkdown;
    private final SynthesisConfig config;

    public JiraWorkspaceSource(JiraClient client, JiraIssueMapper mapper, JiraSyncState syncState,
                                JiraAdfToMarkdown adfToMarkdown, SynthesisConfig config) {
        this.client = client;
        this.mapper = mapper;
        this.syncState = syncState;
        this.adfToMarkdown = adfToMarkdown;
        this.config = config;
    }

    /**
     * Factory method that constructs a JiraWorkspaceSource from configuration and database.
     */
    public static JiraWorkspaceSource fromConfig(SynthesisConfig config, SynthesisDatabase db) {
        JiraClient client = JiraClient.fromConfig(config.getJira());
        JiraIssueMapper mapper = new JiraIssueMapper();
        JiraSyncState syncState = new JiraSyncState(db);
        JiraAdfToMarkdown adfToMarkdown = new JiraAdfToMarkdown();
        return new JiraWorkspaceSource(client, mapper, syncState, adfToMarkdown, config);
    }

    /**
     * Performs a full sync: searches all issues matching the configured JQL scope,
     * fetches changelog/comments, converts to Markdown, and saves to the database.
     *
     * @return list of synced Jira issues for downstream indexing
     */
    public List<JiraIssue> sync() throws IOException, InterruptedException {
        JiraConfig jiraConfig = config.getJira();
        String workspaceName = config.getWorkspace().getName();
        String jql = buildBaseJql(jiraConfig);

        LOG.info("Syncing Jira issues: " + jql);
        List<JsonNode> rawIssues = client.searchIssues(jql, jiraConfig.getMaxIssuesPerSync());

        return processIssues(rawIssues, workspaceName, jql);
    }

    /**
     * Performs an incremental sync: only fetches issues updated since the last sync
     * time, via a server-side {@code updated >= "..."} JQL clause. Falls back to a
     * full sync if no previous sync exists.
     *
     * @return list of changed Jira issues for downstream indexing
     */
    public List<JiraIssue> incrementalSync() throws IOException, InterruptedException {
        JiraConfig jiraConfig = config.getJira();
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

        String baseJql = buildBaseJql(jiraConfig);
        String jql = withUpdatedSince(baseJql, lastSyncOpt.get());

        LOG.info("Incremental Jira sync: " + jql);
        List<JsonNode> rawIssues = client.searchIssues(jql, jiraConfig.getMaxIssuesPerSync());

        if (rawIssues.isEmpty()) {
            LOG.info("No Jira issues changed since last sync");
            return List.of();
        }

        return processIssues(rawIssues, workspaceName, jql);
    }

    // -----------------------------------------------------------------------
    // JQL construction
    // -----------------------------------------------------------------------

    /**
     * Builds the base JQL for a full sync: the explicit {@code jqlFilter} if set,
     * else a {@code project in (...)} clause from {@code projectKeys}, else
     * "all accessible issues". Always ends in {@code ORDER BY updated} for
     * deterministic, updated-ascending pagination.
     */
    String buildBaseJql(JiraConfig jiraConfig) {
        String jqlFilter = jiraConfig.getJqlFilter();
        if (jqlFilter != null && !jqlFilter.isBlank()) {
            return ensureOrderBy(jqlFilter.trim());
        }

        List<String> projectKeys = jiraConfig.getProjectKeys();
        if (projectKeys != null && !projectKeys.isEmpty()) {
            StringBuilder keys = new StringBuilder();
            for (int i = 0; i < projectKeys.size(); i++) {
                if (i > 0) keys.append(", ");
                keys.append("\"").append(projectKeys.get(i)).append("\"");
            }
            return "project in (" + keys + ") ORDER BY updated";
        }

        return "ORDER BY updated";
    }

    private String ensureOrderBy(String jql) {
        return containsIgnoreCase(jql, "ORDER BY") ? jql : jql + " ORDER BY updated";
    }

    /**
     * Inserts a server-side {@code updated >= "..."} clause into the given base JQL,
     * placed before the trailing {@code ORDER BY} clause.
     */
    String withUpdatedSince(String baseJql, Instant since) {
        String clause = "updated >= \"" + JQL_DATE.format(since.atZone(java.time.ZoneOffset.UTC)) + "\"";

        int idx = indexOfIgnoreCase(baseJql, "ORDER BY");
        if (idx < 0) {
            return clause + " ORDER BY updated";
        }

        String beforeOrderBy = baseJql.substring(0, idx).trim();
        String orderByPart = baseJql.substring(idx);

        if (beforeOrderBy.isEmpty()) {
            return clause + " " + orderByPart;
        }
        return "(" + beforeOrderBy + ") AND " + clause + " " + orderByPart;
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return indexOfIgnoreCase(haystack, needle) >= 0;
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toUpperCase(Locale.ROOT).indexOf(needle.toUpperCase(Locale.ROOT));
    }

    // -----------------------------------------------------------------------
    // Issue processing
    // -----------------------------------------------------------------------

    /** Pairs a mapped issue with its extracted issue links, for persistence. */
    private record ProcessedIssue(JiraIssue issue, List<JiraIssueLink> issueLinks) {}

    private List<JiraIssue> processIssues(List<JsonNode> rawIssues, String workspaceName, String jql) {
        String baseUrl = config.getJira().getBaseUrl();
        List<ProcessedIssue> processed = new ArrayList<>();

        for (JsonNode rawIssue : rawIssues) {
            try {
                processed.add(processOneIssue(rawIssue, baseUrl));
            } catch (Exception e) {
                String key = rawIssue.has("key") ? rawIssue.get("key").asText() : "unknown";
                LOG.warning("Failed to process Jira issue " + key + ": " + e.getMessage());
            }
        }

        persist(processed, workspaceName, jql);
        return processed.stream().map(ProcessedIssue::issue).toList();
    }

    private ProcessedIssue processOneIssue(JsonNode rawIssue, String baseUrl) throws IOException, InterruptedException {
        String key = rawIssue.get("key").asText();
        String id = rawIssue.path("id").asText(null);
        JsonNode fields = rawIssue.get("fields");

        String projectKey = fields.path("project").path("key").asText("");
        String summary = fields.path("summary").asText("");
        String status = textOrNull(fields.path("status").path("name"));
        String issueType = textOrNull(fields.path("issuetype").path("name"));
        String assignee = textOrNull(fields.path("assignee").path("displayName"));
        String priority = textOrNull(fields.path("priority").path("name"));
        List<String> labels = toStringList(fields.path("labels"));
        List<String> components = toNameList(fields.path("components"));
        Instant updated = parseJiraTimestamp(textOrNull(fields.path("updated")));

        String descriptionMarkdown = fields.has("description") && !fields.get("description").isNull()
                ? adfToMarkdown.convert(fields.get("description"))
                : null;

        List<JiraIssueLink> issueLinks = extractIssueLinks(fields.path("issuelinks"));

        List<JiraChangelogEntry> changelog = fetchChangelog(key);
        List<JiraComment> comments = fetchComments(key);

        String markdownContent = mapper.buildMarkdown(key, summary, status, issueType, assignee, priority,
                labels, components, descriptionMarkdown, issueLinks, changelog, comments);
        String virtualPath = mapper.buildVirtualPath(projectKey, key);
        String issueUrl = baseUrl != null ? stripTrailingSlash(baseUrl) + "/browse/" + key : null;

        JiraIssue issue = new JiraIssue(id, key, projectKey, status, updated != null ? updated : Instant.now(),
                virtualPath, markdownContent, issueUrl);
        return new ProcessedIssue(issue, issueLinks);
    }

    private List<JiraChangelogEntry> fetchChangelog(String key) {
        try {
            List<JsonNode> histories = client.getChangelog(key);
            List<JiraChangelogEntry> entries = new ArrayList<>();
            for (JsonNode history : histories) {
                Instant created = parseJiraTimestamp(textOrNull(history.path("created")));
                String author = textOrNull(history.path("author").path("displayName"));
                JsonNode items = history.get("items");
                if (items != null && items.isArray()) {
                    for (JsonNode item : items) {
                        entries.add(new JiraChangelogEntry(created, author,
                                textOrNull(item.path("field")),
                                textOrNull(item.path("fromString")),
                                textOrNull(item.path("toString"))));
                    }
                }
            }
            return entries;
        } catch (Exception e) {
            LOG.fine("Failed to fetch changelog for " + key + ": " + e.getMessage());
            return List.of();
        }
    }

    private List<JiraComment> fetchComments(String key) {
        try {
            List<JsonNode> rawComments = client.getComments(key);
            List<JiraComment> comments = new ArrayList<>();
            for (JsonNode comment : rawComments) {
                Instant created = parseJiraTimestamp(textOrNull(comment.path("created")));
                String author = textOrNull(comment.path("author").path("displayName"));
                String bodyMarkdown = comment.has("body") && !comment.get("body").isNull()
                        ? adfToMarkdown.convert(comment.get("body"))
                        : "";
                comments.add(new JiraComment(created, author, bodyMarkdown));
            }
            return comments;
        } catch (Exception e) {
            LOG.fine("Failed to fetch comments for " + key + ": " + e.getMessage());
            return List.of();
        }
    }

    private List<JiraIssueLink> extractIssueLinks(JsonNode issuelinks) {
        if (issuelinks == null || !issuelinks.isArray()) {
            return List.of();
        }
        List<JiraIssueLink> links = new ArrayList<>();
        for (JsonNode link : issuelinks) {
            JsonNode type = link.get("type");
            String name = type != null ? textOrNull(type.path("name")) : null;

            JsonNode target;
            String relationship;
            String direction;
            if (link.has("outwardIssue")) {
                target = link.get("outwardIssue");
                relationship = type != null ? firstNonBlank(textOrNull(type.path("outward")), name) : name;
                direction = "outward";
            } else if (link.has("inwardIssue")) {
                target = link.get("inwardIssue");
                relationship = type != null ? firstNonBlank(textOrNull(type.path("inward")), name) : name;
                direction = "inward";
            } else {
                continue;
            }

            String targetKey = textOrNull(target.path("key"));
            if (targetKey == null) continue;
            String targetSummary = textOrNull(target.path("fields").path("summary"));

            links.add(new JiraIssueLink(relationship != null ? relationship : "relates to",
                    direction, targetKey, targetSummary));
        }
        return links;
    }

    private void persist(List<ProcessedIssue> processed, String workspaceName, String jql) {
        try {
            for (ProcessedIssue entry : processed) {
                JiraIssue issue = entry.issue();
                String contentHash = sha256(issue.markdownContent());
                syncState.recordIssue(workspaceName, issue.key(), issue.id(), issue.projectKey(),
                        extractSummaryLine(issue.markdownContent()), issue.status(), issue.virtualPath(),
                        issue.updated(), contentHash, issue.issueUrl());
                syncState.recordIssueLinks(workspaceName, issue.key(), entry.issueLinks());
            }

            syncState.upsertSyncState(workspaceName, jql, Instant.now(), processed.size(), "ok", null);
        } catch (Exception e) {
            LOG.warning("Failed to persist Jira sync state: " + e.getMessage());
            try {
                syncState.upsertSyncState(workspaceName, jql, Instant.now(), processed.size(), "error", e.getMessage());
            } catch (Exception ignored) {
                // Best effort
            }
        }
    }

    /** Extracts the "KEY: Summary" header line for storage as the recorded summary. */
    private String extractSummaryLine(String markdownContent) {
        if (markdownContent == null) return "";
        int nl = markdownContent.indexOf('\n');
        String firstLine = nl >= 0 ? markdownContent.substring(0, nl) : markdownContent;
        return firstLine.startsWith("# ") ? firstLine.substring(2) : firstLine;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private static List<String> toStringList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode n : arrayNode) {
            values.add(n.asText());
        }
        return values;
    }

    private static List<String> toNameList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode n : arrayNode) {
            String name = textOrNull(n.path("name"));
            if (name != null) values.add(name);
        }
        return values;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Parses a Jira REST API timestamp, e.g. {@code "2026-01-01T10:00:00.000+0000"}.
     * Returns null on null input or parse failure (content is still indexed).
     */
    static Instant parseJiraTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw, JIRA_TIMESTAMP).toInstant();
        } catch (DateTimeParseException e) {
            // Fall back to strict ISO-8601 in case the fixture/instance uses a colon offset.
            try {
                return Instant.parse(raw);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

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
            return "";
        }
    }
}
