package io.exoreaction.synthesis.validate;

import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects documentation drift by cross-referencing CamelCase identifiers
 * found in skill/doc files against the Lucene index of the workspace.
 *
 * <p>An identifier is flagged as drifted when it looks like a Java class name
 * (CamelCase, ≥ 5 chars, not a known stopword) but produces zero results in
 * the indexed codebase.
 *
 * <p>The check is two-stage for robustness:
 * <ol>
 *   <li><em>Filename check</em> — {@code filename:Foo} with a CODE filter finds
 *       {@code Foo.java} directly in the index.</li>
 *   <li><em>Content fallback</em> — {@code Foo} across all CODE content, which
 *       also catches inner classes and annotation types.</li>
 * </ol>
 */
public class DriftDetector {

    /**
     * CamelCase identifier pattern: at least two "Uppercase + lowercase+" groups.
     * <ul>
     *   <li>Matches: {@code SearchService}, {@code ActivityLogUpdater}, {@code McpCommand}</li>
     *   <li>Does NOT match: {@code Synthesis} (single group), {@code API} (all-caps acronym)</li>
     * </ul>
     */
    static final Pattern CAMEL_CASE = Pattern.compile("\\b([A-Z][a-z]+(?:[A-Z][a-z]*)+)\\b");

    /** Minimum identifier length to suppress noise (e.g. "NaN", "Ok"). */
    static final int MIN_LENGTH = 5;

    /**
     * Known false positives — external brands, algorithms, and tech concepts
     * that look like CamelCase Java class names but are not.
     */
    static final Set<String> STOPWORDS = Set.of(
            // External services / brands
            "GitHub", "GitLab", "LinkedIn", "YouTube", "Kubernetes", "Docker",
            "Jenkins", "Slack", "Jira", "Confluence", "ChatGpt", "OpenAi",
            "NotebookLm", "IntelliJ", "SpringBoot",
            // Technical concepts / algorithms
            "CamelCase", "Levenshtein",
            // Languages / frameworks (rarely Java class names in a typical project)
            "JavaScript", "TypeScript", "NoSql", "GraphQl"
    );

    /**
     * Scans {@code docFile} for CamelCase identifiers and checks each one
     * against the Lucene index.
     *
     * @param docFile the skill / documentation file to analyse
     * @param index   read-only (or write) SearchIndex for the workspace
     * @return drift issues found; empty list if all identifiers are verified
     * @throws IOException if the file cannot be read
     */
    public List<DriftIssue> detect(Path docFile, SearchIndex index) throws IOException {
        List<String> lines = Files.readAllLines(docFile);

        // 1. Collect all unique identifiers present in the file
        Set<String> candidates = new LinkedHashSet<>();
        for (String line : lines) {
            for (String id : extractCamelCaseIdentifiers(line)) {
                candidates.add(id);
            }
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        // 2. Check which identifiers are absent from the index (one query each)
        Set<String> missing = new LinkedHashSet<>();
        for (String id : candidates) {
            try {
                if (!existsInIndex(id, index)) {
                    missing.add(id);
                }
            } catch (IOException e) {
                // If an index query fails, skip rather than false-positive
            }
        }

        if (missing.isEmpty()) {
            return List.of();
        }

        // 3. Report the exact line(s) where each missing identifier appears
        List<DriftIssue> issues = new ArrayList<>();
        for (int lineNum = 1; lineNum <= lines.size(); lineNum++) {
            String line = lines.get(lineNum - 1);
            Set<String> alreadyReportedOnLine = new HashSet<>();
            for (String id : extractCamelCaseIdentifiers(line)) {
                if (missing.contains(id) && alreadyReportedOnLine.add(id)) {
                    issues.add(new DriftIssue(docFile, lineNum, id));
                }
            }
        }

        return issues;
    }

    /**
     * Extracts CamelCase identifiers from a single line of text.
     * Filters by {@link #MIN_LENGTH} and {@link #STOPWORDS}.
     *
     * @param line one line of text
     * @return ordered set of unique identifiers found
     */
    static Set<String> extractCamelCaseIdentifiers(String line) {
        Matcher m = CAMEL_CASE.matcher(line);
        Set<String> ids = new LinkedHashSet<>();
        while (m.find()) {
            String id = m.group(1);
            if (id.length() >= MIN_LENGTH && !STOPWORDS.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Returns {@code true} if the identifier appears anywhere in the indexed
     * codebase (CODE-type documents).
     *
     * <p>Stage 1: searches the {@code filename} field — finds a file named
     * {@code <identifier>.*} in the code index.<br>
     * Stage 2 fallback: broad content search in CODE files — catches inner classes
     * and annotation types that don't have their own top-level file.
     */
    boolean existsInIndex(String identifier, SearchIndex index) throws IOException {
        // Stage 1: filename-based (highest precision)
        List<SearchResult> byFilename = index.search("filename:" + identifier, "CODE", 1);
        if (!byFilename.isEmpty()) {
            return true;
        }

        // Stage 2: content fallback (catches inner classes, annotations)
        List<SearchResult> byContent = index.search(identifier, "CODE", 1);
        return !byContent.isEmpty();
    }

    /**
     * A single drift issue: an identifier at a specific line in a file that
     * could not be found in the indexed codebase.
     *
     * @param file       the documentation/skill file containing the identifier
     * @param line       1-based line number
     * @param identifier the CamelCase identifier that is missing from the index
     */
    public record DriftIssue(Path file, int line, String identifier) {}
}
