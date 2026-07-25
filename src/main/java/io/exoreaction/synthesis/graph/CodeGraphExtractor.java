package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CrossFormatLinkRecord;
import io.exoreaction.synthesis.graph.lang.Declaration;
import io.exoreaction.synthesis.graph.lang.Ext;
import io.exoreaction.synthesis.graph.lang.ExclusionRules;
import io.exoreaction.synthesis.graph.lang.JavaLanguageExtractor;
import io.exoreaction.synthesis.graph.lang.KotlinLanguageExtractor;
import io.exoreaction.synthesis.graph.lang.LanguageExtractor;
import io.exoreaction.synthesis.graph.lang.TypeScriptLanguageExtractor;
import io.exoreaction.synthesis.graph.lang.RawEdge;
import io.exoreaction.synthesis.graph.lang.ResolutionKey;
import io.exoreaction.synthesis.graph.lang.Resolver;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Orchestrates code dependency extraction and persists it to the code knowledge graph
 * tables via {@link CodeGraphRepository}.
 *
 * <p>Per-language extraction lives behind the {@link io.exoreaction.synthesis.graph.lang.LanguageExtractor}
 * seam (ADR-0001): this class owns only the shared concerns -- the {@link #REGISTRY}, the
 * two-pass (declare across all languages, then resolve edges via {@link Resolver}),
 * persistence, incremental scoping, and the Java-coupled cross-format step
 * ({@link CrossFormatLinker}). A new language is added by registering one
 * {@code LanguageExtractor}; nothing else here changes.
 *
 * <p>Supports both full extraction and incremental updates for changed files.
 */
public class CodeGraphExtractor {

    private static final Logger LOG = Logger.getLogger(CodeGraphExtractor.class.getName());

    /** Directory names excluded by default (duplicates, vendored code). */
    private static final Set<String> ARCHIVE_DIR_NAMES = Set.of(
            "archive", "vendor", "node_modules"
    );

    /**
     * The per-language extraction seam (ADR-0001). A new language slots in by adding one
     * {@link LanguageExtractor} here -- the orchestrator drives them uniformly, so nothing
     * else in this file changes (the acceptance criterion: a Go extractor touches only this line).
     *
     * <p>Genuinely a constant: the extractors are stateless, so one shared immutable list
     * serves every {@code CodeGraphExtractor} instance.
     */
    private static final List<LanguageExtractor> REGISTRY =
            List.of(new JavaLanguageExtractor(), new KotlinLanguageExtractor(), new TypeScriptLanguageExtractor());

    private final CodeGraphRepository repository;
    private final CrossFormatLinker crossFormatLinker;
    private boolean includeArchives = false;

    public CodeGraphExtractor() {
        this.repository = new CodeGraphRepository();
        this.crossFormatLinker = new CrossFormatLinker();
    }

    public CodeGraphExtractor(CodeGraphRepository repository, CrossFormatLinker crossFormatLinker) {
        this.repository = repository;
        this.crossFormatLinker = crossFormatLinker;
    }

    /**
     * When set to {@code true}, archive/vendor/node_modules directories are
     * included in the code graph analysis. Default is {@code false}.
     *
     * @param includeArchives whether to include archive directories
     */
    public void setIncludeArchives(boolean includeArchives) {
        this.includeArchives = includeArchives;
    }

    /**
     * Full extraction: scans every registered language's files under workspaceRoot,
     * extracts dependencies, and persists them. Clears existing data first.
     *
     * @param workspaceRoot root of the workspace to scan
     * @param conn          database connection
     * @return extraction statistics
     */
    public CodeGraphStats extractAndPersist(Path workspaceRoot, Connection conn) throws SQLException, IOException {
        long start = System.currentTimeMillis();
        String wsPath = workspaceRoot.toString();

        // Clear existing data for this workspace
        repository.deleteAllDependencies(conn, wsPath);
        repository.deleteAllCrossFormatLinks(conn, wsPath);

        // Pass 1 (declarations, always full): for every language in the registry, discover files
        // and register declared identities + resolver fallback indexes before any edge resolves.
        ExclusionRules excl = new ExclusionRules(includeArchives);
        Map<String, String> classToFile = new HashMap<>();
        Map<String, List<String>> packageFunctionFiles = new HashMap<>();
        Map<String, String> tsPathIndex = new HashMap<>();
        Set<String> packages = new HashSet<>();

        List<Path> javaFiles = List.of();
        int totalFiles = 0;
        List<Map.Entry<LanguageExtractor, List<Map.Entry<Path, List<Declaration>>>>> work = new ArrayList<>();
        for (LanguageExtractor lang : REGISTRY) {
            List<Path> files = lang.findFiles(workspaceRoot, excl);
            totalFiles += files.size();
            // Cross-format linking (below) is Java-coupled and lives outside the seam (ADR sub-decision 4).
            if (lang.languageId().equals("java")) javaFiles = files;
            work.add(Map.entry(lang, registerDeclarations(lang, workspaceRoot, files,
                    classToFile, packageFunctionFiles, tsPathIndex, packages)));
        }

        Map<String, List<String>> simpleNameIndex = Resolver.buildSimpleNameIndex(classToFile);
        Resolver resolver = new Resolver(classToFile, simpleNameIndex, packageFunctionFiles, tsPathIndex);

        long now = Instant.now().getEpochSecond();

        // Pass 2 (edges): resolve each edge against the full index and persist rows.
        PersistedRows rows = new PersistedRows();
        for (Map.Entry<LanguageExtractor, List<Map.Entry<Path, List<Declaration>>>> w : work) {
            persistEdges(w.getKey(), workspaceRoot, w.getValue(), resolver, conn, wsPath, now, rows);
        }

        // Cross-format links (SQL -> Java)
        int crossLinks = extractCrossFormatLinks(workspaceRoot, conn, javaFiles, now);

        long elapsed = System.currentTimeMillis() - start;
        return new CodeGraphStats(totalFiles, rows.total(),
                crossLinks, packages.size(), rows.external(), elapsed, Instant.now());
    }

    /**
     * Incremental update: re-extracts only the given changed files.
     * Deletes old edges for those files, then re-extracts.
     *
     * @param workspaceRoot workspace root
     * @param conn          database connection
     * @param changedFiles  set of changed file paths (relative to workspace root)
     * @return extraction statistics
     */
    public CodeGraphStats incrementalUpdate(Path workspaceRoot, Connection conn,
                                             Set<Path> changedFiles) throws SQLException, IOException {
        long start = System.currentTimeMillis();
        String wsPath = workspaceRoot.toString();

        // Build the full index across the whole workspace (all languages) so resolution is
        // correct even when only one side of a cross-language reference changed.
        ExclusionRules excl = new ExclusionRules(includeArchives);
        Map<String, String> classToFile = new HashMap<>();
        Map<String, List<String>> packageFunctionFiles = new HashMap<>();
        Map<String, String> tsPathIndex = new HashMap<>();

        for (LanguageExtractor lang : REGISTRY) {
            registerDeclarations(lang, workspaceRoot, lang.findFiles(workspaceRoot, excl),
                    classToFile, packageFunctionFiles, tsPathIndex, null);
        }

        Map<String, List<String>> simpleNameIndex = Resolver.buildSimpleNameIndex(classToFile);
        Resolver resolver = new Resolver(classToFile, simpleNameIndex, packageFunctionFiles, tsPathIndex);

        PersistedRows rows = new PersistedRows();
        Set<String> packages = new HashSet<>();
        long now = Instant.now().getEpochSecond();
        int filesProcessed = 0;

        for (Path changedFile : changedFiles) {
            Path fullPath = changedFile.isAbsolute() ? changedFile : workspaceRoot.resolve(changedFile);
            if (!Files.exists(fullPath)) continue;

            LanguageExtractor lang = extractorFor(fullPath);
            if (lang == null) continue; // not a language we extract

            String relPath = workspaceRoot.relativize(fullPath).toString();
            filesProcessed++;

            // Delete old edges for this file, then re-extract via its language.
            repository.deleteDependenciesForFile(conn, wsPath, relPath);
            persistChangedFile(lang, workspaceRoot, fullPath,
                    resolver, packages, conn, wsPath, relPath, now, rows);
        }

        long elapsed = System.currentTimeMillis() - start;
        return new CodeGraphStats(filesProcessed, rows.total(), 0,
                packages.size(), rows.external(), elapsed, Instant.now());
    }

    /**
     * Returns the repository for direct queries.
     */
    public CodeGraphRepository getRepository() {
        return repository;
    }

    /**
     * Whether any registered language claims {@code path} by extension (#466). The single
     * source of truth for "is this a code-graph file?" -- callers that keep their own
     * extension list go stale the moment a language is registered.
     *
     * @param path a file path or name (only the suffix is inspected)
     */
    public boolean isSourceFile(String path) {
        for (LanguageExtractor lang : REGISTRY) {
            for (Ext ext : lang.extensions()) {
                if (path.endsWith(ext.suffix())) return true;
            }
        }
        return false;
    }

    /**
     * The source files every registered language claims under {@code workspaceRoot}, keyed by
     * {@link LanguageExtractor#displayName()} in registry order (#466).
     *
     * <p>Callers that need a file set -- the CLI's {@code --incremental} changed set and its
     * {@code --dry-run} counts -- must use this rather than walking for extensions themselves:
     * a hardcoded per-language walk silently drops any language it forgets, leaving that
     * language's graph stale (ADR-0001 gap #6), and misses each language's own exclusions
     * (e.g. TypeScript {@code .d.ts} files) and the shared {@link ExclusionRules} that
     * {@code --include-archives} feeds. Registering a language is therefore enough to have it
     * discovered everywhere.
     *
     * @param workspaceRoot root of the workspace to scan
     * @return display name to that language's files (empty list when a language has none)
     */
    public Map<String, List<Path>> sourceFilesByLanguage(Path workspaceRoot) {
        ExclusionRules excl = new ExclusionRules(includeArchives);
        Map<String, List<Path>> byLanguage = new LinkedHashMap<>();
        for (LanguageExtractor lang : REGISTRY) {
            byLanguage.put(lang.displayName(), lang.findFiles(workspaceRoot, excl));
        }
        return byLanguage;
    }

    /**
     * Returns the registered {@link LanguageExtractor} that claims {@code file} by extension,
     * or {@code null} if no language does. Drives the incremental changed-file dispatch so a
     * new language is handled automatically once it is in {@link #REGISTRY}.
     */
    private LanguageExtractor extractorFor(Path file) {
        String name = file.getFileName().toString();
        for (LanguageExtractor lang : REGISTRY) {
            for (Ext ext : lang.extensions()) {
                if (name.endsWith(ext.suffix())) return lang;
            }
        }
        return null;
    }

    /**
     * Counts the {@code code_dependencies} rows a run persists, not the upsert attempts (#469).
     *
     * <p>The table is {@code UNIQUE(workspace_path, source_file, target_class, target_package)}
     * (V13__code_knowledge_graph.sql) and {@link CodeGraphRepository#upsertDependency} issues
     * {@code INSERT OR REPLACE}, so two edges agreeing on those columns collapse into one row
     * with the last write winning (e.g. the TypeScript specifiers {@code ./bar} and
     * {@code ./bar.js}). Keying on that same tuple -- and letting the last write win for
     * {@code is_external} -- makes the stats agree with
     * {@link CodeGraphRepository#countDependencies}, which {@code code-graph extract --stats}
     * prints. {@code workspace_path} is omitted from the key: it is constant within a run.
     */
    private static final class PersistedRows {
        /** The table's unique key, minus the run-constant {@code workspace_path}. */
        private record RowKey(String sourceFile, String targetClass, String targetPackage) {}

        private final Map<RowKey, Boolean> externalByKey = new HashMap<>();

        void record(CodeDependency dep) {
            externalByKey.put(
                    new RowKey(dep.sourceFile(), dep.targetClass(), dep.targetPackage()),
                    dep.isExternal());
        }

        int total() {
            return externalByKey.size();
        }

        int external() {
            return (int) externalByKey.values().stream().filter(Boolean::booleanValue).count();
        }
    }

    /**
     * Pass 1 for a language (always full): reads each file, registers its declared
     * FQN -> relative-path entries in {@code classToFile}, and merges any per-language
     * package-fallback entries into {@code packageFunctionFiles}. Returns the per-file
     * work list (file + declarations) for pass 2. Language-agnostic: the fallback merge
     * is driven by {@link LanguageExtractor#packageFallbackFiles} (empty for languages
     * that need none). Mirrors the former index builders, including the filename-stem
     * fallback for an unreadable file.
     *
     * @param packages if non-null, each declared package is added here (stats)
     */
    private List<Map.Entry<Path, List<Declaration>>> registerDeclarations(
            LanguageExtractor lang, Path root, List<Path> files,
            Map<String, String> classToFile, Map<String, List<String>> packageFunctionFiles,
            Map<String, String> tsPathIndex, Set<String> packages) {
        List<Map.Entry<Path, List<Declaration>>> work = new ArrayList<>();
        for (Path f : files) {
            String rel = root.relativize(f).toString();
            try {
                String content = FileUtils.readPreview(f, 50_000);
                List<Declaration> decls = lang.declarations(f, content);
                for (Declaration d : decls) {
                    if (d.key() instanceof ResolutionKey.FqnKey fk) {
                        classToFile.put(fk.fqn(), rel);
                        if (packages != null) {
                            String pkg = Resolver.getPackageFromImport(fk.fqn());
                            if (!pkg.isEmpty()) packages.add(pkg);
                        }
                    }
                }
                lang.packageFallbackFiles(f, content, decls).forEach((pkg, pfiles) -> {
                    List<String> bucket = packageFunctionFiles.computeIfAbsent(pkg, k -> new ArrayList<>());
                    for (Path pf : pfiles) bucket.add(root.relativize(pf).toString());
                });
                tsPathIndex.putAll(lang.pathIndex(root, f, content));
                work.add(Map.entry(f, decls));
            } catch (IOException e) {
                // Fallback (as the former index builders did): key on the filename stem.
                String n = f.getFileName().toString();
                int dot = n.lastIndexOf('.');
                classToFile.put(dot > 0 ? n.substring(0, dot) : n, rel);
            }
        }
        return work;
    }

    /**
     * Pass 2 for a language (scoped to {@code work}; full when non-incremental): emits each
     * file's edges, resolves each target against the shared {@code resolver}, and persists a
     * {@code code_dependencies} row. Each persisted row is recorded in {@code rows} so the
     * run's stats report surviving rows rather than upsert attempts (#469).
     */
    private void persistEdges(LanguageExtractor lang, Path root,
            List<Map.Entry<Path, List<Declaration>>> work, Resolver resolver,
            Connection conn, String wsPath, long now, PersistedRows rows) throws SQLException {
        for (Map.Entry<Path, List<Declaration>> w : work) {
            Path f = w.getKey();
            String relPath = root.relativize(f).toString();
            try {
                String content = FileUtils.readPreview(f, 50_000);
                String repoName = detectRepoName(root, f);
                for (RawEdge edge : lang.edges(f, content, w.getValue())) {
                    String targetFile = resolver.resolve(edge.to(), relPath);
                    boolean isExternal = (targetFile == null);
                    CodeDependency dep = new CodeDependency(
                            wsPath, repoName, relPath, edge.sourceClass(), edge.sourcePackage(),
                            targetFile, edge.targetClass(), edge.targetPackage(),
                            edge.dependencyType(), isExternal, now);
                    repository.upsertDependency(conn, dep);
                    rows.record(dep);
                }
            } catch (IOException e) {
                LOG.fine("Skipping unreadable file: " + f + ": " + e.getMessage());
            }
        }
    }

    /**
     * Incremental pass 2 for a single changed file: derives the file's declared packages
     * (stats) and persists its edges (targets resolved against the full {@code resolver}).
     * The caller has already deleted the file's old rows. Persisted rows are recorded in
     * {@code rows} (#469).
     */
    private void persistChangedFile(LanguageExtractor lang, Path root, Path file,
            Resolver resolver, Set<String> packages, Connection conn, String wsPath,
            String relPath, long now, PersistedRows rows) throws SQLException {
        try {
            String content = FileUtils.readPreview(file, 50_000);
            String repoName = detectRepoName(root, file);
            List<Declaration> decls = lang.declarations(file, content);
            for (Declaration d : decls) {
                if (d.key() instanceof ResolutionKey.FqnKey fk) {
                    String pkg = Resolver.getPackageFromImport(fk.fqn());
                    if (!pkg.isEmpty()) packages.add(pkg);
                }
            }
            for (RawEdge edge : lang.edges(file, content, decls)) {
                String targetFile = resolver.resolve(edge.to(), relPath);
                boolean isExternal = (targetFile == null);
                CodeDependency dep = new CodeDependency(
                        wsPath, repoName, relPath, edge.sourceClass(), edge.sourcePackage(),
                        targetFile, edge.targetClass(), edge.targetPackage(),
                        edge.dependencyType(), isExternal, now);
                repository.upsertDependency(conn, dep);
                rows.record(dep);
            }
        } catch (IOException e) {
            LOG.fine("Skipping unreadable file: " + file + ": " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Extraction helpers
    // -----------------------------------------------------------------------

    /**
     * Detects the repository name for a file in a multi-repo workspace.
     *
     * <p>If the workspace root itself is a single repo (has pom.xml/build.gradle at root,
     * or the first path component is "src"), returns empty string. Otherwise, returns
     * the first path component relative to workspace root (e.g., "Quadim-Skill-Service"
     * from "Quadim-Skill-Service/src/main/java/...").
     *
     * @param workspaceRoot workspace root path
     * @param javaFile      the Java source file (absolute path)
     * @return repo name, or empty string for single-repo workspaces
     */
    String detectRepoName(Path workspaceRoot, Path javaFile) {
        Path rel = workspaceRoot.relativize(javaFile);
        if (rel.getNameCount() <= 1) {
            return ""; // File directly under workspace root
        }
        String firstComponent = rel.getName(0).toString();
        // If the first component is "src" or a build dir, this is a single-repo workspace
        if ("src".equals(firstComponent) || "main".equals(firstComponent)
                || "test".equals(firstComponent) || "java".equals(firstComponent)) {
            return "";
        }
        // Check if the workspace root itself has a build file (single-repo)
        if (Files.exists(workspaceRoot.resolve("pom.xml"))
                || Files.exists(workspaceRoot.resolve("build.gradle"))
                || Files.exists(workspaceRoot.resolve("build.gradle.kts"))) {
            return "";
        }
        return firstComponent;
    }

    /**
     * Returns true if the given path is inside a build artifact directory
     * (target/, build/, out/) relative to the workspace root.
     */
    public static boolean isBuildArtifact(Path workspaceRoot, Path file) {
        Path rel = workspaceRoot.relativize(file);
        for (Path component : rel) {
            String name = component.toString();
            if ("target".equals(name) || "build".equals(name) || "out".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the given path is inside an archive, vendor, or
     * node_modules directory. These directories typically contain old
     * or vendored copies of code that inflate the code graph with
     * duplicate packages and false circular dependencies (#279).
     *
     * @param workspaceRoot workspace root directory
     * @param file          the file to check (absolute path)
     * @return true if the file is inside an archive directory
     */
    public static boolean isArchiveDirectory(Path workspaceRoot, Path file) {
        Path rel = workspaceRoot.relativize(file);
        for (Path component : rel) {
            if (ARCHIVE_DIR_NAMES.contains(component.toString())) {
                return true;
            }
        }
        return false;
    }

    private int extractCrossFormatLinks(Path workspaceRoot, Connection conn,
                                         List<Path> javaFiles,
                                         long now) throws IOException, SQLException {
        String wsPath = workspaceRoot.toString();
        List<CrossFormatLinkRecord> allRecords = new ArrayList<>();

        // Find SQL files
        try (Stream<Path> walk = Files.walk(workspaceRoot)) {
            List<Path> sqlFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".sql"))
                    .filter(p -> !p.toString().contains("/."))
                    .filter(p -> !isBuildArtifact(workspaceRoot, p))
                    .toList();

            // Pre-build Java SearchResult list (shared across all SQL files)
            List<SearchResult> javaResults = new ArrayList<>();
            for (Path jf : javaFiles) {
                String jRelPath = workspaceRoot.relativize(jf).toString();
                javaResults.add(new SearchResult(
                        jf, jRelPath, 1.0f, jf.getFileName().toString(),
                        "CODE", "Java", "", "", "", Files.size(jf)));
            }

            for (Path sqlFile : sqlFiles) {
                String relPath = workspaceRoot.relativize(sqlFile).toString();
                SearchResult sqlResult = new SearchResult(
                        sqlFile, relPath, 1.0f, sqlFile.getFileName().toString(),
                        "SQL", null, "", "", "", Files.size(sqlFile));

                try {
                    List<CrossFormatLinker.CrossFormatLink> links =
                            crossFormatLinker.findSqlToJavaLinks(sqlResult, javaResults, workspaceRoot);
                    for (CrossFormatLinker.CrossFormatLink link : links) {
                        allRecords.add(new CrossFormatLinkRecord(
                                wsPath, relPath, link.targetPath(),
                                "table-reference", link.entityName(), now));
                    }
                } catch (IOException e) {
                    LOG.fine("Cross-format link extraction failed for " + sqlFile + ": " + e.getMessage());
                }
            }
        }

        // Batch-insert all cross-format links (1000 per transaction)
        if (!allRecords.isEmpty()) {
            return repository.batchInsertCrossFormatLinks(conn, allRecords, 1000);
        }
        return 0;
    }

}
