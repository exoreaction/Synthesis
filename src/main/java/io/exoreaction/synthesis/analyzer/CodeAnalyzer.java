package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes source code files to extract structural information.
 *
 * <p>Extracts:
 * <ul>
 *   <li>Lines of code (total, non-blank, non-comment)</li>
 *   <li>Programming language (from extension and shebang)</li>
 *   <li>Import/dependency declarations</li>
 *   <li>Class/function/method names</li>
 *   <li>Framework detection from imports</li>
 * </ul>
 */
public class CodeAnalyzer implements FileAnalyzer {

    private static final int CONTENT_PREVIEW_LIMIT = 10240; // 10 KB

    // Common import/require patterns
    private static final Pattern JAVA_IMPORT = Pattern.compile("^import\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern PYTHON_IMPORT = Pattern.compile("^(?:from\\s+([\\w.]+)\\s+)?import\\s+([\\w.]+)", Pattern.MULTILINE);
    private static final Pattern JS_IMPORT = Pattern.compile("(?:import\\s+.*?\\s+from\\s+|import\\s+|require\\s*\\()['\"]([^'\"]+)['\"]", Pattern.MULTILINE);
    private static final Pattern GO_IMPORT = Pattern.compile("\"([^\"]+)\"", Pattern.MULTILINE);

    // Class/function patterns
    private static final Pattern JAVA_CLASS = Pattern.compile("(?:public|private|protected)?\\s*(?:abstract|final)?\\s*(?:class|interface|enum|record)\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern JAVA_METHOD = Pattern.compile("(?:public|private|protected)\\s+(?:static\\s+)?(?:\\w+(?:<[^>]+>)?\\s+)(\\w+)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern PYTHON_DEF = Pattern.compile("^\\s*(?:def|class)\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern JS_FUNCTION = Pattern.compile("(?:function\\s+(\\w+)|(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s+)?(?:\\([^)]*\\)|\\w+)\\s*=>)", Pattern.MULTILINE);
    private static final Pattern SHELL_FUNCTION = Pattern.compile("^\\s*(\\w+)\\s*\\(\\s*\\)", Pattern.MULTILINE);

    // Shebang for language detection
    private static final Pattern SHEBANG = Pattern.compile("^#!.*?(?:python|node|ruby|perl|bash|sh|zsh)");

    // Framework detection from imports.
    // Java/JVM frameworks use fully-qualified package prefixes to avoid false positives.
    // JS/Python frameworks use exact module name matching (handled in detectFrameworks).
    private static final Map<String, String> JAVA_FRAMEWORK_MARKERS = Map.ofEntries(
            Map.entry("org.springframework", "Spring"),
            Map.entry("org.springframework.boot", "Spring Boot"),
            Map.entry("org.junit", "JUnit"),
            Map.entry("jakarta.", "Jakarta EE"),
            Map.entry("javax.servlet", "Java EE"),
            Map.entry("javax.persistence", "JPA"),
            Map.entry("io.reactivex", "RxJava"),
            Map.entry("reactor.", "Project Reactor"),
            Map.entry("io.micronaut", "Micronaut"),
            Map.entry("io.quarkus", "Quarkus"),
            Map.entry("picocli", "Picocli"),
            Map.entry("org.apache.lucene", "Apache Lucene"),
            Map.entry("org.apache.kafka", "Kafka"),
            Map.entry("io.grpc", "gRPC"),
            Map.entry("org.hibernate", "Hibernate")
    );

    // JS/TS framework markers: these require exact module name match
    private static final Map<String, String> JS_FRAMEWORK_MARKERS = Map.ofEntries(
            Map.entry("react", "React"),
            Map.entry("react-dom", "React"),
            Map.entry("next", "Next.js"),
            Map.entry("next/", "Next.js"),
            Map.entry("express", "Express"),
            Map.entry("@angular/core", "Angular"),
            Map.entry("vue", "Vue.js"),
            Map.entry("svelte", "Svelte")
    );

    // Python framework markers: exact module name match
    private static final Map<String, String> PYTHON_FRAMEWORK_MARKERS = Map.ofEntries(
            Map.entry("fastapi", "FastAPI"),
            Map.entry("flask", "Flask"),
            Map.entry("django", "Django"),
            Map.entry("pytest", "pytest"),
            Map.entry("sqlalchemy", "SQLAlchemy"),
            Map.entry("pandas", "pandas"),
            Map.entry("numpy", "NumPy"),
            Map.entry("tensorflow", "TensorFlow"),
            Map.entry("torch", "PyTorch")
    );

    @Override
    public boolean canAnalyze(FileMetadata metadata) {
        return metadata.fileType() == FileUtils.FileType.CODE;
    }

    @Override
    public AnalysisResult analyze(FileMetadata metadata) throws IOException {
        String content = Files.readString(metadata.path());
        if (content.isEmpty()) {
            return AnalysisResult.empty();
        }

        String language = metadata.language();
        if (language == null) {
            language = detectFromShebang(content);
        }

        // Count lines
        String[] lines = content.split("\n", -1);
        int totalLines = lines.length;
        int blankLines = 0;
        int commentLines = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                blankLines++;
            } else if (isComment(trimmed, language)) {
                commentLines++;
            }
        }
        int codeLines = totalLines - blankLines - commentLines;

        // Extract imports and detect frameworks
        List<String> imports = extractImports(content, language);
        Set<String> frameworks = detectFrameworks(imports, language);

        // Extract class/function names
        List<String> declarations = extractDeclarations(content, language);

        // Build summary
        String summary = String.format("%s source, %d lines of code", language != null ? language : "Unknown", codeLines);
        if (!frameworks.isEmpty()) {
            summary += " (" + String.join(", ", frameworks) + ")";
        }

        // Keywords: language + frameworks + top-level declarations
        List<String> keywords = new ArrayList<>();
        if (language != null) keywords.add(language.toLowerCase());
        keywords.addAll(frameworks.stream().map(String::toLowerCase).toList());
        keywords.addAll(declarations);

        // Structure
        String structure = String.format("%d total lines, %d code, %d comments, %d blank, %d imports",
                totalLines, codeLines, commentLines, blankLines, imports.size());

        // Metrics
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalLines", totalLines);
        metrics.put("codeLines", codeLines);
        metrics.put("commentLines", commentLines);
        metrics.put("blankLines", blankLines);
        metrics.put("importCount", imports.size());
        if (language != null) metrics.put("language", language);
        if (!frameworks.isEmpty()) metrics.put("frameworks", new ArrayList<>(frameworks));

        // Content preview
        String preview = content.length() > CONTENT_PREVIEW_LIMIT
                ? content.substring(0, CONTENT_PREVIEW_LIMIT)
                : content;

        return AnalysisResult.builder()
                .summary(summary)
                .headings(declarations)
                .keywords(keywords)
                .links(imports)
                .structure(structure)
                .metrics(metrics)
                .contentPreview(preview)
                .build();
    }

