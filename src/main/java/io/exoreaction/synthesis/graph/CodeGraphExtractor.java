package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CrossFormatLinkRecord;
import io.exoreaction.synthesis.graph.lang.Declaration;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Extracts code dependency information from source files and persists it
 * to the code knowledge graph tables via {@link CodeGraphRepository}.
 *
 * <p>Wraps existing extraction capabilities:
 * <ul>
 *   <li>Java import extraction (pattern-based, similar to {@link ViolationDetector})</li>
 *   <li>Cross-format links via {@link CrossFormatLinker}</li>
 * </ul>
 *
 * <p>Supports both full extraction and incremental updates for changed files.
 */
public class CodeGraphExtractor {

    private static final Logger LOG = Logger.getLogger(CodeGraphExtractor.class.getName());

    /** Directory names excluded by default (duplicates, vendored code). */
    private static final Set<String> ARCHIVE_DIR_NAMES = Set.of(
            "archive", "vendor", "node_modules"
    );

    private final CodeGraphRepository repository;
    private final CrossFormatLinker crossFormatLinker;
    private final JavaLanguageExtractor javaExtractor = new JavaLanguageExtractor();
    private final KotlinLanguageExtractor kotlinExtractor = new KotlinLanguageExtractor();
    private final TypeScriptLanguageExtractor tsExtractor = new TypeScriptLanguageExtractor();
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
     * Full extraction: scans all Java files under workspaceRoot, extracts
     * dependencies, and persists them. Clears existing data first.
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

        // Pass 1 (declarations, always full): discover files, register declared FQNs, and
        // collect any per-language resolver fallback indexes. Java + Kotlin share the FQN
        // resolution machinery, so both declare into the same index before any edge resolves.
        ExclusionRules excl = new ExclusionRules(includeArchives);
        Map<String, String> classToFile = new HashMap<>();
        Map<String, List<String>> packageFunctionFiles = new HashMap<>();
        Map<String, String> tsPathIndex = new HashMap<>();
        Set<String> packages = new HashSet<>();

        List<Path> javaFiles = javaExtractor.findFiles(workspaceRoot, excl);
        List<Map.Entry<Path, List<Declaration>>> javaWork =
                registerDeclarations(javaExtractor, workspaceRoot, javaFiles, classToFile, packageFunctionFiles, tsPathIndex, packages);

        List<Path> kotlinFiles = kotlinExtractor.findFiles(workspaceRoot, excl);
        List<Map.Entry<Path, List<Declaration>>> kotlinWork =
                registerDeclarations(kotlinExtractor, workspaceRoot, kotlinFiles, classToFile, packageFunctionFiles, tsPathIndex, packages);

        List<Path> tsFiles = tsExtractor.findFiles(workspaceRoot, excl);
        List<Map.Entry<Path, List<Declaration>>> tsWork =
                registerDeclarations(tsExtractor, workspaceRoot, tsFiles, classToFile, packageFunctionFiles, tsPathIndex, packages);

        Map<String, List<String>> simpleNameIndex = Resolver.buildSimpleNameIndex(classToFile);
        Resolver resolver = new Resolver(classToFile, simpleNameIndex, packageFunctionFiles, tsPathIndex);

        long now = Instant.now().getEpochSecond();

        // Pass 2 (edges): resolve each edge against the full index and persist rows.
        EdgeTotals javaTotals = persistEdges(javaExtractor, workspaceRoot, javaWork, resolver, conn, wsPath, now);
        EdgeTotals kotlinTotals = persistEdges(kotlinExtractor, workspaceRoot, kotlinWork, resolver, conn, wsPath, now);
        EdgeTotals tsTotals = persistEdges(tsExtractor, workspaceRoot, tsWork, resolver, conn, wsPath, now);
        int dependenciesFound = javaTotals.dependencies() + kotlinTotals.dependencies() + tsTotals.dependencies();
        int externalDeps = javaTotals.external() + kotlinTotals.external() + tsTotals.external();

        // Cross-format links (SQL -> Java)
        int crossLinks = extractCrossFormatLinks(workspaceRoot, conn, javaFiles, classToFile, now);

        long elapsed = System.currentTimeMillis() - start;
        return new CodeGraphStats(javaFiles.size() + kotlinFiles.size() + tsFiles.size(), dependenciesFound,
                crossLinks, packages.size(), externalDeps, elapsed, Instant.now());
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

        List<Path> allJavaFiles = javaExtractor.findFiles(workspaceRoot, excl);
        registerDeclarations(javaExtractor, workspaceRoot, allJavaFiles, classToFile, packageFunctionFiles, tsPathIndex, null);
        List<Path> allKotlinFiles = kotlinExtractor.findFiles(workspaceRoot, excl);
        registerDeclarations(kotlinExtractor, workspaceRoot, allKotlinFiles, classToFile, packageFunctionFiles, tsPathIndex, null);
        List<Path> allTsFiles = tsExtractor.findFiles(workspaceRoot, excl);
        registerDeclarations(tsExtractor, workspaceRoot, allTsFiles, classToFile, packageFunctionFiles, tsPathIndex, null);

