package io.exoreaction.synthesis.report;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Discovers business documents in a workspace for report generation.
 *
 * <p>Searches for pipeline status files, activity logs, event files,
 * strategy documents, and executive updates using heuristic file name
 * matching. Results are sorted by last-modified time (most recent first)
 * and limited to prevent context overflow.
 *
 * <p>This is NOT a code analysis tool. It understands business document
 * naming conventions and organizational patterns.
 */
public class BusinessDocumentFinder {

    /** Maximum number of documents to return in total. */
    private static final int MAX_TOTAL_DOCS = 10;

    /** Maximum characters per document content. */
    private static final int DEFAULT_MAX_CHARS_PER_DOC = 8000;

    // Search patterns for each document category
    private static final List<String> PIPELINE_PATTERNS = List.of(
            "PIPELINE-STATUS", "PIPELINE", "pipeline-status", "pipeline"
    );

    private static final List<String> ACTIVITY_PATTERNS = List.of(
            "ACTIVITY-LOG", "activity-log", "ACTIVITY_LOG", "activity_log"
    );

    private static final List<String> EVENT_PATTERNS = List.of(
            "events"
    );

    private static final List<String> STRATEGY_PATTERNS = List.of(
            "strategy", "analysis", "STRATEGY", "ANALYSIS"
    );

    private static final List<String> EXECUTIVE_PATTERNS = List.of(
            "EXECUTIVE-UPDATE", "SELINA", "executive-update",
            "EXECUTIVE_UPDATE", "executive_update"
    );

    private final int maxCharsPerDoc;

    public BusinessDocumentFinder() {
        this(DEFAULT_MAX_CHARS_PER_DOC);
    }

    public BusinessDocumentFinder(int maxCharsPerDoc) {
        this.maxCharsPerDoc = maxCharsPerDoc;
    }

    /**
     * Discovers business documents in the workspace relevant to the given topic.
     *
     * @param workspaceRoot the workspace root directory
     * @param topic         the report topic (determines which document categories to search)
     * @return list of discovered documents, sorted by last-modified (most recent first)
     */
    public List<ReportDocument> discover(Path workspaceRoot, ReportTopic topic) {
        List<ReportDocument> allDocs = new ArrayList<>();

        try {
            switch (topic) {
                case PIPELINE:
                    allDocs.addAll(findByCategory(workspaceRoot, "pipeline", PIPELINE_PATTERNS));
                    break;
                case ACTIVITIES:
                    allDocs.addAll(findByCategory(workspaceRoot, "activity", ACTIVITY_PATTERNS));
                    allDocs.addAll(findByCategory(workspaceRoot, "event", EVENT_PATTERNS));
                    break;
                case DECISIONS:
                    allDocs.addAll(findByCategory(workspaceRoot, "pipeline", PIPELINE_PATTERNS));
                    allDocs.addAll(findByCategory(workspaceRoot, "activity", ACTIVITY_PATTERNS));
                    allDocs.addAll(findByCategory(workspaceRoot, "strategy", STRATEGY_PATTERNS));
                    break;
                case WEEKLY:
                case EXECUTIVE:
                default:
                    // Full scan: all categories
                    allDocs.addAll(findByCategory(workspaceRoot, "pipeline", PIPELINE_PATTERNS));
                    allDocs.addAll(findByCategory(workspaceRoot, "activity", ACTIVITY_PATTERNS));
                    allDocs.addAll(findByCategory(workspaceRoot, "event", EVENT_PATTERNS));
                    allDocs.addAll(findByCategory(workspaceRoot, "strategy", STRATEGY_PATTERNS));
                    allDocs.addAll(findByCategory(workspaceRoot, "executive", EXECUTIVE_PATTERNS));
                    break;
            }
        } catch (IOException e) {
            System.err.println("Warning: Error discovering business documents: " + e.getMessage());
        }

        // Deduplicate by path
        Map<Path, ReportDocument> deduplicated = new LinkedHashMap<>();
        for (ReportDocument doc : allDocs) {
            deduplicated.putIfAbsent(doc.path(), doc);
        }

        // Sort by last-modified (most recent first) and limit
        return deduplicated.values().stream()
                .sorted(Comparator.comparing(ReportDocument::lastModified).reversed())
                .limit(MAX_TOTAL_DOCS)
                .collect(Collectors.toList());
    }

    /**
     * Generates a fingerprint from discovered documents for cache invalidation.
     * The fingerprint is a hash of all document paths and their last-modified timestamps.
     *
     * @param documents the discovered documents
     * @return a fingerprint string
     */
    public static String generateFingerprint(List<ReportDocument> documents) {
        if (documents == null || documents.isEmpty()) return "empty";

        StringBuilder sb = new StringBuilder();
        documents.stream()
                .sorted(Comparator.comparing(d -> d.path().toString()))
                .forEach(doc -> {
                    sb.append(doc.path().toString());
                    sb.append(':');
                    sb.append(doc.lastModified().toEpochMilli());
                    sb.append(';');
                });

        // Simple hash
        return Integer.toHexString(sb.toString().hashCode());
    }

    /**
     * Finds documents matching the given category patterns.
     */
    private List<ReportDocument> findByCategory(Path workspaceRoot, String category,
                                                  List<String> patterns) throws IOException {
        List<ReportDocument> results = new ArrayList<>();

        // Walk the workspace looking for matching files
        try (Stream<Path> walker = Files.walk(workspaceRoot, 6)) {
            List<Path> candidates = walker
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains(".synthesis"))
                    .filter(p -> !p.toString().contains(".git"))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().contains("target/"))
                    .filter(p -> matchesPatterns(p, patterns, category))
                    .collect(Collectors.toList());

            for (Path path : candidates) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);

                    // Only process readable text files (markdown, text)
                    String fileName = path.getFileName().toString().toLowerCase();
                    if (!fileName.endsWith(".md") && !fileName.endsWith(".txt")
                            && !fileName.endsWith(".yaml") && !fileName.endsWith(".yml")) {
                        continue;
                    }

                    String content = Files.readString(path);
                    if (content.length() > maxCharsPerDoc) {
                        content = content.substring(0, maxCharsPerDoc) + "\n\n[... truncated ...]";
                    }

                    String relativePath = workspaceRoot.relativize(path).toString();

                    results.add(new ReportDocument(
                            path.toAbsolutePath().normalize(),
                            relativePath,
                            category,
                            content,
                            attrs.lastModifiedTime().toInstant(),
                            attrs.size()
                    ));
                } catch (IOException e) {
                    // Skip unreadable files
                }
            }
        }

        return results;
    }

    /**
     * Checks if a path matches any of the given patterns for a category.
     */
    private boolean matchesPatterns(Path path, List<String> patterns, String category) {
        String fileName = path.getFileName().toString();
        String fullPath = path.toString();

        // For events category, check if file is inside an events/ directory
        if ("event".equals(category)) {
            return fullPath.contains("/events/") && fileName.endsWith(".md");
        }

        // For strategy category, check if file is in a strategy/ or analysis/ directory
        if ("strategy".equals(category)) {
            return (fullPath.contains("/business/strategy/") || fullPath.contains("/business/analysis/"))
                    && (fileName.endsWith(".md") || fileName.endsWith(".txt"));
        }

        // For other categories, check if the filename contains any pattern
        for (String pattern : patterns) {
            if (fileName.contains(pattern)) {
                return true;
            }
        }

        return false;
    }
}
