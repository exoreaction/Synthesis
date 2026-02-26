package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CrossFormatLinkRecord;
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

    private static final Pattern JAVA_IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern JAVA_PACKAGE = Pattern.compile(
            "^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern JAVA_EXTENDS = Pattern.compile(
            "\\bextends\\s+([A-Z][\\w.]*)", Pattern.MULTILINE);
    private static final Pattern JAVA_IMPLEMENTS = Pattern.compile(
            "\\bimplements\\s+([A-Z][\\w.,\\s]+)", Pattern.MULTILINE);

    /** Directory names excluded by default (duplicates, vendored code). */
    private static final Set<String> ARCHIVE_DIR_NAMES = Set.of(
            "archive", "vendor", "node_modules"
    );

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

        // Find all Java files
        List<Path> javaFiles = findJavaFiles(workspaceRoot);
        // Build FQN-to-relative-path index for resolving imports
        Map<String, String> classToFile = buildClassToFileMap(javaFiles, workspaceRoot);
        // Build simple-name-to-FQN index for extends/implements resolution
        Map<String, List<String>> simpleNameIndex = buildSimpleNameIndex(classToFile);

        int dependenciesFound = 0;
        int externalDeps = 0;
        Set<String> packages = new HashSet<>();
        long now = Instant.now().getEpochSecond();

        for (Path javaFile : javaFiles) {
            try {
                String content = FileUtils.readPreview(javaFile, 50_000);
                String relPath = workspaceRoot.relativize(javaFile).toString();
                String className = extractClassName(javaFile);
                String packageName = extractPackage(content);
                String repoName = detectRepoName(workspaceRoot, javaFile);

                if (packageName != null) packages.add(packageName);

                List<String> imports = extractImports(content);
                for (String imp : imports) {
                    String targetClass = getSimpleClassName(imp);
                    String targetPackage = getPackageFromImport(imp);
                    // Look up by full import string (FQN) — not simple name
                    String targetFile = classToFile.get(imp);
                    boolean external = (targetFile == null);

                    CodeDependency dep = new CodeDependency(
                            wsPath, repoName, relPath, className,
                            packageName != null ? packageName : "",
                            targetFile, targetClass, targetPackage != null ? targetPackage : "",
                            "import", external, now
                    );
                    repository.upsertDependency(conn, dep);
                    dependenciesFound++;
                    if (external) externalDeps++;
                }

                // Extract extends/implements relationships
                List<CodeDependency> structuralDeps = extractStructuralDeps(
                        content, wsPath, repoName, relPath, className, packageName,
                        classToFile, simpleNameIndex, now);
                for (CodeDependency dep : structuralDeps) {
                    repository.upsertDependency(conn, dep);
                    dependenciesFound++;
                    if (dep.isExternal()) externalDeps++;
                }
            } catch (IOException e) {
                LOG.fine("Skipping unreadable file: " + javaFile + ": " + e.getMessage());
            }
        }

        // Cross-format links (SQL -> Java)
        int crossLinks = extractCrossFormatLinks(workspaceRoot, conn, javaFiles, classToFile, now);

        long elapsed = System.currentTimeMillis() - start;
        return new CodeGraphStats(javaFiles.size(), dependenciesFound, crossLinks,
                packages.size(), externalDeps, elapsed, Instant.now());
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
        List<Path> allJavaFiles = findJavaFiles(workspaceRoot);
        Map<String, String> classToFile = buildClassToFileMap(allJavaFiles, workspaceRoot);
        Map<String, List<String>> simpleNameIndex = buildSimpleNameIndex(classToFile);

        int dependenciesFound = 0;
        int externalDeps = 0;
        Set<String> packages = new HashSet<>();
        long now = Instant.now().getEpochSecond();
        int filesProcessed = 0;

        for (Path changedFile : changedFiles) {
            Path fullPath = changedFile.isAbsolute() ? changedFile : workspaceRoot.resolve(changedFile);
            if (!fullPath.toString().endsWith(".java") || !Files.exists(fullPath)) continue;

            String relPath = workspaceRoot.relativize(fullPath).toString();
            filesProcessed++;

            // Delete old edges for this file
            repository.deleteDependenciesForFile(conn, wsPath, relPath);

            try {
                String content = FileUtils.readPreview(fullPath, 50_000);
                String className = extractClassName(fullPath);
                String packageName = extractPackage(content);
                String repoName = detectRepoName(workspaceRoot, fullPath);

                if (packageName != null) packages.add(packageName);

                List<String> imports = extractImports(content);
                for (String imp : imports) {
                    String targetClass = getSimpleClassName(imp);
                    String targetPackage = getPackageFromImport(imp);
                    // Look up by full import string (FQN) — not simple name
                    String targetFile = classToFile.get(imp);
                    boolean external = (targetFile == null);

                    CodeDependency dep = new CodeDependency(
                            wsPath, repoName, relPath, className,
                            packageName != null ? packageName : "",
                            targetFile, targetClass, targetPackage != null ? targetPackage : "",
                            "import", external, now
                    );
                    repository.upsertDependency(conn, dep);
                    dependenciesFound++;
                    if (external) externalDeps++;
                }

                List<CodeDependency> structuralDeps = extractStructuralDeps(
                        content, wsPath, repoName, relPath, className, packageName,
                        classToFile, simpleNameIndex, now);
                for (CodeDependency dep : structuralDeps) {
                    repository.upsertDependency(conn, dep);
                    dependenciesFound++;
                    if (dep.isExternal()) externalDeps++;
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

    // -----------------------------------------------------------------------
    // Extraction helpers
    // -----------------------------------------------------------------------

    List<Path> findJavaFiles(Path root) throws IOException {
        // Identify non-Java repos to skip in multi-repo workspaces
        Set<String> skippedRepos = identifyNonJavaRepos(root);

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            Stream<Path> filtered = walk
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("/."))  // skip hidden dirs
                .filter(p -> !isBuildArtifact(root, p))
                .filter(p -> !isInSkippedRepo(root, p, skippedRepos));

            // Exclude archive/vendor/node_modules unless explicitly included (#279)
            if (!includeArchives) {
                filtered = filtered.filter(p -> !isArchiveDirectory(root, p));
            }

            filtered.forEach(files::add);
        }

        if (!skippedRepos.isEmpty()) {
            LOG.info("Skipped " + skippedRepos.size() + " non-Java repos: "
                    + String.join(", ", skippedRepos));
        }

        return files;
    }

    /**
     * Identifies top-level subdirectories that are not Java projects.
     * A directory is considered non-Java if it has no Java build file
     * (pom.xml, build.gradle, build.gradle.kts) AND contains zero .java files.
     *
     * @param root workspace root
     * @return set of directory names to skip
     */
    Set<String> identifyNonJavaRepos(Path root) throws IOException {
        Set<String> nonJavaRepos = new HashSet<>();

        try (Stream<Path> topLevel = Files.list(root)) {
            List<Path> subdirs = topLevel.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .toList();

            for (Path subdir : subdirs) {
                // Check for Java build files
                boolean hasBuildFile = Files.exists(subdir.resolve("pom.xml"))
                        || Files.exists(subdir.resolve("build.gradle"))
                        || Files.exists(subdir.resolve("build.gradle.kts"));

                if (!hasBuildFile) {
                    // No build file — check if there are ANY .java files
                    boolean hasJavaFiles;
                    try (Stream<Path> walk = Files.walk(subdir)) {
                        hasJavaFiles = walk.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(".java"))
                                .filter(p -> !p.toString().contains("/."))
                                .filter(p -> !isBuildArtifact(root, p))
                                .findFirst()
                                .isPresent();
                    }

                    if (!hasJavaFiles) {
                        nonJavaRepos.add(subdir.getFileName().toString());
                    }
                }
            }
        }

        return nonJavaRepos;
    }

    /**
     * Checks if a file is inside one of the skipped repo directories.
     */
    private boolean isInSkippedRepo(Path root, Path file, Set<String> skippedRepos) {
        if (skippedRepos.isEmpty()) return false;
        Path rel = root.relativize(file);
        if (rel.getNameCount() > 0) {
            return skippedRepos.contains(rel.getName(0).toString());
        }
        return false;
    }

    /**
     * Builds a map from fully-qualified class name (FQN) to relative file path.
     * For example: "com.example.UserService" -> "src/main/java/com/example/UserService.java".
     *
     * <p>This enables correct external/internal classification: when processing
     * {@code import org.springframework.stereotype.Service}, the lookup uses the
     * full import string "org.springframework.stereotype.Service" which won't match
     * the project's "com.example.Service" FQN.
     *
     * @param javaFiles      list of Java files to index
     * @param workspaceRoot  workspace root for computing relative paths
     * @return map of FQN to relative path
     */
    Map<String, String> buildClassToFileMap(List<Path> javaFiles, Path workspaceRoot) {
        Map<String, String> map = new HashMap<>();
        for (Path f : javaFiles) {
            String className = extractClassName(f);
            String relPath = workspaceRoot.relativize(f).toString();
            try {
                String content = FileUtils.readPreview(f, 2_000); // only need the top for package decl
                String pkg = extractPackage(content);
                if (pkg != null) {
                    String fqn = pkg + "." + className;
                    map.put(fqn, relPath);
                } else {
                    // No package declaration — use simple class name as key
                    map.put(className, relPath);
                }
            } catch (IOException e) {
                // Fallback: use simple name
                map.put(className, relPath);
            }
        }
        return map;
    }

    /**
     * Builds a reverse index from simple class name to set of FQN keys present
     * in the classToFile map. Used for extends/implements resolution where only
     * simple names are available.
     */
    Map<String, List<String>> buildSimpleNameIndex(Map<String, String> classToFileMap) {
        Map<String, List<String>> index = new HashMap<>();
        for (String fqn : classToFileMap.keySet()) {
            String simpleName = getSimpleClassName(fqn);
            index.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(fqn);
        }
        return index;
    }

    /**
     * Looks up a simple class name in the FQN map using the simple name index.
     * If exactly one project class has that simple name, returns its file path.
     * If multiple classes share the name, tries to match by source package proximity.
     * Returns null if no match (external class).
     */
    String lookupBySimpleName(String simpleName, String sourcePackage,
                               Map<String, String> classToFileMap,
                               Map<String, List<String>> simpleNameIndex) {
        List<String> fqns = simpleNameIndex.get(simpleName);
        if (fqns == null || fqns.isEmpty()) {
            return null; // external
        }
        if (fqns.size() == 1) {
            return classToFileMap.get(fqns.get(0));
        }
        // Multiple matches: prefer same package
        for (String fqn : fqns) {
            String pkg = getPackageFromImport(fqn);
            if (pkg.equals(sourcePackage)) {
                return classToFileMap.get(fqn);
            }
        }
        // No exact package match — return first (project-internal either way)
        return classToFileMap.get(fqns.get(0));
    }

    List<String> extractImports(String content) {
        List<String> imports = new ArrayList<>();
        Matcher m = JAVA_IMPORT.matcher(content);
        while (m.find()) {
            imports.add(m.group(1));
        }
        return imports;
    }

    String extractPackage(String content) {
        Matcher m = JAVA_PACKAGE.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    String extractClassName(Path javaFile) {
        String name = javaFile.getFileName().toString();
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    String getSimpleClassName(String fullyQualified) {
        int lastDot = fullyQualified.lastIndexOf('.');
        return lastDot >= 0 ? fullyQualified.substring(lastDot + 1) : fullyQualified;
    }

    String getPackageFromImport(String fullyQualified) {
        int lastDot = fullyQualified.lastIndexOf('.');
        return lastDot >= 0 ? fullyQualified.substring(0, lastDot) : "";
    }

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

    private List<CodeDependency> extractStructuralDeps(String content, String wsPath,
                                                        String repoName, String relPath,
                                                        String className, String packageName,
                                                        Map<String, String> classToFile,
                                                        Map<String, List<String>> simpleNameIndex,
                                                        long now) {
        List<CodeDependency> deps = new ArrayList<>();
        String pkg = packageName != null ? packageName : "";

        // extends
        Matcher extendsM = JAVA_EXTENDS.matcher(content);
        while (extendsM.find()) {
            String parentClass = getSimpleClassName(extendsM.group(1).trim());
            if (!parentClass.equals(className)) {
                // Use simple name index for extends (we only have simple name from source)
                String targetFile = lookupBySimpleName(parentClass, pkg,
                        classToFile, simpleNameIndex);
                deps.add(new CodeDependency(wsPath, repoName, relPath, className, pkg,
                        targetFile, parentClass, "", "extends",
                        targetFile == null, now));
            }
        }

        // implements
        Matcher implM = JAVA_IMPLEMENTS.matcher(content);
        while (implM.find()) {
            String interfaces = implM.group(1).trim();
            for (String iface : interfaces.split(",")) {
                String ifaceName = getSimpleClassName(iface.trim());
                if (!ifaceName.isBlank() && !ifaceName.equals(className)) {
                    // Use simple name index for implements
                    String targetFile = lookupBySimpleName(ifaceName, pkg,
                            classToFile, simpleNameIndex);
                    deps.add(new CodeDependency(wsPath, repoName, relPath, className, pkg,
                            targetFile, ifaceName, "", "implements",
                            targetFile == null, now));
                }
            }
        }

        return deps;
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
}
