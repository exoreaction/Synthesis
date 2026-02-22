package io.exoreaction.synthesis.org;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Classifies directories as {@link DirectoryClassification} to gate semantic
 * processing (centroid/wants/health) during sync.
 *
 * <p>Uses a four-tier heuristic evaluated in order (first match wins):
 * <ol>
 *   <li><b>Tier 1 -- Ancestor build file:</b> Walk up from the directory toward
 *       the workspace root looking for build files (pom.xml, build.gradle, etc.).
 *       If found, classify as {@code CODE}.</li>
 *   <li><b>Tier 2 -- Path pattern:</b> Well-known path segments
 *       (src/main/java, node_modules, target, etc.).</li>
 *   <li><b>Tier 3 -- Content signals:</b> Count file extensions in the directory
 *       (non-recursive). If &gt;80% are of one category, classify accordingly.</li>
 *   <li><b>Tier 4 -- Carve-outs:</b> Overrides a {@code CODE} classification to
 *       {@code DOCUMENT} for documentation directories inside code repos (docs/,
 *       examples/, directories containing only .md files).</li>
 * </ol>
 *
 * <p>Tier 4 is applied as a post-pass on Tier 1 and Tier 2 results, so a {@code docs/}
 * directory inside a Maven project is correctly classified as {@code DOCUMENT}.
 *
 * @since v1.16.0
 */
public class DirectoryClassifier {

