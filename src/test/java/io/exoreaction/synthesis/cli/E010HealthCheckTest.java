package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.cli.E010Check.E010Finding;
import io.exoreaction.synthesis.cli.E010Check.E010Level;
import io.exoreaction.synthesis.org.DirectoryIdentity;
import io.exoreaction.synthesis.org.DirectoryIdentityParser;
import io.exoreaction.synthesis.org.ScopeLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for issue #200: E010 health check — media files in transient or hard-reject directories.
 */
class E010HealthCheckTest {

    @TempDir
    Path workspace;

    private final DirectoryIdentityParser parser = new DirectoryIdentityParser();
    private final E010Check checker = new E010Check();

    /**
     * Creates a directory with a .synthesis.md identity file.
     */
    private Path createDir(String relativePath, DirectoryIdentity identity) throws IOException {
        Path dir = workspace.resolve(relativePath);
        Files.createDirectories(dir);
        parser.write(dir.resolve(".synthesis.md"), identity);
        return dir;
    }

    // ---- INFO: media file in transient dir with no product directory ----

    @Test
    void info_mediaFileInTransientDir_noProductDir() throws IOException {
        // Create a transient marketing/ directory
        createDir("marketing", new DirectoryIdentity(
                List.of("marketing"), List.of("md", "pdf", "png", "mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of()
        ));

        // Place an mp4 file in marketing/
        Files.writeString(workspace.resolve("marketing/synthesis-demo.mp4"), "video-data");

        List<E010Finding> findings = checker.check(workspace);

        // Should produce an INFO finding — no product directory to route to
        assertFalse(findings.isEmpty(), "Should have at least one finding");
        E010Finding finding = findings.get(0);
        assertEquals(E010Level.INFO, finding.level(),
                "Media file in transient dir with no destination should be INFO");
        assertTrue(finding.proposedDestination().isEmpty(),
                "No proposed destination when no matching dir exists");
        assertEquals(0.0, finding.proposedScore(),
                "Score should be 0 when no destination found");
        assertTrue(finding.message().contains("transient"),
                "Message should mention transient directory");
    }

    // ---- WARNING: media file in transient dir with matching product directory ----

    @Test
    void warning_mediaFileInTransientDir_withProductDir() throws IOException {
        // Create a transient marketing/ directory
        createDir("marketing", new DirectoryIdentity(
                List.of("marketing"), List.of("md", "pdf", "png", "mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of()
        ));

        // Create products/Synthesis/media/ with alias "synthesis"
        createDir("products/Synthesis/media", new DirectoryIdentity(
                List.of("media", "video"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.9, null, "test", "",
                List.of(), List.of("synthesis"), false, List.of()
        ));

        // Place synthesis-demo.mp4 in marketing/ — should route to products/Synthesis/media/
        Files.writeString(workspace.resolve("marketing/synthesis-demo.mp4"), "video-data");

        List<E010Finding> findings = checker.check(workspace);

        // Find the WARNING finding for the mp4 file
        List<E010Finding> warnings = findings.stream()
                .filter(f -> f.level() == E010Level.WARNING)
                .toList();

        assertFalse(warnings.isEmpty(),
                "Should have WARNING finding for synthesis-demo.mp4 with a matching product dir");
        E010Finding warning = warnings.get(0);
        assertTrue(warning.proposedDestination().isPresent(),
                "Should propose products/Synthesis/media/ as destination");
        assertTrue(warning.proposedDestination().get().toString().contains("Synthesis"),
                "Destination should contain 'Synthesis', got: " + warning.proposedDestination().get());
        assertTrue(warning.proposedScore() >= 0.4,
                "Score should be >= 0.4, got: " + warning.proposedScore());
        assertTrue(warning.message().contains("better home"),
                "Message should mention 'better home'");
    }

    // ---- ERROR: media file in directory with rejectsTypes ----

    @Test
    void error_mediaFileInRejectTypesDir() throws IOException {
        // Create articles/ directory with rejectsTypes=[video, media, audio]
        createDir("articles", new DirectoryIdentity(
                List.of("article", "documentation"), List.of("md", "pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "",
                List.of("video", "media", "audio"), List.of(), false, List.of()
        ));

        // Place an mp4 in articles/ — should be ERROR
        Files.writeString(workspace.resolve("articles/meeting-recording.mp4"), "video-data");

        List<E010Finding> findings = checker.check(workspace);

        List<E010Finding> errors = findings.stream()
                .filter(f -> f.level() == E010Level.ERROR)
                .toList();

        assertFalse(errors.isEmpty(),
                "Should have ERROR finding for mp4 in articles/ with rejectsTypes");
        E010Finding error = errors.get(0);
        assertTrue(error.message().contains("rejectsTypes"),
                "Error message should mention rejectsTypes");
        assertTrue(error.message().contains("meeting-recording.mp4"),
                "Error message should mention the file name");
    }

    // ---- No finding: media file correctly placed in non-transient media dir ----

    @Test
    void noFinding_mediaFileInCorrectNonTransientDir() throws IOException {
        // Create products/Synthesis/media/ — non-transient, no rejectsTypes
        createDir("products/Synthesis/media", new DirectoryIdentity(
                List.of("media", "video"), List.of("mp4", "mov"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.9, null, "test", "",
                List.of(), List.of("synthesis"), false, List.of()
        ));

        // Place mp4 in its correct directory
        Files.writeString(workspace.resolve("products/Synthesis/media/demo.mp4"), "video-data");

        List<E010Finding> findings = checker.check(workspace);

        // No findings — file is correctly placed
        assertTrue(findings.isEmpty(),
                "Media file in correct non-transient directory should produce no findings");
    }

    // ---- Sorting: ERROR before WARNING before INFO ----

    @Test
    void findings_sortedByLevel_errorFirst() throws IOException {
        // Create both transient and rejectsTypes directories
        createDir("marketing", new DirectoryIdentity(
                List.of("marketing"), List.of("md", "pdf", "png", "mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of()
        ));

        createDir("articles", new DirectoryIdentity(
                List.of("article"), List.of("md", "pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "",
                List.of("video", "media", "audio"), List.of(), false, List.of()
        ));

        // mp4 in articles = ERROR
        Files.writeString(workspace.resolve("articles/bad-video.mp4"), "data");
        // mp4 in marketing = INFO (no matching product dir)
        Files.writeString(workspace.resolve("marketing/random-clip.mp4"), "data");

        List<E010Finding> findings = checker.check(workspace);

        assertTrue(findings.size() >= 2,
                "Should have at least 2 findings, got " + findings.size());
        assertEquals(E010Level.ERROR, findings.get(0).level(),
                "First finding should be ERROR");
        // Last finding should be INFO
        E010Finding last = findings.get(findings.size() - 1);
        assertTrue(last.level() == E010Level.INFO || last.level() == E010Level.WARNING,
                "Last finding should be INFO or WARNING, got: " + last.level());
    }

    // ---- Audio file in rejectsTypes directory ----

    @Test
    void error_audioFileInRejectTypesDir() throws IOException {
        createDir("articles", new DirectoryIdentity(
                List.of("article"), List.of("md", "pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "",
                List.of("video", "media", "audio"), List.of(), false, List.of()
        ));

        // Audio file in articles/ — should be ERROR (rejectsTypes includes "audio" and "media")
        Files.writeString(workspace.resolve("articles/podcast.mp3"), "audio-data");

        List<E010Finding> findings = checker.check(workspace);

        List<E010Finding> errors = findings.stream()
                .filter(f -> f.level() == E010Level.ERROR)
                .toList();

        assertFalse(errors.isEmpty(),
                "Should have ERROR finding for mp3 in articles/ with rejectsTypes=[video, media, audio]");
    }

    // ---- Image file in rejectsTypes directory ----

    @Test
    void error_imageFileInMediaRejectDir() throws IOException {
        createDir("plans", new DirectoryIdentity(
                List.of("plan", "strategy"), List.of("md", "pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.7, null, "test", "",
                List.of("video", "media", "audio"), List.of(), false, List.of()
        ));

        // Image file — png maps to Set.of("image", "media"), "media" is in rejectsTypes
        Files.writeString(workspace.resolve("plans/diagram.png"), "image-data");

        List<E010Finding> findings = checker.check(workspace);

        List<E010Finding> errors = findings.stream()
                .filter(f -> f.level() == E010Level.ERROR)
                .toList();

        assertFalse(errors.isEmpty(),
                "Should have ERROR finding for png in plans/ with rejectsTypes=[video, media, audio]");
    }

    // ---- Non-media file in transient dir should NOT trigger E010 ----

    @Test
    void noFinding_nonMediaFileInTransientDir() throws IOException {
        createDir("marketing", new DirectoryIdentity(
                List.of("marketing"), List.of("md", "pdf", "png", "mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of()
        ));

        // Place a .md file — not a media file, so E010 transient check should skip it
        Files.writeString(workspace.resolve("marketing/readme.md"), "text content");

        List<E010Finding> findings = checker.check(workspace);

        // No findings — .md is not a media file
        assertTrue(findings.isEmpty(),
                "Non-media file in transient directory should not trigger E010");
    }

    // ---- .synthesis.md file should NOT trigger rejectsTypes check ----

    @Test
    void noFinding_synthesisFileExcludedFromRejectsCheck() throws IOException {
        createDir("articles", new DirectoryIdentity(
                List.of("article"), List.of("md", "pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "",
                List.of("video", "media", "audio"), List.of(), false, List.of()
        ));

        // The .synthesis.md itself should not be flagged even though .md maps to "document"
        // (and "document" is not in rejectsTypes, so this is already fine — but verify the
        //  filter explicitly excludes .synthesis.md)
        List<E010Finding> findings = checker.check(workspace);

        assertTrue(findings.isEmpty(),
                ".synthesis.md should not trigger any finding");
    }

    // ---- Empty workspace returns no findings ----

    @Test
    void emptyWorkspace_returnsNoFindings() {
        List<E010Finding> findings = checker.check(workspace);
        assertTrue(findings.isEmpty(),
                "Empty workspace should return no findings");
    }

    // ---- PDF in articles/ should NOT trigger error (rejectsTypes = video, media, audio) ----

    @Test
    void noError_pdfInArticlesWithVideoRejects() throws IOException {
        createDir("articles", new DirectoryIdentity(
                List.of("article"), List.of("md", "pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "",
                List.of("video", "media", "audio"), List.of(), false, List.of()
        ));

        // PDF maps to "document" — not in rejectsTypes
        Files.writeString(workspace.resolve("articles/research-paper.pdf"), "pdf-data");

        List<E010Finding> findings = checker.check(workspace);

        assertTrue(findings.isEmpty(),
                "PDF in articles/ should not trigger error because 'document' is not in rejectsTypes");
    }
}
