package io.exoreaction.synthesis;

import io.exoreaction.synthesis.cli.*;
import io.exoreaction.synthesis.metrics.MetricsCollector;
import io.exoreaction.synthesis.telemetry.ApprovalConfig;
import io.exoreaction.synthesis.telemetry.ApprovalService;
import io.exoreaction.synthesis.telemetry.ClientUUID;
import io.exoreaction.synthesis.telemetry.TelemetryService;
import io.exoreaction.synthesis.update.UpdateChecker;
import io.exoreaction.synthesis.util.Version;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
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
 *   synthesis summary              Generate executive summaries at different levels
 *   synthesis org scan             Auto-discover organizational structure
 *   synthesis org list             Show companies, clients, products
 *   synthesis org classify         Classify Downloads files by organization
 *   synthesis learn                Generate Claude Code skills from workspace knowledge
 *   synthesis learn --install      Install skills to ~/.claude/skills/
 *   synthesis perspectives <q>     Analyze a question from multiple perspectives
 *   synthesis extract-slides <pdf> Extract slides from a presentation PDF
 *   synthesis telemetry            View pilot status and telemetry info
 *   synthesis which <file>          Find which workspace(s) contain a file
 *   synthesis search --all <query> Search across all workspaces
 *   synthesis list --type source   Filter workspaces by type
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
                ImpactCommand.class,
                CrossRepoDepsCommand.class,
                WatchCommand.class,
                DiffCommand.class,
                ChangedCommand.class,
                MaintainCommand.class,
                ExportCommand.class,
                StatusCommand.class,
                DashboardCommand.class,
                SummaryCommand.class,
                OrgCommand.class,
                LearnCommand.class,
                PerspectivesCommand.class,
                ExtractSlidesCommand.class,
                TelemetryCommand.class,
                UpdateCommand.class,
                EnrichCommand.class,
                ExplainCommand.class,
                ArchitectureCommand.class,
                MetricsCommand.class,
                ListWorkspacesCommand.class,
                WhichCommand.class,
                TrackCommand.class,
                ChangelogCommand.class,
                StagingCommand.class,
                DownloadsCommand.class,
                MigrateReposCommand.class,
                ExportSkillsCommand.class,
                UpcomingCommand.class,
                ResearchCommand.class,
                ReportCommand.class,
                CredentialsCommand.class,
                DiscoverCommand.class,
                ValidateCommand.class,
                TraceCommand.class,
                HealthCommand.class,
                NamingCommand.class,
                PruneCommand.class,
                SweepCommand.class,
                ScatterCommand.class,
                ConsolidateCommand.class,
                ArchiveCommand.class,
                TtlCommand.class,
                SyncCommand.class,
                RouteExplainCommand.class,
                DescribeCommand.class,
                FeedbackCommand.class,
                KnowledgeGraphCommand.class,
                StructuralAnalysisCommand.class
        }
)
public class SynthesisApp implements Callable<Integer> {

    @Option(
            names = {"-d", "--directory"},
            description = "Workspace root directory (default: configured workspace or current directory)",
            scope = CommandLine.ScopeType.INHERIT
    )
    private Path workspaceRoot;

    /**
     * Returns the resolved workspace root directory.
     * Used by subcommands via @ParentCommand injection.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Explicit -d/--directory flag (if provided)</li>
     *   <li>SYNTHESIS_WORKSPACE environment variable</li>
     *   <li>~/.synthesis/workspace file</li>
     *   <li>Current directory (fallback)</li>
     * </ol>
     */
    public Path getWorkspaceRoot() {
        // If explicitly provided via -d flag, use that
        if (workspaceRoot != null) {
            return workspaceRoot.toAbsolutePath().normalize();
        }

        // Try environment variable
        String envWorkspace = System.getenv("SYNTHESIS_WORKSPACE");
        if (envWorkspace != null && !envWorkspace.isBlank()) {
            return Path.of(envWorkspace).toAbsolutePath().normalize();
        }

        // Try ~/.synthesis/workspace file
        Path workspaceFile = Path.of(System.getProperty("user.home"), ".synthesis", "workspace");
        if (Files.exists(workspaceFile)) {
            try {
                String configuredWorkspace = Files.readString(workspaceFile).trim();
                if (!configuredWorkspace.isEmpty()) {
                    return Path.of(configuredWorkspace).toAbsolutePath().normalize();
                }
            } catch (Exception e) {
                // Ignore and fall through to default
            }
        }

        // Fall back to current directory
        return Path.of(".").toAbsolutePath().normalize();
    }

