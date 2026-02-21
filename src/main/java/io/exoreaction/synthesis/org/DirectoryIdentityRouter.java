package io.exoreaction.synthesis.org;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates directory identity-based file routing.
 *
 * <p>Walks the workspace root looking for directories that have {@code .synthesis.md}
 * identity files, parses them using {@link DirectoryIdentityParser}, and scores all
 * candidates using {@link DirectoryScorer} for a given file and its resolved scope.
 *
 * <p>Results are cached per instance to avoid repeated filesystem walks.
 *
 * <p>As of P1-05, this class replaces {@code SubjectBasedRouter} as the unified
 * routing mechanism. The {@link #route(Path, double, boolean)} overload supports
 * transient-destination filtering for rebalance and health check operations.
 *
 * @since v1.9.9
 */
public class DirectoryIdentityRouter {

    private final Path workspaceRoot;
    private final ScopeResolver scopeResolver;
    private final DirectoryIdentityParser parser;
    private final DirectoryScorer scorer;

    /** Lazily-initialized candidate list (all directories), cached per instance. */
    private List<DirectoryScorer.DirectoryCandidate> allCandidates;

    /** Lazily-initialized candidate list (non-transient only), cached per instance. */
    private List<DirectoryScorer.DirectoryCandidate> nonTransientCandidates;

    /**
     * Creates a router for the given workspace.
     *
     * @param workspaceRoot the workspace root path
     * @param orgRegistry   organization registry for scope resolution (may be null)
     */
    public DirectoryIdentityRouter(Path workspaceRoot, OrganizationRegistry orgRegistry) {
        this.workspaceRoot = workspaceRoot;
        this.scopeResolver = new ScopeResolver(orgRegistry);
        this.parser = new DirectoryIdentityParser();
        this.scorer = new DirectoryScorer(new ScopeChecker(), workspaceRoot);
    }

    /**
     * Returns the best-matching directory candidate for the given file, or empty if:
     * <ul>
     *   <li>no candidates exist</li>
     *   <li>top score is below threshold</li>
     *   <li>top result is ambiguous (AMBIGUOUS in reasons)</li>
     * </ul>
     *
     * <p>Routing hints (from {@code .synthesis/routing-hints.json}) are checked first.
     * If a hint matches, it takes priority over regular scoring and returns a synthetic
     * candidate with a score of 0.9.
     *
     * @param file      the file to route
     * @param threshold minimum score to auto-route (typically 0.5)
     * @return the top RouteResult, or empty
     */
    public Optional<RouteResult> route(Path file, double threshold) {
        return route(file, threshold, false);
    }

    /**
     * Returns the best-matching directory candidate for the given file, with optional
     * transient-destination filtering.
     *
     * <p>When {@code skipTransient} is {@code true}, transient directories are excluded
     * from the candidate set. This is used by rebalance operations (moving files OUT of
     * transient directories to permanent homes) and by E010 health checks.
     *
     * @param file           the file to route
     * @param threshold      minimum score to auto-route
     * @param skipTransient  if true, exclude transient directories from candidates
     * @return the top RouteResult, or empty
     * @since v1.13.0 (P1-05)
     */
    public Optional<RouteResult> route(Path file, double threshold, boolean skipTransient) {
        // Check routing hints first (they have priority)
        Optional<RouteResult> hintResult = checkRoutingHints(file);
        if (hintResult.isPresent()) return hintResult;

        List<DirectoryScorer.DirectoryCandidate> candidateList =
                skipTransient ? loadNonTransientCandidates() : loadCandidates();
        if (candidateList.isEmpty()) return Optional.empty();

        ScopeResolver.ResolvedScope fileScope = scopeResolver.resolve(
                file.getParent() != null ? file.getParent() : workspaceRoot);

        List<DirectoryScorer.ScoredCandidate> scored = scorer.score(file, fileScope, candidateList);
        if (scored.isEmpty()) return Optional.empty();

        DirectoryScorer.ScoredCandidate top = scored.get(0);
        if (top.blocked()) return Optional.empty();
        if (top.totalScore() < threshold) return Optional.empty();
        if (top.reasons().contains("AMBIGUOUS")) return Optional.of(new RouteResult(top, true));
        return Optional.of(new RouteResult(top, false));
    }

    /**
     * Checks routing hints for a matching pattern and returns a synthetic RouteResult.
     *
     * <p>Loads hints from {@code .synthesis/routing-hints.json}, matches against the
     * file's basename, and if a match is found, increments the hint's hit count and
     * returns a synthetic scored candidate with score 0.9.
     *
     * @param file the file to check hints for
     * @return a RouteResult if a hint matches, empty otherwise
     */
    private Optional<RouteResult> checkRoutingHints(Path file) {
        RoutingHints routingHints = new RoutingHints(workspaceRoot);
        List<RoutingHints.RoutingHint> matches;
        try {
            routingHints.load();
            matches = routingHints.matchingHints(file.getFileName().toString());
        } catch (IOException e) {
            return Optional.empty();
        }

        if (matches.isEmpty()) return Optional.empty();

        // Use the first matching hint
        RoutingHints.RoutingHint hint = matches.get(0);
        Path hintDest = Path.of(hint.destinationPath());
        if (!Files.isDirectory(hintDest)) return Optional.empty();

        // Increment hit count
        try {
            routingHints.addOrUpdate(new RoutingHints.RoutingHint(
                    hint.filenamePattern(), hint.destinationPath(),
                    hint.learnedAt(), hint.hitCount()));
        } catch (IOException ignored) {
            // Best-effort — don't block routing if we can't update the count
        }

        // Create a synthetic ScoredCandidate for the hint
        DirectoryIdentity dummyIdentity = DirectoryIdentity.empty();
        DirectoryScorer.ScoredCandidate hintCandidate = new DirectoryScorer.ScoredCandidate(
                hintDest, dummyIdentity, 0.9, 0.0, 0.9, false, List.of("hint-match"));
        return Optional.of(new RouteResult(hintCandidate, false));
    }

    /**
     * Returns ALL scored candidates sorted by score, for display purposes (verbose mode).
     *
     * @param file the file to score candidates for
     * @return sorted list of scored candidates
     */
    public List<DirectoryScorer.ScoredCandidate> scoreAll(Path file) {
        List<DirectoryScorer.DirectoryCandidate> candidateList = loadCandidates();
        ScopeResolver.ResolvedScope fileScope = scopeResolver.resolve(
                file.getParent() != null ? file.getParent() : workspaceRoot);
        return scorer.score(file, fileScope, candidateList);
    }

    /** Lazy-initialize and cache the full candidate list. */
    private List<DirectoryScorer.DirectoryCandidate> loadCandidates() {
        if (allCandidates != null) return allCandidates;
        allCandidates = discoverCandidates(false);
        return allCandidates;
    }

    /**
     * Lazy-initialize and cache the non-transient candidate list.
     * @since v1.13.0 (P1-05)
     */
    private List<DirectoryScorer.DirectoryCandidate> loadNonTransientCandidates() {
        if (nonTransientCandidates != null) return nonTransientCandidates;
        nonTransientCandidates = discoverCandidates(true);
        return nonTransientCandidates;
    }

    /**
     * Walk workspace looking for directories with {@code .synthesis.md} files.
     *
     * @param skipTransient if true, exclude directories where {@code transient_() == true}
     */
    private List<DirectoryScorer.DirectoryCandidate> discoverCandidates(boolean skipTransient) {
        List<DirectoryScorer.DirectoryCandidate> result = new ArrayList<>();
        try {
            Files.walk(workspaceRoot)
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(workspaceRoot))
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .forEach(dir -> {
                        Path synthesisFile = dir.resolve(".synthesis.md");
                        if (Files.exists(synthesisFile)) {
                            DirectoryIdentity identity = parser.parse(synthesisFile);
                            if (!identity.acceptsTypes().isEmpty() || !identity.acceptsFormats().isEmpty()) {
                                if (!skipTransient || !identity.transient_()) {
                                    result.add(new DirectoryScorer.DirectoryCandidate(dir, identity));
                                }
                            }
                        }
                    });
        } catch (IOException e) {
            // Return whatever was collected
        }
        return result;
    }

    /**
     * Encapsulates a routing decision: the scored candidate and whether the match
     * was ambiguous (multiple candidates with very similar scores).
     *
     * @param candidate the top-scoring candidate
     * @param ambiguous true if the match was ambiguous
     */
    public record RouteResult(DirectoryScorer.ScoredCandidate candidate, boolean ambiguous) {

        /** Returns the target directory path. */
        public Path directory() {
            return candidate.directory();
        }

        /** Returns the total score. */
        public double score() {
            return candidate.totalScore();
        }

        /** Returns a human-readable label summarizing the routing decision. */
        public String scoreLabel() {
            return String.format("dir-identity: %s @ %.2f",
                    directory().getFileName(), score());
        }
    }
}
