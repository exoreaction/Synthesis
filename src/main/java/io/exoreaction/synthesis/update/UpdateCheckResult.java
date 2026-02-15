package io.exoreaction.synthesis.update;

import java.util.Collections;
import java.util.List;

/**
 * Result of checking for available updates.
 *
 * <p>Contains information about the current version, latest available version,
 * and what components are missing or outdated.
 *
 * @author Thor Henning Hetland / eXOReaction
 */
public class UpdateCheckResult {

    private final String currentVersion;
    private final String latestVersion;
    private final boolean hasVersionUpdate;
    private final List<String> missingComponents;
    private final List<String> outdatedComponents;
    private final VersionManifest manifest;
    private final InstallationFingerprint fingerprint;

    public UpdateCheckResult(String currentVersion, String latestVersion,
                             boolean hasVersionUpdate,
                             List<String> missingComponents,
                             List<String> outdatedComponents,
                             VersionManifest manifest,
                             InstallationFingerprint fingerprint) {
        this.currentVersion = currentVersion;
        this.latestVersion = latestVersion;
        this.hasVersionUpdate = hasVersionUpdate;
        this.missingComponents = missingComponents != null ? missingComponents : Collections.emptyList();
        this.outdatedComponents = outdatedComponents != null ? outdatedComponents : Collections.emptyList();
        this.manifest = manifest;
        this.fingerprint = fingerprint;
    }

    /** Current installed version. */
    public String getCurrentVersion() { return currentVersion; }

    /** Latest available version (may be null if check failed). */
    public String getLatestVersion() { return latestVersion; }

    /** Whether a newer version is available. */
    public boolean hasVersionUpdate() { return hasVersionUpdate; }

    /** Whether any update is needed (version or components). */
    public boolean hasUpdate() {
        return hasVersionUpdate || !missingComponents.isEmpty() || !outdatedComponents.isEmpty();
    }

    /** Components present in the manifest but not installed. */
    public List<String> getMissingComponents() { return missingComponents; }

    /** Components installed but at a different version than current. */
    public List<String> getOutdatedComponents() { return outdatedComponents; }

    /** Components that are new (not just missing, but introduced after installed version). */
    public List<String> getNewComponents() {
        if (manifest == null || currentVersion == null) return Collections.emptyList();
        return manifest.getComponentsNewSince(currentVersion).stream()
                .map(VersionManifest.Component::getName)
                .toList();
    }

    /** The full version manifest. */
    public VersionManifest getManifest() { return manifest; }

    /** The installation fingerprint. */
    public InstallationFingerprint getFingerprint() { return fingerprint; }

    /**
     * Get a human-readable summary for display to the user.
     */
    public String getSummary() {
        if (!hasUpdate()) {
            return "Up to date: " + currentVersion;
        }

        StringBuilder sb = new StringBuilder();
        if (hasVersionUpdate) {
            sb.append("Update available: ").append(currentVersion)
                    .append(" -> ").append(latestVersion);
        }
        if (!missingComponents.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("Missing components: ").append(String.join(", ", missingComponents));
        }
        if (!outdatedComponents.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("Outdated components: ").append(String.join(", ", outdatedComponents));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "UpdateCheckResult{current=" + currentVersion + ", latest=" + latestVersion
                + ", hasUpdate=" + hasUpdate()
                + ", missing=" + missingComponents.size()
                + ", outdated=" + outdatedComponents.size() + "}";
    }
}
