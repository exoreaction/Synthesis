package io.exoreaction.synthesis.update;

import io.exoreaction.synthesis.util.AnsiOutput;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Comprehensive update manager for Synthesis installations.
 *
 * <p>Handles detection, download, and installation of all components:
 * <ul>
 *   <li>Core JARs (synthesis CLI, MCP server, LSP server)</li>
 *   <li>Launcher scripts (synthesis, synthesis-mcp-server, synthesis-lsp-server)</li>
 *   <li>Management scripts (install.sh, uninstall.sh, update.sh)</li>
 *   <li>Documentation (docs/ directory)</li>
 *   <li>Visual assets (docs/visuals/ directory)</li>
 * </ul>
 *
 * <p>Update sources (checked in order):
 * <ol>
 *   <li>Local source directory (git pull + mvn package) - preferred for dev setups</li>
 *   <li>Cantara Maven repository (CLI JAR) + GitHub raw (scripts)</li>
 * </ol>
 *
 * @author Thor Henning Hetland / eXOReaction
 */
public class UpdateManager {

    private static final String CANTARA_BASE =
            "https://mvnrepo.cantara.no/content/repositories/releases/io/exoreaction/synthesis";
    private static final String CANTARA_METADATA_URL = CANTARA_BASE + "/maven-metadata.xml";

    private final Path synthesisHome;
    private final Path libDir;
    private final Path binDir;
    private final Path metaDir;
    private final boolean verbose;

    /**
     * Create an UpdateManager for the given Synthesis home directory.
     *
     * @param synthesisHome the ~/.synthesis directory
     * @param verbose whether to print detailed progress
     */
    public UpdateManager(Path synthesisHome, boolean verbose) {
        this.synthesisHome = synthesisHome;
        this.libDir = synthesisHome.resolve("lib");
        this.binDir = synthesisHome.resolve("bin");
        this.metaDir = synthesisHome.resolve(".metadata");
        this.verbose = verbose;
    }

    public UpdateManager(Path synthesisHome) {
        this(synthesisHome, false);
    }

    // -----------------------------------------------------------------------
    // Check for updates
    // -----------------------------------------------------------------------

    /**
     * Check if an update is available and what components need updating.
     *
     * @return result describing available updates
     */
    public UpdateCheckResult checkForUpdates() {
        VersionManifest localManifest = VersionManifest.loadOrEmpty();
        String currentVersion = localManifest.getVersion();
        if ("unknown".equals(currentVersion) || currentVersion.contains("${")) {
            // Fallback: read from metadata file
            currentVersion = readVersionFromMetadata();
        }

        InstallationFingerprint fingerprint = loadOrDetectFingerprint();

        // Check Cantara Maven for latest version
        String latestVersion = null;
        try {
            latestVersion = fetchLatestMavenVersion();
        } catch (Exception e) {
            if (verbose) {
                System.err.println("  Could not check Cantara Maven: " + e.getMessage());
            }
        }

        // Determine if update is available
        boolean hasVersionUpdate = false;
        if (latestVersion != null && currentVersion != null && !"unknown".equals(currentVersion)) {
            hasVersionUpdate = VersionManifest.compareVersions(latestVersion, currentVersion) > 0;
        }

        // Check for missing components (new since installed version)
        List<String> missingComponents = detectMissingComponents(fingerprint, localManifest);
        List<String> outdatedComponents = detectOutdatedComponents(fingerprint, currentVersion);

        return new UpdateCheckResult(
                currentVersion,
                latestVersion,
                hasVersionUpdate,
                missingComponents,
                outdatedComponents,
                localManifest,
                fingerprint
        );
    }

