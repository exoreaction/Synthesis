package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.SynthesisApp;
import io.exoreaction.synthesis.config.ConfigLoader;
import io.exoreaction.synthesis.config.SynthesisConfig;
import io.exoreaction.synthesis.org.*;
import io.exoreaction.synthesis.search.WorkspaceDiscoveryConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Discovers and bootstraps directory identity metadata ({@code .synthesis.md} files).
 *
 * <p>Walks workspace directories, infers directory identity using
 * {@link DirectoryNameVocabulary} and {@link DirectorySignalExtractor},
 * and writes directory-level {@code .synthesis.md} files.
 *
 * <p>Precedence rules for {@code .synthesis.md} generation:
 * <ol>
 *   <li>Hand-edited files (source: "manual") — NEVER overwritten (unless {@code --force})</li>
 *   <li>Config sub-workspace entries → synthesized with source "config entry" (confidence 0.95)</li>
 *   <li>Inferred identities (source: "inferred from N files") — always regeneratable</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 *   synthesis sync                 # Sync all directories in workspace
 *   synthesis sync --dry-run       # Show what would be created/updated
 *   synthesis sync --dir path      # Sync only a specific directory tree
 *   synthesis sync --force         # Overwrite existing .synthesis.md files
 *   synthesis sync --verbose       # Show per-directory detail
 * </pre>
 */
