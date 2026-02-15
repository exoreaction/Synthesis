package io.exoreaction.synthesis.workspace;

/**
 * Extended metadata for a Synthesis workspace.
 *
 * <p>Stored in the workspace section of config.yaml alongside name, type,
 * and description. Provides categorization data used by cross-workspace
 * search, unified MCP serving, and the list command filtering.
 *
 * <p>Design: Uses plain Java class (not record) for SnakeYAML compatibility.
 * SnakeYAML requires no-arg constructors and setter methods for deserialization.
 */
public class WorkspaceMetadata {

    /** Workspace classification: source-code, documents, or mixed. */
    private String category = "mixed";

    /** Primary programming language (e.g., "java", "javascript", "python"). Null for document workspaces. */
    private String primaryLanguage;

    /** Number of repositories in this workspace (for source-code workspaces). */
    private int repoCount;

    /** Brief description of the workspace purpose. */
    private String description = "";

    /** Company or organization that owns this workspace. */
    private String company;

    /** Tags for additional classification. */
    private String tags = "";

    // --- No-arg constructor for SnakeYAML ---

    public WorkspaceMetadata() {}

    // --- Builder-style constructor ---

    public WorkspaceMetadata(String category, String primaryLanguage, int repoCount,
                             String description, String company) {
        this.category = category;
        this.primaryLanguage = primaryLanguage;
        this.repoCount = repoCount;
        this.description = description;
        this.company = company;
    }

    // --- Getters and Setters ---

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getPrimaryLanguage() { return primaryLanguage; }
    public void setPrimaryLanguage(String primaryLanguage) { this.primaryLanguage = primaryLanguage; }

    public int getRepoCount() { return repoCount; }
    public void setRepoCount(int repoCount) { this.repoCount = repoCount; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    /**
     * Returns the resolved WorkspaceType for this metadata.
     */
    public WorkspaceType getWorkspaceType() {
        return WorkspaceType.fromConfigValue(category);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WorkspaceMetadata{");
        sb.append("category='").append(category).append('\'');
        if (primaryLanguage != null) sb.append(", lang=").append(primaryLanguage);
        if (repoCount > 0) sb.append(", repos=").append(repoCount);
        if (company != null) sb.append(", company=").append(company);
        sb.append('}');
        return sb.toString();
    }
}
