package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Relationship mapping command. Shows how a file relates to other files
 * in the workspace through imports, references, and dependencies.
 *
 * <p>Usage:
 * <pre>
 *   synthesis relate PluginRegistry.java                # Find relationships
 *   synthesis relate src/main/Main.java --mermaid       # Output Mermaid diagram
 *   synthesis relate config.yaml --depth 2              # Follow references 2 levels
 * </pre>
 */
@Command(
        name = "relate",
        description = "Show file relationships, imports, and dependencies",
        mixinStandardHelpOptions = true
)
public class RelateCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(
            index = "0",
            description = "File name or path to analyze relationships for"
    )
    private String targetFile;

    @Option(
            names = {"--mermaid"},
            description = "Output as Mermaid diagram",
            defaultValue = "false"
    )
    private boolean mermaid;

    @Option(
            names = {"--depth"},
            description = "How many levels of relationships to follow (default: 1)",
            defaultValue = "1"
    )
    private int depth;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show detailed reference information",
            defaultValue = "false"
    )
    private boolean verbose;

    // Patterns for detecting file references in various languages
    private static final Pattern JAVA_IMPORT = Pattern.compile("^import\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern PYTHON_IMPORT = Pattern.compile("^(?:from\\s+(\\S+)\\s+import|import\\s+(\\S+))", Pattern.MULTILINE);
    private static final Pattern JS_TS_IMPORT = Pattern.compile("(?:import|require)\\s*\\(?['\"]([^'\"]+)['\"]\\)?", Pattern.MULTILINE);
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)]+)\\)", Pattern.MULTILINE);
    private static final Pattern YAML_REF = Pattern.compile("\\$ref:\\s*['\"]?([^'\"\\s]+)['\"]?", Pattern.MULTILINE);
    private static final Pattern GENERIC_FILE_REF = Pattern.compile("(?:['\"`])([\\w./-]+\\.(?:java|py|js|ts|md|yaml|yml|json|xml|go|rs|kt))['\"`]");

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            // Find the target file in the index
            List<SearchResult> targetResults;
            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                targetResults = index.search(targetFile, 10);
            }

            // Find best match
            SearchResult target = findBestMatch(targetResults, targetFile);
            if (target == null) {
                AnsiOutput.printError("File not found in index: " + targetFile);
                AnsiOutput.printInfo("Try 'synthesis search " + targetFile + "' to find it.");
                return 1;
            }

            // Analyze relationships
            RelationshipMap relationshipMap = new RelationshipMap(target.relativePath());

            // Get all indexed files for cross-referencing
            List<SearchResult> allFiles;
            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                allFiles = index.listAll(null, 5000);
            }

            // Build index of file names to relative paths
            Map<String, List<String>> fileNameIndex = new HashMap<>();
            for (SearchResult f : allFiles) {
                fileNameIndex.computeIfAbsent(f.fileName(), k -> new ArrayList<>()).add(f.relativePath());
            }

            // Phase 1: Find what this file imports/references
            analyzeOutgoingRefs(target, workspaceRoot, relationshipMap, fileNameIndex);

            // Phase 2: Find what references this file
            analyzeIncomingRefs(target, allFiles, workspaceRoot, relationshipMap);

            // Phase 3: Follow references for deeper analysis
            if (depth > 1) {
                Set<String> visited = new HashSet<>();
                visited.add(target.relativePath());
                deepenRelationships(relationshipMap, allFiles, workspaceRoot, fileNameIndex, visited, depth - 1);
            }

            // Output results
            if (mermaid) {
                System.out.println(generateMermaid(relationshipMap));
            } else {
                printRelationships(relationshipMap, target);
            }

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Relate failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Finds the best matching file from search results.
     */
    public SearchResult findBestMatch(List<SearchResult> results, String target) {
        if (results.isEmpty()) return null;

        // Exact path match first
        for (SearchResult r : results) {
            if (r.relativePath().equals(target) || r.relativePath().endsWith("/" + target)) {
                return r;
            }
        }

        // Exact filename match
        for (SearchResult r : results) {
            if (r.fileName().equals(target)) {
                return r;
            }
        }

        // Best search score
        return results.get(0);
    }

    /**
     * Analyzes outgoing references (what this file imports/references).
     */
    public void analyzeOutgoingRefs(SearchResult target, Path workspaceRoot,
                             RelationshipMap map, Map<String, List<String>> fileNameIndex) {
        try {
            Path filePath = target.path();
            if (!Files.exists(filePath) || !Files.isReadable(filePath)) return;

            String content = FileUtils.readPreview(filePath, 50_000);
            if (content.isEmpty()) return;

            Set<String> references = new LinkedHashSet<>();

            // Language-specific imports
            if ("Java".equals(target.language())) {
                extractMatches(JAVA_IMPORT, content, 1, references);
            } else if ("Python".equals(target.language())) {
                extractMatches(PYTHON_IMPORT, content, 1, references);
                extractMatches(PYTHON_IMPORT, content, 2, references);
            } else if ("JavaScript".equals(target.language()) || "TypeScript".equals(target.language())) {
                extractMatches(JS_TS_IMPORT, content, 1, references);
            }

            // Markdown links
            if ("MARKDOWN".equals(target.fileType())) {
                Matcher m = MARKDOWN_LINK.matcher(content);
                while (m.find()) {
                    String link = m.group(2);
                    if (!link.startsWith("http") && !link.startsWith("#")) {
                        references.add(link);
                    }
                }
            }

            // YAML $ref
            if ("YAML".equals(target.fileType())) {
                extractMatches(YAML_REF, content, 1, references);
            }

            // Generic file references
            extractMatches(GENERIC_FILE_REF, content, 1, references);

            // Resolve references to actual files in the index
            for (String ref : references) {
                String resolved = resolveReference(ref, target.relativePath(), fileNameIndex);
                if (resolved != null && !resolved.equals(target.relativePath())) {
                    map.addOutgoing(resolved, "imports/references");
                }
            }
        } catch (IOException e) {
            if (verbose) {
                AnsiOutput.printWarning("Could not read " + target.relativePath() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Analyzes incoming references (what references this file).
     */
    public void analyzeIncomingRefs(SearchResult target, List<SearchResult> allFiles,
                             Path workspaceRoot, RelationshipMap map) {
        String targetName = target.fileName();
        // Remove extension for import matching
        String targetBaseName = targetName.contains(".")
                ? targetName.substring(0, targetName.lastIndexOf('.'))
                : targetName;

        for (SearchResult file : allFiles) {
            if (file.relativePath().equals(target.relativePath())) continue;

            try {
                Path filePath = file.path();
                if (!Files.exists(filePath) || !Files.isReadable(filePath)) continue;

                String content = FileUtils.readPreview(filePath, 50_000);
                if (content.isEmpty()) continue;

                // Check if this file references the target
                boolean found = false;

                // Direct file name reference
                if (content.contains(targetName)) {
                    found = true;
                }

                // Import reference (for code files)
                if (!found && content.contains(targetBaseName)) {
                    // More specific check for import statements
                    if (content.contains("import") && content.contains(targetBaseName)) {
                        found = true;
                    }
                }

                // Markdown link to target
                if (!found && "MARKDOWN".equals(file.fileType())) {
                    if (content.contains(target.relativePath()) || content.contains(targetName)) {
                        found = true;
                    }
                }

                if (found) {
                    map.addIncoming(file.relativePath(), "references");
                }
            } catch (IOException e) {
                // Skip unreadable files
            }
        }
    }

    /**
     * Follows references deeper for multi-level relationship mapping.
     */
    private void deepenRelationships(RelationshipMap map, List<SearchResult> allFiles,
                                      Path workspaceRoot, Map<String, List<String>> fileNameIndex,
                                      Set<String> visited, int remainingDepth) {
        if (remainingDepth <= 0) return;

        Set<String> toVisit = new HashSet<>();
        toVisit.addAll(map.outgoing().keySet());
        toVisit.addAll(map.incoming().keySet());
        toVisit.removeAll(visited);

        for (String relPath : toVisit) {
            visited.add(relPath);
            SearchResult file = allFiles.stream()
                    .filter(f -> f.relativePath().equals(relPath))
                    .findFirst()
                    .orElse(null);
            if (file != null) {
                analyzeIncomingRefs(file, allFiles, workspaceRoot, map);
            }
        }
    }

    /**
     * Resolves a reference string to an actual file in the index.
     */
    public String resolveReference(String ref, String sourceRelPath,
                            Map<String, List<String>> fileNameIndex) {
        if (ref == null || ref.isBlank()) return null;

        // Clean up reference
        ref = ref.replace("\\", "/").trim();
        if (ref.startsWith("./")) ref = ref.substring(2);

        // Direct name match
        String fileName = ref.contains("/") ? ref.substring(ref.lastIndexOf('/') + 1) : ref;

        // First, try the fileName directly (handles "Config.java", "README.md", etc.)
        List<String> matches = fileNameIndex.get(fileName);
        if (matches != null && !matches.isEmpty()) {
            return matches.get(0);
        }

        // For Java imports (e.g., "com.example.Config"), convert to file name
        if (ref.contains(".") && !ref.contains("/") && !hasKnownExtension(ref)) {
            String[] parts = ref.split("\\.");
            fileName = parts[parts.length - 1] + ".java";
        }

        matches = fileNameIndex.get(fileName);
        if (matches != null && !matches.isEmpty()) {
            return matches.get(0);
        }

        // Try adding common extensions
        for (String ext : List.of(".java", ".py", ".js", ".ts", ".md")) {
            matches = fileNameIndex.get(fileName + ext);
            if (matches != null && !matches.isEmpty()) {
                return matches.get(0);
            }
        }

        return null;
    }

    /**
     * Checks if a reference string ends with a known file extension.
     */
    private static boolean hasKnownExtension(String ref) {
        String lower = ref.toLowerCase();
        return lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".js") ||
                lower.endsWith(".ts") || lower.endsWith(".md") || lower.endsWith(".yaml") ||
                lower.endsWith(".yml") || lower.endsWith(".json") || lower.endsWith(".xml") ||
                lower.endsWith(".go") || lower.endsWith(".rs") || lower.endsWith(".kt") ||
                lower.endsWith(".txt") || lower.endsWith(".sh") || lower.endsWith(".sql");
    }

    private void extractMatches(Pattern pattern, String content, int group, Set<String> results) {
        Matcher m = pattern.matcher(content);
        while (m.find()) {
            String match = m.group(group);
            if (match != null && !match.isBlank()) {
                results.add(match);
            }
        }
    }

    /**
     * Prints relationships in a human-readable format.
     */
    private void printRelationships(RelationshipMap map, SearchResult target) {
        System.out.println();
        System.out.println("  " + AnsiOutput.bold("Relationships for: " + target.relativePath()));
        if (target.language() != null) {
            System.out.println("  " + AnsiOutput.dim("Language: " + target.language() +
                    " | Type: " + target.fileType() +
                    " | Size: " + FileUtils.formatSize(target.sizeBytes())));
        }
        System.out.println();

        // Outgoing references
        if (!map.outgoing().isEmpty()) {
            System.out.println("  " + AnsiOutput.bold(AnsiOutput.cyan("Imports/References (outgoing):")) +
                    " " + map.outgoing().size() + " files");
            for (var entry : map.outgoing().entrySet()) {
                System.out.println("    " + AnsiOutput.green("->") + " " + entry.getKey() +
                        AnsiOutput.dim(" (" + entry.getValue() + ")"));
            }
            System.out.println();
        } else {
            System.out.println("  " + AnsiOutput.dim("No outgoing references found."));
            System.out.println();
        }

        // Incoming references
        if (!map.incoming().isEmpty()) {
            System.out.println("  " + AnsiOutput.bold(AnsiOutput.magenta("Referenced by (incoming):")) +
                    " " + map.incoming().size() + " files");
            for (var entry : map.incoming().entrySet()) {
                System.out.println("    " + AnsiOutput.yellow("<-") + " " + entry.getKey() +
                        AnsiOutput.dim(" (" + entry.getValue() + ")"));
            }
            System.out.println();
        } else {
            System.out.println("  " + AnsiOutput.dim("No incoming references found."));
            System.out.println();
        }

        // Summary
        int total = map.outgoing().size() + map.incoming().size();
        if (total == 0) {
            System.out.println("  " + AnsiOutput.yellow("This file appears to be orphaned (no references found)."));
        } else {
            System.out.println("  " + AnsiOutput.bold("Total connections: " + total));
        }
        System.out.println();
    }

    /**
     * Generates a Mermaid diagram of relationships.
     */
    public String generateMermaid(RelationshipMap map) {
        StringBuilder sb = new StringBuilder();
        sb.append("```mermaid\ngraph LR\n");

        String targetId = sanitizeMermaidId(map.targetFile());
        sb.append("    ").append(targetId).append("[\"").append(map.targetFile()).append("\"]\n");
        sb.append("    style ").append(targetId).append(" fill:#f9f,stroke:#333,stroke-width:2px\n");

        for (var entry : map.outgoing().entrySet()) {
            String id = sanitizeMermaidId(entry.getKey());
            sb.append("    ").append(id).append("[\"").append(entry.getKey()).append("\"]\n");
            sb.append("    ").append(targetId).append(" --> ").append(id).append("\n");
        }

        for (var entry : map.incoming().entrySet()) {
            String id = sanitizeMermaidId(entry.getKey());
            sb.append("    ").append(id).append("[\"").append(entry.getKey()).append("\"]\n");
            sb.append("    ").append(id).append(" --> ").append(targetId).append("\n");
        }

        sb.append("```\n");
        return sb.toString();
    }

    private String sanitizeMermaidId(String path) {
        return path.replaceAll("[^a-zA-Z0-9]", "_");
    }

    // --- Data types ---

    /**
     * Tracks bidirectional relationships for a target file.
     */
    public static class RelationshipMap {
        private final String targetFile;
        private final Map<String, String> outgoing = new LinkedHashMap<>();
        private final Map<String, String> incoming = new LinkedHashMap<>();

        public RelationshipMap(String targetFile) {
            this.targetFile = targetFile;
        }

        public void addOutgoing(String file, String type) {
            outgoing.put(file, type);
        }

        public void addIncoming(String file, String type) {
            incoming.put(file, type);
        }

        public String targetFile() { return targetFile; }
        public Map<String, String> outgoing() { return Collections.unmodifiableMap(outgoing); }
        public Map<String, String> incoming() { return Collections.unmodifiableMap(incoming); }
    }
}