        Map<String, List<String>> simpleNameIndex = Resolver.buildSimpleNameIndex(classToFile);
        Resolver resolver = new Resolver(classToFile, simpleNameIndex, packageFunctionFiles, tsPathIndex);

        int dependenciesFound = 0;
        int externalDeps = 0;
        Set<String> packages = new HashSet<>();
        long now = Instant.now().getEpochSecond();
        int filesProcessed = 0;

        for (Path changedFile : changedFiles) {
            Path fullPath = changedFile.isAbsolute() ? changedFile : workspaceRoot.resolve(changedFile);
            if (!Files.exists(fullPath)) continue;

            String pathStr = fullPath.toString();
            boolean isJava = pathStr.endsWith(".java");
            boolean isKotlin = pathStr.endsWith(".kt");
            boolean isTypeScript = pathStr.endsWith(".ts") || pathStr.endsWith(".tsx");
            if (!isJava && !isKotlin && !isTypeScript) continue;

            String relPath = workspaceRoot.relativize(fullPath).toString();
            filesProcessed++;

            if (isTypeScript) {
                // Delete old edges for this TS file and re-extract.
                repository.deleteDependenciesForFile(conn, wsPath, relPath);
                EdgeTotals ts = persistChangedFile(tsExtractor, workspaceRoot, fullPath,
                        resolver, packages, conn, wsPath, relPath, now);
                dependenciesFound += ts.dependencies();
                externalDeps += ts.external();
                continue;
            }

            if (isKotlin) {
                // Delete old edges for this Kotlin file and re-extract.
                repository.deleteDependenciesForFile(conn, wsPath, relPath);
                EdgeTotals kt = persistChangedFile(kotlinExtractor, workspaceRoot, fullPath,
                        resolver, packages, conn, wsPath, relPath, now);
                dependenciesFound += kt.dependencies();
                externalDeps += kt.external();
                continue;
            }

            // Delete old edges for this file, then re-extract.
            repository.deleteDependenciesForFile(conn, wsPath, relPath);
            EdgeTotals jv = persistChangedFile(javaExtractor, workspaceRoot, fullPath,
                    resolver, packages, conn, wsPath, relPath, now);
            dependenciesFound += jv.dependencies();
            externalDeps += jv.external();
        }

        long elapsed = System.currentTimeMillis() - start;
        return new CodeGraphStats(filesProcessed, dependenciesFound, 0,
                packages.size(), externalDeps, elapsed, Instant.now());
    }

    /**
     * Returns the repository for direct queries.
     */
    public CodeGraphRepository getRepository() {
        return repository;
    }

    /** Per-language edge counters accumulated across a pass. */
    private record EdgeTotals(int dependencies, int external) {}

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
     * {@code code_dependencies} row.
     */
    private EdgeTotals persistEdges(LanguageExtractor lang, Path root,
            List<Map.Entry<Path, List<Declaration>>> work, Resolver resolver,
            Connection conn, String wsPath, long now) throws SQLException {
        int deps = 0;
        int external = 0;
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
                    deps++;
                    if (isExternal) external++;
                }
            } catch (IOException e) {
                LOG.fine("Skipping unreadable file: " + f + ": " + e.getMessage());
            }
        }
        return new EdgeTotals(deps, external);
    }

    /**
     * Incremental pass 2 for a single changed file: derives the file's declared packages
     * (stats) and persists its edges (targets resolved against the full {@code resolver}).
     * The caller has already deleted the file's old rows.
     */
    private EdgeTotals persistChangedFile(LanguageExtractor lang, Path root, Path file,
            Resolver resolver, Set<String> packages, Connection conn, String wsPath,
            String relPath, long now) throws SQLException {
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
            int deps = 0;
            int external = 0;
            for (RawEdge edge : lang.edges(file, content, decls)) {
                String targetFile = resolver.resolve(edge.to(), relPath);
                boolean isExternal = (targetFile == null);
                CodeDependency dep = new CodeDependency(
                        wsPath, repoName, relPath, edge.sourceClass(), edge.sourcePackage(),
                        targetFile, edge.targetClass(), edge.targetPackage(),
                        edge.dependencyType(), isExternal, now);
                repository.upsertDependency(conn, dep);
                deps++;
                if (isExternal) external++;
            }
            return new EdgeTotals(deps, external);
        } catch (IOException e) {
            LOG.fine("Skipping unreadable file: " + file + ": " + e.getMessage());
            return new EdgeTotals(0, 0);
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
                                         Map<String, String> classToFile,
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

    // -----------------------------------------------------------------------
    // TypeScript / TSX support (#323)
    // -----------------------------------------------------------------------

}