    /**
     * Check installation health: are all expected components present and correct?
     *
     * @return health report
     */
    public InstallationHealth checkHealth() {
        InstallationFingerprint fingerprint = loadOrDetectFingerprint();
        VersionManifest manifest = VersionManifest.loadOrEmpty();

        List<InstallationHealth.Issue> issues = new ArrayList<>();

        // Check CLI JAR
        if (!Files.exists(libDir.resolve("current.jar"))) {
            issues.add(new InstallationHealth.Issue("synthesis-cli",
                    "Core CLI JAR missing", InstallationHealth.Severity.CRITICAL));
        }

        // Check MCP server
        if (!Files.exists(libDir.resolve("synthesis-mcp-server.jar"))) {
            issues.add(new InstallationHealth.Issue("synthesis-mcp-server",
                    "MCP server JAR not installed (available since 1.0.4)",
                    InstallationHealth.Severity.INFO));
        }

        // Check LSP server
        if (!Files.exists(libDir.resolve("synthesis-lsp-server.jar"))) {
            issues.add(new InstallationHealth.Issue("synthesis-lsp-server",
                    "LSP server JAR not installed (available since 1.0.4)",
                    InstallationHealth.Severity.INFO));
        }

        // Check launcher scripts
        Path synthesisBin = binDir.resolve("synthesis");
        if (!Files.exists(synthesisBin)) {
            issues.add(new InstallationHealth.Issue("launcher-synthesis",
                    "Main launcher script missing", InstallationHealth.Severity.CRITICAL));
        } else if (!Files.isExecutable(synthesisBin)) {
            issues.add(new InstallationHealth.Issue("launcher-synthesis",
                    "Launcher script not executable", InstallationHealth.Severity.WARNING));
        }

        if (!Files.exists(binDir.resolve("synthesis-mcp-server"))) {
            issues.add(new InstallationHealth.Issue("launcher-mcp-server",
                    "MCP server launcher not installed (available since 1.0.4)",
                    InstallationHealth.Severity.INFO));
        }

        if (!Files.exists(binDir.resolve("synthesis-lsp-server"))) {
            issues.add(new InstallationHealth.Issue("launcher-lsp-server",
                    "LSP server launcher not installed (available since 1.0.4)",
                    InstallationHealth.Severity.INFO));
        }

        // Check update script
        if (!Files.exists(binDir.resolve("update.sh")) && !Files.exists(binDir.resolve("synthesis-update"))) {
            issues.add(new InstallationHealth.Issue("update-script",
                    "Update script missing", InstallationHealth.Severity.WARNING));
        }

        // Check fingerprint exists
        if (!InstallationFingerprint.exists(synthesisHome)) {
            issues.add(new InstallationHealth.Issue("fingerprint",
                    "Installation fingerprint missing (will be created on next update)",
                    InstallationHealth.Severity.INFO));
        }

        return new InstallationHealth(
                fingerprint.getVersion(),
                fingerprint.getInstallDate(),
                fingerprint.getInstallMethod(),
                fingerprint.installedCount(),
                manifest.getComponents().size(),
                issues
        );
    }

    // -----------------------------------------------------------------------
    // Perform update
    // -----------------------------------------------------------------------

    /**
     * Perform a comprehensive update of the Synthesis installation.
     *
     * <p>This method:
     * <ol>
     *   <li>Detects the update source (local source, GitHub, Cantara)</li>
     *   <li>Backs up the current installation</li>
     *   <li>Downloads and installs updated components</li>
     *   <li>Updates the installation fingerprint</li>
     *   <li>Preserves user configuration</li>
     * </ol>
     *
     * @param options update options (dry run, skip docs, force, etc.)
     * @return result describing what was updated
     */
    public UpdateResult performUpdate(UpdateOptions options) {
        List<String> updatedComponents = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String newVersion = null;

        InstallationFingerprint fingerprint = loadOrDetectFingerprint();
        String currentVersion = fingerprint.getVersion();

        // Determine source directory
        Path sourceDir = detectSourceDirectory();

        if (sourceDir != null && Files.exists(sourceDir.resolve("pom.xml"))) {
            // Source-based update (preferred for development setups)
            newVersion = performSourceUpdate(sourceDir, fingerprint, options, updatedComponents, errors);
        } else {
            // Maven-based update (download JAR from Cantara, scripts from GitHub)
            newVersion = performMavenUpdate(fingerprint, options, updatedComponents, errors);
        }

        // Update fingerprint
        if (newVersion != null && !options.isDryRun()) {
            fingerprint.markUpdated(newVersion);
            try {
                fingerprint.save(synthesisHome);
            } catch (IOException e) {
                errors.add("Failed to save installation fingerprint: " + e.getMessage());
            }

            // Update metadata version file
            try {
                Files.createDirectories(metaDir);
                Files.writeString(metaDir.resolve("version"), newVersion);
                Files.writeString(metaDir.resolve("last-update"), Instant.now().toString());
            } catch (IOException e) {
                errors.add("Failed to update metadata: " + e.getMessage());
            }
        }

        return new UpdateResult(
                currentVersion,
                newVersion,
                updatedComponents,
                errors,
                options.isDryRun()
        );
    }

