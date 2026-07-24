package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CrossFormatLinkRecord;
import io.exoreaction.synthesis.graph.lang.Declaration;
import io.exoreaction.synthesis.graph.lang.ExclusionRules;
import io.exoreaction.synthesis.graph.lang.JavaLanguageExtractor;
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

    /**
     * Kotlin import. Unlike Java, the trailing {@code ;} is optional and imports may carry
     * an {@code as} alias ({@code import com.foo.Bar as Baz} -- alias is ignored, only the
     * FQN is captured) or a wildcard suffix ({@code import com.foo.*} -- caller drops these,
     * see {@link #extractKotlinImports}).
     */
    private static final Pattern KOTLIN_IMPORT = Pattern.compile(
            "^import\\s+([\\w.]+(?:\\.\\*)?)(?:\\s+as\\s+\\w+)?\\s*(?:;|$)", Pattern.MULTILINE);

    private static final Pattern KOTLIN_PACKAGE = Pattern.compile(
            "^package\\s+([\\w.]+)\\s*(?:;|$)", Pattern.MULTILINE);

    /**
     * Matches a top-level Kotlin type declaration ({@code class}/{@code interface}/{@code object},
     * optionally prefixed with modifiers -- {@code data}, {@code sealed}, {@code enum}, {@code value},
     * {@code annotation}, visibility, etc. -- which Kotlin allows in front of the bare keyword rather
     * than as compound keywords). No leading {@code \s*} before the anchor: nested/inner declarations
     * are indented in idiomatic Kotlin (ktlint/detekt-enforced in this codebase, verified against
     * real tvimenning-template source), so requiring column-0 is a cheap, effective filter against
     * matching non-top-level classes -- a real parser would use scope tracking instead.
     *
     * <p>Group 1: type name. Group 2 (optional): raw supertype list text after {@code :}, up to
     * {@code {} or end of line -- fed to {@link #splitKotlinSupertypes} for cleanup. Constructor-arg
     * parens and generic angle-brackets are matched non-greedily and are assumed non-nested (no
     * default-value calls like {@code = foo()} inside the primary constructor); this mirrors the
     * existing {@code JAVA_IMPLEMENTS} pattern's equally naive comma-split, not a regression.
     *
     * <p>{@code fun} appears in the modifier list for {@code fun interface} (SAM) declarations.
     * This cannot mis-match a top-level function: the regex still requires a following
     * {@code class}/{@code interface}/{@code object} keyword.
     */
    private static final Pattern KOTLIN_TOPLEVEL_DECL = Pattern.compile(
            "^(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*"
                    + "(?:(?:public|private|protected|internal|open|sealed|abstract|final|inner|data|enum|value|annotation|fun)\\s+)*"
                    + "(?:class|interface|object)\\s+"
                    + "([A-Z]\\w*)"
                    + "(?:\\s*<[^<>]*>)?"
                    + "(?:\\s*\\([^()]*\\))?"
                    + "(?:\\s*:\\s*([^{\\n]+))?",
            Pattern.MULTILINE);

    /** Directory names excluded by default (duplicates, vendored code). */
    private static final Set<String> ARCHIVE_DIR_NAMES = Set.of(
            "archive", "vendor", "node_modules"
    );

    private final CodeGraphRepository repository;
    private final CrossFormatLinker crossFormatLinker;
    private final JavaLanguageExtractor javaExtractor = new JavaLanguageExtractor();
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

        // Java pass 1 (declarations): discover files + register declared FQNs.
        List<Path> javaFiles = javaExtractor.findFiles(workspaceRoot, new ExclusionRules(includeArchives));
        Map<String, String> classToFile = new HashMap<>();
        Set<String> packages = new HashSet<>();
        List<Map.Entry<Path, List<Declaration>>> javaWork =
                registerJavaDeclarations(workspaceRoot, javaFiles, classToFile, packages);

        // Merge in Kotlin declarations BEFORE building the simple-name index, so Java<->Kotlin
        // cross-references resolve correctly in mixed repos (Kotlin imports are FQN-based just
        // like Java's, so it shares this same resolution machinery rather than needing a new
        // path-based resolver like the TypeScript support below).
        List<Path> kotlinFiles = findKotlinFiles(workspaceRoot);
        KotlinIndexes kotlinIndexes = buildKotlinIndexes(kotlinFiles, workspaceRoot);
        classToFile.putAll(kotlinIndexes.classToFile());
        Map<String, List<String>> kotlinPackageFunctionFiles = kotlinIndexes.packageFunctionFiles();

        // Build simple-name-to-FQN index for extends/implements/supertype resolution
        Map<String, List<String>> simpleNameIndex = Resolver.buildSimpleNameIndex(classToFile);
        Resolver resolver = new Resolver(classToFile, simpleNameIndex, kotlinPackageFunctionFiles);

        int dependenciesFound = 0;
        int externalDeps = 0;
        long now = Instant.now().getEpochSecond();

        // Java pass 2 (edges): resolve each edge against the full index and persist a row.
        for (Map.Entry<Path, List<Declaration>> work : javaWork) {
            Path javaFile = work.getKey();
            String relPath = workspaceRoot.relativize(javaFile).toString();
            try {
                String content = FileUtils.readPreview(javaFile, 50_000);
                String repoName = detectRepoName(workspaceRoot, javaFile);
                for (RawEdge edge : javaExtractor.edges(javaFile, content, work.getValue())) {
                    String targetFile = resolver.resolve(edge.to());
                    boolean external = (targetFile == null);
                    CodeDependency dep = new CodeDependency(
                            wsPath, repoName, relPath, edge.sourceClass(), edge.sourcePackage(),
                            targetFile, edge.targetClass(), edge.targetPackage(),
                            edge.dependencyType(), external, now);
                    repository.upsertDependency(conn, dep);
                    dependenciesFound++;
                    if (external) externalDeps++;
                }
            } catch (IOException e) {
                LOG.fine("Skipping unreadable file: " + javaFile + ": " + e.getMessage());
            }
        }

        // Kotlin support: reuses the merged classToFile/simpleNameIndex built above, same
        // resolution machinery as Java (see extractKotlinFiles for why this differs from TS).
        KtExtractionTotals ktTotals = extractKotlinFiles(
                workspaceRoot, conn, kotlinFiles, classToFile, simpleNameIndex,
                kotlinPackageFunctionFiles, packages, now);
        dependenciesFound += ktTotals.dependencies();
        externalDeps += ktTotals.external();

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

        // Build full FQN-to-file map (we need it for resolving imports)
        List<Path> allJavaFiles = javaExtractor.findFiles(workspaceRoot, new ExclusionRules(includeArchives));
        Map<String, String> classToFile = new HashMap<>();
        registerJavaDeclarations(workspaceRoot, allJavaFiles, classToFile, null);

        // Merge in Kotlin declarations across the whole workspace (not just changedFiles) so
        // resolution is correct even when only one side of a Java<->Kotlin reference changed.
        List<Path> allKotlinFiles = findKotlinFiles(workspaceRoot);
        KotlinIndexes kotlinIndexes = buildKotlinIndexes(allKotlinFiles, workspaceRoot);
        classToFile.putAll(kotlinIndexes.classToFile());
        Map<String, List<String>> kotlinPackageFunctionFiles = kotlinIndexes.packageFunctionFiles();

        Map<String, List<String>> simpleNameIndex = Resolver.buildSimpleNameIndex(classToFile);
        Resolver resolver = new Resolver(classToFile, simpleNameIndex, kotlinPackageFunctionFiles);

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
                KtExtractionTotals fileTotals = extractKotlinFile(
                        workspaceRoot, conn, fullPath, classToFile, simpleNameIndex,
                        kotlinPackageFunctionFiles, packages, now);
                dependenciesFound += fileTotals.dependencies();
                externalDeps += fileTotals.external();
                continue;
            }

            // Delete old edges for this file
            repository.deleteDependenciesForFile(conn, wsPath, relPath);

            try {
                String content = FileUtils.readPreview(fullPath, 50_000);
                String repoName = detectRepoName(workspaceRoot, fullPath);

                List<Declaration> decls = javaExtractor.declarations(fullPath, content);
                for (Declaration d : decls) {
                    if (d.key() instanceof ResolutionKey.FqnKey fk) {
                        String p = Resolver.getPackageFromImport(fk.fqn());
                        if (!p.isEmpty()) packages.add(p);
                    }
                }
                for (RawEdge edge : javaExtractor.edges(fullPath, content, decls)) {
                    String targetFile = resolver.resolve(edge.to());
                    boolean external = (targetFile == null);
                    CodeDependency dep = new CodeDependency(
                            wsPath, repoName, relPath, edge.sourceClass(), edge.sourcePackage(),
                            targetFile, edge.targetClass(), edge.targetPackage(),
                            edge.dependencyType(), external, now);
                    repository.upsertDependency(conn, dep);
                    dependenciesFound++;
                    if (external) externalDeps++;
                }
            } catch (IOException e) {
                LOG.fine("Skipping unreadable file: " + fullPath + ": " + e.getMessage());
            }
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

    /**
     * Java pass 1: reads each file's package/class declaration (via
     * {@link JavaLanguageExtractor#declarations}) and registers the resulting
     * FQN -> relative-path entries in {@code classToFile}. Returns the per-file
     * work list (file + its declarations) so pass 2 can emit edges without a
     * second declaration parse. Mirrors the former {@code buildClassToFileMap},
     * including its filename-stem fallback for an unreadable file.
     *
     * @param packages if non-null, each declared package is added here (stats)
     */
    private List<Map.Entry<Path, List<Declaration>>> registerJavaDeclarations(
            Path root, List<Path> javaFiles, Map<String, String> classToFile, Set<String> packages) {
        List<Map.Entry<Path, List<Declaration>>> work = new ArrayList<>();
        for (Path f : javaFiles) {
            String rel = root.relativize(f).toString();
            try {
                String content = FileUtils.readPreview(f, 2_000);
                List<Declaration> decls = javaExtractor.declarations(f, content);
                for (Declaration d : decls) {
                    if (d.key() instanceof ResolutionKey.FqnKey fk) {
                        classToFile.put(fk.fqn(), rel);
                        if (packages != null) {
                            String pkg = Resolver.getPackageFromImport(fk.fqn());
                            if (!pkg.isEmpty()) packages.add(pkg);
                        }
                    }
                }
                work.add(Map.entry(f, decls));
            } catch (IOException e) {
                // Fallback (as buildClassToFileMap did): key on the filename stem.
                String n = f.getFileName().toString();
                classToFile.put(n.endsWith(".java") ? n.substring(0, n.length() - 5) : n, rel);
            }
        }
        return work;
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

    // -----------------------------------------------------------------------
    // Kotlin support
    // -----------------------------------------------------------------------

    private record KtExtractionTotals(int dependencies, int external) {}

    /** A top-level Kotlin declaration found via {@link #KOTLIN_TOPLEVEL_DECL}. */
    record KotlinDecl(String name, List<String> supertypes) {}

    /**
     * Walks the workspace for {@code .kt} files, applying the same exclusion rules as
     * {@link #findTypeScriptFiles} (build artifacts, archive directories, hidden dirs) --
     * no {@code identifyNonJavaRepos}-style repo-skip logic is needed here, mirroring how
     * TS handles this (#323). {@code .kts} script files (Gradle Kotlin DSL, build scripts)
     * are excluded -- they aren't application source.
     */
    List<Path> findKotlinFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            Stream<Path> filtered = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".kt"))
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
     * Extracts non-wildcard import FQNs from Kotlin source. Wildcard imports
     * ({@code import x.*}) are dropped -- they don't name a specific class to resolve,
     * matching how {@code JAVA_IMPORT}'s stricter {@code ;}-terminated pattern already
     * fails to match Java wildcard imports today (pre-existing behavior, not a regression).
     */
    List<String> extractKotlinImports(String content) {
        List<String> imports = new ArrayList<>();
        Matcher m = KOTLIN_IMPORT.matcher(content);
        while (m.find()) {
            String imp = m.group(1);
            if (imp != null && !imp.endsWith(".*")) {
                imports.add(imp);
            }
        }
        return imports;
    }

    String extractKotlinPackage(String content) {
        Matcher m = KOTLIN_PACKAGE.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Fallback identity for a Kotlin file with no top-level type declaration (e.g. an
     * extension-function-only utility file like {@code StringExt.kt}).
     */
    String extractKotlinFileClassName(Path ktFile) {
        String name = ktFile.getFileName().toString();
        return name.endsWith(".kt") ? name.substring(0, name.length() - 3) : name;
    }

    /**
     * Picks which of a Kotlin file's top-level declarations owns the file's import edges.
     *
     * <p>Unlike Java (compiler-enforced: the one public top-level type must match the
     * filename), Kotlin allows several public top-level declarations per file in any order,
     * so "first declared" is an arbitrary tie-break with no correctness guarantee -- e.g. a
     * {@code data class} response type declared above the file's actual primary class would
     * silently steal all of that class's import edges. Prefer the declaration whose name
     * matches the filename (Kotlin's own strong convention, same one
     * {@link #extractKotlinFileClassName} assumes); fall back to the first declaration only
     * when nothing matches.
     */
    String choosePrimaryClass(List<KotlinDecl> decls, Path ktFile) {
        String fileBasedName = extractKotlinFileClassName(ktFile);
        if (decls.isEmpty()) return fileBasedName;
        return decls.stream()
                .filter(d -> d.name().equals(fileBasedName))
                .findFirst()
                .map(KotlinDecl::name)
                .orElse(decls.get(0).name());
    }

    /**
     * Finds every top-level type declaration in a Kotlin file. Unlike Java, one file may
     * declare zero (a pure extension-function/utility file), one, or several top-level
     * classes/interfaces/objects -- the filename-equals-classname convention
     * {@code JavaLanguageExtractor.extractClassName} relies on for Java is only a convention in Kotlin, not
     * enforced by the compiler.
     */
    List<KotlinDecl> findKotlinTopLevelDecls(String content) {
        List<KotlinDecl> decls = new ArrayList<>();
        Matcher m = KOTLIN_TOPLEVEL_DECL.matcher(content);
        while (m.find()) {
            decls.add(new KotlinDecl(m.group(1), splitKotlinSupertypes(m.group(2))));
        }
        return decls;
    }

    /**
     * Cleans a raw {@code : A(), B<T>} supertype-list capture into simple type names.
     * Strips constructor-call parens and generic angle-brackets (both assumed non-nested --
     * see {@link #KOTLIN_TOPLEVEL_DECL}'s caveat) before splitting on top-level commas.
     */
    List<String> splitKotlinSupertypes(String raw) {
        if (raw == null) return List.of();
        String cleaned = raw
                .replaceAll("<[^<>]*>", "")
                .replaceAll("\\([^()]*\\)", "");
        List<String> names = new ArrayList<>();
        for (String part : cleaned.split(",")) {
            String name = part.trim();
            if (name.matches("[A-Za-z_][\\w.]*")) {
                names.add(Resolver.getSimpleClassName(name));
            }
        }
        return names;
    }

    /** Combined result of {@link #buildKotlinIndexes}. */
    private record KotlinIndexes(Map<String, String> classToFile, Map<String, List<String>> packageFunctionFiles) {}

    /**
     * Single read+regex pass over {@code kotlinFiles} that builds both the FQN -> file index
     * ({@link #buildKotlinClassToFileMap}'s contract) and the package -> function-only-file
     * index ({@link #buildKotlinPackageFunctionFileIndex}'s contract) together. The two were
     * previously independent loops, each re-reading and re-parsing every Kotlin file with
     * {@link FileUtils#readPreview} + {@link #findKotlinTopLevelDecls} -- merged here so the
     * two production call sites ({@link #extractAndPersist} and the incremental path) only
     * pay for one I/O + regex pass per file. Per-file logic is unchanged from the two original
     * methods.
     */
    private KotlinIndexes buildKotlinIndexes(List<Path> kotlinFiles, Path workspaceRoot) {
        Map<String, String> classToFile = new HashMap<>();
        Map<String, List<String>> packageFunctionFiles = new HashMap<>();
        for (Path f : kotlinFiles) {
            String relPath = workspaceRoot.relativize(f).toString();
            try {
                String content = FileUtils.readPreview(f, 50_000);
                String pkg = extractKotlinPackage(content);
                List<KotlinDecl> decls = findKotlinTopLevelDecls(content);
                if (decls.isEmpty()) {
                    String fallback = extractKotlinFileClassName(f);
                    classToFile.put(pkg != null ? pkg + "." + fallback : fallback, relPath);
                    packageFunctionFiles.computeIfAbsent(pkg != null ? pkg : "", k -> new ArrayList<>()).add(relPath);
                } else {
                    for (KotlinDecl decl : decls) {
                        classToFile.put(pkg != null ? pkg + "." + decl.name() : decl.name(), relPath);
                    }
                }
            } catch (IOException e) {
                classToFile.put(extractKotlinFileClassName(f), relPath);
            }
        }
        return new KotlinIndexes(classToFile, packageFunctionFiles);
    }

    /**
     * Builds FQN -> file entries for every top-level Kotlin declaration in {@code kotlinFiles},
     * to be merged into the same map Java uses ({@code JavaLanguageExtractor} declarations) so Java<->Kotlin
     * cross-references resolve correctly in mixed repos. A file with zero declarations gets one
     * filename-derived fallback entry (mirrors Java's filename-based behavior) so it still has
     * a stable identity for edge attribution.
     *
     * <p>Delegates to {@link #buildKotlinIndexes}; kept as its own method (rather than inlined
     * at call sites) since it's exercised directly by unit tests.
     */
    Map<String, String> buildKotlinClassToFileMap(List<Path> kotlinFiles, Path workspaceRoot) {
        return buildKotlinIndexes(kotlinFiles, workspaceRoot).classToFile();
    }

    /**
     * Builds package -> [file] index for Kotlin files with zero top-level type declarations
     * (pure top-level-function/property files, e.g. {@code Utils.kt} containing only
     * {@code fun doThing()}). The Kotlin compiler compiles such declarations into a synthetic
     * {@code <FileName>Kt} facade class, but source-level imports name the function directly
     * ({@code import pkg.doThing}), never the facade ({@code import pkg.UtilsKt}) -- so
     * {@link #buildKotlinClassToFileMap}'s FQN map can never contain a matching key for these
     * imports. This index lets {@link #extractKotlinFile} fall back to same-package resolution:
     * if exactly one function-only file exists in the imported symbol's package, attribute the
     * edge to it. Ambiguous (more than one candidate) or empty stays external -- same
     * conservative default as today, just narrowed to the genuinely unresolvable cases.
     *
     * <p>Delegates to {@link #buildKotlinIndexes}; kept as its own method (rather than inlined
     * at call sites) since it's exercised directly by unit tests.
     */
    Map<String, List<String>> buildKotlinPackageFunctionFileIndex(List<Path> kotlinFiles, Path workspaceRoot) {
        return buildKotlinIndexes(kotlinFiles, workspaceRoot).packageFunctionFiles();
    }

    private KtExtractionTotals extractKotlinFiles(Path workspaceRoot, Connection conn,
                                                   List<Path> kotlinFiles,
                                                   Map<String, String> classToFile,
                                                   Map<String, List<String>> simpleNameIndex,
                                                   Map<String, List<String>> packageFunctionFiles,
                                                   Set<String> packages,
                                                   long now) throws SQLException {
        int deps = 0;
        int external = 0;
        for (Path ktFile : kotlinFiles) {
            KtExtractionTotals fileTotals = extractKotlinFile(
                    workspaceRoot, conn, ktFile, classToFile, simpleNameIndex,
                    packageFunctionFiles, packages, now);
            deps += fileTotals.dependencies();
            external += fileTotals.external();
        }
        return new KtExtractionTotals(deps, external);
    }

    /**
     * Extracts and persists import + supertype dependency edges for a single Kotlin file,
     * using the shared FQN map built across Java + Kotlin for internal/external
     * classification -- the same resolution machinery the Java loop in
     * {@link #extractAndPersist} uses, not a new path-based resolver like TypeScript needed.
     * Structural (supertype) edges use dependency type {@code "supertype"}, distinct from
     * Java's separate {@code "extends"}/{@code "implements"} types since Kotlin's colon-based
     * inheritance syntax doesn't distinguish the two at the syntax level.
     */
    private KtExtractionTotals extractKotlinFile(Path workspaceRoot, Connection conn,
                                                  Path ktFile,
                                                  Map<String, String> classToFile,
                                                  Map<String, List<String>> simpleNameIndex,
                                                  Map<String, List<String>> packageFunctionFiles,
                                                  Set<String> packages,
                                                  long now) throws SQLException {
        int deps = 0;
        int external = 0;
        String wsPath = workspaceRoot.toString();
        String relPath = workspaceRoot.relativize(ktFile).toString();
        String repoName = detectRepoName(workspaceRoot, ktFile);

        String content;
        try {
            content = FileUtils.readPreview(ktFile, 50_000);
        } catch (IOException e) {
            LOG.fine("Skipping unreadable file: " + ktFile + ": " + e.getMessage());
            return new KtExtractionTotals(0, 0);
        }

        String packageName = extractKotlinPackage(content);
        if (packageName != null) packages.add(packageName);
        List<KotlinDecl> decls = findKotlinTopLevelDecls(content);
        String primaryClass = choosePrimaryClass(decls, ktFile);

        for (String imp : extractKotlinImports(content)) {
            String targetClass = Resolver.getSimpleClassName(imp);
            String targetPackage = Resolver.getPackageFromImport(imp);
            String targetFile = classToFile.get(imp);

            // Fallback for imports of top-level functions/properties: these have no
            // classToFile entry (see buildKotlinPackageFunctionFileIndex) because the import
            // names the symbol directly, not the compiler-synthesized <FileName>Kt facade.
            // Only resolve when exactly one function-only file exists in the target package --
            // ambiguous cases stay external rather than guess.
            if (targetFile == null) {
                List<String> candidates = packageFunctionFiles.get(targetPackage);
                if (candidates != null && candidates.size() == 1) {
                    targetFile = candidates.get(0);
                }
            }
            boolean isExternal = (targetFile == null);

            CodeDependency dep = new CodeDependency(
                    wsPath, repoName, relPath, primaryClass,
                    packageName != null ? packageName : "",
                    targetFile, targetClass, targetPackage != null ? targetPackage : "",
                    "import", isExternal, now);
            repository.upsertDependency(conn, dep);
            deps++;
            if (isExternal) external++;
        }

        for (KotlinDecl decl : decls) {
            for (String supertype : decl.supertypes()) {
                if (supertype.equals(decl.name())) continue; // guard against a malformed capture
                String targetFile = Resolver.lookupBySimpleName(supertype,
                        packageName != null ? packageName : "", classToFile, simpleNameIndex);
                boolean isExternal = (targetFile == null);

                CodeDependency dep = new CodeDependency(
                        wsPath, repoName, relPath, decl.name(),
                        packageName != null ? packageName : "",
                        targetFile, supertype, "",
                        "supertype", isExternal, now);
                repository.upsertDependency(conn, dep);
                deps++;
                if (isExternal) external++;
            }
        }

        return new KtExtractionTotals(deps, external);
    }
}
