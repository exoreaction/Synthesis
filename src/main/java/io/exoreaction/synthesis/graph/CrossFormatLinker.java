package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.index.SearchResult;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Detects cross-format relationships invisible to import analysis:
 *
 * <ul>
 *   <li>SQL migration → Java: which classes reference tables created by a migration</li>
 *   <li>YAML config → Java: which classes reference config keys from a YAML file</li>
 * </ul>
 */
public class CrossFormatLinker {

    public record CrossFormatLink(
        String targetPath,
        String targetFile,
        String linkType,
        String entityName
    ) {}

    private static final Pattern CREATE_TABLE =
        Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern YAML_TOP_KEY =
        Pattern.compile("^([a-zA-Z][a-zA-Z0-9_-]+)\\s*:", Pattern.MULTILINE);

    // -----------------------------------------------------------------------
    // SQL → Java
    // -----------------------------------------------------------------------

    /**
     * For a SQL file, find Java source files that reference any table it creates.
     */
    public List<CrossFormatLink> findSqlToJavaLinks(SearchResult sqlFile,
                                                     List<SearchResult> allFiles,
                                                     Path workspaceRoot) throws IOException {
        List<String> tableNames = extractTableNames(sqlFile, workspaceRoot);
        if (tableNames.isEmpty()) return List.of();

        List<CrossFormatLink> links = new ArrayList<>();
        for (SearchResult javaFile : javaSourceFiles(allFiles)) {
            Path p = workspaceRoot.resolve(javaFile.relativePath());
            if (!Files.exists(p)) continue;
            String content;
            try { content = Files.readString(p).toLowerCase(java.util.Locale.ROOT); }
            catch (IOException e) { continue; }

            for (String table : tableNames) {
                String t = table.toLowerCase(java.util.Locale.ROOT);
                if (content.contains("\"" + t + "\"")
                        || content.contains("'" + t + "'")
                        || content.contains(t.replace("_", ""))) {
                    links.add(new CrossFormatLink(
                        javaFile.relativePath(), javaFile.fileName(), "table", table));
                    break;
                }
            }
        }
        return links;
    }

    /** Extract CREATE TABLE names from a SQL file. */
    public List<String> extractTableNames(SearchResult sqlFile, Path workspaceRoot) throws IOException {
        Path p = workspaceRoot.resolve(sqlFile.relativePath());
        if (!Files.exists(p)) return List.of();
        String sql = Files.readString(p);
        List<String> names = new ArrayList<>();
        Matcher m = CREATE_TABLE.matcher(sql);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    // -----------------------------------------------------------------------
    // YAML → Java
    // -----------------------------------------------------------------------

    /**
     * For a YAML config file, find Java source files that reference any of its top-level keys.
     */
    public List<CrossFormatLink> findYamlToJavaLinks(SearchResult yamlFile,
                                                      List<SearchResult> allFiles,
                                                      Path workspaceRoot) throws IOException {
        Path p = workspaceRoot.resolve(yamlFile.relativePath());
        if (!Files.exists(p)) return List.of();
        String yaml = Files.readString(p);

        List<String> keys = new ArrayList<>();
        Matcher m = YAML_TOP_KEY.matcher(yaml);
        while (m.find()) {
            String key = m.group(1);
            // skip common YAML noise words and short keys
            if (key.length() > 2 && !key.equals("true") && !key.equals("false")
                    && !key.equals("null") && !key.equals("yes") && !key.equals("no")) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) return List.of();

        List<CrossFormatLink> links = new ArrayList<>();
        for (SearchResult javaFile : javaFiles(allFiles)) {
            Path jp = workspaceRoot.resolve(javaFile.relativePath());
            if (!Files.exists(jp)) continue;
            String content;
            try { content = Files.readString(jp); }
            catch (IOException e) { continue; }

            for (String key : keys) {
                if (content.contains("\"" + key + "\"")
                        || content.contains("${" + key + "}")
                        || content.contains("get(\"" + key)) {
                    links.add(new CrossFormatLink(
                        javaFile.relativePath(), javaFile.fileName(), "config-key", key));
                    break;
                }
            }
        }
        return links;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    public boolean isSqlFile(SearchResult file) {
        return file.fileName().endsWith(".sql");
    }

    public boolean isYamlFile(SearchResult file) {
        String n = file.fileName();
        return n.endsWith(".yaml") || n.endsWith(".yml");
    }

    private List<SearchResult> javaSourceFiles(List<SearchResult> all) {
        return all.stream()
            .filter(f -> f.fileName().endsWith(".java"))
            .filter(f -> !f.relativePath().contains("src/test/"))
            .collect(Collectors.toList());
    }

    private List<SearchResult> javaFiles(List<SearchResult> all) {
        return all.stream()
            .filter(f -> f.fileName().endsWith(".java"))
            .collect(Collectors.toList());
    }
}
