package io.exoreaction.synthesis.notion;

import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.index.DocumentFields;
import io.exoreaction.synthesis.index.FileIndexer;
import org.apache.lucene.document.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Notion integration points used by ScanCommand:
 * configuration gating and virtual file indexing.
 *
 * <p>These tests verify the building blocks that ScanCommand uses,
 * without instantiating the full Picocli command (which requires
 * a workspace on disk).
 */
class ScanCommandNotionTest {

    // -----------------------------------------------------------------------
    // 1. Notion config gating — disabled by default
    // -----------------------------------------------------------------------

    @Test
    void notionConfig_disabledByDefault() {
        SynthesisConfig config = new SynthesisConfig();
        assertFalse(config.getNotion().isEnabled(),
                "Notion should be disabled by default");
    }

    // -----------------------------------------------------------------------
    // 2. Notion config gating — enabled when configured
    // -----------------------------------------------------------------------

    @Test
    void notionConfig_enabledWhenSet() {
        SynthesisConfig config = new SynthesisConfig();
        config.getNotion().setEnabled(true);
        config.getNotion().setToken("ntn_test");
        config.getNotion().setRootPageId("root-123");

        assertTrue(config.getNotion().isEnabled());
        assertEquals("ntn_test", config.getNotion().getToken());
        assertEquals("root-123", config.getNotion().getRootPageId());
    }

    // -----------------------------------------------------------------------
    // 3. FileIndexer.indexVirtualFile creates correct document
    // -----------------------------------------------------------------------

    @Test
    void indexVirtualFile_createsCorrectDocument() {
        FileIndexer indexer = new FileIndexer();
        long lastModifiedMs = Instant.now().toEpochMilli();

        Document doc = indexer.indexVirtualFile(
                "Engineering/Architecture.md",
                "# Architecture\n\nThis is the architecture page.",
                lastModifiedMs);

        // Verify identity fields
        assertEquals("notion://Engineering/Architecture.md", doc.get(DocumentFields.PATH));
        assertEquals("notion://Engineering/Architecture.md", doc.get(DocumentFields.RELATIVE_PATH));
        assertEquals("Architecture.md", doc.get(DocumentFields.FILENAME));
        assertEquals(".md", doc.get(DocumentFields.EXTENSION));

        // Verify classification
        assertEquals("MARKDOWN", doc.get(DocumentFields.FILE_TYPE));
        assertEquals("notion", doc.get(DocumentFields.SOURCE));

        // Verify summary is populated
        assertNotNull(doc.get(DocumentFields.SUMMARY));
        assertTrue(doc.get(DocumentFields.SUMMARY).contains("Architecture"));

        // Verify metadata
        assertNotNull(doc.get(DocumentFields.SIZE));
        assertNotNull(doc.get(DocumentFields.LAST_MODIFIED));
    }

    // -----------------------------------------------------------------------
    // 4. FileIndexer.indexVirtualFile — empty content
    // -----------------------------------------------------------------------

    @Test
    void indexVirtualFile_emptyContent_stillCreatesDocument() {
        FileIndexer indexer = new FileIndexer();

        Document doc = indexer.indexVirtualFile("Notes.md", "", Instant.now().toEpochMilli());

        assertEquals("notion://Notes.md", doc.get(DocumentFields.PATH));
        assertEquals("Notes.md", doc.get(DocumentFields.FILENAME));
        assertEquals("notion", doc.get(DocumentFields.SOURCE));
        // No summary for empty content
        assertNull(doc.get(DocumentFields.SUMMARY));
    }

    // -----------------------------------------------------------------------
    // 5. FileIndexer.indexVirtualFile — path without directory
    // -----------------------------------------------------------------------

    @Test
    void indexVirtualFile_flatPath_extractsFilename() {
        FileIndexer indexer = new FileIndexer();

        Document doc = indexer.indexVirtualFile("README.md", "# Hello", Instant.now().toEpochMilli());

        assertEquals("notion://README.md", doc.get(DocumentFields.PATH));
        assertEquals("README.md", doc.get(DocumentFields.FILENAME));
    }

    // -----------------------------------------------------------------------
    // 6. NotionWorkspaceSource.fromConfig — throws on missing token
    // -----------------------------------------------------------------------

    @Test
    void fromConfig_missingToken_throwsIllegalState() {
        SynthesisConfig config = new SynthesisConfig();
        config.getNotion().setEnabled(true);
        // No token set, and NOTION_TOKEN env var is likely not set in test

        if (System.getenv("NOTION_TOKEN") == null) {
            assertThrows(IllegalStateException.class,
                    () -> NotionWorkspaceSource.fromConfig(config, null));
        }
    }

    // -----------------------------------------------------------------------
    // 7. Scan skips Notion when disabled
    // -----------------------------------------------------------------------

    @Test
    void scanCommand_skipsNotion_whenDisabled() {
        // Verify the gating condition: when notion.enabled=false,
        // the conditional block in ScanCommand would not execute.
        // We test this by verifying the config's default state.
        SynthesisConfig config = new SynthesisConfig();

        // This is the exact condition from ScanCommand
        boolean shouldSync = config.getNotion().isEnabled();
        assertFalse(shouldSync, "Should not sync Notion when disabled");
    }
}