    // ---- Metrics ----

    private MetricsCollector metrics;

    /**
     * Returns the shared MetricsCollector, creating it lazily on first use.
     * Safe to call from any subcommand via {@code parent.getMetrics()}.
     */
    public MetricsCollector getMetrics() {
        if (metrics == null) {
            metrics = MetricsCollector.create();
        }
        return metrics;
    }

    /**
     * Shuts down the MetricsCollector if it was initialized.
     * Safe to call even if metrics were never used.
     */
    public void shutdownMetrics() {
        if (metrics != null) metrics.shutdown();
    }

    // ---- Edition Detection ----

    /** Commands that require AI/cloud connectivity and are disabled in air-gapped mode. */
    private static final Set<String> AI_COMMAND_NAMES = Set.of(
            "ask", "perspectives"
    );

    /**
     * Returns the current Synthesis edition.
     *
     * <p>Editions are determined by the {@code SYNTHESIS_EDITION} environment variable,
     * typically set by the launcher script ({@code bin/synthesis} or {@code bin/synthesis-core}).
     *
     * <p>Valid editions:
     * <ul>
     *   <li>{@code core} -- Air-gapped, no AI, no telemetry, no cloud</li>
     *   <li>{@code pro} -- Full features including AI (default)</li>
     *   <li>{@code enterprise} -- Air-gapped with daemon support</li>
     *   <li>{@code ultimate} -- Full features with daemon support</li>
     * </ul>
     *
     * @return the edition string, defaults to "pro" if not set
     */
    public static String getEdition() {
        String edition = System.getenv("SYNTHESIS_EDITION");
        return (edition != null && !edition.isBlank()) ? edition.toLowerCase() : "pro";
    }

    /**
     * Returns {@code true} if running in air-gapped mode (no cloud connectivity).
     *
     * <p>In air-gapped mode:
     * <ul>
     *   <li>AI-dependent commands (ask, perspectives) are not registered</li>
     *   <li>Telemetry is disabled</li>
     *   <li>Update checks are skipped</li>
     *   <li>Pilot approval checks are skipped</li>
     * </ul>
     *
     * <p>Air-gapped editions: {@code core}, {@code enterprise}.
     *
     * @return true if the current edition is air-gapped
     */
    public static boolean isAirGapped() {
        String edition = getEdition();
        return "core".equals(edition) || "enterprise".equals(edition);
    }

    @Override
    public Integer call() {
        // No subcommand specified -- print usage
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        boolean airGapped = isAirGapped();

        // Initialize telemetry (async, non-blocking)
        // In air-gapped mode, TelemetryService.create() returns a no-op instance
        TelemetryService telemetry = airGapped
                ? TelemetryService.createNoOp()
                : TelemetryService.create();

        // Skip pilot approval and update checks in air-gapped mode
        if (!airGapped) {
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
        }

        // Determine the command name for telemetry tracking
        String commandName = args.length > 0 ? args[0] : "help";
        long startTime = System.currentTimeMillis();

        int exitCode;
        SynthesisApp app = new SynthesisApp();
        try {
            CommandLine cmd = new CommandLine(app)
                    .setExecutionExceptionHandler((ex, cmdLine, parseResult) -> {
                        System.err.println("Error: " + ex.getMessage());
                        return 1;
                    });

            // Install grouped command help renderer
            installGroupedHelpRenderer(cmd);

            // In air-gapped mode, remove AI-dependent commands
            if (airGapped) {
                for (String aiCommand : AI_COMMAND_NAMES) {
                    cmd.getSubcommands().remove(aiCommand);
                }
            }

            exitCode = cmd.execute(args);
        } catch (Exception e) {
            exitCode = 1;
        }

        // Report command execution (async, non-blocking)
        long durationMs = System.currentTimeMillis() - startTime;
        telemetry.reportCommand(commandName, exitCode == 0, durationMs);

        // Allow pending telemetry events to be sent (max 2 seconds)
        telemetry.shutdown();

        // Flush any CLI-recorded metrics
        app.shutdownMetrics();

        System.exit(exitCode);
    }

