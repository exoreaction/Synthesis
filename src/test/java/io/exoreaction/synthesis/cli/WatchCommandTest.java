package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.analyzer.AnalyzerRegistry;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.FileIndexer;
import io.exoreaction.synthesis.index.SearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static java.nio.file.StandardWatchEventKinds.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the WatchCommand (Watch Mode).
 */
class WatchCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void registerDirectoriesSkipsExcludedDirs() throws IOException {
        // Create directories
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("node_modules"));
        Files.createDirectories(tempDir.resolve(".git"));
        Files.createDirectories(tempDir.resolve("build"));

        WatchCommand cmd = new WatchCommand();
        WatchService watchService = FileSystems.getDefault().newWatchService();

        // Should not throw
        cmd.registerDirectories(tempDir, watchService, new SynthesisConfig.ScanConfig());

        watchService.close();
    }

    @Test
    void processChangesHandlesFileCreation() throws IOException {
        // Initialize workspace
        WorkspaceManager workspace = new WorkspaceManager(tempDir);
        workspace.init("test", "general");

        SynthesisConfig config = new SynthesisConfig();

        // Create a test file
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "hello world");

        // Process a CREATE event
        Map<Path, WatchEvent.Kind<?>> changes = new LinkedHashMap<>();
        changes.put(testFile, ENTRY_CREATE);

        WatchCommand cmd = new WatchCommand();
        cmd.processChanges(changes, workspace, config, tempDir,
                new AnalyzerRegistry(), new FileIndexer());

        assertEquals(1, cmd.getIndexedCount(), "Should have indexed 1 file");
    }

    @Test
    void processChangesHandlesFileDeletion() throws IOException {
        // Initialize workspace and index a file
        WorkspaceManager workspace = new WorkspaceManager(tempDir);
        workspace.init("test", "general");

        Path testFile = tempDir.resolve("toDelete.txt");
        Files.writeString(testFile, "will be deleted");

        // First, add the file to index
        try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
            FileMetadata metadata = FileMetadata.of(testFile, tempDir,
                    Files.size(testFile), Files.getLastModifiedTime(testFile).toInstant(), null);
            AnalysisResult analysis = AnalysisResult.minimal("Test file", "will be deleted");
            index.addDocument(new FileIndexer().createDocument(metadata, analysis));
            index.commit();
            assertEquals(1, index.documentCount());
        }

        // Delete the actual file
        Files.delete(testFile);

        // Process DELETE event
        Map<Path, WatchEvent.Kind<?>> changes = new LinkedHashMap<>();
        changes.put(testFile, ENTRY_DELETE);

        SynthesisConfig config = new SynthesisConfig();
        WatchCommand cmd = new WatchCommand();
        cmd.processChanges(changes, workspace, config, tempDir,
                new AnalyzerRegistry(), new FileIndexer());

        assertEquals(1, cmd.getIndexedCount(), "Should have processed 1 deletion");
    }

    @Test
    void processChangesHandlesFileModification() throws IOException {
        // Initialize workspace
        WorkspaceManager workspace = new WorkspaceManager(tempDir);
        workspace.init("test", "general");

        Path testFile = tempDir.resolve("modify.txt");
        Files.writeString(testFile, "original content");

        // Index original
        Map<Path, WatchEvent.Kind<?>> create = new LinkedHashMap<>();
        create.put(testFile, ENTRY_CREATE);

        SynthesisConfig config = new SynthesisConfig();
        WatchCommand cmd = new WatchCommand();
        cmd.processChanges(create, workspace, config, tempDir,
                new AnalyzerRegistry(), new FileIndexer());

        // Modify file
        Files.writeString(testFile, "modified content");

        // Process MODIFY event
        Map<Path, WatchEvent.Kind<?>> modify = new LinkedHashMap<>();
        modify.put(testFile, ENTRY_MODIFY);

        cmd.processChanges(modify, workspace, config, tempDir,
                new AnalyzerRegistry(), new FileIndexer());

        assertEquals(2, cmd.getIndexedCount(), "Should have indexed 2 operations (create + modify)");
    }

    @Test
    void processChangesHandlesBatchUpdates() throws IOException {
        // Initialize workspace
        WorkspaceManager workspace = new WorkspaceManager(tempDir);
        workspace.init("test", "general");

        Path file1 = tempDir.resolve("file1.txt");
        Path file2 = tempDir.resolve("file2.txt");
        Path file3 = tempDir.resolve("file3.txt");
        Files.writeString(file1, "content 1");
        Files.writeString(file2, "content 2");
        Files.writeString(file3, "content 3");

        Map<Path, WatchEvent.Kind<?>> changes = new LinkedHashMap<>();
        changes.put(file1, ENTRY_CREATE);
        changes.put(file2, ENTRY_CREATE);
        changes.put(file3, ENTRY_CREATE);

        SynthesisConfig config = new SynthesisConfig();
        WatchCommand cmd = new WatchCommand();
        cmd.processChanges(changes, workspace, config, tempDir,
                new AnalyzerRegistry(), new FileIndexer());

        assertEquals(3, cmd.getIndexedCount(), "Should have indexed all 3 files");
    }

    @Test
    void stopMethodSetsRunningToFalse() {
        WatchCommand cmd = new WatchCommand();
        assertTrue(cmd.isRunning(), "Should be running initially");

        cmd.stop();

        assertFalse(cmd.isRunning(), "Should be stopped after stop()");
    }

    @Test
    void eventCountStartsAtZero() {
        WatchCommand cmd = new WatchCommand();
        assertEquals(0, cmd.getEventCount());
        assertEquals(0, cmd.getIndexedCount());
    }
}
