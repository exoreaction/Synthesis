package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.analyzer.AnalyzerRegistry;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.*;
import io.exoreaction.synthesis.index.FileIndexer;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.ai.ClaudeClient;
import io.exoreaction.synthesis.ai.ReadmeGenerator;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import io.exoreaction.synthesis.util.ProgressReporter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scans the workspace, analyzes files, and builds the search index.
 *
 * <p>The scan pipeline:
 * <ol>
 *   <li>Walk directory tree, apply include/exclude filters</li>
 *   <li>Extract file metadata (size, type, hash)</li>
 *   <li>Analyze each file with the appropriate analyzer</li>
 *   <li>Index metadata + analysis results in Lucene</li>
 *   <li>Print summary statistics</li>
 * </ol>
 *
 * <p>Usage: {@code synthesis scan [--full] [--verbose]}
 */
@Command(
        name = "scan",
        description = "Scan workspace and build search index",
        mixinStandardHelpOptions = true
)
public class ScanCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--full"},
            description = "Full rebuild (delete existing index first)",
            defaultValue = "false"
    )
    private boolean fullRebuild;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show detailed output during scan",
            defaultValue = "false"
    )
    private boolean verbose;

    @Option(
            names = {"--with-readme"},
            description = "Generate README.md for directories missing one (requires AI)",
            defaultValue = "false"
    )
    private boolean withReadme;

    @Option(
            names = {"--force-readme"},
            description = "Overwrite existing README.md files (use with --with-readme)",
            defaultValue = "false"
    )
    private boolean forceReadme;

    @Override
    public Integer call() {
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();

            AnsiOutput.printHeader("Synthesis - Scan Workspace");

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            // Load configuration
            SynthesisConfig config = ConfigLoader.load(workspaceRoot);
            AnsiOutput.printInfo("Workspace: " + config.getWorkspace().getName());
            AnsiOutput.printInfo("Root: " + workspaceRoot);
            System.out.println();

            // Phase 1: Scan directory
            AnsiOutput.printInfo("Phase 1: Scanning directory tree...");
            DirectoryScanner scanner = new DirectoryScanner(workspaceRoot, config.getScan(), verbose);
            ScanResult scanResult = scanner.scan();

            System.out.println();
            AnsiOutput.printInfo("Phase 2: Analyzing files and building index...");

            // Phase 2: Analyze and index
            AnalyzerRegistry analyzers = new AnalyzerRegistry();
            FileIndexer fileIndexer = new FileIndexer();

            try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
                if (fullRebuild) {
                    index.deleteAll();
                    AnsiOutput.printInfo("Index cleared for full rebuild");
                }

                ProgressReporter progress = new ProgressReporter("Indexing", scanResult.fileCount());

                int indexed = 0;
                int errors = 0;

                for (FileMetadata metadata : scanResult.files()) {
                    try {
                        AnalysisResult analysis = analyzers.analyze(metadata);
                        var doc = fileIndexer.createDocument(metadata, analysis);
                        index.addDocument(doc);
                        indexed++;
                    } catch (Exception e) {
                        errors++;
                        if (verbose) {
                            System.err.println("  Warning: Failed to index " + metadata.relativePath() + ": " + e.getMessage());
                        }
                    }
                    progress.tick();
                }

                index.commit();
                progress.complete();

                // Print summary
                printSummary(scanResult, indexed, errors, index.documentCount());
            }

            // Phase 3: AI-powered README generation (optional)
            if (withReadme) {
                generateReadmes(config, workspaceRoot, scanResult);
            }

            // Save scan state for incremental maintenance
            ScanState state = ScanState.fromScanResult(scanResult);
            state.save(workspace.getScanStatePath());

            return 0;
        } catch (Exception e) {
            AnsiOutput.printError("Scan failed: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    private void printSummary(ScanResult result, int indexed, int errors, int totalInIndex) {
        System.out.println();
        AnsiOutput.printHeader("Scan Summary");

        System.out.printf("  %-20s %s%n", "Files discovered:", AnsiOutput.bold(String.valueOf(result.fileCount())));
        System.out.printf("  %-20s %s%n", "Files indexed:", AnsiOutput.bold(String.valueOf(indexed)));
        if (errors > 0) {
            System.out.printf("  %-20s %s%n", "Errors:", AnsiOutput.error(String.valueOf(errors)));
        }
        System.out.printf("  %-20s %s%n", "Total in index:", AnsiOutput.bold(String.valueOf(totalInIndex)));
        System.out.printf("  %-20s %s%n", "Total size:",
                AnsiOutput.bold(FileUtils.formatSize(result.totalSizeBytes())));
        System.out.printf("  %-20s %s%n", "Scan duration:",
                AnsiOutput.bold(formatDuration(result.duration().toMillis())));

        // File type breakdown
        System.out.println();
        System.out.println("  " + AnsiOutput.bold("File types:"));
        Map<FileUtils.FileType, Long> byType = result.countByType();
        byType.entrySet().stream()
                .sorted(Map.Entry.<FileUtils.FileType, Long>comparingByValue().reversed())
                .forEach(entry -> System.out.printf("    %-15s %d files%n",
                        entry.getKey(), entry.getValue()));

        // Language breakdown (if any code files)
        Map<String, Long> byLanguage = result.countByLanguage();
        if (!byLanguage.isEmpty()) {
            System.out.println();
            System.out.println("  " + AnsiOutput.bold("Languages:"));
            byLanguage.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(entry -> System.out.printf("    %-15s %d files%n",
                            entry.getKey(), entry.getValue()));
        }

        System.out.println();
        System.out.println("  Run " + AnsiOutput.cyan("synthesis search <query>") + " to search your workspace.");
        System.out.println();
    }

    private void generateReadmes(SynthesisConfig config, Path workspaceRoot, ScanResult scanResult) {
        Optional<ClaudeClient> clientOpt = ClaudeClient.create(config.getAi());
        if (clientOpt.isEmpty()) {
            AnsiOutput.printWarning("AI not configured. Set ai.enabled=true and ANTHROPIC_API_KEY to use --with-readme.");
            return;
        }

        System.out.println();
        AnsiOutput.printInfo("Phase 3: Generating READMEs for directories...");

        ReadmeGenerator generator = new ReadmeGenerator(clientOpt.get(), config.getAi().getMaxTokens());

        // Find directories that have scanned files but no README
        Set<Path> directoriesWithFiles = scanResult.files().stream()
                .map(fm -> fm.path().getParent())
                .collect(Collectors.toCollection(TreeSet::new));

        int generated = 0;
        int skipped = 0;

        for (Path dir : directoriesWithFiles) {
            try {
                boolean created = generator.generate(dir, workspaceRoot, forceReadme);
                if (created) {
                    generated++;
                } else {
                    skipped++;
                }
            } catch (IOException e) {
                if (verbose) {
                    AnsiOutput.printWarning("Failed for " + workspaceRoot.relativize(dir) + ": " + e.getMessage());
                }
            }
        }

        if (generated > 0) {
            AnsiOutput.printSuccess("Generated " + generated + " README files.");
        } else {
            AnsiOutput.printInfo("No new READMEs needed (" + skipped + " directories already have one).");
        }
    }

    private String formatDuration(long millis) {
        if (millis < 1000) return millis + "ms";
        if (millis < 60000) return String.format("%.1fs", millis / 1000.0);
        return String.format("%dm %ds", millis / 60000, (millis % 60000) / 1000);
    }
}
