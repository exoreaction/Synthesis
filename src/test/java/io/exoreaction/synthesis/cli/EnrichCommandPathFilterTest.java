package io.exoreaction.synthesis.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EnrichCommand's path/exclude filtering logic.
 *
 * <p>Covers issue #76: {@code --path} and {@code --exclude} options for targeting
 * specific subdirectories or excluding patterns during enrichment.
 *
 * <p>Covers issue #256: {@code --path} prefix normalization so bare directory
 * names (e.g. {@code knowledge-infrastructure/}) match files recursively.
 */
class EnrichCommandPathFilterTest {

    private static final FileSystem FS = FileSystems.getDefault();

    private PathMatcher glob(String pattern) {
        return FS.getPathMatcher("glob:" + pattern);
    }

    /** Helper: normalize then create glob matcher (mimics production code path). */
    private PathMatcher normalizedGlob(String rawPattern) {
        return FS.getPathMatcher("glob:" + EnrichCommand.normalizePathPattern(rawPattern));
    }

    // ---- no filters → all paths pass ----

    @Test
    void noFilter_allPathsMatch() {
        assertTrue(EnrichCommand.matchesPathFilter("docs/image.png", List.of(), List.of()));
        assertTrue(EnrichCommand.matchesPathFilter("archive/old.mp4", List.of(), List.of()));
        assertTrue(EnrichCommand.matchesPathFilter("README.md", List.of(), List.of()));
    }

    // ---- --path include filter ----

    @Test
    void includeFilter_directoryPrefix_matchesFilesUnderDir() {
        List<PathMatcher> include = List.of(glob("docs/**"));
        assertTrue(EnrichCommand.matchesPathFilter("docs/image.png", include, List.of()));
        assertTrue(EnrichCommand.matchesPathFilter("docs/sub/video.mp4", include, List.of()));
    }

    @Test
    void includeFilter_directoryPrefix_excludesFilesOutsideDir() {
        List<PathMatcher> include = List.of(glob("docs/**"));
        assertFalse(EnrichCommand.matchesPathFilter("archive/image.png", include, List.of()));
        assertFalse(EnrichCommand.matchesPathFilter("image.png", include, List.of()));
    }

    @Test
    void includeFilter_glob_matchesMatchingFilesInSubdirs() {
        // "**/*.pdf" requires at least one directory component in Java's PathMatcher glob
        List<PathMatcher> include = List.of(glob("**/*.pdf"));
        assertTrue(EnrichCommand.matchesPathFilter("docs/report.pdf", include, List.of()));
        assertTrue(EnrichCommand.matchesPathFilter("eXOReaction/decks/slides.pdf", include, List.of()));
    }

    @Test
    void includeFilter_simpleGlob_matchesRootLevelFiles() {
        // "*.pdf" matches root-level filenames without directory component
        List<PathMatcher> include = List.of(glob("*.pdf"));
        assertTrue(EnrichCommand.matchesPathFilter("report.pdf", include, List.of()));
        assertFalse(EnrichCommand.matchesPathFilter("report.mp4", include, List.of()));
    }

    @Test
    void includeFilter_glob_excludesNonMatchingFiles() {
        List<PathMatcher> include = List.of(glob("**/*.pdf"));
        assertFalse(EnrichCommand.matchesPathFilter("docs/image.png", include, List.of()));
    }

    @Test
    void includeFilter_multiplePatterns_matchesAny() {
        List<PathMatcher> include = List.of(glob("docs/**"), glob("slides/**"));
        assertTrue(EnrichCommand.matchesPathFilter("docs/image.png", include, List.of()));
        assertTrue(EnrichCommand.matchesPathFilter("slides/deck.pdf", include, List.of()));
        assertFalse(EnrichCommand.matchesPathFilter("archive/old.mp4", include, List.of()));
    }

    // ---- --exclude filter ----

    @Test
    void excludeFilter_removesMatchingPaths() {
        List<PathMatcher> exclude = List.of(glob("archive/**"));
        assertFalse(EnrichCommand.matchesPathFilter("archive/old.png", List.of(), exclude));
        assertFalse(EnrichCommand.matchesPathFilter("archive/sub/video.mp4", List.of(), exclude));
    }

    @Test
    void excludeFilter_doesNotAffectNonMatchingPaths() {
        List<PathMatcher> exclude = List.of(glob("archive/**"));
        assertTrue(EnrichCommand.matchesPathFilter("docs/image.png", List.of(), exclude));
        assertTrue(EnrichCommand.matchesPathFilter("image.png", List.of(), exclude));
    }

    // ---- combined --path + --exclude ----

    @Test
    void combined_includeAndExclude_applyBoth() {
        List<PathMatcher> include = List.of(glob("eXOReaction/**"));
        List<PathMatcher> exclude = List.of(glob("eXOReaction/archive/**"));

        // In include dir, not excluded
        assertTrue(EnrichCommand.matchesPathFilter("eXOReaction/docs/image.png", include, exclude));

        // In include dir, but excluded
        assertFalse(EnrichCommand.matchesPathFilter("eXOReaction/archive/old.mp4", include, exclude));

        // Outside include dir
        assertFalse(EnrichCommand.matchesPathFilter("Quadim/image.png", include, exclude));
    }

