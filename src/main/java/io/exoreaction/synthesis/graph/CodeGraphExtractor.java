package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CrossFormatLinkRecord;
import io.exoreaction.synthesis.graph.lang.Declaration;
import io.exoreaction.synthesis.graph.lang.ExclusionRules;
import io.exoreaction.synthesis.graph.lang.JavaLanguageExtractor;
import io.exoreaction.synthesis.graph.lang.KotlinLanguageExtractor;
import io.exoreaction.synthesis.graph.lang.LanguageExtractor;
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

    /**
     * Matches ES6 import / require references and captures the module specifier.
     *
     * <p>Covers four TypeScript/JavaScript import forms:
     * <ul>
     *   <li>{@code import X from 'specifier'} — default import</li>
     *   <li>{@code import { X } from 'specifier'} — named import</li>
     *   <li>{@code import 'specifier'} — side-effect import</li>
     *   <li>{@code require('specifier')} — CommonJS</li>
     *   <li>{@code export ... from 'specifier'} — re-export</li>
     * </ul>
     * The key insight: for named / default imports the specifier follows {@code from},
     * not {@code import} directly. Using {@code (?:from|import)\s+} as the prefix
     * captures both cases with a single group.
     */
    private static final Pattern JS_TS_IMPORT = Pattern.compile(
            "(?:\\b(?:from|import)\\s+|require\\s*\\()['\"]([^'\"]+)['\"]",
            Pattern.MULTILINE);

    /** Directory names excluded by default (duplicates, vendored code). */
    private static final Set<String> ARCHIVE_DIR_NAMES = Set.of(
            "archive", "vendor", "node_modules"
    );

    private final CodeGraphRepository repository;
    private final CrossFormatLinker crossFormatLinker;
    private final JavaLanguageExtractor javaExtractor = new JavaLanguageExtractor();
    private final KotlinLanguageExtractor kotlinExtractor = new KotlinLanguageExtractor();
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
        Set<String> packages = new HashSet<>();

        List<Path> javaFiles = javaExtractor.findFiles(workspaceRoot, excl);
        List<Map.Entry<Path, List<Declaration>>> javaWork =
                registerDeclarations(javaExtractor, workspaceRoot, javaFiles, classToFile, packageFunctionFiles, packages);

        List<Path> kotlinFiles = kotlinExtractor.findFiles(workspaceRoot, excl);
        List<Map.Entry<Path, List<Declaration>>> kotlinWork =
                registerDeclarations(kotlinExtractor, workspaceRoot, kotlinFiles, classToFile, packageFunctionFiles, packages);

        Map<String, List<String>> simpleNameIndex = Resolver.buildSimpleNameIndex(classToFile);
        Resolver resolver = new Resolver(classToFile, simpleNameIndex, packageFunctionFiles);

        long now = Instant.now().getEpochSecond();

        // Pass 2 (edges): resolve each edge against the full index and persist rows.
        EdgeTotals javaTotals = persistEdges(javaExtractor, workspaceRoot, javaWork, resolver, conn, wsPath, now);
        EdgeTotals kotlinTotals = persistEdges(kotlinExtractor, workspaceRoot, kotlinWork, resolver, conn, wsPath, now);
        int dependenciesFound = javaTotals.dependencies() + kotlinTotals.dependencies();
        int externalDeps = javaTotals.external() + kotlinTotals.external();

        // TypeScript / TSX support (#323): mirror the Java extraction so impact and
        // graph queries see edges for Bun/NodeNext projects.
        List<Path> tsFiles = findTypeScriptFiles(workspaceRoot);
        Map<String, String> tsPathIndex = buildTsPathIndex(tsFiles, workspaceRoot);
        TsExtractionTotals tsTotals = extractTypeScript(
                workspaceRoot, conn, tsFiles, tsPathIndex, now);
        dependenciesFound += tsTotals.dependencies;
        externalDeps += tsTotals.external;

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

        // Build the full FQN-to-file index across the whole workspace (both languages) so
        // resolution is correct even when only one side of a Java<->Kotlin reference changed.
        ExclusionRules excl = new ExclusionRules(includeArchives);
        Map<String, String> classToFile = new HashMap<>();
        Map<String, List<String>> packageFunctionFiles = new HashMap<>();

        List<Path> allJavaFiles = javaExtractor.findFiles(workspaceRoot, excl);
        registerDeclarations(javaExtractor, workspaceRoot, allJavaFiles, classToFile, packageFunctionFiles, null);
        List<Path> allKotlinFiles = kotlinExtractor.findFiles(workspaceRoot, excl);
        registerDeclarations(kotlinExtractor, workspaceRoot, allKotlinFiles, classToFile, packageFunctionFiles, null);

        Map<String, List<String>> simpleNameIndex = Resolver.buildSimpleNameIndex(classToFile);
        Resolver resolver = new Resolver(classToFile, simpleNameIndex, packageFunctionFiles);

        // TypeScript path index for resolving incremental TS imports (#323).
        List<Path> allTsFiles = findTypeScriptFiles(workspaceRoot);
        Map<String, String> tsPathIndex = buildTsPathIndex(allTsFiles, workspaceRoot);

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
                TsExtractionTotals fileTotals = extractTypeScriptFile(
                        workspaceRoot, conn, fullPath, tsPathIndex, now);
                dependenciesFound += fileTotals.dependencies;
                externalDeps += fileTotals.external;
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
            Set<String> packages) {
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
                    String targetFile = resolver.resolve(edge.to());
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
                String targetFile = resolver.resolve(edge.to());
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

    /** Aggregate counters returned by the TypeScript extraction helpers. */
    private record TsExtractionTotals(int dependencies, int external) {}

    /**
     * Walks the workspace for {@code .ts} and {@code .tsx} files, applying the same
     * exclusion rules used for Java (build artifacts, archive directories, hidden dirs).
     * Declaration files ({@code .d.ts}) are excluded — they describe ambient types and
     * would inflate the graph with synthetic edges.
     */
    List<Path> findTypeScriptFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            Stream<Path> filtered = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String s = p.toString();
                        return (s.endsWith(".ts") || s.endsWith(".tsx")) && !s.endsWith(".d.ts");
                    })
                    .filter(p -> !p.toString().contains("/."))
                    .filter(p -> !isBuildArtifact(root, p));
            if (!includeArchives) {
                filtered = filtered.filter(p -> !isArchiveDirectory(root, p));
            }
            filtered.forEach(files::add);
        }
        return files;
    }

    /**
     * Builds a lookup for resolving relative TypeScript imports. Each TS file is
     * indexed by both:
     * <ul>
     *   <li>its full relative path with extension stripped (e.g. {@code src/foo/Bar})</li>
     *   <li>the same path with {@code /index} appended for directory-style imports</li>
     * </ul>
     * Both entries point at the actual relative path including extension.
     *
     * <p>The index uses forward slashes so it works on Windows as well.
     */
    Map<String, String> buildTsPathIndex(List<Path> tsFiles, Path workspaceRoot) {
        Map<String, String> index = new HashMap<>();
        for (Path f : tsFiles) {
            String rel = workspaceRoot.relativize(f).toString().replace('\\', '/');
            String stem = stripTsExtension(rel);
            index.put(stem, rel);
            // Directory-style import: `import './foo'` may resolve to `foo/index.ts`.
            if (stem.endsWith("/index")) {
                index.put(stem.substring(0, stem.length() - "/index".length()), rel);
            }
        }
        return index;
    }

    private static String stripTsExtension(String path) {
        if (path.endsWith(".tsx")) return path.substring(0, path.length() - 4);
        if (path.endsWith(".ts")) return path.substring(0, path.length() - 3);
        return path;
    }

    /** Bulk extraction over a list of TypeScript files. */
    private TsExtractionTotals extractTypeScript(Path workspaceRoot, Connection conn,
                                                  List<Path> tsFiles,
                                                  Map<String, String> tsPathIndex,
                                                  long now) throws SQLException {
        int deps = 0;
        int external = 0;
        for (Path tsFile : tsFiles) {
            TsExtractionTotals fileTotals = extractTypeScriptFile(
                    workspaceRoot, conn, tsFile, tsPathIndex, now);
            deps += fileTotals.dependencies;
            external += fileTotals.external;
        }
        return new TsExtractionTotals(deps, external);
    }

    /**
     * Extracts and persists imports for a single TypeScript file. Bare-module specifiers
     * (e.g. {@code 'react'}) are recorded as external dependencies; relative specifiers
     * (e.g. {@code './Foo.js'}) are resolved against the source file's directory and the
     * TS path index — applying the {@code .js} -> {@code .ts}/{@code .tsx} rewrite that
     * Bun/NodeNext projects rely on (#323).
     */
    private TsExtractionTotals extractTypeScriptFile(Path workspaceRoot, Connection conn,
                                                      Path tsFile,
                                                      Map<String, String> tsPathIndex,
                                                      long now) throws SQLException {
        int deps = 0;
        int external = 0;
        String wsPath = workspaceRoot.toString();
        String relPath = workspaceRoot.relativize(tsFile).toString().replace('\\', '/');
        String repoName = detectRepoName(workspaceRoot, tsFile);
        String sourceModule = stripTsExtension(tsFile.getFileName().toString());

        String content;
        try {
            content = FileUtils.readPreview(tsFile, 50_000);
        } catch (IOException e) {
            LOG.fine("Skipping unreadable file: " + tsFile + ": " + e.getMessage());
            return new TsExtractionTotals(0, 0);
        }

        Set<String> seenSpecifiers = new LinkedHashSet<>();
        Matcher m = JS_TS_IMPORT.matcher(content);
        while (m.find()) {
            String spec = m.group(1);
            if (spec != null && !spec.isBlank()) seenSpecifiers.add(spec);
        }

        for (String spec : seenSpecifiers) {
            String targetFile = resolveTypeScriptImport(spec, relPath, tsPathIndex);
            String targetClass = simpleSpecifierName(spec);
            boolean isExternal = (targetFile == null);

            CodeDependency dep = new CodeDependency(
                    wsPath, repoName, relPath, sourceModule, "",
                    targetFile, targetClass, "",
                    "import", isExternal, now);
            repository.upsertDependency(conn, dep);
            deps++;
            if (isExternal) external++;
        }
        return new TsExtractionTotals(deps, external);
    }

    /**
     * Resolves a TypeScript import specifier to a workspace-relative file path, or
     * returns {@code null} if the specifier is a bare module (npm / external).
     *
     * <p>Honours the {@code .js} -> {@code .ts}/{@code .tsx} rewrite required by
     * Bun/NodeNext projects where source code imports its own files using the
     * compiled extension (#323).
     */
    String resolveTypeScriptImport(String spec, String sourceRelPath,
                                    Map<String, String> tsPathIndex) {
        if (spec == null || spec.isBlank()) return null;
        String normalized = spec.replace('\\', '/');
        // Strip query strings or fragments occasionally seen in bundler imports.
        int q = normalized.indexOf('?');
        if (q >= 0) normalized = normalized.substring(0, q);

        boolean relative = normalized.startsWith("./") || normalized.startsWith("../");
        if (!relative) return null; // bare module -> external

        Path sourceDir = Path.of(sourceRelPath).getParent();
        String basePath = (sourceDir == null ? "" : sourceDir.toString().replace('\\', '/'));
        String joined = basePath.isEmpty() ? normalized : basePath + "/" + normalized;
        String resolved = normalizeRelativePath(joined);

        // Attempt 1: direct lookup with whatever extension was supplied (after stripping).
        String stem = stripTsExtension(resolved);
        // The `.js` / `.jsx` rewrite: drop the JS extension so the stem can match `.ts`/`.tsx`.
        if (stem.endsWith(".js")) stem = stem.substring(0, stem.length() - 3);
        else if (stem.endsWith(".jsx")) stem = stem.substring(0, stem.length() - 4);

        String hit = tsPathIndex.get(stem);
        if (hit != null) return hit;

        // Attempt 2: directory-style import -> `<stem>/index.ts(x)`.
        return tsPathIndex.get(stem + "/index");
    }

    /** Normalizes a path segment list, collapsing {@code .} and {@code ..} entries. */
    private static String normalizeRelativePath(String path) {
        Deque<String> stack = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (!stack.isEmpty() && !"..".equals(stack.peekLast())) stack.removeLast();
                else stack.addLast("..");
            } else {
                stack.addLast(segment);
            }
        }
        return String.join("/", stack);
    }

    /** Extracts the trailing identifier from a module specifier (best-effort). */
    private static String simpleSpecifierName(String spec) {
        String trimmed = spec.replace('\\', '/');
        int slash = trimmed.lastIndexOf('/');
        String last = (slash >= 0) ? trimmed.substring(slash + 1) : trimmed;
        // Drop common extensions for a cleaner display name.
        if (last.endsWith(".tsx")) last = last.substring(0, last.length() - 4);
        else if (last.endsWith(".ts")) last = last.substring(0, last.length() - 3);
        else if (last.endsWith(".jsx")) last = last.substring(0, last.length() - 4);
        else if (last.endsWith(".js")) last = last.substring(0, last.length() - 3);
        return last.isBlank() ? spec : last;
    }

}
