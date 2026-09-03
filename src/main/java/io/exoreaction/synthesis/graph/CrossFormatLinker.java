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
 *   <li>YAML config → Java: which classes reference config keys from a YAML config file</li>
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

    /**
     * Directory names that mark everything below them as configuration.
     *
     * <p>Matched as whole path segments, so {@code confetti/} does not qualify -- the same
     * segment discipline {@link #isTestPath} uses.
     */
    private static final Set<String> CONFIG_DIRECTORY_NAMES =
        Set.of("config", "configs", "conf", "configuration");

    /** Words in a file name that mark a YAML file as configuration wherever it lives. */
    private static final Set<String> CONFIG_FILE_NAME_MARKERS =
        Set.of("application", "config");

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
        // extractConfigKeys applies the existence and readability gates itself, and returns an
        // empty list when either fails -- so an unreadable config falls out here as one with no
        // keys, without this method repeating the checks.
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

    /**
     * Whether this file is a YAML <em>configuration</em> file, and so a cross-format source.
     *
     * <p>Narrowed from "every {@code .yaml} in the tree" in #506. See {@link #isConfigYaml}
     * for the rule and why it exists.
     */
    public boolean isConfigYamlFile(SearchResult file) {
        return isConfigYaml(file.relativePath());
    }

    /**
     * Whether a workspace-relative path names a YAML configuration file.
     *
     * <p>Every top-level key of every YAML file used to count as a config key (#506). Most YAML
     * in a repository is not configuration -- KCP manifests, skill manifests, documentation-site
     * settings -- and their generic keys ({@code name}, {@code version}, {@code description})
     * collide with ordinary Java string literals. On this repository that produced 868
     * {@code config-key} rows, 822 of them collisions on those three words.
     *
     * <p>The rule is a naming convention rather than a stop-list of keys, so it can be explained
     * to whoever reads a link: the file is configuration because it is named like configuration
     * or lives in a directory named for it. A repository that follows neither convention gets no
     * config-key links, which is the honest answer for a repository whose YAML is not config.
     *
     * <p>Both ends of the narrowing share this method: {@code relate} selects its cross-format
     * target with {@link #isConfigYamlFile}, and {@code CodeGraphExtractor} selects the sources
     * it persists with {@code isCrossFormatSourceFile}. Narrowing only one of them would
     * reopen #464 -- {@code relate} printing a link the graph does not hold -- in the opposite
     * direction.
     *
     * @param relativePath the workspace-relative path, with {@code /} separators
     */
    public static boolean isConfigYaml(String relativePath) {
        // Separators are normalized so the directory rule reads a Windows path too. The
        // surrounding walks assume "/" already, so this narrows nothing that they admit.
        String path = relativePath.toLowerCase(java.util.Locale.ROOT).replace('\\', '/');
        if (!path.endsWith(".yaml") && !path.endsWith(".yml")) return false;

        String[] segments = path.split("/");
        String fileName = segments[segments.length - 1];
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        if (baseName.isEmpty()) return false; // ".yaml" names no file

        for (String marker : CONFIG_FILE_NAME_MARKERS) {
            if (baseName.contains(marker)) return true;
        }
        for (int i = 0; i < segments.length - 1; i++) {
            if (CONFIG_DIRECTORY_NAMES.contains(segments[i])) return true;
        }
        return false;
    }

    private List<SearchResult> javaSourceFiles(List<SearchResult> all) {
        return all.stream()
            .filter(f -> f.fileName().endsWith(".java"))
            .filter(f -> !isTestPath(f.relativePath()))
            .collect(Collectors.toList());
    }

    /**
     * Whether a workspace-relative path belongs to a test source tree.
     *
     * <p>Both ends of a cross-format link are filtered by this: the Java end here, and the
     * source end in {@code CodeGraphExtractor}'s walks. It lives in one place so the two ends
     * cannot drift apart -- a rule enforced in one direction only is what let a test fixture
     * explain production code.
     *
     * <p>Matched as a path segment, so {@code src/testing/} -- production code whose directory
     * name merely starts with "test" -- is not excluded.
     */
    public static boolean isTestPath(String relativePath) {
        return relativePath.contains("src/test/");
    }
}
