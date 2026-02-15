package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.update.*;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.Version;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * CLI command for comprehensive Synthesis updates.
 *
 * <p>Provides update checking, health monitoring, and component management:
 * <pre>
 *   synthesis update                           # Full update
 *   synthesis update --check                   # Check what's available
 *   synthesis update --health                  # Check installation health
 *   synthesis update --install-component mcp   # Install specific component
 *   synthesis update --dry-run                 # Show what would change
 * </pre>
 *
 * @author Thor Henning Hetland / eXOReaction
 */
@Command(
        name = "update",
        description = "Update Synthesis to the latest version (JARs, scripts, docs, assets)",
        mixinStandardHelpOptions = true
)
public class UpdateCommand implements Callable<Integer> {

    @Option(names = {"--check"}, description = "Check for updates without installing")
    private boolean checkOnly;

    @Option(names = {"--health"}, description = "Check installation health")
    private boolean healthCheck;

    @Option(names = {"--install-component"},
            description = "Install a specific component (synthesis-mcp-server, synthesis-lsp-server, launcher-scripts, update-script, documentation)")
    private String installComponent;

    @Option(names = {"--dry-run"}, description = "Show what would be updated without making changes")
    private boolean dryRun;

    @Option(names = {"--force"}, description = "Force update even if versions match")
    private boolean force;

    @Option(names = {"--skip-docs"}, description = "Skip documentation files")
    private boolean skipDocs;

    @Option(names = {"--skip-visuals"}, description = "Skip visual assets (saves ~270MB)")
    private boolean skipVisuals;

    @Option(names = {"--skip-build"}, description = "Skip Maven build (use existing JARs from target/)")
    private boolean skipBuild;

    @Option(names = {"--verbose", "-v"}, description = "Show detailed progress")
    private boolean verbose;

    @Override
    public Integer call() {
        Path synthesisHome = Path.of(System.getProperty("user.home"), ".synthesis");
        if (!Files.exists(synthesisHome)) {
            AnsiOutput.printError("Synthesis not installed at " + synthesisHome);
            System.out.println("  Run the installer first:");
            System.out.println("    curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash");
            return 1;
        }

        UpdateManager manager = new UpdateManager(synthesisHome, verbose);

        if (healthCheck) {
            return performHealthCheck(manager);
        }

        if (installComponent != null) {
            return performComponentInstall(manager);
        }

        if (checkOnly) {
            return performCheck(manager);
        }

        return performUpdate(manager);
    }

    // -----------------------------------------------------------------------
    // Check for updates
    // -----------------------------------------------------------------------

    private int performCheck(UpdateManager manager) {
        AnsiOutput.printHeader("Synthesis Update Check");

        System.out.println("  Checking for updates...");
        UpdateCheckResult result = manager.checkForUpdates();

        System.out.printf("  %-20s %s%n", "Current version:", AnsiOutput.bold(result.getCurrentVersion()));

        if (result.getLatestVersion() != null) {
            System.out.printf("  %-20s %s%n", "Latest version:", AnsiOutput.bold(result.getLatestVersion()));
        } else {
            System.out.printf("  %-20s %s%n", "Latest version:", AnsiOutput.dim("Could not check"));
        }

        System.out.println();

        if (result.hasVersionUpdate()) {
            System.out.println("  " + AnsiOutput.success("Update available!") + " "
                    + result.getCurrentVersion() + " -> " + result.getLatestVersion());
        } else if (result.getLatestVersion() != null) {
            System.out.println("  " + AnsiOutput.success("Already up to date."));
        }

        // Show missing components
        if (!result.getMissingComponents().isEmpty()) {
            System.out.println();
            System.out.println("  " + AnsiOutput.bold("Missing components:"));
            for (String component : result.getMissingComponents()) {
                VersionManifest manifest = result.getManifest();
                String desc = "";
                if (manifest != null) {
                    desc = manifest.getComponent(component)
                            .map(c -> " - " + c.getDescription())
                            .orElse("");
                }
                System.out.println("    + " + AnsiOutput.yellow(component) + AnsiOutput.dim(desc));
            }
        }

        // Show outdated components
        if (!result.getOutdatedComponents().isEmpty()) {
            System.out.println();
            System.out.println("  " + AnsiOutput.bold("Outdated components:"));
            for (String component : result.getOutdatedComponents()) {
                System.out.println("    ~ " + AnsiOutput.yellow(component));
            }
        }

        if (result.hasUpdate()) {
            System.out.println();
            System.out.println("  Run " + AnsiOutput.cyan("synthesis update") + " to update.");
        }

        System.out.println();
        return 0;
    }

