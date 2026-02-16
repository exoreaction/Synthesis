package io.exoreaction.synthesis.search;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Configuration for workspace discovery.
 *
 * <p>Loads search paths from ~/.synthesis/config/workspace-discovery.yaml
 * or falls back to sensible defaults if not found.
 */
public class WorkspaceDiscoveryConfig {

    private static final Logger LOG = Logger.getLogger(WorkspaceDiscoveryConfig.class.getName());

    private static final String CONFIG_FILE = "workspace-discovery.yaml";
    private final List<String> searchPaths;
    private final int maxDepth;

    /**
     * Creates a new configuration.
     */
    private WorkspaceDiscoveryConfig(List<String> searchPaths, int maxDepth) {
        this.searchPaths = searchPaths;
        this.maxDepth = maxDepth;
    }

    /**
     * Returns the search paths with variables expanded.
     */
    public List<Path> getSearchPaths() {
        List<Path> expanded = new ArrayList<>();
        String userHome = System.getProperty("user.home");

        for (String pathStr : searchPaths) {
            // Expand variables
            String expandedPath = pathStr
                    .replace("${user.home}", userHome)
                    .replace("$HOME", userHome);
            expanded.add(Path.of(expandedPath));
        }

        return expanded;
    }

    /**
     * Returns the maximum search depth.
     */
    public int getMaxDepth() {
        return maxDepth;
    }

    /**
     * Loads the workspace discovery configuration.
     *
     * <p>Searches for config file in:
     * <ol>
     *   <li>~/.synthesis/config/workspace-discovery.yaml</li>
     *   <li>Falls back to default paths if not found</li>
     * </ol>
     *
     * @return loaded configuration
     */
    public static WorkspaceDiscoveryConfig load() {
        String userHome = System.getProperty("user.home");
        Path configPath = Path.of(userHome, ".synthesis", "config", CONFIG_FILE);

        if (Files.exists(configPath)) {
            try {
                WorkspaceDiscoveryConfig config = loadFromFile(configPath);
                LOG.info("Loaded workspace discovery config from " + configPath);
                LOG.info("Search paths: " + config.searchPaths);
                return config;
            } catch (Exception e) {
                LOG.warning("Failed to load workspace discovery config from " + configPath + ": " + e.getMessage());
                LOG.warning("Falling back to default search paths");
                e.printStackTrace();
            }
        } else {
            LOG.info("No config file found at " + configPath + ", using defaults");
        }

        return getDefaults();
    }

    /**
     * Loads configuration from a YAML file.
     */
    @SuppressWarnings("unchecked")
    private static WorkspaceDiscoveryConfig loadFromFile(Path configPath) throws IOException {
        Yaml yaml = new Yaml();
        String content = Files.readString(configPath);
        Map<String, Object> config = yaml.load(content);

        List<String> searchPaths = (List<String>) config.getOrDefault("searchPaths", getDefaultPaths());
        int maxDepth = (Integer) config.getOrDefault("maxDepth", 1);

        return new WorkspaceDiscoveryConfig(searchPaths, maxDepth);
    }

    /**
     * Returns default configuration.
     */
    private static WorkspaceDiscoveryConfig getDefaults() {
        return new WorkspaceDiscoveryConfig(getDefaultPaths(), 1);
    }

    /**
     * Returns default search paths.
     */
    private static List<String> getDefaultPaths() {
        return List.of(
                "${user.home}/Documents",
                "${user.home}/Downloads",
                "${user.home}/Pictures",
                "/src",
                "${user.home}/src"
        );
    }
}