@Command(
        name = "sync",
        description = "Discover and bootstrap directory identity metadata (.synthesis.md files)",
        mixinStandardHelpOptions = true
)
public class SyncCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Option(names = {"--dry-run"}, description = "Show what would be created/updated without writing")
    private boolean dryRun;

    @Option(names = {"--verbose", "-v"}, description = "Show per-directory detail")
    private boolean verbose;

    @Option(names = {"--dir"}, description = "Sync only a specific directory (not full workspace)")
    private Path targetDir;

    @Option(names = {"--force"}, description = "Overwrite existing .synthesis.md files (reset to inferred)")
    private boolean force;

    @Option(names = {"--enrich-centroids"}, description = "Compute semantic centroids and wants for directories")
    private boolean enrichCentroids;

    @Override
    public Integer call() throws Exception {
        Path workspaceRoot = parent.getWorkspaceRoot();
        return syncWorkspace(workspaceRoot);
    }

    /**
     * Runs the sync algorithm on the given workspace root.
     * Package-private for use from {@link MaintainCommand}.
     *
     * @param workspaceRoot the workspace root directory
     * @return exit code (0 = success)
     */
    int syncWorkspace(Path workspaceRoot) throws Exception {
        SynthesisConfig config;
        try {
            config = ConfigLoader.load(workspaceRoot);
        } catch (IOException e) {
            config = new SynthesisConfig();
        }

        List<String> excludePatterns = config.getScan().getEffectiveExcludePatterns(workspaceRoot);

        // Build map: normalized absolute path → SubWorkspaceConfig
        Map<Path, SynthesisConfig.SubWorkspaceConfig> configSubWorkspaces = new HashMap<>();
        for (SynthesisConfig.SubWorkspaceConfig sw : config.getSubWorkspaces()) {
            if (sw.getPath() != null && !sw.getPath().isBlank()) {
                Path normalized = workspaceRoot.resolve(sw.getPath()).normalize();
                configSubWorkspaces.put(normalized, sw);
            }
        }

        // Load org registry (if available)
        OrganizationRegistry registry = loadOrgRegistry(workspaceRoot);
        ScopeResolver scopeResolver = new ScopeResolver(registry);

        DirectoryIdentityParser parser = new DirectoryIdentityParser();
        DirectoryNameVocabulary vocabulary = new DirectoryNameVocabulary();
        DirectorySignalExtractor extractor = new DirectorySignalExtractor();
        EnrichmentSignatureExtractor enrichmentExtractor = enrichCentroids ? new EnrichmentSignatureExtractor() : null;
        CentroidComputer centroidComputer = enrichCentroids ? new CentroidComputer() : null;
        WantsBootstrapper wantsBootstrapper = enrichCentroids ? new WantsBootstrapper() : null;
        WantSatisfactionComputer satisfactionComputer = enrichCentroids ? new WantSatisfactionComputer() : null;

        // Cache centroids by directory for parent-centroid inheritance
        Map<Path, DirectoryCentroid> centroidCache = new HashMap<>();

        Path scanRoot = targetDir != null ? targetDir : workspaceRoot;

        int created = 0;
        int updated = 0;
        int unchanged = 0;

        try (Stream<Path> walk = Files.walk(scanRoot)) {
            List<Path> directories = walk
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(scanRoot))
                    .filter(dir -> !isHiddenDir(dir))
                    .filter(dir -> !isSynthesisDir(dir))
                    .filter(dir -> !matchesExcludePattern(dir, workspaceRoot, excludePatterns))
                    .filter(dir -> !isCodePackagePath(workspaceRoot, dir))
                    .filter(dir -> !isDeepInsideArchive(workspaceRoot, dir))
                    .toList();

            for (Path dir : directories) {
                Path synthesisFile = dir.resolve(".synthesis.md");
                ScopeResolver.ResolvedScope scope = scopeResolver.resolve(dir);

                boolean exists = Files.exists(synthesisFile) && !force;

                // Parse existing identity if present
                DirectoryIdentity existing = null;
                if (exists) {
                    existing = parser.parse(synthesisFile);
                }

                // --- Precedence 1: MANUAL source — never overwrite (unless --force) ---
                if (exists && existing != null && "manual".equals(existing.source()) && !force) {
                    if (verbose) {
                        System.out.println("  [MANUAL] " + workspaceRoot.relativize(dir)
                                + " (skipping — source: manual)");
                    }
                    unchanged++;
                    continue;
                }

                // --- Precedence 2: CONFIG ENTRY — generate from config sub-workspace ---
                SynthesisConfig.SubWorkspaceConfig configEntry = configSubWorkspaces.get(dir);
                if (configEntry != null && !force) {
                    List<String> configTypes = deriveTypesFromConfigEntry(configEntry);
                    DirectoryIdentity configIdentity = new DirectoryIdentity(
                            configTypes,
                            List.of(),   // formats (not specified in config)
                            List.of(),   // patterns
                            ScopeLevel.ORGANIZATION,
                            extractOrgFromPath(workspaceRoot, dir, configEntry),
                            extractEntityFromPath(workspaceRoot, dir, configEntry),
                            0.95,        // high confidence: explicitly configured
                            null,
                            "config entry",
                            configEntry.getDescription() != null ? configEntry.getDescription() : ""
                    );

                    // Write if no existing, or if existing is not manual
                    if (!exists || (existing != null && !"manual".equals(existing.source()))) {
                        // Check if anything actually changed
                        if (exists && existing != null && isEquivalent(existing, configIdentity)) {
                            unchanged++;
                            if (verbose) {
                                System.out.println("  [UNCHANGED] " + workspaceRoot.relativize(dir));
                            }
                            continue;
                        }
                        if (!dryRun) {
                            parser.write(synthesisFile, configIdentity);
                        }
                        String relativePath = workspaceRoot.relativize(dir).toString();
                        if (dryRun) {
                            if (verbose) {
                                printDetail(relativePath, configIdentity,
                                        exists ? "DRY update" : "DRY create");
                            } else {
                                System.out.println("  [DRY] Would "
                                        + (exists ? "update" : "create") + ": " + relativePath);
                            }
                        } else {
                            if (verbose) {
                                printDetail(relativePath, configIdentity,
                                        exists ? "CONFIG" : "CONFIG");
                            }
                        }
                        if (exists) updated++; else created++;
                    }
                    continue;  // Don't do inference for config-registered dirs
                }

                // --- Precedence 3: INFERENCE --- 

                // Run vocabulary inference
                Optional<DirectoryIdentity> vocabResult =
                        vocabulary.inferFromName(dir.getFileName().toString(), scope);

                // Run signal extraction
                DirectorySignalExtractor.DirectorySignals signals = extractor.extract(dir);

                // Skip if empty dir with no vocabulary match
                if (signals.fileCount() == 0 && vocabResult.isEmpty()) {
                    continue;
                }

                // Build discovered identity from signals
                DirectoryIdentity signalsIdentity = buildIdentityFromSignals(signals, scope);

                // Merge vocabulary and signals
                DirectoryIdentity discovered;
                if (vocabResult.isPresent()) {
                    discovered = parser.merge(vocabResult.get(), signalsIdentity);
                } else {
                    discovered = signalsIdentity;
                }

                // Skip if result has no meaningful data
                if (discovered.acceptsTypes().isEmpty()
                        && discovered.acceptsFormats().isEmpty()
                        && discovered.acceptsPatterns().isEmpty()
                        && discovered.confidence() == 0.0
                        && vocabResult.isEmpty()) {
                    continue;
                }

                // Ensure inferred identity has source set (preserve all 14 fields)
                if (discovered.source() == null || discovered.source().isEmpty()) {
                    discovered = new DirectoryIdentity(
                            discovered.acceptsTypes(), discovered.acceptsFormats(),
                            discovered.acceptsPatterns(),
                            discovered.scopeLevel(), discovered.scopeOrganization(),
                            discovered.scopeEntity(),
                            discovered.confidence(), discovered.lastSynced(),
                            "inferred from " + signals.fileCount() + " files",
                            discovered.description(),
                            discovered.rejectsTypes(), discovered.aliases(),
                            discovered.transient_(), discovered.movedFiles()
                    );
                }

                // Merge with existing if applicable
                DirectoryIdentity result;
                if (exists && existing != null) {
                    result = parser.merge(existing, discovered);
                    // Check if anything actually changed
                    if (isEquivalent(existing, result)) {
                        unchanged++;
                        if (verbose) {
                            System.out.println("  [UNCHANGED] "
                                    + workspaceRoot.relativize(dir));
                        }
                        continue;
                    }
                } else {
                    result = discovered;
                }

                // Depth guard (P1-02): transient is only meaningful for shallow landing zones.
                // A directory more than 2 levels deep from the workspace root is almost
                // certainly a permanent organisational home, not a transient staging area.
                if (result.transient_()) {
                    int depth = (int) workspaceRoot.relativize(dir).getNameCount();
                    if (depth > 2) {
                        result = new DirectoryIdentity(
                                result.acceptsTypes(), result.acceptsFormats(),
                                result.acceptsPatterns(),
                                result.scopeLevel(), result.scopeOrganization(),
                                result.scopeEntity(),
                                result.confidence(), result.lastSynced(),
                                result.source(), result.description(),
                                result.rejectsTypes(), result.aliases(),
                                false, result.movedFiles()
                        );
                    }
                }

                // Phase 2: Compute centroid and wants if --enrich-centroids is enabled
                DirectoryCentroid centroid = DirectoryCentroid.empty();
                DirectoryWants wants = DirectoryWants.empty();

                if (enrichCentroids) {
                    // Extract enrichment signatures for all files in the directory
                    List<EnrichmentSignature> signatures = extractEnrichmentSignatures(
                            dir, enrichmentExtractor, workspaceRoot);
                    int totalFileCount = countFilesInDirectory(dir);

                    if (!signatures.isEmpty()) {
                        centroid = centroidComputer.compute(signatures, totalFileCount);
                        centroidCache.put(dir, centroid);
                    }

                    // Bootstrap wants if centroid is absent or weak (confidence <= 0.8)
                    if (centroid.isEmpty() || centroid.confidence() <= 0.8) {
                        // Look up parent centroid
                        DirectoryCentroid parentCentroid = null;
                        if (dir.getParent() != null) {
                            parentCentroid = centroidCache.get(dir.getParent());
                        }
                        wants = wantsBootstrapper.bootstrap(dir, parentCentroid);
                    }

                    // Compute want satisfaction (P3-04)
                    if (!wants.isEmpty()) {
                        wants = satisfactionComputer.withSatisfaction(centroid, wants);
                    }

                    if (verbose && !centroid.isEmpty()) {
                        System.out.println("    centroid: topics=" + centroid.topics()
                                + ", entities=" + centroid.entities()
                                + ", confidence=" + String.format("%.2f", centroid.confidence()));
                    }
                    if (verbose && !wants.isEmpty()) {
                        System.out.println("    wants: topics=" + wants.topics()
                                + ", source=" + wants.source());
                    }
                }

                // Write or report
                String relativePath = workspaceRoot.relativize(dir).toString();
                if (exists && existing != null) {
                    // Updating existing
                    if (dryRun) {
                        if (verbose) {
                            printDetail(relativePath, result, "DRY update");
                        } else {
                            System.out.println("  [DRY] Would update: " + relativePath);
                        }
                    } else {
                        if (enrichCentroids) {
                            DirectoryProfile profile = new DirectoryProfile(result, centroid, wants);
                            parser.writeProfile(synthesisFile, profile);
                        } else {
                            parser.write(synthesisFile, result);
                        }
                        if (verbose) {
                            printDetail(relativePath, result, "updated");
                        }
                    }
                    updated++;
                } else {
                    // Creating new
                    if (dryRun) {
                        if (verbose) {
                            printDetail(relativePath, result, "DRY create");
                        } else {
                            System.out.println("  [DRY] Would create: " + relativePath);
                        }
                    } else {
                        if (enrichCentroids) {
                            DirectoryProfile profile = new DirectoryProfile(result, centroid, wants);
                            parser.writeProfile(synthesisFile, profile);
                        } else {
                            parser.write(synthesisFile, result);
                        }
                        if (verbose) {
                            printDetail(relativePath, result, "created");
                        }
                    }
                    created++;
                }
            }
        }

        int total = created + updated + unchanged;
        System.out.println("Synced: " + total + " directories ("
                + created + " created, " + updated + " updated, " + unchanged + " unchanged)");

        return 0;
    }

    // ---- Package-private setters for testing and MaintainCommand integration ----

    void setParent(SynthesisApp parent) {
        this.parent = parent;
    }

    void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    void setTargetDir(Path targetDir) {
        this.targetDir = targetDir;
    }

    void setForce(boolean force) {
        this.force = force;
    }

    void setEnrichCentroids(boolean enrichCentroids) {
        this.enrichCentroids = enrichCentroids;
    }

    // ---- Internal helpers ----

    /**
     * Derives a list of type strings from a {@link SynthesisConfig.SubWorkspaceConfig}.
     * The config type is expanded into semantic types; tags are also included.
     */
    List<String> deriveTypesFromConfigEntry(SynthesisConfig.SubWorkspaceConfig entry) {
        String type = entry.getType() != null ? entry.getType() : "general";
        List<String> types = new ArrayList<>(entry.getTags());
        switch (type) {
            case "source-code" -> types.addAll(List.of("source", "code"));
            case "documents" -> types.add("document");
            case "client" -> types.add("client");
            case "staging" -> types.add("staging");
            case "general" -> types.add("knowledge");
            default -> types.add(type);
        }
        return types;
    }

    /**
     * Extracts the organization hint from a config entry.
     * Uses the entry's name as the org, if set.
     */
    String extractOrgFromPath(Path workspaceRoot, Path dir,
                              SynthesisConfig.SubWorkspaceConfig entry) {
        if (entry.getName() != null && !entry.getName().isEmpty()) {
            return entry.getName();
        }
        return null;
    }

    /**
     * Extracts the entity hint from a config entry.
     * Returns null (kept simple for now).
     */
    String extractEntityFromPath(Path workspaceRoot, Path dir,
                                 SynthesisConfig.SubWorkspaceConfig entry) {
        return null;
    }

    /**
     * Builds a {@link DirectoryIdentity} from extracted signals and resolved scope.
     */
    static DirectoryIdentity buildIdentityFromSignals(
            DirectorySignalExtractor.DirectorySignals signals,
            ScopeResolver.ResolvedScope scope) {

        return new DirectoryIdentity(
                signals.inferredTypes(),
                signals.inferredFormats(),
                signals.inferredPatterns(),
                scope.level(),
                scope.organization(),
                scope.entity(),
                signals.confidence(),
                Instant.now(),
                "inferred from " + signals.fileCount() + " files",
                "",
                List.of(), List.of(), false, List.of()
        );
    }

    /**
     * Checks whether two identities are functionally equivalent
     * (ignoring lastSynced which always changes).
     */
    static boolean isEquivalent(DirectoryIdentity a, DirectoryIdentity b) {
        return a.acceptsTypes().equals(b.acceptsTypes())
                && a.acceptsFormats().equals(b.acceptsFormats())
                && a.acceptsPatterns().equals(b.acceptsPatterns())
                && a.scopeLevel() == b.scopeLevel()
                && java.util.Objects.equals(a.scopeOrganization(), b.scopeOrganization())
                && java.util.Objects.equals(a.scopeEntity(), b.scopeEntity())
                && Double.compare(a.confidence(), b.confidence()) == 0
                && java.util.Objects.equals(a.source(), b.source())
                && java.util.Objects.equals(a.description(), b.description())
                && a.rejectsTypes().equals(b.rejectsTypes())
                && a.aliases().equals(b.aliases())
                && a.transient_() == b.transient_();
    }

    /**
     * Returns true if the directory name starts with a dot (hidden directory).
     */
    private static boolean isHiddenDir(Path dir) {
        return dir.getFileName() != null
                && dir.getFileName().toString().startsWith(".");
    }

    /**
     * Returns true if the directory is named {@code .synthesis}.
     */
    private static boolean isSynthesisDir(Path dir) {
        return dir.getFileName() != null
                && dir.getFileName().toString().equals(".synthesis");
    }

    /**
     * Returns true if the directory matches any of the exclude patterns.
     * Matches against the directory name relative to workspace root.
     */
    private static boolean matchesExcludePattern(Path dir, Path workspaceRoot, List<String> excludePatterns) {
        String relativePath = workspaceRoot.relativize(dir).toString();
        for (String pattern : excludePatterns) {
            // Match directory name against simple patterns
            // e.g. "**/node_modules/**" should exclude node_modules dirs
            String cleanPattern = pattern.replace("**/", "").replace("/**", "");
            String dirName = dir.getFileName().toString();
            if (dirName.equals(cleanPattern)) {
                return true;
            }
            // Also check if relative path contains the pattern directory
            if (relativePath.contains(cleanPattern + "/") || relativePath.endsWith(cleanPattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Loads the OrganizationRegistry, searching workspace root and common locations.
     */
    private OrganizationRegistry loadOrgRegistry(Path workspaceRoot) {
        // Try the workspace root
        Path orgsFile = workspaceRoot.resolve(".synthesis").resolve("organizations.json");
        if (Files.exists(orgsFile)) {
            try {
                OrganizationRegistry registry = new OrganizationRegistry(workspaceRoot);
                registry.load();
                if (registry.hasOrganizations()) return registry;
            } catch (Exception ignored) {}
        }

        // Try ~/Documents
        Path docsPath = Path.of(System.getProperty("user.home"), "Documents");
        orgsFile = docsPath.resolve(".synthesis").resolve("organizations.json");
        if (Files.exists(orgsFile)) {
            try {
                OrganizationRegistry registry = new OrganizationRegistry(docsPath);
                registry.load();
                if (registry.hasOrganizations()) return registry;
            } catch (Exception ignored) {}
        }

        // Try all discovered workspaces
        try {
            WorkspaceDiscoveryConfig config = WorkspaceDiscoveryConfig.load();
            for (Path searchPath : config.getSearchPaths()) {
                if (!Files.exists(searchPath)) continue;
                orgsFile = searchPath.resolve(".synthesis").resolve("organizations.json");
                if (Files.exists(orgsFile)) {
                    try {
                        OrganizationRegistry registry = new OrganizationRegistry(searchPath);
                        registry.load();
                        if (registry.hasOrganizations()) return registry;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Returns true if the path is inside a Java/resource source tree.
     * These are code organisation directories, not routing targets.
     */
    static boolean isCodePackagePath(Path workspaceRoot, Path dir) {
        String rel = workspaceRoot.relativize(dir).toString().replace('\\', '/');
        return rel.contains("/src/main/java/")
                || rel.contains("/src/test/java/")
                || rel.contains("/src/main/resources/")
                || rel.contains("/src/test/resources/")
                || rel.endsWith("/src/main/java")
                || rel.endsWith("/src/test/java")
                || rel.endsWith("/src/main/resources")
                || rel.endsWith("/src/test/resources");
    }

    /**
     * Returns true if the path is more than 2 levels deep inside any {@code archive/} directory.
     * Allows {@code archive/} (level 0) and direct children like {@code archive/2022/} (level 1),
     * but excludes deeper subtrees such as browser-saved HTML artefacts.
     */
    static boolean isDeepInsideArchive(Path workspaceRoot, Path dir) {
        Path rel = workspaceRoot.relativize(dir);
        int depthBelowArchive = 0;
        for (int i = rel.getNameCount() - 1; i >= 0; i--) {
            if (rel.getName(i).toString().equals("archive")) {
                return depthBelowArchive > 2;
            }
            depthBelowArchive++;
        }
        return false;
    }

    /**
     * Extracts enrichment signatures for all regular files in a directory.
     * Skips hidden files, .synthesis.md files, and companion files.
     *
     * @param directory            the directory to scan
     * @param enrichmentExtractor  the extractor to use
     * @param workspaceRoot        the workspace root for relative path computation
     * @return list of non-empty enrichment signatures
     */
    static List<EnrichmentSignature> extractEnrichmentSignatures(
            Path directory, EnrichmentSignatureExtractor enrichmentExtractor, Path workspaceRoot) {
        List<EnrichmentSignature> signatures = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().startsWith("."))
                    .filter(f -> !f.getFileName().toString().equals(".synthesis.md"))
                    .filter(f -> !f.getFileName().toString().endsWith(".synthesis.md"))
                    .forEach(f -> {
                        EnrichmentSignature sig = enrichmentExtractor.extract(f, workspaceRoot);
                        if (!sig.isEmpty()) {
                            signatures.add(sig);
                        }
                    });
        } catch (IOException e) {
            // Return what we have
        }
        return signatures;
    }

    /**
     * Counts regular files in a directory (non-hidden, non-.synthesis).
     */
    static int countFilesInDirectory(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return (int) files
                    .filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().startsWith("."))
                    .filter(f -> !f.getFileName().toString().endsWith(".synthesis.md"))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Prints per-directory detail for verbose mode.
     */
    private void printDetail(String relativePath, DirectoryIdentity identity, String action) {
        System.out.println("  [" + action.toUpperCase() + "] " + relativePath
                + " (types=" + identity.acceptsTypes()
                + ", formats=" + identity.acceptsFormats()
                + ", confidence=" + identity.confidence() + ")");
    }
}