    private String detectFromShebang(String content) {
        Matcher matcher = SHEBANG.matcher(content);
        if (matcher.find()) {
            String shebang = matcher.group().toLowerCase();
            if (shebang.contains("python")) return "Python";
            if (shebang.contains("node")) return "JavaScript";
            if (shebang.contains("ruby")) return "Ruby";
            if (shebang.contains("perl")) return "Perl";
            if (shebang.contains("bash") || shebang.contains("/sh") || shebang.contains("zsh")) return "Shell";
        }
        return null;
    }

    private boolean isComment(String trimmedLine, String language) {
        if (language == null) return false;
        return switch (language) {
            case "Java", "JavaScript", "TypeScript", "C", "C++", "C#", "Go", "Rust", "Kotlin", "Scala", "Swift", "Dart" ->
                    trimmedLine.startsWith("//") || trimmedLine.startsWith("/*") || trimmedLine.startsWith("*");
            case "Python", "Ruby", "Shell", "Perl", "R" ->
                    trimmedLine.startsWith("#");
            case "Lua" -> trimmedLine.startsWith("--");
            case "SQL" -> trimmedLine.startsWith("--") || trimmedLine.startsWith("/*");
            case "Haskell" -> trimmedLine.startsWith("--") || trimmedLine.startsWith("{-");
            case "Clojure", "Elixir", "Erlang" -> trimmedLine.startsWith(";") || trimmedLine.startsWith("%");
            default -> trimmedLine.startsWith("//") || trimmedLine.startsWith("#");
        };
    }