    /** Build file names that indicate a code project root. */
    static final Set<String> BUILD_FILES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts",
            "Cargo.toml", "go.mod", "package.json"
    );

    /** Build file extensions that indicate a code project root (.csproj, .sln). */
    static final Set<String> BUILD_FILE_EXTENSIONS = Set.of(
            ".csproj", ".sln"
    );

    /** Path segments that indicate source code trees (Tier 2). */
    private static final Set<String> CODE_PATH_SEGMENTS = Set.of(
            "src/main/java", "src/test/java",
            "src/main/kotlin", "src/test/kotlin",
            "src/main/resources", "src/test/resources"
    );

    /** Directory names that indicate generated/build artifacts (Tier 2). */
    static final Set<String> GENERATED_DIR_NAMES = Set.of(
            "node_modules", "vendor", "target", ".gradle",
            "build", "dist", "__pycache__"
    );

    /** Source code file extensions (Tier 3). */
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".java", ".py", ".ts", ".js", ".go", ".rs",
            ".kt", ".scala", ".c", ".cpp", ".h", ".cs"
    );

    /** Media file extensions (Tier 3). */
    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".svg",
            ".mp4", ".avi", ".mov", ".mkv", ".webm",
            ".mp3", ".wav", ".flac", ".ogg", ".aac"
    );

    /** Document file extensions (Tier 3). */
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            ".md", ".pdf", ".docx", ".txt", ".pptx",
            ".doc", ".rtf", ".odt", ".xlsx", ".csv"
    );

    /** Directory names that are documentation carve-outs inside code repos (Tier 4). */
    static final Set<String> DOC_CARVEOUT_NAMES = Set.of(
            "docs", "documentation", "doc", "examples",
            "sample", "samples", "wiki"
    );

    private DirectoryClassifier() {
        // Static utility class
    }

    /**
     * Classifies a directory using the four-tier heuristic.
     *
     * @param dir                   the directory to classify
     * @param workspaceRoot         the workspace root (ancestor search stops here)
     * @param ancestorBuildFileCache cache for ancestor build file lookups; may be
     *                               shared across a full workspace walk for performance
     * @return the classification for the directory
     */
    public static DirectoryClassification classify(Path dir, Path workspaceRoot,
                                                    Map<Path, Optional<Path>> ancestorBuildFileCache) {
        if (dir == null || workspaceRoot == null) {
            return DirectoryClassification.UNKNOWN;
        }

        // Normalize paths for consistent comparison
        Path normalDir = dir.toAbsolutePath().normalize();
        Path normalRoot = workspaceRoot.toAbsolutePath().normalize();

        // Tier 2 (checked before Tier 1 for generated dirs -- fast path)
        String dirName = normalDir.getFileName() != null
                ? normalDir.getFileName().toString() : "";
        if (GENERATED_DIR_NAMES.contains(dirName)) {
            return DirectoryClassification.GENERATED;
        }

        // Tier 2: Path pattern check for known source tree segments
        String relativePath = normalRoot.relativize(normalDir).toString().replace('\\', '/');
        for (String segment : CODE_PATH_SEGMENTS) {
            if (relativePath.contains(segment + "/") || relativePath.endsWith(segment)) {
                // Check Tier 4 carve-outs before returning CODE
                return applyCarveOuts(normalDir, DirectoryClassification.CODE);
            }
        }

        // Tier 2: "src" or "lib" at depth <= 3 from a build file ancestor
        if (("src".equals(dirName) || "lib".equals(dirName))) {
            Optional<Path> buildFile = findAncestorBuildFile(normalDir, normalRoot, ancestorBuildFileCache);
            if (buildFile.isPresent()) {
                int depthFromBuildFile = normalDir.getNameCount()
                        - buildFile.get().getParent().getNameCount();
                if (depthFromBuildFile <= 3) {
                    return applyCarveOuts(normalDir, DirectoryClassification.CODE);
                }
            }
        }

        // Tier 1: Ancestor build file
        Optional<Path> ancestorBuildFile = findAncestorBuildFile(normalDir, normalRoot, ancestorBuildFileCache);
        if (ancestorBuildFile.isPresent()) {
            // Found a build file ancestor -- this is a code tree
            // But apply Tier 4 carve-outs first
            return applyCarveOuts(normalDir, DirectoryClassification.CODE);
        }

        // Tier 3: Content signals (only if Tier 1+2 inconclusive)
        DirectoryClassification contentClassification = classifyByContent(normalDir);
        if (contentClassification != DirectoryClassification.UNKNOWN) {
            return contentClassification;
        }

        return DirectoryClassification.UNKNOWN;
    }

    /**
     * Convenience overload without cache (creates a temporary one).
     * Suitable for one-off classifications; for workspace walks, pass a shared cache.
     */
    public static DirectoryClassification classify(Path dir, Path workspaceRoot) {
        return classify(dir, workspaceRoot, new java.util.HashMap<>());
    }

    /**
     * Applies Tier 4 carve-outs: overrides CODE to DOCUMENT for documentation
     * directories inside code repos.
     */
    static DirectoryClassification applyCarveOuts(Path dir, DirectoryClassification base) {
        if (base != DirectoryClassification.CODE) {
            return base;
        }

        String dirName = dir.getFileName() != null
                ? dir.getFileName().toString().toLowerCase() : "";

        // Carve-out: well-known documentation directory names
        if (DOC_CARVEOUT_NAMES.contains(dirName)) {
            return DirectoryClassification.DOCUMENT;
        }

        // Carve-out: directory contains ONLY .md files
        if (containsOnlyMarkdown(dir)) {
            return DirectoryClassification.DOCUMENT;
        }

        return base;
    }

    /**
     * Returns true if the directory contains at least one file and ALL files
     * are .md files.
     */
    static boolean containsOnlyMarkdown(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(dir)) {
            long[] counts = {0, 0}; // [total, md]
            files.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().startsWith("."))
                    .forEach(f -> {
                        counts[0]++;
                        String name = f.getFileName().toString().toLowerCase();
                        if (name.endsWith(".md")) {
                            counts[1]++;
                        }
                    });
            return counts[0] > 0 && counts[0] == counts[1];
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Tier 3: classifies by counting file extensions in the directory (non-recursive).
     * Returns UNKNOWN if no dominant category (>80%) is found.
     */
    static DirectoryClassification classifyByContent(Path dir) {
        if (!Files.isDirectory(dir)) {
            return DirectoryClassification.UNKNOWN;
        }

        int sourceCount = 0;
        int mediaCount = 0;
        int docCount = 0;
        int totalCount = 0;

        try (Stream<Path> files = Files.list(dir)) {
            var fileList = files
                    .filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().startsWith("."))
                    .toList();

            for (Path file : fileList) {
                totalCount++;
                String name = file.getFileName().toString().toLowerCase();
                String ext = getExtension(name);
                if (ext != null) {
                    if (SOURCE_EXTENSIONS.contains(ext)) sourceCount++;
                    else if (MEDIA_EXTENSIONS.contains(ext)) mediaCount++;
                    else if (DOCUMENT_EXTENSIONS.contains(ext)) docCount++;
                }
            }
        } catch (IOException e) {
            return DirectoryClassification.UNKNOWN;
        }

        if (totalCount == 0) {
            return DirectoryClassification.UNKNOWN;
        }

        double sourceRatio = (double) sourceCount / totalCount;
        double mediaRatio = (double) mediaCount / totalCount;
        double docRatio = (double) docCount / totalCount;

        if (sourceRatio > 0.8) return DirectoryClassification.CODE;
        if (mediaRatio > 0.8) return DirectoryClassification.MEDIA;
        if (docRatio > 0.8) return DirectoryClassification.DOCUMENT;

        return DirectoryClassification.UNKNOWN;
    }

    /**
     * Walks up from {@code dir} toward {@code root} looking for build files.
     * Returns the path of the first build file found, or empty.
     *
     * <p>Results are cached in {@code cache} for performance across workspace walks.
     */
    static Optional<Path> findAncestorBuildFile(Path dir, Path root,
                                                 Map<Path, Optional<Path>> cache) {
        Path current = dir;
        while (current != null && current.startsWith(root)) {
            // Check cache
            Optional<Path> cached = cache.get(current);
            if (cached != null) {
                return cached;
            }

            // Check for build files in current directory
            Optional<Path> found = checkForBuildFile(current);
            if (found.isPresent()) {
                // Cache this result and all directories we've walked through
                cacheBuildFileResult(dir, current, found, cache);
                return found;
            }

            // Don't go above root
            if (current.equals(root)) {
                break;
            }
            current = current.getParent();
        }

        // No build file found -- cache negative result for the starting dir
        cache.put(dir, Optional.empty());
        return Optional.empty();
    }

    /**
     * Checks if a directory contains any build files.
     */
    private static Optional<Path> checkForBuildFile(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }

        // Check exact names
        for (String buildFile : BUILD_FILES) {
            Path candidate = dir.resolve(buildFile);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }

        // Check extensions (.csproj, .sln)
        try (Stream<Path> files = Files.list(dir)) {
            Optional<Path> match = files
                    .filter(Files::isRegularFile)
                    .filter(f -> {
                        String name = f.getFileName().toString().toLowerCase();
                        return BUILD_FILE_EXTENSIONS.stream().anyMatch(name::endsWith);
                    })
                    .findFirst();
            if (match.isPresent()) {
                return match;
            }
        } catch (IOException e) {
            // Fall through
        }

        return Optional.empty();
    }

    /**
     * Caches the build file result for all directories from {@code start} up to
     * and including {@code buildFileDir}.
     */
    private static void cacheBuildFileResult(Path start, Path buildFileDir,
                                              Optional<Path> result,
                                              Map<Path, Optional<Path>> cache) {
        Path current = start;
        while (current != null && !current.equals(buildFileDir.getParent())) {
            cache.put(current, result);
            if (current.equals(buildFileDir)) {
                break;
            }
            current = current.getParent();
        }
    }

    /**
     * Extracts the file extension including the dot, e.g. ".java".
     * Returns null if no extension found.
     */
    private static String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot);
        }
        return null;
    }
}
