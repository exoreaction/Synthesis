package io.exoreaction.synthesis.jira;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Maps Jira issues to virtual filesystem paths and a Markdown document shape
 * for Synthesis indexing — the Jira equivalent of {@code NotionPageMapper}.
 *
 * <p><b>Why this differs from {@code NotionPageMapper}:</b> Notion/Confluence pages
 * are organized in a parent/child page tree with freeform titles, so the mapper has
 * to walk ancestor chains and resolve filename collisions between pages that happen
 * to share a title. Jira issues have no such tree (they have project/epic/subtask
 * relationships instead, which are represented as {@code issuelinks}/{@code parent},
 * not a page hierarchy) and every issue already has a globally unique, stable
 * identifier — the issue key (e.g. {@code PROJ-123}). So the virtual path here is
 * simply {@code <projectKey>/<issueKey>.md}: deterministic, collision-free by
 * construction, and no collision-resolution pass is needed (contrast
 * {@code NotionPageMapper#resolveCollisions}).
 *
 * <p><b>Document shape (RFC #542 open question #1):</b> each issue becomes one
 * Markdown file containing: a header with key/summary, a metadata line (status,
 * type, assignee, priority, labels, components), the description (converted from
 * ADF), an "Issue Links" section, a "Changelog" section (full history, not
 * summarized), and a "Comments" section (full thread, not summarized). Changelog
 * and comments are inlined in full rather than excerpted/summarized — this is the
 * connector's distinguishing value per the RFC (temporal/causal provenance), so
 * truncating it here would defeat the point; if this later proves too large for
 * practical indexing, truncation is a cheap follow-up, not a redesign.
 */
public class JiraIssueMapper {

    /** Maximum filename length (before .md extension), mirrors NotionPageMapper. */
    private static final int MAX_FILENAME_LENGTH = 200;

    private static final Pattern UNSAFE_CHARS = Pattern.compile("[/\\\\:*?\"<>|]");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1f\\x7f]");

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    /**
     * A single field transition from an issue's changelog.
     *
     * @param created    when the transition happened
     * @param author     who made the change (display name)
     * @param field      the field that changed, e.g. {@code "status"}
     * @param fromValue  the human-readable previous value (may be null); named {@code fromValue}
     *                   rather than {@code fromString} to mirror the Jira API's own
     *                   {@code fromString} field name as closely as Java allows
     * @param toValue    the human-readable new value (may be null); named {@code toValue}
     *                   because a record component literally named {@code toString} is
     *                   illegal (collides with {@link Object#toString()})
     */
    public record JiraChangelogEntry(
            Instant created, String author, String field, String fromValue, String toValue) {}

    /**
     * A typed, structured relationship to another issue (from {@code issuelinks}).
     *
     * @param relationship  the human-readable relationship, e.g. {@code "blocks"} or
     *                      {@code "is blocked by"}
     * @param direction     {@code "outward"} or {@code "inward"} (matches the Jira API's
     *                      {@code outwardIssue}/{@code inwardIssue} distinction)
     * @param targetKey     the linked issue's key
     * @param targetSummary the linked issue's summary (may be null if not expanded)
     */
    public record JiraIssueLink(String relationship, String direction, String targetKey, String targetSummary) {}

    /**
     * A single comment on an issue.
     *
     * @param created      when the comment was posted
     * @param author       the comment author's display name
     * @param bodyMarkdown the comment body, converted from ADF to Markdown
     */
    public record JiraComment(Instant created, String author, String bodyMarkdown) {}

    /**
     * A fully-mapped Jira issue, ready for indexing.
     *
     * @param id              the Jira issue id (numeric, e.g. "10002")
     * @param key             the Jira issue key (e.g. "PROJ-123")
     * @param projectKey      the project key (e.g. "PROJ")
     * @param status          the issue status name at sync time (may be null)
     * @param updated         when the issue was last updated in Jira
     * @param virtualPath     the resolved virtual filesystem path
     * @param markdownContent the full rendered Markdown document for this issue
     * @param issueUrl        the browsable Jira URL for this issue
     */
    public record JiraIssue(
            String id,
            String key,
            String projectKey,
            String status,
            Instant updated,
            String virtualPath,
            String markdownContent,
            String issueUrl
    ) {}

    /**
     * Builds the virtual filesystem path for an issue: {@code <projectKey>/<issueKey>.md}.
     *
     * <p>Unlike {@code NotionPageMapper#buildVirtualPath}, this requires no ancestor
     * walk and no collision resolution — the issue key is already a unique identifier.
     *
     * @param projectKey the Jira project key
     * @param issueKey   the Jira issue key
     * @return the virtual path, e.g. {@code "PROJ/PROJ-123.md"}
     */
    public String buildVirtualPath(String projectKey, String issueKey) {
        String dir = sanitizeSegment(projectKey, "project");
        String file = sanitizeSegment(issueKey, "issue");
        return dir + "/" + file + ".md";
    }

    /**
     * Renders the full Markdown document for an issue: metadata header, description,
     * issue links, changelog, and comments.
     *
     * @param key              the issue key
     * @param summary          the issue summary
     * @param status           the issue status name (may be null)
     * @param issueType        the issue type name (may be null)
     * @param assignee         the assignee display name (may be null)
     * @param priority         the priority name (may be null)
     * @param labels           issue labels (may be empty)
     * @param components       issue components (may be empty)
     * @param descriptionMarkdown the description, already converted from ADF to Markdown
     * @param issueLinks       structured issue links (may be empty)
     * @param changelog        full changelog history, oldest first (may be empty)
     * @param comments         full comment thread, oldest first (may be empty)
     * @return the rendered Markdown document
     */
    public String buildMarkdown(String key, String summary, String status, String issueType,
                                 String assignee, String priority, List<String> labels,
                                 List<String> components, String descriptionMarkdown,
                                 List<JiraIssueLink> issueLinks, List<JiraChangelogEntry> changelog,
                                 List<JiraComment> comments) {
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(key).append(": ").append(summary == null ? "" : summary).append("\n\n");

        sb.append("**Status:** ").append(orDash(status));
        sb.append("  **Type:** ").append(orDash(issueType));
        sb.append("  **Priority:** ").append(orDash(priority)).append("\n");
        sb.append("**Assignee:** ").append(orDash(assignee));
        sb.append("  **Labels:** ").append(joinOrDash(labels));
        sb.append("  **Components:** ").append(joinOrDash(components)).append("\n\n");

        if (descriptionMarkdown != null && !descriptionMarkdown.isBlank()) {
            sb.append("## Description\n\n").append(descriptionMarkdown.strip()).append("\n\n");
        }

        if (issueLinks != null && !issueLinks.isEmpty()) {
            sb.append("## Issue Links\n\n");
            for (JiraIssueLink link : issueLinks) {
                sb.append("- ").append(link.relationship()).append(" ").append(link.targetKey());
                if (link.targetSummary() != null && !link.targetSummary().isBlank()) {
                    sb.append(": ").append(link.targetSummary());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (changelog != null && !changelog.isEmpty()) {
            sb.append("## Changelog\n\n");
            for (JiraChangelogEntry entry : changelog) {
                sb.append("- ").append(formatTimestamp(entry.created()))
                        .append(" — ").append(orDash(entry.author()))
                        .append(" changed **").append(entry.field()).append("**: ")
                        .append(orNone(entry.fromValue())).append(" → ").append(orNone(entry.toValue()))
                        .append("\n");
            }
            sb.append("\n");
        }

        if (comments != null && !comments.isEmpty()) {
            sb.append("## Comments\n\n");
            for (JiraComment comment : comments) {
                sb.append("### ").append(orDash(comment.author()))
                        .append(" — ").append(formatTimestamp(comment.created())).append("\n\n");
                sb.append(comment.bodyMarkdown() == null ? "" : comment.bodyMarkdown().strip()).append("\n\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    private String formatTimestamp(Instant instant) {
        return instant == null ? "unknown" : TIMESTAMP_FORMAT.format(instant);
    }

    private String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String orNone(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private String joinOrDash(List<String> values) {
        if (values == null || values.isEmpty()) return "—";
        return String.join(", ", values);
    }

    /**
     * Sanitizes a path segment (project key or issue key) for safe filesystem use.
     * Falls back to a generic placeholder if sanitization empties the input.
     */
    private String sanitizeSegment(String raw, String kind) {
        if (raw == null || raw.isBlank()) {
            return "unknown-" + kind;
        }
        String safe = UNSAFE_CHARS.matcher(raw).replaceAll("");
        safe = CONTROL_CHARS.matcher(safe).replaceAll("").trim();
        if (safe.isEmpty()) {
            return "unknown-" + kind;
        }
        if (safe.length() > MAX_FILENAME_LENGTH) {
            safe = safe.substring(0, MAX_FILENAME_LENGTH);
        }
        return safe;
    }
}
