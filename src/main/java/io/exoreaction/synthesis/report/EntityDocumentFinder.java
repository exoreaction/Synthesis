package io.exoreaction.synthesis.report;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Discovers business documents relevant to a specific named entity (product or client).
 *
 * <p>Unlike {@link BusinessDocumentFinder} which does workspace-level discovery,
 * this finder targets a single named entity and gathers:
 *
 * <p><b>For products</b> (e.g., "Synthesis", "lib-pcb", "Xorcery AAA"):
 * <ul>
 *   <li>Source and docs directories matching the product name under {@code /src/exoreaction/}</li>
 *   <li>Business docs under {@code ~/Documents/eXOReaction/products/<product>/}</li>
 *   <li>Extracted mentions from workspace-level business docs (PIPELINE-STATUS, ACTIVITY-LOG)</li>
 * </ul>
 *
 * <p><b>For clients</b> (e.g., "Elprint", "Mynder", "SpareBank 1"):
 * <ul>
 *   <li>Active client directories under {@code ~/Documents/eXOReaction/clients/<client>/}</li>
 *   <li>Opportunity directories under {@code ~/Documents/eXOReaction/clients/opportunity-<client>/}</li>
 *   <li>Extracted mentions from PIPELINE-STATUS and ACTIVITY-LOG</li>
 * </ul>
 *
 * <p>Fuzzy name matching: "SpareBank 1" matches "SpareBank1", "opportunity-SpareBank1",
 * "sparebank1" (case-insensitive, dashes/spaces ignored).
 */
public class EntityDocumentFinder {

    private static final int MAX_ENTITY_DOCS = 8;
    private static final int MAX_CHARS_PER_DOC = 8000;
    /** Lines of context to capture around each entity mention in large docs. */
    private static final int MENTION_CONTEXT_LINES = 6;
    /** Maximum chars to extract from large docs via mention search. */
    private static final int MAX_MENTION_CHARS = 3000;

    /**
     * File name patterns to exclude from entity discovery.
     * Prevents reference/cheatsheet files from contaminating product reports.
     *
     * @see <a href="https://github.com/exoreaction/Synthesis/issues/52">#52</a>
     */
    private static final List<String> EXCLUDED_FILE_PATTERNS = List.of(
            "-gotchas.", ".notes.", "-cheatsheet.", "-reference."
    );

    // ---- Public API ----

    /**
     * Returns the root directory of a named entity within the workspace, without loading any files.
     *
     * <p>Used by {@code ReportCommand} to determine the auto-save location for co-located reports.
     *
     * @param workspaceRoot the workspace root (e.g., ~/Documents)
     * @param entityName    the entity name to locate
     * @param entityType    CLIENT or PRODUCT
     * @return the matching directory path, or empty if not found
     */
    public Optional<Path> findEntityRoot(Path workspaceRoot, String entityName, ReportTopic entityType) {
        Path searchRoot = entityType == ReportTopic.CLIENT
                ? workspaceRoot.resolve("eXOReaction/clients")
                : workspaceRoot.resolve("eXOReaction/products");

        if (!Files.isDirectory(searchRoot)) return Optional.empty();

        // For clients, also check opportunity- prefixed directories
        if (entityType == ReportTopic.CLIENT) {
            try (Stream<Path> stream = Files.list(searchRoot)) {
                Optional<Path> opportunityDir = stream.filter(Files::isDirectory)
                        .filter(dir -> {
                            String dirName = dir.getFileName().toString();
                            return dirName.startsWith("opportunity-")
                                    && fuzzyMatches(dirName.substring("opportunity-".length()), entityName);
                        })
                        .findFirst();
                if (opportunityDir.isPresent()) return opportunityDir;
            } catch (IOException e) {
                // fall through to direct name match
            }
        }

        return findMatchingDirs(searchRoot, entityName).stream().findFirst();
    }

    /**
     * Discovers documents for a named product.
     *
     * @param workspaceRoot the workspace root (e.g., ~/Documents)
     * @param productName   the product name to search for
     * @return list of relevant documents, sorted by last-modified (most recent first)
     */
    public List<ReportDocument> discoverForProduct(Path workspaceRoot, String productName) {
        List<ReportDocument> docs = new ArrayList<>();

        // 1. Source directories: /src/exoreaction/<product>/docs/
        Path srcRoot = Path.of("/src/exoreaction");
        if (Files.isDirectory(srcRoot)) {
            findMatchingDirs(srcRoot, productName).forEach(dir -> {
                collectDocFiles(dir, "product-source", docs);
            });
        }

        // 2. Business docs: ~/Documents/eXOReaction/products/<product>/
        Path productsDir = workspaceRoot.resolve("eXOReaction/products");
        if (Files.isDirectory(productsDir)) {
            findMatchingDirs(productsDir, productName).forEach(dir -> {
                collectDocFiles(dir, "product-business", docs);
            });
        }

        // 3. Extracted mentions from workspace-level business docs
        extractMentionsFromBusinessDocs(workspaceRoot, productName, "product-mention", docs);

        return dedupAndSort(docs);
    }

