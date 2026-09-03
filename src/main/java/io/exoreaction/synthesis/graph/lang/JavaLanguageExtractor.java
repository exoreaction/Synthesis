package io.exoreaction.synthesis.graph.lang;

import io.exoreaction.synthesis.graph.CodeGraphExtractor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Java extraction behind the {@link LanguageExtractor} seam (ADR-0001). All logic
 * is lifted verbatim from {@code CodeGraphExtractor}'s former inline Java path —
 * pattern-based import/package/type parsing and the multi-repo file-discovery
 * filters. Shared concerns (resolution, persistence, repo-name detection,
 * cross-format, the build-artifact/archive predicates) remain on the orchestrator.
 */
public class JavaLanguageExtractor implements LanguageExtractor {

    private static final Logger LOG = Logger.getLogger(JavaLanguageExtractor.class.getName());

    private static final Pattern JAVA_IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern JAVA_PACKAGE = Pattern.compile(
            "^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern JAVA_EXTENDS = Pattern.compile(
            "\\bextends\\s+([A-Z][\\w.]*)", Pattern.MULTILINE);
    private static final Pattern JAVA_IMPLEMENTS = Pattern.compile(
            "\\bimplements\\s+([A-Z][\\w.,\\s]+)", Pattern.MULTILINE);

    @Override
    public String languageId() {
        return "java";
    }

    @Override
    public Set<Ext> extensions() {
        return Set.of(new Ext(".java"));
    }

    @Override
    public Set<EdgeKind> supportedEdgeKinds() {
        return Set.of(EdgeKind.IMPORT, EdgeKind.SUPERTYPE);
    }

    @Override
    public List<Path> findFiles(Path root, ExclusionRules excl) {
        // Identify non-Java repos to skip in multi-repo workspaces
        Set<String> skippedRepos;
        try {
            skippedRepos = identifyNonJavaRepos(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            Stream<Path> filtered = walk
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("/."))  // skip hidden dirs
                .filter(p -> !CodeGraphExtractor.isBuildArtifact(root, p))
                .filter(p -> !isInSkippedRepo(root, p, skippedRepos));

            // Exclude archive/vendor/node_modules unless explicitly included (#279)
            if (!excl.includeArchives()) {
                filtered = filtered.filter(p -> !CodeGraphExtractor.isArchiveDirectory(root, p));
            }

            filtered.forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (!skippedRepos.isEmpty()) {
            LOG.info("Skipped " + skippedRepos.size() + " non-Java repos: "
                    + String.join(", ", skippedRepos));
        }

        return files;
    }

    @Override
    public List<Declaration> declarations(Path file, String content) {
        String className = extractClassName(file);
        String pkg = extractPackage(content);
        String fqn = pkg != null ? pkg + "." + className : className;
        return List.of(new Declaration(new ResolutionKey.FqnKey(fqn), file));
    }

    @Override
    public List<RawEdge> edges(Path file, String content, List<Declaration> decls) {
        String className = extractClassName(file);
        String packageName = extractPackage(content);
        String pkg = packageName != null ? packageName : "";

        List<RawEdge> edges = new ArrayList<>();

        // imports
        for (String imp : extractImports(content)) {
            String targetClass = Resolver.getSimpleClassName(imp);
            String targetPackage = Resolver.getPackageFromImport(imp);
            edges.add(new RawEdge(className, pkg,
                    new ResolutionRef.FqnRef(imp, false),
                    targetClass, targetPackage, EdgeKind.IMPORT, "import"));
        }

        // extends
        Matcher extendsM = JAVA_EXTENDS.matcher(content);
        while (extendsM.find()) {
            String parentClass = Resolver.getSimpleClassName(extendsM.group(1).trim());
            if (!parentClass.equals(className)) {
                edges.add(new RawEdge(className, pkg,
                        new ResolutionRef.SimpleNameRef(parentClass, pkg),
                        parentClass, "", EdgeKind.SUPERTYPE, "extends"));
            }
        }

        // implements
        Matcher implM = JAVA_IMPLEMENTS.matcher(content);
        while (implM.find()) {
            String interfaces = implM.group(1).trim();
            for (String iface : interfaces.split(",")) {
                String ifaceName = Resolver.getSimpleClassName(iface.trim());
                if (!ifaceName.isBlank() && !ifaceName.equals(className)) {
                    edges.add(new RawEdge(className, pkg,
                            new ResolutionRef.SimpleNameRef(ifaceName, pkg),
                            ifaceName, "", EdgeKind.SUPERTYPE, "implements"));
                }
            }
        }

        return edges;
    }

    // -----------------------------------------------------------------------
    // Java parsing + multi-repo discovery (verbatim from CodeGraphExtractor)
    // -----------------------------------------------------------------------

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

    /**
     * Identifies top-level subdirectories that are not Java projects.
     * A directory is considered non-Java if it has no Java build file
     * (pom.xml, build.gradle, build.gradle.kts) AND contains zero .java files.
     */
    Set<String> identifyNonJavaRepos(Path root) throws IOException {
        Set<String> nonJavaRepos = new HashSet<>();

        try (Stream<Path> topLevel = Files.list(root)) {
            List<Path> subdirs = topLevel.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .toList();

            for (Path subdir : subdirs) {
                boolean hasBuildFile = Files.exists(subdir.resolve("pom.xml"))
                        || Files.exists(subdir.resolve("build.gradle"))
                        || Files.exists(subdir.resolve("build.gradle.kts"));

                if (!hasBuildFile) {
                    boolean hasJavaFiles;
                    try (Stream<Path> walk = Files.walk(subdir)) {
                        hasJavaFiles = walk.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(".java"))
                                .filter(p -> !p.toString().contains("/."))
                                .filter(p -> !CodeGraphExtractor.isBuildArtifact(root, p))
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

    /** Checks if a file is inside one of the skipped repo directories. */
    private boolean isInSkippedRepo(Path root, Path file, Set<String> skippedRepos) {
        if (skippedRepos.isEmpty()) return false;
        Path rel = root.relativize(file);
        if (rel.getNameCount() > 0) {
            return skippedRepos.contains(rel.getName(0).toString());
        }
        return false;
    }
}
