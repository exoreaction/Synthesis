package io.exoreaction.synthesis.confluence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfluencePageMapper} — filename sanitization, virtual path building,
 * and collision resolution.
 *
 * <p>Mirrors {@code NotionPageMapperTest} exactly: Confluence's page-parent hierarchy
 * (via {@code parentId}) is directly analogous to Notion's {@code parent.page_id}, so
 * this logic transfers unchanged (per RFC #541). The space key is intentionally
 * handled by the caller ({@code ConfluenceWorkspaceSource}), not this class.
 */
class ConfluencePageMapperTest {

    private ConfluencePageMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ConfluencePageMapper();
    }

    // -----------------------------------------------------------------------
    // sanitizeFilename tests
    // -----------------------------------------------------------------------

    @Test
    void sanitizeFilename_normalTitle() {
        assertEquals("Meeting Notes.md", ConfluencePageMapper.sanitizeFilename("Meeting Notes"));
    }

    @Test
    void sanitizeFilename_titleWithSlashes() {
        assertEquals("Q12025 Report.md", ConfluencePageMapper.sanitizeFilename("Q1/2025 Report"));
    }

    @Test
    void sanitizeFilename_emptyString() {
        assertEquals("untitled.md", ConfluencePageMapper.sanitizeFilename(""));
    }

    @Test
    void sanitizeFilename_nullTitle() {
        assertEquals("untitled.md", ConfluencePageMapper.sanitizeFilename(null));
    }

    @Test
    void sanitizeFilename_veryLongTitle() {
        String longTitle = "A".repeat(300);
        String result = ConfluencePageMapper.sanitizeFilename(longTitle);
        assertEquals(203, result.length());
        assertTrue(result.endsWith(".md"));
    }

    @Test
    void sanitizeFilename_unicodeTitle() {
        assertEquals("Årsrapport 2025.md", ConfluencePageMapper.sanitizeFilename("Årsrapport 2025"));
    }

    @Test
    void sanitizeFilename_onlyUnsafeChars() {
        assertEquals("untitled.md", ConfluencePageMapper.sanitizeFilename("/:*?\"<>|"));
    }

    // -----------------------------------------------------------------------
    // buildVirtualPath tests
    // -----------------------------------------------------------------------

    @Test
    void buildVirtualPath_rootLevelPage() {
        Map<String, String> idToTitle = Map.of("page-1", "Architecture");
        Map<String, String> idToParent = Map.of();

        String path = mapper.buildVirtualPath("page-1", idToTitle, idToParent, "");
        assertEquals("Architecture.md", path);
    }

    @Test
    void buildVirtualPath_twoLevelDeep() {
        Map<String, String> idToTitle = Map.of(
                "parent-1", "Engineering",
                "page-1", "Architecture"
        );
        Map<String, String> idToParent = Map.of("page-1", "parent-1");

        String path = mapper.buildVirtualPath("page-1", idToTitle, idToParent, "");
        assertEquals("Engineering/Architecture.md", path);
    }

    @Test
    void buildVirtualPath_threeLevelDeep() {
        Map<String, String> idToTitle = Map.of(
                "l1", "Company",
                "l2", "Engineering",
                "l3", "Backend"
        );
        Map<String, String> idToParent = Map.of(
                "l2", "l1",
                "l3", "l2"
        );

        String path = mapper.buildVirtualPath("l3", idToTitle, idToParent, "");
        assertEquals("Company/Engineering/Backend.md", path);
    }

    @Test
    void buildVirtualPath_cycleDetection_maxDepth() {
        Map<String, String> idToTitle = new HashMap<>();
        idToTitle.put("a", "PageA");
        idToTitle.put("b", "PageB");

        Map<String, String> idToParent = new HashMap<>();
        idToParent.put("a", "b");
        idToParent.put("b", "a");

        String path = mapper.buildVirtualPath("a", idToTitle, idToParent, "");
        assertNotNull(path);
        assertTrue(path.endsWith(".md"));
    }

    @Test
    void buildVirtualPath_pageWithNoParent() {
        Map<String, String> idToTitle = Map.of("orphan", "Orphan Page");
        Map<String, String> idToParent = Map.of();

        String path = mapper.buildVirtualPath("orphan", idToTitle, idToParent, "");
        assertEquals("Orphan Page.md", path);
    }

    // -----------------------------------------------------------------------
    // resolveCollisions tests
    // -----------------------------------------------------------------------

    @Test
    void resolveCollisions_noCollisions() {
        List<ConfluencePageMapper.ConfluencePage> pages = List.of(
                page("id-1", "Page A", "PathA.md"),
                page("id-2", "Page B", "PathB.md")
        );

        List<ConfluencePageMapper.ConfluencePage> resolved = mapper.resolveCollisions(pages);
        assertEquals(2, resolved.size());
        assertTrue(resolved.stream().anyMatch(p -> p.virtualPath().equals("PathA.md")));
        assertTrue(resolved.stream().anyMatch(p -> p.virtualPath().equals("PathB.md")));
    }

    @Test
    void resolveCollisions_oneCollision_bothRenamed() {
        List<ConfluencePageMapper.ConfluencePage> pages = List.of(
                page("abcd1234", "Notes", "Notes.md"),
                page("efgh5678", "Notes", "Notes.md")
        );

        List<ConfluencePageMapper.ConfluencePage> resolved = mapper.resolveCollisions(pages);
        assertEquals(2, resolved.size());

        assertTrue(resolved.stream().anyMatch(p -> p.virtualPath().equals("Notes-abcd1234.md")));
        assertTrue(resolved.stream().anyMatch(p -> p.virtualPath().equals("Notes-efgh5678.md")));
    }

    @Test
    void resolveCollisions_multipleCollisions() {
        List<ConfluencePageMapper.ConfluencePage> pages = List.of(
                page("aaaa00001111", "README", "README.md"),
                page("bbbb00002222", "README", "README.md"),
                page("cccc00003333", "README", "README.md")
        );

        List<ConfluencePageMapper.ConfluencePage> resolved = mapper.resolveCollisions(pages);
        assertEquals(3, resolved.size());

        long uniquePaths = resolved.stream()
                .map(ConfluencePageMapper.ConfluencePage::virtualPath).distinct().count();
        assertEquals(3, uniquePaths);
    }

    @Test
    void resolveCollisions_emptyList() {
        List<ConfluencePageMapper.ConfluencePage> resolved = mapper.resolveCollisions(List.of());
        assertTrue(resolved.isEmpty());
    }

    @Test
    void resolveCollisions_nullList() {
        List<ConfluencePageMapper.ConfluencePage> resolved = mapper.resolveCollisions(null);
        assertTrue(resolved.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ConfluencePageMapper.ConfluencePage page(String id, String title, String virtualPath) {
        return new ConfluencePageMapper.ConfluencePage(
                id, title, "ENG", null,
                Instant.now(), Instant.now(),
                virtualPath, "# " + title
        );
    }
}
