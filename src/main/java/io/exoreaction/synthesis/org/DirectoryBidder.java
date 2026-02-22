package io.exoreaction.synthesis.org;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pull-based routing: directories bid on enriched files they want.
 *
 * <p>Given a file's {@link EnrichmentSignature} and a collection of directory
 * profiles (each with a centroid and/or wants), computes bid strengths using
 * semantic similarity. The highest bidder wins physical membership; strong
 * runners-up (bid > 0.3) get virtual membership candidates (capped at 3).
 *
 * <p>Bid strength formula:
 * <pre>
 *   topicSim     = jaccard(file.topics, dir.topics) * 0.40
 *   entitySim    = jaccard(file.entities, dir.entities) * 0.45
 *   typeSim      = (file.docType in dir.docTypes) ? 0.10 : 0.0
 *   timeframeSim = timeframeOverlap(file, dir) * 0.05
 *   bidStrength  = (topicSim + entitySim + typeSim + timeframeSim) * dir.confidence
 * </pre>
 *
 * @since v1.15.0 (P3-01)
 */
public class DirectoryBidder {

    /** Minimum bid strength to be considered a virtual membership candidate. */
    static final double VIRTUAL_THRESHOLD = 0.3;

    /** Minimum bid strength to be considered at all (below = orphan). */
    static final double ORPHAN_THRESHOLD = 0.1;

    /** Maximum number of virtual membership candidates per file. */
    static final int MAX_VIRTUAL_MEMBERS = 3;

    // Scoring weights
    static final double TOPIC_WEIGHT = 0.40;
    static final double ENTITY_WEIGHT = 0.45;
    static final double TYPE_WEIGHT = 0.10;
    static final double TIMEFRAME_WEIGHT = 0.05;

    /** Optional routing learner for feedback-adjusted confidence (P4-07). */
    private final RoutingLearner learner;

    /** Workspace path for learner queries (null if no learner). */
    private final String workspacePath;

    /** Creates a bidder without learning (original behavior). */
    public DirectoryBidder() {
        this.learner = null;
        this.workspacePath = null;
    }

    /**
     * Creates a bidder that adjusts confidence based on routing feedback history.
     *
     * @param learner       the routing learner (non-null to enable learning)
     * @param workspacePath workspace path for feedback queries
     */
    public DirectoryBidder(RoutingLearner learner, String workspacePath) {
        this.learner = learner;
        this.workspacePath = workspacePath;
    }

    /**
     * A directory profile entry for bidding: directory path + centroid + wants.
     *
     * @param directory the directory path
     * @param centroid  the directory's semantic centroid
     * @param wants     the directory's wants (used when centroid is weak/empty)
     */
    public record BiddingCandidate(
            Path directory,
            DirectoryCentroid centroid,
            DirectoryWants wants
    ) {}

    /**
     * The result of bidding: ranked bids with a winner and virtual candidates.
     *
     * @param winner             the highest bidder (physical membership), or null if orphan
     * @param virtualCandidates  runners-up above the virtual threshold (max 3)
     * @param allBids            all bids sorted by strength descending
     * @param isOrphan           true if no bid exceeded the orphan threshold
     */
    public record BiddingResult(
            Bid winner,
            List<Bid> virtualCandidates,
            List<Bid> allBids,
            boolean isOrphan
    ) {}

    /**
     * Computes bids for the given file signature against all candidate directories.
     *
     * @param fileSignature the enrichment signature of the file being routed
     * @param candidates    all directories with centroids or wants
     * @return the bidding result with winner, virtual candidates, and orphan status
     */
    public BiddingResult bid(EnrichmentSignature fileSignature, List<BiddingCandidate> candidates) {
        if (fileSignature == null || fileSignature.isEmpty() || candidates == null || candidates.isEmpty()) {
            return new BiddingResult(null, List.of(), List.of(), true);
        }

        List<Bid> allBids = new ArrayList<>();

        for (BiddingCandidate candidate : candidates) {
            Bid bid = computeBid(fileSignature, candidate);
            if (bid.strength() > 0.0) {
                allBids.add(bid);
            }
        }

        // Sort by strength descending
        allBids.sort(Comparator.comparingDouble(Bid::strength).reversed());

        if (allBids.isEmpty() || allBids.get(0).strength() < ORPHAN_THRESHOLD) {
            return new BiddingResult(null, List.of(), allBids, true);
        }

        // Winner gets physical membership
        Bid winner = new Bid(
                allBids.get(0).directory(),
                allBids.get(0).strength(),
                Bid.MembershipType.PHYSICAL,
                allBids.get(0).reasons()
        );

        // Runners-up above virtual threshold get virtual membership (max 3)
        List<Bid> virtualCandidates = allBids.stream()
                .skip(1)
                .filter(b -> b.strength() >= VIRTUAL_THRESHOLD)
                .limit(MAX_VIRTUAL_MEMBERS)
                .map(b -> new Bid(b.directory(), b.strength(), Bid.MembershipType.VIRTUAL, b.reasons()))
                .toList();

        return new BiddingResult(winner, virtualCandidates, allBids, false);
    }