    // -----------------------------------------------------------------------
    // Health check
    // -----------------------------------------------------------------------

    private int performHealthCheck(UpdateManager manager) {
        AnsiOutput.printHeader("Synthesis Installation Health");

        InstallationHealth health = manager.checkHealth();

        System.out.printf("  %-20s %s%n", "Version:", AnsiOutput.bold(
                health.getVersion() != null ? health.getVersion() : "unknown"));
        System.out.printf("  %-20s %s%n", "Install date:",
                health.getInstallDate() != null ? health.getInstallDate() : "unknown");
        System.out.printf("  %-20s %s%n", "Install method:",
                health.getInstallMethod() != null ? health.getInstallMethod() : "unknown");
        System.out.printf("  %-20s %s%n", "Components:",
                health.getInstalledComponentCount() + "/" + health.getTotalComponentCount());
        System.out.println();

        if (health.isHealthy()) {
            System.out.println("  " + AnsiOutput.success("Installation is healthy!"));
        } else {
            // Critical issues
            for (InstallationHealth.Issue issue : health.getIssues(InstallationHealth.Severity.CRITICAL)) {
                System.out.println("  " + AnsiOutput.red("[CRITICAL]") + " " + issue.component()
                        + ": " + issue.message());
            }

            // Warnings
            for (InstallationHealth.Issue issue : health.getIssues(InstallationHealth.Severity.WARNING)) {
                System.out.println("  " + AnsiOutput.yellow("[WARNING]") + "  " + issue.component()
                        + ": " + issue.message());
            }

            // Info
            for (InstallationHealth.Issue issue : health.getIssues(InstallationHealth.Severity.INFO)) {
                System.out.println("  " + AnsiOutput.dim("[INFO]") + "     " + issue.component()
                        + ": " + issue.message());
            }

            System.out.println();
            if (health.hasCriticalIssues()) {
                System.out.println("  Recommendation: Run " + AnsiOutput.cyan("synthesis update --force")
                        + " to fix critical issues.");
            } else {
                System.out.println("  Recommendation: Run " + AnsiOutput.cyan("synthesis update")
                        + " to install missing components.");
            }
        }

        System.out.println();
        return health.hasCriticalIssues() ? 1 : 0;
    }

    // -----------------------------------------------------------------------
    // Install specific component
    // -----------------------------------------------------------------------

    private int performComponentInstall(UpdateManager manager) {
        System.out.println();
        System.out.println("  Installing component: " + AnsiOutput.bold(installComponent));
        System.out.println();

        boolean success = manager.installComponent(installComponent);

        if (success) {
            AnsiOutput.printSuccess("Component " + installComponent + " installed successfully.");

            // Show integration hints for MCP/LSP servers
            if ("synthesis-mcp-server".equals(installComponent)) {
                System.out.println();
                System.out.println("  " + AnsiOutput.bold("Configure Claude Code:"));
                System.out.println("  Add to ~/.claude/config.json:");
                System.out.println("    {");
                System.out.println("      \"mcpServers\": {");
                System.out.println("        \"synthesis\": {");
                System.out.println("          \"command\": \"synthesis-mcp-server\",");
                System.out.println("          \"args\": [\"--workspace\", \"/path/to/project\"]");
                System.out.println("        }");
                System.out.println("      }");
                System.out.println("    }");
            } else if ("synthesis-lsp-server".equals(installComponent)) {
                System.out.println();
                System.out.println("  " + AnsiOutput.bold("Configure VSCode:"));
                System.out.println("  See docs/guides/LSP-QUICKSTART.md for setup instructions.");
            }
        } else {
            AnsiOutput.printError("Failed to install component: " + installComponent);
            return 1;
        }

        System.out.println();
        return 0;
    }

