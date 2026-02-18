package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReportDocument record — construction, isArchived(), briefDescription(), formatSize.
 */
class ReportDocumentTest {

    @Test
    void construction_storesAllFields() {
        Instant now = Instant.now();
        Path path = Path.of("/workspace/eXOReaction/PIPELINE-STATUS.md");
        ReportDocument doc = new ReportDocument(
                path, "eXOReaction/PIPELINE-STATUS.md", "pipeline",
                "content here", now, 1024L);

        assertEquals(path, doc.path());
        assertEquals("eXOReaction/PIPELINE-STATUS.md", doc.relativePath());
        assertEquals("pipeline", doc.category());
        assertEquals("content here", doc.content());
        assertEquals(now, doc.lastModified());
        assertEquals(1024L, doc.sizeBytes());
    }

    // --- isArchived ---

    @Test
    void isArchived_normalPath_returnsFalse() {
        ReportDocument doc = docWithPath("/workspace/eXOReaction/PIPELINE-STATUS.md");
        assertFalse(doc.isArchived());
    }

    @Test
    void isArchived_archivePath_returnsTrue() {
        ReportDocument doc = docWithPath("/workspace/eXOReaction/archive/old-pipeline.md");
        assertTrue(doc.isArchived(), "Path containing /archive/ should be archived");
    }

    @Test
    void isArchived_archivedPath_returnsTrue() {
        ReportDocument doc = docWithPath("/workspace/docs/archived/old-doc.md");
        assertTrue(doc.isArchived(), "Path containing /archived/ should be archived");
    }

    @Test
    void isArchived_legacyPath_returnsTrue() {
        ReportDocument doc = docWithPath("/workspace/legacy/old-file.md");
        assertTrue(doc.isArchived(), "Path containing /legacy/ should be archived");
    }

    @Test
    void isArchived_historicalPath_returnsTrue() {
        ReportDocument doc = docWithPath("/workspace/historical/q1-report.md");
        assertTrue(doc.isArchived(), "Path containing /historical/ should be archived");
    }

    @Test
    void isArchived_oldPath_returnsTrue() {
        ReportDocument doc = docWithPath("/workspace/old/decisions.md");
        assertTrue(doc.isArchived(), "Path containing /old/ should be archived");
    }

    @Test
    void isArchived_caseInsensitive() {
        ReportDocument doc = docWithPath("/workspace/ARCHIVE/FILE.md");
        assertTrue(doc.isArchived(), "isArchived check should be case-insensitive");
    }

    // --- briefDescription ---

    @Test
    void briefDescription_containsCategory() {
        ReportDocument doc = new ReportDocument(
                Path.of("/ws/pipeline.md"), "pipeline.md", "pipeline",
                "content", Instant.now(), 512L);
        assertTrue(doc.briefDescription().contains("pipeline"), "Brief description should include category");
    }

    @Test
    void briefDescription_containsRelativePath() {
        ReportDocument doc = new ReportDocument(
                Path.of("/ws/pipeline.md"), "pipeline.md", "pipeline",
                "content", Instant.now(), 512L);
        assertTrue(doc.briefDescription().contains("pipeline.md"), "Brief description should include relative path");
    }

    @Test
    void briefDescription_containsSizeInfo() {
        ReportDocument doc = new ReportDocument(
                Path.of("/ws/file.md"), "file.md", "activity",
                "content", Instant.now(), 2048L);
        String desc = doc.briefDescription();
        assertFalse(desc.isBlank());
        assertTrue(desc.contains("KB") || desc.contains("B") || desc.contains("MB"),
                "Brief description should include size units");
    }

    // --- size formatting ---

    @ParameterizedTest
    @CsvSource({
        "512,        B",
        "1023,       B",
        "1024,       KB",
        "102400,     KB",
        "1048576,    MB",
        "5242880,    MB"
    })
    void sizeBytes_formattedCorrectlyInBriefDescription(long sizeBytes, String expectedUnit) {
        ReportDocument doc = new ReportDocument(
                Path.of("/ws/file.md"), "file.md", "pipeline",
                "content", Instant.now(), sizeBytes);
        String desc = doc.briefDescription();
        assertTrue(desc.contains(expectedUnit),
                "Size " + sizeBytes + " bytes should format as " + expectedUnit);
    }

    // --- equality ---

    @Test
    void equality_sameFields_areEqual() {
        Instant now = Instant.now();
        Path path = Path.of("/ws/doc.md");
        ReportDocument a = new ReportDocument(path, "doc.md", "pipeline", "content", now, 100L);
        ReportDocument b = new ReportDocument(path, "doc.md", "pipeline", "content", now, 100L);
        assertEquals(a, b);
    }

    @Test
    void equality_differentContent_notEqual() {
        Instant now = Instant.now();
        Path path = Path.of("/ws/doc.md");
        ReportDocument a = new ReportDocument(path, "doc.md", "pipeline", "content A", now, 100L);
        ReportDocument b = new ReportDocument(path, "doc.md", "pipeline", "content B", now, 100L);
        assertNotEquals(a, b);
    }

    // --- helpers ---

    private static ReportDocument docWithPath(String absolutePath) {
        return new ReportDocument(
                Path.of(absolutePath),
                absolutePath.replace("/workspace/", ""),
                "pipeline",
                "content",
                Instant.now(),
                1024L
        );
    }
}