    /**
     * Computes a single bid for a directory against a file signature.
     */
    Bid computeBid(EnrichmentSignature fileSignature, BiddingCandidate candidate) {
        // Determine which topics/entities to use: prefer centroid, fall back to wants
        List<String> dirTopics;
        List<String> dirEntities;
        List<String> dirDocTypes;
        String dirTimeframe;
        double dirConfidence;

        DirectoryCentroid centroid = candidate.centroid();
        DirectoryWants wants = candidate.wants();

        if (centroid != null && !centroid.isEmpty()) {
            dirTopics = centroid.topics();
            dirEntities = centroid.entities();
            dirDocTypes = centroid.documentTypes();
            dirTimeframe = centroid.timeframe();
            dirConfidence = centroid.confidence();
        } else if (wants != null && !wants.isEmpty()) {
            dirTopics = wants.topics();
            dirEntities = wants.entities();
            dirDocTypes = List.of(); // wants don't have doc types
            dirTimeframe = null;
            // Wants-based bidding is weaker (lower confidence)
            dirConfidence = 0.3;
        } else {
            return new Bid(candidate.directory(), 0.0, Bid.MembershipType.NONE, List.of());
        }

        // Apply routing feedback learning adjustment (P4-07)
        if (learner != null && workspacePath != null) {
            try {
                String dirPath = candidate.directory().toString();
                dirConfidence = learner.adjustConfidence(workspacePath, dirPath, dirConfidence);
            } catch (java.sql.SQLException e) {
                // Silently fall back to unadjusted confidence
            }
        }

        List<String> reasons = new ArrayList<>();

        // Topic similarity (Jaccard)
        double topicSim = jaccard(
                toLowerSet(fileSignature.topics()),
                toLowerSet(dirTopics)
        );
        double topicScore = topicSim * TOPIC_WEIGHT;
        if (topicSim > 0.0) {
            Set<String> overlap = intersection(toLowerSet(fileSignature.topics()), toLowerSet(dirTopics));
            reasons.add(String.format("topic-match(%.2f): %s", topicSim, overlap));
        }

        // Entity similarity (Jaccard)
        double entitySim = jaccard(
                toLowerSet(fileSignature.entities()),
                toLowerSet(dirEntities)
        );
        double entityScore = entitySim * ENTITY_WEIGHT;
        if (entitySim > 0.0) {
            Set<String> overlap = intersection(toLowerSet(fileSignature.entities()), toLowerSet(dirEntities));
            reasons.add(String.format("entity-match(%.2f): %s", entitySim, overlap));
        }

        // Document type match
        double typeScore = 0.0;
        if (fileSignature.documentType() != null && !fileSignature.documentType().isEmpty()) {
            boolean typeMatch = dirDocTypes.stream()
                    .anyMatch(dt -> dt.equalsIgnoreCase(fileSignature.documentType()));
            if (typeMatch) {
                typeScore = TYPE_WEIGHT;
                reasons.add("type-match: " + fileSignature.documentType());
            }
        }

        // Timeframe overlap
        double timeframeScore = 0.0;
        if (fileSignature.timeframe() != null && dirTimeframe != null
                && !fileSignature.timeframe().isEmpty() && !dirTimeframe.isEmpty()) {
            double overlap = computeTimeframeOverlap(fileSignature.timeframe(), dirTimeframe);
            timeframeScore = overlap * TIMEFRAME_WEIGHT;
            if (overlap > 0.0) {
                reasons.add(String.format("timeframe-overlap(%.2f)", overlap));
            }
        }

        double rawStrength = topicScore + entityScore + typeScore + timeframeScore;
        double bidStrength = rawStrength * dirConfidence;

        // Clamp to 0.0-1.0
        bidStrength = Math.min(1.0, Math.max(0.0, bidStrength));

        Bid.MembershipType type = bidStrength >= ORPHAN_THRESHOLD
                ? Bid.MembershipType.NONE  // placeholder — assigned properly in bid()
                : Bid.MembershipType.NONE;

        return new Bid(candidate.directory(), bidStrength, type, reasons);
    }

    /**
     * Computes the Jaccard similarity between two sets.
     * Returns 0.0 if both sets are empty.
     */
    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);

        Set<String> union = new HashSet<>(a);
        union.addAll(b);

        return (double) intersection.size() / union.size();
    }

    /**
     * Computes intersection of two sets.
     */
    static <T> Set<T> intersection(Set<T> a, Set<T> b) {
        Set<T> result = new HashSet<>(a);
        result.retainAll(b);
        return result;
    }

    /**
     * Converts a list of strings to a lowercase set for case-insensitive comparison.
     */
    static Set<String> toLowerSet(List<String> items) {
        if (items == null || items.isEmpty()) return Set.of();
        return items.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    /**
     * Computes timeframe overlap between a file timeframe and a directory timeframe.
     *
     * <p>Simple heuristic: checks if the quarter/year tokens overlap.
     * Returns 1.0 for exact match, 0.5 for partial overlap, 0.0 for no overlap.
     */
    static double computeTimeframeOverlap(String fileTimeframe, String dirTimeframe) {
        if (fileTimeframe == null || dirTimeframe == null) return 0.0;

        Set<String> fileTokens = tokenizeTimeframe(fileTimeframe);
        Set<String> dirTokens = tokenizeTimeframe(dirTimeframe);

        if (fileTokens.isEmpty() || dirTokens.isEmpty()) return 0.0;

        Set<String> overlap = new HashSet<>(fileTokens);
        overlap.retainAll(dirTokens);

        if (overlap.isEmpty()) return 0.0;
        if (overlap.equals(fileTokens) || overlap.equals(dirTokens)) return 1.0;
        return 0.5;
    }

    /**
     * Tokenizes a timeframe string like "2025-Q4 / 2026-Q1" into tokens
     * like {"2025", "q4", "2026", "q1"}.
     */
    static Set<String> tokenizeTimeframe(String timeframe) {
        if (timeframe == null) return Set.of();
        return Arrays.stream(timeframe.toLowerCase().split("[\\s/\\-]+"))
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toSet());
    }
}
