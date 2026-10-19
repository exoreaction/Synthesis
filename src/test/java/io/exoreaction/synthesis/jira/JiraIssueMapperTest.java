package io.exoreaction.synthesis.jira;

import io.exoreaction.synthesis.jira.JiraIssueMapper.JiraChangelogEntry;
import io.exoreaction.synthesis.jira.JiraIssueMapper.JiraComment;
import io.exoreaction.synthesis.jira.JiraIssueMapper.JiraIssueLink;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JiraIssueMapper} — virtual path construction and Markdown
 * document rendering for Jira issues.
 */
class JiraIssueMapperTest {

    private final JiraIssueMapper mapper = new JiraIssueMapper();

    // -----------------------------------------------------------------------
    // buildVirtualPath
    // -----------------------------------------------------------------------

    @Test
    void buildVirtualPath_simpleKey_returnsProjectSlashKey() {
        String path = mapper.buildVirtualPath("PROJ", "PROJ-123");
        assertEquals("PROJ/PROJ-123.md", path);
    }

    @Test
    void buildVirtualPath_sameKeyTwice_isDeterministic() {
        // Unlike NotionPageMapper, no collision resolution needed: same input -> same output.
        String path1 = mapper.buildVirtualPath("PROJ", "PROJ-1");
        String path2 = mapper.buildVirtualPath("PROJ", "PROJ-1");
        assertEquals(path1, path2);
    }

    @Test
    void buildVirtualPath_blankProjectKey_fallsBackToPlaceholder() {
        String path = mapper.buildVirtualPath("", "PROJ-1");
        assertEquals("unknown-project/PROJ-1.md", path);
    }

    @Test
    void buildVirtualPath_unsafeChars_areStripped() {
        String path = mapper.buildVirtualPath("PR/OJ", "PROJ-1");
        assertEquals("PROJ/PROJ-1.md", path);
    }

    // -----------------------------------------------------------------------
    // buildMarkdown — metadata header
    // -----------------------------------------------------------------------

    @Test
    void buildMarkdown_includesKeyAndSummaryHeader() {
        String md = mapper.buildMarkdown("PROJ-1", "Fix the login bug", "Done", "Bug",
                "Alice", "High", List.of("auth", "urgent"), List.of("Backend"),
                null, List.of(), List.of(), List.of());

        assertTrue(md.startsWith("# PROJ-1: Fix the login bug"));
        assertTrue(md.contains("**Status:** Done"));
        assertTrue(md.contains("**Type:** Bug"));
        assertTrue(md.contains("**Priority:** High"));
        assertTrue(md.contains("**Assignee:** Alice"));
        assertTrue(md.contains("**Labels:** auth, urgent"));
        assertTrue(md.contains("**Components:** Backend"));
    }

    @Test
    void buildMarkdown_missingOptionalFields_rendersDash() {
        String md = mapper.buildMarkdown("PROJ-2", "No metadata", null, null,
                null, null, List.of(), List.of(), null, List.of(), List.of(), List.of());

        assertTrue(md.contains("**Status:** —"));
        assertTrue(md.contains("**Assignee:** —"));
        assertTrue(md.contains("**Labels:** —"));
    }

    // -----------------------------------------------------------------------
    // buildMarkdown — description
    // -----------------------------------------------------------------------

    @Test
    void buildMarkdown_withDescription_rendersDescriptionSection() {
        String md = mapper.buildMarkdown("PROJ-3", "Has description", "Open", "Task",
                "Bob", "Low", List.of(), List.of(), "This is the description body.",
                List.of(), List.of(), List.of());

        assertTrue(md.contains("## Description"));
        assertTrue(md.contains("This is the description body."));
    }

    @Test
    void buildMarkdown_blankDescription_omitsSection() {
        String md = mapper.buildMarkdown("PROJ-4", "No description", "Open", "Task",
                "Bob", "Low", List.of(), List.of(), "   ",
                List.of(), List.of(), List.of());

        assertFalse(md.contains("## Description"));
    }

    // -----------------------------------------------------------------------
    // buildMarkdown — issue links (the Jira-specific structured relationship)
    // -----------------------------------------------------------------------

