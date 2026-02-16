package io.exoreaction.synthesis.core;

import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;

import java.nio.file.Path;
import java.util.List;

/**
 * Resolves which sub-workspace a file belongs to based on its path.
 *
 * <p>This class is the single source of truth for sub-workspace resolution.
 * Used by ScanCommand, WatchCommand, MaintainCommand, and FileIndexer
 * to consistently tag files with their sub-workspace.
 *
 * <p>Resolution algorithm:
 * <ol>
 *   <li>Compute file's relative path from workspace root</li>
 *   <li>Check each sub-workspace's path prefix against the relative path</li>
 *   <li>Use the longest matching prefix (most specific match wins)</li>
 *   <li>Return null if no sub-workspace matches (file belongs to root workspace)</li>
 * </ol>
 *
 * @since v1.4.0
 */
public class SubWorkspaceResolver {

    private final List<SynthesisConfig.SubWorkspaceConfig> subWorkspaces;

    /**
     * Creates a resolver from a list of sub-workspace configurations.
     *
     * @param subWorkspaces the configured sub-workspaces (may be null or empty)
     */
    public SubWorkspaceResolver(List<SynthesisConfig.SubWorkspaceConfig> subWorkspaces) {
        this.subWorkspaces = subWorkspaces != null ? subWorkspaces : List.of();
    }

    /**
     * Creates a resolver from a full Synthesis configuration.
     *
     * @param config the workspace configuration
     */
    public SubWorkspaceResolver(SynthesisConfig config) {
        this(config != null ? config.getSubWorkspaces() : null);
    }

    /**
     * Resolves which sub-workspace a file belongs to.
     *
     * @param relativePath the file's path relative to the workspace root
     * @return the matching sub-workspace name, or null if no match
     */
    public String resolve(String relativePath) {
        return ConfigLoader.resolveSubWorkspace(relativePath, subWorkspaces);
    }

    /**
     * Resolves which sub-workspace a file belongs to using its absolute path.
     *
     * @param filePath      absolute path to the file
     * @param workspaceRoot the workspace root directory
     * @return the matching sub-workspace name, or null if no match
     */
    public String resolve(Path filePath, Path workspaceRoot) {
        if (filePath == null || workspaceRoot == null) return null;
        try {
            String relativePath = workspaceRoot.relativize(filePath).toString();
            return resolve(relativePath);
        } catch (IllegalArgumentException e) {
            // filePath is not relative to workspaceRoot
            return null;
        }
    }

    /**
     * Returns whether any sub-workspaces are configured.
     */
    public boolean hasSubWorkspaces() {
        return !subWorkspaces.isEmpty();
    }

    /**
     * Returns the number of configured sub-workspaces.
     */
    public int count() {
        return subWorkspaces.size();
    }

    /**
     * Finds the sub-workspace configuration by name.
     *
     * @param name the sub-workspace name
     * @return the matching configuration, or null if not found
     */
    public SynthesisConfig.SubWorkspaceConfig findByName(String name) {
        if (name == null) return null;
        for (SynthesisConfig.SubWorkspaceConfig sw : subWorkspaces) {
            if (name.equals(sw.getName())) {
                return sw;
            }
        }
        return null;
    }

    /**
     * Returns the list of configured sub-workspaces.
     */
    public List<SynthesisConfig.SubWorkspaceConfig> getSubWorkspaces() {
        return subWorkspaces;
    }
}
