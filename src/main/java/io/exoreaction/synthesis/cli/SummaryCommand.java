package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.AiClient;
import io.exoreaction.synthesis.ai.AiProvider;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.changelog.ChangeEvent;
import io.exoreaction.synthesis.changelog.ChangeReportGenerator;
import io.exoreaction.synthesis.changelog.SnapshotManager;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.graph.SecurityPosture;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.summary.*;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Generates executive summaries of the codebase at different abstraction levels.
 *
 * <p>Phase 1 (rule-based): Instant metrics-driven summaries without AI.
 * Future phases will add AI-enhanced analysis, caching, and temporal awareness.
 *
 * <p>Usage:
 * <pre>
 *   synthesis summary                           # Executive 30-second overview
 *   synthesis summary --level manager           # 5-minute briefing
 *   synthesis summary --level developer         # Full technical detail
 *   synthesis summary --perspective architect   # Architecture-focused view
 *   synthesis summary --format markdown -o report.md
 * </pre>
 */
@Command(
        name = "summary",
        description = "Generate executive summaries at different abstraction levels",
        mixinStandardHelpOptions = true
)
public class SummaryCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--level", "-l"},
            description = "Detail level: executive (30s), manager (5min), developer (technical). Default: executive"
    )
    private String level = "executive";

    @Option(
            names = {"--perspective", "-p"},
            description = "Role perspective: general, executive, engineering_manager, architect, security, devops, product_manager, developer. Default: general"
    )
    private String perspective = "general";

    @Option(
            names = {"--no-ai"},
            description = "Skip AI-enhanced summary (faster, metrics-only)"
    )
    private boolean noAi = false;  // Phase 2: AI enabled by default

    @Option(
            names = {"--format", "-f"},
            description = "Output format: terminal (default), markdown, json"
    )
    private String format = "terminal";

    @Option(
            names = {"--output", "-o"},
            description = "Save output to file"
    )
    private String outputFile;

    @Option(
            names = {"--no-cache"},
            description = "Skip cache lookup and force fresh generation"
    )
    private boolean noCache = false;

    @Option(
            names = {"--since"},
            description = "Show changes since date/time (e.g., '2d ago', '1 week', 'yesterday', '2026-02-01')"
    )
    private String since;

    @Override
    public Integer call() {
        try {
            long startTime = System.currentTimeMillis();
            Path workspaceRoot = parent.getWorkspaceRoot();

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            // Parse parameters
            SummaryLevel summaryLevel = SummaryLevel.fromString(level);
            SummaryPerspective summaryPerspective = SummaryPerspective.fromString(perspective);

            // Phase 3: Check cache (if enabled)
            // Bypass cache when --since is provided: temporal context is dynamic
            // and must always reflect the current change window.
            // Also bypass cache for security perspective: findings change on each maintain run.
            String indexFingerprint = SummaryCache.generateIndexFingerprint(workspace.getIndexPath());
            SummaryResult result = null;
            boolean useCache = !noCache && (since == null || since.isBlank())
                    && summaryPerspective != SummaryPerspective.SECURITY;

            if (useCache) {
                try {
                    SynthesisDatabase db = SynthesisDatabase.getDefault();
                    Connection conn = db.getConnection();
                    SummaryCache cache = new SummaryCache(conn, 0);  // No TTL by default
                    Optional<SummaryResult> cached = cache.get(
                        workspaceRoot, summaryLevel, summaryPerspective, indexFingerprint);

                    if (cached.isPresent()) {
                        result = cached.get();
                        if ("terminal".equalsIgnoreCase(format) && outputFile == null) {
                            System.err.println("  " + AnsiOutput.dim("Loaded from cache (generated " +
                                formatDuration(System.currentTimeMillis() -
                                    result.generatedAt().toEpochMilli()) + " ago)"));
                        }
                    }
                } catch (Exception e) {
                    // Cache failures should not break functionality
                    System.err.println("Warning: Cache lookup failed: " + e.getMessage());
                }
            }

            // Generate if not cached
            if (result == null) {
                // Generate profile (Phase 1: rule-based metrics)
                CodebaseProfile profiler = new CodebaseProfile();
                CodebaseProfile.Profile profile;
                try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                    profile = profiler.generate(index, workspaceRoot);
                }

                // Phase 5: Temporal context (computed first so AI can use it)
                String temporalContext = null;
                if (since != null && !since.isBlank()) {
                    Instant sinceInstant = parseSince(since);
                    if (sinceInstant == null) {
                        AnsiOutput.printWarning("Could not parse --since value: '" + since +
                                "'. Use '7d', '24h', '2w', or '2026-01-15'.");
                    } else {
                        try {
                            SynthesisDatabase changeDb = SynthesisDatabase.getDefault();
                            SnapshotManager snapshots = new SnapshotManager(changeDb);
                            List<ChangeEvent> events = snapshots.getChangesForWorkspace(
                                    workspaceRoot.toString(), sinceInstant);
                            String changeSummary = new ChangeReportGenerator().generateSummary(events);
                            temporalContext = "Changes since " + since + ": " + changeSummary;
                        } catch (Exception e) {
                            temporalContext = "Changes since " + since +
                                    " (changelog not available — run 'synthesis maintain' first)";
                        }
                    }
                }

                // Security posture: query DB findings for security perspective or executive level
                SecurityPosture securityPosture = SecurityPosture.empty();
                if (summaryPerspective == SummaryPerspective.SECURITY
                        || summaryLevel == SummaryLevel.EXECUTIVE) {
                    try {
                        SynthesisDatabase secDb = SynthesisDatabase.getDefault();
                        Connection secConn = secDb.getConnection();
                        securityPosture = SecurityPosture.query(secConn, workspaceRoot.toString());
                    } catch (Exception e) {
                        // Graceful degradation: security data unavailable
                    }
                }

                // Inject security context into temporal context for AI prompt
                if (!securityPosture.noData()) {
                    String secLevel = switch (summaryLevel) {
                        case EXECUTIVE -> "executive";
                        case MANAGER -> "manager";
                        case DEVELOPER -> "developer";
                    };
                    String securityContext = "\n\n**Security Findings (from Synthesis CKG-5 scan):**\n"
                            + securityPosture.format(secLevel);
                    if (temporalContext != null) {
                        temporalContext = temporalContext + securityContext;
                    } else {
                        temporalContext = securityContext;
                    }
                }

                // Phase 2: AI-enhanced summary (if enabled)
                String aiSummary = null;
                String modelUsed = null;
                if (!noAi) {
                    SynthesisConfig config = ConfigLoader.load(workspaceRoot);
                    Optional<AiClient> clientOpt = AiClient.create(config.getAi());

                    if (clientOpt.isPresent()) {
                        SummaryEngine engine = new SummaryEngine(clientOpt.get());
                        modelUsed = engine.getModel();
                        if ("terminal".equalsIgnoreCase(format) && outputFile == null) {
                            System.err.println("  " + AnsiOutput.dim("Generating AI summary with " +
                                modelUsed + "..."));
                        }
                        aiSummary = engine.generateSummary(profile, summaryLevel, summaryPerspective,
                                temporalContext);
                    } else {
                        // AI not configured - show warning only for terminal output
                        if ("terminal".equalsIgnoreCase(format) && outputFile == null) {
                            AnsiOutput.printWarning("AI not configured. Showing metrics-only summary.");
                            AnsiOutput.printInfo("To enable AI: set ai.enabled=true and "
                                    + AiProvider.forConfig(config.getAi()).apiKeyName());
                            System.err.println();
                        }
                    }
                }

                long generationTime = System.currentTimeMillis() - startTime;

                // Create result
                if (temporalContext != null) {
                    result = SummaryResult.withTemporal(
                        profile, aiSummary, summaryLevel, summaryPerspective, temporalContext, generationTime);
                } else if (aiSummary != null) {
                    result = SummaryResult.withAiSummary(
                        profile, aiSummary, summaryLevel, summaryPerspective, generationTime);
                } else {
                    result = SummaryResult.fromProfile(
                        profile, summaryLevel, summaryPerspective, generationTime);
                }

                // Phase 3: Store in cache (skip for temporal results — they are always fresh)
                if (useCache) {
                    try {
                        SynthesisDatabase db = SynthesisDatabase.getDefault();
                        Connection conn = db.getConnection();
                        SummaryCache cache = new SummaryCache(conn, 0);
                        cache.put(workspaceRoot, summaryLevel, summaryPerspective,
                            indexFingerprint, result, modelUsed);
                    } catch (Exception e) {
                        // Cache storage failures should not break functionality
                        System.err.println("Warning: Cache storage failed: " + e.getMessage());
                    }
                }
            }

            // Render output
            SummaryRenderer renderer = new SummaryRenderer();
            String output = switch (format.toLowerCase()) {
                case "markdown", "md" -> renderer.renderMarkdown(result);
                case "json" -> renderer.renderJson(result);
                default -> renderer.renderTerminal(result);
            };

            // Output to console or file
            if (outputFile != null) {
                Files.writeString(Path.of(outputFile), output);
                AnsiOutput.printSuccess("Summary saved to " + outputFile);
            } else {
                System.out.println(output);
            }

            return 0;

        } catch (Exception e) {
            AnsiOutput.printError("Summary generation failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * Parses a --since value into an Instant.
     * Delegates to {@link ChangedCommand#parseSince} (ISO dates + duration formats).
     */
    Instant parseSince(String since) {
        return ChangedCommand.parseSince(since);
    }

    private String formatDuration(long millis) {
        if (millis < 60000) return (millis / 1000) + "s";
        if (millis < 3600000) return (millis / 60000) + "m";
        return (millis / 3600000) + "h";
    }
}
