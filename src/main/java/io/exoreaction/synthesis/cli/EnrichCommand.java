package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.ai.AiClient;
import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.analyzer.AnalyzerRegistry;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.core.WorkspaceManager;
import io.exoreaction.synthesis.enrichment.CompanionFileGenerator;
import io.exoreaction.synthesis.enrichment.EnrichmentLevel;
import io.exoreaction.synthesis.enrichment.EnrichmentResult;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import io.exoreaction.synthesis.util.AnsiOutput;
import io.exoreaction.synthesis.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Generates companion files for binary assets to make them fully text-searchable.
 *
 * <p>For every image, video, PDF, and audio file in the workspace, generates a
 * {@code .synthesis.md} companion file containing structured metadata, extracted
 * text, and relationship data. These companion files are automatically indexed
 * by standard scanning.
 *
 * <p>Usage:
 * <pre>
 *   synthesis enrich                    # Generate companions for all binary files
 *   synthesis enrich --force            # Regenerate even if companions exist
 *   synthesis enrich --type video       # Only for video files
 *   synthesis enrich --type image       # Only for image files
 *   synthesis enrich --level basic      # Force basic enrichment (no AI)
 *   synthesis enrich --dry-run          # Show what would be generated
 *   synthesis enrich --stats            # Show enrichment coverage statistics
 * </pre>
 *
 * @see CompanionFileGenerator
 * @see EnrichmentLevel
 */