    @Test
    void buildMarkdown_withIssueLinks_rendersLinksSection() {
        List<JiraIssueLink> links = List.of(
                new JiraIssueLink("blocks", "outward", "PROJ-5", "Update docs"),
                new JiraIssueLink("is blocked by", "inward", "PROJ-6", "Refactor auth service")
        );

        String md = mapper.buildMarkdown("PROJ-1", "Summary", "Open", "Story",
                "Alice", "Medium", List.of(), List.of(), null, links, List.of(), List.of());

        assertTrue(md.contains("## Issue Links"));
        assertTrue(md.contains("- blocks PROJ-5: Update docs"));
        assertTrue(md.contains("- is blocked by PROJ-6: Refactor auth service"));
    }

    @Test
    void buildMarkdown_noIssueLinks_omitsSection() {
        String md = mapper.buildMarkdown("PROJ-1", "Summary", "Open", "Story",
                "Alice", "Medium", List.of(), List.of(), null, List.of(), List.of(), List.of());

        assertFalse(md.contains("## Issue Links"));
    }

    // -----------------------------------------------------------------------
    // buildMarkdown — changelog (temporal/causal value per the RFC)
    // -----------------------------------------------------------------------

    @Test
    void buildMarkdown_withChangelog_rendersFullHistoryNotSummarized() {
        List<JiraChangelogEntry> changelog = List.of(
                new JiraChangelogEntry(Instant.parse("2026-01-01T00:00:00Z"), "Alice",
                        "status", "To Do", "In Progress"),
                new JiraChangelogEntry(Instant.parse("2026-01-02T00:00:00Z"), "Bob",
                        "assignee", null, "Bob")
        );

        String md = mapper.buildMarkdown("PROJ-1", "Summary", "In Progress", "Bug",
                "Bob", "High", List.of(), List.of(), null, List.of(), changelog, List.of());

        assertTrue(md.contains("## Changelog"));
        assertTrue(md.contains("Alice changed **status**: To Do → In Progress"));
        assertTrue(md.contains("Bob changed **assignee**: (none) → Bob"));
    }

    // -----------------------------------------------------------------------
    // buildMarkdown — comments (decision trail / provenance per the RFC)
    // -----------------------------------------------------------------------

    @Test
    void buildMarkdown_withComments_rendersFullThread() {
        List<JiraComment> comments = List.of(
                new JiraComment(Instant.parse("2026-02-01T00:00:00Z"), "Carol", "First comment"),
                new JiraComment(Instant.parse("2026-02-02T00:00:00Z"), "Dave", "Second comment")
        );

        String md = mapper.buildMarkdown("PROJ-1", "Summary", "Open", "Bug",
                "Carol", "Low", List.of(), List.of(), null, List.of(), List.of(), comments);

        assertTrue(md.contains("## Comments"));
        assertTrue(md.contains("### Carol — 2026-02-01 00:00"));
        assertTrue(md.contains("First comment"));
        assertTrue(md.contains("### Dave — 2026-02-02 00:00"));
        assertTrue(md.contains("Second comment"));
    }

    @Test
    void buildMarkdown_fullIssue_allSectionsPresentInOrder() {
        List<JiraIssueLink> links = List.of(new JiraIssueLink("blocks", "outward", "PROJ-9", "Other"));
        List<JiraChangelogEntry> changelog = List.of(
                new JiraChangelogEntry(Instant.parse("2026-01-01T00:00:00Z"), "Alice", "status", "To Do", "Done"));
        List<JiraComment> comments = List.of(
                new JiraComment(Instant.parse("2026-02-01T00:00:00Z"), "Bob", "LGTM"));

        String md = mapper.buildMarkdown("PROJ-1", "Full issue", "Done", "Story",
                "Alice", "High", List.of("a"), List.of("b"), "Description text",
                links, changelog, comments);

        int descIdx = md.indexOf("## Description");
        int linksIdx = md.indexOf("## Issue Links");
        int changelogIdx = md.indexOf("## Changelog");
        int commentsIdx = md.indexOf("## Comments");

        assertTrue(descIdx >= 0 && linksIdx > descIdx && changelogIdx > linksIdx && commentsIdx > changelogIdx,
                "Sections should appear in order: Description, Issue Links, Changelog, Comments. Got:\n" + md);
    }
}
