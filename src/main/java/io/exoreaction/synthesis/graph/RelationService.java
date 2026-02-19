package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core relationship analysis engine.
 *
 * <p>Extracts the non-CLI logic from the former {@code RelateCommand} so that
 * service-layer callers ({@code CodeExplainer}, {@code ArchitectureMonitor},
 * {@code SynthesisToolHandler}, {@code SynthesisTextDocumentService}) can
 * analyse file relationships without depending on the CLI layer.
 */
public class RelationService {

    private static final Pattern JAVA_IMPORT = Pattern.compile("^import\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern PYTHON_IMPORT = Pattern.compile("^(?:from\\s+(\\S+)\\s+import|import\\s+(\\S+))", Pattern.MULTILINE);
    private static final Pattern JS_TS_IMPORT = Pattern.compile("(?:import|require)\\s*\\(?['\"]([^'\"]+)['\"]\\)?", Pattern.MULTILINE);
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)]+)\\)", Pattern.MULTILINE);
    private static final Pattern YAML_REF = Pattern.compile("\\$ref:\\s*['\"]?([^'\"\\s]+)['\"]?", Pattern.MULTILINE);
    private static final Pattern GENERIC_FILE_REF = Pattern.compile("(?:['\"`])([\\w./-]+\\.(?:java|py|js|ts|md|yaml|yml|json|xml|go|rs|kt))['\"`]");

    public SearchResult findBestMatch(List<SearchResult> results, String target) {
        if (results.isEmpty()) return null;
        for (SearchResult r : results) {
            if (r.relativePath().equals(target) || r.relativePath().endsWith("/" + target)) return r;
        }
        for (SearchResult r : results) {
            if (r.fileName().equals(target)) return r;
        }
        return results.get(0);
    }

    public void analyzeOutgoingRefs(SearchResult target, Path workspaceRoot,
                                     RelationshipMap map, Map<String, List<String>> fileNameIndex) {
        try {
            Path filePath = target.path();
            if (!Files.exists(filePath) || !Files.isReadable(filePath)) return;
            String content = FileUtils.readPreview(filePath, 50_000);
            if (content.isEmpty()) return;

            Set<String> references = new LinkedHashSet<>();
            if ("Java".equals(target.language())) {
                extractMatches(JAVA_IMPORT, content, 1, references);
            } else if ("Python".equals(target.language())) {
                extractMatches(PYTHON_IMPORT, content, 1, references);
                extractMatches(PYTHON_IMPORT, content, 2, references);
            } else if ("JavaScript".equals(target.language()) || "TypeScript".equals(target.language())) {
                extractMatches(JS_TS_IMPORT, content, 1, references);
            }
            if ("MARKDOWN".equals(target.fileType())) {
                Matcher m = MARKDOWN_LINK.matcher(content);
                while (m.find()) {
                    String link = m.group(2);
                    if (!link.startsWith("http") && !link.startsWith("#")) references.add(link);
                }
            }
            if ("YAML".equals(target.fileType())) {
                extractMatches(YAML_REF, content, 1, references);
            }
            extractMatches(GENERIC_FILE_REF, content, 1, references);

            for (String ref : references) {
                String resolved = resolveReference(ref, target.relativePath(), fileNameIndex);
                if (resolved != null && !resolved.equals(target.relativePath())) {
                    map.addOutgoing(resolved, "imports/references");
                }
            }
        } catch (IOException e) {
            // Skip unreadable files
        }
    }

    public void analyzeIncomingRefs(SearchResult target, List<SearchResult> allFiles,
                                     Path workspaceRoot, RelationshipMap map) {
        String targetName = target.fileName();
        String targetBaseName = targetName.contains(".")
                ? targetName.substring(0, targetName.lastIndexOf('.')) : targetName;

        for (SearchResult file : allFiles) {
            if (file.relativePath().equals(target.relativePath())) continue;
            try {
                Path filePath = file.path();
                if (!Files.exists(filePath) || !Files.isReadable(filePath)) continue;
                String content = FileUtils.readPreview(filePath, 50_000);
                if (content.isEmpty()) continue;

                boolean found = false;
                if (content.contains(targetName)) found = true;
                if (!found && content.contains(targetBaseName)) {
                    if (content.contains("import") && content.contains(targetBaseName)) found = true;
                }
                if (!found && "MARKDOWN".equals(file.fileType())) {
                    if (content.contains(target.relativePath()) || content.contains(targetName)) found = true;
                }
                if (found) map.addIncoming(file.relativePath(), "references");
            } catch (IOException e) {
                // Skip
            }
        }
    }

    public String resolveReference(String ref, String sourceRelPath,
                                    Map<String, List<String>> fileNameIndex) {
        if (ref == null || ref.isBlank()) return null;
        ref = ref.replace("\\", "/").trim();
        if (ref.startsWith("./")) ref = ref.substring(2);
        String fileName = ref.contains("/") ? ref.substring(ref.lastIndexOf('/') + 1) : ref;

        List<String> matches = fileNameIndex.get(fileName);
        if (matches != null && !matches.isEmpty()) return matches.get(0);

        if (ref.contains(".") && !ref.contains("/") && !hasKnownExtension(ref)) {
            String[] parts = ref.split("\\.");
            fileName = parts[parts.length - 1] + ".java";
        }
        matches = fileNameIndex.get(fileName);
        if (matches != null && !matches.isEmpty()) return matches.get(0);

        for (String ext : List.of(".java", ".py", ".js", ".ts", ".md")) {
            matches = fileNameIndex.get(fileName + ext);
            if (matches != null && !matches.isEmpty()) return matches.get(0);
        }
        return null;
    }

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

    public Map<String, List<String>> buildFileNameIndex(List<SearchResult> allFiles) {
        Map<String, List<String>> fileNameIndex = new HashMap<>();
        for (SearchResult f : allFiles) {
            fileNameIndex.computeIfAbsent(f.fileName(), k -> new ArrayList<>()).add(f.relativePath());
        }
        return fileNameIndex;
    }

    private String sanitizeMermaidId(String path) {
        return path.replaceAll("[^a-zA-Z0-9]", "_");
    }

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
            if (match != null && !match.isBlank()) results.add(match);
        }
    }

    public static class RelationshipMap {
        private final String targetFile;
        private final Map<String, String> outgoing = new LinkedHashMap<>();
        private final Map<String, String> incoming = new LinkedHashMap<>();

        public RelationshipMap(String targetFile) { this.targetFile = targetFile; }
        public void addOutgoing(String file, String type) { outgoing.put(file, type); }
        public void addIncoming(String file, String type) { incoming.put(file, type); }
        public String targetFile() { return targetFile; }
        public Map<String, String> outgoing() { return Collections.unmodifiableMap(outgoing); }
        public Map<String, String> incoming() { return Collections.unmodifiableMap(incoming); }
    }
}
