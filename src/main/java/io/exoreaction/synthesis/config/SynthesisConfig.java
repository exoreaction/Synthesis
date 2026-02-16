package io.exoreaction.synthesis.config;

import io.exoreaction.synthesis.workspace.WorkspaceMetadata;
import io.exoreaction.synthesis.workspace.WorkspaceType;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
        private List<String> excludePatterns = List.of(
                "**/node_modules/**", "**/.git/**", "**/target/**",
                "**/build/**", "**/.gradle/**", "**/__pycache__/**",
                "**/.venv/**", "**/venv/**", "**/.idea/**",
                "**/.vscode/**", "**/.synthesis/**",
                "**/dist/**", "**/out/**"
        );
        private boolean computeHashes = true;
        private long maxFileSizeBytes = 10 * 1024 * 1024; // 10 MB

        public List<String> getIncludePatterns() { return includePatterns; }
        public void setIncludePatterns(List<String> includePatterns) { this.includePatterns = includePatterns; }

        public List<String> getExcludePatterns() { return excludePatterns; }
        public void setExcludePatterns(List<String> excludePatterns) { this.excludePatterns = excludePatterns; }

        public boolean isComputeHashes() { return computeHashes; }
        public void setComputeHashes(boolean computeHashes) { this.computeHashes = computeHashes; }

        public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
        public void setMaxFileSizeBytes(long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }
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
}
