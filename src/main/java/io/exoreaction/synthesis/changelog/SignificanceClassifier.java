package io.exoreaction.synthesis.changelog;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Set;

/**
 * Classifies the significance of a file change based on heuristics.
 *
 * <p>Rules:
 * <ul>
 *   <li><b>NOISE:</b> .synthesis/, temp files, lock files, build artifacts</li>
 *   <li><b>NORMAL:</b> Most document/code changes</li>
 *   <li><b>NOTABLE:</b> README changes, config files, large files, new directories</li>
 *   <li><b>CRITICAL:</b> .env files, credentials, mass deletions</li>
 * </ul>
 */
public class SignificanceClassifier {

    private static final Set<String> NOISE_FILENAMES = Set.of(
            ".DS_Store", "Thumbs.db", "desktop.ini", ".directory",
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml"
    );

    private static final List<String> NOISE_PATH_PATTERNS = List.of(
            "**/.synthesis/**", "**/.git/**", "**/node_modules/**",
            "**/target/**", "**/build/**", "**/.gradle/**",
            "**/__pycache__/**", "**/.cache/**", "**/*.tmp",
            "**/*.swp", "**/*.bak", "**/*~"
    );

    private static final Set<String> NOTABLE_FILENAMES = Set.of(
            "README.md", "CHANGELOG.md", "CLAUDE.md", "MEMORY.md",
            "pom.xml", "build.gradle", "package.json", "Dockerfile",
            "docker-compose.yml", "Makefile", ".gitignore"
    );

    private static final List<String> CRITICAL_PATH_PATTERNS = List.of(
            "**/.env", "**/.env.*", "**/credentials*", "**/secrets*",
            "**/*.key", "**/*.pem", "**/*.cert", "**/id_rsa*"
    );

    private static final long LARGE_FILE_THRESHOLD = 1_048_576; // 1 MB

    private final List<PathMatcher> noiseMatchers;
    private final List<PathMatcher> criticalMatchers;
    private final List<String> customNoisePaths;
    private final List<String> customCriticalPaths;
    private final int massDeleteThreshold;

    /**
     * Creates a classifier with default rules.
     */
    public SignificanceClassifier() {
        this(List.of(), List.of(), 10);
    }

    /**
     * Creates a classifier with custom rules merged with defaults.
     */
    public SignificanceClassifier(List<String> customNoisePaths,
                                   List<String> customCriticalPaths,
                                   int massDeleteThreshold) {
        this.customNoisePaths = customNoisePaths;
        this.customCriticalPaths = customCriticalPaths;
        this.massDeleteThreshold = massDeleteThreshold;

        var fs = FileSystems.getDefault();

        var allNoisePatterns = new java.util.ArrayList<>(NOISE_PATH_PATTERNS);
        allNoisePatterns.addAll(customNoisePaths);
        this.noiseMatchers = allNoisePatterns.stream()
                .map(p -> fs.getPathMatcher("glob:" + p))
                .toList();

        var allCriticalPatterns = new java.util.ArrayList<>(CRITICAL_PATH_PATTERNS);
        allCriticalPatterns.addAll(customCriticalPaths);
        this.criticalMatchers = allCriticalPatterns.stream()
                .map(p -> fs.getPathMatcher("glob:" + p))
                .toList();
    }

    /**
     * Classifies the significance of a single file change.
     */
    public ChangeSignificance classify(String relativePath, String fileType,
                                        long fileSize, ChangeEvent.ChangeType changeType) {
        // Normalize path for consistent matching (forward slashes)
        String normalizedPath = relativePath.replace('\\', '/');
        var path = java.nio.file.Path.of(normalizedPath);
        String fileName = path.getFileName() != null ? path.getFileName().toString() : "";

        // Check critical first (highest priority)
        for (PathMatcher m : criticalMatchers) {
            if (m.matches(path)) return ChangeSignificance.CRITICAL;
        }
        // Also check by simple prefix/contains for patterns like **.env
        if (normalizedPath.contains(".env") || normalizedPath.contains("credentials")
            || normalizedPath.contains("secrets") || normalizedPath.endsWith(".key")
            || normalizedPath.endsWith(".pem") || normalizedPath.contains("id_rsa")) {
            return ChangeSignificance.CRITICAL;
        }
        // Check custom critical patterns (convert glob to simple match)
        for (String pattern : customCriticalPaths) {
            if (matchesGlobPattern(normalizedPath, pattern)) {
                return ChangeSignificance.CRITICAL;
            }
        }

        // Check noise by filename
        if (NOISE_FILENAMES.contains(fileName)) return ChangeSignificance.NOISE;

        // Check noise by path patterns
        for (PathMatcher m : noiseMatchers) {
            if (m.matches(path)) return ChangeSignificance.NOISE;
        }
        // Also check by simple contains for common patterns (with or without leading slash)
        if (normalizedPath.contains("/.synthesis/") || normalizedPath.startsWith(".synthesis/")
            || normalizedPath.contains("/.git/") || normalizedPath.startsWith(".git/")
            || normalizedPath.contains("/node_modules/") || normalizedPath.startsWith("node_modules/")
            || normalizedPath.contains("/target/") || normalizedPath.startsWith("target/")
            || normalizedPath.contains("/build/") || normalizedPath.startsWith("build/")
            || normalizedPath.contains("/.gradle/") || normalizedPath.startsWith(".gradle/")
            || normalizedPath.contains("/__pycache__/") || normalizedPath.startsWith("__pycache__/")
            || normalizedPath.contains("/.cache/") || normalizedPath.startsWith(".cache/")
            || normalizedPath.endsWith(".tmp") || normalizedPath.endsWith(".swp")
            || normalizedPath.endsWith(".bak") || normalizedPath.endsWith("~")) {
            return ChangeSignificance.NOISE;
        }
        // Check custom noise patterns (convert glob to simple match)
        for (String pattern : customNoisePaths) {
            if (matchesGlobPattern(normalizedPath, pattern)) {
                return ChangeSignificance.NOISE;
            }
        }

        // Check notable
        if (NOTABLE_FILENAMES.contains(fileName)) return ChangeSignificance.NOTABLE;
        if (fileSize > LARGE_FILE_THRESHOLD && changeType == ChangeEvent.ChangeType.ADDED) {
            return ChangeSignificance.NOTABLE;
        }

        return ChangeSignificance.NORMAL;
    }

    /**
     * Simple glob pattern matcher for common patterns.
     * Converts glob patterns like "** /logs/**" to simple string checks.
     */
    private boolean matchesGlobPattern(String path, String pattern) {
        // Remove leading/trailing **/ wildcards
        String stripped = pattern.replace("**/", "").replace("/**", "");

        // Handle wildcard at end (e.g., "credentials*")
        if (stripped.endsWith("*")) {
            String prefix = stripped.substring(0, stripped.length() - 1);
            return path.contains(prefix) || path.contains("/" + prefix) || path.startsWith(prefix);
        }

        // Handle exact match or contains
        return path.contains(stripped) || path.contains("/" + stripped) || path.startsWith(stripped);
    }

    /**
     * Checks if a batch of deletions in one directory path constitutes a mass deletion.
     */
    public boolean isMassDeletion(int deletionCount) {
        return deletionCount >= massDeleteThreshold;
    }

    public int getMassDeleteThreshold() {
        return massDeleteThreshold;
    }
}
