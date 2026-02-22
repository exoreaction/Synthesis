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

    private final CodeGraphRepository repository;
    private final CrossFormatLinker crossFormatLinker;

    public CodeGraphExtractor() {
        this.repository = new CodeGraphRepository();
        this.crossFormatLinker = new CrossFormatLinker();
    }

    public CodeGraphExtractor(CodeGraphRepository repository, CrossFormatLinker crossFormatLinker) {
        this.repository = repository;
        this.crossFormatLinker = crossFormatLinker;
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
        // Build a file-name to relative-path index for resolving imports
        Map<String, String> classToFile = buildClassToFileMap(javaFiles, workspaceRoot);

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

                if (packageName != null) packages.add(packageName);

                List<String> imports = extractImports(content);
                for (String imp : imports) {
                    String targetClass = getSimpleClassName(imp);
                    String targetPackage = getPackageFromImport(imp);
                    String targetFile = classToFile.get(targetClass);
                    boolean external = (targetFile == null);

                    CodeDependency dep = new CodeDependency(
                            wsPath, relPath, className, packageName != null ? packageName : "",
                            targetFile, targetClass, targetPackage != null ? targetPackage : "",
                            "import", external, now
                    );
                    repository.upsertDependency(conn, dep);
                    dependenciesFound++;
                    if (external) externalDeps++;
                }

                // Extract extends/implements relationships
                List<CodeDependency> structuralDeps = extractStructuralDeps(
                        content, wsPath, relPath, className, packageName, classToFile, now);
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

        // Build full class-to-file map (we need it for resolving imports)
        List<Path> allJavaFiles = findJavaFiles(workspaceRoot);
        Map<String, String> classToFile = buildClassToFileMap(allJavaFiles, workspaceRoot);

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

                if (packageName != null) packages.add(packageName);

                List<String> imports = extractImports(content);
                for (String imp : imports) {
                    String targetClass = getSimpleClassName(imp);
                    String targetPackage = getPackageFromImport(imp);
                    String targetFile = classToFile.get(targetClass);
                    boolean external = (targetFile == null);

                    CodeDependency dep = new CodeDependency(
                            wsPath, relPath, className, packageName != null ? packageName : "",
                            targetFile, targetClass, targetPackage != null ? targetPackage : "",
                            "import", external, now
                    );
                    repository.upsertDependency(conn, dep);
                    dependenciesFound++;
                    if (external) externalDeps++;
                }

                List<CodeDependency> structuralDeps = extractStructuralDeps(
                        content, wsPath, relPath, className, packageName, classToFile, now);
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
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("/."))  // skip hidden dirs
                .forEach(files::add);
        }
        return files;
    }

    Map<String, String> buildClassToFileMap(List<Path> javaFiles, Path workspaceRoot) {
        Map<String, String> map = new HashMap<>();
        for (Path f : javaFiles) {
            String className = extractClassName(f);
            String relPath = workspaceRoot.relativize(f).toString();
            map.put(className, relPath);
        }
        return map;
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

    private List<CodeDependency> extractStructuralDeps(String content, String wsPath,
                                                        String relPath, String className,
                                                        String packageName,
                                                        Map<String, String> classToFile,
                                                        long now) {
        List<CodeDependency> deps = new ArrayList<>();
        String pkg = packageName != null ? packageName : "";

        // extends
        Matcher extendsM = JAVA_EXTENDS.matcher(content);
        while (extendsM.find()) {
            String parentClass = getSimpleClassName(extendsM.group(1).trim());
            if (!parentClass.equals(className)) {
                String targetFile = classToFile.get(parentClass);
                deps.add(new CodeDependency(wsPath, relPath, className, pkg,
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
                    String targetFile = classToFile.get(ifaceName);
                    deps.add(new CodeDependency(wsPath, relPath, className, pkg,
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
        int count = 0;

        // Find SQL files
        try (Stream<Path> walk = Files.walk(workspaceRoot)) {
            List<Path> sqlFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".sql"))
                    .filter(p -> !p.toString().contains("/."))
                    .toList();

            for (Path sqlFile : sqlFiles) {
                String relPath = workspaceRoot.relativize(sqlFile).toString();
                SearchResult sqlResult = new SearchResult(
                        sqlFile, relPath, 1.0f, sqlFile.getFileName().toString(),
                        "SQL", null, "", "", "", Files.size(sqlFile));

                // Create SearchResult list for Java files
                List<SearchResult> javaResults = new ArrayList<>();
                for (Path jf : javaFiles) {
                    String jRelPath = workspaceRoot.relativize(jf).toString();
                    javaResults.add(new SearchResult(
                            jf, jRelPath, 1.0f, jf.getFileName().toString(),
                            "CODE", "Java", "", "", "", Files.size(jf)));
                }

                try {
                    List<CrossFormatLinker.CrossFormatLink> links =
                            crossFormatLinker.findSqlToJavaLinks(sqlResult, javaResults, workspaceRoot);
                    for (CrossFormatLinker.CrossFormatLink link : links) {
                        CrossFormatLinkRecord record = new CrossFormatLinkRecord(
                                wsPath, relPath, link.targetPath(),
                                "table-reference", link.entityName(), now);
                        repository.upsertCrossFormatLink(conn, record);
                        count++;
                    }
                } catch (IOException e) {
                    LOG.fine("Cross-format link extraction failed for " + sqlFile + ": " + e.getMessage());
                }
            }
        }

        return count;
    }
}
