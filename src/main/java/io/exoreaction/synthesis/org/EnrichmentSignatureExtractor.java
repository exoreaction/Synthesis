package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.enrichment.CompanionFileGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a semantic {@link EnrichmentSignature} from a file.
 *
 * <p>Uses a three-tier extraction strategy (in priority order):
 * <ol>
 *   <li><b>Companion file</b> ({@code filename.ext.synthesis.md}): parses the companion
 *       content for type, keywords, AI-generated descriptions, and entity references.</li>
 *   <li><b>Content header</b>: for text files (Markdown), reads the first 50 lines
 *       looking for headings and keyword patterns.</li>
 *   <li><b>Filename heuristic</b>: tokenizes the filename using the same approach as
 *       {@link DirectoryScorer#tokenize(String)}.</li>
 * </ol>
 *
 * <p>Returns {@link EnrichmentSignature#empty()} when no signals are found.
 */
public class EnrichmentSignatureExtractor {

    /** Pattern for YAML-like key: value lines in companion files. */
    private static final Pattern YAML_KV = Pattern.compile("^(\\w[\\w_]*):\\s*(.+)$");

    /** Pattern for Markdown headings. */
    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,3}\\s+(.+)$");

    /** Pattern for Keywords/Tags lines (common in companion metadata). */
    private static final Pattern KEYWORDS_PATTERN =
            Pattern.compile("^\\*\\*(?:Keywords?|Tags?):\\*\\*\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    /** Pattern for organizations (capitalized multi-word names).
     *  Each word starts with uppercase and may contain mixed case (e.g. "GreenField Energy"). */
    private static final Pattern ENTITY_PATTERN =
            Pattern.compile("\\b([A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)+)\\b");

    /** Known document type keywords that can be inferred from content. */
    private static final Set<String> DOCUMENT_TYPE_KEYWORDS = Set.of(
            "proposal", "contract", "invoice", "report", "presentation",
            "meeting-notes", "meeting notes", "minutes", "guide", "tutorial",
            "plan", "strategy", "analysis", "review", "brief", "memo",
            "specification", "requirements", "design", "architecture"
    );

    /**
     * Extracts an enrichment signature from the given file.
     *
     * @param file          the file to extract from
     * @param workspaceRoot the workspace root for relative path computation
     * @return the enrichment signature, or {@link EnrichmentSignature#empty()} if no signals found
     */
    public EnrichmentSignature extract(Path file, Path workspaceRoot) {
        if (file == null || !Files.exists(file) || Files.isDirectory(file)) {
            return EnrichmentSignature.empty();
        }

        // Tier 1: Try companion file
        EnrichmentSignature fromCompanion = extractFromCompanion(file);
        if (fromCompanion != null && !fromCompanion.isEmpty()) {
            return fromCompanion;
        }

        // Tier 2: For text files, try reading content headers
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".md") || fileName.endsWith(".txt")) {
            EnrichmentSignature fromContent = extractFromContentHeaders(file);
            if (fromContent != null && !fromContent.isEmpty()) {
                return fromContent;
            }
        }

        // Tier 3: Filename heuristic
        return extractFromFilename(file);
    }

    /**
     * Extracts an enrichment signature from a companion file.
     *
     * @param originalFile the original file (the companion is at originalFile.synthesis.md)
     * @return signature extracted from companion, or null if no companion or no useful data
     */
    EnrichmentSignature extractFromCompanion(Path originalFile) {
        Path companionPath = CompanionFileGenerator.companionPathFor(originalFile);
        if (!Files.exists(companionPath)) {
            return null;
        }

        try {
            String content = Files.readString(companionPath);
            return parseCompanionContent(content);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Parses companion file content to extract topics, entities, document type.
     */
    EnrichmentSignature parseCompanionContent(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        List<String> topics = new ArrayList<>();
        List<String> entities = new ArrayList<>();
        String documentType = null;
        Set<String> entitySet = new LinkedHashSet<>();

        String[] lines = content.split("\n");
        boolean inYamlBlock = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Track YAML code blocks in companion files
            if (trimmed.startsWith("```yaml")) {
                inYamlBlock = true;
                continue;
            }
            if (trimmed.startsWith("```") && inYamlBlock) {
                inYamlBlock = false;
                continue;
            }

            // Extract type from YAML block
            if (inYamlBlock) {
                Matcher kv = YAML_KV.matcher(trimmed);
                if (kv.matches()) {
                    String key = kv.group(1).toLowerCase(Locale.ROOT);
                    String value = kv.group(2).trim();
                    if ("type".equals(key)) {
                        documentType = normalizeDocumentType(value);
                    }
                }
                continue;
            }

            // Extract keywords
            Matcher keywordsMatcher = KEYWORDS_PATTERN.matcher(trimmed);
            if (keywordsMatcher.matches()) {
                String keywordsStr = keywordsMatcher.group(1);
                for (String kw : keywordsStr.split("[,;]")) {
                    String cleaned = kw.trim().toLowerCase(Locale.ROOT);
                    if (!cleaned.isEmpty() && cleaned.length() >= 3) {
                        topics.add(cleaned);
                    }
                }
                continue;
            }

            // Extract headings as topic candidates
            Matcher headingMatcher = HEADING_PATTERN.matcher(trimmed);
            if (headingMatcher.matches()) {
                String heading = headingMatcher.group(1).trim();
                // Skip very short or metadata headings
                if (heading.length() >= 3 && !heading.startsWith("[") && !heading.contains("```")) {
                    // Check for document type in heading
                    for (String dtype : DOCUMENT_TYPE_KEYWORDS) {
                        if (heading.toLowerCase(Locale.ROOT).contains(dtype)) {
                            if (documentType == null) {
                                documentType = dtype;
                            }
                        }
                    }
                }
            }

            // Extract entities (capitalized multi-word names)
            Matcher entityMatcher = ENTITY_PATTERN.matcher(trimmed);
            while (entityMatcher.find()) {
                String entity = entityMatcher.group(1);
                // Filter common false positives
                if (!isCommonPhrase(entity) && entity.length() >= 4) {
                    entitySet.add(entity);
                }
            }
        }

        entities.addAll(entitySet);

        if (topics.isEmpty() && entities.isEmpty() && documentType == null) {
            return null;
        }

        return new EnrichmentSignature(
                List.copyOf(topics),
                List.copyOf(entities),
                documentType,
                null, // timeframe extracted elsewhere
                "companion"
        );
    }

    /**
     * Extracts topics from the first N lines of a text file (headings, keywords).
     */
    EnrichmentSignature extractFromContentHeaders(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            int maxLines = Math.min(lines.size(), 50);

            List<String> topics = new ArrayList<>();
            Set<String> entitySet = new LinkedHashSet<>();
            String documentType = null;

            for (int i = 0; i < maxLines; i++) {
                String line = lines.get(i).trim();

                // Extract headings as topics
                Matcher headingMatcher = HEADING_PATTERN.matcher(line);
                if (headingMatcher.matches()) {
                    String heading = headingMatcher.group(1).trim().toLowerCase(Locale.ROOT);
                    if (heading.length() >= 3) {
                        // Check for document type
                        for (String dtype : DOCUMENT_TYPE_KEYWORDS) {
                            if (heading.contains(dtype)) {
                                if (documentType == null) {
                                    documentType = dtype;
                                }
                            }
                        }
                        // Add significant heading words as topics
                        for (String token : heading.split("[-_\\s:]+")) {
                            if (token.length() >= 3 && !isStopWord(token)) {
                                topics.add(token);
                            }
                        }
                    }
                }

                // Extract entities from body text
                Matcher entityMatcher = ENTITY_PATTERN.matcher(line);
                while (entityMatcher.find()) {
                    String entity = entityMatcher.group(1);
                    if (!isCommonPhrase(entity) && entity.length() >= 4) {
                        entitySet.add(entity);
                    }
                }
            }

            List<String> entities = new ArrayList<>(entitySet);

            if (topics.isEmpty() && entities.isEmpty() && documentType == null) {
                return null;
            }

            // Deduplicate and limit
            List<String> dedupedTopics = new ArrayList<>(new LinkedHashSet<>(topics));
            if (dedupedTopics.size() > 10) {
                dedupedTopics = dedupedTopics.subList(0, 10);
            }
            if (entities.size() > 5) {
                entities = entities.subList(0, 5);
            }

            return new EnrichmentSignature(
                    List.copyOf(dedupedTopics),
                    List.copyOf(entities),
                    documentType,
                    null,
                    "content-headers"
            );
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Extracts a minimal signature from the filename itself.
     * Uses the same tokenization approach as {@link DirectoryScorer#tokenize(String)}.
     */
    EnrichmentSignature extractFromFilename(Path file) {
        String fileName = file.getFileName().toString();

        // Strip extension
        int lastDot = fileName.lastIndexOf('.');
        String nameWithoutExt = lastDot > 0 ? fileName.substring(0, lastDot) : fileName;

        Set<String> tokens = DirectoryScorer.tokenize(nameWithoutExt);

        if (tokens.isEmpty()) {
            return EnrichmentSignature.empty();
        }

        // Check for document type keywords in tokens
        String documentType = null;
        for (String token : tokens) {
            if (DOCUMENT_TYPE_KEYWORDS.contains(token)) {
                documentType = token;
                break;
            }
        }

        // Check for date patterns to infer timeframe
        String timeframe = inferTimeframeFromFilename(fileName);

        // All tokens become low-confidence topics
        List<String> topics = new ArrayList<>(tokens);

        return new EnrichmentSignature(
                List.copyOf(topics),
                List.of(), // no entities from filename alone
                documentType,
                timeframe,
                "filename-heuristic"
        );
    }

    /**
     * Infers a timeframe from date patterns in a filename.
     * Recognizes patterns like: 2026-Q1, 2026-02, Q1-2026, etc.
     */
    static String inferTimeframeFromFilename(String fileName) {
        // Pattern: 2026-Q1 or Q1-2026
        Pattern quarterPattern = Pattern.compile("(20\\d{2})[_-]?Q([1-4])|Q([1-4])[_-]?(20\\d{2})");
        Matcher m = quarterPattern.matcher(fileName);
        if (m.find()) {
            String year = m.group(1) != null ? m.group(1) : m.group(4);
            String quarter = m.group(2) != null ? m.group(2) : m.group(3);
            return year + "-Q" + quarter;
        }

        // Pattern: 2026-02 (year-month)
        Pattern yearMonthPattern = Pattern.compile("(20\\d{2})[_-](0[1-9]|1[0-2])");
        m = yearMonthPattern.matcher(fileName);
        if (m.find()) {
            int month = Integer.parseInt(m.group(2));
            int quarter = (month - 1) / 3 + 1;
            return m.group(1) + "-Q" + quarter;
        }

        return null;
    }

    /**
     * Normalizes a document type string to a canonical form.
     */
    private static String normalizeDocumentType(String type) {
        if (type == null) return null;
        String lower = type.toLowerCase(Locale.ROOT).trim();
        // Strip quotes
        if (lower.startsWith("\"") && lower.endsWith("\"")) {
            lower = lower.substring(1, lower.length() - 1);
        }
        return switch (lower) {
            case "video", "audio", "image", "pdf" -> lower;
            case "markdown", "md" -> "document";
            default -> lower;
        };
    }

    /** Common phrases that look like entities but are not. */
    private static final Set<String> COMMON_PHRASES = Set.of(
            "The", "This", "That", "These", "Those",
            "New York", "United States", "Last Updated",
            "Table Of", "See Also", "For More", "How To",
            "Note That", "Make Sure", "Keep In"
    );

    private static boolean isCommonPhrase(String phrase) {
        return COMMON_PHRASES.contains(phrase);
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "this", "that",
            "are", "was", "were", "been", "have", "has", "had",
            "not", "but", "all", "any", "can", "did", "get",
            "will", "new", "use", "how", "its", "our", "you"
    );

    private static boolean isStopWord(String word) {
        return STOP_WORDS.contains(word.toLowerCase(Locale.ROOT));
    }
}
