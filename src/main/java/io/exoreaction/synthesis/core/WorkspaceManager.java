package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.util.AnsiOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Manages the lifecycle of a Synthesis workspace.
 *
 * <p>A workspace is a directory that has been initialized with Synthesis.
 * It contains a {@code .synthesis/} subdirectory with:
 * <ul>
 *   <li>{@code config.yaml} -- workspace configuration</li>
 *   <li>{@code index/} -- Lucene search index files</li>
 *   <li>{@code scan-state.json} -- last scan timestamp and checksums</li>
 *   <li>{@code reports/} -- maintenance reports</li>
 * </ul>
 */
public class WorkspaceManager {

    /** The hidden directory inside the workspace containing Synthesis data. */
    public static final String SYNTHESIS_DIR = ".synthesis";

    public static final String INDEX_DIR = "index";
    public static final String REPORTS_DIR = "reports";
    public static final String SCAN_STATE_FILE = "scan-state.json";

    private final Path workspaceRoot;

    public WorkspaceManager(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /**
     * Initializes a new Synthesis workspace.
     * Creates the .synthesis/ directory structure and default configuration.
     *
     * @param name workspace name (used in config)
     * @param type workspace type (e.g., "general", "plugin-ecosystem")
     * @return the loaded configuration
     * @throws IOException if directory creation fails
     */
    public SynthesisConfig init(String name, String type) throws IOException {
        return initWithMetadata(name, type, null, null, 0, null);
    }

    /**
     * Initializes a new Synthesis workspace with extended metadata.
     *
     * @param name            workspace name (used in config)
     * @param type            workspace type (e.g., "general")
     * @param category        workspace category: source-code, documents, or mixed
     * @param primaryLanguage primary programming language (may be null)
     * @param repoCount       number of repositories (0 if unknown)
     * @param company         owning company (may be null)
     * @return the loaded configuration
     * @throws IOException if directory creation fails
     */
    public SynthesisConfig initWithMetadata(String name, String type,
                                             String category, String primaryLanguage,
                                             int repoCount, String company) throws IOException {
        Path synthesisDir = workspaceRoot.resolve(SYNTHESIS_DIR);

        if (Files.exists(synthesisDir)) {
            AnsiOutput.printWarning("Workspace already initialized at " + workspaceRoot);
            return ConfigLoader.load(workspaceRoot);
        }

        // Create directory structure
        Files.createDirectories(synthesisDir.resolve(INDEX_DIR));
        Files.createDirectories(synthesisDir.resolve(REPORTS_DIR));

        // Generate config with metadata
        String workspaceName = name != null && !name.isBlank() ? name : workspaceRoot.getFileName().toString();
        String workspaceType = type != null && !type.isBlank() ? type : "general";

        String configContent = ConfigLoader.generateDefaultConfig(
                workspaceName, workspaceType, category, primaryLanguage, repoCount, company);
        Path configPath = synthesisDir.resolve("config.yaml");
        Files.writeString(configPath, configContent);

        AnsiOutput.printSuccess("Workspace initialized: " + workspaceRoot);
        AnsiOutput.printInfo("Config: " + configPath);
        AnsiOutput.printInfo("Index:  " + synthesisDir.resolve(INDEX_DIR));

        return ConfigLoader.load(workspaceRoot);
    }

    /**
     * Validates that the given path is an initialized Synthesis workspace.
     *
     * <p>When the path is not a workspace, walks up the directory tree to find
     * the nearest ancestor that is a workspace and includes it in the error message
     * as a suggestion (see <a href="https://github.com/exoreaction/Synthesis/issues/87">#87</a>).
     *
     * @return empty if valid, descriptive error message if invalid
     */
    public Optional<String> validate() {
        if (!Files.exists(workspaceRoot)) {
            return Optional.of("Workspace directory does not exist: " + workspaceRoot);
        }
        if (!Files.isDirectory(workspaceRoot)) {
            return Optional.of("Not a directory: " + workspaceRoot);
        }

        Path synthesisDir = workspaceRoot.resolve(SYNTHESIS_DIR);
        if (!Files.exists(synthesisDir)) {
            StringBuilder msg = new StringBuilder();
            msg.append("'").append(workspaceRoot).append("' is not a Synthesis workspace")
               .append(" (no ").append(SYNTHESIS_DIR).append("/ found).");

            // Look for ancestor workspaces (walk up)
            Path ancestor = findAncestorWorkspace(workspaceRoot);
            // Look for child workspaces (walk down 1-2 levels)
            List<Path> children = findChildWorkspaces(workspaceRoot, 2);

            if (ancestor != null || !children.isEmpty()) {
                msg.append("\n\nDid you mean one of these?");
                if (ancestor != null) {
                    msg.append("\n  ").append(ancestor).append("    (parent workspace)");
                }
                for (Path child : children) {
                    msg.append("\n  ").append(child).append("    (synthesis workspace)");
                }
            }

            msg.append("\n\nRun 'synthesis init' to initialise this directory,")
               .append(" or 'synthesis list' to see all known workspaces.");
            return Optional.of(msg.toString());
        }

        return Optional.empty();
    }

    /**
     * Walks up the directory tree from {@code start} to find the nearest ancestor
     * that contains a {@code .synthesis/} directory.
     *
     * @param start the directory to begin searching from (not checked itself)
     * @return the nearest ancestor workspace path, or {@code null} if none found
     */
    Path findAncestorWorkspace(Path start) {
        Path current = start.getParent();
        while (current != null) {
            if (Files.isDirectory(current.resolve(SYNTHESIS_DIR))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * Walks down the directory tree from {@code start} to find child directories
     * that contain a {@code .synthesis/} directory, up to the given depth.
     *
     * @param start    the directory to begin searching from (not checked itself)
     * @param maxDepth maximum depth to walk (e.g. 2 = children + grandchildren)
     * @return list of child workspace paths, sorted alphabetically
     */
    List<Path> findChildWorkspaces(Path start, int maxDepth) {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(start)) return found;

        try (Stream<Path> walk = Files.walk(start, maxDepth)) {
            walk.filter(Files::isDirectory)
                .filter(p -> !p.equals(start))
                .filter(p -> Files.isDirectory(p.resolve(SYNTHESIS_DIR)))
                .sorted()
                .forEach(found::add);
        } catch (IOException e) {
            // Silently ignore -- best-effort suggestion
        }
        return found;
    }

    /**
     * Resolves the path to the Lucene index directory.
     */
    public Path getIndexPath() {
        return workspaceRoot.resolve(SYNTHESIS_DIR).resolve(INDEX_DIR);
    }

    /**
     * Resolves the path to the reports directory.
     */
    public Path getReportsPath() {
        return workspaceRoot.resolve(SYNTHESIS_DIR).resolve(REPORTS_DIR);
    }

    /**
     * Returns the reports output directory, respecting the workspace config.
     *
     * <p>If {@code config.report.outputDir} is set, resolves it relative to the
     * workspace root (or uses it as-is if absolute). Otherwise falls back to the
     * default {@code .synthesis/reports/}.
     *
     * @param config the workspace configuration (may be null)
     * @return the resolved reports base path
     */
    public Path getReportsPath(SynthesisConfig config) {
        if (config != null && config.getReport() != null) {
            String outputDir = config.getReport().getOutputDir();
            if (outputDir != null && !outputDir.isBlank()) {
                Path custom = Path.of(outputDir);
                return custom.isAbsolute() ? custom : workspaceRoot.resolve(custom);
            }
        }
        return getReportsPath();
    }

    /**
     * Resolves the path to the scan state file.
     */
    public Path getScanStatePath() {
        return workspaceRoot.resolve(SYNTHESIS_DIR).resolve(SCAN_STATE_FILE);
    }

    /**
     * Returns the workspace root directory.
     */
    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }
}