    /**
     * Discovers documents for a named client.
     *
     * @param workspaceRoot the workspace root (e.g., ~/Documents)
     * @param clientName    the client name to search for
     * @return list of relevant documents, sorted by last-modified (most recent first)
     */
    public List<ReportDocument> discoverForClient(Path workspaceRoot, String clientName) {
        List<ReportDocument> docs = new ArrayList<>();

        Path clientsDir = workspaceRoot.resolve("eXOReaction/clients");
        if (Files.isDirectory(clientsDir)) {
            // 1. Active client directories: clients/<ClientName>/
            findMatchingDirs(clientsDir, clientName).forEach(dir -> {
                collectDocFiles(dir, "client", docs);
            });

            // 2. Opportunity directories: clients/opportunity-<Name>/
            // (opportunity dirs are prefixed — find them with the same fuzzy match)
            try (Stream<Path> stream = Files.list(clientsDir)) {
                stream.filter(Files::isDirectory)
                        .filter(dir -> {
                            String dirName = dir.getFileName().toString();
                            return dirName.startsWith("opportunity-")
                                    && fuzzyMatches(dirName.substring("opportunity-".length()), clientName);
                        })
                        .forEach(dir -> collectDocFiles(dir, "opportunity", docs));
            } catch (IOException e) {
                // ignore
            }
        }

        // 3. Extracted mentions from workspace-level business docs
        extractMentionsFromBusinessDocs(workspaceRoot, clientName, "client-mention", docs);

        return dedupAndSort(docs);
    }

    // ---- Document collection ----

