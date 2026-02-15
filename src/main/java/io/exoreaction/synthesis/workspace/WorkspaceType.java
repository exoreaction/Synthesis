package io.exoreaction.synthesis.workspace;

/**
 * Classification of Synthesis workspace types.
 *
 * <p>Used to categorize workspaces for filtering, unified MCP serving,
 * and cross-workspace search operations.
 *
 * <ul>
 *   <li>{@link #SOURCE_CODE} -- Repositories containing source code (Java, JS, Python, etc.)</li>
 *   <li>{@link #DOCUMENTS} -- Knowledge bases, documentation, business files</li>
 *   <li>{@link #MIXED} -- Workspaces containing both code and documents</li>
 * </ul>
 */
public enum WorkspaceType {

    /** Source code repositories (e.g., /src/exoreaction, /src/cantara). */
    SOURCE_CODE("source-code", "Source code repositories"),

    /** Document-oriented workspaces (e.g., ~/Documents, ~/Downloads). */
    DOCUMENTS("documents", "Document and knowledge workspaces"),

    /** Mixed workspaces containing both code and documents. */
    MIXED("mixed", "Mixed code and document workspaces");

    private final String configValue;
    private final String description;

    WorkspaceType(String configValue, String description) {
        this.configValue = configValue;
        this.description = description;
    }

    /**
     * Returns the value used in config.yaml files.
     */
    public String getConfigValue() {
        return configValue;
    }

    /**
     * Returns a human-readable description.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Parses a workspace type from a config value string.
     *
     * <p>Accepts both enum names and config values (case-insensitive):
     * "source-code", "SOURCE_CODE", "source_code" all map to {@link #SOURCE_CODE}.
     *
     * @param value the config value or enum name
     * @return the matching WorkspaceType
     * @throws IllegalArgumentException if no match found
     */
    public static WorkspaceType fromConfigValue(String value) {
        if (value == null || value.isBlank()) {
            return MIXED; // default
        }

        String normalized = value.trim().toLowerCase();

        for (WorkspaceType type : values()) {
            if (type.configValue.equals(normalized) ||
                type.name().toLowerCase().equals(normalized) ||
                type.name().toLowerCase().replace('_', '-').equals(normalized)) {
                return type;
            }
        }

        // Legacy type values (backward compatibility with existing "general" type)
        return switch (normalized) {
            case "general", "plugin-ecosystem", "monorepo", "multi-project" -> MIXED;
            default -> MIXED;
        };
    }

    @Override
    public String toString() {
        return configValue;
    }
}
