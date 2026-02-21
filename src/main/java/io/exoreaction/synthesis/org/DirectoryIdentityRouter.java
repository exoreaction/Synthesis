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
 * routing mechanism. As of P1-06, the primary API uses {@link RoutingContext} for
 * caller preferences and returns {@link RoutingDecision} with full reasoning.
 * Backward-compatible overloads using raw threshold/skipTransient parameters
 * delegate to the new API.
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

    // =========================================================================
    // Primary API (P1-06): RoutingContext -> RoutingDecision
    // =========================================================================

    /**
     * Routes a file using the given routing context, returning a structured decision.
     *
     * <p>Routing hints (from {@code .synthesis/routing-hints.json}) are checked first.
     * If a hint matches, it takes priority over regular scoring.
     *
     * @param file    the file to route
     * @param context caller preferences (threshold, skipTransient, etc.)
     * @return the routing decision, or empty if no candidate meets the threshold
     * @since v1.13.0 (P1-06)
     */
    public Optional<RoutingDecision> route(Path file, RoutingContext context) {
        // Check routing hints first (they have priority)
        Optional<RoutingDecision> hintResult = checkRoutingHintsDecision(file);
        if (hintResult.isPresent()) return hintResult;

        List<DirectoryScorer.DirectoryCandidate> candidateList =
                context.skipTransient() ? loadNonTransientCandidates() : loadCandidates();
        if (candidateList.isEmpty()) return Optional.empty();

        ScopeResolver.ResolvedScope fileScope = scopeResolver.resolve(
                file.getParent() != null ? file.getParent() : workspaceRoot);

        List<DirectoryScorer.ScoredCandidate> scored = scorer.score(file, fileScope, candidateList);
        if (scored.isEmpty()) return Optional.empty();

        DirectoryScorer.ScoredCandidate top = scored.get(0);
        if (top.blocked()) return Optional.empty();
        if (top.totalScore() < context.threshold()) return Optional.empty();

        boolean ambiguous = top.reasons().contains("AMBIGUOUS");
        return Optional.of(RoutingDecision.fromScoredCandidate(top, ambiguous));
    }

    // =========================================================================
    // Backward-compatible API (delegates to RoutingContext API)
    // =========================================================================

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
        RoutingContext context = new RoutingContext(threshold, skipTransient, false, false);
        Optional<RoutingDecision> decision = route(file, context);
        return decision.map(RouteResult::new);
    }

    // =========================================================================
    // Routing hints
    // =========================================================================

    /**
     * Checks routing hints for a matching pattern and returns a RoutingDecision.
     *
     * <p>Loads hints from {@code .synthesis/routing-hints.json}, matches against the
     * file's basename, and if a match is found, increments the hint's hit count and
     * returns a synthetic decision with score 0.9.
     *
     * @param file the file to check hints for
     * @return a RoutingDecision if a hint matches, empty otherwise
     */
    private Optional<RoutingDecision> checkRoutingHintsDecision(Path file) {
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
            // Best-effort -- don't block routing if we can't update the count
        }

        return Optional.of(RoutingDecision.fromHint(hintDest, 0.9));
    }

    // =========================================================================
    // Bidding-based routing (P3-02): enrichment-aware pull model
    // =========================================================================

    /** Lazily-initialized bidding candidates (directories with centroids/wants). */
    private List<DirectoryBidder.BiddingCandidate> biddingCandidates;

    /**
     * Routes a file using enrichment-based bidding when a signature is available,
     * falling back to identity scoring for non-enriched files.
     *
     * <p>Routing cascade order:
     * <ol>
     *   <li>RoutingHints (learned patterns) -- if match, return immediately</li>
     *   <li>ConfigRules (glob + keyword) -- handled by existing scoring</li>
     *   <li>DirectoryBidder (enrichment-based bidding) -- if enriched file</li>
     *   <li>DirectoryScorer (identity-based scoring) -- fallback for non-enriched</li>
     * </ol>
     *
     * @param file            the file to route
     * @param context         caller preferences
     * @param fileSignature   enrichment signature of the file (may be null or empty)
     * @return extended result with physical decision + virtual membership proposals
     * @since v1.15.0 (P3-02)
     */
    public Optional<RoutingDecision.ExtendedResult> routeWithBidding(
            Path file, RoutingContext context, EnrichmentSignature fileSignature) {

        // Step 1: Check routing hints first (highest priority)
        Optional<RoutingDecision> hintResult = checkRoutingHintsDecision(file);
        if (hintResult.isPresent()) {
            return Optional.of(new RoutingDecision.ExtendedResult(hintResult.get(), List.of()));
        }

        // Step 2: Try bidding if enrichment data is available
        if (fileSignature != null && !fileSignature.isEmpty()) {
            List<DirectoryBidder.BiddingCandidate> candidates = loadBiddingCandidates(context.skipTransient());
            if (!candidates.isEmpty()) {
                DirectoryBidder bidder = new DirectoryBidder();
                DirectoryBidder.BiddingResult biddingResult = bidder.bid(fileSignature, candidates);

                if (!biddingResult.isOrphan() && biddingResult.winner() != null
                        && biddingResult.winner().strength() >= context.threshold()) {
                    RoutingDecision decision = RoutingDecision.fromBid(biddingResult.winner());
                    return Optional.of(new RoutingDecision.ExtendedResult(
                            decision, biddingResult.virtualCandidates()));
                }
            }
        }

        // Step 3: Fall back to identity-based scoring (Phase 1 behavior)
        Optional<RoutingDecision> fallback = route(file, context);
        return fallback.map(d -> new RoutingDecision.ExtendedResult(d, List.of()));
    }

    /**
     * Lazy-initialize and cache the bidding candidates (directories with centroids or wants).
     *
     * @param skipTransient if true, exclude transient directories
     * @since v1.15.0 (P3-02)
     */
    private List<DirectoryBidder.BiddingCandidate> loadBiddingCandidates(boolean skipTransient) {
        if (biddingCandidates != null) return biddingCandidates;

        List<DirectoryBidder.BiddingCandidate> result = new ArrayList<>();
        try {
            Files.walk(workspaceRoot)
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(workspaceRoot))
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .forEach(dir -> {
                        Path synthesisFile = dir.resolve(".synthesis.md");
                        if (Files.exists(synthesisFile)) {
                            DirectoryProfile profile = parser.parseProfile(synthesisFile);
                            if (skipTransient && profile.identity().transient_()) {
                                return; // skip transient
                            }
                            // Only include directories that have centroid or wants
                            if (!profile.centroid().isEmpty() || !profile.wants().isEmpty()) {
                                result.add(new DirectoryBidder.BiddingCandidate(
                                        dir, profile.centroid(), profile.wants()));
                            }
                        }
                    });
        } catch (IOException e) {
            // Return whatever was collected
        }

        biddingCandidates = result;
        return result;
    }

    // =========================================================================
    // Scoring (for display/explain)
    // =========================================================================

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

    // =========================================================================
    // Candidate discovery (cached)
    // =========================================================================

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

    // =========================================================================
    // RouteResult (backward-compatible wrapper around RoutingDecision)
    // =========================================================================

    /**
     * Backward-compatible routing result wrapping a {@link RoutingDecision}.
     *
     * <p>Provides the same API as the previous inner record ({@code directory()},
     * {@code score()}, {@code ambiguous()}, {@code scoreLabel()}) while delegating
     * to the underlying {@link RoutingDecision}.
     *
     * @param decision the underlying routing decision
     * @since v1.13.0 (P1-06: wraps RoutingDecision)
     */
    public record RouteResult(RoutingDecision decision) {

        /** Returns the target directory path. */
        public Path directory() {
            return decision.destination();
        }

        /** Returns the total score. */
        public double score() {
            return decision.score();
        }

        /** Returns true if the match was ambiguous. */
        public boolean ambiguous() {
            return decision.ambiguous();
        }

        /** Returns the confidence level. */
        public RoutingConfidence confidence() {
            return decision.confidence();
        }

        /** Returns a human-readable label summarizing the routing decision. */
        public String scoreLabel() {
            return decision.scoreLabel();
        }
    }
}
