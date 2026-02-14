package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ScanState persistence and change detection.
 */
class ScanStateTest {

    @TempDir
    Path tempDir;

    @Test
    void testSaveAndLoad() throws IOException {
        // Create a scan state
        ScanState state = new ScanState();
        Path savePath = tempDir.resolve("scan-state.json");

        // Save it
        state.save(savePath);

        // Load it back
        ScanState loaded = ScanState.load(savePath);
        assertNotNull(loaded);
        assertNotNull(loaded.getLastScanTime());
    }

    @Test
    void testFromScanResult() throws IOException {
        // Create some file metadata
        Path testFile = tempDir.resolve("test.md");
        Files.writeString(testFile, "# Test");

        FileMetadata fm = FileMetadata.of(testFile, tempDir, 6,
                Instant.now(), "abc123");

        ScanResult result = new ScanResult(
                List.of(fm), Instant.now(), Duration.ofMillis(100), tempDir.toString());

        ScanState state = ScanState.fromScanResult(result);
        assertEquals(1, state.getFileCount());
        assertTrue(state.getEntries().containsKey("test.md"));

        // Save and reload
        Path savePath = tempDir.resolve("state.json");
        state.save(savePath);

        ScanState loaded = ScanState.load(savePath);
        assertEquals(1, loaded.getFileCount());
        assertTrue(loaded.getEntries().containsKey("test.md"));
        assertEquals("abc123", loaded.getEntries().get("test.md").hash());
    }

    @Test
    void testDetectsNewFiles() throws IOException {
        // Create initial state with one file
        Path file1 = tempDir.resolve("file1.md");
        Files.writeString(file1, "content1");
        FileMetadata fm1 = FileMetadata.of(file1, tempDir, 8, Instant.now(), "hash1");
        ScanResult initial = new ScanResult(
                List.of(fm1), Instant.now(), Duration.ofMillis(10), tempDir.toString());
        ScanState state = ScanState.fromScanResult(initial);

        // New scan with two files
        Path file2 = tempDir.resolve("file2.md");
        Files.writeString(file2, "content2");
        FileMetadata fm2 = FileMetadata.of(file2, tempDir, 8, Instant.now(), "hash2");
        ScanResult freshScan = new ScanResult(
                List.of(fm1, fm2), Instant.now(), Duration.ofMillis(10), tempDir.toString());

        ScanState.ChangeSet changes = state.computeChanges(freshScan);
        assertEquals(1, changes.added().size());
        assertEquals("file2.md", changes.added().get(0).relativePath());
        assertTrue(changes.modified().isEmpty());
        assertTrue(changes.deleted().isEmpty());
    }

    @Test
    void testDetectsModifiedFiles() throws IOException {
        Path file1 = tempDir.resolve("file1.md");
        Files.writeString(file1, "original");
        FileMetadata fm1 = FileMetadata.of(file1, tempDir, 8, Instant.now(), "hash1");
        ScanResult initial = new ScanResult(
                List.of(fm1), Instant.now(), Duration.ofMillis(10), tempDir.toString());
        ScanState state = ScanState.fromScanResult(initial);

        // Same file but different hash
        FileMetadata fm1Modified = FileMetadata.of(file1, tempDir, 12, Instant.now(), "hash2");
        ScanResult freshScan = new ScanResult(
                List.of(fm1Modified), Instant.now(), Duration.ofMillis(10), tempDir.toString());

        ScanState.ChangeSet changes = state.computeChanges(freshScan);
        assertTrue(changes.added().isEmpty());
        assertEquals(1, changes.modified().size());
        assertTrue(changes.deleted().isEmpty());
    }

    @Test
    void testDetectsDeletedFiles() throws IOException {
        Path file1 = tempDir.resolve("file1.md");
        Path file2 = tempDir.resolve("file2.md");
        Files.writeString(file1, "content1");
        Files.writeString(file2, "content2");

        FileMetadata fm1 = FileMetadata.of(file1, tempDir, 8, Instant.now(), "hash1");
        FileMetadata fm2 = FileMetadata.of(file2, tempDir, 8, Instant.now(), "hash2");
        ScanResult initial = new ScanResult(
                List.of(fm1, fm2), Instant.now(), Duration.ofMillis(10), tempDir.toString());
        ScanState state = ScanState.fromScanResult(initial);

        // New scan with only first file
        ScanResult freshScan = new ScanResult(
                List.of(fm1), Instant.now(), Duration.ofMillis(10), tempDir.toString());

        ScanState.ChangeSet changes = state.computeChanges(freshScan);
        assertTrue(changes.added().isEmpty());
        assertTrue(changes.modified().isEmpty());
        assertEquals(1, changes.deleted().size());
        assertEquals("file2.md", changes.deleted().get(0));
    }

    @Test
    void testNoChanges() throws IOException {
        Path file1 = tempDir.resolve("file1.md");
        Files.writeString(file1, "content1");
        Instant modTime = Instant.now();
        FileMetadata fm1 = FileMetadata.of(file1, tempDir, 8, modTime, "hash1");

        ScanResult initial = new ScanResult(
                List.of(fm1), Instant.now(), Duration.ofMillis(10), tempDir.toString());
        ScanState state = ScanState.fromScanResult(initial);

        // Same scan
        ScanResult freshScan = new ScanResult(
                List.of(fm1), Instant.now(), Duration.ofMillis(10), tempDir.toString());

        ScanState.ChangeSet changes = state.computeChanges(freshScan);
        assertFalse(changes.hasChanges());
        assertEquals(0, changes.totalChanges());
    }

    @Test
    void testLoadNonexistent() throws IOException {
        ScanState state = ScanState.load(tempDir.resolve("nonexistent.json"));
        assertNotNull(state);
        assertEquals(0, state.getFileCount());
    }

    @Test
    void testExistsCheck() throws IOException {
        Path statePath = tempDir.resolve("state.json");
        assertFalse(ScanState.exists(statePath));

        new ScanState().save(statePath);
        assertTrue(ScanState.exists(statePath));
    }

    @Test
    void testSpecialCharactersInPaths() throws IOException {
        Path subDir = tempDir.resolve("sub dir");
        Files.createDirectories(subDir);
        Path file = subDir.resolve("file with spaces.md");
        Files.writeString(file, "content");

        FileMetadata fm = FileMetadata.of(file, tempDir, 7, Instant.now(), "hash");
        ScanResult result = new ScanResult(
                List.of(fm), Instant.now(), Duration.ofMillis(10), tempDir.toString());

        ScanState state = ScanState.fromScanResult(result);
        Path savePath = tempDir.resolve("state.json");
        state.save(savePath);

        ScanState loaded = ScanState.load(savePath);
        assertEquals(1, loaded.getFileCount());
        // The path should be preserved even with spaces
        assertTrue(loaded.getEntries().keySet().stream().anyMatch(k -> k.contains("spaces")));
    }
}
