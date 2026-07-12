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
    private static final Pattern KOTLIN_IMPORT = Pattern.compile(
            "^import\\s+([\\w.]+(?:\\.\\*)?)(?:\\s+as\\s+\\w+)?\\s*(?:;|$)", Pattern.MULTILINE);
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

    /**
     * Returns the other candidates in {@code results} that {@link #findBestMatch} could
     * just as validly have picked instead of {@code chosen} (#430). Mirrors
     * {@code findBestMatch}'s own three resolution tiers -- exact-path-or-suffix,
     * filename-only, then "whatever's first" -- and reports ambiguity only within whichever
     * tier actually produced the match, so a {@code target} that uniquely resolves via a
     * more specific tier isn't flagged just because a same-named file exists elsewhere
     * (e.g. "cli/Foo.java" uniquely matches tier 1 even when a "graph/Foo.java" also exists),
     * while a {@code target} that falls through to the arbitrary tier-3 pick is correctly
     * flagged whenever more than one candidate was on the table.
     */
    public List<SearchResult> findAmbiguousMatches(List<SearchResult> results, String target, SearchResult chosen) {
        if (chosen == null) return List.of();

        List<SearchResult> tier1 = new ArrayList<>();
        for (SearchResult r : results) {
            if (r.relativePath().equals(target) || r.relativePath().endsWith("/" + target)) tier1.add(r);
        }
        if (!tier1.isEmpty()) return otherThan(tier1, chosen);

        List<SearchResult> tier2 = new ArrayList<>();
        for (SearchResult r : results) {
            if (r.fileName().equals(target)) tier2.add(r);
        }
        if (!tier2.isEmpty()) return otherThan(tier2, chosen);

        return otherThan(results, chosen);
    }

    private List<SearchResult> otherThan(List<SearchResult> candidates, SearchResult chosen) {
        List<SearchResult> others = new ArrayList<>();
        for (SearchResult r : candidates) {
            if (!r.relativePath().equals(chosen.relativePath())) others.add(r);
        }
        return others;
    }

    /**
     * Formats the stderr warning for an ambiguous resolution (#430), or {@code null} when
     * {@code chosen} was unambiguous. Shared by {@code RelateCommand} and {@code ImpactCommand}
     * so the message stays identical across both callers.
     */
    public String formatAmbiguityWarning(List<SearchResult> results, String target, SearchResult chosen) {
        List<SearchResult> others = findAmbiguousMatches(results, target, chosen);
        if (others.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(others.size()).append(" other file(s) are also named '").append(chosen.fileName())
                .append("' -- resolved to ").append(chosen.relativePath())
                .append(". Pass a longer/relative path to disambiguate:");
        for (SearchResult r : others) sb.append("\n    - ").append(r.relativePath());
        return sb.toString();
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
            } else if ("Kotlin".equals(target.language())) {
                extractKotlinImports(content, references);
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

        // Bun/NodeNext TypeScript projects emit `import './Foo.js'` even though the source
        // file is `Foo.ts`. After the literal `.js` lookup fails, try the TypeScript
        // counterparts so the relate command resolves to the source, not a compiled artifact.
        // See issue #323.
        String tsRewriteResolved = tryTsRewrite(fileName, fileNameIndex);
        if (tsRewriteResolved != null) return tsRewriteResolved;

        if (ref.contains(".") && !ref.contains("/") && !hasKnownExtension(ref)) {
            String[] parts = ref.split("\\.");
            // Bare stem only -- no hardcoded extension. This branch is shared across every
            // dotted-FQN-style language (Java, Kotlin, Python), so no single extension is
            // correct here; the extension-fallback loop below resolves it (#439).
            fileName = parts[parts.length - 1];
        }
        matches = fileNameIndex.get(fileName);
        if (matches != null && !matches.isEmpty()) return matches.get(0);

        // Bare-stem extension fallback. Prefer TypeScript over JavaScript so source files
        // win over compiled artifacts in mixed Bun/NodeNext projects (#323).
        // NOTE (#439): .java is probed before .kt, so a same-simple-name Java/Kotlin stem
        // collision resolves to the Java file. Pre-existing class of ambiguity in this
        // fallback design (same risk already exists for .ts/.js/.jsx); not fixed here --
        // see RelationServiceTest#resolveReference_javaKotlinStemCollision_prefersJava.
        for (String ext : List.of(".java", ".kt", ".py", ".ts", ".tsx", ".js", ".jsx", ".md")) {
            matches = fileNameIndex.get(fileName + ext);
            if (matches != null && !matches.isEmpty()) return matches.get(0);
        }
        return null;
    }

    /**
     * Rewrites JS-extension references to their TypeScript counterparts. Bun/NodeNext
     * style imports use `.js` even when the on-disk source is `.ts` (or `.tsx`).
     *
     * @return the resolved relative path, or {@code null} if no TS counterpart exists
     */
    private String tryTsRewrite(String fileName, Map<String, List<String>> fileNameIndex) {
        List<String> candidates;
        if (fileName.endsWith(".js")) {
            String stem = fileName.substring(0, fileName.length() - 3);
            candidates = List.of(stem + ".ts", stem + ".tsx");
        } else if (fileName.endsWith(".jsx")) {
            String stem = fileName.substring(0, fileName.length() - 4);
            candidates = List.of(stem + ".tsx", stem + ".ts");
        } else {
            return null;
        }
        for (String candidate : candidates) {
            List<String> matches = fileNameIndex.get(candidate);
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

    /**
     * Kotlin imports need wildcard filtering that {@link #extractMatches} doesn't provide --
     * mirrors {@code CodeGraphExtractor.extractKotlinImports}'s wildcard-drop behavior (#406).
     */
    private void extractKotlinImports(String content, Set<String> results) {
        Matcher m = KOTLIN_IMPORT.matcher(content);
        while (m.find()) {
            String match = m.group(1);
            if (match != null && !match.isBlank() && !match.endsWith(".*")) {
                results.add(match);
            }
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
