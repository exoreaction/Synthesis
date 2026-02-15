package io.exoreaction.synthesis;

import io.exoreaction.synthesis.cli.*;
import io.exoreaction.synthesis.telemetry.ApprovalService;
import io.exoreaction.synthesis.telemetry.ClientUUID;
import io.exoreaction.synthesis.telemetry.TelemetryService;
import io.exoreaction.synthesis.update.UpdateChecker;
import io.exoreaction.synthesis.util.Version;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Synthesis: AI operations partner for knowledge infrastructure.
 *
 * <p>A CLI tool that scans, indexes, and searches workspace file systems,
 * providing rapid discovery of documents, code, and knowledge artifacts.
 *
 * <p>Usage:
 * <pre>
 *   synthesis init [directory]     Initialize a workspace
 *   synthesis scan                 Scan and index files
 *   synthesis search <query>       Search the index
 *   synthesis ask <question>       AI-powered Q&A about your workspace
 *   synthesis analyze              Smart project analysis
 *   synthesis relate <file>        Show file relationships
 *   synthesis insights             Deep codebase analysis with metrics
 *   synthesis graph <file>         Generate visual knowledge graph
 *   synthesis cross-repo-deps      Find cross-repository dependencies
 *   synthesis watch                Monitor changes in real-time
 *   synthesis diff <ref>           Git diff integration
 *   synthesis changed --since <d>  Files changed since date
 *   synthesis maintain             Detect changes and update index
 *   synthesis export               Export index as JSON, Markdown, or AI docs
 *   synthesis status               Show workspace health
 *   synthesis org scan             Auto-discover organizational structure
 *   synthesis org list             Show companies, clients, products
 *   synthesis org classify         Classify Downloads files by organization
 *   synthesis learn                Generate Claude Code skills from workspace knowledge
 *   synthesis learn --install      Install skills to ~/.claude/skills/
 *   synthesis perspectives <q>     Analyze a question from multiple perspectives
 *   synthesis extract-slides <pdf> Extract slides from a presentation PDF
 *   synthesis telemetry            View pilot status and telemetry info
 *   synthesis update               Update all components (JARs, scripts, docs)
 *   synthesis update --check       Check for updates without installing
 *   synthesis update --health      Check installation health
 * </pre>
 *
 * @author Thor Henning Hetland / eXOReaction
 */
@Command(
        name = "synthesis",
        description = "AI operations partner for knowledge infrastructure",
        versionProvider = SynthesisApp.VersionProvider.class,
        mixinStandardHelpOptions = true,
        subcommands = {
                InitCommand.class,
                ScanCommand.class,
                SearchCommand.class,
                AskCommand.class,
                AnalyzeCommand.class,
                RelateCommand.class,
                InsightsCommand.class,
                GraphCommand.class,
                CrossRepoDepsCommand.class,
                WatchCommand.class,
                DiffCommand.class,
                ChangedCommand.class,
                MaintainCommand.class,
                ExportCommand.class,
                StatusCommand.class,
                OrgCommand.class,
                LearnCommand.class,
                PerspectivesCommand.class,
                ExtractSlidesCommand.class,
                TelemetryCommand.class,
                UpdateCommand.class
        }
)
public class SynthesisApp implements Callable<Integer> {

    @Option(
            names = {"-d", "--directory"},
            description = "Workspace root directory (default: current directory)",
            defaultValue = ".",
            scope = CommandLine.ScopeType.INHERIT
    )
    private Path workspaceRoot;

    /**
     * Returns the resolved workspace root directory.
     * Used by subcommands via @ParentCommand injection.
     */
    public Path getWorkspaceRoot() {
        return workspaceRoot.toAbsolutePath().normalize();
    }

    @Override
    public Integer call() {
        // No subcommand specified -- print usage
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        // Initialize telemetry (async, non-blocking, mandatory)
        TelemetryService telemetry = TelemetryService.create();

        // Check pilot approval status and show nag if not approved
        checkPilotApproval(telemetry.getClientUuid());

        // Background update check (non-blocking, daily)
        Path synthesisHome = Path.of(System.getProperty("user.home"), ".synthesis");
        UpdateChecker.checkInBackground(synthesisHome);

        // Show any pending update notification from previous check
        String firstArg = args.length > 0 ? args[0] : "";
        if (!"update".equals(firstArg) && !"--version".equals(firstArg) && !"-V".equals(firstArg)) {
            UpdateChecker.showPendingNotification(synthesisHome);
        }

        // Determine the command name for telemetry tracking
        String commandName = args.length > 0 ? args[0] : "help";
        long startTime = System.currentTimeMillis();

        int exitCode;
        try {
            exitCode = new CommandLine(new SynthesisApp())
                    .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                        System.err.println("Error: " + ex.getMessage());
                        return 1;
                    })
                    .execute(args);
        } catch (Exception e) {
            exitCode = 1;
        }

        // Report command execution (async, non-blocking, mandatory)
        long durationMs = System.currentTimeMillis() - startTime;
        telemetry.reportCommand(commandName, exitCode == 0, durationMs);

        // Allow pending telemetry events to be sent (max 2 seconds)
        telemetry.shutdown();

        System.exit(exitCode);
    }

    /**
     * Version provider for picocli that reads from Version utility
     * instead of a hardcoded string.
     */
    static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] { Version.getFullVersion() };
        }
    }

    /**
     * Checks pilot approval status and prints appropriate message.
     *
     * <p>Behavior:
     * <ul>
     *   <li>If approved: show welcome message (once per approval)</li>
     *   <li>If not approved: show 1-line nag message (non-blocking)</li>
     *   <li>If approval system not configured: silently skip</li>
     * </ul>
     *
     * <p>Triggers a daily refresh of approval status (first command after 24 hours).
     * This method never throws or blocks command execution.
     */
    private static void checkPilotApproval(String clientUuid) {
        try {
            if (clientUuid == null || "unknown".equals(clientUuid)) {
                return;
            }

            ApprovalService approval = ApprovalService.create();

            // If approval system is not configured, skip silently
            if (!approval.shouldRefresh() && approval.getCachedApproval() == null) {
                return;
            }

            boolean isApproved = approval.isApproved(clientUuid);

            if (isApproved) {
                // Show welcome message once after approval
                if (approval.shouldShowWelcome()) {
                    System.err.println("  \u2713 Pilot approved -- Thank you for participating!");
                }
            } else {
                // Nag message for unapproved installations (1 line, non-intrusive)
                System.err.println("  \u26A0\uFE0F  Synthesis pilot approval pending. UUID: "
                        + clientUuid + ". Contact maintainer for access.");
            }
        } catch (Exception e) {
            // Approval check should never prevent command execution
        }
    }
}
