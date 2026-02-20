package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.org.RoutingHints.RoutingHint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoutingHintsTest {

    @TempDir
    Path tempDir;

    @Test
    void load_noFile_returnsEmptyList() throws IOException {
        RoutingHints hints = new RoutingHints(tempDir);
        List<RoutingHint> loaded = hints.load();

        assertTrue(loaded.isEmpty(), "Should return empty list when no hints file exists");
    }

    @Test
    void save_and_load_roundtrip() throws IOException {
        RoutingHints hints = new RoutingHints(tempDir);
        Instant now = Instant.parse("2026-02-20T10:00:00Z");

        List<RoutingHint> toSave = List.of(
                new RoutingHint("*mynder*meeting*.pdf", "/home/user/docs/meetings/", now, 3),
                new RoutingHint("*invoice*.pdf", "/home/user/docs/billing/", now, 1)
        );
        hints.save(toSave);

        // Verify file was created
        assertTrue(Files.exists(hints.getHintsFilePath()),
                "Hints file should be created");

        // Reload and verify
        RoutingHints reloaded = new RoutingHints(tempDir);
        List<RoutingHint> loaded = reloaded.load();

        assertEquals(2, loaded.size(), "Should load 2 hints");
        assertEquals("*mynder*meeting*.pdf", loaded.get(0).filenamePattern());
        assertEquals("/home/user/docs/meetings/", loaded.get(0).destinationPath());
        assertEquals(3, loaded.get(0).hitCount());
        assertEquals(now, loaded.get(0).learnedAt());
        assertEquals("*invoice*.pdf", loaded.get(1).filenamePattern());
        assertEquals(1, loaded.get(1).hitCount());
    }

    @Test
    void derivePattern_extractsTokens() {
        String pattern = RoutingHints.derivePattern("mynder-meeting-2026-02-20.pdf");
        assertEquals("*mynder*meeting*.pdf", pattern,
                "Should extract meaningful tokens and skip ISO dates");
    }

    @Test
    void derivePattern_shortFilename_returnsExtension() {
        String pattern = RoutingHints.derivePattern("abc.pdf");
        assertEquals("*.pdf", pattern,
                "Short tokens (< 4 chars) should be filtered out, falling back to *.ext");
    }

    @Test
    void derivePattern_noExtension_returnsWildcard() {
        String pattern = RoutingHints.derivePattern("readme");
        assertEquals("*readme*", pattern,
                "Filenames without extension should still produce a pattern");
    }

    @Test
    void derivePattern_numericTokensFiltered() {
        String pattern = RoutingHints.derivePattern("invoice-Q4-2025.xlsx");
        assertEquals("*invoice*.xlsx", pattern,
                "Pure numeric tokens (2025) and short tokens (Q4) should be filtered");
    }

    @Test
    void matchingHints_glob_matchesFilename() throws IOException {
        RoutingHints hints = new RoutingHints(tempDir);
        Instant now = Instant.now();
        hints.save(List.of(
                new RoutingHint("*meeting*.pdf", "/home/user/meetings/", now, 0),
                new RoutingHint("*invoice*.xlsx", "/home/user/billing/", now, 0)
        ));
        hints.load();

        List<RoutingHint> matches = hints.matchingHints("mynder-meeting.pdf");
        assertEquals(1, matches.size(), "Should match one hint");
        assertEquals("*meeting*.pdf", matches.get(0).filenamePattern());

        List<RoutingHint> noMatch = hints.matchingHints("report.docx");
        assertTrue(noMatch.isEmpty(), "Should not match any hint");
    }

    @Test
    void addOrUpdate_incrementsHitCount() throws IOException {
        RoutingHints hints = new RoutingHints(tempDir);
        Instant now = Instant.now();

        // Add first time
        hints.addOrUpdate(new RoutingHint("*meeting*.pdf", "/home/user/meetings/", now, 0));
        List<RoutingHint> loaded = new RoutingHints(tempDir).load();
        assertEquals(1, loaded.size());
        assertEquals(0, loaded.get(0).hitCount(), "Initial hit count should be 0");

        // Add again with same pattern — should increment
        hints.load();
        hints.addOrUpdate(new RoutingHint("*meeting*.pdf", "/home/user/meetings/", now, 0));
        loaded = new RoutingHints(tempDir).load();
        assertEquals(1, loaded.size(), "Should still have 1 hint");
        assertEquals(1, loaded.get(0).hitCount(), "Hit count should be incremented to 1");
    }

    @Test
    void delete_removesHintByIndex() throws IOException {
        RoutingHints hints = new RoutingHints(tempDir);
        Instant now = Instant.now();
        hints.save(List.of(
                new RoutingHint("*meeting*.pdf", "/meetings/", now, 0),
                new RoutingHint("*invoice*.pdf", "/billing/", now, 0),
                new RoutingHint("*report*.pdf", "/reports/", now, 0)
        ));
        hints.load();

        // Delete index 2 (1-based)
        hints.delete(2);
        List<RoutingHint> remaining = new RoutingHints(tempDir).load();
        assertEquals(2, remaining.size(), "Should have 2 hints after deletion");
        assertEquals("*meeting*.pdf", remaining.get(0).filenamePattern());
        assertEquals("*report*.pdf", remaining.get(1).filenamePattern());
    }

    @Test
    void delete_invalidIndex_throwsException() throws IOException {
        RoutingHints hints = new RoutingHints(tempDir);
        hints.save(List.of(
                new RoutingHint("*meeting*.pdf", "/meetings/", Instant.now(), 0)
        ));
        hints.load();

        assertThrows(IndexOutOfBoundsException.class, () -> hints.delete(0),
                "Index 0 should be invalid (1-based)");
        assertThrows(IndexOutOfBoundsException.class, () -> hints.delete(5),
                "Index 5 should be invalid when only 1 hint exists");
    }
}
