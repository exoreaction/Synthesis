package io.exoreaction.synthesis.notion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NotionPageMapper} — filename sanitization, virtual path building,
 * and collision resolution.
 */
class NotionPageMapperTest {

    private NotionPageMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new NotionPageMapper();
    }

    // -----------------------------------------------------------------------
    // sanitizeFilename tests
    // -----------------------------------------------------------------------

    @Test
    void sanitizeFilename_normalTitle() {
        assertEquals("Meeting Notes.md", NotionPageMapper.sanitizeFilename("Meeting Notes"));
    }

    @Test
    void sanitizeFilename_titleWithSlashes() {
        // Slashes are stripped (not replaced with space)
        assertEquals("Q12025 Report.md", NotionPageMapper.sanitizeFilename("Q1/2025 Report"));
    }

    @Test
    void sanitizeFilename_titleWithAllUnsafeChars() {
        assertEquals("file name.md", NotionPageMapper.sanitizeFilename("file/\\:*?\"<>| name"));
    }

    @Test
    void sanitizeFilename_emptyString() {
        assertEquals("untitled.md", NotionPageMapper.sanitizeFilename(""));
    }

    @Test
    void sanitizeFilename_nullTitle() {
        assertEquals("untitled.md", NotionPageMapper.sanitizeFilename(null));
    }

    @Test
    void sanitizeFilename_blankTitle() {
        assertEquals("untitled.md", NotionPageMapper.sanitizeFilename("   "));
    }

    @Test
    void sanitizeFilename_veryLongTitle() {
        String longTitle = "A".repeat(300);
        String result = NotionPageMapper.sanitizeFilename(longTitle);
        // 200 chars + ".md" = 203
        assertEquals(203, result.length());
        assertTrue(result.endsWith(".md"));
        assertTrue(result.startsWith("AAAA"));
    }

    @Test
    void sanitizeFilename_unicodeTitle() {
        assertEquals("\u00c5rsrapport 2025.md", NotionPageMapper.sanitizeFilename("\u00c5rsrapport 2025"));
    }

    @Test
    void sanitizeFilename_controlChars() {
        assertEquals("hello.md", NotionPageMapper.sanitizeFilename("hel\u0000lo\u001f"));
    }

    @Test
    void sanitizeFilename_onlyUnsafeChars() {
        // All chars are stripped, resulting in empty -> "untitled.md"
        assertEquals("untitled.md", NotionPageMapper.sanitizeFilename("/:*?\"<>|"));
    }

    // -----------------------------------------------------------------------
    // buildVirtualPath tests
    // -----------------------------------------------------------------------

    @Test
    void buildVirtualPath_rootLevelPage() {
        // Page is a direct child of root
        Map<String, String> idToTitle = Map.of("page-1", "Architecture", "root", "Root");
        Map<String, String> idToParent = Map.of("page-1", "root");

        String path = mapper.buildVirtualPath("page-1", idToTitle, idToParent, "root");
        assertEquals("Architecture.md", path);
    }

    @Test
    void buildVirtualPath_twoLevelDeep() {
        Map<String, String> idToTitle = Map.of(
                "root", "Root",
                "parent-1", "Engineering",
                "page-1", "Architecture"
        );
        Map<String, String> idToParent = Map.of(
                "parent-1", "root",
                "page-1", "parent-1"
        );

        String path = mapper.buildVirtualPath("page-1", idToTitle, idToParent, "root");
        assertEquals("Engineering/Architecture.md", path);
    }

    @Test
    void buildVirtualPath_threeLevelDeep() {
        Map<String, String> idToTitle = Map.of(
                "root", "Root",
                "l1", "Company",
                "l2", "Engineering",
                "l3", "Backend"
        );
        Map<String, String> idToParent = Map.of(
                "l1", "root",
                "l2", "l1",
                "l3", "l2"
        );

        String path = mapper.buildVirtualPath("l3", idToTitle, idToParent, "root");
        assertEquals("Company/Engineering/Backend.md", path);
    }

    @Test
    void buildVirtualPath_cycleDetection_maxDepth() {
        // Create a circular reference: a -> b -> a
        Map<String, String> idToTitle = new HashMap<>();
        idToTitle.put("a", "PageA");
        idToTitle.put("b", "PageB");

        Map<String, String> idToParent = new HashMap<>();
        idToParent.put("a", "b");
        idToParent.put("b", "a");

        // Should not hang — stops at cycle detection
        String path = mapper.buildVirtualPath("a", idToTitle, idToParent, "root");
        assertNotNull(path);
        assertTrue(path.endsWith(".md"));
    }

    @Test
    void buildVirtualPath_pageWithNoParent() {
        // Page has no parent in the map — treated as root-level
        Map<String, String> idToTitle = Map.of("orphan", "Orphan Page");
        Map<String, String> idToParent = Map.of();

        String path = mapper.buildVirtualPath("orphan", idToTitle, idToParent, "root");
        assertEquals("Orphan Page.md", path);
    }

    // -----------------------------------------------------------------------
    // resolveCollisions tests
    // -----------------------------------------------------------------------

    @Test
    void resolveCollisions_noCollisions() {
        List<NotionPageMapper.NotionPage> pages = List.of(
                page("id-1", "Page A", "PathA.md"),
                page("id-2", "Page B", "PathB.md")
        );

        List<NotionPageMapper.NotionPage> resolved = mapper.resolveCollisions(pages);
        assertEquals(2, resolved.size());
        assertTrue(resolved.stream().anyMatch(p -> p.virtualPath().equals("PathA.md")));
        assertTrue(resolved.stream().anyMatch(p -> p.virtualPath().equals("PathB.md")));
    }

    @Test
    void resolveCollisions_oneCollision_bothRenamed() {
        List<NotionPageMapper.NotionPage> pages = List.of(
                page("abcd1234-0000", "Notes", "Notes.md"),
                page("efgh5678-0000", "Notes", "Notes.md")
        );

        List<NotionPageMapper.NotionPage> resolved = mapper.resolveCollisions(pages);
        assertEquals(2, resolved.size());

        // Both should be disambiguated
        assertTrue(resolved.stream().anyMatch(p -> p.virtualPath().equals("Notes-abcd1234.md")));
        assertTrue(resolved.stream().anyMatch(p -> p.virtualPath().equals("Notes-efgh5678.md")));
    }

    @Test
    void resolveCollisions_multipleCollisions() {
        List<NotionPageMapper.NotionPage> pages = List.of(
                page("aaaa0000-1111", "README", "README.md"),
                page("bbbb0000-2222", "README", "README.md"),
                page("cccc0000-3333", "README", "README.md")
        );

        List<NotionPageMapper.NotionPage> resolved = mapper.resolveCollisions(pages);
        assertEquals(3, resolved.size());

        // All three should have unique paths
        long uniquePaths = resolved.stream().map(NotionPageMapper.NotionPage::virtualPath).distinct().count();
        assertEquals(3, uniquePaths);
    }

    @Test
    void resolveCollisions_emptyList() {
        List<NotionPageMapper.NotionPage> resolved = mapper.resolveCollisions(List.of());
        assertTrue(resolved.isEmpty());
    }

    @Test
    void resolveCollisions_nullList() {
        List<NotionPageMapper.NotionPage> resolved = mapper.resolveCollisions(null);
        assertTrue(resolved.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private NotionPageMapper.NotionPage page(String id, String title, String virtualPath) {
        return new NotionPageMapper.NotionPage(
                id, title, null,
                Instant.now(), Instant.now(),
                virtualPath, "# " + title
        );
    }
}
