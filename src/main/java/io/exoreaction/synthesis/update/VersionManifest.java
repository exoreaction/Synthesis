package io.exoreaction.synthesis.update;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Represents the Synthesis version manifest embedded in the JAR at build time.
 *
 * <p>The manifest is generated from {@code synthesis-manifest.json} with Maven
 * resource filtering replacing {@code ${project.version}} and similar placeholders.
 *
 * <p>Contains version information and a list of all distributable components
 * (JARs, scripts, documentation) that make up a complete Synthesis installation.
 *
 * @author Thor Henning Hetland / eXOReaction
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VersionManifest {

    private static final String MANIFEST_RESOURCE = "synthesis-manifest.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonProperty("version")
    private String version;

    @JsonProperty("artifactId")
    private String artifactId;

    @JsonProperty("groupId")
    private String groupId;

    @JsonProperty("buildTimestamp")
    private String buildTimestamp;

    @JsonProperty("components")
    private List<Component> components = Collections.emptyList();

    @JsonProperty("changelog")
    private String changelog;

    @JsonProperty("repository")
    private String repository;

    /** Default constructor for Jackson deserialization. */
    public VersionManifest() {}

    // --- Accessors ---

    public String getVersion() { return version; }
    public String getArtifactId() { return artifactId; }
    public String getGroupId() { return groupId; }
    public String getBuildTimestamp() { return buildTimestamp; }
    public List<Component> getComponents() { return components; }
    public String getChangelog() { return changelog; }
    public String getRepository() { return repository; }

    // --- Component lookup ---

    /**
     * Find a component by name.
     */
    public Optional<Component> getComponent(String name) {
        return components.stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst();
    }

    /**
     * Get all components of a given type (jar, script, docs, assets).
     */
    public List<Component> getComponentsByType(String type) {
        return components.stream()
                .filter(c -> type.equals(c.getType()))
                .toList();
    }

    /**
     * Get all required components.
     */
    public List<Component> getRequiredComponents() {
        return components.stream()
                .filter(Component::isRequired)
                .toList();
    }

    /**
     * Get components that were introduced in or after the given version.
     * Useful for detecting what's new since a user's installed version.
     */
    public List<Component> getComponentsNewSince(String sinceVersion) {
        return components.stream()
                .filter(c -> c.getSince() != null && compareVersions(c.getSince(), sinceVersion) > 0)
                .toList();
    }

    // --- Loading ---

    /**
     * Load the manifest from the classpath (embedded in JAR).
     *
     * @return the parsed manifest
     * @throws IOException if the manifest cannot be read or parsed
     */
    public static VersionManifest loadFromClasspath() throws IOException {
        try (InputStream is = VersionManifest.class.getClassLoader().getResourceAsStream(MANIFEST_RESOURCE)) {
            if (is == null) {
                throw new IOException("Manifest not found on classpath: " + MANIFEST_RESOURCE);
            }
            return MAPPER.readValue(is, VersionManifest.class);
        }
    }

    /**
     * Load a manifest from a file path (e.g., downloaded from GitHub).
     *
     * @param path the path to the manifest JSON file
     * @return the parsed manifest
     * @throws IOException if the file cannot be read or parsed
     */
    public static VersionManifest loadFromFile(Path path) throws IOException {
        return MAPPER.readValue(Files.readString(path), VersionManifest.class);
    }

    /**
     * Load a manifest from a JSON string.
     *
     * @param json the JSON content
     * @return the parsed manifest
     * @throws IOException if the JSON cannot be parsed
     */
    public static VersionManifest loadFromString(String json) throws IOException {
        return MAPPER.readValue(json, VersionManifest.class);
    }

    /**
     * Try to load from classpath; return empty manifest on failure.
     */
    public static VersionManifest loadOrEmpty() {
        try {
            return loadFromClasspath();
        } catch (IOException e) {
            VersionManifest empty = new VersionManifest();
            empty.version = "unknown";
            empty.components = Collections.emptyList();
            return empty;
        }
    }

    // --- Version comparison ---

    /**
     * Compare two version strings (e.g., "1.0.3" vs "1.0.4-SNAPSHOT").
     * Returns positive if v1 > v2, negative if v1 < v2, 0 if equal.
     *
     * <p>SNAPSHOT versions are treated as pre-release (lower than release of same number).
     */
    public static int compareVersions(String v1, String v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;

        boolean v1Snap = v1.endsWith("-SNAPSHOT");
        boolean v2Snap = v2.endsWith("-SNAPSHOT");
        String v1Base = v1Snap ? v1.substring(0, v1.length() - 9) : v1;
        String v2Base = v2Snap ? v2.substring(0, v2.length() - 9) : v2;

        String[] parts1 = v1Base.split("\\.");
        String[] parts2 = v2Base.split("\\.");
        int maxLen = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < maxLen; i++) {
            int p1 = i < parts1.length ? parseIntSafe(parts1[i]) : 0;
            int p2 = i < parts2.length ? parseIntSafe(parts2[i]) : 0;
            if (p1 != p2) return Integer.compare(p1, p2);
        }

        // Same base version: release > snapshot
        if (v1Snap && !v2Snap) return -1;
        if (!v1Snap && v2Snap) return 1;
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public String toString() {
        return "VersionManifest{version='" + version + "', components=" + components.size() + "}";
    }

    // --- Component record ---

    /**
     * Represents a single distributable component in the Synthesis installation.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Component {

        @JsonProperty("name")
        private String name;

        @JsonProperty("description")
        private String description;

        @JsonProperty("file")
        private String file;

        @JsonProperty("path")
        private String path;

        @JsonProperty("type")
        private String type;

        @JsonProperty("required")
        private boolean required;

        @JsonProperty("since")
        private String since;

        /** Default constructor for Jackson. */
        public Component() {}

        public String getName() { return name; }
        public String getDescription() { return description; }

        /**
         * The file path for single-file components (JARs, scripts).
         * May be null for directory-based components (docs, assets).
         */
        public String getFile() { return file; }

        /**
         * The directory path for multi-file components (docs, assets).
         * May be null for single-file components.
         */
        public String getPath() { return path; }

        /** Component type: jar, script, docs, assets */
        public String getType() { return type; }

        /** Whether this component is required for basic operation. */
        public boolean isRequired() { return required; }

        /** Version when this component was first introduced (e.g., "1.0.4"). */
        public String getSince() { return since; }

        /**
         * Returns the effective path, preferring file over path.
         */
        public String getEffectivePath() {
            return file != null ? file : path;
        }

        @Override
        public String toString() {
            return "Component{name='" + name + "', type='" + type + "', since='" + since + "'}";
        }
    }
}
