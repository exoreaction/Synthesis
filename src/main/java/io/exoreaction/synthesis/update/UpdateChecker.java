package io.exoreaction.synthesis.update;

import io.exoreaction.synthesis.util.Version;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Lightweight, non-blocking update checker that runs in the background.
 *
 * <p>Designed to be called on every CLI invocation without impacting
 * user experience. Checks are throttled to once per day. Results are
 * cached to a file so they can be displayed on the next invocation.
 *
 * <p>Usage from SynthesisApp:
 * <pre>
 *   // At startup (non-blocking)
 *   UpdateChecker.checkInBackground(synthesisHome);
 *
 *   // Before command execution
 *   UpdateChecker.showPendingNotification(synthesisHome);
 * </pre>
 *
 * @author Thor Henning Hetland / eXOReaction
 */
public class UpdateChecker {

    private static final String GITHUB_API = "https://api.github.com/repos/exoreaction/Synthesis/releases/latest";
    private static final long CHECK_INTERVAL_SECONDS = 86400; // 24 hours
    private static final String RESULT_FILE = "update-check-result";
    private static final String LAST_CHECK_FILE = "last-update-check";

    private UpdateChecker() {}

    /**
     * Run a lightweight update check in the background.
     * Does not block the calling thread.
     *
     * @param synthesisHome the ~/.synthesis directory
     */
    public static void checkInBackground(Path synthesisHome) {
        if (synthesisHome == null || !Files.exists(synthesisHome)) return;
        if (isUpdateCheckDisabled()) return;
        if (!shouldCheck(synthesisHome)) return;

        Thread checker = new Thread(() -> {
            try {
                performCheck(synthesisHome);
            } catch (Exception e) {
                // Silently ignore -- update checks should never crash the app
            }
        }, "synthesis-update-checker");
        checker.setDaemon(true);
        checker.start();
    }

    /**
     * Show any pending update notification from a previous background check.
     * This is meant to be called synchronously before command output.
     *
     * @param synthesisHome the ~/.synthesis directory
     * @return true if a notification was shown
     */
    public static boolean showPendingNotification(Path synthesisHome) {
        if (synthesisHome == null || !Files.exists(synthesisHome)) return false;
        if (isUpdateCheckDisabled()) return false;

        Path resultFile = synthesisHome.resolve(".metadata").resolve(RESULT_FILE);
        if (!Files.exists(resultFile)) return false;

        try {
            String result = Files.readString(resultFile).trim();
            if (result.isEmpty()) return false;

            // Parse the result: "version|description"
            String[] parts = result.split("\\|", 2);
            if (parts.length >= 1 && !parts[0].isEmpty()) {
                String currentVersion = Version.getVersion();
                String latestVersion = parts[0];
                String description = parts.length > 1 ? parts[1] : "";

                // Only show if genuinely newer
                if (VersionManifest.compareVersions(latestVersion, currentVersion) > 0) {
                    System.err.println();
                    System.err.println("  \u26A0  Update available: " + currentVersion + " -> " + latestVersion);
                    if (!description.isEmpty()) {
                        System.err.println("     " + description);
                    }
                    System.err.println("     Run 'synthesis-update' to update.");
                    System.err.println();

                    // Clear the notification after showing
                    Files.deleteIfExists(resultFile);
                    return true;
                }
            }

            // Clear stale result
            Files.deleteIfExists(resultFile);
        } catch (IOException e) {
            // Ignore
        }

        return false;
    }

    /**
     * Check whether the SYNTHESIS_NO_UPDATE_CHECK environment variable is set.
     */
    private static boolean isUpdateCheckDisabled() {
        String env = System.getenv("SYNTHESIS_NO_UPDATE_CHECK");
        return "1".equals(env) || "true".equalsIgnoreCase(env);
    }

    /**
     * Whether enough time has passed since the last check.
     */
    private static boolean shouldCheck(Path synthesisHome) {
        Path lastCheckFile = synthesisHome.resolve(".metadata").resolve(LAST_CHECK_FILE);
        if (!Files.exists(lastCheckFile)) return true;

        try {
            String content = Files.readString(lastCheckFile).trim();
            long lastCheck = Long.parseLong(content);
            long now = Instant.now().getEpochSecond();
            return (now - lastCheck) >= CHECK_INTERVAL_SECONDS;
        } catch (Exception e) {
            return true; // Check if we can't read the timestamp
        }
    }

    /**
     * Perform the actual update check.
     */
    private static void performCheck(Path synthesisHome) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_API))
                    .header("Accept", "application/vnd.github.v3+json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Update last check timestamp regardless of result
            Path metaDir = synthesisHome.resolve(".metadata");
            Files.createDirectories(metaDir);
            Files.writeString(metaDir.resolve(LAST_CHECK_FILE),
                    String.valueOf(Instant.now().getEpochSecond()));

            if (response.statusCode() == 200) {
                // Parse minimal info from response
                String body = response.body();
                String latestVersion = extractJsonField(body, "tag_name");
                if (latestVersion != null) {
                    latestVersion = latestVersion.replaceFirst("^v", "");

                    String currentVersion = Version.getVersion();
                    if (VersionManifest.compareVersions(latestVersion, currentVersion) > 0) {
                        // Extract release name for description
                        String releaseName = extractJsonField(body, "name");
                        String description = releaseName != null ? releaseName : "";

                        // Also check for missing MCP/LSP components
                        InstallationFingerprint fp = InstallationFingerprint.detect(synthesisHome);
                        if (!fp.hasComponent("synthesis-mcp-server")) {
                            description += " (includes MCP server)";
                        }
                        if (!fp.hasComponent("synthesis-lsp-server")) {
                            description += " (includes LSP server)";
                        }

                        // Write result for next invocation to display
                        Files.writeString(metaDir.resolve(RESULT_FILE),
                                latestVersion + "|" + description.trim());
                    } else {
                        // No update needed -- clear any old result
                        Files.deleteIfExists(metaDir.resolve(RESULT_FILE));
                    }
                }
            }
        } catch (Exception e) {
            // Silently ignore all errors in background check
        }
    }

    /**
     * Simple JSON field extractor (avoids parsing dependency in background thread).
     * Only works for top-level string fields.
     */
    private static String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]+)\"";
        var matcher = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
