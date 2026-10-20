package io.exoreaction.synthesis.confluence;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Maps Confluence page hierarchy to virtual directory paths for Synthesis indexing.
 *
 * <p>Each Confluence page is mapped to a virtual filesystem path that mirrors the
 * page tree structure, rooted under its space key. For example, a page titled
 * "Architecture" under a parent titled "Engineering" in space "ENG" becomes
 * {@code ENG/Engineering/Architecture.md}.
 *
 * <p>Confluence's page-parent hierarchy (via {@code parentId}) is directly analogous
 * to Notion's {@code parent.page_id}, so the ancestor-walking and collision-resolution
 * logic here mirrors {@code NotionPageMapper} exactly. The one genuinely new concept
 * is the space key, which Confluence has and Notion does not — it is prepended to the
 * path by the caller ({@link ConfluenceWorkspaceSource}), not by this class, so that
 * {@link #buildVirtualPath} and {@link #resolveCollisions} stay space-agnostic and
 * directly comparable to their Notion counterparts.
 */
public class ConfluencePageMapper {

    /** Maximum depth to traverse when building virtual paths (prevents infinite loops). */
    private static final int MAX_DEPTH = 10;

    /** Characters that are not allowed in filenames. */
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[/\\\\:*?\"<>|]");

    /** Control characters (U+0000 to U+001F and U+007F). */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1f\\x7f]");

    /** Maximum filename length (before .md extension). */
    private static final int MAX_FILENAME_LENGTH = 200;

    /**
     * Represents a Confluence page with its resolved virtual path and content.
     *
     * @param id              the Confluence page ID
     * @param title           the page title
     * @param spaceKey        the key of the space this page belongs to
     * @param parentId        the parent page ID (null for space-root pages)
     * @param lastEditedTime  when the page's current version was created in Confluence
     * @param createdTime     when the page was created in Confluence
     * @param virtualPath     the resolved virtual filesystem path (e.g. "ENG/Parent/Child.md")
     * @param markdownContent the page content converted to Markdown
     */
    public record ConfluencePage(
            String id,
            String title,
            String spaceKey,
            String parentId,
            Instant lastEditedTime,
            Instant createdTime,
            String virtualPath,
            String markdownContent
    ) {}

    /**
     * Sanitizes a page title for use as a filename.
     *
     * <p>Strips unsafe characters ({@code / \ : * ? " < > |}), control characters,
     * trims whitespace, and truncates to {@value MAX_FILENAME_LENGTH} characters.
     * Appends {@code .md} extension. Returns {@code untitled.md} for empty/null input.
     *
     * @param title the raw page title
     * @return a sanitized filename ending in .md
     */
    public static String sanitizeFilename(String title) {
        if (title == null || title.isBlank()) {
            return "untitled.md";
        }

        String safe = UNSAFE_CHARS.matcher(title).replaceAll("");
        safe = CONTROL_CHARS.matcher(safe).replaceAll("");
        safe = safe.trim();

        if (safe.isEmpty()) {
            return "untitled.md";
        }

        if (safe.length() > MAX_FILENAME_LENGTH) {
            safe = safe.substring(0, MAX_FILENAME_LENGTH);
        }

        return safe + ".md";
    }

    /**
     * Builds a virtual filesystem path for a page by walking up the parent chain.
     *
     * <p>For a page with ancestors root &rarr; Parent &rarr; Child, the result is
     * {@code Parent/Child.md} (the root page itself is excluded from the path).
     * Space-root pages (direct children of rootId, or with no recorded parent) get
     * just {@code Page.md}. The caller is expected to prepend the space key.
     *
     * <p>Traversal stops at {@value MAX_DEPTH} ancestors to prevent infinite loops
     * from circular parent references.
     *
     * @param pageId     the page whose path to build
     * @param idToTitle  map of page ID to page title
     * @param idToParent map of page ID to parent page ID
     * @param rootId     the root page ID (excluded from path segments), may be blank
     * @return the virtual path, e.g. "Parent/Child.md"
     */
    public String buildVirtualPath(String pageId, Map<String, String> idToTitle,
                                   Map<String, String> idToParent, String rootId) {
        List<String> segments = new ArrayList<>();
        String currentId = pageId;
        Set<String> visited = new HashSet<>();
        int depth = 0;

        while (currentId != null && depth < MAX_DEPTH) {
            if (visited.contains(currentId)) {
                break; // cycle detected
            }
            visited.add(currentId);

            if (currentId.equals(rootId)) {
                break; // reached the root, stop
            }

            String title = idToTitle.getOrDefault(currentId, "untitled");
            segments.add(title);

            currentId = idToParent.get(currentId);
            depth++;
        }

        if (segments.isEmpty()) {
            return "untitled.md";
        }

        // Reverse to get root-first order
        Collections.reverse(segments);

        // Last segment gets .md extension, others are directory names
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                path.append("/");
            }
            if (i == segments.size() - 1) {
                path.append(sanitizeFilename(segments.get(i)));
            } else {
                // Directory segment: sanitize similarly but without .md
                String dirName = UNSAFE_CHARS.matcher(segments.get(i)).replaceAll("");
                dirName = CONTROL_CHARS.matcher(dirName).replaceAll("").trim();
                if (dirName.isEmpty()) dirName = "untitled";
                if (dirName.length() > MAX_FILENAME_LENGTH) {
                    dirName = dirName.substring(0, MAX_FILENAME_LENGTH);
                }
                path.append(dirName);
            }
        }

        return path.toString();
    }

    /**
     * Resolves path collisions by appending the first 8 characters of the page ID
     * to duplicate filenames.
     *
     * <p>For example, if two pages both resolve to {@code Notes.md}, they become
     * {@code Notes-ab12cd34.md} and {@code Notes-ef56gh78.md}.
     *
     * @param pages the list of pages (may contain duplicate virtual paths)
     * @return a new list with collision-free virtual paths
     */
    public List<ConfluencePage> resolveCollisions(List<ConfluencePage> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }

        // Group by virtual path
        Map<String, List<ConfluencePage>> byPath = pages.stream()
                .collect(Collectors.groupingBy(ConfluencePage::virtualPath));

        List<ConfluencePage> resolved = new ArrayList<>();
        for (Map.Entry<String, List<ConfluencePage>> entry : byPath.entrySet()) {
            List<ConfluencePage> group = entry.getValue();
            if (group.size() == 1) {
                resolved.add(group.get(0));
            } else {
                // Collision: disambiguate all pages in the group
                for (ConfluencePage page : group) {
                    String path = page.virtualPath();
                    String idSuffix = page.id().length() >= 8
                            ? page.id().substring(0, 8)
                            : page.id();
                    String newPath;
                    if (path.endsWith(".md")) {
                        newPath = path.substring(0, path.length() - 3) + "-" + idSuffix + ".md";
                    } else {
                        newPath = path + "-" + idSuffix;
                    }
                    resolved.add(new ConfluencePage(
                            page.id(), page.title(), page.spaceKey(), page.parentId(),
                            page.lastEditedTime(), page.createdTime(),
                            newPath, page.markdownContent()
                    ));
                }
            }
        }
        return resolved;
    }
}
