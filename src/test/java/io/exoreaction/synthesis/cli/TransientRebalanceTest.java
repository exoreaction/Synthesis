package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.org.DirectoryIdentity;
import io.exoreaction.synthesis.org.DirectoryIdentityParser;
import io.exoreaction.synthesis.org.ScopeLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for issue #203: rebalance scope expansion to transient directories.
 *
 * <p>Verifies that the maintain rebalance phase moves media files from
 * transient directories (like {@code marketing/}) to matching permanent
 * directories (like {@code products/Synthesis/media/}) using subject-based routing.
 */
class TransientRebalanceTest {

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

    // ---- Core behavior: transient dir media file moves to matching permanent dir ----

    @Test
    void rebalanceTransient_movesMediaFile_toMatchingPermanentDir() throws IOException {
        // Create transient marketing/ directory
        createDir("marketing", new DirectoryIdentity(
                List.of("marketing"), List.of("md", "pdf", "png", "mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of()
        ));

        // Create permanent products/Synthesis/media/ with aliases
        createDir("products/Synthesis/media", new DirectoryIdentity(
                List.of("media", "video"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.9, null, "test", "",
                List.of(), List.of("synthesis", "knowledge-infrastructure"), false, List.of()
        ));

        // Place synthesis-knowledge-infrastructure-demo.mp4 in marketing/
        // Tokens: [synthesis, knowledge, infrastructure, demo]
        // Dir tokens include: [products, synthesis, media, knowledge, infrastructure]
        // Match: 3/4 tokens * 0.9 = 0.675... need to ensure score >= 0.7
        // Actually let's use a simpler filename: synthesis-media-demo.mp4
        // Tokens: [synthesis, media, demo]
        // Dir tokens: [products, synthesis, media, knowledge, infrastructure]
        // Match: 2/3 * 0.9 = 0.6 -- still under 0.7
        // Use filename with MORE matching tokens:
        // synthesis-infrastructure-knowledge.mp4 = [synthesis, infrastructure, knowledge]
        // Dir tokens: [products, synthesis, media, knowledge, infrastructure]
        // Match: 3/3 * 0.9 = 0.9 -- above 0.7!
        Path sourceFile = workspace.resolve("marketing/synthesis-infrastructure-knowledge.mp4");
        Files.writeString(sourceFile, "video-data");

        MaintainCommand cmd = new MaintainCommand();
        int moved = cmd.rebalanceTransient(workspace);

        assertEquals(1, moved, "Should move 1 media file from transient to permanent dir");
        assertFalse(Files.exists(sourceFile),
                "Source file should no longer exist in marketing/");
        assertTrue(Files.exists(workspace.resolve(
                "products/Synthesis/media/synthesis-infrastructure-knowledge.mp4")),
                "File should be in products/Synthesis/media/");
    }

    // ---- No move when score is below threshold ----

    @Test
    void rebalanceTransient_doesNotMove_whenScoreBelowThreshold() throws IOException {
        // Create transient marketing/
        createDir("marketing", new DirectoryIdentity(
                List.of("marketing"), List.of("md", "pdf", "png", "mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of()
        ));

        // Create permanent dir with no matching aliases
        createDir("products/xorcery/media", new DirectoryIdentity(
                List.of("media"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.9, null, "test", "",
                List.of(), List.of("xorcery"), false, List.of()
        ));

        // random-talk.mp4 has tokens [random, talk] — no match to xorcery
        Path sourceFile = workspace.resolve("marketing/random-talk.mp4");
        Files.writeString(sourceFile, "video-data");

        MaintainCommand cmd = new MaintainCommand();
        int moved = cmd.rebalanceTransient(workspace);

        assertEquals(0, moved, "Should not move file with no matching directory");
        assertTrue(Files.exists(sourceFile), "Source file should still exist in marketing/");
    }

    // ---- Non-transient directories are skipped ----

    @Test
    void rebalanceTransient_skipsNonTransientDirs() throws IOException {
        // Create NON-transient articles/ directory
        createDir("articles", new DirectoryIdentity(
                List.of("article"), List.of("md", "pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "",
                List.of(), List.of(), false, List.of()
        ));

        // Create a matching permanent dir
        createDir("products/Synthesis/media", new DirectoryIdentity(
                List.of("media"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.9, null, "test", "",
                List.of(), List.of("synthesis"), false, List.of()
        ));

        // Even if a media file is in articles/, it should NOT be rebalanced
        // because articles/ is NOT transient
        Path sourceFile = workspace.resolve("articles/synthesis-demo.mp4");
        Files.writeString(sourceFile, "video-data");

        MaintainCommand cmd = new MaintainCommand();
        int moved = cmd.rebalanceTransient(workspace);

        assertEquals(0, moved,
                "Should not move files from non-transient directories");
        assertTrue(Files.exists(sourceFile),
                "File should remain in articles/");
    }

    // ---- Non-media files in transient dirs are skipped ----

    @Test
    void rebalanceTransient_skipsNonMediaFiles() throws IOException {
        // Create transient marketing/
        createDir("marketing", new DirectoryIdentity(
                List.of("marketing"), List.of("md", "pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of()
        ));

        // Create matching permanent dir
        createDir("products/Synthesis/docs", new DirectoryIdentity(
                List.of("documentation"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.9, null, "test", "",
                List.of(), List.of("synthesis"), false, List.of()
        ));

        // .md file should NOT be moved (only media files)
        Path mdFile = workspace.resolve("marketing/synthesis-notes.md");
        Files.writeString(mdFile, "some notes");

        MaintainCommand cmd = new MaintainCommand();
        int moved = cmd.rebalanceTransient(workspace);

        assertEquals(0, moved, "Should not move non-media files");
        assertTrue(Files.exists(mdFile), "MD file should remain in marketing/");
    }

    // ---- Empty transient dir produces zero moves ----

    @Test
    void rebalanceTransient_handlesEmptyTransientDir() throws IOException {
        // Create transient staging/ with no files
        createDir("staging", new DirectoryIdentity(
                List.of("staging"), List.of("*"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of()
        ));

        MaintainCommand cmd = new MaintainCommand();
        int moved = cmd.rebalanceTransient(workspace);

        assertEquals(0, moved, "Empty transient dir should produce zero moves");
    }

    // ---- No transient dirs in workspace ----

    @Test
    void rebalanceTransient_handlesNoTransientDirs() throws IOException {
        // Create only non-transient dirs
        createDir("docs", new DirectoryIdentity(
                List.of("documentation"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "",
                List.of(), List.of(), false, List.of()
        ));

        MaintainCommand cmd = new MaintainCommand();
        int moved = cmd.rebalanceTransient(workspace);

        assertEquals(0, moved, "No transient dirs should produce zero moves");
    }

    // ---- Multiple files in transient dir: some move, some don't ----

    @Test
    void rebalanceTransient_movesOnlyMatchingFiles() throws IOException {
        // Create transient incoming/
        createDir("incoming", new DirectoryIdentity(
                List.of("incoming"), List.of("*"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of()
        ));

        // Create permanent products/Aurora/media/ with alias "aurora"
        createDir("products/Aurora/media", new DirectoryIdentity(
                List.of("media", "video"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.9, null, "test", "",
                List.of(), List.of("aurora", "temporal", "analytics"), false, List.of()
        ));

        // aurora-temporal-analytics.mp4 has tokens [aurora, temporal, analytics]
        // Dir tokens: [products, aurora, media, temporal, analytics]
        // Match: 3/3 * 0.9 = 0.9 -> MOVES
        Path matching = workspace.resolve("incoming/aurora-temporal-analytics.mp4");
        Files.writeString(matching, "video-data");

        // random-podcast.mp3 has tokens [random, podcast] — no match
        Path nonMatching = workspace.resolve("incoming/random-podcast.mp3");
        Files.writeString(nonMatching, "audio-data");

        MaintainCommand cmd = new MaintainCommand();
        int moved = cmd.rebalanceTransient(workspace);

        assertEquals(1, moved, "Should move only the matching file");
        assertFalse(Files.exists(matching), "Matching file should be moved");
        assertTrue(Files.exists(nonMatching), "Non-matching file should remain");
        assertTrue(Files.exists(workspace.resolve(
                "products/Aurora/media/aurora-temporal-analytics.mp4")),
                "File should arrive in Aurora/media/");
    }
}
