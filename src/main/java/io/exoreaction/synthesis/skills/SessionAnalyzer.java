package io.exoreaction.synthesis.skills;

import io.exoreaction.synthesis.sessions.ClaudeSession;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Extracts skill-worthy patterns from Claude Code session transcripts using
 * heuristic analysis. No LLM calls — all extraction is rule-based.
 *
 * <p>Patterns are identified by scanning user text for corrections, explicit
 * rules, domain terms, tool combinations, and workflow steps. Fragments from
 * multiple sessions are clustered by keyword overlap, and clusters that appear
 * across 2+ sessions receive a confidence boost.
 *
 * <p>The output is a list of {@link ExtractedPattern} records suitable for
 * passing to {@link SkillUpdater#apply} to create or update skill YAML files.
 */
public class SessionAnalyzer {

    // -----------------------------------------------------------------------
    // Public record types
    // -----------------------------------------------------------------------

    /**
     * A pattern extracted from one or more sessions, ready for skill generation.
     */
    public record ExtractedPattern(
            String patternId,
            String suggestedName,
            String description,
            List<String> triggerPhrases,
            List<String> instructions,
            List<String> tags,
            int sessionCount,
            double confidence
    ) {}

    /**
     * A raw fragment extracted from a single session before clustering.
     */
    record RawFragment(
            String text,
            FragmentType type,
            String sessionId,
            Set<String> keywords
    ) {}

    /**
     * Classification of a raw fragment.
     */
    enum FragmentType {
        CORRECTION,
        EXPLICIT_RULE,
        DOMAIN_TERM,
        TOOL_PATTERN,
        WORKFLOW_STEP
    }

    // -----------------------------------------------------------------------
    // Confidence baselines
    // -----------------------------------------------------------------------

    private static final double CORRECTION_CONFIDENCE = 0.85;
    private static final double EXPLICIT_RULE_CONFIDENCE = 0.75;
    private static final double DOMAIN_TERM_CONFIDENCE = 0.4;
    private static final double TOOL_PATTERN_CONFIDENCE = 0.5;
    private static final double WORKFLOW_STEP_CONFIDENCE = 0.6;
    private static final double MULTI_SESSION_BOOST = 0.2;
    private static final double JACCARD_THRESHOLD = 0.3;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Analyzes sessions and returns patterns with confidence >= {@code minConfidence}.
     *
     * @param sessions      the sessions to analyze
     * @param minConfidence minimum confidence threshold (0.0 - 1.0)
     * @return extracted patterns sorted by confidence descending
     */
    public static List<ExtractedPattern> analyze(List<ClaudeSession> sessions, double minConfidence) {
        if (sessions == null || sessions.isEmpty()) return List.of();

        List<RawFragment> allFragments = new ArrayList<>();
        for (ClaudeSession session : sessions) {
            allFragments.addAll(extractFragments(session));
        }

        if (allFragments.isEmpty()) return List.of();

        return clusterFragments(allFragments, minConfidence);
    }

    // -----------------------------------------------------------------------
    // Fragment extraction
    // -----------------------------------------------------------------------

    /**
     * Extracts raw fragments from a single session by scanning user text.
     */
    static List<RawFragment> extractFragments(ClaudeSession session) {
        List<RawFragment> fragments = new ArrayList<>();
        if (session == null) return fragments;

        String userText = session.allUserText();
        if (userText == null || userText.isBlank()) return fragments;

        String sessionId = session.sessionId();

        // Split into sentences (simple heuristic: split on ". ", "! ", "? ", or newline)
        String[] sentences = userText.split("(?<=[.!?])\\s+|\\n+");

        for (String raw : sentences) {
            String sentence = raw.strip();
            if (sentence.length() < 5) continue;
            String lower = sentence.toLowerCase();

            // CORRECTION patterns
            if (startsWithAny(lower, "no,", "not like that", "wrong", "instead,",
                    "actually,", "don't", "stop", "i said", "i meant")) {
                Set<String> kw = tokenize(sentence);
                fragments.add(new RawFragment(sentence, FragmentType.CORRECTION, sessionId, kw));
            }

            // EXPLICIT_RULE patterns
            if (containsAny(lower, " always ", " never ", " must ", " should ",
                    "important:", "rule:", "convention:", "pattern:", " prefer ", " avoid ")) {
                Set<String> kw = tokenize(sentence);
                fragments.add(new RawFragment(sentence, FragmentType.EXPLICIT_RULE, sessionId, kw));
            }

            // WORKFLOW_STEP patterns
            if (containsAny(lower, "first,", "then,", "next,", "after that,",
                    "finally,", "step 1", "step 2", "step 3")) {
                Set<String> kw = tokenize(sentence);
                fragments.add(new RawFragment(sentence, FragmentType.WORKFLOW_STEP, sessionId, kw));
            }
        }

        // DOMAIN_TERM patterns: frequent tokens and bigrams
        fragments.addAll(extractDomainTerms(userText, sessionId));

        // TOOL_PATTERN: combinations of 3+ distinct tools
        if (session.toolNames() != null && session.toolNames().size() >= 3) {
            String toolText = "Tool workflow: " + String.join(", ", session.toolNames());
            Set<String> kw = new HashSet<>(session.toolNames().stream()
                    .map(String::toLowerCase).toList());
            kw.addAll(tokenize(toolText));
            fragments.add(new RawFragment(toolText, FragmentType.TOOL_PATTERN, sessionId, kw));
        }

        return fragments;
    }

    /**
     * Extracts domain terms from text: single tokens appearing 3+ times,
     * bigrams appearing 2+ times.
     */
    private static List<RawFragment> extractDomainTerms(String text, String sessionId) {
        List<RawFragment> fragments = new ArrayList<>();
        Set<String> tokens = tokenize(text);

        // Count single-token frequencies
        String[] words = text.toLowerCase().split("[^a-z0-9]+");
        Map<String, Integer> freq = new HashMap<>();
        for (String w : words) {
            if (w.length() >= 3 && !STOP_WORDS.contains(w)) {
                freq.merge(w, 1, Integer::sum);
            }
        }

        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() >= 3) {
                fragments.add(new RawFragment(
                        e.getKey(),
                        FragmentType.DOMAIN_TERM,
                        sessionId,
                        Set.of(e.getKey())
                ));
            }
        }

        // Count bigram frequencies
        Map<String, Integer> bigramFreq = new HashMap<>();
        for (int i = 0; i < words.length - 1; i++) {
            String w1 = words[i];
            String w2 = words[i + 1];
            if (w1.length() >= 3 && w2.length() >= 3
                    && !STOP_WORDS.contains(w1) && !STOP_WORDS.contains(w2)) {
                bigramFreq.merge(w1 + " " + w2, 1, Integer::sum);
            }
        }

        for (Map.Entry<String, Integer> e : bigramFreq.entrySet()) {
            if (e.getValue() >= 2) {
                String[] parts = e.getKey().split(" ");
                fragments.add(new RawFragment(
                        e.getKey(),
                        FragmentType.DOMAIN_TERM,
                        sessionId,
                        new HashSet<>(List.of(parts[0], parts[1]))
                ));
            }
        }

        return fragments;
    }

    // -----------------------------------------------------------------------
    // Clustering
    // -----------------------------------------------------------------------

    /**
     * Clusters fragments by keyword overlap and produces extracted patterns.
     * Fragments from 2+ sessions receive a confidence boost.
     */
    static List<ExtractedPattern> clusterFragments(List<RawFragment> fragments, double minConfidence) {
        // Simple greedy clustering by Jaccard similarity
        List<List<RawFragment>> clusters = new ArrayList<>();
        boolean[] assigned = new boolean[fragments.size()];

        for (int i = 0; i < fragments.size(); i++) {
            if (assigned[i]) continue;
            List<RawFragment> cluster = new ArrayList<>();
            cluster.add(fragments.get(i));
            assigned[i] = true;

            for (int j = i + 1; j < fragments.size(); j++) {
                if (assigned[j]) continue;
                if (jaccardSimilarity(fragments.get(i).keywords(), fragments.get(j).keywords())
                        >= JACCARD_THRESHOLD) {
                    cluster.add(fragments.get(j));
                    assigned[j] = true;
                }
            }
            clusters.add(cluster);
        }

        // Convert clusters to patterns
        List<ExtractedPattern> patterns = new ArrayList<>();
        for (List<RawFragment> cluster : clusters) {
            ExtractedPattern pattern = buildPattern(cluster);
            if (pattern.confidence() >= minConfidence) {
                patterns.add(pattern);
            }
        }

        // Sort by confidence descending
        patterns.sort(Comparator.comparingDouble(ExtractedPattern::confidence).reversed());
        return patterns;
    }

    private static ExtractedPattern buildPattern(List<RawFragment> cluster) {
        // Collect unique session IDs
        Set<String> sessionIds = cluster.stream()
                .map(RawFragment::sessionId)
                .collect(Collectors.toSet());

        // Base confidence from the highest-confidence fragment type
        double baseConfidence = cluster.stream()
                .mapToDouble(f -> confidenceFor(f.type()))
                .max().orElse(0.0);

        // Multi-session boost
        if (sessionIds.size() >= 2) {
            baseConfidence = Math.min(1.0, baseConfidence + MULTI_SESSION_BOOST);
        }

        // Collect all keywords sorted for stable ID generation
        Set<String> allKeywords = new TreeSet<>();
        cluster.forEach(f -> allKeywords.addAll(f.keywords()));

        // Pattern ID: hex hash of sorted keywords
        String patternId = Integer.toHexString(allKeywords.hashCode());

        // Suggested name from top-2 keywords
        List<String> topKeywords = allKeywords.stream().limit(2).toList();
        String suggestedName = String.join("-", topKeywords)
                .replaceAll("[^a-z0-9-]", "");
        if (suggestedName.isBlank()) suggestedName = "pattern-" + patternId;

        // Description from the highest-confidence fragment text
        RawFragment bestFragment = cluster.stream()
                .max(Comparator.comparingDouble(f -> confidenceFor(f.type())))
                .orElse(cluster.get(0));
        String description = bestFragment.text();
        if (description.length() > 100) {
            description = description.substring(0, 100);
        }

        // Trigger phrases: unique non-DOMAIN_TERM fragment texts
        List<String> triggerPhrases = cluster.stream()
                .filter(f -> f.type() != FragmentType.DOMAIN_TERM)
                .map(RawFragment::text)
                .distinct()
                .limit(5)
                .toList();

        // Instructions: all fragment texts
        List<String> instructions = cluster.stream()
                .map(RawFragment::text)
                .distinct()
                .limit(10)
                .toList();

        // Tags: all keywords as tags
        List<String> tags = new ArrayList<>(allKeywords);

        return new ExtractedPattern(
                patternId,
                suggestedName,
                description,
                triggerPhrases,
                instructions,
                tags,
                sessionIds.size(),
                baseConfidence
        );
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static double confidenceFor(FragmentType type) {
        return switch (type) {
            case CORRECTION -> CORRECTION_CONFIDENCE;
            case EXPLICIT_RULE -> EXPLICIT_RULE_CONFIDENCE;
            case WORKFLOW_STEP -> WORKFLOW_STEP_CONFIDENCE;
            case TOOL_PATTERN -> TOOL_PATTERN_CONFIDENCE;
            case DOMAIN_TERM -> DOMAIN_TERM_CONFIDENCE;
        };
    }

    static double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static boolean startsWithAny(String text, String... prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean containsAny(String text, String... substrings) {
        for (String sub : substrings) {
            if (text.contains(sub)) return true;
        }
        return false;
    }

    /**
     * Tokenizes text into lowercase keywords, filtering stop words and short tokens.
     * Uses the same approach as {@link SkillMatcher#tokenise}.
     */
    static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> tokens = new HashSet<>();
        for (String word : text.toLowerCase().split("[^a-z0-9]+")) {
            if (word.length() >= 3 && !STOP_WORDS.contains(word)) {
                tokens.add(word);
            }
        }
        return tokens;
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "this", "that", "are", "was",
            "all", "can", "use", "used", "how", "what", "when", "where", "which",
            "not", "but", "you", "your", "its", "has", "have", "will", "each",
            "also", "been", "just", "more", "than", "then", "them", "they",
            "into", "some", "such", "only", "other", "our", "out", "too"
    );
}
