package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.DirectoryIdentity;
import io.exoreaction.synthesis.org.DirectoryIdentityParser;
import io.exoreaction.synthesis.org.ForwardingPointer;
import io.exoreaction.synthesis.org.ScopeLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for issue #204: forwarding pointers recorded in {@code .synthesis.md}
 * when files are moved during transient rebalance.
 */
class ForwardingPointerTest {

    @TempDir
    Path workspace;

    private final DirectoryIdentityParser parser = new DirectoryIdentityParser();

    /**
     * Creates a directory with a .synthesis.md identity file.
     */
    private Path createDir(String relativePath, DirectoryIdentity identity) throws IOException {
        Path dir = workspace.resolve(relativePath);
        Files.createDirectories(dir);
        parser.write(dir.resolve(".synthesis.md"), identity);
        return dir;
    }

    // ---- Core: forwarding pointer is written after rebalance move ----

    @Test
    void rebalanceTransient_writesForwardingPointer_afterMove() throws IOException {
        // Create transient incoming/ directory
        createDir("incoming", new DirectoryIdentity(
                List.of("incoming"), List.of("*"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of()
        ));

        // Create permanent products/Aurora/media/ with aliases
        createDir("products/Aurora/media", new DirectoryIdentity(
                List.of("media", "video"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.9, null, "test", "",
                List.of(), List.of("aurora", "temporal", "analytics"), false, List.of()
        ));

        // Place file that will match: aurora-temporal-analytics.mp4
        Path sourceFile = workspace.resolve("incoming/aurora-temporal-analytics.mp4");
        Files.writeString(sourceFile, "video-data");

        MaintainCommand cmd = new MaintainCommand();
        int moved = cmd.rebalanceTransient(workspace);

        assertEquals(1, moved, "Should move 1 file");

        // Read the source directory's .synthesis.md and check for forwarding pointer
        DirectoryIdentity updatedIdentity = parser.parse(
                workspace.resolve("incoming/.synthesis.md"));

        assertFalse(updatedIdentity.movedFiles().isEmpty(),
                "Source directory should have forwarding pointers after rebalance");
        assertEquals(1, updatedIdentity.movedFiles().size(),
                "Should have exactly 1 forwarding pointer");

        ForwardingPointer pointer = updatedIdentity.movedFiles().get(0);
        assertEquals("aurora-temporal-analytics.mp4", pointer.fileName(),
                "Pointer should record the moved file name");
        assertTrue(pointer.movedTo().contains("Aurora"),
                "Pointer should record destination path, got: " + pointer.movedTo());
        assertNotNull(pointer.movedAt(),
                "Pointer should record move timestamp");
        assertEquals("rebalance", pointer.movedBy(),
                "Pointer should be attributed to 'rebalance'");
        assertTrue(pointer.reason().startsWith("score "),
                "Pointer reason should contain routing score, got: " + pointer.reason());
    }

    // ---- Multiple moves accumulate forwarding pointers ----

    @Test
    void rebalanceTransient_accumulatesForwardingPointers() throws IOException {
        // Create transient incoming/ directory
        createDir("incoming", new DirectoryIdentity(
                List.of("incoming"), List.of("*"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of()
        ));

        // Create two permanent destination directories
        createDir("products/Aurora/media", new DirectoryIdentity(
                List.of("media", "video"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.9, null, "test", "",
                List.of(), List.of("aurora", "temporal", "analytics"), false, List.of()
        ));

        createDir("products/Synthesis/media", new DirectoryIdentity(
                List.of("media", "video"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.9, null, "test", "",
                List.of(), List.of("synthesis", "knowledge", "infrastructure"), false, List.of()
        ));

        // Two files that will each route to different destinations
        Files.writeString(workspace.resolve("incoming/aurora-temporal-analytics.mp4"), "v1");
        Files.writeString(workspace.resolve("incoming/synthesis-knowledge-infrastructure.mp4"), "v2");

        MaintainCommand cmd = new MaintainCommand();
        int moved = cmd.rebalanceTransient(workspace);

        assertEquals(2, moved, "Should move 2 files");

        // Check forwarding pointers
        DirectoryIdentity updatedIdentity = parser.parse(
                workspace.resolve("incoming/.synthesis.md"));

        assertEquals(2, updatedIdentity.movedFiles().size(),
                "Should have 2 forwarding pointers");

        // Both file names should be present
        List<String> movedFileNames = updatedIdentity.movedFiles().stream()
                .map(ForwardingPointer::fileName)
                .toList();
        assertTrue(movedFileNames.contains("aurora-temporal-analytics.mp4"),
                "Should have pointer for aurora file");
        assertTrue(movedFileNames.contains("synthesis-knowledge-infrastructure.mp4"),
                "Should have pointer for synthesis file");
    }

    // ---- Forwarding pointer preserves existing identity fields ----

    @Test
    void forwardingPointer_preservesExistingIdentityFields() throws IOException {
        // Create transient incoming/ with existing aliases and rejectsTypes
        createDir("incoming", new DirectoryIdentity(
                List.of("incoming"), List.of("*"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "Incoming files landing zone",
                List.of(), List.of("upload-zone"), true, List.of()
        ));

        // Create matching permanent dir
        createDir("products/Synthesis/media", new DirectoryIdentity(
                List.of("media"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.9, null, "test", "",
                List.of(), List.of("synthesis", "knowledge", "infrastructure"), false, List.of()
        ));

        // Place matching file
        Files.writeString(workspace.resolve("incoming/synthesis-knowledge-infrastructure.mp4"), "data");

        MaintainCommand cmd = new MaintainCommand();
        cmd.rebalanceTransient(workspace);

        // Verify existing fields are preserved
        DirectoryIdentity updatedIdentity = parser.parse(
                workspace.resolve("incoming/.synthesis.md"));

        assertTrue(updatedIdentity.transient_(),
                "transient flag should be preserved");
        assertEquals(List.of("upload-zone"), updatedIdentity.aliases(),
                "aliases should be preserved");
        assertEquals(List.of("incoming"), updatedIdentity.acceptsTypes(),
                "acceptsTypes should be preserved");
        assertEquals(1, updatedIdentity.movedFiles().size(),
                "Should have 1 forwarding pointer");
    }

    // ---- recordForwardingPointer unit test ----

    @Test
    void recordForwardingPointer_appendsToExistingPointers() throws IOException {
        // Create a directory with an existing forwarding pointer
        java.time.Instant existingTime = java.time.Instant.parse("2026-02-20T10:00:00Z");
        ForwardingPointer existingPointer = new ForwardingPointer(
                "old-file.mp4", "products/OldDir/media", existingTime, "rebalance", "score 0.85");

        Path dir = workspace.resolve("staging");
        Files.createDirectories(dir);
        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("staging"), List.of("*"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of(existingPointer)
        );
        parser.write(dir.resolve(".synthesis.md"), identity);

        // Record a new forwarding pointer
        MaintainCommand cmd = new MaintainCommand();
        cmd.recordForwardingPointer(
                dir, "new-file.mp4", "products/NewDir/media", 0.92, parser);

        // Verify both pointers exist
        DirectoryIdentity updated = parser.parse(dir.resolve(".synthesis.md"));
        assertEquals(2, updated.movedFiles().size(),
                "Should have 2 forwarding pointers (1 existing + 1 new)");

        // Verify ordering: existing first, then new
        assertEquals("old-file.mp4", updated.movedFiles().get(0).fileName());
        assertEquals("new-file.mp4", updated.movedFiles().get(1).fileName());
        assertEquals("products/NewDir/media", updated.movedFiles().get(1).movedTo());
        assertEquals("rebalance", updated.movedFiles().get(1).movedBy());
        assertTrue(updated.movedFiles().get(1).reason().contains("0.92"),
                "Reason should contain the score");
    }

    // ---- No forwarding pointer when move fails ----

    @Test
    void noForwardingPointer_whenNoFilesMove() throws IOException {
        // Create transient incoming/ with no matching files
        createDir("incoming", new DirectoryIdentity(
                List.of("incoming"), List.of("*"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of()
        ));

        // Place non-matching file
        Files.writeString(workspace.resolve("incoming/random-stuff.mp4"), "data");

        MaintainCommand cmd = new MaintainCommand();
        int moved = cmd.rebalanceTransient(workspace);

        assertEquals(0, moved, "No files should move");

        // Verify no forwarding pointers
        DirectoryIdentity identity = parser.parse(
                workspace.resolve("incoming/.synthesis.md"));
        assertTrue(identity.movedFiles().isEmpty(),
                "Should have no forwarding pointers when no files move");
    }
}
