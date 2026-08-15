package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.index.SearchResult;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
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

    private static final Logger log = Logger.getLogger(CrossFormatLinker.class.getName());

    /** Maximum file size (in bytes) we will attempt to read as text. Files larger than this are skipped. */
    static final long MAX_TEXT_FILE_SIZE = 2 * 1024 * 1024; // 2 MB

    /** File extensions recognized as text formats safe to read with Files.readString(). */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        ".java", ".kt", ".sql", ".xml", ".yaml", ".yml", ".json",
        ".properties", ".gradle", ".kts", ".groovy", ".scala",
        ".ts", ".js", ".md", ".txt", ".csv", ".html", ".css",
        ".cfg", ".conf", ".toml", ".ini", ".sh", ".bat", ".py", ".rb"
    );

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

    /** YAML literal words that are never a meaningful config key. */
    private static final Set<String> YAML_LITERAL_WORDS =
        Set.of("true", "false", "null", "yes", "no");

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
            if (!isReadableTextFile(p)) continue;
            String content;
            try { content = Files.readString(p).toLowerCase(java.util.Locale.ROOT); }
            catch (IOException e) { continue; }

            for (String table : tableNames) {
                if (referencesTable(content, table)) {
                    links.add(new CrossFormatLink(
                        javaFile.relativePath(), javaFile.fileName(), "table", table));
                    break;
                }
            }
        }
        return links;
    }

    /**
     * Whether already-lowercased Java source content references {@code table}.
     *
     * <p>Extracted from {@link #findSqlToJavaLinks} so a caller that already holds the file
     * content -- the incremental path, which reads each Java file once per run rather than
     * once per SQL file (#465) -- can match without reading it again.
     */
    public static boolean referencesTable(String lowerCaseContent, String table) {
        String t = table.toLowerCase(java.util.Locale.ROOT);
        return lowerCaseContent.contains("\"" + t + "\"")
                || lowerCaseContent.contains("'" + t + "'")
                || lowerCaseContent.contains(t.replace("_", ""));
    }

    /** Extract CREATE TABLE names from a SQL file. */
    public List<String> extractTableNames(SearchResult sqlFile, Path workspaceRoot) throws IOException {
        Path p = workspaceRoot.resolve(sqlFile.relativePath());
        if (!Files.exists(p)) return List.of();
        if (!isReadableTextFile(p)) {
            log.fine("Skipping non-text/oversized file in cross-format linking: " + p);
            return List.of();
        }
        String sql;
        try {
            sql = Files.readString(p);
        } catch (IOException e) {
            log.fine("Skipping non-text file in cross-format linking: " + p);
            return List.of();
        }
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
        if (!isReadableTextFile(p)) {
            log.fine("Skipping non-text/oversized file in cross-format linking: " + p);
            return List.of();
        }
        List<String> keys = extractConfigKeys(yamlFile, workspaceRoot);
        if (keys.isEmpty()) return List.of();

        List<CrossFormatLink> links = new ArrayList<>();
        for (SearchResult javaFile : javaSourceFiles(allFiles)) {
            Path jp = workspaceRoot.resolve(javaFile.relativePath());
            if (!Files.exists(jp)) continue;
            if (!isReadableTextFile(jp)) continue;
            String content;
            try { content = Files.readString(jp); }
            catch (IOException e) { continue; }

            for (String key : keys) {
                if (referencesConfigKey(content, key)) {
                    links.add(new CrossFormatLink(
                        javaFile.relativePath(), javaFile.fileName(), "config-key", key));
                    break;
                }
            }
        }
        return links;
    }

    /**
     * Whether Java source content references the config key {@code key}.
     *
     * <p>Extracted from {@link #findYamlToJavaLinks} so a caller that already holds the file
     * content -- the incremental path, which reads each Java file once per run rather than once
     * per config file (#464) -- can match without reading it again.
     *
     * <p>Unlike {@link #referencesTable}, the match is case-sensitive and always requires a
     * delimiter: a config key is written verbatim in the source, and the bare-identifier
     * fallback that suits table names would match ordinary Java identifiers.
     */
    public static boolean referencesConfigKey(String content, String key) {
        return content.contains("\"" + key + "\"")
                || content.contains("${" + key + "}")
                || content.contains("get(\"" + key);
    }

    /**
     * Extract the top-level keys of a YAML config file.
     *
     * <p>Keys of two characters or fewer and the YAML literal words are dropped: they carry no
     * signal and would match a large share of the Java in any repository.
     */
    public List<String> extractConfigKeys(SearchResult yamlFile, Path workspaceRoot)
            throws IOException {
        Path p = workspaceRoot.resolve(yamlFile.relativePath());
        if (!Files.exists(p)) return List.of();
        if (!isReadableTextFile(p)) {
            log.fine("Skipping non-text/oversized file in cross-format linking: " + p);
            return List.of();
        }
        String yaml;
        try {
            yaml = Files.readString(p);
        } catch (IOException e) {
            log.fine("Skipping non-text file in cross-format linking: " + p);
            return List.of();
        }

        List<String> keys = new ArrayList<>();
        Matcher m = YAML_TOP_KEY.matcher(yaml);
        while (m.find()) {
            String key = m.group(1);
            if (key.length() > 2 && !YAML_LITERAL_WORDS.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the file has a recognized text extension AND is
     * smaller than {@link #MAX_TEXT_FILE_SIZE}. This prevents
     * {@link java.nio.file.Files#readString(Path)} from blowing up on large
     * binary files (e.g. PNGs) that would cause {@link OutOfMemoryError}.
     *
     * @param path the file to check
     * @return true if safe to read as text
     */
    static boolean isReadableTextFile(Path path) {
        // Extension check
        String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false; // no extension — skip to be safe
        String ext = fileName.substring(dot);
        if (!TEXT_EXTENSIONS.contains(ext)) {
            return false;
        }

        // Size check
        try {
            long size = Files.size(path);
            return size <= MAX_TEXT_FILE_SIZE;
        } catch (IOException e) {
            return false;
        }
    }

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
}
