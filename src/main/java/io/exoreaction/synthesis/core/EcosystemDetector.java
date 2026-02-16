package io.exoreaction.synthesis.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Detects software development ecosystems based on marker files in a workspace.
 * Performs shallow scans to identify project types and their build tools.
 */
public class EcosystemDetector {

    private EcosystemDetector() {
        // Utility class - prevent instantiation
    }

    /**
     * Detects all ecosystems present in the workspace root directory.
     * Searches for marker files that indicate specific project types.
     *
     * @param workspaceRoot the root directory to scan
     * @return set of detected ecosystems (empty if none found)
     */
    public static Set<Ecosystem> detect(Path workspaceRoot) {
        Set<Ecosystem> detected = new HashSet<>();

        if (!Files.isDirectory(workspaceRoot)) {
            return detected;
        }

        // Python detection
        if (hasAnyFile(workspaceRoot, "requirements.txt", "setup.py", "pyproject.toml", "Pipfile")) {
            detected.add(Ecosystem.PYTHON);
        }

        // JavaScript/Node.js detection
        if (hasFile(workspaceRoot, "package.json")) {
            detected.add(Ecosystem.JAVASCRIPT);
        }

        // Java Maven detection
        if (hasFile(workspaceRoot, "pom.xml")) {
            detected.add(Ecosystem.JAVA_MAVEN);
        }

        // Java Gradle detection
        if (hasAnyFile(workspaceRoot, "build.gradle", "build.gradle.kts", "settings.gradle")) {
            detected.add(Ecosystem.JAVA_GRADLE);
        }

        // Rust detection
        if (hasFile(workspaceRoot, "Cargo.toml")) {
            detected.add(Ecosystem.RUST);
        }

        // Go detection
        if (hasFile(workspaceRoot, "go.mod")) {
            detected.add(Ecosystem.GO);
        }

        // .NET detection (requires shallow search for project files)
        if (hasDotNetProjectFiles(workspaceRoot)) {
            detected.add(Ecosystem.DOTNET);
        }

        // Ruby detection
        if (hasFile(workspaceRoot, "Gemfile")) {
            detected.add(Ecosystem.RUBY);
        }

        // PHP detection
        if (hasFile(workspaceRoot, "composer.json")) {
            detected.add(Ecosystem.PHP);
        }

        return detected;
    }

    /**
     * Checks if a specific file exists in the workspace root.
     *
     * @param workspaceRoot the directory to check
     * @param filename the filename to look for
     * @return true if the file exists
     */
    private static boolean hasFile(Path workspaceRoot, String filename) {
        return Files.exists(workspaceRoot.resolve(filename));
    }

    /**
     * Checks if any of the specified files exist in the workspace root.
     *
     * @param workspaceRoot the directory to check
     * @param filenames the filenames to look for
     * @return true if any file exists
     */
    private static boolean hasAnyFile(Path workspaceRoot, String... filenames) {
        for (String filename : filenames) {
            if (hasFile(workspaceRoot, filename)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if .NET project files exist within 2 directory levels.
     * Searches for *.csproj, *.fsproj, *.vbproj, or *.sln files.
     *
     * @param workspaceRoot the directory to check
     * @return true if any .NET project files are found
     */
    private static boolean hasDotNetProjectFiles(Path workspaceRoot) {
        try (Stream<Path> paths = Files.walk(workspaceRoot, 2)) {
            return paths
                .filter(Files::isRegularFile)
                .anyMatch(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".csproj") ||
                           name.endsWith(".fsproj") ||
                           name.endsWith(".vbproj") ||
                           name.endsWith(".sln");
                });
        } catch (IOException e) {
            // If we can't scan, assume no .NET projects
            return false;
        }
    }
}
