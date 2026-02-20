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
 * @since v1.9.9
 */
public class DirectoryIdentityRouter {

    private final Path workspaceRoot;
    private final ScopeResolver scopeResolver;
    private final DirectoryIdentityParser parser;
    private final DirectoryScorer scorer;

    /** Lazily-initialized candidate list, cached per instance. */
    private List<DirectoryScorer.DirectoryCandidate> candidates;

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
        this.scorer = new DirectoryScorer(new ScopeChecker());
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
        // Check routing hints first (they have priority)
        Optional<RouteResult> hintResult = checkRoutingHints(file);
        if (hintResult.isPresent()) return hintResult;

        List<DirectoryScorer.DirectoryCandidate> allCandidates = loadCandidates();
        if (allCandidates.isEmpty()) return Optional.empty();

        ScopeResolver.ResolvedScope fileScope = scopeResolver.resolve(
                file.getParent() != null ? file.getParent() : workspaceRoot);

        List<DirectoryScorer.ScoredCandidate> scored = scorer.score(file, fileScope, allCandidates);
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
        List<DirectoryScorer.DirectoryCandidate> allCandidates = loadCandidates();
        ScopeResolver.ResolvedScope fileScope = scopeResolver.resolve(
                file.getParent() != null ? file.getParent() : workspaceRoot);
        return scorer.score(file, fileScope, allCandidates);
    }

    /** Lazy-initialize and cache the candidate list. */
    private List<DirectoryScorer.DirectoryCandidate> loadCandidates() {
        if (candidates != null) return candidates;
        candidates = discoverCandidates();
        return candidates;
    }

    /** Walk workspace looking for directories with {@code .synthesis.md} files. */
    private List<DirectoryScorer.DirectoryCandidate> discoverCandidates() {
        List<DirectoryScorer.DirectoryCandidate> result = new ArrayList<>();
        try {
            Files.walk(workspaceRoot)
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(workspaceRoot))
                    .forEach(dir -> {
                        Path synthesisFile = dir.resolve(".synthesis.md");
                        if (Files.exists(synthesisFile)) {
                            DirectoryIdentity identity = parser.parse(synthesisFile);
                            if (!identity.acceptsTypes().isEmpty() || !identity.acceptsFormats().isEmpty()) {
                                result.add(new DirectoryScorer.DirectoryCandidate(dir, identity));
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
