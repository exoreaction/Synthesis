package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.org.ScopeResolver.ResolvedScope;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scores directory candidates for file routing based on content matching and scope bonuses.
 *
 * <p>Content scoring heuristics:
 * <ul>
 *   <li>Type match (extension maps to accepted type): +0.3</li>
 *   <li>Format match (extension in acceptsFormats or wildcard): +0.2</li>
 *   <li>Pattern match (filename matches a glob in acceptsPatterns): +0.3</li>
 * </ul>
 *
 * <p>Content score is capped at 1.0. Scope bonus (0.0-0.64) is added on top.
 * Blocked candidates (scope-incompatible) are sorted last.
 */
public class DirectoryScorer {

    /**
     * A candidate directory with its identity metadata.
     *
     * @param directory the directory path
     * @param identity  the parsed directory identity
     */
    public record DirectoryCandidate(Path directory, DirectoryIdentity identity) {}

    /**
     * A scored candidate with full breakdown.
     *
     * @param directory    the directory path
     * @param identity     the parsed directory identity
     * @param contentScore content-based score (0.0-1.0)
     * @param scopeBonus   scope-based bonus (0.0-0.64)
     * @param totalScore   contentScore + scopeBonus
     * @param blocked      true if scope is incompatible
     * @param reasons      human-readable scoring breakdown
     */
    public record ScoredCandidate(
            Path directory,
            DirectoryIdentity identity,
            double contentScore,
            double scopeBonus,
            double totalScore,
            boolean blocked,
            List<String> reasons
    ) implements Comparable<ScoredCandidate> {

        @Override
        public int compareTo(ScoredCandidate other) {
            // Blocked candidates come last
            if (this.blocked != other.blocked) {
                return this.blocked ? 1 : -1;
            }
            // Higher totalScore first
            return Double.compare(other.totalScore, this.totalScore);
        }
    }

    private static final double TYPE_MATCH_SCORE = 0.3;
    private static final double FORMAT_MATCH_SCORE = 0.2;
    private static final double PATTERN_MATCH_SCORE = 0.3;
    private static final double MAX_CONTENT_SCORE = 1.0;
    private static final double AMBIGUITY_THRESHOLD = 0.15;
    private static final double AMBIGUITY_MIN_SCORE = 0.1;

    /**
     * Maps file extensions to content type keywords for type matching.
     */
    private static final Map<String, Set<String>> EXTENSION_TYPE_MAP = Map.ofEntries(
            Map.entry("sh", Set.of("automation", "scripts")),
            Map.entry("bash", Set.of("automation", "scripts")),
            Map.entry("py", Set.of("automation", "scripts")),
            Map.entry("md", Set.of("documentation", "meeting-notes", "report", "business")),
            Map.entry("pdf", Set.of("documentation", "report", "presentation", "invoice", "financial", "contract", "legal")),
            Map.entry("png", Set.of("media", "visual")),
            Map.entry("jpg", Set.of("media", "visual")),
            Map.entry("jpeg", Set.of("media", "visual")),
            Map.entry("pptx", Set.of("presentation", "slides")),
            Map.entry("ppt", Set.of("presentation", "slides"))
    );

    private final ScopeChecker scopeChecker;

    /**
     * Creates a DirectoryScorer with the given scope checker.
     *
     * @param scopeChecker the scope checker for compatibility and bonus calculations
     */
    public DirectoryScorer(ScopeChecker scopeChecker) {
        this.scopeChecker = scopeChecker;
    }

