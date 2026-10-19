package io.exoreaction.synthesis.confluence;

import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.index.DocumentFields;
import io.exoreaction.synthesis.index.FileIndexer;
import org.apache.lucene.document.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Confluence integration points used by ScanCommand:
 * configuration gating and virtual file indexing. Mirrors {@code ScanCommandNotionTest}.
 *
 * <p>Also covers the generalized {@link FileIndexer#indexVirtualFile(String, String, long, String)}
 * overload added so a single method can serve both the Notion and Confluence sources without
 * hardcoding "notion" into a shared virtual-indexing helper.
 */
class ScanCommandConfluenceTest {

    // -----------------------------------------------------------------------
    // 1. Confluence config gating — disabled by default
    // -----------------------------------------------------------------------

    @Test
    void confluenceConfig_disabledByDefault() {
        SynthesisConfig config = new SynthesisConfig();
        assertFalse(config.getConfluence().isEnabled(),
                "Confluence should be disabled by default");
    }

    // -----------------------------------------------------------------------
    // 2. Confluence config gating — enabled when configured
    // -----------------------------------------------------------------------

    @Test
    void confluenceConfig_enabledWhenSet() {
        SynthesisConfig config = new SynthesisConfig();
        config.getConfluence().setEnabled(true);
        config.getConfluence().setBaseUrl("https://example.atlassian.net/wiki");
        config.getConfluence().setEmail("a@b.com");
        config.getConfluence().setApiToken("tok");
        config.getConfluence().setSpaceKeys(java.util.List.of("ENG"));

        assertTrue(config.getConfluence().isEnabled());
        assertEquals("https://example.atlassian.net/wiki", config.getConfluence().getBaseUrl());
        assertEquals(java.util.List.of("ENG"), config.getConfluence().getSpaceKeys());
    }

    // -----------------------------------------------------------------------
    // 3. indexVirtualFile(source) — uses the given source for prefix and field
    // -----------------------------------------------------------------------

    @Test
    void indexVirtualFile_withConfluenceSource_createsCorrectDocument() {
        FileIndexer indexer = new FileIndexer();
        long lastModifiedMs = Instant.now().toEpochMilli();

        Document doc = indexer.indexVirtualFile(
                "ENG/Architecture.md",
                "# Architecture\n\nThis is the architecture page.",
                lastModifiedMs,
                "confluence");

        assertEquals("confluence://ENG/Architecture.md", doc.get(DocumentFields.PATH));
        assertEquals("confluence://ENG/Architecture.md", doc.get(DocumentFields.RELATIVE_PATH));
        assertEquals("Architecture.md", doc.get(DocumentFields.FILENAME));
        assertEquals(".md", doc.get(DocumentFields.EXTENSION));
        assertEquals("MARKDOWN", doc.get(DocumentFields.FILE_TYPE));
        assertEquals("confluence", doc.get(DocumentFields.SOURCE));
        assertNotNull(doc.get(DocumentFields.SUMMARY));
        assertTrue(doc.get(DocumentFields.SUMMARY).contains("Architecture"));
    }

    // -----------------------------------------------------------------------
    // 4. indexVirtualFile(3-arg) — still defaults to "notion" (backward compatible)
    // -----------------------------------------------------------------------

    @Test
    void indexVirtualFile_threeArgOverload_defaultsToNotionSource() {
        FileIndexer indexer = new FileIndexer();

        Document doc = indexer.indexVirtualFile("Notes.md", "# Notes", Instant.now().toEpochMilli());

        assertEquals("notion://Notes.md", doc.get(DocumentFields.PATH));
        assertEquals("notion", doc.get(DocumentFields.SOURCE));
    }

    // -----------------------------------------------------------------------
    // 5. Confluence watcher skip gating
    // -----------------------------------------------------------------------

    @Test
    void scanCommand_skipsConfluence_whenDisabled() {
        SynthesisConfig config = new SynthesisConfig();

        boolean shouldSync = config.getConfluence().isEnabled();
        assertFalse(shouldSync, "Should not sync Confluence when disabled");
    }

    // -----------------------------------------------------------------------
    // 6. ConfluenceWorkspaceSource.fromConfig — throws on missing credentials
    // -----------------------------------------------------------------------

    @Test
    void fromConfig_missingCredentials_throwsIllegalState() {
        SynthesisConfig config = new SynthesisConfig();
        config.getConfluence().setEnabled(true);

        if (System.getenv("CONFLUENCE_BASE_URL") == null
                && System.getenv("CONFLUENCE_EMAIL") == null
                && System.getenv("CONFLUENCE_API_TOKEN") == null) {
            assertThrows(IllegalStateException.class,
                    () -> ConfluenceWorkspaceSource.fromConfig(config, null));
        }
    }
}