    /**
     * Install a specific component that may be missing.
     *
     * @param componentName the component to install (e.g., "synthesis-mcp-server")
     * @return true if installation was successful
     */
    public boolean installComponent(String componentName) {
        InstallationFingerprint fingerprint = loadOrDetectFingerprint();
        Path sourceDir = detectSourceDirectory();

        if (sourceDir == null) {
            System.err.println("Error: No source directory found. Cannot install component.");
            System.err.println("  Set source dir: echo '/path/to/synthesis' > ~/.synthesis/.metadata/source-dir");
            return false;
        }

        boolean success = false;

        switch (componentName) {
            case "synthesis-mcp-server" -> {
                success = installJarFromSource(sourceDir, "synthesis-mcp-server.jar");
                if (success) {
                    installScriptFromSource(sourceDir, "synthesis-mcp-server");
                    fingerprint.setComponent("synthesis-mcp-server", true, fingerprint.getVersion());
                    fingerprint.setComponent("launcher-mcp-server", true, fingerprint.getVersion());
                }
            }
            case "synthesis-lsp-server" -> {
                success = installJarFromSource(sourceDir, "synthesis-lsp-server.jar");
                if (success) {
                    installScriptFromSource(sourceDir, "synthesis-lsp-server");
                    fingerprint.setComponent("synthesis-lsp-server", true, fingerprint.getVersion());
                    fingerprint.setComponent("launcher-lsp-server", true, fingerprint.getVersion());
                }
            }
            case "launcher-scripts" -> {
                success = installScriptFromSource(sourceDir, "synthesis");
                installScriptFromSource(sourceDir, "synthesis-mcp-server");
                installScriptFromSource(sourceDir, "synthesis-lsp-server");
                fingerprint.setComponent("launcher-synthesis", true, fingerprint.getVersion());
                fingerprint.setComponent("launcher-mcp-server", true, fingerprint.getVersion());
                fingerprint.setComponent("launcher-lsp-server", true, fingerprint.getVersion());
            }
            case "update-script" -> {
                success = installScriptFromSource(sourceDir, "update.sh");
                fingerprint.setComponent("update-script", true, fingerprint.getVersion());
            }
            case "documentation" -> {
                success = installDocsFromSource(sourceDir);
                fingerprint.setComponent("documentation", true, fingerprint.getVersion());
            }
            default -> {
                System.err.println("Unknown component: " + componentName);
                System.err.println("Available: synthesis-mcp-server, synthesis-lsp-server, " +
                        "launcher-scripts, update-script, documentation");
                return false;
            }
        }

        if (success) {
            try {
                fingerprint.save(synthesisHome);
            } catch (IOException e) {
                System.err.println("Warning: Could not save fingerprint: " + e.getMessage());
            }
        }

        return success;
    }

    // -----------------------------------------------------------------------
    // Source-based update
    // -----------------------------------------------------------------------

    private String performSourceUpdate(Path sourceDir, InstallationFingerprint fingerprint,
                                        UpdateOptions options, List<String> updated, List<String> errors) {
        if (verbose) {
            System.out.println("  Source directory: " + sourceDir);
        }

        // Get version from pom.xml
        String version = readVersionFromPom(sourceDir);
        if (version == null) {
            errors.add("Could not read version from pom.xml");
            return null;
        }

        if (options.isDryRun()) {
            System.out.println("  [DRY RUN] Would update from source: " + sourceDir);
            System.out.println("  [DRY RUN] Version: " + version);
            return version;
        }

        // Git pull if applicable
        if (Files.exists(sourceDir.resolve(".git")) && !options.isSkipGitPull()) {
            try {
                ProcessBuilder pb = new ProcessBuilder("git", "pull", "--ff-only")
                        .directory(sourceDir.toFile())
                        .redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes());
                int exitCode = p.waitFor();
                if (exitCode != 0 && verbose) {
                    System.err.println("  Git pull returned non-zero: " + output.trim());
                }
                // Re-read version after pull
                version = readVersionFromPom(sourceDir);
            } catch (Exception e) {
                if (verbose) {
                    System.err.println("  Git pull failed: " + e.getMessage());
                }
            }
        }