    private void collectDocFiles(Path dir, String category, List<ReportDocument> results) {
        try (Stream<Path> walker = Files.walk(dir, 3)) {
            walker.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".md") || name.endsWith(".txt")
                                || name.endsWith(".yaml") || name.endsWith(".yml");
                    })
                    .filter(p -> !p.toString().contains(".synthesis"))
                    .filter(p -> !p.toString().contains(".git"))
                    .filter(p -> !p.toString().contains("target/"))
                    .filter(p -> !p.toString().contains("node_modules"))
                    // Exclude reference/gotcha files that cause cross-contamination (#52)
                    .filter(p -> {
                        String nameLower = p.getFileName().toString().toLowerCase();
                        for (String pattern : EXCLUDED_FILE_PATTERNS) {
                            if (nameLower.contains(pattern)) return false;
                        }
                        return true;
                    })
                    .filter(p -> {
                        // For product-source, prefer docs/ and key files
                        if ("product-source".equals(category)) {
                            String s = p.toString();
                            String name = p.getFileName().toString();
                            String nameUpper = name.toUpperCase();
                            return s.contains("/docs/")
                                    || name.equalsIgnoreCase("README.md")
                                    || name.equalsIgnoreCase("CLAUDE.md")
                                    || nameUpper.contains("CHANGELOG")
                                    || nameUpper.contains("RELEASE-NOTES")    // #49
                                    || nameUpper.contains("RELEASE_NOTES")    // #49
                                    || nameUpper.contains("ROADMAP")           // #49
                                    || (nameUpper.contains("ACTIVITY") && nameUpper.contains("LOG")); // #49
                        }
                        return true;
                    })
                    .forEach(path -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                            String content = Files.readString(path);
                            if (content.length() > MAX_CHARS_PER_DOC) {
                                content = content.substring(0, MAX_CHARS_PER_DOC) + "\n\n[... truncated ...]";
                            }
                            // Use the path as-is for relative display
                            String relPath = path.toAbsolutePath().toString();
                            // Try to make it relative to home
                            Path home = Path.of(System.getProperty("user.home"));
                            try {
                                relPath = home.relativize(path.toAbsolutePath()).toString();
                            } catch (Exception e) {
                                // keep absolute
                            }
                            results.add(new ReportDocument(
                                    path.toAbsolutePath().normalize(),
                                    relPath,
                                    category,
                                    content,
                                    attrs.lastModifiedTime().toInstant(),
                                    attrs.size()
                            ));
                        } catch (IOException e) {
                            // skip unreadable files
                        }
                    });
        } catch (IOException e) {
            // skip unreadable directories
        }
    }

    /**
     * Extracts sections from workspace-level business docs (PIPELINE-STATUS, ACTIVITY-LOG)
     * that mention the entity name, with surrounding context.
     */
    private void extractMentionsFromBusinessDocs(Path workspaceRoot, String entityName,
                                                   String category, List<ReportDocument> results) {
        List<Path> candidates = new ArrayList<>();

        // Find PIPELINE-STATUS.md and ACTIVITY-LOG.md in workspace root
        try (Stream<Path> walker = Files.walk(workspaceRoot, 3)) {
            walker.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toUpperCase();
                        return name.startsWith("PIPELINE") || name.startsWith("ACTIVITY-LOG")
                                || name.startsWith("ACTIVITY_LOG") || name.startsWith("EXECUTIVE-UPDATE");
                    })
                    .filter(p -> !p.toString().contains(".synthesis"))
                    .filter(p -> !p.toString().contains(".git"))
                    .forEach(candidates::add);
        } catch (IOException e) {
            return;
        }

        for (Path docPath : candidates) {
            try {
                String fullContent = Files.readString(docPath);
                String extracted = extractMentions(fullContent, entityName);
                if (extracted == null || extracted.isBlank()) {
                    continue;
                }

                BasicFileAttributes attrs = Files.readAttributes(docPath, BasicFileAttributes.class);
                String fileName = docPath.getFileName().toString();
                String label = fileName + " (mentions of \"" + entityName + "\")";

                Path home = Path.of(System.getProperty("user.home"));
                String relPath;
                try {
                    relPath = home.relativize(docPath.toAbsolutePath()).toString() + " [extracted]";
                } catch (Exception e) {
                    relPath = docPath.toString() + " [extracted]";
                }

                results.add(new ReportDocument(
                        docPath.toAbsolutePath().normalize(),
                        relPath,
                        category,
                        extracted,
                        attrs.lastModifiedTime().toInstant(),
                        attrs.size()
                ));
            } catch (IOException e) {
                // skip
            }
        }
    }

    /**
     * Extracts lines mentioning the entity from a document, with surrounding context.
     * Returns null if no mentions found.
     */
    private String extractMentions(String content, String entityName) {
        String[] lines = content.split("\n");
        String nameLower = entityName.toLowerCase()
                .replace(" ", "").replace("-", "").replace("_", "");

        Set<Integer> includedLines = new TreeSet<>();
        for (int i = 0; i < lines.length; i++) {
            String normalized = lines[i].toLowerCase()
                    .replace(" ", "").replace("-", "").replace("_", "");
            if (normalized.contains(nameLower)) {
                // Include context around this line
                for (int j = Math.max(0, i - MENTION_CONTEXT_LINES);
                     j <= Math.min(lines.length - 1, i + MENTION_CONTEXT_LINES); j++) {
                    includedLines.add(j);
                }
            }
        }

        if (includedLines.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        int prev = -2;
        for (int idx : includedLines) {
            if (idx > prev + 1) {
                sb.append("\n---\n");
            }
            sb.append(lines[idx]).append("\n");
            prev = idx;
            if (sb.length() > MAX_MENTION_CHARS) {
                sb.append("\n[... more mentions omitted ...]");
                break;
            }
        }

        return sb.toString().strip();
    }

    // ---- Directory matching ----

    /**
     * Finds immediate subdirectories of {@code parent} whose names fuzzy-match {@code entityName}.
     */
    private List<Path> findMatchingDirs(Path parent, String entityName) {
        try (Stream<Path> stream = Files.list(parent)) {
            return stream.filter(Files::isDirectory)
                    .filter(dir -> fuzzyMatches(dir.getFileName().toString(), entityName))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Case-insensitive fuzzy match: ignores dashes, underscores, and spaces.
     * e.g., "SpareBank 1" matches "SpareBank1", "sparebank-1", "SPAREBANK1"
     *
     * <p>Requires both normalized names to be at least 3 characters to avoid
     * false positives from trivially short directory names (e.g., "t", "src").
     */
    static boolean fuzzyMatches(String dirName, String entityName) {
        String normalDir = dirName.toLowerCase()
                .replace("-", "").replace("_", "").replace(" ", "");
        String normalEntity = entityName.toLowerCase()
                .replace("-", "").replace("_", "").replace(" ", "");
        if (normalDir.length() < 3 || normalEntity.length() < 3) return false;
        return normalDir.contains(normalEntity) || normalEntity.contains(normalDir);
    }

    // ---- Sorting & dedup ----

    private List<ReportDocument> dedupAndSort(List<ReportDocument> docs) {
        Map<Path, ReportDocument> seen = new LinkedHashMap<>();
        for (ReportDocument doc : docs) {
            seen.putIfAbsent(doc.path(), doc);
        }
        return seen.values().stream()
                .sorted(Comparator.comparing(ReportDocument::lastModified).reversed())
                .limit(MAX_ENTITY_DOCS)
                .collect(Collectors.toList());
    }
}
