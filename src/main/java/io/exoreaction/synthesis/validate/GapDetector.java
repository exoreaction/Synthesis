package io.exoreaction.synthesis.validate;

import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Detects source files that have no coverage in any skill or documentation file.
 *
 * <p>A source file is "uncovered" when its class name (filename without extension)
 * does not appear in any of the provided skill/doc files.
 *
 * <p>Results are ranked by priority: CLI and MCP files score highest (user-facing),
 * followed by core/config (foundational), then everything else.
 */
public class GapDetector {

    public record GapResult(String className, String relativePath,
                            long sizeBytes, int priority) {}

    /**
     * Finds indexed CODE files whose class names appear in none of the skill files.
     *
     * @param index      read-only SearchIndex (must have CODE documents)
     * @param skillFiles list of skill/doc files to check coverage against
     * @return uncovered source files sorted by priority descending, then name
     */
    public List<GapResult> detectGaps(SearchIndex index,
                                       List<Path> skillFiles) throws IOException {
        // 1. Get all CODE filenames from the index
        List<SearchResult> codeFiles = index.search("*", "CODE", 5000);
        if (codeFiles.isEmpty()) {
            // Try a different approach if wildcard doesn't work
            codeFiles = index.search("class", "CODE", 5000);
        }

        // 2. Build a combined skill content string for fast contains() checks
        StringBuilder allSkillContent = new StringBuilder();
        for (Path skill : skillFiles) {
            try {
                allSkillContent.append(Files.readString(skill)).append('\n');
            } catch (IOException e) {
                // skip unreadable files
            }
        }
        String skillContent = allSkillContent.toString();

        // 3. For each source file, check if its class name appears in skills
        List<GapResult> gaps = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (SearchResult result : codeFiles) {
            String fileName = result.fileName();
            if (fileName == null) continue;

            // Extract class name: "SearchIndex.java" -> "SearchIndex"
            String className = stripExtension(fileName);
            if (className.isBlank() || !seen.add(className)) continue;

            // Skip test classes, generated classes, and package-info
            if (className.endsWith("Test") || className.endsWith("Tests")
                    || className.equals("package-info")
                    || className.startsWith("Abstract")) {
                continue;
            }

            if (!skillContent.contains(className)) {
                int priority = computePriority(result.relativePath(), result.sizeBytes());
                gaps.add(new GapResult(className, result.relativePath(),
                        result.sizeBytes(), priority));
            }
        }

        // 4. Sort: highest priority first, then alphabetically
        gaps.sort(Comparator.comparingInt(GapResult::priority).reversed()
                .thenComparing(GapResult::className));

        return gaps;
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    int computePriority(String relativePath, long sizeBytes) {
        if (relativePath == null) return 1;
        int score = 0;
        String lower = relativePath.toLowerCase();
        if (lower.contains("/cli/")) score += 3;
        if (lower.contains("/mcp/")) score += 3;
        if (lower.contains("/core/") || lower.contains("/config/")) score += 2;
        if (lower.contains("/index/") || lower.contains("/search/")) score += 2;
        if (sizeBytes > 500 * 50) score += 2;   // >500 lines approx
        else if (sizeBytes > 200 * 50) score += 1;
        return Math.max(1, score);
    }
}
