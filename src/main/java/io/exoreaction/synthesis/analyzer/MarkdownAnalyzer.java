package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes Markdown files to extract document structure and content.
 *
 * <p>Extracts:
 * <ul>
 *   <li>Headings (H1-H6) with hierarchy</li>
 *   <li>Links (both inline and reference)</li>
 *   <li>Word count</li>
 *   <li>Code block detection</li>
 *   <li>Front matter detection</li>
 *   <li>Content preview for indexing</li>
 * </ul>
 */
public class MarkdownAnalyzer implements FileAnalyzer {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\(([^)]+)\\)");
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("^```", Pattern.MULTILINE);
    private static final Pattern FRONT_MATTER_PATTERN = Pattern.compile("^---\\s*$", Pattern.MULTILINE);
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");

    private static final int CONTENT_PREVIEW_LIMIT = 10240; // 10 KB

    @Override
    public boolean canAnalyze(FileMetadata metadata) {
        return metadata.fileType() == FileUtils.FileType.MARKDOWN;
    }

    @Override
    public AnalysisResult analyze(FileMetadata metadata) throws IOException {
        String content = Files.readString(metadata.path());
        if (content.isEmpty()) {
            return AnalysisResult.empty();
        }

        // Extract headings
        List<String> headings = extractHeadings(content);

        // Extract links
        List<String> links = extractLinks(content);

        // Extract keywords (bold text, heading words)
        List<String> keywords = extractKeywords(content, headings);

        // Count structural elements
        int wordCount = countWords(content);
        int codeBlockCount = countCodeBlocks(content);
        boolean hasFrontMatter = hasFrontMatter(content);

        // Build summary from first heading or first paragraph
        String summary = buildSummary(content, headings);

        // Structure description
        String structure = String.format("%d headings, %d words, %d code blocks, %d links%s",
                headings.size(), wordCount, codeBlockCount, links.size(),
                hasFrontMatter ? ", front matter" : "");

        // Metrics
        Map<String, Object> metrics = Map.of(
                "wordCount", wordCount,
                "headingCount", headings.size(),
                "linkCount", links.size(),
                "codeBlockCount", codeBlockCount,
                "hasFrontMatter", hasFrontMatter
        );

        // Content preview (truncated for index)
        String preview = content.length() > CONTENT_PREVIEW_LIMIT
                ? content.substring(0, CONTENT_PREVIEW_LIMIT)
                : content;

        return AnalysisResult.builder()
                .summary(summary)
                .headings(headings)
                .keywords(keywords)
                .links(links)
                .structure(structure)
                .metrics(metrics)
                .contentPreview(preview)
                .build();
    }

    private List<String> extractHeadings(String content) {
        List<String> headings = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(content);
        while (matcher.find()) {
            headings.add(matcher.group(2).trim());
        }
        return headings;
    }

    private List<String> extractLinks(String content) {
        List<String> links = new ArrayList<>();
        Matcher matcher = LINK_PATTERN.matcher(content);
        while (matcher.find()) {
            links.add(matcher.group(2)); // URL/path
        }
        return links;
    }

    private List<String> extractKeywords(String content, List<String> headings) {
        Set<String> keywords = new LinkedHashSet<>();

        // Words from headings are high-value keywords
        for (String heading : headings) {
            for (String word : heading.split("\\s+")) {
                String clean = word.replaceAll("[^a-zA-Z0-9-]", "").toLowerCase();
                if (clean.length() > 2) {
                    keywords.add(clean);
                }
            }
        }

        // Bold text often contains key concepts
        Matcher boldMatcher = BOLD_PATTERN.matcher(content);
        while (boldMatcher.find()) {
            String bold = boldMatcher.group(1).trim().toLowerCase();
            if (bold.length() > 2 && bold.length() < 50) {
                keywords.add(bold);
            }
        }

        return new ArrayList<>(keywords);
    }

    private int countWords(String content) {
        if (content.isBlank()) return 0;
        // Strip markdown formatting for more accurate word count
        String stripped = content
                .replaceAll("```[\\s\\S]*?```", "") // remove code blocks
                .replaceAll("`[^`]+`", "")            // remove inline code
                .replaceAll("!?\\[[^\\]]*]\\([^)]*\\)", "") // remove links
                .replaceAll("#+ ", "")                 // remove heading markers
                .replaceAll("[*_~]+", "");             // remove emphasis markers
        return stripped.split("\\s+").length;
    }

    private int countCodeBlocks(String content) {
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(content);
        int count = 0;
        while (matcher.find()) count++;
        return count / 2; // Each code block has opening and closing ```
    }

    private boolean hasFrontMatter(String content) {
        return content.startsWith("---") && FRONT_MATTER_PATTERN.matcher(content).results().count() >= 2;
    }

    private String buildSummary(String content, List<String> headings) {
        // Use first heading as title if available
        if (!headings.isEmpty()) {
            return headings.get(0);
        }

        // Otherwise use first non-empty, non-heading line
        for (String line : content.split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("---")
                    && !line.startsWith("```") && !line.startsWith("|")) {
                return line.length() > 120 ? line.substring(0, 120) + "..." : line;
            }
        }

        return "";
    }
}