@Command(
        name = "enrich",
        description = "Generate companion files for binary assets (makes images, videos, PDFs searchable)",
        mixinStandardHelpOptions = true
)
public class EnrichCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(
            names = {"--force"},
            description = "Regenerate companion files even if they already exist",
            defaultValue = "false"
    )
    private boolean force;

    @Option(
            names = {"--type"},
            description = "Only enrich files of this type: video, image, pdf, audio"
    )
    private String typeFilter;

    @Option(
            names = {"--level"},
            description = "Enrichment level: basic, local, ai (default: auto-detect)"
    )
    private String levelOverride;

    @Option(
            names = {"--dry-run"},
            description = "Show what would be generated without writing files",
            defaultValue = "false"
    )
    private boolean dryRun;

    @Option(
            names = {"--stats"},
            description = "Show enrichment coverage statistics",
            defaultValue = "false"
    )
    private boolean statsOnly;

    @Option(
            names = {"-v", "--verbose"},
            description = "Show detailed output",
            defaultValue = "false"
    )
    private boolean verbose;

    @Option(
            names = {"--path"},
            description = "Only enrich files matching this path prefix or glob (repeatable, e.g. 'eXOReaction/' or 'Quadim/**')",
            arity = "0..*"
    )
    private List<String> pathPatterns = new ArrayList<>();

    @Option(
            names = {"--exclude"},
            description = "Exclude files matching this glob pattern (repeatable, e.g. 'archive/**')",
            arity = "0..*"
    )
    private List<String> excludePatterns = new ArrayList<>();

    /** File types eligible for companion file generation. */
    private static final Set<String> ENRICHABLE_TYPES = Set.of(
            "VIDEO", "IMAGE", "PDF", "AUDIO"
    );

    @Override
    public Integer call() {
        long startMs = System.nanoTime();
        boolean metricsSuccess = false;
        String metricsWs = "unknown";
        try {
            Path workspaceRoot = parent.getWorkspaceRoot();
            metricsWs = workspaceRoot.toString();

            // Validate workspace
            WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
            var validation = workspace.validate();
            if (validation.isPresent()) {
                AnsiOutput.printError(validation.get());
                return 1;
            }

            SynthesisConfig config = ConfigLoader.load(workspaceRoot);

            AnsiOutput.printHeader("Synthesis - Enrichment");
            System.out.println();

            // Get all indexed files
            List<SearchResult> allFiles;
            try (SearchIndex index = SearchIndex.openReadOnly(workspace.getIndexPath())) {
                allFiles = index.listAll(null, 50000);
            }

            if (allFiles.isEmpty()) {
                AnsiOutput.printWarning("No files in index. Run 'synthesis scan' first.");
                return 1;
            }

            // Build path matchers for include/exclude filtering.
            // Normalize path patterns: bare prefixes like "docs/" become "docs/**"
            // so they match files recursively under that directory.
            FileSystem fs = FileSystems.getDefault();
            List<PathMatcher> includeMatchers = pathPatterns.stream()
                    .map(EnrichCommand::normalizePathPattern)
                    .map(p -> fs.getPathMatcher("glob:" + p))
                    .toList();
            List<PathMatcher> excludeMatchers = excludePatterns.stream()
                    .map(EnrichCommand::normalizePathPattern)
                    .map(p -> fs.getPathMatcher("glob:" + p))
                    .toList();

            // Filter to enrichable file types
            List<SearchResult> enrichable = allFiles.stream()
                    .filter(f -> f.fileType() != null && ENRICHABLE_TYPES.contains(f.fileType()))
                    .filter(f -> typeFilter == null ||
                            f.fileType().equalsIgnoreCase(typeFilter))
                    .filter(f -> matchesPathFilter(f.relativePath(), includeMatchers, excludeMatchers))
                    .toList();

            if (enrichable.isEmpty() && typeFilter != null && !typeFilter.equalsIgnoreCase("pdf")) {
                // A type filter was given that excludes PDFs; nothing to process.
                AnsiOutput.printInfo("No binary files found to enrich (filter: " + typeFilter + ").");
                metricsSuccess = true;
                return 0;
            }

            if (enrichable.isEmpty()) {
                // No indexed files, but oversized PDFs may still need metadata companions.
                // Fall through to the oversized-PDF pass below.
                if (!pathPatterns.isEmpty()) {
                    AnsiOutput.printInfo("No indexed binary files found to enrich (path: "
                            + String.join(", ", pathPatterns) + "). Checking for oversized PDFs...");
                } else {
                    AnsiOutput.printInfo("No indexed binary files found to enrich. Checking for oversized PDFs...");
                }
            }

            // Stats mode -- show coverage and exit
            if (statsOnly) {
                int r = showStats(enrichable, workspaceRoot);
                metricsSuccess = (r == 0);
                return r;
            }

            // Determine enrichment level
            EnrichmentLevel level;
            if (levelOverride != null) {
                level = switch (levelOverride.toLowerCase()) {
                    case "basic" -> EnrichmentLevel.BASIC;
                    case "local" -> EnrichmentLevel.LOCAL;
                    case "ai" -> EnrichmentLevel.AI;
                    default -> EnrichmentLevel.maxAvailable();
                };
            } else {
                level = EnrichmentLevel.maxAvailable();
            }

            AnsiOutput.printInfo("Enrichment level: " + level.name());
            AnsiOutput.printInfo("Files to process: " + enrichable.size());
            if (!pathPatterns.isEmpty()) {
                AnsiOutput.printInfo("Path filter:      " + String.join(", ", pathPatterns));
            }
            if (!excludePatterns.isEmpty()) {
                AnsiOutput.printInfo("Exclude filter:   " + String.join(", ", excludePatterns));
            }

            if (dryRun) {
                int r = showDryRun(enrichable, workspaceRoot, config, includeMatchers, excludeMatchers);
                metricsSuccess = (r == 0);
                return r;
            }

            // Create AI client if needed for AI-level enrichment.
            // Falls back to API-key-only client when ai.enabled=false but a key is available.
            AiClient aiClient = null;
            if (level.hasAI()) {
                Optional<AiClient> clientOpt = AiClient.create(config.getAi());
                if (clientOpt.isEmpty()) {
                    clientOpt = AiClient.createIfApiKeyAvailable(config.getAi(), config.getAi().getModel());
                }
                aiClient = clientOpt.orElse(null);
                if (aiClient == null) {
                    AnsiOutput.printWarning("AI enrichment requested but no API key available. Falling back to BASIC.");
                    level = EnrichmentLevel.BASIC;
                }
            }

            // Generate companion files
            CompanionFileGenerator generator = new CompanionFileGenerator(level, force, aiClient);
            AnalyzerRegistry analyzers = new AnalyzerRegistry();
            long startTime = System.currentTimeMillis();
            int generated = 0;
            int skipped = 0;
            int errors = 0;
            List<Path> generatedPaths = new ArrayList<>();

            for (int i = 0; i < enrichable.size(); i++) {
                SearchResult file = enrichable.get(i);
                try {
                    // Build FileMetadata from SearchResult
                    Path filePath = file.path();
                    if (!Files.exists(filePath)) {
                        if (verbose) {
                            AnsiOutput.printWarning("File not found: " + file.relativePath());
                        }
                        errors++;
                        continue;
                    }

                    BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
                    FileMetadata metadata = FileMetadata.of(
                            filePath, workspaceRoot, attrs.size(),
                            attrs.lastModifiedTime().toInstant(), null);

                    // Analyze the file
                    AnalysisResult analysis = analyzers.analyze(metadata);

                    // Detect related files (temporal proximity, naming conventions)
                    List<CompanionFileGenerator.RelatedFile> relatedFiles =
                            detectRelatedFiles(file, allFiles, workspaceRoot);

                    // Generate companion file
                    Optional<Path> companionPath = generator.generate(metadata, analysis, relatedFiles);

                    if (companionPath.isPresent()) {
                        generated++;
                        generatedPaths.add(companionPath.get());
                        if (verbose) {
                            System.out.println("  GENERATE " + file.relativePath() + ".synthesis.md");
                        }
                    } else {
                        skipped++;
                        if (verbose) {
                            System.out.println("  SKIP     " + file.relativePath());
                        }
                    }

                    // Progress indicator (every 10 files)
                    if (!verbose && (i + 1) % 10 == 0) {
                        System.out.printf("  Progress: %d/%d files processed%n", i + 1, enrichable.size());
                    }
                } catch (Exception e) {
                    errors++;
                    if (verbose) {
                        AnsiOutput.printWarning("Error: " + file.relativePath() + " - " + e.getMessage());
                    }
                }
            }

            // Pass 2: Walk the filesystem for oversized PDFs that are not in the index.
            // Files larger than maxFileSizeBytes (default 10 MB) are excluded by DirectoryScanner
            // and therefore never reach the enrichment loop above. We walk the workspace
            // directly to find .pdf files above the size threshold and generate
            // metadata-only companions for them (enrichment_level: METADATA).
            int oversizedGenerated = 0;
            int oversizedSkipped = 0;
            long sizeThreshold = config.getScan().getMaxFileSizeBytes();
            try (var walk = Files.walk(workspaceRoot)) {
                List<Path> oversizedPdfs = walk
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase();
                            return name.endsWith(".pdf");
                        })
                        .filter(p -> {
                            try {
                                return Files.size(p) > sizeThreshold;
                            } catch (IOException ex) {
                                return false;
                            }
                        })
                        .filter(p -> !CompanionFileGenerator.isCompanionFile(p))
                        .filter(p -> !CompanionFileGenerator.hasCompanion(p) || force)
                        .filter(p -> {
                            String rel = workspaceRoot.relativize(p).toString();
                            return matchesPathFilter(rel, includeMatchers, excludeMatchers);
                        })
                        .toList();

                if (!oversizedPdfs.isEmpty()) {
                    AnsiOutput.printInfo("Oversized PDFs (>" + FileUtils.formatSize(sizeThreshold)
                            + ") without companions: " + oversizedPdfs.size());
                }

                for (Path pdfPath : oversizedPdfs) {
                    String rel = workspaceRoot.relativize(pdfPath).toString();
                    if (dryRun) {
                        System.out.println("  WOULD GENERATE (metadata-only)  " + rel + ".synthesis.md");
                        oversizedGenerated++;
                        continue;
                    }
                    try {
                        Path companionPath = CompanionFileGenerator.generateOversizedPdfCompanion(pdfPath);
                        oversizedGenerated++;
                        generatedPaths.add(companionPath);
                        if (verbose) {
                            System.out.println("  GENERATE (metadata-only) " + rel + ".synthesis.md");
                        }
                    } catch (Exception e) {
                        errors++;
                        if (verbose) {
                            AnsiOutput.printWarning("Error (oversized): " + rel + " - " + e.getMessage());
                        }
                    }
                }
            }

            generated += oversizedGenerated;
            skipped += oversizedSkipped;

                        long duration = System.currentTimeMillis() - startTime;
            EnrichmentResult result = new EnrichmentResult(
                    enrichable.size(), generated, skipped, errors,
                    generatedPaths, duration, level);

            // Print summary
            System.out.println();
            AnsiOutput.printSuccess(result.summary());

            // Suggest next steps
            if (generated > 0) {
                System.out.println();
                AnsiOutput.printInfo("Next: Run 'synthesis scan' to index the new companion files.");
                AnsiOutput.printInfo("Then 'synthesis search' will find content in binary files.");
            }

            metricsSuccess = (errors == 0);
            return errors > 0 ? 1 : 0;

        } catch (Exception e) {
            AnsiOutput.printError("Enrichment failed: " + e.getMessage());
            return 1;
        } finally {
            long elapsed = (System.nanoTime() - startMs) / 1_000_000;
            parent.getMetrics().recordMcpInvocation("enrich", metricsWs, elapsed, null, metricsSuccess, null);
        }
    }

    /**
     * Normalizes a path pattern for glob matching.
     *
     * <p>When a user supplies a bare directory prefix (e.g. {@code knowledge-infrastructure/}),
     * Java's glob matcher treats it as a literal path that only matches the directory entry
     * itself, not files underneath. This method appends {@code **} so that the pattern
     * matches all files recursively under that directory.
     *
     * <p>Patterns that already contain glob meta-characters ({@code *}, {@code ?},
     * {@code [}, {@code \{}) are returned unchanged.
     *
     * @param pattern the raw pattern from {@code --path} or {@code --exclude}
     * @return a glob-ready pattern
     */
    static String normalizePathPattern(String pattern) {
        // If the pattern already contains glob characters, leave it as-is
        if (pattern.contains("*") || pattern.contains("?")
                || pattern.contains("[") || pattern.contains("{")) {
            return pattern;
        }
        // Bare directory prefix ending with '/' → append '**'
        if (pattern.endsWith("/")) {
            return pattern + "**";
        }
        // Bare name without extension or slash could be a directory name → append '/**'
        // e.g. "knowledge-infrastructure" → "knowledge-infrastructure/**"
        if (!pattern.contains(".")) {
            return pattern + "/**";
        }
        return pattern;
    }

    /**
     * Returns true if the given relative path should be processed.
     * A path must match at least one include matcher (if any) and must not match any exclude matcher.
     */
    static boolean matchesPathFilter(String relativePath, List<PathMatcher> include, List<PathMatcher> exclude) {
        Path rel = Path.of(relativePath);
        if (!include.isEmpty() && include.stream().noneMatch(m -> m.matches(rel))) return false;
        return exclude.stream().noneMatch(m -> m.matches(rel));
    }

    /**
     * Detects files related to the target file using naming conventions and proximity.
     */
    private List<CompanionFileGenerator.RelatedFile> detectRelatedFiles(
            SearchResult target, List<SearchResult> allFiles, Path workspaceRoot) {

        List<CompanionFileGenerator.RelatedFile> related = new ArrayList<>();
        String baseName = target.fileName();
        int dotIndex = baseName.lastIndexOf('.');
        String nameWithoutExt = dotIndex > 0 ? baseName.substring(0, dotIndex) : baseName;
        String parentDir = target.relativePath().contains("/")
                ? target.relativePath().substring(0, target.relativePath().lastIndexOf('/'))
                : "";

        for (SearchResult file : allFiles) {
            if (file.relativePath().equals(target.relativePath())) continue;

            // Check for naming convention matches (e.g., video.mp4 and video.srt, video-transcript.md)
            String otherName = file.fileName();
            String otherRelDir = file.relativePath().contains("/")
                    ? file.relativePath().substring(0, file.relativePath().lastIndexOf('/'))
                    : "";

            // Same directory, similar name
            if (otherRelDir.equals(parentDir)) {
                if (otherName.startsWith(nameWithoutExt + "-") || otherName.startsWith(nameWithoutExt + "_")) {
                    String suffix = otherName.substring(nameWithoutExt.length() + 1);
                    String relationship = inferRelationship(suffix);
                    related.add(new CompanionFileGenerator.RelatedFile(
                            file.fileName(), file.relativePath(), relationship));
                }
                // Subtitle/transcript files (.srt, .vtt)
                if (otherName.equals(nameWithoutExt + ".srt") || otherName.equals(nameWithoutExt + ".vtt")) {
                    related.add(new CompanionFileGenerator.RelatedFile(
                            file.fileName(), file.relativePath(), "subtitle/transcript"));
                }
            }

            // Limit to 5 related files
            if (related.size() >= 5) break;
        }

        return related;
    }

    private String inferRelationship(String suffix) {
        String lower = suffix.toLowerCase();
        if (lower.contains("transcript")) return "transcript";
        if (lower.contains("subtitle") || lower.endsWith(".srt") || lower.endsWith(".vtt")) return "subtitle";
        if (lower.contains("thumb") || lower.contains("preview")) return "thumbnail";
        if (lower.contains("slide")) return "slides";
        if (lower.contains("note")) return "notes";
        return "related";
    }

    /**
     * Shows enrichment coverage statistics.
     */
    private int showStats(List<SearchResult> enrichable, Path workspaceRoot) {
        int withCompanion = 0;
        int withoutCompanion = 0;

        for (SearchResult file : enrichable) {
            if (CompanionFileGenerator.hasCompanion(file.path())) {
                withCompanion++;
            } else {
                withoutCompanion++;
            }
        }

        double coverage = enrichable.isEmpty() ? 0 :
                (double) withCompanion / enrichable.size() * 100;

        System.out.println("  Enrichment Coverage Statistics");
        System.out.println("  " + "-".repeat(40));
        System.out.printf("  Total binary files:     %d%n", enrichable.size());
        System.out.printf("  With companion file:    %d%n", withCompanion);
        System.out.printf("  Without companion file: %d%n", withoutCompanion);
        System.out.printf("  Coverage:               %.1f%%%n", coverage);
        System.out.println();

        // Breakdown by type
        var byType = enrichable.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        f -> f.fileType() != null ? f.fileType() : "UNKNOWN",
                        java.util.stream.Collectors.counting()));
        System.out.println("  By type:");
        byType.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> System.out.printf("    %-10s %d files%n", e.getKey(), e.getValue()));

        System.out.println();
        if (withoutCompanion > 0) {
            AnsiOutput.printInfo("Run 'synthesis enrich' to generate " +
                    withoutCompanion + " companion files.");
        } else {
            AnsiOutput.printSuccess("All binary files have companion files!");
        }

        return 0;
    }

    /**
     * Shows what would be generated in dry-run mode.
     *
     * <p>Also lists oversized PDFs (files exceeding the scanner size threshold) that
     * would receive metadata-only companions.
     */
    private int showDryRun(List<SearchResult> enrichable, Path workspaceRoot,
                            SynthesisConfig config, List<PathMatcher> includeMatchers,
                            List<PathMatcher> excludeMatchers) {
        int wouldGenerate = 0;
        int wouldSkip = 0;

        // Pass 1: indexed files
        for (SearchResult file : enrichable) {
            if (CompanionFileGenerator.hasCompanion(file.path()) && !force) {
                wouldSkip++;
            } else {
                wouldGenerate++;
                System.out.println("  WOULD GENERATE  " +
                        file.relativePath() + ".synthesis.md");
            }
        }

        // Pass 2: oversized PDFs not in the index
        long sizeThreshold = config.getScan().getMaxFileSizeBytes();
        try (var walk = java.nio.file.Files.walk(workspaceRoot)) {
            List<Path> oversizedPdfs = walk
                    .filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .filter(p -> {
                        try {
                            return java.nio.file.Files.size(p) > sizeThreshold;
                        } catch (java.io.IOException ex) {
                            return false;
                        }
                    })
                    .filter(p -> !CompanionFileGenerator.isCompanionFile(p))
                    .filter(p -> !CompanionFileGenerator.hasCompanion(p) || force)
                    .filter(p -> {
                        String rel = workspaceRoot.relativize(p).toString();
                        return matchesPathFilter(rel, includeMatchers, excludeMatchers);
                    })
                    .toList();

            if (!oversizedPdfs.isEmpty()) {
                System.out.println();
                AnsiOutput.printInfo("Oversized PDFs (>" + FileUtils.formatSize(sizeThreshold)
                        + ") without companions: " + oversizedPdfs.size());
                for (Path pdf : oversizedPdfs) {
                    String rel = workspaceRoot.relativize(pdf).toString();
                    System.out.println("  WOULD GENERATE (metadata-only)  " + rel + ".synthesis.md");
                    wouldGenerate++;
                }
            }
        } catch (java.io.IOException e) {
            AnsiOutput.printWarning("Could not scan for oversized PDFs: " + e.getMessage());
        }

        System.out.println();
        System.out.printf("  Would generate: %d companion files%n", wouldGenerate);
        System.out.printf("  Would skip:     %d (already exist)%n", wouldSkip);

        return 0;
    }
}
