package io.exoreaction.synthesis.update;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks the state of a Synthesis installation at {@code ~/.synthesis/.installation.json}.
 *
 * <p>The fingerprint records which version is installed, what components are present,
 * and how the installation was performed. This allows the update mechanism to detect
 * incomplete or outdated installations and determine what needs updating.
 *
 * <p>Example usage:
 * <pre>
 *   // Read existing fingerprint
 *   InstallationFingerprint fp = InstallationFingerprint.load(synthesisHome);
 *
 *   // Check what's installed
 *   if (!fp.hasComponent("synthesis-mcp-server")) {
 *       System.out.println("MCP server not installed");
 *   }
 *
 *   // Update after installing a component
 *   fp.setComponent("synthesis-mcp-server", true, "1.0.4-SNAPSHOT");
 *   fp.save(synthesisHome);
 * </pre>
 *
 * @author Thor Henning Hetland / eXOReaction
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstallationFingerprint {

    private static final String FINGERPRINT_FILE = ".installation.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty("version")
    private String version;

    @JsonProperty("installDate")
    private String installDate;

    @JsonProperty("lastUpdateDate")
    private String lastUpdateDate;

    @JsonProperty("installMethod")
    private String installMethod; // "installer", "source", "manual"

    @JsonProperty("installSource")
    private String installSource; // "github-release", "cantara-release", "cantara-snapshot", "source-build"

    @JsonProperty("sourceDirectory")
    private String sourceDirectory;

    @JsonProperty("components")
    private Map<String, ComponentState> components = new LinkedHashMap<>();

    /** Default constructor for Jackson. */
    public InstallationFingerprint() {}

    // --- Factory ---

    /**
     * Create a new fingerprint for a fresh installation.
     */
    public static InstallationFingerprint createNew(String version, String method, String source) {
        InstallationFingerprint fp = new InstallationFingerprint();
        fp.version = version;
        fp.installDate = Instant.now().toString();
        fp.installMethod = method;
        fp.installSource = source;
        return fp;
    }

    // --- Accessors ---

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getInstallDate() { return installDate; }

    public String getLastUpdateDate() { return lastUpdateDate; }
    public void setLastUpdateDate(String lastUpdateDate) { this.lastUpdateDate = lastUpdateDate; }

    public String getInstallMethod() { return installMethod; }
    public void setInstallMethod(String installMethod) { this.installMethod = installMethod; }

    public String getInstallSource() { return installSource; }
    public void setInstallSource(String installSource) { this.installSource = installSource; }

    public String getSourceDirectory() { return sourceDirectory; }
    public void setSourceDirectory(String sourceDirectory) { this.sourceDirectory = sourceDirectory; }

    public Map<String, ComponentState> getComponents() { return components; }

    // --- Component management ---

    /**
     * Check if a component is installed.
     */
    public boolean hasComponent(String name) {
        ComponentState state = components.get(name);
        return state != null && state.isInstalled();
    }

    /**
     * Get the state of a specific component.
     */
    public ComponentState getComponent(String name) {
        return components.get(name);
    }

    /**
     * Record a component as installed or not installed.
     */
    public void setComponent(String name, boolean installed, String version) {
        ComponentState state = components.computeIfAbsent(name, k -> new ComponentState());
        state.setInstalled(installed);
        state.setVersion(version);
        if (installed) {
            state.setInstalledDate(Instant.now().toString());
        }
    }

    /**
     * Record a component with checksum information.
     */
    public void setComponent(String name, boolean installed, String version, String checksum) {
        setComponent(name, installed, version);
        if (checksum != null) {
            components.get(name).setChecksum(checksum);
        }
    }

    /**
     * Count installed components.
     */
    public long installedCount() {
        return components.values().stream()
                .filter(ComponentState::isInstalled)
                .count();
    }

    /**
     * Mark the installation as having been updated.
     */
    public void markUpdated(String newVersion) {
        this.version = newVersion;
        this.lastUpdateDate = Instant.now().toString();
    }

    // --- Persistence ---

    /**
     * Load fingerprint from the installation directory.
     *
     * @param synthesisHome the ~/.synthesis directory
     * @return the loaded fingerprint, or a new empty one if not found
     */
    public static InstallationFingerprint load(Path synthesisHome) {
        Path fingerprintPath = synthesisHome.resolve(FINGERPRINT_FILE);
        if (Files.exists(fingerprintPath)) {
            try {
                String json = Files.readString(fingerprintPath);
                return MAPPER.readValue(json, InstallationFingerprint.class);
            } catch (IOException e) {
                // Corrupted fingerprint -- start fresh
                System.err.println("Warning: Could not read installation fingerprint: " + e.getMessage());
            }
        }
        return new InstallationFingerprint();
    }

    /**
     * Check if a fingerprint file exists at the given path.
     */
    public static boolean exists(Path synthesisHome) {
        return Files.exists(synthesisHome.resolve(FINGERPRINT_FILE));
    }

    /**
     * Save fingerprint to the installation directory.
     *
     * @param synthesisHome the ~/.synthesis directory
     * @throws IOException if the file cannot be written
     */
    public void save(Path synthesisHome) throws IOException {
        Path fingerprintPath = synthesisHome.resolve(FINGERPRINT_FILE);
        Files.createDirectories(synthesisHome);
        Files.writeString(fingerprintPath, MAPPER.writeValueAsString(this));
    }

    /**
     * Build a fingerprint by detecting what is currently installed.
     * Useful for existing installations that don't have a fingerprint yet.
     *
     * @param synthesisHome the ~/.synthesis directory
     * @return a fingerprint reflecting the current installation state
     */
    public static InstallationFingerprint detect(Path synthesisHome) {
        InstallationFingerprint fp = new InstallationFingerprint();

        // Read version from metadata
        Path versionFile = synthesisHome.resolve(".metadata/version");
        if (Files.exists(versionFile)) {
            try {
                fp.version = Files.readString(versionFile).trim();
            } catch (IOException e) {
                fp.version = "unknown";
            }
        } else {
            fp.version = "unknown";
        }

        // Read install date
        Path installDateFile = synthesisHome.resolve(".metadata/install-date");
        if (Files.exists(installDateFile)) {
            try {
                fp.installDate = Files.readString(installDateFile).trim();
            } catch (IOException ignored) {}
        }

        // Read source directory
        Path sourceDirFile = synthesisHome.resolve(".metadata/source-dir");
        if (Files.exists(sourceDirFile)) {
            try {
                fp.sourceDirectory = Files.readString(sourceDirFile).trim();
                fp.installMethod = "source";
                fp.installSource = "source-build";
            } catch (IOException ignored) {}
        }

        if (fp.installMethod == null) {
            fp.installMethod = "installer";
            fp.installSource = "unknown";
        }

        // Detect installed components
        Path libDir = synthesisHome.resolve("lib");
        Path binDir = synthesisHome.resolve("bin");

        // CLI JAR
        boolean hasCli = Files.exists(libDir.resolve("current.jar"));
        fp.setComponent("synthesis-cli", hasCli, fp.version);

        // MCP server JAR
        boolean hasMcp = Files.exists(libDir.resolve("synthesis-mcp-server.jar"));
        fp.setComponent("synthesis-mcp-server", hasMcp, hasMcp ? fp.version : null);

        // LSP server JAR
        boolean hasLsp = Files.exists(libDir.resolve("synthesis-lsp-server.jar"));
        fp.setComponent("synthesis-lsp-server", hasLsp, hasLsp ? fp.version : null);

        // Launcher scripts
        fp.setComponent("launcher-synthesis", Files.exists(binDir.resolve("synthesis")),
                fp.version);
        fp.setComponent("launcher-mcp-server", Files.exists(binDir.resolve("synthesis-mcp-server")),
                hasMcp ? fp.version : null);
        fp.setComponent("launcher-lsp-server", Files.exists(binDir.resolve("synthesis-lsp-server")),
                hasLsp ? fp.version : null);

        // Update script
        fp.setComponent("update-script",
                Files.exists(binDir.resolve("update.sh")) || Files.exists(binDir.resolve("synthesis-update")),
                fp.version);

        return fp;
    }

    @Override
    public String toString() {
        return "InstallationFingerprint{version='" + version + "', method='" + installMethod
                + "', components=" + installedCount() + "/" + components.size() + "}";
    }

    // --- ComponentState ---

    /**
     * Tracks the state of a single installed component.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ComponentState {

        @JsonProperty("installed")
        private boolean installed;

        @JsonProperty("version")
        private String version;

        @JsonProperty("installedDate")
        private String installedDate;

        @JsonProperty("checksum")
        private String checksum;

        /** Default constructor for Jackson. */
        public ComponentState() {}

        public boolean isInstalled() { return installed; }
        public void setInstalled(boolean installed) { this.installed = installed; }

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }

        public String getInstalledDate() { return installedDate; }
        public void setInstalledDate(String installedDate) { this.installedDate = installedDate; }

        public String getChecksum() { return checksum; }
        public void setChecksum(String checksum) { this.checksum = checksum; }

        @Override
        public String toString() {
            return "ComponentState{installed=" + installed + ", version='" + version + "'}";
        }
    }
}
