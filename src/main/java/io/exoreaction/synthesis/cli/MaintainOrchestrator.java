package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.analyzer.AnalyzerRegistry;
import io.exoreaction.synthesis.changelog.SnapshotManager;
import io.exoreaction.synthesis.changelog.WorkspaceSnapshot;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.config.SynthesisConfig.RoutingRule;
import io.exoreaction.synthesis.config.SynthesisConfig.SubWorkspaceConfig;
import io.exoreaction.synthesis.core.*;
import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.index.FileIndexer;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.org.DirectoryIdentityRouter;
import io.exoreaction.synthesis.staging.StagingManager;
import io.exoreaction.synthesis.staging.StagingManager.StagedFile;
import io.exoreaction.synthesis.tracking.FileMovementTracker;
import io.exoreaction.synthesis.tracking.FileTrackingDatabase;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Orchestrates the 9-phase {@code synthesis maintain} workspace loop.
 *
 * <p>Each phase is run in strict order. A phase failure is captured as a
 * {@link PhaseResult#failed} entry and does NOT abort subsequent phases.
 *
 * <pre>
 * Phase 1: Ingest     — scan staging dirs, register new files
 * Phase 2: Route      — route staged files to permanent destinations
 * Phase 3: Sync       — discover/bootstrap .synthesis.md directory identities
 * Phase 4: Sweep      — archive stale root-level files
 * Phase 5: Rebalance  — move archive files back to active directories
 * Phase 6: Expire     — enforce TTL rules, archive expired files
 * Phase 7: Index      — incremental index update (scan, diff, apply)
 * Phase 8: Track      — file movement tracking + changelog snapshots
 * Phase 9: Prune      — remove empty directories
 * </pre>
 *
 * <p>The orchestrator does NOT print anything to stdout/stderr. All output
 * is captured in the returned {@link MaintainResult} for the caller
 * ({@link MaintainCommand}) to format.
 *
 * @since v1.9.9 (issue #183)
 */
public class MaintainOrchestrator {

    private final Path workspaceRoot;
    private final MaintainOptions options;
    private final SynthesisConfig config;

    /**
     * Creates an orchestrator for the given workspace.
     *
     * @param workspaceRoot workspace root directory (must be a valid Synthesis workspace)
     * @param options       orchestrator options (dry-run, verbose, etc.)
     * @param config        loaded workspace configuration
     */
    public MaintainOrchestrator(Path workspaceRoot, MaintainOptions options,
                                 SynthesisConfig config) {
        this.workspaceRoot = workspaceRoot;
        this.options = options;
        this.config = config;
    }

    // =========================================================================
    // State shared between phases 7 and 8
    // =========================================================================

    /** Cached scan state from the previous run (loaded in phase 7, used in phase 8). */
    private ScanState previousState;
    /** Fresh scan result computed in phase 7 and used by phase 8. */
    private ScanResult freshScan;
    /** Change set computed in phase 7 and used by phase 8. */
    private ScanState.ChangeSet changes;

    // =========================================================================
    // Main entry point
    // =========================================================================

    /**
     * Runs all 9 phases in sequence.
     *
     * @return aggregate result containing per-phase results and timing
     * @throws Exception only if workspace validation fails (phases themselves catch exceptions)
     */
    public MaintainResult run() throws Exception {
        List<PhaseResult> results = new ArrayList<>();
        long start = System.currentTimeMillis();

        // Phase 1: Ingest
        if (options.skipDownloads()) {
            results.add(PhaseResult.skipped(1, "Ingest", "--skip-downloads"));
        } else {
            results.add(runPhase(1, "Ingest", this::runIngest));
        }

        // Phase 2: Route
        if (options.skipDownloads()) {
            results.add(PhaseResult.skipped(2, "Route", "--skip-downloads"));
        } else {
            results.add(runPhase(2, "Route", this::runRoute));
        }

        // Phase 3: Sync
        results.add(runPhase(3, "Sync", this::runSync));

        // Phase 4: Sweep
        results.add(runPhase(4, "Sweep", this::runSweep));

        // Phase 5: Rebalance
        results.add(runPhase(5, "Rebalance", this::runRebalance));

        // Phase 6: Expire
        results.add(runPhase(6, "Expire", this::runExpire));

        // Phase 7: Index
        results.add(runPhase(7, "Index", this::runIndex));

        // Phase 8: Track
        results.add(runPhase(8, "Track", this::runTrack));

        // Phase 9: Prune
        results.add(runPhase(9, "Prune", this::runPrune));

        return new MaintainResult(results, System.currentTimeMillis() - start);
    }

    // =========================================================================
    // Phase runner (try/catch wrapper)
    // =========================================================================

    /**
     * Wraps a phase callable in try/catch. On exception, returns a
     * {@link PhaseResult#failed} entry so remaining phases can continue.
     */
    private PhaseResult runPhase(int num, String name, Callable<PhaseResult> phase) {
        try {
            return phase.call();
        } catch (Exception e) {
            return PhaseResult.failed(num, name, e.getMessage());
        }
    }

    // =========================================================================
    // Phase 1: Ingest
    // =========================================================================

    private PhaseResult runIngest() throws Exception {
        SynthesisConfig.StagingConfig stagingConfig = config.getStaging();
        if (stagingConfig == null || !stagingConfig.isEnabled()) {
            return PhaseResult.skipped(1, "Ingest", "staging not enabled");
        }

        List<SubWorkspaceConfig> stagingAreas =
                StagingManager.findStagingSubWorkspaces(config.getSubWorkspaces());
        if (stagingAreas.isEmpty()) {
            return PhaseResult.skipped(1, "Ingest", "no staging areas configured");
        }

        if (options.dryRun()) {
            int count = countStagingNewFiles(stagingAreas);
            return PhaseResult.success(1, "Ingest", count,
                    count + " file(s) would be ingested", List.of());
        }

        SynthesisDatabase db = SynthesisDatabase.getDefault();
        StagingManager staging = new StagingManager(db, stagingConfig, workspaceRoot);
        int ingested = ingestStagingAreas(staging, stagingAreas);
        return PhaseResult.success(1, "Ingest", ingested,
                ingested + " new file(s) ingested", List.of());
    }

    /**
     * Counts how many new files exist in staging directories that have not yet been ingested.
     */
    private int countStagingNewFiles(List<SubWorkspaceConfig> stagingAreas) {
        int count = 0;
        for (SubWorkspaceConfig sw : stagingAreas) {
            Path stagingDir = workspaceRoot.resolve(sw.getPath());
            if (!Files.isDirectory(stagingDir)) continue;
            try (Stream<Path> files = Files.walk(stagingDir)) {
                count += (int) files
                        .filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().contains("_processed"))
                        .filter(p -> !p.getFileName().toString().endsWith(".synthesis.md"))
                        .count();
            } catch (IOException e) {
                // best effort
            }
        }
        return count;
    }

    /**
     * Ingests new files from all staging areas.
     */
    private int ingestStagingAreas(StagingManager staging,
                                    List<SubWorkspaceConfig> stagingAreas) throws Exception {
        int totalIngested = 0;
        for (SubWorkspaceConfig sw : stagingAreas) {
            Path stagingDir = workspaceRoot.resolve(sw.getPath());
            if (!Files.isDirectory(stagingDir)) continue;

            List<StagedFile> existing = staging.list("pending");
            java.util.Set<String> existingPaths = new java.util.HashSet<>();
            for (StagedFile f : existing) {
                if (f.subWorkspace().equals(sw.getName())) {
                    existingPaths.add(f.relativePath());
                }
            }

            try (Stream<Path> files = Files.walk(stagingDir)) {
                List<Path> newFiles = files
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String rel = workspaceRoot.relativize(p).toString();
                            if (rel.startsWith(".synthesis/")) return false;
                            String basename = p.getFileName().toString();
                            if (basename.contains("_processed")) return false;
                            if (basename.endsWith(".synthesis.md")) return false;
                            return !existingPaths.contains(rel);
                        })
                        .toList();

                for (Path file : newFiles) {
                    try {
                        String relativePath = workspaceRoot.relativize(file).toString();
                        long size = Files.size(file);
                        String ext = getExtension(file.getFileName().toString());
                        staging.ingest(relativePath, sw.getName(), size, ext, null);
                        totalIngested++;
                    } catch (Exception e) {
                        // Skip individual file failures
                    }
                }
            }
        }
        return totalIngested;
    }

    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    // =========================================================================
    // Phase 2: Route
    // =========================================================================

    private PhaseResult runRoute() throws Exception {
        SynthesisConfig.StagingConfig stagingConfig = config.getStaging();
        if (stagingConfig == null || !stagingConfig.isEnabled()) {
            return PhaseResult.skipped(2, "Route", "staging not enabled");
        }

        if (!config.getRouting().hasRules() && !stagingConfig.isAutoClassify()) {
            return PhaseResult.skipped(2, "Route", "no routing rules configured");
        }

        SynthesisDatabase db = SynthesisDatabase.getDefault();
        StagingManager staging = new StagingManager(db, stagingConfig, workspaceRoot);
        List<StagedFile> pending = staging.list("pending");

        if (pending.isEmpty()) {
            return PhaseResult.success(2, "Route", 0, "no pending files", List.of());
        }

        if (options.dryRun()) {
            return PhaseResult.success(2, "Route", pending.size(),
                    pending.size() + " file(s) would be routed", List.of());
        }

        int routed = routePendingFiles(staging, pending);
        return PhaseResult.success(2, "Route", routed,
                routed + " file(s) routed", List.of());
    }

    /**
     * Routes pending staged files using config routing rules.
     */
    private int routePendingFiles(StagingManager staging, List<StagedFile> pending)
            throws Exception {
        List<RoutingRule> rules = config.getRouting().getRules();
        List<List<PathMatcher>> ruleMatchers = new ArrayList<>();
        for (RoutingRule rule : rules) {
            List<PathMatcher> matchers = new ArrayList<>();
            for (String pattern : rule.getPatterns()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
            }
            ruleMatchers.add(matchers);
        }
        boolean copyCompanions = config.getRouting().isCopyCompanions();

        int routed = 0;
        for (StagedFile file : pending) {
            String relPath = file.relativePath();
            if (relPath.startsWith(".synthesis/")) continue;
            String basename = Path.of(relPath).getFileName().toString();
            if (basename.contains("_processed")) continue;
            if (basename.endsWith(".synthesis.md")) continue;

            Path basenameAsPath = Path.of(basename);

            // Find first matching rule
            RoutingRule matchedRule = null;
            for (int i = 0; i < rules.size(); i++) {
                RoutingRule rule = rules.get(i);
                for (PathMatcher matcher : ruleMatchers.get(i)) {
                    if (matcher.matches(basenameAsPath)) {
                        matchedRule = rule;
                        break;
                    }
                }
                if (matchedRule != null) break;
                if (rule.hasKeywords()) {
                    Path companionPath = workspaceRoot.resolve(relPath + ".synthesis.md");
                    if (StagingCommand.companionMatchesKeywords(companionPath, rule.getKeywords())) {
                        matchedRule = rule;
                    }
                }
                if (matchedRule != null) break;
            }

            if (matchedRule != null) {
                Path destDir = Path.of(matchedRule.getDestination());
                Path destFile = destDir.resolve(basename);
                try {
                    boolean success = staging.routeTo(file, destFile, copyCompanions);
                    if (success) routed++;
                } catch (Exception e) {
                    // Skip individual file failures
                }
            }
        }
        return routed;
    }

    // =========================================================================
    // Phase 3: Sync
    // =========================================================================

    private PhaseResult runSync() throws Exception {
        SyncCommand syncCmd = new SyncCommand();
        syncCmd.setDryRun(options.dryRun());
        syncCmd.setVerbose(false);

        // SyncCommand.syncWorkspace prints to stdout; suppress it in quiet/json modes
        // so that the caller's output format is not polluted
        PrintStream savedOut = null;
        if (options.quiet() || options.json()) {
            savedOut = System.out;
            System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        }
        int exitCode;
        try {
            exitCode = syncCmd.syncWorkspace(workspaceRoot);
        } finally {
            if (savedOut != null) {
                System.setOut(savedOut);
            }
        }

        if (exitCode != 0) {
            return PhaseResult.failed(3, "Sync", "sync exited with code " + exitCode);
        }
        return PhaseResult.success(3, "Sync", 0,
                "directory identities synced", List.of());
    }

    // =========================================================================
    // Phase 4: Sweep
    // =========================================================================

    private PhaseResult runSweep() throws Exception {
        List<SweepCommand.SweepCandidate> candidates =
                SweepCommand.findCandidates(workspaceRoot, 30);

        if (candidates.isEmpty()) {
            return PhaseResult.success(4, "Sweep", 0,
                    "no stale root files", List.of());
        }

        // Load routing rules
        List<RoutingRule> rules = List.of();
        List<List<PathMatcher>> ruleMatchers = List.of();
        try {
            List<RoutingRule> loaded = config.getRouting().getRules();
            List<List<PathMatcher>> matchers = new ArrayList<>();
            for (RoutingRule rule : loaded) {
                List<PathMatcher> pm = new ArrayList<>();
                for (String pattern : rule.getPatterns()) {
                    pm.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
                }
                matchers.add(pm);
            }
            rules = loaded;
            ruleMatchers = matchers;
        } catch (Exception e) {
            // No routing rules — use archive fallback
        }

        Path archiveDest = workspaceRoot.resolve("archive")
                .resolve("swept-" + LocalDate.now());
        DirectoryIdentityRouter identityRouter =
                new DirectoryIdentityRouter(workspaceRoot, null);

        Map<SweepCommand.SweepCandidate, Path> destinations =
                SweepCommand.resolveDestinations(candidates, workspaceRoot,
                        rules, ruleMatchers, archiveDest, identityRouter);

        if (options.dryRun()) {
            List<String> details = new ArrayList<>();
            for (SweepCommand.SweepCandidate c : candidates) {
                details.add(c.path().getFileName().toString() + " -> "
                        + workspaceRoot.relativize(destinations.get(c)));
            }
            return PhaseResult.success(4, "Sweep", candidates.size(),
                    candidates.size() + " file(s) would be swept", details);
        }

        int moved = 0;
        for (SweepCommand.SweepCandidate c : candidates) {
            Path destination = destinations.get(c);
            if (destination == null) continue;
            try {
                Files.createDirectories(destination);
                Path target = destination.resolve(c.path().getFileName());
                Files.move(c.path(), target);
                moved++;
            } catch (IOException e) {
                // Skip individual file failures
            }
        }
        return PhaseResult.success(4, "Sweep", moved,
                moved + " file(s) swept", List.of());
    }

    // =========================================================================
    // Phase 5: Rebalance
    // =========================================================================

    private PhaseResult runRebalance() throws Exception {
        Path archiveDir = workspaceRoot.resolve("archive");
        if (!Files.isDirectory(archiveDir)) {
            return PhaseResult.success(5, "Rebalance", 0,
                    "no archive directory", List.of());
        }

        DirectoryIdentityRouter router =
                new DirectoryIdentityRouter(workspaceRoot, null);

        if (options.dryRun()) {
            // Count files that would be rebalanced
            int count = countRebalanceCandidates(archiveDir, router);
            return PhaseResult.success(5, "Rebalance", count,
                    count + " file(s) would be rebalanced", List.of());
        }

        MaintainCommand cmd = new MaintainCommand();
        int moved = cmd.rebalanceArchive(archiveDir, router, workspaceRoot);
        return PhaseResult.success(5, "Rebalance", moved,
                moved + " file(s) rebalanced", List.of());
    }

    private int countRebalanceCandidates(Path archiveDir,
                                          DirectoryIdentityRouter router) throws IOException {
        int count = 0;
        List<Path> archiveFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(archiveDir)) {
            walk.filter(Files::isRegularFile).forEach(archiveFiles::add);
        }
        for (Path file : archiveFiles) {
            var routed = router.route(file, 0.5);
            if (routed.isPresent() && !routed.get().ambiguous()) {
                count++;
            }
        }
        return count;
    }

    // =========================================================================
    // Phase 6: Expire
    // =========================================================================

    private PhaseResult runExpire() throws Exception {
        List<TtlCommand.TtlRule> rules = TtlCommand.loadRules(workspaceRoot);
        if (rules.isEmpty()) {
            return PhaseResult.success(6, "Expire", 0,
                    "no TTL rules defined", List.of());
        }

        // Find files expired by FILE AGE (not rule creation date)
        List<Path> expiredFiles = findExpiredByFileAge(workspaceRoot, rules);
        if (expiredFiles.isEmpty()) {
            return PhaseResult.success(6, "Expire", 0,
                    "no expired files", List.of());
        }

        if (options.dryRun()) {
            List<String> details = expiredFiles.stream()
                    .map(f -> "[would] " + f.getFileName() + " → archive/expired-" + LocalDate.now() + "/")
                    .collect(java.util.stream.Collectors.toList());
            return PhaseResult.success(6, "Expire", expiredFiles.size(),
                    expiredFiles.size() + " file(s) would be archived by TTL rules", details);
        }

        Path dest = workspaceRoot.resolve("archive").resolve("expired-" + LocalDate.now());
        Files.createDirectories(dest);
        int archived = 0;
        for (Path file : expiredFiles) {
            try {
                Files.move(file, dest.resolve(file.getFileName()));
                archived++;
            } catch (IOException e) {
                // Skip individual file failures
            }
        }
        return PhaseResult.success(6, "Expire", archived,
                archived + " file(s) archived by TTL rules", List.of());
    }

    /**
     * Finds files matching TTL rule patterns whose {@code lastModified} age exceeds the
     * rule's {@code days} limit.  Checks only direct children of {@code workspaceRoot}
     * (same scope as {@link TtlCommand#findExpiredFiles}).
     *
     * @param workspaceRoot workspace root directory
     * @param rules         TTL rules to apply
     * @return sorted list of expired file paths
     */
    static List<Path> findExpiredByFileAge(Path workspaceRoot, List<TtlCommand.TtlRule> rules)
            throws IOException {
        List<Path> matched = new ArrayList<>();
        try (Stream<Path> stream = Files.list(workspaceRoot)) {
            List<Path> rootFiles = stream.filter(Files::isRegularFile)
                    .collect(java.util.stream.Collectors.toList());
            for (TtlCommand.TtlRule rule : rules) {
                Instant threshold = Instant.now().minus(rule.days(), ChronoUnit.DAYS);
                for (Path file : rootFiles) {
                    if (TtlCommand.matchesPattern(file, rule.pattern())) {
                        Instant lastMod = Files.getLastModifiedTime(file).toInstant();
                        if (lastMod.isBefore(threshold)) {
                            if (!matched.contains(file)) {
                                matched.add(file);
                            }
                        }
                    }
                }
            }
        }
        matched.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return matched;
    }

    // =========================================================================
    // Phase 7: Index
    // =========================================================================

    private PhaseResult runIndex() throws Exception {
        WorkspaceManager workspace = new WorkspaceManager(workspaceRoot);
        Path scanStatePath = workspace.getScanStatePath();

        if (!ScanState.exists(scanStatePath)) {
            if (options.dryRun()) {
                return PhaseResult.success(7, "Index", 0,
                        "full scan would be performed (no previous state)", List.of());
            }
            // Run full scan
            int indexed = runFullScan(workspace);
            return PhaseResult.success(7, "Index", indexed,
                    indexed + " file(s) indexed (full scan)", List.of());
        }

        previousState = ScanState.load(scanStatePath);

        DirectoryScanner scanner = new DirectoryScanner(
                workspaceRoot, config.getScan(), false);
        freshScan = scanner.scan();

        changes = previousState.computeChanges(freshScan);

        if (!changes.hasChanges()) {
            return PhaseResult.success(7, "Index", 0,
                    "workspace up to date", List.of());
        }

        int totalChanges = changes.totalChanges();
        if (options.dryRun()) {
            List<String> details = new ArrayList<>();
            details.add("+" + changes.added().size() + " new");
            details.add("~" + changes.modified().size() + " modified");
            details.add("-" + changes.deleted().size() + " deleted");
            return PhaseResult.success(7, "Index", totalChanges,
                    totalChanges + " change(s) detected", details);
        }

        int updated = applyChanges(workspace, changes);

        // Save new scan state
        ScanState newState = ScanState.fromScanResult(freshScan);
        newState.save(scanStatePath);

        return PhaseResult.success(7, "Index", updated,
                updated + " document(s) updated", List.of());
    }

    /**
     * Runs a full scan when no previous scan state exists.
     * Extracted from MaintainCommand.runFullScan.
     */
    private int runFullScan(WorkspaceManager workspace) throws IOException {
        DirectoryScanner scanner = new DirectoryScanner(
                workspaceRoot, config.getScan(), false);
        ScanResult scanResult = scanner.scan();

        AnalyzerRegistry analyzers = new AnalyzerRegistry();
        FileIndexer fileIndexer = new FileIndexer();

        int indexed = 0;
        try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
            index.deleteAll();

            for (FileMetadata metadata : scanResult.files()) {
                try {
                    AnalysisResult analysis = analyzers.analyze(metadata);
                    var doc = fileIndexer.createDocument(metadata, analysis);
                    index.addDocument(doc);
                    indexed++;
                } catch (Exception e) {
                    // Skip individual file failures
                }
            }
            index.commit();
        }

        ScanState state = ScanState.fromScanResult(scanResult);
        state.save(workspace.getScanStatePath());

        // Store for phase 8
        this.freshScan = scanResult;

        return indexed;
    }

    /**
     * Applies incremental changes to the search index.
     * Extracted from MaintainCommand.applyChanges.
     */
    private int applyChanges(WorkspaceManager workspace,
                              ScanState.ChangeSet changes) throws IOException {
        SubWorkspaceResolver subWsResolver = new SubWorkspaceResolver(config);
        AnalyzerRegistry analyzers = new AnalyzerRegistry();
        FileIndexer fileIndexer = new FileIndexer();
        int updated = 0;

        try (SearchIndex index = new SearchIndex(workspace.getIndexPath())) {
            for (FileMetadata fm : changes.added()) {
                try {
                    AnalysisResult analysis = analyzers.analyze(fm);
                    String subWorkspace = subWsResolver.resolve(fm.relativePath());
                    index.addDocument(fileIndexer.createDocument(fm, analysis,
                            null, null, null, subWorkspace));
                    updated++;
                } catch (Exception e) {
                    // Skip individual file failures
                }
            }

            for (FileMetadata fm : changes.modified()) {
                try {
                    AnalysisResult analysis = analyzers.analyze(fm);
                    String subWorkspace = subWsResolver.resolve(fm.relativePath());
                    index.addDocument(fileIndexer.createDocument(fm, analysis,
                            null, null, null, subWorkspace));
                    updated++;
                } catch (Exception e) {
                    // Skip individual file failures
                }
            }

            for (String path : changes.deleted()) {
                index.deleteByRelativePath(path);
                updated++;
            }

            index.commit();
        }

        return updated;
    }

    // =========================================================================
    // Phase 8: Track
    // =========================================================================

    private PhaseResult runTrack() throws Exception {
        // Phase 8 depends on phase 7 having run (needs previousState and changes)
        if (previousState == null || changes == null || freshScan == null) {
            return PhaseResult.skipped(8, "Track", "no scan data from Index phase");
        }

        if (!changes.hasChanges()) {
            return PhaseResult.success(8, "Track", 0,
                    "no changes to track", List.of());
        }

        if (options.dryRun()) {
            return PhaseResult.success(8, "Track", 0,
                    "tracking would be updated", List.of());
        }

        int trackingChanges = 0;
        List<String> details = new ArrayList<>();

        // File movement tracking
        try {
            SynthesisDatabase synthDb = SynthesisDatabase.getDefault();
            FileTrackingDatabase trackingDb = new FileTrackingDatabase(synthDb);
            FileMovementTracker tracker = new FileMovementTracker(trackingDb, 7);

            int movements = tracker.detectMovementsWithHistory(
                    previousState.getEntries(),
                    changes.deleted(),
                    changes.added(),
                    workspaceRoot.toString(),
                    workspaceRoot.toString());

            int resolved = tracker.resolvePendingDeletions(
                    workspaceRoot.toString(), changes);

            int eligible = tracker.processExpiredSafetyPeriods();

            trackingChanges += movements + resolved + eligible;
            if (movements > 0) details.add(movements + " movement(s) detected");
            if (resolved > 0) details.add(resolved + " pending resolved");
            if (eligible > 0) details.add(eligible + " cleanup-eligible");
        } catch (Exception e) {
            details.add("tracking: " + e.getMessage());
        }

        // Changelog snapshot
        try {
            SynthesisDatabase synthDb = SynthesisDatabase.getDefault();
            SnapshotManager snapshots = new SnapshotManager(synthDb);
            long snapshotId = snapshots.takeSnapshotFromScanResult(
                    workspaceRoot.toString(),
                    config.getWorkspace().getName(),
                    freshScan, "maintain");

            List<WorkspaceSnapshot> recent = snapshots.getSnapshots(
                    workspaceRoot.toString(), 2);
            if (recent.size() >= 2) {
                snapshots.compareSnapshots(recent.get(1).id(), snapshotId);
            }
            trackingChanges++;
            details.add("snapshot created");
        } catch (Exception e) {
            details.add("snapshot: " + e.getMessage());
        }

        String summary = trackingChanges > 0
                ? trackingChanges + " tracking update(s)"
                : "tracking updated";
        return PhaseResult.success(8, "Track", trackingChanges, summary, details);
    }

    // =========================================================================
    // Phase 9: Prune
    // =========================================================================

    private PhaseResult runPrune() throws Exception {
        Set<String> protectedPaths = PruneCommand.buildProtectedPaths(workspaceRoot, config);
        List<Path> pruneable = PruneCommand.findPruneable(
                workspaceRoot, workspaceRoot, protectedPaths);

        if (pruneable.isEmpty()) {
            return PhaseResult.success(9, "Prune", 0,
                    "no empty directories", List.of());
        }

        if (options.dryRun()) {
            List<String> details = new ArrayList<>();
            for (Path p : pruneable) {
                details.add(workspaceRoot.relativize(p).toString());
            }
            return PhaseResult.success(9, "Prune", pruneable.size(),
                    pruneable.size() + " empty dir(s) would be removed", details);
        }

        int removed = PruneCommand.pruneDirectories(pruneable);
        return PhaseResult.success(9, "Prune", removed,
                removed + " empty dir(s) removed", List.of());
    }
}