    // -----------------------------------------------------------------------
    // Full update
    // -----------------------------------------------------------------------

    private int performUpdate(UpdateManager manager) {
        AnsiOutput.printHeader("Synthesis Comprehensive Update");

        // First check what's available
        System.out.println("  Checking for updates...");
        UpdateCheckResult checkResult = manager.checkForUpdates();

        System.out.printf("  %-20s %s%n", "Current version:", AnsiOutput.bold(checkResult.getCurrentVersion()));
        if (checkResult.getLatestVersion() != null) {
            System.out.printf("  %-20s %s%n", "Latest version:", AnsiOutput.bold(checkResult.getLatestVersion()));
        }
        System.out.println();

        if (!checkResult.hasUpdate() && !force) {
            System.out.println("  " + AnsiOutput.success("Already up to date.") + " No update needed.");

            // Still check for missing components
            if (!checkResult.getMissingComponents().isEmpty()) {
                System.out.println();
                System.out.println("  " + AnsiOutput.bold("Missing optional components:"));
                for (String component : checkResult.getMissingComponents()) {
                    System.out.println("    + " + component);
                }
                System.out.println();
                System.out.println("  Use " + AnsiOutput.cyan("synthesis update --force")
                        + " to install missing components.");
            }

            System.out.println();
            return 0;
        }

        // Show what will be updated
        if (!checkResult.getMissingComponents().isEmpty()) {
            System.out.println("  " + AnsiOutput.bold("New components to install:"));
            for (String component : checkResult.getMissingComponents()) {
                System.out.println("    + " + AnsiOutput.green(component));
            }
            System.out.println();
        }

        // Perform the update
        UpdateOptions options = new UpdateOptions()
                .dryRun(dryRun)
                .force(force)
                .skipDocs(skipDocs)
                .skipVisuals(skipVisuals)
                .skipBuild(skipBuild);

        System.out.println("  " + AnsiOutput.bold("Updating..."));
        System.out.println();

        UpdateResult result = manager.performUpdate(options);

        // Display results
        if (dryRun) {
            System.out.println("  " + AnsiOutput.yellow("[DRY RUN]") + " No changes were made.");
            System.out.println();
        }

        if (result.hasUpdates()) {
            System.out.println("  " + AnsiOutput.bold("Updated components:"));
            for (String component : result.getUpdatedComponents()) {
                System.out.println("    " + AnsiOutput.green("\u2713") + " " + component);
            }
            System.out.println();
        }

        if (!result.getErrors().isEmpty()) {
            System.out.println("  " + AnsiOutput.bold("Errors:"));
            for (String error : result.getErrors()) {
                System.out.println("    " + AnsiOutput.red("\u2717") + " " + error);
            }
            System.out.println();
        }

        if (result.isSuccessful()) {
            System.out.println("  " + AnsiOutput.success("Update complete!"));
            System.out.printf("  %-20s %s%n", "Previous:", result.getPreviousVersion());
            System.out.printf("  %-20s %s%n", "Current:", AnsiOutput.bold(result.getNewVersion()));

            // Show new capability hints
            if (checkResult.getMissingComponents().contains("synthesis-mcp-server")) {
                System.out.println();
                System.out.println("  " + AnsiOutput.cyan("New:") + " MCP server installed!"
                        + " See docs/guides/MCP-QUICKSTART.md");
            }
            if (checkResult.getMissingComponents().contains("synthesis-lsp-server")) {
                System.out.println("  " + AnsiOutput.cyan("New:") + " LSP server installed!"
                        + " See docs/guides/LSP-QUICKSTART.md");
            }
        } else if (!dryRun) {
            System.out.println("  " + AnsiOutput.warning("Update completed with errors."));
        }

        System.out.println();
        return result.isSuccessful() || dryRun ? 0 : 1;
    }
}
