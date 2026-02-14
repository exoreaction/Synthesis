package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.analyzer.AnalyzerRegistry;
import io.exoreaction.synthesis.analyzer.VideoAnalyzer;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.*;
import io.exoreaction.synthesis.index.FileIndexer;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.ai.ClaudeClient;
import io.exoreaction.synthesis.ai.PromptTemplates;
import io.exoreaction.synthesis.ai.ReadmeGenerator;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FfprobeDetector;
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

    @Option(
            names = {"--no-vision"},
            description = "Disable AI vision analysis for images (vision is enabled by default when AI is configured)",
            defaultValue = "false"
    )
    private boolean noVision;

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

            // Show video file detection and ffprobe status
            long videoCount = scanResult.files().stream()
                    .filter(fm -> fm.fileType() == FileUtils.FileType.VIDEO
                            || fm.fileType() == FileUtils.FileType.AUDIO)
                    .count();

            if (videoCount > 0) {
                printVideoGuidance(videoCount, scanResult);
            }

            System.out.println();
            AnsiOutput.printInfo("Phase 2: Analyzing files and building index...");

            // Phase 2: Analyze and index
            AnalyzerRegistry analyzers = new AnalyzerRegistry();
            FileIndexer fileIndexer = new FileIndexer();

            // Track video metadata extraction methods for summary
            int videosWithFullMeta = 0;
            int videosWithBasicMeta = 0;
            int videosNeedingFfprobe = 0;

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

                        // Track video extraction methods for summary
                        if (metadata.fileType() == FileUtils.FileType.VIDEO
                                || metadata.fileType() == FileUtils.FileType.AUDIO) {
                            Object method = analysis.metrics().get("extractionMethod");
                            if ("metadata_extractor".equals(method) || "ffprobe".equals(method)) {
                                videosWithFullMeta++;
                            } else {
                                videosWithBasicMeta++;
                                if (analysis.keywords().contains("ffprobe-needed")) {
                                    videosNeedingFfprobe++;
                                }
                            }

                            // Verbose per-file output
                            if (verbose) {
                                printVerboseVideoLine(metadata, analysis);
                            }
                        }

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

                // Print video metadata coverage if applicable
                if (videoCount > 0 && (videosWithBasicMeta > 0 || videosNeedingFfprobe > 0)) {
                    printVideoSummary(videoCount, videosWithFullMeta,
                            videosWithBasicMeta, videosNeedingFfprobe);
                }
            }

            // Phase 3: AI vision analysis for images (default enabled, --no-vision to disable)
            if (!noVision && config.getAi().isEnabled() && config.getAi().getVision().isEnabled()) {
                analyzeImagesWithVision(config, scanResult);
            }

            // Phase 4: AI-powered README generation (optional)
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

    /**
     * Analyzes images using Claude's vision capabilities.
     * Shows cost estimate and asks for confirmation before proceeding.
     */
    private void analyzeImagesWithVision(SynthesisConfig config, ScanResult scanResult) {
        // Find vision-compatible images
        List<FileMetadata> images = scanResult.files().stream()
                .filter(fm -> fm.fileType() == FileUtils.FileType.IMAGE)
                .filter(fm -> ClaudeClient.isVisionSupported(fm.extension()))
                .filter(fm -> fm.sizeBytes() <= config.getAi().getVision().getMaxImageSizeBytes())
                .toList();

        if (images.isEmpty()) {
            return;
        }

        // Calculate cost estimate
        double totalCost = images.stream()
                .mapToDouble(fm -> ClaudeClient.estimateVisionCost(fm.sizeBytes()))
                .sum();

        System.out.println();
        AnsiOutput.printInfo(String.format(
                "Phase 3: Vision analysis -- Found %d images. Estimated cost: ~$%.2f",
                images.size(), totalCost));

        // Show confirmation if configured
        if (config.getAi().getVision().isConfirmBeforeScan()) {
            System.out.print("  Continue with vision analysis? [Y/n] ");
            try {
                // Read from stdin -- for non-interactive contexts, default to yes
                if (System.console() != null) {
                    String response = System.console().readLine();
                    if (response != null && (response.trim().equalsIgnoreCase("n")
                            || response.trim().equalsIgnoreCase("no"))) {
                        AnsiOutput.printInfo("Vision analysis skipped.");
                        return;
                    }
                }
            } catch (Exception e) {
                // Non-interactive mode -- skip
                AnsiOutput.printInfo("Vision analysis skipped (non-interactive mode).");
                return;
            }
        }

        // Create AI client
        Optional<ClaudeClient> clientOpt = ClaudeClient.create(config.getAi());
        if (clientOpt.isEmpty()) {
            AnsiOutput.printWarning("AI not configured. Set ANTHROPIC_API_KEY to enable vision.");
            return;
        }

        ClaudeClient client = clientOpt.get();
        int analyzed = 0;
        int errors = 0;

        ProgressReporter progress = new ProgressReporter("Vision analysis", images.size());
        for (FileMetadata image : images) {
            try {
                String description = client.generateFromImage(
                        image.path(), PromptTemplates.IMAGE_DESCRIPTION,
                        config.getAi().getMaxTokens());

                if (!description.isEmpty() && verbose) {
                    System.out.println("  " + image.relativePath() + ": " +
                            description.substring(0, Math.min(80, description.length())) + "...");
                }
                analyzed++;
            } catch (Exception e) {
                errors++;
                if (verbose) {
                    System.err.println("  Warning: Vision failed for " + image.relativePath() + ": " + e.getMessage());
                }
            }
            progress.tick();
        }
        progress.complete();

        if (analyzed > 0) {
            AnsiOutput.printSuccess("Vision analysis complete: " + analyzed + " images described.");
        }
        if (errors > 0) {
            AnsiOutput.printWarning(errors + " images could not be analyzed.");
        }
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

    /**
     * Shows video file detection info and ffprobe availability guidance.
     * Displayed once after Phase 1 scan, before indexing begins.
     */
    private void printVideoGuidance(long videoCount, ScanResult scanResult) {
        System.out.println();
        System.out.println("  " + AnsiOutput.cyan("Found " + videoCount + " video/audio file"
                + (videoCount != 1 ? "s" : "")));

        if (FfprobeDetector.isAvailable()) {
            String source = FfprobeDetector.isUsingBundled() ? "bundled ffprobe" : "ffprobe detected";
            System.out.println("  " + AnsiOutput.success(source)
                    + " - full video format support");
        } else {
            System.out.println("  " + AnsiOutput.info("ffprobe not detected")
                    + " - using pure Java metadata extraction");
            System.out.println("    Supports: MP4, MOV, AVI, M4V, 3GP (covers ~90% of videos)");

            // Count how many files specifically need ffprobe
            long ffprobeNeeded = scanResult.files().stream()
                    .filter(fm -> fm.fileType() == FileUtils.FileType.VIDEO
                            || fm.fileType() == FileUtils.FileType.AUDIO)
                    .filter(fm -> FfprobeDetector.isFfprobeOnlyFormat(fm.extension()))
                    .count();

            if (ffprobeNeeded > 0) {
                System.out.println("    " + AnsiOutput.warning(ffprobeNeeded + " file"
                        + (ffprobeNeeded != 1 ? "s" : "")
                        + " (MKV/WebM/FLV) need ffprobe for full metadata"));
                System.out.println();
                System.out.println("    Installation:");
                System.out.println("      Linux:   sudo apt install ffmpeg  (or dnf/pacman)");
                System.out.println("      macOS:   brew install ffmpeg");
                System.out.println("      Windows: winget install ffmpeg");
                System.out.println();
                System.out.println("    Optional - videos will still be indexed with available metadata.");
            } else {
                System.out.println("    All video files are in supported formats.");
            }
        }
    }

    /**
     * Prints a verbose per-file line for video analysis results.
     */
    private void printVerboseVideoLine(FileMetadata metadata, AnalysisResult analysis) {
        Object method = analysis.metrics().get("extractionMethod");
        String methodStr = method != null ? method.toString() : "basic";

        StringBuilder line = new StringBuilder("  ");
        if ("metadata_extractor".equals(methodStr) || "ffprobe".equals(methodStr)) {
            line.append(AnsiOutput.success("[OK]")).append(" ");
        } else {
            line.append(AnsiOutput.warning("[!!]")).append(" ");
        }

        line.append(metadata.relativePath()).append(" (");
        line.append(methodStr);

        Object duration = analysis.metrics().get("durationSeconds");
        if (duration instanceof Number d && d.doubleValue() > 0) {
            line.append(": ").append(VideoAnalyzer.formatDuration(d.doubleValue()));
        }

        Object width = analysis.metrics().get("width");
        Object height = analysis.metrics().get("height");
        if (width instanceof Number w && height instanceof Number h && w.intValue() > 0) {
            line.append(", ").append(w.intValue()).append("x").append(h.intValue());
        }

        if ("basic".equals(methodStr) && analysis.keywords().contains("ffprobe-needed")) {
            line.append(", ffprobe needed for metadata");
        }

        line.append(")");
        System.out.println(line);
    }

    /**
     * Prints the video metadata coverage summary at the end of the scan.
     */
    private void printVideoSummary(long totalVideos, int withFullMeta,
                                    int withBasicMeta, int needingFfprobe) {
        System.out.println();
        System.out.println("  " + AnsiOutput.bold("Video metadata coverage:"));
        if (withFullMeta > 0) {
            System.out.printf("    %d video%s with full metadata (via metadata-extractor/ffprobe)%n",
                    withFullMeta, withFullMeta != 1 ? "s" : "");
        }
        if (withBasicMeta > 0) {
            System.out.printf("    %d video%s with basic metadata",
                    withBasicMeta, withBasicMeta != 1 ? "s" : "");
            if (needingFfprobe > 0) {
                System.out.printf(" (%d need ffprobe)", needingFfprobe);
            }
            System.out.println();
        }

        if (needingFfprobe > 0 && !FfprobeDetector.isAvailable()) {
            System.out.println();
            System.out.println("  Tip: Install ffmpeg for complete video support: "
                    + AnsiOutput.cyan(FfprobeDetector.getInstallHint()));
        }
    }
}