    /**
     * Scores all candidate directories for a given file and its scope.
     *
     * <p>Results are sorted by totalScore descending, with blocked candidates last.
     * Ambiguity is detected when the top two non-blocked candidates have scores
     * within 0.15 of each other and both exceed 0.1.
     *
     * @param file       the file to route
     * @param fileScope  the resolved scope of the file
     * @param candidates the candidate directories to score
     * @return sorted list of scored candidates
     */
    public List<ScoredCandidate> score(
            Path file,
            ResolvedScope fileScope,
            List<DirectoryCandidate> candidates
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        String fileName = file.getFileName().toString();
        String extension = extractExtension(fileName);

        List<ScoredCandidate> results = new ArrayList<>();

        for (DirectoryCandidate candidate : candidates) {
            DirectoryIdentity identity = candidate.identity();
            ResolvedScope dirScope = new ResolvedScope(
                    identity.scopeLevel(),
                    identity.scopeOrganization(),
                    identity.scopeEntity()
            );

            boolean blocked = !scopeChecker.isCompatible(fileScope, dirScope);
            double bonus = blocked ? 0.0 : scopeChecker.scopeBonus(fileScope, dirScope);

            List<String> reasons = new ArrayList<>();
            double contentScore = 0.0;

            // Type match: check if file extension maps to any of the identity's acceptsTypes
            boolean typeMatched = false;
            if (extension != null && !extension.isEmpty()) {
                Set<String> typeKeywords = EXTENSION_TYPE_MAP.get(extension);
                if (typeKeywords != null) {
                    boolean typeMatch = identity.acceptsTypes().stream()
                            .anyMatch(t -> typeKeywords.contains(t.toLowerCase(Locale.ROOT)));
                    if (typeMatch) {
                        contentScore += TYPE_MATCH_SCORE;
                        reasons.add("type-match(+" + TYPE_MATCH_SCORE + ")");
                        typeMatched = true;
                    }
                }
            }

            // Also check if filename contains a token matching an accepted type
            if (!typeMatched && !identity.acceptsTypes().isEmpty()) {
                String fileNameLower = fileName.toLowerCase(Locale.ROOT);
                boolean tokenMatch = identity.acceptsTypes().stream()
                        .anyMatch(t -> fileNameLower.contains(t.toLowerCase(Locale.ROOT)));
                if (tokenMatch) {
                    contentScore += TYPE_MATCH_SCORE;
                    reasons.add("type-match-filename(+" + TYPE_MATCH_SCORE + ")");
                }
            }

            // Format match: check if extension is in acceptsFormats or acceptsFormats contains "*"
            if (extension != null && !extension.isEmpty()) {
                boolean formatMatch = identity.acceptsFormats().contains(extension)
                        || identity.acceptsFormats().contains("*");
                if (formatMatch) {
                    contentScore += FORMAT_MATCH_SCORE;
                    reasons.add("format-match(+" + FORMAT_MATCH_SCORE + ")");
                }
            }

            // Pattern match: check if filename matches any glob pattern in acceptsPatterns
            boolean patternMatch = false;
            for (String pattern : identity.acceptsPatterns()) {
                try {
                    PathMatcher matcher = FileSystems.getDefault()
                            .getPathMatcher("glob:" + pattern);
                    if (matcher.matches(Path.of(fileName))) {
                        patternMatch = true;
                        break;
                    }
                } catch (Exception e) {
                    // Skip invalid patterns
                }
            }
            if (patternMatch) {
                contentScore += PATTERN_MATCH_SCORE;
                reasons.add("pattern-match(+" + PATTERN_MATCH_SCORE + ")");
            }

            contentScore = Math.min(contentScore, MAX_CONTENT_SCORE);
            double totalScore = contentScore + bonus;

            if (blocked) {
                reasons.add("BLOCKED(scope-incompatible)");
            }
            if (bonus > 0.0) {
                reasons.add("scope-bonus(+" + bonus + ")");
            }

            results.add(new ScoredCandidate(
                    candidate.directory(),
                    identity,
                    contentScore,
                    bonus,
                    totalScore,
                    blocked,
                    reasons
            ));
        }

        // Sort: non-blocked first (higher score first), then blocked
        Collections.sort(results);

        // Ambiguity detection: top two non-blocked candidates
        List<ScoredCandidate> nonBlocked = results.stream()
                .filter(sc -> !sc.blocked())
                .toList();

        if (nonBlocked.size() >= 2) {
            ScoredCandidate first = nonBlocked.get(0);
            ScoredCandidate second = nonBlocked.get(1);
            if (first.totalScore() > AMBIGUITY_MIN_SCORE
                    && second.totalScore() > AMBIGUITY_MIN_SCORE
                    && (first.totalScore() - second.totalScore()) < AMBIGUITY_THRESHOLD) {
                // Replace the first candidate with an updated version including AMBIGUOUS reason
                int idx = results.indexOf(first);
                List<String> updatedReasons = new ArrayList<>(first.reasons());
                updatedReasons.add("AMBIGUOUS");
                results.set(idx, new ScoredCandidate(
                        first.directory(),
                        first.identity(),
                        first.contentScore(),
                        first.scopeBonus(),
                        first.totalScore(),
                        first.blocked(),
                        updatedReasons
                ));
            }
        }

        return results;
    }

    private static String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
