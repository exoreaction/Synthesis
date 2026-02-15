package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.architecture.ArchitectureAlert;
import io.exoreaction.synthesis.architecture.ArchitectureMonitor;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.util.AnsiOutput;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Continuous architecture intelligence: anti-pattern detection, coupling analysis,
 * and architecture health monitoring.
 *
 * <p>Usage:
 * <pre>
 *   synthesis architecture analyze          # Run full analysis
 *   synthesis architecture analyze --severity warning   # Only warnings and errors
 *   synthesis architecture analyze --category GOD_CLASS # Filter by category
 *   synthesis architecture analyze --format json        # JSON output
 * </pre>
 *
 * @see ArchitectureMonitor
 */
@Command(
        name = "architecture",
        description = "Architecture intelligence: detect anti-patterns, coupling issues, and quality gaps",
        mixinStandardHelpOptions = true
)
public class ArchitectureCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--severity"},
            description = "Minimum severity to report: error, warning, info (default: info)",
            defaultValue = "info"
    )
    private String minSeverity;

    @Option(
            names = {"--category"},
            description = "Filter by category (e.g., GOD_CLASS, CIRCULAR_DEPENDENCY, DEAD_CODE)"
    )
    private String categoryFilter;

    @Option(
            names = {"--format"},
            description = "Output format: text, json (default: text)",
            defaultValue = "text"
    )
    private String format;

    @Option(
            names = {"--limit"},
            description = "Maximum number of alerts to show (default: 50)",
            defaultValue = "50"
    )
    private int limit;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            AnsiOutput.printHeader("Synthesis - Architecture Analysis");
            System.out.println();

            ArchitectureMonitor monitor = new ArchitectureMonitor(workspaceRoot);

            long startTime = System.currentTimeMillis();
            List<ArchitectureAlert> alerts;
            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                alerts = monitor.analyze(index);
            }
            long duration = System.currentTimeMillis() - startTime;

            // Apply severity filter
            ArchitectureAlert.Severity minSev = switch (minSeverity.toLowerCase()) {
                case "error" -> ArchitectureAlert.Severity.ERROR;
                case "warning", "warn" -> ArchitectureAlert.Severity.WARNING;
                default -> ArchitectureAlert.Severity.INFO;
            };

            alerts = alerts.stream()
                    .filter(a -> a.severity().ordinal() <= minSev.ordinal())
                    .toList();

            // Apply category filter
            if (categoryFilter != null && !categoryFilter.isBlank()) {
                String upper = categoryFilter.toUpperCase();
                alerts = alerts.stream()
                        .filter(a -> a.category().name().equals(upper))
                        .toList();
            }

            // Apply limit
            if (alerts.size() > limit) {
                alerts = alerts.subList(0, limit);
            }

            if ("json".equals(format)) {
                return outputJson(alerts, duration);
            } else {
                return outputText(alerts, duration);
            }

        } catch (Exception e) {
            AnsiOutput.printError("Architecture analysis failed: " + e.getMessage());
            return 1;
        }
    }

    private int outputText(List<ArchitectureAlert> alerts, long duration) {
        if (alerts.isEmpty()) {
            AnsiOutput.printSuccess("No architecture issues found!");
            return 0;
        }

        // Group by severity
        Map<ArchitectureAlert.Severity, List<ArchitectureAlert>> bySeverity = alerts.stream()
                .collect(Collectors.groupingBy(ArchitectureAlert::severity));

        int errors = bySeverity.getOrDefault(ArchitectureAlert.Severity.ERROR, List.of()).size();
        int warnings = bySeverity.getOrDefault(ArchitectureAlert.Severity.WARNING, List.of()).size();
        int infos = bySeverity.getOrDefault(ArchitectureAlert.Severity.INFO, List.of()).size();

        // Summary
        System.out.printf("  Found %d alerts: %s errors, %s warnings, %s info (%.1fs)%n%n",
                alerts.size(),
                AnsiOutput.red(String.valueOf(errors)),
                AnsiOutput.yellow(String.valueOf(warnings)),
                AnsiOutput.blue(String.valueOf(infos)),
                duration / 1000.0);

        // Group by category for organized output
        Map<ArchitectureAlert.Category, List<ArchitectureAlert>> byCategory = alerts.stream()
                .collect(Collectors.groupingBy(ArchitectureAlert::category));

        for (var entry : byCategory.entrySet()) {
            System.out.println("  " + AnsiOutput.bold(entry.getKey().name()) +
                    " (" + entry.getValue().size() + ")");
            for (ArchitectureAlert alert : entry.getValue()) {
                String prefix = switch (alert.severity()) {
                    case ERROR -> AnsiOutput.red("  ERROR ");
                    case WARNING -> AnsiOutput.yellow("  WARN  ");
                    case INFO -> AnsiOutput.blue("  INFO  ");
                };
                System.out.println(prefix + alert.filePath());
                System.out.println("          " + alert.message());
            }
            System.out.println();
        }

        return errors > 0 ? 2 : (warnings > 0 ? 1 : 0);
    }

    private int outputJson(List<ArchitectureAlert> alerts, long duration) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"totalAlerts\": ").append(alerts.size()).append(",\n");
        sb.append("  \"durationMs\": ").append(duration).append(",\n");
        sb.append("  \"alerts\": [\n");

        for (int i = 0; i < alerts.size(); i++) {
            ArchitectureAlert alert = alerts.get(i);
            sb.append("    {\n");
            sb.append("      \"severity\": \"").append(alert.severity()).append("\",\n");
            sb.append("      \"category\": \"").append(alert.category()).append("\",\n");
            sb.append("      \"filePath\": \"").append(escapeJson(alert.filePath())).append("\",\n");
            sb.append("      \"message\": \"").append(escapeJson(alert.message())).append("\"\n");
            sb.append("    }").append(i < alerts.size() - 1 ? "," : "").append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");

        System.out.print(sb);
        return 0;
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
