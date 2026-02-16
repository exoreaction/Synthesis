package io.exoreaction.synthesis.config;

import io.exoreaction.synthesis.core.Ecosystem;
import io.exoreaction.synthesis.core.EcosystemDetector;
import io.exoreaction.synthesis.core.SmartExclusions;
import io.exoreaction.synthesis.workspace.WorkspaceMetadata;
import io.exoreaction.synthesis.workspace.WorkspaceType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Root configuration for a Synthesis workspace.
 * Loaded from synthesis-config.yaml in the workspace root or .synthesis/ directory.
 *
 * <p>Design: Uses plain Java classes (not records) for SnakeYAML compatibility.
 * SnakeYAML requires no-arg constructors and setter methods for deserialization.
 * Values are validated after loading via {@link ConfigLoader#validate}.
 */
public class SynthesisConfig {

    private WorkspaceConfig workspace = new WorkspaceConfig();
    private SearchConfig search = new SearchConfig();
    private AiConfig ai = new AiConfig();
    private ScanConfig scan = new ScanConfig();
    private TrackingConfig tracking = new TrackingConfig();
    private ChangelogConfig changelog = new ChangelogConfig();
    private List<SubWorkspaceConfig> subWorkspaces = new ArrayList<>();
    private StagingConfig staging = new StagingConfig();

    public WorkspaceConfig getWorkspace() { return workspace; }
    public void setWorkspace(WorkspaceConfig workspace) { this.workspace = workspace; }

    public SearchConfig getSearch() { return search; }
    public void setSearch(SearchConfig search) { this.search = search; }

    public AiConfig getAi() { return ai; }
    public void setAi(AiConfig ai) { this.ai = ai; }

    public ScanConfig getScan() { return scan; }
    public void setScan(ScanConfig scan) { this.scan = scan; }

    public TrackingConfig getTracking() { return tracking; }
    public void setTracking(TrackingConfig tracking) { this.tracking = tracking; }

    public ChangelogConfig getChangelog() { return changelog; }
    public void setChangelog(ChangelogConfig changelog) { this.changelog = changelog; }

    public List<SubWorkspaceConfig> getSubWorkspaces() { return subWorkspaces; }
    public void setSubWorkspaces(List<SubWorkspaceConfig> subWorkspaces) {
        this.subWorkspaces = subWorkspaces != null ? subWorkspaces : new ArrayList<>();
    }

    public StagingConfig getStaging() { return staging; }
    public void setStaging(StagingConfig staging) { this.staging = staging; }

    /**
     * Workspace identity and structure configuration.
     */
    public static class WorkspaceConfig {
        private String name = "";
        private String type = "general";
        private String description = "";
        private WorkspaceMetadata metadata = new WorkspaceMetadata();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public WorkspaceMetadata getMetadata() { return metadata; }
        public void setMetadata(WorkspaceMetadata metadata) { this.metadata = metadata; }

        /**
         * Returns the resolved workspace type, preferring metadata.category
         * over the legacy type field.
         */
        public WorkspaceType getWorkspaceType() {
            if (metadata != null && metadata.getCategory() != null
                    && !metadata.getCategory().isBlank()
                    && !"mixed".equals(metadata.getCategory())) {
                return WorkspaceType.fromConfigValue(metadata.getCategory());
            }
            return WorkspaceType.fromConfigValue(type);
        }
    }

    /**
     * Search index configuration.
     */
    public static class SearchConfig {
        private int maxResults = 20;
        private int previewLength = 200;
        private int contentPreviewBytes = 10240; // 10 KB content indexed per file

        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }

        public int getPreviewLength() { return previewLength; }
        public void setPreviewLength(int previewLength) { this.previewLength = previewLength; }

        public int getContentPreviewBytes() { return contentPreviewBytes; }
        public void setContentPreviewBytes(int contentPreviewBytes) { this.contentPreviewBytes = contentPreviewBytes; }
    }

    /**
     * AI provider configuration (optional -- Synthesis works without AI).
     */
    public static class AiConfig {
        private boolean enabled = false;
        private String model = "claude-sonnet-4-5-20250929";
        private boolean readmeGeneration = true;
        private boolean contentSummary = false;
        private int maxTokens = 1024;
        private VisionConfig vision = new VisionConfig();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public boolean isReadmeGeneration() { return readmeGeneration; }
        public void setReadmeGeneration(boolean readmeGeneration) { this.readmeGeneration = readmeGeneration; }

        public boolean isContentSummary() { return contentSummary; }
        public void setContentSummary(boolean contentSummary) { this.contentSummary = contentSummary; }

        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

        public VisionConfig getVision() { return vision; }
        public void setVision(VisionConfig vision) { this.vision = vision; }
    }

    /**
     * Vision AI configuration for image analysis.
     * Vision is enabled by default when AI is enabled (opt-out with --no-vision).
     */
    public static class VisionConfig {
        /** Vision is enabled by default -- use --no-vision to disable. */
        private boolean enabled = true;
        /** Estimated cost per image analysis in USD. */
        private double costPerImageUsd = 0.02;
        /** Maximum image file size to analyze (default: 20 MB). */
        private long maxImageSizeBytes = 20 * 1024 * 1024;
        /** Whether to require confirmation before vision analysis. */
        private boolean confirmBeforeScan = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public double getCostPerImageUsd() { return costPerImageUsd; }
        public void setCostPerImageUsd(double costPerImageUsd) { this.costPerImageUsd = costPerImageUsd; }

        public long getMaxImageSizeBytes() { return maxImageSizeBytes; }
        public void setMaxImageSizeBytes(long maxImageSizeBytes) { this.maxImageSizeBytes = maxImageSizeBytes; }

        public boolean isConfirmBeforeScan() { return confirmBeforeScan; }
        public void setConfirmBeforeScan(boolean confirmBeforeScan) { this.confirmBeforeScan = confirmBeforeScan; }
    }

    /**
     * Scan behavior configuration.
     */
    public static class ScanConfig {
        private List<String> includePatterns = List.of(
                "**/*.md", "**/*.java", "**/*.py", "**/*.js", "**/*.ts",
                "**/*.yaml", "**/*.yml", "**/*.json", "**/*.sh",
                "**/*.xml", "**/*.toml", "**/*.cfg", "**/*.properties",
                "**/*.go", "**/*.rs", "**/*.rb", "**/*.kt", "**/*.scala",
                "**/*.sql", "**/*.html", "**/*.css",
                "**/*.txt", "**/*.pdf",
                // Media files (analyzed for metadata, searchable via descriptions)
                "**/*.png", "**/*.jpg", "**/*.jpeg", "**/*.gif", "**/*.bmp",
                "**/*.svg", "**/*.webp", "**/*.tiff", "**/*.tif",
                "**/*.mp4", "**/*.avi", "**/*.mov", "**/*.mkv", "**/*.webm",
                "**/*.mp3", "**/*.wav", "**/*.flac", "**/*.ogg", "**/*.aac"
        );
        private List<String> excludePatterns = List.of();
        private boolean useSmartDefaults = true;
        private boolean computeHashes = true;
        private long maxFileSizeBytes = 10 * 1024 * 1024; // 10 MB

        public List<String> getIncludePatterns() { return includePatterns; }
        public void setIncludePatterns(List<String> includePatterns) { this.includePatterns = includePatterns; }

        public List<String> getExcludePatterns() { return excludePatterns; }
        public void setExcludePatterns(List<String> excludePatterns) { this.excludePatterns = excludePatterns; }

        public boolean isUseSmartDefaults() { return useSmartDefaults; }
        public void setUseSmartDefaults(boolean useSmartDefaults) { this.useSmartDefaults = useSmartDefaults; }

        public boolean isComputeHashes() { return computeHashes; }
        public void setComputeHashes(boolean computeHashes) { this.computeHashes = computeHashes; }

        public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
        public void setMaxFileSizeBytes(long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }

        /**
         * Returns the effective exclude patterns to use for scanning.
         * If useSmartDefaults is true, merges universal patterns, ecosystem-specific patterns,
         * and user-specified patterns. Otherwise, returns only user-specified patterns.
         *
         * @param workspaceRoot the workspace root directory for ecosystem detection
         * @return list of effective exclude patterns
         */
        public List<String> getEffectiveExcludePatterns(Path workspaceRoot) {
            if (!useSmartDefaults) {
                return excludePatterns;
            }

            // Merge: UNIVERSAL + ecosystem patterns + user patterns
            Set<String> merged = new HashSet<>();
            merged.addAll(SmartExclusions.UNIVERSAL);

            // Add ecosystem-specific patterns
            Set<Ecosystem> ecosystems = EcosystemDetector.detect(workspaceRoot);
            for (Ecosystem ecosystem : ecosystems) {
                merged.addAll(ecosystem.getExclusionPatterns());
            }

            // Add user-specified patterns
            merged.addAll(excludePatterns);

            return new ArrayList<>(merged);
        }
    }

    /**
     * File movement tracking configuration (v1.3.0+).
     */
    public static class TrackingConfig {
        private boolean enabled = true;
        private int safetyPeriodDays = 7;
        private boolean autoDetect = true;
        private long watchCorrelationWindowMs = 60000;
        private int retentionDays = 90;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getSafetyPeriodDays() { return safetyPeriodDays; }
        public void setSafetyPeriodDays(int safetyPeriodDays) { this.safetyPeriodDays = safetyPeriodDays; }

        public boolean isAutoDetect() { return autoDetect; }
        public void setAutoDetect(boolean autoDetect) { this.autoDetect = autoDetect; }

        public long getWatchCorrelationWindowMs() { return watchCorrelationWindowMs; }
        public void setWatchCorrelationWindowMs(long watchCorrelationWindowMs) {
            this.watchCorrelationWindowMs = watchCorrelationWindowMs;
        }

        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    }

    /**
     * Cross-workspace change reporting configuration (v1.3.0+).
     */
    public static class ChangelogConfig {
        private boolean enabled = true;
        private boolean autoSnapshot = true;
        private int snapshotIntervalHours = 6;
        private int retentionDays = 90;
        private SignificanceConfig significance = new SignificanceConfig();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isAutoSnapshot() { return autoSnapshot; }
        public void setAutoSnapshot(boolean autoSnapshot) { this.autoSnapshot = autoSnapshot; }

        public int getSnapshotIntervalHours() { return snapshotIntervalHours; }
        public void setSnapshotIntervalHours(int snapshotIntervalHours) {
            this.snapshotIntervalHours = snapshotIntervalHours;
        }

        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

        public SignificanceConfig getSignificance() { return significance; }
        public void setSignificance(SignificanceConfig significance) { this.significance = significance; }

        /**
         * Significance classification configuration.
         */
        public static class SignificanceConfig {
            private List<String> noisePaths = List.of();
            private List<String> criticalPaths = List.of();
            private int massDeleteThreshold = 10;

            public List<String> getNoisePaths() { return noisePaths; }
            public void setNoisePaths(List<String> noisePaths) { this.noisePaths = noisePaths; }

            public List<String> getCriticalPaths() { return criticalPaths; }
            public void setCriticalPaths(List<String> criticalPaths) { this.criticalPaths = criticalPaths; }

            public int getMassDeleteThreshold() { return massDeleteThreshold; }
            public void setMassDeleteThreshold(int massDeleteThreshold) {
                this.massDeleteThreshold = massDeleteThreshold;
            }
        }
    }

    /**
     * Sub-workspace configuration for logical partitioning within a workspace (v1.4.0+).
     *
     * <p>A sub-workspace maps a directory prefix (relative path) to a named logical partition.
     * Files within that directory are tagged with the sub-workspace name in the index,
     * enabling scoped search, per-partition analytics, and organizational hierarchy.
     *
     * <p>Sub-workspaces support inheritance: scan/exclude overrides are merged with
     * the parent workspace's configuration. Unset fields fall back to parent defaults.
     *
     * <p>Example YAML:
     * <pre>
     * subWorkspaces:
     *   - name: "eXOReaction"
     *     path: "eXOReaction"
     *     description: "eXOReaction company files"
     *     tags:
     *       - "company"
     *       - "core"
     *     excludePatterns:
     *       - "**&#47;archive/**"
     * </pre>
     */
    public static class SubWorkspaceConfig {
        private String name = "";
        private String path = "";
        private String description = "";
        private String type = "general";
        private List<String> tags = new ArrayList<>();
        private List<String> includePatterns = null;
        private List<String> excludePatterns = null;

        public SubWorkspaceConfig() {}

        public SubWorkspaceConfig(String name, String path) {
            this.name = name;
            this.path = path;
        }

        /** Logical name for this sub-workspace (e.g., "eXOReaction", "Quadim"). */
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        /** Relative path prefix from workspace root (e.g., "eXOReaction" or "src/main"). */
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        /** Human-readable description. */
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        /** Sub-workspace type: "general", "source-code", "documents", "staging". */
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        /** Optional tags for classification and filtering. */
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags != null ? tags : new ArrayList<>(); }

        /**
         * Include patterns override (null = inherit from parent).
         * If set, these patterns are used instead of the parent's include patterns
         * for files within this sub-workspace.
         */
        public List<String> getIncludePatterns() { return includePatterns; }
        public void setIncludePatterns(List<String> includePatterns) { this.includePatterns = includePatterns; }

        /**
         * Exclude patterns override (null = inherit from parent).
         * If set, these patterns are ADDED to the parent's exclude patterns.
         */
        public List<String> getExcludePatterns() { return excludePatterns; }
        public void setExcludePatterns(List<String> excludePatterns) { this.excludePatterns = excludePatterns; }

        /**
         * Returns whether this sub-workspace is a staging type.
         */
        public boolean isStaging() {
            return "staging".equals(type);
        }

        @Override
        public String toString() {
            return "SubWorkspaceConfig{name='" + name + "', path='" + path + "'}";
        }
    }

    /**
     * Staging workspace configuration for incoming/temporary files (v1.4.0+).
     *
     * <p>Staging workspaces have special behavior:
     * <ul>
     *   <li>Time-based retention with configurable expiry</li>
     *   <li>Promotion workflow to move files to permanent sub-workspaces</li>
     *   <li>Auto-classification using organizational context</li>
     *   <li>Periodic cleanup of expired files</li>
     * </ul>
     */
    public static class StagingConfig {
        private boolean enabled = false;
        private int retentionDays = 30;
        private boolean autoClassify = true;
        private double classificationThreshold = 0.5;
        private boolean cleanupExpired = false;

        /** Whether staging functionality is enabled. */
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        /** Number of days to retain unclassified files before cleanup. */
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

        /** Whether to auto-classify incoming files using DownloadsClassifier. */
        public boolean isAutoClassify() { return autoClassify; }
        public void setAutoClassify(boolean autoClassify) { this.autoClassify = autoClassify; }

        /** Minimum confidence threshold for auto-classification. */
        public double getClassificationThreshold() { return classificationThreshold; }
        public void setClassificationThreshold(double classificationThreshold) {
            this.classificationThreshold = classificationThreshold;
        }

        /** Whether to automatically delete files that exceed retentionDays. */
        public boolean isCleanupExpired() { return cleanupExpired; }
        public void setCleanupExpired(boolean cleanupExpired) { this.cleanupExpired = cleanupExpired; }
    }
}