    // ==== Issue #256: normalizePathPattern tests ====

    @Test
    void normalize_trailingSlash_appendsDoublestar() {
        assertEquals("knowledge-infrastructure/**",
                EnrichCommand.normalizePathPattern("knowledge-infrastructure/"));
        assertEquals("docs/**",
                EnrichCommand.normalizePathPattern("docs/"));
        assertEquals("eXOReaction/media/**",
                EnrichCommand.normalizePathPattern("eXOReaction/media/"));
    }

    @Test
    void normalize_bareDirName_appendsSlashDoublestar() {
        assertEquals("docs/**",
                EnrichCommand.normalizePathPattern("docs"));
        assertEquals("knowledge-infrastructure/**",
                EnrichCommand.normalizePathPattern("knowledge-infrastructure"));
    }

    @Test
    void normalize_globAlreadyPresent_unchanged() {
        assertEquals("docs/**", EnrichCommand.normalizePathPattern("docs/**"));
        assertEquals("**/*.pdf", EnrichCommand.normalizePathPattern("**/*.pdf"));
        assertEquals("docs/*.png", EnrichCommand.normalizePathPattern("docs/*.png"));
        assertEquals("file?.txt", EnrichCommand.normalizePathPattern("file?.txt"));
        assertEquals("[abc].txt", EnrichCommand.normalizePathPattern("[abc].txt"));
        assertEquals("{a,b}.txt", EnrichCommand.normalizePathPattern("{a,b}.txt"));
    }

    @Test
    void normalize_fileWithExtension_unchanged() {
        // A pattern like "report.pdf" looks like a specific file, not a directory
        assertEquals("report.pdf", EnrichCommand.normalizePathPattern("report.pdf"));
    }

    // ==== Issue #256: end-to-end path filter with bare prefix (the bug scenario) ====

    @Test
    void pathPrefixTrailingSlash_matchesFilesUnderDir() {
        // This is the exact scenario from bug #256:
        // --path knowledge-infrastructure/ should find files under that directory
        List<PathMatcher> include = List.of(normalizedGlob("knowledge-infrastructure/"));

        assertTrue(EnrichCommand.matchesPathFilter(
                "knowledge-infrastructure/image.png", include, List.of()));
        assertTrue(EnrichCommand.matchesPathFilter(
                "knowledge-infrastructure/sub/deep/file.pdf", include, List.of()));
        assertFalse(EnrichCommand.matchesPathFilter(
                "other-dir/image.png", include, List.of()));
    }

    @Test
    void pathPrefixNoSlash_matchesFilesUnderDir() {
        // --path knowledge-infrastructure (no trailing slash) should also work
        List<PathMatcher> include = List.of(normalizedGlob("knowledge-infrastructure"));

        assertTrue(EnrichCommand.matchesPathFilter(
                "knowledge-infrastructure/image.png", include, List.of()));
        assertTrue(EnrichCommand.matchesPathFilter(
                "knowledge-infrastructure/sub/report.pdf", include, List.of()));
        assertFalse(EnrichCommand.matchesPathFilter(
                "other-dir/image.png", include, List.of()));
    }

    @Test
    void pathPrefixNoMatch_returnsEmpty() {
        // --path with a dir that has no matching files
        List<PathMatcher> include = List.of(normalizedGlob("nonexistent-dir/"));

        assertFalse(EnrichCommand.matchesPathFilter(
                "knowledge-infrastructure/image.png", include, List.of()));
        assertFalse(EnrichCommand.matchesPathFilter(
                "docs/report.pdf", include, List.of()));
    }

    @Test
    void pathPrefixNestedDir_matchesFilesRecursively() {
        // --path eXOReaction/media/ should match files recursively under that path
        List<PathMatcher> include = List.of(normalizedGlob("eXOReaction/media/"));

        assertTrue(EnrichCommand.matchesPathFilter(
                "eXOReaction/media/video.mp4", include, List.of()));
        assertTrue(EnrichCommand.matchesPathFilter(
                "eXOReaction/media/images/photo.png", include, List.of()));
        assertFalse(EnrichCommand.matchesPathFilter(
                "eXOReaction/docs/file.txt", include, List.of()));
    }

    @Test
    void excludePrefixNormalized_excludesRecursively() {
        // --exclude should also normalize, excluding a whole directory tree
        List<PathMatcher> exclude = List.of(normalizedGlob("archive/"));

        assertFalse(EnrichCommand.matchesPathFilter(
                "archive/old.png", List.of(), exclude));
        assertFalse(EnrichCommand.matchesPathFilter(
                "archive/deep/nested/file.pdf", List.of(), exclude));
        assertTrue(EnrichCommand.matchesPathFilter(
                "docs/image.png", List.of(), exclude));
    }
}
