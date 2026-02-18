package io.exoreaction.synthesis.staging;

import io.exoreaction.synthesis.config.SynthesisConfig.StagingConfig;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for StagingManager — ingest, routeTo, promote, and expiry.
 */
class StagingManagerTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private StagingManager manager;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        StagingConfig config = new StagingConfig();
        config.setEnabled(true);
        config.setRetentionDays(30);
        config.setCleanupExpired(false);
        manager = new StagingManager(db, config, tempDir);
    }

    // -------------------------------------------------------------------------
    // ingest
    // -------------------------------------------------------------------------

    @Test
    void ingest_registersFileAsPending() throws SQLException {
        StagingManager.StagedFile file = manager.ingest("report.pdf", "Inbox", 1024, "PDF", "abc123");

        assertEquals("report.pdf", file.relativePath());
        assertEquals("Inbox", file.subWorkspace());
        assertEquals(1024, file.fileSize());
        assertEquals("PDF", file.fileType());
        assertEquals("abc123", file.contentHash());
        assertEquals("pending", file.status());
        assertNotNull(file.ingestedAt());
        assertNotNull(file.expiresAt());
        assertNull(file.promotedAt());
        assertNull(file.promotedTo());
    }

    @Test
    void ingest_isPending_returnsTrue() throws SQLException {
        StagingManager.StagedFile file = manager.ingest("file.pdf", "Inbox", 100, "PDF", null);
        assertTrue(file.isPending());
        assertFalse(file.isPromoted());
        assertFalse(file.isExpired());
    }

    @Test
    void ingest_expiresAfterRetentionDays() throws SQLException {
        StagingManager.StagedFile file = manager.ingest("file.pdf", "Inbox", 100, "PDF", null);
        assertTrue(file.expiresAt().isAfter(file.ingestedAt()));
    }

    @Test
    void ingest_replaceExisting_updatesRecord() throws SQLException {
        manager.ingest("dup.pdf", "Inbox", 100, "PDF", "hash1");
        manager.ingest("dup.pdf", "Inbox", 200, "PDF", "hash2");

        List<StagingManager.StagedFile> list = manager.list(null);
        long count = list.stream().filter(f -> f.relativePath().equals("dup.pdf")).count();
        assertEquals(1, count, "Duplicate ingest should replace, not duplicate");
    }

    // -------------------------------------------------------------------------
    // routeTo — file operations
    // -------------------------------------------------------------------------

    @Test
    void routeTo_copiesFileToDestination() throws SQLException, IOException {
        Path source = tempDir.resolve("report.pdf");
        Files.writeString(source, "content");
        StagingManager.StagedFile file = manager.ingest("report.pdf", "Inbox", 7, "PDF", null);

        Path dest = tempDir.resolve("dest/report.pdf");
        boolean result = manager.routeTo(file, dest, false);

        assertTrue(result);
        assertTrue(Files.exists(dest), "File should be copied to destination");
        assertEquals("content", Files.readString(dest));
    }

    @Test
    void routeTo_renamesSourceWithProcessedSuffix() throws SQLException, IOException {
        Path source = tempDir.resolve("report.pdf");
        Files.writeString(source, "content");
        StagingManager.StagedFile file = manager.ingest("report.pdf", "Inbox", 7, "PDF", null);

        manager.routeTo(file, tempDir.resolve("dest/report.pdf"), false);

        assertFalse(Files.exists(source), "Original should be renamed");
        assertTrue(Files.exists(tempDir.resolve("report_processed.pdf")), "Should have _processed suffix");
    }

    @Test
    void routeTo_processedSourceRetainsOriginalContent() throws SQLException, IOException {
        Path source = tempDir.resolve("data.pdf");
        Files.writeString(source, "original content");
        StagingManager.StagedFile file = manager.ingest("data.pdf", "Inbox", 16, "PDF", null);

        manager.routeTo(file, tempDir.resolve("dest/data.pdf"), false);

        assertEquals("original content", Files.readString(tempDir.resolve("data_processed.pdf")));
    }

    @Test
    void routeTo_createsDestinationDirectories() throws SQLException, IOException {
        Path source = tempDir.resolve("file.pdf");
        Files.writeString(source, "x");
        StagingManager.StagedFile file = manager.ingest("file.pdf", "Inbox", 1, "PDF", null);

        Path dest = tempDir.resolve("a/b/c/file.pdf");
        manager.routeTo(file, dest, false);

        assertTrue(Files.exists(dest));
    }

    @Test
    void routeTo_returnsFalse_whenSourceMissing() throws SQLException, IOException {
        StagingManager.StagedFile file = manager.ingest("ghost.pdf", "Inbox", 0, "PDF", null);
        // No actual file on disk

        boolean result = manager.routeTo(file, tempDir.resolve("dest/ghost.pdf"), false);

        assertFalse(result);
    }

    // -------------------------------------------------------------------------
    // routeTo — companion handling
    // -------------------------------------------------------------------------

    @Test
    void routeTo_withCopyCompanions_copiesCompanionToDest() throws SQLException, IOException {
        Path source = tempDir.resolve("report.pdf");
        Path companion = tempDir.resolve("report.pdf.synthesis.md");
        Files.writeString(source, "content");
        Files.writeString(companion, "# companion");
        StagingManager.StagedFile file = manager.ingest("report.pdf", "Inbox", 7, "PDF", null);

        Path dest = tempDir.resolve("dest/report.pdf");
        manager.routeTo(file, dest, true);

        assertTrue(Files.exists(Path.of(dest + ".synthesis.md")), "Companion should be at destination");
        assertEquals("# companion", Files.readString(Path.of(dest + ".synthesis.md")));
    }

    @Test
    void routeTo_withCopyCompanions_renamesSourceCompanionToProcessed() throws SQLException, IOException {
        Path source = tempDir.resolve("report.pdf");
        Path companion = tempDir.resolve("report.pdf.synthesis.md");
        Files.writeString(source, "content");
        Files.writeString(companion, "# companion");
        StagingManager.StagedFile file = manager.ingest("report.pdf", "Inbox", 7, "PDF", null);

        manager.routeTo(file, tempDir.resolve("dest/report.pdf"), true);

        assertFalse(Files.exists(companion), "Original companion should be renamed");
        assertTrue(Files.exists(tempDir.resolve("report_processed.pdf.synthesis.md")),
                "Companion should have _processed suffix");
    }

    @Test
    void routeTo_withoutCopyCompanions_leavesCompanionUntouched() throws SQLException, IOException {
        Path source = tempDir.resolve("report.pdf");
        Path companion = tempDir.resolve("report.pdf.synthesis.md");
        Files.writeString(source, "content");
        Files.writeString(companion, "# companion");
        StagingManager.StagedFile file = manager.ingest("report.pdf", "Inbox", 7, "PDF", null);

        manager.routeTo(file, tempDir.resolve("dest/report.pdf"), false);

        assertTrue(Files.exists(companion), "Companion should be untouched when copyCompanions=false");
    }

    @Test
    void routeTo_noCompanionExists_doesNotFail() throws SQLException, IOException {
        Path source = tempDir.resolve("report.pdf");
        Files.writeString(source, "content");
        StagingManager.StagedFile file = manager.ingest("report.pdf", "Inbox", 7, "PDF", null);

        assertDoesNotThrow((org.junit.jupiter.api.function.Executable)
                () -> manager.routeTo(file, tempDir.resolve("dest/report.pdf"), true));
    }

    // -------------------------------------------------------------------------
    // routeTo — DB state
    // -------------------------------------------------------------------------

    @Test
    void routeTo_marksFileAsPromotedInDb() throws SQLException, IOException {
        Path source = tempDir.resolve("report.pdf");
        Files.writeString(source, "content");
        StagingManager.StagedFile file = manager.ingest("report.pdf", "Inbox", 7, "PDF", null);

        manager.routeTo(file, tempDir.resolve("dest/report.pdf"), false);

        List<StagingManager.StagedFile> promoted = manager.list("promoted");
        assertEquals(1, promoted.size());
        assertEquals("report.pdf", promoted.get(0).relativePath());
        assertTrue(promoted.get(0).isPromoted());
    }

    @Test
    void routeTo_recordsDestinationInDb() throws SQLException, IOException {
        Path source = tempDir.resolve("report.pdf");
        Files.writeString(source, "content");
        StagingManager.StagedFile file = manager.ingest("report.pdf", "Inbox", 7, "PDF", null);

        Path dest = tempDir.resolve("dest/report.pdf");
        manager.routeTo(file, dest, false);

        List<StagingManager.StagedFile> promoted = manager.list("promoted");
        assertNotNull(promoted.get(0).promotedTo());
        assertTrue(promoted.get(0).promotedTo().contains(dest.toString()),
                "promotedTo should contain destination path");
    }

    @Test
    void routeTo_setsPromotedAt() throws SQLException, IOException {
        Path source = tempDir.resolve("report.pdf");
        Files.writeString(source, "content");
        StagingManager.StagedFile file = manager.ingest("report.pdf", "Inbox", 7, "PDF", null);

        manager.routeTo(file, tempDir.resolve("dest/report.pdf"), false);

        List<StagingManager.StagedFile> promoted = manager.list("promoted");
        assertNotNull(promoted.get(0).promotedAt());
    }

    // -------------------------------------------------------------------------
    // promote
    // -------------------------------------------------------------------------

    @Test
    void promote_movesFileToTargetPath() throws SQLException, IOException {
        Path source = tempDir.resolve("inbox/file.pdf");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "content");
        StagingManager.StagedFile file = manager.ingest("inbox/file.pdf", "Inbox", 7, "PDF", null);

        manager.promote(file, "Archive", "archive/file.pdf");

        assertFalse(Files.exists(source), "Source should be moved");
        assertTrue(Files.exists(tempDir.resolve("archive/file.pdf")));
    }

    @Test
    void promote_marksFileAsPromoted() throws SQLException, IOException {
        Path source = tempDir.resolve("file.pdf");
        Files.writeString(source, "x");
        StagingManager.StagedFile file = manager.ingest("file.pdf", "Inbox", 1, "PDF", null);

        manager.promote(file, "Archive", "archive/file.pdf");

        List<StagingManager.StagedFile> promoted = manager.list("promoted");
        assertEquals(1, promoted.size());
    }

    @Test
    void promote_returnsFalse_whenSourceMissing() throws SQLException, IOException {
        StagingManager.StagedFile file = manager.ingest("ghost.pdf", "Inbox", 0, "PDF", null);

        boolean result = manager.promote(file, "Archive", "archive/ghost.pdf");

        assertFalse(result);
    }

    // -------------------------------------------------------------------------
    // list
    // -------------------------------------------------------------------------

    @Test
    void list_noFilter_returnsAllFiles() throws SQLException {
        manager.ingest("a.pdf", "Inbox", 1, "PDF", null);
        manager.ingest("b.pdf", "Inbox", 1, "PDF", null);

        assertEquals(2, manager.list(null).size());
    }

    @Test
    void list_statusFilter_returnsOnlyMatchingStatus() throws SQLException, IOException {
        manager.ingest("pending.pdf", "Inbox", 1, "PDF", null);

        Path source = tempDir.resolve("routed.pdf");
        Files.writeString(source, "x");
        StagingManager.StagedFile routed = manager.ingest("routed.pdf", "Inbox", 1, "PDF", null);
        manager.routeTo(routed, tempDir.resolve("dest/routed.pdf"), false);

        List<StagingManager.StagedFile> pending = manager.list("pending");
        assertEquals(1, pending.size());
        assertEquals("pending.pdf", pending.get(0).relativePath());
    }

    // -------------------------------------------------------------------------
    // processExpired
    // -------------------------------------------------------------------------

    @Test
    void processExpired_marksExpiredFiles() throws SQLException {
        // Ingest with -1-day retention so expiresAt is in the past
        StagingConfig shortConfig = new StagingConfig();
        shortConfig.setRetentionDays(-1);
        StagingManager shortManager = new StagingManager(db, shortConfig, tempDir);

        shortManager.ingest("old.pdf", "Inbox", 1, "PDF", null);

        int expired = shortManager.processExpired();
        assertEquals(1, expired);

        List<StagingManager.StagedFile> list = shortManager.list("expired");
        assertEquals(1, list.size());
    }

    @Test
    void processExpired_withCleanupEnabled_deletesFileFromDisk() throws SQLException, IOException {
        StagingConfig cleanupConfig = new StagingConfig();
        cleanupConfig.setRetentionDays(-1);
        cleanupConfig.setCleanupExpired(true);
        StagingManager cleanupManager = new StagingManager(db, cleanupConfig, tempDir);

        Path file = tempDir.resolve("expired.pdf");
        Files.writeString(file, "x");
        cleanupManager.ingest("expired.pdf", "Inbox", 1, "PDF", null);

        cleanupManager.processExpired();

        assertFalse(Files.exists(file), "Expired file should be deleted when cleanupExpired=true");
    }

    @Test
    void processExpired_withCleanupDisabled_keepsFileOnDisk() throws SQLException, IOException {
        StagingConfig noCleanupConfig = new StagingConfig();
        noCleanupConfig.setRetentionDays(-1);
        noCleanupConfig.setCleanupExpired(false);
        StagingManager noCleanupManager = new StagingManager(db, noCleanupConfig, tempDir);

        Path file = tempDir.resolve("keep.pdf");
        Files.writeString(file, "x");
        noCleanupManager.ingest("keep.pdf", "Inbox", 1, "PDF", null);

        noCleanupManager.processExpired();

        assertTrue(Files.exists(file), "File should remain when cleanupExpired=false");
    }
}