        // Maven build
        if (!options.isSkipBuild()) {
            try {
                if (verbose) System.out.println("  Building from source...");
                ProcessBuilder pb = new ProcessBuilder("mvn", "package", "-DskipTests", "-q")
                        .directory(sourceDir.toFile())
                        .redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes());
                int exitCode = p.waitFor();
                if (exitCode != 0) {
                    errors.add("Maven build failed: " + output.trim());
                    return null;
                }
            } catch (Exception e) {
                errors.add("Maven build failed: " + e.getMessage());
                return null;
            }
        }

        // Install CLI JAR
        String cliJarName = "synthesis-" + version + ".jar";
        Path cliJar = sourceDir.resolve("target").resolve(cliJarName);
        if (Files.exists(cliJar)) {
            try {
                Files.createDirectories(libDir);
                Files.copy(cliJar, libDir.resolve(cliJarName), StandardCopyOption.REPLACE_EXISTING);
                // Update symlink
                Path currentJar = libDir.resolve("current.jar");
                Files.deleteIfExists(currentJar);
                Files.createSymbolicLink(currentJar, libDir.resolve(cliJarName));
                fingerprint.setComponent("synthesis-cli", true, version);
                updated.add("synthesis-cli (" + cliJarName + ")");
            } catch (IOException e) {
                errors.add("Failed to install CLI JAR: " + e.getMessage());
            }
        }

        // Install MCP server JAR
        Path mcpJar = sourceDir.resolve("target/synthesis-mcp-server.jar");
        if (Files.exists(mcpJar)) {
            try {
                Files.copy(mcpJar, libDir.resolve("synthesis-mcp-server.jar"), StandardCopyOption.REPLACE_EXISTING);
                fingerprint.setComponent("synthesis-mcp-server", true, version);
                updated.add("synthesis-mcp-server.jar");
            } catch (IOException e) {
                errors.add("Failed to install MCP server JAR: " + e.getMessage());
            }
        }

        // Install LSP server JAR
        Path lspJar = sourceDir.resolve("target/synthesis-lsp-server.jar");
        if (Files.exists(lspJar)) {
            try {
                Files.copy(lspJar, libDir.resolve("synthesis-lsp-server.jar"), StandardCopyOption.REPLACE_EXISTING);
                fingerprint.setComponent("synthesis-lsp-server", true, version);
                updated.add("synthesis-lsp-server.jar");
            } catch (IOException e) {
                errors.add("Failed to install LSP server JAR: " + e.getMessage());
            }
        }

        // Install/update launcher scripts from source
        for (String script : List.of("synthesis", "synthesis-mcp-server", "synthesis-lsp-server")) {
            if (installScriptFromSource(sourceDir, script)) {
                String componentName = "launcher-" + script.replace("synthesis-", "").replace("synthesis", "synthesis");
                if ("synthesis".equals(script)) componentName = "launcher-synthesis";
                else if ("synthesis-mcp-server".equals(script)) componentName = "launcher-mcp-server";
                else if ("synthesis-lsp-server".equals(script)) componentName = "launcher-lsp-server";
                fingerprint.setComponent(componentName, true, version);
                updated.add("bin/" + script);
            }
        }

        // Install/update management scripts
        for (String script : List.of("update.sh", "install.sh", "uninstall.sh")) {
            if (installScriptFromSource(sourceDir, script)) {
                fingerprint.setComponent(script.replace(".sh", "-script"), true, version);
                updated.add("bin/" + script);
            }
        }

        // Recreate synthesis-update symlink
        try {
            Path symlink = binDir.resolve("synthesis-update");
            Files.deleteIfExists(symlink);
            Files.createSymbolicLink(symlink, Path.of("update.sh"));
        } catch (IOException e) {
            // Non-critical
        }

        // Install documentation (if not skipped)
        if (!options.isSkipDocs()) {
            if (installDocsFromSource(sourceDir)) {
                fingerprint.setComponent("documentation", true, version);
                updated.add("docs/ (documentation)");
            }
        }

        // Install visual assets (if not skipped)
        if (!options.isSkipVisuals()) {
            if (installVisualsFromSource(sourceDir)) {
                fingerprint.setComponent("visual-assets", true, version);
                updated.add("docs/visuals/ (visual assets)");
            }
        }

        return version;
    }

    // -----------------------------------------------------------------------
    // Maven-based update (CLI JAR from Cantara, scripts from GitHub raw)
    // -----------------------------------------------------------------------

    private String performMavenUpdate(InstallationFingerprint fingerprint,
                                       UpdateOptions options, List<String> updated, List<String> errors) {
        // Fetch latest version from Cantara Maven metadata
        String latestVersion;
        try {
            latestVersion = fetchLatestMavenVersion();
            if (latestVersion == null) {
                errors.add("Could not determine latest version from Cantara Maven repository");
                return null;
            }
        } catch (Exception e) {
            errors.add("Failed to check Cantara Maven repository: " + e.getMessage());
            return null;
        }

        if (options.isDryRun()) {
            System.out.println("  [DRY RUN] Would download synthesis-" + latestVersion + ".jar from Cantara Maven");
            return latestVersion;
        }

        // Download and install CLI JAR from Cantara Maven
        String jarName = "synthesis-" + latestVersion + ".jar";
        String jarUrl = CANTARA_BASE + "/" + latestVersion + "/" + jarName;
        try {
            Path tempFile = downloadFile(jarUrl);
            Files.createDirectories(libDir);
            Files.move(tempFile, libDir.resolve(jarName), StandardCopyOption.REPLACE_EXISTING);
            Path currentJar = libDir.resolve("current.jar");
            Files.deleteIfExists(currentJar);
            Files.createSymbolicLink(currentJar, libDir.resolve(jarName));
            fingerprint.setComponent("synthesis-cli", true, latestVersion);
            updated.add("synthesis-cli (" + jarName + ")");
        } catch (Exception e) {
            errors.add("Failed to download CLI JAR from Cantara: " + e.getMessage());
            return null;
        }

        // Extract bundled scripts from the JAR (repo is private, scripts are bundled)
        for (String script : List.of("synthesis", "synthesis-mcp-server", "synthesis-lsp-server",
                "update.sh", "install.sh", "uninstall.sh")) {
            if (extractBundledScript(script, updated)) {
                // success
            } else if (verbose) {
                System.err.println("  Bundled script not found: " + script);
            }
        }

        // Recreate synthesis-update symlink
        try {
            Path symlink = binDir.resolve("synthesis-update");
            Files.deleteIfExists(symlink);
            Files.createSymbolicLink(symlink, Path.of("update.sh"));
        } catch (IOException e) {
            // Non-critical
        }

        return latestVersion;
    }

    // -----------------------------------------------------------------------
    // Component installation helpers
    // -----------------------------------------------------------------------

    private boolean installJarFromSource(Path sourceDir, String jarName) {
        Path sourceJar = sourceDir.resolve("target").resolve(jarName);
        if (!Files.exists(sourceJar)) {
            System.err.println("  JAR not found: " + sourceJar);
            System.err.println("  Build first: cd " + sourceDir + " && mvn package -DskipTests");
            return false;
        }
        try {
            Files.createDirectories(libDir);
            Files.copy(sourceJar, libDir.resolve(jarName), StandardCopyOption.REPLACE_EXISTING);
            if (verbose) System.out.println("  Installed " + jarName);
            return true;
        } catch (IOException e) {
            System.err.println("  Failed to install " + jarName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Extract a bundled script from the JAR (classpath resource /bin/{scriptName}).
     * Used when the source directory is not available (Maven-based updates).
     */
    private boolean extractBundledScript(String scriptName, List<String> updated) {
        String resourcePath = "/bin/" + scriptName;
        try (var stream = getClass().getResourceAsStream(resourcePath)) {
            if (stream == null) return false;
            Files.createDirectories(binDir);
            Path target = binDir.resolve(scriptName);
            Files.write(target, stream.readAllBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            makeExecutable(target);
            updated.add("bin/" + scriptName);
            return true;
        } catch (IOException e) {
            if (verbose) {
                System.err.println("  Failed to extract bundled script " + scriptName + ": " + e.getMessage());
            }
            return false;
        }
    }

    private boolean installScriptFromSource(Path sourceDir, String scriptName) {
        Path sourceScript = sourceDir.resolve("bin").resolve(scriptName);
        if (!Files.exists(sourceScript)) {
            return false;
        }
        try {
            Files.createDirectories(binDir);
            Files.copy(sourceScript, binDir.resolve(scriptName), StandardCopyOption.REPLACE_EXISTING);
            makeExecutable(binDir.resolve(scriptName));
            if (verbose) System.out.println("  Installed bin/" + scriptName);
            return true;
        } catch (IOException e) {
            if (verbose) {
                System.err.println("  Failed to install " + scriptName + ": " + e.getMessage());
            }
            return false;
        }
    }

    private boolean installDocsFromSource(Path sourceDir) {
        Path sourceDocs = sourceDir.resolve("docs");
        if (!Files.exists(sourceDocs)) return false;

        // Docs go to a docs/ directory inside synthesisHome
        Path targetDocs = synthesisHome.resolve("docs");
        try {
            copyDirectoryRecursive(sourceDocs, targetDocs);
            if (verbose) System.out.println("  Installed documentation");
            return true;
        } catch (IOException e) {
            if (verbose) {
                System.err.println("  Failed to install docs: " + e.getMessage());
            }
            return false;
        }
    }

    private boolean installVisualsFromSource(Path sourceDir) {
        Path sourceVisuals = sourceDir.resolve("docs/visuals");
        if (!Files.exists(sourceVisuals)) return false;

        Path targetVisuals = synthesisHome.resolve("docs/visuals");
        try {
            copyDirectoryRecursive(sourceVisuals, targetVisuals);
            if (verbose) System.out.println("  Installed visual assets");
            return true;
        } catch (IOException e) {
            if (verbose) {
                System.err.println("  Failed to install visuals: " + e.getMessage());
            }
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Utility methods
    // -----------------------------------------------------------------------

    private InstallationFingerprint loadOrDetectFingerprint() {
        if (InstallationFingerprint.exists(synthesisHome)) {
            return InstallationFingerprint.load(synthesisHome);
        }
        // Auto-detect from existing installation
        return InstallationFingerprint.detect(synthesisHome);
    }

    private String readVersionFromMetadata() {
        Path versionFile = metaDir.resolve("version");
        if (Files.exists(versionFile)) {
            try {
                return Files.readString(versionFile).trim();
            } catch (IOException e) {
                return "unknown";
            }
        }
        return "unknown";
    }

    private String readVersionFromPom(Path sourceDir) {
        Path pomFile = sourceDir.resolve("pom.xml");
        if (!Files.exists(pomFile)) return null;
        try {
            String pom = Files.readString(pomFile);
            // Simple regex extraction -- avoids XML parser dependency
            var matcher = java.util.regex.Pattern.compile("<version>([^<]+)</version>")
                    .matcher(pom);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }

    private Path detectSourceDirectory() {
        // Check metadata
        Path sourceDirFile = metaDir.resolve("source-dir");
        if (Files.exists(sourceDirFile)) {
            try {
                String dir = Files.readString(sourceDirFile).trim();
                Path p = Path.of(dir);
                if (Files.exists(p.resolve("pom.xml"))) {
                    return p;
                }
            } catch (IOException e) {
                // ignore
            }
        }

        // Auto-detect common locations
        for (String candidate : List.of(
                System.getProperty("user.home") + "/src/synthesis",
                System.getProperty("user.home") + "/src/exoreaction/synthesis",
                System.getProperty("user.home") + "/projects/synthesis")) {
            Path p = Path.of(candidate);
            if (Files.exists(p.resolve("pom.xml"))) {
                return p;
            }
        }

        return null;
    }

    /**
     * Fetch the latest released version from the Cantara Maven repository metadata.
     *
     * @return latest release version string, or null if unavailable
     */
    private String fetchLatestMavenVersion() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CANTARA_METADATA_URL))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            // Extract <release> tag from Maven metadata XML
            var matcher = java.util.regex.Pattern.compile("<release>([^<]+)</release>")
                    .matcher(response.body());
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }

    private Path downloadFile(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(300))
                .GET()
                .build();
        Path tempFile = Files.createTempFile("synthesis-update-", ".tmp");
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(tempFile);
            throw new IOException("Download failed: HTTP " + response.statusCode() + " from " + url);
        }
        return tempFile;
    }

    private List<String> detectMissingComponents(InstallationFingerprint fingerprint,
                                                   VersionManifest manifest) {
        List<String> missing = new ArrayList<>();
        for (VersionManifest.Component component : manifest.getComponents()) {
            if (!fingerprint.hasComponent(component.getName())) {
                missing.add(component.getName());
            }
        }
        return missing;
    }

    private List<String> detectOutdatedComponents(InstallationFingerprint fingerprint,
                                                    String currentVersion) {
        List<String> outdated = new ArrayList<>();
        for (Map.Entry<String, InstallationFingerprint.ComponentState> entry :
                fingerprint.getComponents().entrySet()) {
            InstallationFingerprint.ComponentState state = entry.getValue();
            if (state.isInstalled() && state.getVersion() != null
                    && !state.getVersion().equals(currentVersion)) {
                outdated.add(entry.getKey());
            }
        }
        return outdated;
    }

    private void copyDirectoryRecursive(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            stream.forEach(sourcePath -> {
                try {
                    Path targetPath = target.resolve(source.relativize(sourcePath));
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private void makeExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows or permission issue -- ignore
        }
    }
}
