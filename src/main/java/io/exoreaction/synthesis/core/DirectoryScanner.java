package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.util.FileUtils;
import io.exoreaction.synthesis.util.ProgressReporter;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Recursively scans a workspace directory to discover and catalog files.
 *
 * <p>Walks the file tree, applies include/exclude glob patterns,
 * extracts metadata for each matching file, and produces a {@link ScanResult}.
 *
 * <p>Design decisions:
 * <ul>
 *   <li>Uses {@link Files#walkFileTree} for explicit control over traversal</li>
 *   <li>Skips excluded directories early (in preVisitDirectory) for efficiency</li>
 *   <li>Reports progress via {@link ProgressReporter} for large workspaces</li>
 *   <li>Hashing is optional (controlled by config) for faster scanning</li>
 * </ul>
 */
public class DirectoryScanner {

    private final Path workspaceRoot;
    private final SynthesisConfig.ScanConfig scanConfig;
    private final boolean verbose;

    // Pre-compiled glob matchers for performance
    private final List<PathMatcher> includeMatchers;
    private final List<PathMatcher> excludeMatchers;

    // Compiled .synthesisignore directory predicates (gitignore-style).
    private final List<java.util.function.Predicate<Path>> synthesisIgnoreMatchers;

    public DirectoryScanner(Path workspaceRoot, SynthesisConfig.ScanConfig scanConfig, boolean verbose) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.scanConfig = scanConfig;
        this.verbose = verbose;

        FileSystem fs = FileSystems.getDefault();

        // Build include matchers. For patterns like "**/*.md", also add "*.md"
        // because Java's glob "**" does not match zero path components --
        // files at the workspace root would otherwise be missed.
        var includeBuilders = new java.util.ArrayList<PathMatcher>();
        for (String pattern : scanConfig.getIncludePatterns()) {
            includeBuilders.add(fs.getPathMatcher("glob:" + pattern));
            if (pattern.startsWith("**/")) {
                includeBuilders.add(fs.getPathMatcher("glob:" + pattern.substring(3)));
            }
        }
        this.includeMatchers = List.copyOf(includeBuilders);

        // Use effective exclude patterns (includes smart defaults if enabled)
        List<String> effectiveExcludes = scanConfig.getEffectiveExcludePatterns(workspaceRoot);
        this.excludeMatchers = effectiveExcludes.stream()
                .map(pattern -> fs.getPathMatcher("glob:" + pattern))
                .toList();

        // Compile .synthesisignore into directory predicates (gitignore-style).
        this.synthesisIgnoreMatchers = loadSynthesisIgnoreMatchers(this.workspaceRoot);

        // Verbose output for smart exclusions
        if (verbose && scanConfig.isUseSmartDefaults()) {
            java.util.Set<Ecosystem> detected = EcosystemDetector.detect(workspaceRoot);
            if (!detected.isEmpty()) {
                System.out.println("\n📦 Detected ecosystems: " +
                    String.join(", ", detected.stream()
                        .map(e -> e.name().toLowerCase().replace('_', '-'))
                        .sorted()
                        .toList()));
                System.out.println("   Applying smart exclusions...\n");
            }
        }
    }

    /**
     * Scans the workspace and returns metadata for all matching files.
     */
    public ScanResult scan() throws IOException {
        Instant start = Instant.now();

        // Phase 1: Discover all files (fast walk to count for progress bar)
        List<Path> discoveredFiles = discoverFiles();

        // Phase 2: Extract metadata with progress reporting
        ProgressReporter progress = new ProgressReporter("Scanning", discoveredFiles.size());
        List<FileMetadata> metadata = new ArrayList<>(discoveredFiles.size());

        for (Path file : discoveredFiles) {
            try {
                FileMetadata fm = extractMetadata(file);
                if (fm != null) {
                    metadata.add(fm);
                }
            } catch (IOException e) {
                if (verbose) {
                    System.err.println("  Warning: Could not read " + workspaceRoot.relativize(file) + ": " + e.getMessage());
                }
            }
            progress.tick();
        }
        progress.complete();

        Duration duration = Duration.between(start, Instant.now());
        return new ScanResult(metadata, start, duration, workspaceRoot.toString());
    }

    /**
     * Discovers all files matching include/exclude patterns.
     * This is a fast first pass -- no metadata extraction, just path collection.
     */
    private List<Path> discoverFiles() throws IOException {
        List<Path> files = new ArrayList<>();

        Files.walkFileTree(workspaceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Skip excluded directories early for efficiency
                if (isExcludedDirectory(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (shouldIncludeFile(file, attrs)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // Skip unreadable files silently
                return FileVisitResult.CONTINUE;
            }
        });

        return files;
    }

    /**
     * Extracts metadata for a single file.
     */
    private FileMetadata extractMetadata(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);

        // Skip files exceeding size limit
        if (attrs.size() > scanConfig.getMaxFileSizeBytes()) {
            return null;
        }

        String contentHash = null;
        if (scanConfig.isComputeHashes() && !FileUtils.isBinaryFile(file)) {
            try {
                contentHash = FileUtils.md5Hash(file);
            } catch (IOException e) {
                // Hash computation failed -- continue without hash
            }
        }

        return FileMetadata.of(
                file,
                workspaceRoot,
                attrs.size(),
                attrs.lastModifiedTime().toInstant(),
                contentHash
        );
    }

    /**
     * Checks whether a directory should be excluded from traversal.
     */
    private boolean isExcludedDirectory(Path dir) {
        if (dir.equals(workspaceRoot)) return false;

        Path relative = workspaceRoot.relativize(dir);

        // .synthesisignore — compiled predicates (bare-name or glob, see compileIgnorePattern).
        if (synthesisIgnoreMatchers.stream().anyMatch(p -> p.test(relative))) return true;

        // Check against configured exclude patterns (includes smart defaults if enabled)
        return matchesExcludeGlob(relative, excludeMatchers);
    }

    /**
     * Returns {@code true} if a workspace-relative directory path matches any of the given
     * exclude glob matchers, using the same three-way test the scanner applies during traversal:
     * the path itself, the path with a trailing separator (for directory patterns like {@code logs/}),
     * and a dummy child (for prefix patterns like {@code node_modules/**}).
     *
     * <p>Shared so that other commands operating on the same workspace (e.g. {@code prune}) apply
     * identical exclusion semantics and cannot drift from indexing behaviour.
     */
    public static boolean matchesExcludeGlob(Path relative, List<PathMatcher> excludeMatchers) {
        for (PathMatcher matcher : excludeMatchers) {
            // Test the directory path itself
            if (matcher.matches(relative)) {
                return true;
            }

            // Test with trailing separator to match directory patterns (e.g., "logs/")
            if (matcher.matches(Path.of(relative + "/"))) {
                return true;
            }

            // Test if any file inside this directory would match
            // For patterns like "node_modules/**", check if "node_modules/dummy" matches
            Path testPath = Path.of(relative.toString(), "dummy");
            if (matcher.matches(testPath)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Parses a {@code .synthesisignore} file, stripping blank lines and {@code #} comments.
     *
     * @param ignoreFile path to the {@code .synthesisignore} file
     * @return list of non-blank, non-comment patterns
     */
    public static List<String> parseSynthesisIgnore(Path ignoreFile) {
        try {
            return Files.readAllLines(ignoreFile).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Loads and compiles the workspace's {@code .synthesisignore} into directory predicates,
     * exactly as the scanner applies them during traversal. Returns an empty list when the
     * file does not exist.
     *
     * <p>Shared so that other commands operating on the same workspace (e.g. {@code prune})
     * apply identical {@code .synthesisignore} semantics and cannot drift from indexing
     * behaviour — the same reasoning as {@link #matchesExcludeGlob}.
     */
    public static List<java.util.function.Predicate<Path>> loadSynthesisIgnoreMatchers(Path workspaceRoot) {
        Path ignoreFile = workspaceRoot.resolve(".synthesisignore");
        return Files.isRegularFile(ignoreFile)
                ? parseSynthesisIgnore(ignoreFile).stream()
                        .map(DirectoryScanner::compileIgnorePattern)
                        .filter(java.util.Objects::nonNull)
                        .toList()
                : List.of();
    }

    /**
     * Compiles a single {@code .synthesisignore} line into a directory-path predicate.
     *
     * <p>Bare names (no slash, no glob chars) match any path component at any depth.
     * Everything else compiles as a root-anchored Java glob with a dummy-suffix probe
     * so descendant-targeting patterns ({@code **&#47;.archive/**}) prune the parent.
     *
     * @return predicate, or {@code null} if the pattern is empty after normalization
     */
    static java.util.function.Predicate<Path> compileIgnorePattern(String raw) {
        String p = raw;
        if (p.endsWith("/"))   p = p.substring(0, p.length() - 1);
        if (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return null;

        boolean isBareName = p.indexOf('/') < 0
                && p.indexOf('*') < 0
                && p.indexOf('?') < 0
                && p.indexOf('[') < 0;

        if (isBareName) {
            String name = p;
            return rel -> {
                for (int i = 0; i < rel.getNameCount(); i++)
                    if (rel.getName(i).toString().equals(name)) return true;
                return false;
            };
        }
        PathMatcher glob = FileSystems.getDefault().getPathMatcher("glob:" + p);
        return rel -> glob.matches(rel) || glob.matches(Path.of(rel.toString(), "dummy"));
    }

    /**
     * Checks whether a file should be included in the scan results.
     */
    private boolean shouldIncludeFile(Path file, BasicFileAttributes attrs) {
        // Skip symlinks
        if (attrs.isSymbolicLink()) return false;

        // Skip hidden files
        String name = file.getFileName().toString();
        if (name.startsWith(".")) return false;

        // Check against include patterns
        Path relative = workspaceRoot.relativize(file);
        for (PathMatcher matcher : includeMatchers) {
            if (matcher.matches(relative)) {
                return true;
            }
        }

        // If no include patterns matched, exclude the file
        return false;
    }
}