    private static final java.util.LinkedHashMap<String, java.util.List<String>> HELP_GROUPS;
    static {
        HELP_GROUPS = new java.util.LinkedHashMap<>();
        HELP_GROUPS.put("Core:", java.util.List.of(
            "search", "maintain", "health", "init", "scan", "downloads"
        ));
        HELP_GROUPS.put("Analysis:", java.util.List.of(
            "ask", "analyze", "relate", "insights", "perspectives",
            "summary", "research", "explain", "architecture", "impact"
        ));
        HELP_GROUPS.put("Change Tracking:", java.util.List.of(
            "watch", "diff", "changed", "track", "changelog"
        ));
        HELP_GROUPS.put("Workspace:", java.util.List.of(
            "staging", "sweep", "prune", "ttl", "archive", "sync",
            "describe", "feedback", "knowledge-graph", "structure", "consolidate", "scatter", "naming"
        ));
        HELP_GROUPS.put("Admin:", java.util.List.of(
            "update", "telemetry", "credentials", "org", "learn",
            "status", "export", "graph", "cross-repo-deps", "which",
            "list", "discover", "validate", "report", "upcoming",
            "extract-slides", "migrate-repos", "export-skills", "trace",
            "metrics", "dashboard", "enrich"
        ));
    }

    /**
     * Installs the grouped command list renderer on the given {@link CommandLine}.
     * Called from {@link #main(String[])} and available for testing.
     */
    public static void installGroupedHelpRenderer(CommandLine cmd) {
        cmd.getHelpSectionMap().put(
            picocli.CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST,
            help -> renderGroupedCommandList(help)
        );
    }

    private static String renderGroupedCommandList(picocli.CommandLine.Help help) {
        java.util.Map<String, picocli.CommandLine> subcommands = help.commandSpec().subcommands();
        if (subcommands.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        java.util.Set<String> rendered = new java.util.HashSet<>();

        // Column width for command names
        int nameWidth = 16;

        for (java.util.Map.Entry<String, java.util.List<String>> group : HELP_GROUPS.entrySet()) {
            String groupHeader = group.getKey();
            java.util.List<String> groupCommands = group.getValue();

            // Check if any commands from this group are registered
            boolean hasAny = groupCommands.stream().anyMatch(subcommands::containsKey);
            if (!hasAny) continue;

            sb.append("  ").append(groupHeader).append(System.lineSeparator());

            for (String cmdName : groupCommands) {
                picocli.CommandLine sub = subcommands.get(cmdName);
                if (sub == null) continue;
                String desc = "";
                String[] descLines = sub.getCommandSpec().usageMessage().description();
                if (descLines != null && descLines.length > 0) {
                    desc = descLines[0];
                    // Truncate to 50 chars
                    if (desc.length() > 50) desc = desc.substring(0, 47) + "...";
                }
                sb.append(String.format("    %-" + nameWidth + "s %s%n", cmdName, desc));
                rendered.add(cmdName);
            }
            sb.append(System.lineSeparator());
        }

        // Catch any commands not in any group
        boolean hasOther = subcommands.keySet().stream().anyMatch(n -> !rendered.contains(n));
        if (hasOther) {
            sb.append("  Other:").append(System.lineSeparator());
            subcommands.entrySet().stream()
                .filter(e -> !rendered.contains(e.getKey()))
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> {
                    String desc = "";
                    String[] descLines = e.getValue().getCommandSpec().usageMessage().description();
                    if (descLines != null && descLines.length > 0) {
                        desc = descLines[0];
                        if (desc.length() > 50) desc = desc.substring(0, 47) + "...";
                    }
                    sb.append(String.format("    %-" + nameWidth + "s %s%n", e.getKey(), desc));
                });
        }

        return sb.toString();
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
            ApprovalConfig config = ApprovalConfig.load();

            // If approval system is not configured (empty tokens), skip silently
            if (!config.isConfigured()) {
                return;
            }

            // If we have a cached status and it's not time to refresh, use cached
            if (!approval.shouldRefresh() && approval.getCachedApproval() != null) {
                boolean isApproved = approval.getCachedApproval();
                if (isApproved && approval.shouldShowWelcome()) {
                    System.err.println("  \u2713 Pilot approved -- Thank you for participating!");
                }
                return;
            }

            // Perform approval check
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
