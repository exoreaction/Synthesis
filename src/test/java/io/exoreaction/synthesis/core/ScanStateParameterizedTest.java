package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parameterized tests for ScanState — change detection with
 * multiple files added/modified/deleted, empty states, and mixed scenarios.
 */
class ScanStateParameterizedTest {

    // --- multiple files added ---

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10})
    void computeChanges_multipleFilesAdded_allDetected(int count, @TempDir Path tempDir)
            throws IOException {
        ScanState initial = ScanState.fromScanResult(emptyScan(tempDir));

        List<FileMetadata> files = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Path f = tempDir.resolve("file" + i + ".txt");
            Files.writeString(f, "content" + i);
            files.add(FileMetadata.of(f, tempDir, ("content" + i).length(), Instant.now(), "hash" + i));
        }

        ScanResult newScan = new ScanResult(files, Instant.now(), Duration.ofMillis(10), tempDir.toString());
        ScanState.ChangeSet changes = initial.computeChanges(newScan);

        assertEquals(count, changes.added().size(), "Should detect " + count + " added files");
        assertTrue(changes.modified().isEmpty());
        assertTrue(changes.deleted().isEmpty());
    }

    // --- multiple files deleted ---

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5})
    void computeChanges_multipleFilesDeleted_allDetected(int count, @TempDir Path tempDir)
            throws IOException {
        List<FileMetadata> files = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Path f = tempDir.resolve("file" + i + ".txt");
            Files.writeString(f, "content" + i);
            files.add(FileMetadata.of(f, tempDir, 8, Instant.now(), "hash" + i));
        }

        ScanResult initial = new ScanResult(files, Instant.now(), Duration.ofMillis(10), tempDir.toString());
        ScanState state = ScanState.fromScanResult(initial);

        // Delete all files from the new scan
        ScanResult emptied = emptyScan(tempDir);
        ScanState.ChangeSet changes = state.computeChanges(emptied);

        assertEquals(count, changes.deleted().size(), "Should detect " + count + " deleted files");
        assertTrue(changes.added().isEmpty());
        assertTrue(changes.modified().isEmpty());
    }

    // --- hasChanges / totalChanges ---

    @Test
    void computeChanges_allThreeTypes_hasChanges(@TempDir Path tempDir) throws IOException {
        Path existing = tempDir.resolve("existing.txt");
        Path toDelete = tempDir.resolve("delete.txt");
        Files.writeString(existing, "original");
        Files.writeString(toDelete, "will-delete");

        FileMetadata fmExisting = FileMetadata.of(existing, tempDir, 8, Instant.now(), "hash1");
        FileMetadata fmToDelete = FileMetadata.of(toDelete, tempDir, 11, Instant.now(), "hash2");

        ScanResult initialScan = new ScanResult(
                List.of(fmExisting, fmToDelete), Instant.now(), Duration.ofMillis(10), tempDir.toString());
        ScanState state = ScanState.fromScanResult(initialScan);

        // New scan: existing file modified, toDelete gone, new file added
        FileMetadata fmModified = FileMetadata.of(existing, tempDir, 9, Instant.now(), "hash1_changed");
        Path newFile = tempDir.resolve("new.txt");
        Files.writeString(newFile, "new");
        FileMetadata fmNew = FileMetadata.of(newFile, tempDir, 3, Instant.now(), "hash3");

        ScanResult newScan = new ScanResult(
                List.of(fmModified, fmNew), Instant.now(), Duration.ofMillis(10), tempDir.toString());
        ScanState.ChangeSet changes = state.computeChanges(newScan);

        assertTrue(changes.hasChanges());
        assertEquals(1, changes.added().size());
        assertEquals(1, changes.modified().size());
        assertEquals(1, changes.deleted().size());
        assertEquals(3, changes.totalChanges());
    }

    // --- no changes ---

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5})
    void computeChanges_sameFiles_noChanges(int count, @TempDir Path tempDir)
            throws IOException {
        List<FileMetadata> files = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Path f = tempDir.resolve("file" + i + ".txt");
            Files.writeString(f, "content" + i);
            files.add(FileMetadata.of(f, tempDir, 8, Instant.now(), "stable_hash" + i));
        }

        ScanResult scan = new ScanResult(files, Instant.now(), Duration.ofMillis(10), tempDir.toString());
        ScanState state = ScanState.fromScanResult(scan);

        // Re-scan with exact same metadata
        ScanState.ChangeSet changes = state.computeChanges(scan);
        assertFalse(changes.hasChanges());
        assertEquals(0, changes.totalChanges());
    }

    // --- save/load round-trip with multiple files ---

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10})
    void saveLoad_multipleFiles_preservesCount(int count, @TempDir Path tempDir)
            throws IOException {
        List<FileMetadata> files = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Path f = tempDir.resolve("file" + i + ".txt");
            Files.writeString(f, "content" + i);
            files.add(FileMetadata.of(f, tempDir, 8, Instant.now(), "hash" + i));
        }

        ScanResult scan = new ScanResult(files, Instant.now(), Duration.ofMillis(10), tempDir.toString());
        ScanState state = ScanState.fromScanResult(scan);

        Path savePath = tempDir.resolve("state.json");
        state.save(savePath);
        ScanState loaded = ScanState.load(savePath);

        assertEquals(count, loaded.getFileCount());
    }

    // --- file hash change detected as modified ---

    @ParameterizedTest
    @CsvSource({
        "hash1, hash2",
        "abc,   xyz",
        "sha1,  sha2"
    })
    void computeChanges_hashChanged_detectedAsModified(String oldHash, String newHash,
                                                        @TempDir Path tempDir)
            throws IOException {
        Path f = tempDir.resolve("file.txt");
        Files.writeString(f, "content");

        FileMetadata old = FileMetadata.of(f, tempDir, 7, Instant.now(), oldHash);
        ScanResult initial = new ScanResult(
                List.of(old), Instant.now(), Duration.ofMillis(10), tempDir.toString());
        ScanState state = ScanState.fromScanResult(initial);

        FileMetadata modified = FileMetadata.of(f, tempDir, 7, Instant.now(), newHash);
        ScanResult newScan = new ScanResult(
                List.of(modified), Instant.now(), Duration.ofMillis(10), tempDir.toString());
        ScanState.ChangeSet changes = state.computeChanges(newScan);

        assertEquals(1, changes.modified().size(), "Hash change should be detected as modified");
    }

    // --- empty initial state ---

    @Test
    void computeChanges_emptyInitial_allNewFilesAreAdded(@TempDir Path tempDir)
            throws IOException {
        ScanState empty = ScanState.fromScanResult(emptyScan(tempDir));

        Path f = tempDir.resolve("file.txt");
        Files.writeString(f, "content");
        FileMetadata fm = FileMetadata.of(f, tempDir, 7, Instant.now(), "hash1");
        ScanResult newScan = new ScanResult(
                List.of(fm), Instant.now(), Duration.ofMillis(10), tempDir.toString());

        ScanState.ChangeSet changes = empty.computeChanges(newScan);
        assertEquals(1, changes.added().size());
        assertFalse(changes.hasChanges() == false); // has changes
    }

    // --- helper ---

    private ScanResult emptyScan(Path workspaceRoot) {
        return new ScanResult(List.of(), Instant.now(), Duration.ofMillis(1), workspaceRoot.toString());
    }
}