    private List<String> extractImports(String content, String language) {
        if (language == null) return List.of();

        Pattern pattern = switch (language) {
            case "Java", "Kotlin", "Scala" -> JAVA_IMPORT;
            case "Python" -> PYTHON_IMPORT;
            case "JavaScript", "TypeScript" -> JS_IMPORT;
            case "Go" -> GO_IMPORT;
            default -> null;
        };

        if (pattern == null) return List.of();

        List<String> imports = new ArrayList<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String imp = matcher.group(1);
            if (imp != null && !imp.isBlank()) {
                imports.add(imp.trim());
            }
        }
        return imports;
    }

    private Set<String> detectFrameworks(List<String> imports, String language) {
        Set<String> frameworks = new LinkedHashSet<>();
        if (language == null) return frameworks;

        for (String imp : imports) {
            switch (language) {
                case "Java", "Kotlin", "Scala", "Groovy" -> {
                    // Java imports use package prefixes -- check with startsWith for precision
                    for (var entry : JAVA_FRAMEWORK_MARKERS.entrySet()) {
                        if (imp.startsWith(entry.getKey())) {
                            frameworks.add(entry.getValue());
                        }
                    }
                }
                case "JavaScript", "TypeScript" -> {
                    // JS/TS imports are module names -- check exact match or startsWith for scoped
                    for (var entry : JS_FRAMEWORK_MARKERS.entrySet()) {
                        if (imp.equals(entry.getKey()) || imp.startsWith(entry.getKey() + "/")) {
                            frameworks.add(entry.getValue());
                        }
                    }
                }
                case "Python" -> {
                    // Python imports use dot-separated module paths
                    String topModule = imp.contains(".") ? imp.substring(0, imp.indexOf('.')) : imp;
                    for (var entry : PYTHON_FRAMEWORK_MARKERS.entrySet()) {
                        if (topModule.equals(entry.getKey())) {
                            frameworks.add(entry.getValue());
                        }
                    }
                }
                default -> {
                    // For other languages, use Java markers with startsWith as a reasonable default
                    for (var entry : JAVA_FRAMEWORK_MARKERS.entrySet()) {
                        if (imp.startsWith(entry.getKey())) {
                            frameworks.add(entry.getValue());
                        }
                    }
                }
            }
        }
        return frameworks;
    }

    private List<String> extractDeclarations(String content, String language) {
        if (language == null) return List.of();

        List<Pattern> patterns = switch (language) {
            case "Java", "Kotlin", "Scala" -> List.of(JAVA_CLASS, JAVA_METHOD);
            case "Python" -> List.of(PYTHON_DEF);
            case "JavaScript", "TypeScript" -> List.of(JS_FUNCTION);
            case "Shell" -> List.of(SHELL_FUNCTION);
            default -> List.of();
        };

        Set<String> declarations = new LinkedHashSet<>();
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                for (int i = 1; i <= matcher.groupCount(); i++) {
                    String name = matcher.group(i);
                    if (name != null && !name.isBlank() && name.length() > 1) {
                        declarations.add(name);
                    }
                }
            }
        }
        return new ArrayList<>(declarations);
    }
}
