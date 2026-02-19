package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.ClaudeClient;
import io.exoreaction.synthesis.ai.PromptTemplates;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Smart project analysis command.
 *
 * <p>Analyzes the workspace structure, detects patterns, identifies issues,
 * and provides actionable recommendations. Uses AI when available for
 * deeper analysis, falls back to rule-based analysis without AI.
 *
 * <p>Usage:
 * <pre>
 *   synthesis analyze                    # Full analysis with AI
 *   synthesis analyze --no-ai            # Rule-based analysis only
 *   synthesis analyze --output report.md # Save to file
 * </pre>
 */
@Command(
        name = "analyze",
        description = "Analyze project structure, patterns, and issues",
        mixinStandardHelpOptions = true
)
public class AnalyzeCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--no-ai"},
            description = "Skip AI analysis (rule-based only)",
            defaultValue = "false"
    )
    private boolean noAi;

    @Option(
            names = {"-o", "--output"},
            description = "Output file for analysis report"
    )
    private Path output;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show detailed analysis",
            defaultValue = "false"
    )
    private boolean verbose;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            SynthesisConfig config = ConfigLoader.load(workspaceRoot);

            AnsiOutput.printHeader("Synthesis - Project Analysis");
            AnsiOutput.printInfo("Workspace: " + config.getWorkspace().getName());
            System.out.println();

            // Get all indexed files
            List<SearchResult> allFiles;
            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                allFiles = index.listAll(null, 5000);
            }

            if (allFiles.isEmpty()) {
                AnsiOutput.printWarning("No files in index. Run 'synthesis scan' first.");
                return 1;
            }

            // Build statistics
            String statistics = buildStatistics(allFiles, workspaceRoot);
            List<AnalysisIssue> issues = detectIssues(allFiles, workspaceRoot);

            // Rule-based analysis output
            StringBuilder report = new StringBuilder();
            report.append("# Project Analysis: ").append(config.getWorkspace().getName()).append("\n\n");

            // Statistics section
            report.append("## Statistics\n\n");
            report.append(statistics).append("\n");

            // Issues section
            report.append("## Issues Detected\n\n");
            if (issues.isEmpty()) {
                report.append("No issues detected.\n\n");
            } else {
                Map<IssueSeverity, List<AnalysisIssue>> bySeverity = issues.stream()
                        .collect(Collectors.groupingBy(AnalysisIssue::severity));

                for (IssueSeverity severity : IssueSeverity.values()) {
                    List<AnalysisIssue> sevIssues = bySeverity.getOrDefault(severity, List.of());
                    if (!sevIssues.isEmpty()) {
                        report.append("### ").append(severity.label()).append(" (").append(sevIssues.size()).append(")\n\n");
                        for (AnalysisIssue issue : sevIssues) {
                            report.append("- **").append(issue.title()).append("**");
                            if (issue.path() != null) {
                                report.append(" - `").append(issue.path()).append("`");
                            }
                            report.append("\n  ").append(issue.description()).append("\n");
                        }
                        report.append("\n");
                    }
                }
            }

            // AI analysis if available
            if (!noAi) {
                Optional<ClaudeClient> clientOpt = ClaudeClient.create(config.getAi());
                if (clientOpt.isPresent()) {
                    AnsiOutput.printInfo("Running AI-powered deep analysis...");
                    String samples = buildFileSamples(allFiles, workspaceRoot);
                    String prompt = PromptTemplates.buildAnalyzePrompt(statistics, samples);

                    try {
                        String aiAnalysis = clientOpt.get().generate(prompt, 3000);
                        report.append("## AI Analysis\n\n");
                        report.append(aiAnalysis).append("\n\n");
                    } catch (Exception e) {
                        AnsiOutput.printWarning("AI analysis failed: " + e.getMessage());
                        report.append("## AI Analysis\n\n");
                        report.append("AI analysis unavailable: ").append(e.getMessage()).append("\n\n");
                    }
                } else if (verbose) {
                    AnsiOutput.printInfo("AI not configured. Showing rule-based analysis only.");
                }
            }

            String reportContent = report.toString();

            // Output
            if (output != null) {
                Files.writeString(output, reportContent);
                AnsiOutput.printSuccess("Analysis report saved to " + output);
            } else {
                System.out.println(reportContent);
            }

            // Summary
            long errorCount = issues.stream().filter(i -> i.severity() == IssueSeverity.ERROR).count();
            long warnCount = issues.stream().filter(i -> i.severity() == IssueSeverity.WARNING).count();
            long infoCount = issues.stream().filter(i -> i.severity() == IssueSeverity.INFO).count();

            System.out.println(AnsiOutput.bold("  Summary: ") +
                    (errorCount > 0 ? AnsiOutput.red(errorCount + " errors") + " " : "") +
                    (warnCount > 0 ? AnsiOutput.yellow(warnCount + " warnings") + " " : "") +
                    (infoCount > 0 ? AnsiOutput.blue(infoCount + " info") : ""));
            System.out.println();

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Analysis failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Builds a statistics summary from indexed files.
     */
    String buildStatistics(List<SearchResult> files, Path workspaceRoot) {
        StringBuilder sb = new StringBuilder();

        sb.append("- **Total files:** ").append(files.size()).append("\n");

        // Total size
        long totalBytes = files.stream().mapToLong(SearchResult::sizeBytes).sum();
        sb.append("- **Total size:** ").append(FileUtils.formatSize(totalBytes)).append("\n");

        // By type
        Map<String, Long> byType = files.stream()
                .filter(f -> f.fileType() != null)
                .collect(Collectors.groupingBy(SearchResult::fileType, Collectors.counting()));
        sb.append("- **File types:** ");
        sb.append(byType.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", ")));
        sb.append("\n");

        // By language
        Map<String, Long> byLang = files.stream()
                .filter(f -> f.language() != null)
                .collect(Collectors.groupingBy(SearchResult::language, Collectors.counting()));
        if (!byLang.isEmpty()) {
            sb.append("- **Languages:** ");
            sb.append(byLang.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", ")));
            sb.append("\n");
        }

        // Largest files
        List<SearchResult> largest = files.stream()
                .sorted(Comparator.comparingLong(SearchResult::sizeBytes).reversed())
                .limit(5)
                .toList();
        if (!largest.isEmpty()) {
            sb.append("- **Largest files:**\n");
            for (SearchResult f : largest) {
                sb.append("  - `").append(f.relativePath()).append("` (").append(FileUtils.formatSize(f.sizeBytes())).append(")\n");
            }
        }

        // Directory depth analysis
        int maxDepth = files.stream()
                .mapToInt(f -> f.relativePath().split("[/\\\\]").length)
                .max().orElse(0);
        sb.append("- **Max directory depth:** ").append(maxDepth).append("\n");

        return sb.toString();
    }

    /**
     * Detects common project issues using rule-based analysis.
     */
    List<AnalysisIssue> detectIssues(List<SearchResult> files, Path workspaceRoot) {
        List<AnalysisIssue> issues = new ArrayList<>();

        // Check for directories without README
        Set<String> directories = new TreeSet<>();
        Set<String> readmeDirs = new TreeSet<>();
        for (SearchResult f : files) {
            String relPath = f.relativePath();
            int lastSep = relPath.lastIndexOf('/');
            if (lastSep < 0) lastSep = relPath.lastIndexOf('\\');
            String dir = lastSep > 0 ? relPath.substring(0, lastSep) : ".";
            directories.add(dir);
            if (f.fileName().equalsIgnoreCase("README.md") || f.fileName().equalsIgnoreCase("README")) {
                readmeDirs.add(dir);
            }
        }

        Set<String> missingReadme = new TreeSet<>(directories);
        missingReadme.removeAll(readmeDirs);
        // Only report for significant directories (with 3+ files)
        Map<String, Long> filesPerDir = files.stream()
                .collect(Collectors.groupingBy(f -> {
                    String relPath = f.relativePath();
                    int lastSep = Math.max(relPath.lastIndexOf('/'), relPath.lastIndexOf('\\'));
                    return lastSep > 0 ? relPath.substring(0, lastSep) : ".";
                }, Collectors.counting()));

        for (String dir : missingReadme) {
            long fileCount = filesPerDir.getOrDefault(dir, 0L);
            if (fileCount >= 3) {
                issues.add(new AnalysisIssue(
                        IssueSeverity.WARNING,
                        "Missing README",
                        "Directory has " + fileCount + " files but no README.md",
                        dir
                ));
            }
        }

        // Check for test coverage gaps
        Set<String> codeDirs = new TreeSet<>();
        Set<String> testDirs = new TreeSet<>();
        for (SearchResult f : files) {
            if ("CODE".equals(f.fileType())) {
                String relPath = f.relativePath();
                int lastSep = Math.max(relPath.lastIndexOf('/'), relPath.lastIndexOf('\\'));
                String dir = lastSep > 0 ? relPath.substring(0, lastSep) : ".";
                if (relPath.toLowerCase().contains("test") || relPath.toLowerCase().contains("spec")) {
                    testDirs.add(dir);
                } else {
                    codeDirs.add(dir);
                }
            }
        }

        // Check for large files
        for (SearchResult f : files) {
            if (f.sizeBytes() > 100_000) {
                issues.add(new AnalysisIssue(
                        IssueSeverity.INFO,
                        "Large file",
                        "File is " + FileUtils.formatSize(f.sizeBytes()) + " - consider splitting",
                        f.relativePath()
                ));
            }
        }

        // Check for deeply nested files
        for (SearchResult f : files) {
            int depth = f.relativePath().split("[/\\\\]").length;
            if (depth > 8) {
                issues.add(new AnalysisIssue(
                        IssueSeverity.INFO,
                        "Deep nesting",
                        "File is " + depth + " levels deep - consider flattening",
                        f.relativePath()
                ));
            }
        }

        return issues;
    }

    /**
     * Builds file samples for AI analysis (reads a subset of files).
     */
    private String buildFileSamples(List<SearchResult> allFiles, Path workspaceRoot) {
        StringBuilder sb = new StringBuilder();
        int maxFiles = 15;
        int maxBytesPerFile = 2048;

        // Select a representative sample: some code, some docs, some config
        List<SearchResult> samples = new ArrayList<>();
        Map<String, List<SearchResult>> byType = allFiles.stream()
                .filter(f -> f.fileType() != null)
                .collect(Collectors.groupingBy(SearchResult::fileType));

        for (var entry : byType.entrySet()) {
            List<SearchResult> typeFiles = entry.getValue();
            int take = Math.min(3, typeFiles.size());
            samples.addAll(typeFiles.subList(0, take));
            if (samples.size() >= maxFiles) break;
        }

        for (SearchResult r : samples) {
            sb.append("\n--- ").append(r.relativePath());
            if (r.fileType() != null) sb.append(" [").append(r.fileType()).append("]");
            sb.append(" ---\n");
            if (!r.summary().isEmpty()) {
                sb.append("Summary: ").append(r.summary()).append("\n");
            }
            try {
                if (Files.exists(r.path()) && Files.isReadable(r.path())) {
                    String content = FileUtils.readPreview(r.path(), maxBytesPerFile);
                    if (!content.isEmpty()) {
                        sb.append(content).append("\n");
                    }
                }
            } catch (IOException e) {
                sb.append("(error reading file)\n");
            }
        }

        return sb.toString();
    }

    // --- Data types ---

    enum IssueSeverity {
        ERROR("Errors"),
        WARNING("Warnings"),
        INFO("Info");

        private final String label;

        IssueSeverity(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record AnalysisIssue(
            IssueSeverity severity,
            String title,
            String description,
            String path
    ) {
    }
}
