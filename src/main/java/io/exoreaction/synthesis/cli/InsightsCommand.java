package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.insights.InsightsEngine;
import io.exoreaction.synthesis.insights.InsightsEngine.*;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Provides deep analysis of codebase structure with actionable metrics.
 *
 * <p>Calculates connectivity, complexity, quality, and architectural metrics
 * from the indexed files, producing a comprehensive insights report.
 *
 * <p>Usage:
 * <pre>
 *   synthesis insights                    # Full report
 *   synthesis insights --repo project-a   # Scoped to one repo
 *   synthesis insights --output report.md # Save to file
 * </pre>
 */
@Command(
        name = "insights",
        description = "Deep analysis of codebase structure with actionable metrics",
        mixinStandardHelpOptions = true
)
public class InsightsCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--repo"},
            description = "Scope analysis to a specific repository"
    )
    private String repo;

    @Option(
            names = {"--company"},
            description = "Scope analysis to a specific organization/company"
    )
    private String company;

    @Option(
            names = {"--output", "-o"},
            description = "Save report to file (markdown format)"
    )
    private String outputFile;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            AnsiOutput.printHeader("Synthesis - Knowledge Graph Insights");

            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            // Load all files
            List<SearchResult> allFiles;
            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                if (company != null && !company.isBlank()) {
                    allFiles = index.listAll(null, repo, company, null, 50000);
                    if (allFiles.isEmpty()) {
                        AnsiOutput.printWarning("No files found for organization: " + company);
                        return 0;
                    }
                } else if (repo != null && !repo.isBlank()) {
                    allFiles = index.listAll(null, repo, 50000);
                    if (allFiles.isEmpty()) {
                        AnsiOutput.printWarning("No files found for repository: " + repo);
                        return 0;
                    }
                } else {
                    allFiles = index.listAll(null, 50000);
                }
            }

            if (allFiles.isEmpty()) {
                AnsiOutput.printWarning("No files in index. Run 'synthesis scan' first.");
                return 0;
            }

            // Run analysis
            InsightsEngine engine = new InsightsEngine();
            InsightsReport report = engine.analyze(allFiles, workspaceRoot);

            // Output
            StringBuilder output = new StringBuilder();
            formatReport(output, report, allFiles.size());

            System.out.println(output);

            // Save to file if requested
            if (outputFile != null) {
                StringBuilder mdOutput = new StringBuilder();
                formatMarkdownReport(mdOutput, report, allFiles.size());
                Files.writeString(Path.of(outputFile), mdOutput.toString());
                AnsiOutput.printSuccess("Report saved to " + outputFile);
            }

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Insights analysis failed: " + e.getMessage());
            return 1;
        }
    }

    private void formatReport(StringBuilder sb, InsightsReport report, int totalFiles) {
        String separator = "=".repeat(60);

        sb.append("\n  ").append(AnsiOutput.bold("Knowledge Graph Insights")).append("\n");
        sb.append("  ").append(separator).append("\n\n");

        // Connectivity
        sb.append("  ").append(AnsiOutput.bold(AnsiOutput.cyan("Connectivity:"))).append("\n");

        // Top referenced files
        List<Map.Entry<String, Integer>> topIncoming = report.connectivity().incomingRefs().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .toList();
        if (!topIncoming.isEmpty()) {
            sb.append("    Most referenced: ");
            sb.append(topIncoming.get(0).getKey())
                    .append(" (").append(topIncoming.get(0).getValue()).append(" incoming refs)\n");
            for (int i = 1; i < topIncoming.size(); i++) {
                sb.append("                     ").append(topIncoming.get(i).getKey())
                        .append(" (").append(topIncoming.get(i).getValue()).append(")\n");
            }
        }

        // Hub files
        List<Map.Entry<String, Integer>> topOutgoing = report.connectivity().outgoingRefs().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .filter(e -> e.getValue() > 0)
                .limit(5)
                .toList();
        if (!topOutgoing.isEmpty()) {
            sb.append("    Hub files:       ");
            sb.append(topOutgoing.get(0).getKey())
                    .append(" (").append(topOutgoing.get(0).getValue()).append(" outgoing refs)\n");
            for (int i = 1; i < topOutgoing.size(); i++) {
                sb.append("                     ").append(topOutgoing.get(i).getKey())
                        .append(" (").append(topOutgoing.get(i).getValue()).append(")\n");
            }
        }

        sb.append("    Orphaned files:  ").append(report.connectivity().orphanedFiles().size())
                .append(" files (")
                .append(String.format("%.1f", totalFiles == 0 ? 0 :
                        (double) report.connectivity().orphanedFiles().size() / totalFiles * 100))
                .append("% of codebase)\n");
        sb.append("    Circular deps:   ").append(report.connectivity().circularClusters().size())
                .append(" clusters detected\n");
        sb.append("    Avg refs/file:   ").append(String.format("%.1f", report.connectivity().averageRefsPerFile()))
                .append("\n");
        sb.append("    Total refs:      ").append(report.connectivity().totalReferences()).append("\n");
        sb.append("\n");

        // Complexity
        sb.append("  ").append(AnsiOutput.bold(AnsiOutput.cyan("Complexity:"))).append("\n");
        sb.append("    Average file size: ").append(FileUtils.formatSize((long) report.complexity().averageFileSize()))
                .append("\n");
        sb.append("    Size distribution: ");
        report.complexity().fileSizeDistribution().forEach((bucket, count) -> {
            if (count > 0) {
                sb.append(bucket).append("=").append(count).append(" ");
            }
        });
        sb.append("\n");

        if (!report.complexity().largestFiles().isEmpty()) {
            sb.append("    Largest files:\n");
            for (int i = 0; i < Math.min(5, report.complexity().largestFiles().size()); i++) {
                SearchResult f = report.complexity().largestFiles().get(i);
                sb.append("      ").append(f.relativePath())
                        .append(" (").append(FileUtils.formatSize(f.sizeBytes())).append(")\n");
            }
        }

        sb.append("    Deepest nesting: ").append(report.complexity().maxNestingDepth()).append(" levels\n");

        // Top bloated directories
        List<Map.Entry<String, Integer>> bloatedDirs = report.complexity().filesPerDirectory().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .toList();
        if (!bloatedDirs.isEmpty()) {
            sb.append("    Largest directories:\n");
            for (var entry : bloatedDirs) {
                sb.append("      ").append(entry.getKey()).append(" (").append(entry.getValue()).append(" files)\n");
            }
        }

        sb.append("    File types: ");
        report.complexity().typeRatio().entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append(e.getKey()).append("=").append(e.getValue()).append(" "));
        sb.append("\n\n");

        // Quality
        sb.append("  ").append(AnsiOutput.bold(AnsiOutput.cyan("Quality:"))).append("\n");
        sb.append("    Documentation:   ").append(String.format("%.0f", report.quality().documentationCoverage()))
                .append("% (").append(report.quality().directoriesWithReadme())
                .append("/").append(report.quality().totalDirectories()).append(" directories with README)\n");
        sb.append("    Test ratio:      ").append(String.format("%.1f", report.quality().testRatio()))
                .append(":1 (").append(report.quality().testFiles())
                .append(" test / ").append(report.quality().sourceFiles()).append(" source)\n");
        sb.append("    Dead code:       ").append(report.quality().deadCodeCandidates().size())
                .append(" candidates\n");
        if (!report.quality().hotspotFiles().isEmpty()) {
            sb.append("    Hotspots:\n");
            for (String hotspot : report.quality().hotspotFiles()) {
                sb.append("      ").append(hotspot).append("\n");
            }
        }
        sb.append("\n");

        // Architecture
        sb.append("  ").append(AnsiOutput.bold(AnsiOutput.cyan("Architecture:"))).append("\n");
        sb.append("    Modules: ").append(report.architecture().moduleCount()).append("\n");

        if (!report.architecture().layeringViolations().isEmpty()) {
            sb.append("    Layering violations:\n");
            for (String violation : report.architecture().layeringViolations()) {
                sb.append("      ").append(violation).append("\n");
            }
        }

        // Show top coupled modules
        List<Map.Entry<String, Integer>> topCoupled = report.architecture().directoryCoupling().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .filter(e -> e.getValue() > 0)
                .limit(5)
                .toList();
        if (!topCoupled.isEmpty()) {
            sb.append("    Most coupled modules:\n");
            for (var entry : topCoupled) {
                sb.append("      ").append(entry.getKey())
                        .append(" (references ").append(entry.getValue()).append(" other modules)\n");
            }
        }
        sb.append("\n");

        // Warnings
        if (!report.warnings().isEmpty()) {
            sb.append("  ").append(AnsiOutput.bold(AnsiOutput.yellow("Warnings:"))).append("\n");
            for (String warning : report.warnings()) {
                sb.append("    ").append(AnsiOutput.yellow("* ")).append(warning).append("\n");
            }
            sb.append("\n");
        }

        // Recommendations
        if (!report.recommendations().isEmpty()) {
            sb.append("  ").append(AnsiOutput.bold(AnsiOutput.green("Recommendations:"))).append("\n");
            for (String rec : report.recommendations()) {
                sb.append("    ").append(AnsiOutput.green("* ")).append(rec).append("\n");
            }
            sb.append("\n");
        }
    }

    private void formatMarkdownReport(StringBuilder sb, InsightsReport report, int totalFiles) {
        sb.append("# Knowledge Graph Insights Report\n\n");
        sb.append("Generated by Synthesis | Files analyzed: ").append(totalFiles).append("\n\n");

        sb.append("## Connectivity\n\n");
        sb.append("| Metric | Value |\n|--------|-------|\n");
        sb.append("| Total references | ").append(report.connectivity().totalReferences()).append(" |\n");
        sb.append("| Average refs/file | ").append(String.format("%.1f", report.connectivity().averageRefsPerFile())).append(" |\n");
        sb.append("| Orphaned files | ").append(report.connectivity().orphanedFiles().size()).append(" |\n");
        sb.append("| Circular deps | ").append(report.connectivity().circularClusters().size()).append(" |\n\n");

        sb.append("## Complexity\n\n");
        sb.append("| Metric | Value |\n|--------|-------|\n");
        sb.append("| Average file size | ").append(FileUtils.formatSize((long) report.complexity().averageFileSize())).append(" |\n");
        sb.append("| Max nesting depth | ").append(report.complexity().maxNestingDepth()).append(" |\n\n");

        sb.append("## Quality\n\n");
        sb.append("| Metric | Value |\n|--------|-------|\n");
        sb.append("| Documentation coverage | ").append(String.format("%.0f%%", report.quality().documentationCoverage())).append(" |\n");
        sb.append("| Test ratio | ").append(String.format("%.1f:1", report.quality().testRatio())).append(" |\n");
        sb.append("| Dead code candidates | ").append(report.quality().deadCodeCandidates().size()).append(" |\n\n");

        if (!report.warnings().isEmpty()) {
            sb.append("## Warnings\n\n");
            for (String w : report.warnings()) {
                sb.append("- ").append(w).append("\n");
            }
            sb.append("\n");
        }

        if (!report.recommendations().isEmpty()) {
            sb.append("## Recommendations\n\n");
            for (String r : report.recommendations()) {
                sb.append("- ").append(r).append("\n");
            }
            sb.append("\n");
        }
    }
}
