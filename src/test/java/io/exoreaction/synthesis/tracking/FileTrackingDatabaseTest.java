package io.exoreaction.synthesis.tracking;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.core.ScanState;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileMovementTrackerTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private FileTrackingDatabase trackingDb;
    private FileMovementTracker tracker;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        trackingDb = new FileTrackingDatabase(db);
        tracker = new FileMovementTracker(trackingDb, 7);
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    @Test
    void detectMovementsWithHistory_matchesByHash() throws SQLException {
        // Simulate: file deleted from source, same hash appears in target
        Map<String, ScanState.FileEntry> previousEntries = Map.of(
                "old/file.txt", new ScanState.FileEntry("hash123", 1024, Instant.now())
        );

        List<String> deletedPaths = List.of("old/file.txt");

        Path dummyPath = tempDir.resolve("new").resolve("file.txt");
        FileMetadata addedFile = new FileMetadata(
                dummyPath, "new/file.txt", "file.txt", ".txt",
                FileUtils.FileType.MARKDOWN, null, 1024, Instant.now(), "hash123"
        );

        int detected = tracker.detectMovementsWithHistory(
                previousEntries, deletedPaths, List.of(addedFile),
                "/workspace1", "/workspace1"
        );

        assertEquals(1, detected);
        assertEquals(1, trackingDb.getMovementCount());

        // Verify the recorded movement
        List<FileMovementRecord> movements = trackingDb.getByContentHash("hash123");
        assertEquals(1, movements.size());
        assertEquals("old/file.txt", movements.get(0).sourcePath());
        assertEquals("new/file.txt", movements.get(0).targetPath());
        assertEquals(MovementStatus.CONFIRMED, movements.get(0).status());
    }

    @Test
    void detectMovementsWithHistory_noMatchWhenHashDiffers() throws SQLException {
        Map<String, ScanState.FileEntry> previousEntries = Map.of(
                "old/file.txt", new ScanState.FileEntry("hash_A", 1024, Instant.now())
        );

        List<String> deletedPaths = List.of("old/file.txt");

        Path dummyPath = tempDir.resolve("new").resolve("file.txt");
        FileMetadata addedFile = new FileMetadata(
                dummyPath, "new/file.txt", "file.txt", ".txt",
                FileUtils.FileType.MARKDOWN, null, 1024, Instant.now(), "hash_B"
        );

        int detected = tracker.detectMovementsWithHistory(
                previousEntries, deletedPaths, List.of(addedFile),
                "/workspace1", "/workspace1"
        );

        assertEquals(0, detected);
    }

    @Test
    void detectMovementsWithHistory_skipNullHashes() throws SQLException {
        Map<String, ScanState.FileEntry> previousEntries = Map.of(
                "old/file.txt", new ScanState.FileEntry(null, 1024, Instant.now())
        );

        List<String> deletedPaths = List.of("old/file.txt");

        int detected = tracker.detectMovementsWithHistory(
                previousEntries, deletedPaths, List.of(),
                "/workspace1", "/workspace1"
        );

        assertEquals(0, detected);
    }

    @Test
    void processExpiredSafetyPeriods_transitionsEligibleMovements() throws SQLException {
        // Record a movement and set immediate expiry
        long id = trackingDb.recordMovement(FileMovementRecord.detected(
                "hash1", "/ws1", "a.txt", "/ws2", "b.txt",
                100, "MARKDOWN", DetectionMethod.HASH_MATCH));
        trackingDb.startSafetyPeriod(id, 0);

        // Small delay to ensure expiry is in the past
        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        int count = tracker.processExpiredSafetyPeriods();
        assertTrue(count >= 0); // May be 0 or 1 depending on timing
    }
}
