package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.org.ScopeResolver.ResolvedScope;
import io.exoreaction.synthesis.util.MediaTypes;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scores directory candidates for file routing based on content matching and scope bonuses.
 *
 * <p>Content scoring heuristics:
 * <ul>
 *   <li>Type match (extension maps to accepted type): +0.3 (specific) or +0.15 (generic)</li>
 *   <li>Format match (extension in acceptsFormats or wildcard): +0.2</li>
 *   <li>Pattern match (filename matches a glob in acceptsPatterns): +0.3</li>
 *   <li>Filename token match (filename tokens vs directory path tokens): +0.25 max</li>
 * </ul>
 *
 * <p>Content score is capped at 1.0. Scope bonus is normalized so that the
 * total score never exceeds 1.0: {@code totalScore = contentScore +
 * (scopeBonus * (1.0 - contentScore) * 0.5)}. This makes scope bonus a
 * tiebreaker rather than a dominant signal. Blocked candidates
 * (scope-incompatible) are sorted last.
 *
 * <p>Type matching distinguishes specific vs generic types. "Specific" types are those
 * unique to fewer file categories (e.g. "automation", "meeting-notes"). "Generic" types
 * are broad categories that many extensions share (e.g. "documentation", "data", "media").
 * Specific type matches receive full +0.3, generic matches receive +0.15.
 *
 * <p>Filename token matching tokenizes both the filename (splitting on {@code -}, {@code _},
 * {@code .}, and spaces) and the candidate directory's relative path from workspace root,
 * plus any {@link DirectoryIdentity#aliases()} declared in the directory's identity.
 * Tokens shorter than 3 characters are ignored. The score is proportional to the overlap
 * ratio, weighted at +0.25 max.
 *
 * @since v1.9.9 (scoring improvements: issue #209)
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
     * @param scopeBonus   normalized scope-based bonus (0.0-0.325 max after normalization)
     * @param totalScore   normalized total (0.0-1.0): contentScore + normalizedScopeBonus
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
    private static final double TYPE_MATCH_GENERIC_SCORE = 0.15;
    private static final double FORMAT_MATCH_SCORE = 0.2;
    private static final double PATTERN_MATCH_SCORE = 0.3;
    private static final double FILENAME_TOKEN_MATCH_MAX = 0.25;
    private static final double MAX_CONTENT_SCORE = 1.0;
    private static final double AMBIGUITY_THRESHOLD = 0.15;
    private static final double AMBIGUITY_MIN_SCORE = 0.1;

    /**
     * Type keywords considered "generic" — they map from many different file extensions
     * and provide a weaker signal for directory matching. Generic type matches receive
     * half the normal type-match score.
     */
    private static final Set<String> GENERIC_TYPES = Set.of(
            "documentation", "data", "media", "visual", "report",
            "document", "config", "archive", "artifact"
    );

    /**
     * Maps file extensions to content type keywords for type matching.
     */
    private static final Map<String, Set<String>> EXTENSION_TYPE_MAP = Map.ofEntries(
            Map.entry("sh", Set.of("automation", "scripts")),
            Map.entry("bash", Set.of("automation", "scripts")),
            Map.entry("py", Set.of("automation", "scripts", "code")),
            Map.entry("md", Set.of("documentation", "meeting-notes", "report", "business", "guide")),
            Map.entry("pdf", Set.of("documentation", "report", "presentation", "invoice", "financial", "contract", "legal")),
            Map.entry("png", Set.of("media", "visual")),
            Map.entry("jpg", Set.of("media", "visual")),
            Map.entry("jpeg", Set.of("media", "visual")),
            Map.entry("gif", Set.of("media", "visual")),
            Map.entry("mp4", Set.of("media", "visual")),
            Map.entry("pptx", Set.of("presentation", "slides")),
            Map.entry("ppt", Set.of("presentation", "slides")),
            Map.entry("docx", Set.of("document")),
            Map.entry("doc", Set.of("document")),
            Map.entry("xlsx", Set.of("spreadsheet", "data")),
            Map.entry("xls", Set.of("spreadsheet", "data")),
            Map.entry("csv", Set.of("data", "spreadsheet")),
            Map.entry("json", Set.of("data", "config")),
            Map.entry("yaml", Set.of("config", "automation")),
            Map.entry("yml", Set.of("config", "automation")),
            Map.entry("sql", Set.of("data", "database")),
            Map.entry("zip", Set.of("archive", "artifact")),
            Map.entry("tar", Set.of("archive", "artifact")),
            Map.entry("gz", Set.of("archive", "artifact"))
    );

    // EXTENSION_REJECT_TYPE_MAP moved to MediaTypes.EXTENSION_REJECT_TYPE_MAP (P1-04)

    private final ScopeChecker scopeChecker;
    private final Path workspaceRoot;

    /**
     * Creates a DirectoryScorer with the given scope checker.
     *
     * @param scopeChecker the scope checker for compatibility and bonus calculations
     */
    public DirectoryScorer(ScopeChecker scopeChecker) {
        this(scopeChecker, null);
    }

    /**
     * Creates a DirectoryScorer with the given scope checker and workspace root.
     *
     * <p>When {@code workspaceRoot} is provided, filename token matching uses
     * the candidate directory's relative path from the workspace root for tokenization.
     * When {@code null}, filename token matching uses only the directory name.
     *
     * @param scopeChecker  the scope checker for compatibility and bonus calculations
     * @param workspaceRoot the workspace root for relative path computation (may be null)
     */
    public DirectoryScorer(ScopeChecker scopeChecker, Path workspaceRoot) {
        this.scopeChecker = scopeChecker;
        this.workspaceRoot = workspaceRoot;
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

            // Hard-reject guard: if the file's inferred types overlap with rejectsTypes, score 0.0
            if (!identity.rejectsTypes().isEmpty() && extension != null && !extension.isEmpty()) {
                Set<String> fileRejectTypes = MediaTypes.EXTENSION_REJECT_TYPE_MAP.get(extension);
                if (fileRejectTypes != null) {
                    boolean rejected = identity.rejectsTypes().stream()
                            .anyMatch(rt -> fileRejectTypes.contains(rt.toLowerCase(Locale.ROOT)));
                    if (rejected) {
                        List<String> rejectReasons = List.of("HARD-REJECTED(rejectsTypes)");
                        results.add(new ScoredCandidate(
                                candidate.directory(), identity,
                                0.0, 0.0, 0.0, true, rejectReasons));
                        continue;
                    }
                }
            }

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
            // Distinguishes specific types (full +0.3) from generic types (+0.15)
            boolean typeMatched = false;
            if (extension != null && !extension.isEmpty()) {
                Set<String> typeKeywords = EXTENSION_TYPE_MAP.get(extension);
                if (typeKeywords != null) {
                    // Find matching types and determine specificity
                    List<String> matchingTypes = identity.acceptsTypes().stream()
                            .filter(t -> typeKeywords.contains(t.toLowerCase(Locale.ROOT)))
                            .toList();
                    if (!matchingTypes.isEmpty()) {
                        boolean hasSpecific = matchingTypes.stream()
                                .anyMatch(t -> !GENERIC_TYPES.contains(t.toLowerCase(Locale.ROOT)));
                        if (hasSpecific) {
                            contentScore += TYPE_MATCH_SCORE;
                            reasons.add("type-match(+" + TYPE_MATCH_SCORE + ")");
                        } else {
                            contentScore += TYPE_MATCH_GENERIC_SCORE;
                            reasons.add("type-match-generic(+" + TYPE_MATCH_GENERIC_SCORE + ")");
                        }
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

            // Filename token match: tokenize the filename and candidate directory path
            // (including aliases from the directory identity), then score based on
            // overlapping tokens (e.g. "synthesis-demo.mp4" vs
            // "products/Synthesis/media" with aliases ["synthesis"] -> "synthesis" matches)
            double tokenMatchScore = computeFilenameTokenScore(fileName, candidate.directory(), identity);
            if (tokenMatchScore > 0.0) {
                contentScore += tokenMatchScore;
                reasons.add(String.format("filename-token-match(+%.3f)", tokenMatchScore));
            }

            contentScore = Math.min(contentScore, MAX_CONTENT_SCORE);

            // Normalize total score to 0.0-1.0 (P1-08):
            // Scope bonus fills a fraction of the remaining headroom above contentScore,
            // so it acts as a tiebreaker without pushing scores above 1.0.
            // Formula: totalScore = contentScore + (scopeBonus * (1.0 - contentScore) * 0.5)
            double normalizedBonus = bonus * (1.0 - contentScore) * 0.5;
            double totalScore = contentScore + normalizedBonus;

            if (blocked) {
                reasons.add("BLOCKED(scope-incompatible)");
            }
            if (bonus > 0.0) {
                reasons.add(String.format("scope-bonus(+%.3f, raw=%.2f)", normalizedBonus, bonus));
            }

            results.add(new ScoredCandidate(
                    candidate.directory(),
                    identity,
                    contentScore,
                    normalizedBonus,
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

    /**
     * Computes the filename-to-directory token overlap score.
     *
     * <p>Tokenizes the filename (without extension) by splitting on {@code -}, {@code _},
     * {@code .}, and spaces, then lowercasing and filtering tokens shorter than 3 characters.
     * Tokenizes the candidate directory's relative path (or absolute path if no workspace root
     * is set) by splitting path segments the same way. Additionally includes tokens from the
     * directory identity's {@link DirectoryIdentity#aliases()}, which provide alternate names
     * for subject-based matching (e.g., a directory at {@code products/Aurora/media} with
     * alias {@code "temporal"} will match files containing "temporal" in their name).
     *
     * <p>Score = (matching tokens / filename tokens) * {@link #FILENAME_TOKEN_MATCH_MAX},
     * capped at {@code FILENAME_TOKEN_MATCH_MAX}.
     *
     * @param fileName  the file name (e.g. {@code "synthesis-demo.mp4"})
     * @param directory the candidate directory path
     * @param identity  the directory identity (aliases contribute to dir tokens)
     * @return score between 0.0 and {@code FILENAME_TOKEN_MATCH_MAX}
     */
    double computeFilenameTokenScore(String fileName, Path directory, DirectoryIdentity identity) {
        // Strip extension for tokenization
        int lastDot = fileName.lastIndexOf('.');
        String nameWithoutExt = lastDot > 0 ? fileName.substring(0, lastDot) : fileName;

        Set<String> fileTokens = tokenize(nameWithoutExt);
        if (fileTokens.isEmpty()) return 0.0;

        // Build directory tokens from the relative or absolute path
        Path pathToTokenize;
        if (workspaceRoot != null && directory.startsWith(workspaceRoot)) {
            pathToTokenize = workspaceRoot.relativize(directory);
        } else {
            pathToTokenize = directory;
        }

        Set<String> dirTokens = new HashSet<>();
        for (int i = 0; i < pathToTokenize.getNameCount(); i++) {
            dirTokens.addAll(tokenize(pathToTokenize.getName(i).toString()));
        }

        // Include alias tokens from the directory identity (P1-05: unified routing)
        if (identity != null) {
            for (String alias : identity.aliases()) {
                dirTokens.addAll(tokenize(alias));
            }
        }

        if (dirTokens.isEmpty()) return 0.0;

        // Count matching tokens
        long matches = fileTokens.stream().filter(dirTokens::contains).count();
        if (matches == 0) return 0.0;

        double ratio = (double) matches / fileTokens.size();
        return Math.min(ratio * FILENAME_TOKEN_MATCH_MAX, FILENAME_TOKEN_MATCH_MAX);
    }

    /**
     * Tokenizes a string by splitting on {@code -}, {@code _}, {@code .}, and spaces.
     * Returns lowercase tokens with length >= 3.
     *
     * @param input the string to tokenize
     * @return set of lowercase tokens (length >= 3)
     */
    static Set<String> tokenize(String input) {
        if (input == null || input.isEmpty()) return Set.of();
        String[] parts = input.toLowerCase(Locale.ROOT).split("[-_. ]+");
        Set<String> tokens = new HashSet<>();
        for (String part : parts) {
            if (part.length() >= 3) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private static String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
