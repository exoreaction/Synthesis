package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.util.AnsiOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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
        Path synthesisDir = workspaceRoot.resolve(SYNTHESIS_DIR);

        if (Files.exists(synthesisDir)) {
            AnsiOutput.printWarning("Workspace already initialized at " + workspaceRoot);
            return ConfigLoader.load(workspaceRoot);
        }

        // Create directory structure
        Files.createDirectories(synthesisDir.resolve(INDEX_DIR));
        Files.createDirectories(synthesisDir.resolve(REPORTS_DIR));

        // Generate default config
        String workspaceName = name != null && !name.isBlank() ? name : workspaceRoot.getFileName().toString();
        String workspaceType = type != null && !type.isBlank() ? type : "general";

        String configContent = ConfigLoader.generateDefaultConfig(workspaceName, workspaceType);
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
     * @return empty if valid, error message if invalid
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
            return Optional.of("Not a Synthesis workspace (missing " + SYNTHESIS_DIR + "/). Run 'synthesis init' first.");
        }

        return Optional.empty();
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
