package io.exoreaction.synthesis.changelog;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.core.ScanResult;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized tests for SnapshotManager — multiple change scenarios, workspace isolation,
 * significance classification, and snapshot lifecycle.
 */
class SnapshotManagerParameterizedTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private SnapshotManager snapshotManager;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        snapshotManager = new SnapshotManager(db);
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    // --- Multiple additions at once ---

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10})
    void compareSnapshots_multipleAdditions_allDetected(int addedCount) throws SQLException {
        // Snapshot 1: empty
        long snap1 = snapshotManager.takeSnapshotFromScanResult(
                "/ws", "WS", createScanResult(List.of()), "test");

        // Snapshot 2: N files added
        List<FileMetadata> files = new ArrayList<>();
        for (int i = 0; i < addedCount; i++) {
            files.add(createFile("file" + i + ".txt", "hash" + i, 100 + i));
        }
        long snap2 = snapshotManager.takeSnapshotFromScanResult(
                "/ws", "WS", createScanResult(files), "test");

        List<ChangeEvent> changes = snapshotManager.compareSnapshots(snap1, snap2);
        assertEquals(addedCount, changes.size(),
                addedCount + " additions should produce " + addedCount + " ADDED events");
        changes.forEach(c ->
                assertEquals(ChangeEvent.ChangeType.ADDED, c.changeType()));
    }

    // --- Multiple deletions at once ---

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5})
    void compareSnapshots_multipleDeletions_allDetected(int deletedCount) throws SQLException {
        // Snapshot 1: N files
        List<FileMetadata> initialFiles = new ArrayList<>();
        for (int i = 0; i < deletedCount + 1; i++) {  // +1 to always keep one
            initialFiles.add(createFile("file" + i + ".txt", "hash" + i, 100));
        }
        long snap1 = snapshotManager.takeSnapshotFromScanResult(
                "/ws", "WS", createScanResult(initialFiles), "test");

        // Snapshot 2: only the first file remains
        long snap2 = snapshotManager.takeSnapshotFromScanResult(
                "/ws", "WS", createScanResult(List.of(initialFiles.get(0))), "test");

        List<ChangeEvent> changes = snapshotManager.compareSnapshots(snap1, snap2);
        long deletedEvents = changes.stream()
                .filter(c -> c.changeType() == ChangeEvent.ChangeType.DELETED)
                .count();
        assertEquals(deletedCount, deletedEvents,
                deletedCount + " deletions should be detected");
    }

    // --- Mixed changes: add + modify + delete simultaneously ---

    @Test
    void compareSnapshots_mixedChanges_allThreeTypesDetected() throws SQLException {
        FileMetadata unchanged = createFile("unchanged.txt", "hash-u", 100);
        FileMetadata toModify  = createFile("modified.txt", "hash-m1", 200);
        FileMetadata toDelete  = createFile("deleted.txt", "hash-d", 300);

        long snap1 = snapshotManager.takeSnapshotFromScanResult(
                "/ws", "WS",
                createScanResult(List.of(unchanged, toModify, toDelete)), "test");

        FileMetadata modifiedNow = createFile("modified.txt", "hash-m2", 250); // same path, new hash
        FileMetadata added = createFile("added.txt", "hash-a", 150);

        long snap2 = snapshotManager.takeSnapshotFromScanResult(
                "/ws", "WS",
                createScanResult(List.of(unchanged, modifiedNow, added)), "test");

        List<ChangeEvent> changes = snapshotManager.compareSnapshots(snap1, snap2);
        long added_count    = changes.stream().filter(c -> c.changeType() == ChangeEvent.ChangeType.ADDED).count();
        long modified_count = changes.stream().filter(c -> c.changeType() == ChangeEvent.ChangeType.MODIFIED).count();
        long deleted_count  = changes.stream().filter(c -> c.changeType() == ChangeEvent.ChangeType.DELETED).count();

        assertEquals(1, added_count,    "1 file added");
        assertEquals(1, modified_count, "1 file modified");
        assertEquals(1, deleted_count,  "1 file deleted");
        assertEquals(3, changes.size(), "Total 3 changes");
    }

    // --- Workspace isolation ---

    @ParameterizedTest
    @CsvSource({
        "/ws/alpha,  /ws/beta",
        "/workspace, /other-workspace",
        "/a,         /b"
    })
    void getLatestSnapshot_twoWorkspaces_returnsCorrectSnapshot(String ws1Path, String ws2Path)
            throws SQLException {
        snapshotManager.takeSnapshotFromScanResult(
                ws1Path, "WS1",
                createScanResult(List.of(createFile("a.txt", "h1", 100))), "test");
        snapshotManager.takeSnapshotFromScanResult(
                ws2Path, "WS2",
                createScanResult(List.of(
                        createFile("b.txt", "h2", 200),
                        createFile("c.txt", "h3", 300))), "test");

        WorkspaceSnapshot snap1 = snapshotManager.getLatestSnapshot(ws1Path);
        WorkspaceSnapshot snap2 = snapshotManager.getLatestSnapshot(ws2Path);

        assertNotNull(snap1, ws1Path + " should have a snapshot");
        assertNotNull(snap2, ws2Path + " should have a snapshot");
        assertEquals(1, snap1.fileCount(), ws1Path + " should have 1 file");
        assertEquals(2, snap2.fileCount(), ws2Path + " should have 2 files");
    }

    // --- Snapshot count constraints ---

    @ParameterizedTest
    @CsvSource({
        "5, 2, 2",
        "5, 5, 5",
        "5, 10, 5"
    })
    void getSnapshots_limitRespected(int totalSnapshots, int requestedLimit, int expectedCount)
            throws SQLException {
        for (int i = 0; i < totalSnapshots; i++) {
            snapshotManager.takeSnapshotFromScanResult(
                    "/ws", "WS", createScanResult(List.of()), "test");
        }
        List<WorkspaceSnapshot> snapshots = snapshotManager.getSnapshots("/ws", requestedLimit);
        assertEquals(expectedCount, snapshots.size(),
                "Requesting " + requestedLimit + " from " + totalSnapshots + " should return " + expectedCount);
    }

    // --- Change significance reflected in ChangeEvent ---

    @Test
    void compareSnapshots_significanceAssigned_notNull() throws SQLException {
        long snap1 = snapshotManager.takeSnapshotFromScanResult(
                "/ws", "WS", createScanResult(List.of()), "test");
        long snap2 = snapshotManager.takeSnapshotFromScanResult(
                "/ws", "WS",
                createScanResult(List.of(createFile("src/Main.java", "h1", 500))), "test");

        List<ChangeEvent> changes = snapshotManager.compareSnapshots(snap1, snap2);
        assertFalse(changes.isEmpty());
        changes.forEach(c ->
                assertNotNull(c.significance(), "Significance should never be null"));
    }

    // --- helpers ---

    private ScanResult createScanResult(List<FileMetadata> files) {
        return new ScanResult(files, Instant.now(), Duration.ofMillis(10), "/ws");
    }

    private FileMetadata createFile(String relativePath, String hash, long size) {
        return new FileMetadata(
                tempDir.resolve(relativePath), relativePath,
                Path.of(relativePath).getFileName().toString(),
                ".txt", FileUtils.FileType.MARKDOWN, null,
                size, Instant.now(), hash
        );
    }
}
