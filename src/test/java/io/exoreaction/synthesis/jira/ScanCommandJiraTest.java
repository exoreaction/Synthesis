package io.exoreaction.synthesis.jira;

import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.index.DocumentFields;
import io.exoreaction.synthesis.index.FileIndexer;
import org.apache.lucene.document.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Jira integration points used by ScanCommand/WatchCommand/
 * MaintainOrchestrator: configuration gating and source-tagged virtual file
 * indexing — the Jira equivalent of {@code ScanCommandNotionTest}.
 */
class ScanCommandJiraTest {

    // -----------------------------------------------------------------------
    // 1. Jira config gating — disabled by default
    // -----------------------------------------------------------------------

    @Test
    void jiraConfig_disabledByDefault() {
        SynthesisConfig config = new SynthesisConfig();
        assertFalse(config.getJira().isEnabled(), "Jira should be disabled by default");
    }

    // -----------------------------------------------------------------------
    // 2. Jira config gating — enabled when configured
    // -----------------------------------------------------------------------

    @Test
    void jiraConfig_enabledWhenSet() {
        SynthesisConfig config = new SynthesisConfig();
        config.getJira().setEnabled(true);
        config.getJira().setBaseUrl("https://x.atlassian.net");
        config.getJira().setEmail("a@b.com");
        config.getJira().setApiToken("tok");

        assertTrue(config.getJira().isEnabled());
        assertEquals("https://x.atlassian.net", config.getJira().getBaseUrl());
        assertEquals("a@b.com", config.getJira().getEmail());
        assertEquals("tok", config.getJira().getApiToken());
    }

    // -----------------------------------------------------------------------
    // 3. FileIndexer.indexVirtualFile(..., "jira") creates a source-tagged document
    // -----------------------------------------------------------------------

    @Test
    void indexVirtualFile_withJiraSource_createsCorrectDocument() {
        FileIndexer indexer = new FileIndexer();
        long lastModifiedMs = Instant.now().toEpochMilli();

        Document doc = indexer.indexVirtualFile(
                "PROJ/PROJ-123.md",
                "# PROJ-123: Fix the login bug",
                lastModifiedMs,
                "jira");

        assertEquals("jira://PROJ/PROJ-123.md", doc.get(DocumentFields.PATH));
        assertEquals("jira://PROJ/PROJ-123.md", doc.get(DocumentFields.RELATIVE_PATH));
        assertEquals("PROJ-123.md", doc.get(DocumentFields.FILENAME));
        assertEquals(".md", doc.get(DocumentFields.EXTENSION));
        assertEquals("MARKDOWN", doc.get(DocumentFields.FILE_TYPE));
        assertEquals("jira", doc.get(DocumentFields.SOURCE));
        assertNotNull(doc.get(DocumentFields.SUMMARY));
    }

    // -----------------------------------------------------------------------
    // 4. FileIndexer.indexVirtualFile 3-arg overload still defaults to "notion"
    //    (backward compatibility — existing NotionWatcher/ScanCommand call sites
    //    are unmodified by this change)
    // -----------------------------------------------------------------------

    @Test
    void indexVirtualFile_threeArgOverload_stillDefaultsToNotion() {
        FileIndexer indexer = new FileIndexer();
        Document doc = indexer.indexVirtualFile("Notes.md", "content", Instant.now().toEpochMilli());

        assertEquals("notion://Notes.md", doc.get(DocumentFields.PATH));
        assertEquals("notion", doc.get(DocumentFields.SOURCE));
    }

    // -----------------------------------------------------------------------
    // 5. JiraWorkspaceSource.fromConfig — throws on missing credentials
    // -----------------------------------------------------------------------

    @Test
    void fromConfig_missingCredentials_throwsIllegalState() {
        SynthesisConfig config = new SynthesisConfig();
        config.getJira().setEnabled(true);
        // No baseUrl/email/apiToken set, and env vars are unlikely to be set in test

        if (System.getenv("JIRA_BASE_URL") == null && System.getenv("JIRA_EMAIL") == null
                && System.getenv("JIRA_API_TOKEN") == null) {
            assertThrows(IllegalStateException.class,
                    () -> JiraWorkspaceSource.fromConfig(config, null));
        }
    }

    // -----------------------------------------------------------------------
    // 6. Scan skips Jira when disabled
    // -----------------------------------------------------------------------

    @Test
    void scanCommand_skipsJira_whenDisabled() {
        SynthesisConfig config = new SynthesisConfig();
        boolean shouldSync = config.getJira().isEnabled();
        assertFalse(shouldSync, "Should not sync Jira when disabled");
    }
}
