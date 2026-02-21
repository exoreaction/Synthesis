package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DirectoryBidder} -- the pull-based routing mechanism.
 */
class DirectoryBidderTest {

    private final DirectoryBidder bidder = new DirectoryBidder();

    // ---- Helper factories ----

    static DirectoryCentroid centroid(List<String> topics, List<String> entities,
                                      List<String> docTypes, double confidence) {
        return new DirectoryCentroid(topics, entities, "2026-Q1", docTypes,
                confidence, 5, 0, Instant.now());
    }

    static DirectoryWants wants(List<String> topics, List<String> entities) {
        return new DirectoryWants(topics, entities, List.of(),
                "inferred from directory name", 0.0);
    }

    static EnrichmentSignature signature(List<String> topics, List<String> entities,
                                          String docType, String timeframe) {
        return new EnrichmentSignature(topics, entities, docType, timeframe, "companion");
    }

    static DirectoryBidder.BiddingCandidate candidate(String dirName,
                                                       DirectoryCentroid centroid,
                                                       DirectoryWants wants) {
        return new DirectoryBidder.BiddingCandidate(
                Path.of("/workspace/" + dirName), centroid, wants);
    }

    // ---- Jaccard similarity ----

    @Test
    void jaccard_identicalSets_returns1() {
        Set<String> a = Set.of("renewable energy", "sdd");
        assertEquals(1.0, DirectoryBidder.jaccard(a, a));
    }

    @Test
    void jaccard_disjointSets_returns0() {
        Set<String> a = Set.of("renewable energy");
        Set<String> b = Set.of("marketing");
        assertEquals(0.0, DirectoryBidder.jaccard(a, b));
    }

    @Test
    void jaccard_partialOverlap() {
        Set<String> a = Set.of("energy", "sdd", "workshop");
        Set<String> b = Set.of("energy", "marketing");
        // intersection=1 (energy), union=4 (energy, sdd, workshop, marketing)
        assertEquals(1.0 / 4.0, DirectoryBidder.jaccard(a, b), 0.001);
    }

    @Test
    void jaccard_emptySets_returns0() {
        assertEquals(0.0, DirectoryBidder.jaccard(Set.of(), Set.of()));
    }

    @Test
    void jaccard_oneEmpty_returns0() {
        assertEquals(0.0, DirectoryBidder.jaccard(Set.of("a"), Set.of()));
    }

    // ---- Timeframe overlap ----

    @Test
    void timeframeOverlap_exactMatch() {
        assertEquals(1.0, DirectoryBidder.computeTimeframeOverlap("2026-Q1", "2026-Q1"));
    }

    @Test
    void timeframeOverlap_partialMatch() {
        // "2026-Q1" tokens: {2026, q1}  vs "2025-Q4 / 2026-Q1" tokens: {2025, q4, 2026, q1}
        // overlap: {2026, q1} = fileTokens fully contained => 1.0
        assertEquals(1.0, DirectoryBidder.computeTimeframeOverlap("2026-Q1", "2025-Q4 / 2026-Q1"));
    }

    @Test
    void timeframeOverlap_noOverlap() {
        assertEquals(0.0, DirectoryBidder.computeTimeframeOverlap("2024-Q2", "2026-Q1"));
    }

    @Test
    void timeframeOverlap_nulls() {
        assertEquals(0.0, DirectoryBidder.computeTimeframeOverlap(null, "2026-Q1"));
        assertEquals(0.0, DirectoryBidder.computeTimeframeOverlap("2026-Q1", null));
    }

    // ---- toLowerSet ----

    @Test
    void toLowerSet_normalizesCaseAndDeduplicates() {
        List<String> input = List.of("Energy", "SDD", "energy");
        Set<String> result = DirectoryBidder.toLowerSet(input);
        assertEquals(Set.of("energy", "sdd"), result);
    }

    @Test
    void toLowerSet_nullAndEmpty() {
        assertEquals(Set.of(), DirectoryBidder.toLowerSet(null));
        assertEquals(Set.of(), DirectoryBidder.toLowerSet(List.of()));
    }

    // ---- Bidding: winner selection ----

    @Test
    void bid_singleCandidate_withStrongMatch_winsPhysical() {
        EnrichmentSignature sig = signature(
                List.of("renewable energy", "SDD"),
                List.of("GreenField Energy"),
                "proposal",
                "2026-Q1"
        );

        DirectoryCentroid greenfieldCentroid = centroid(
                List.of("renewable energy", "SDD methodology"),
                List.of("GreenField Energy", "Jane Smith"),
                List.of("proposal", "contract"),
                0.87
        );

        List<DirectoryBidder.BiddingCandidate> candidates = List.of(
                candidate("clients/opportunity-greenfield", greenfieldCentroid, DirectoryWants.empty())
        );

        DirectoryBidder.BiddingResult result = bidder.bid(sig, candidates);

        assertFalse(result.isOrphan());
        assertNotNull(result.winner());
        assertEquals(Bid.MembershipType.PHYSICAL, result.winner().membershipType());
        assertTrue(result.winner().strength() > 0.1, "Winner should have meaningful strength");
    }

    @Test
    void bid_multipleCompetingCandidates_highestWins() {
        EnrichmentSignature sig = signature(
                List.of("renewable energy", "SDD"),
                List.of("GreenField Energy"),
                "proposal",
                "2026-Q1"
        );

        DirectoryCentroid strongMatch = centroid(
                List.of("renewable energy", "SDD"),
                List.of("GreenField Energy"),
                List.of("proposal"),
                0.9
        );

        DirectoryCentroid weakMatch = centroid(
                List.of("marketing"),
                List.of("Other Corp"),
                List.of("brochure"),
                0.8
        );

        List<DirectoryBidder.BiddingCandidate> candidates = List.of(
                candidate("clients/opportunity-greenfield", strongMatch, DirectoryWants.empty()),
                candidate("marketing/brochures", weakMatch, DirectoryWants.empty())
        );

        DirectoryBidder.BiddingResult result = bidder.bid(sig, candidates);

        assertNotNull(result.winner());
        assertTrue(result.winner().directory().toString().contains("greenfield"),
                "Stronger match should win");
    }

    @Test
    void bid_runnersUp_getVirtualMembership() {
        EnrichmentSignature sig = signature(
                List.of("renewable energy", "SDD methodology", "workshop"),
                List.of("GreenField Energy"),
                "proposal",
                "2026-Q1"
        );

        DirectoryCentroid clientCentroid = centroid(
                List.of("renewable energy", "GreenField partnership"),
                List.of("GreenField Energy"),
                List.of("proposal", "contract"),
                0.9
        );

        DirectoryCentroid methodologyCentroid = centroid(
                List.of("SDD methodology", "workshop delivery"),
                List.of("eXOReaction"),
                List.of("guide", "proposal"),
                0.85
        );

        DirectoryCentroid workshopCentroid = centroid(
                List.of("workshop", "training", "SDD methodology"),
                List.of("eXOReaction"),
                List.of("curriculum", "proposal"),
                0.8
        );

        List<DirectoryBidder.BiddingCandidate> candidates = List.of(
                candidate("clients/opportunity-greenfield", clientCentroid, DirectoryWants.empty()),
                candidate("methodology/sdd", methodologyCentroid, DirectoryWants.empty()),
                candidate("products/workshop", workshopCentroid, DirectoryWants.empty())
        );

        DirectoryBidder.BiddingResult result = bidder.bid(sig, candidates);

        assertNotNull(result.winner());
        assertEquals(Bid.MembershipType.PHYSICAL, result.winner().membershipType());

        // Virtual candidates should be present for strong runners-up
        for (Bid virtualBid : result.virtualCandidates()) {
            assertEquals(Bid.MembershipType.VIRTUAL, virtualBid.membershipType());
            assertTrue(virtualBid.strength() >= DirectoryBidder.VIRTUAL_THRESHOLD,
                    "Virtual candidate should be above threshold");
        }
    }

    @Test
    void bid_virtualCandidates_cappedAtMaxThree() {
        EnrichmentSignature sig = signature(
                List.of("energy", "sdd", "workshop", "marketing", "consulting"),
                List.of("GreenField"),
                "report",
                "2026-Q1"
        );

        // Create 5 moderately matching directories
        List<DirectoryBidder.BiddingCandidate> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            DirectoryCentroid c = centroid(
                    List.of("energy", "sdd", "consulting"),
                    List.of("GreenField"),
                    List.of("report"),
                    0.7 + i * 0.01
            );
            candidates.add(candidate("dir-" + i, c, DirectoryWants.empty()));
        }

        DirectoryBidder.BiddingResult result = bidder.bid(sig, candidates);

        assertTrue(result.virtualCandidates().size() <= DirectoryBidder.MAX_VIRTUAL_MEMBERS,
                "Virtual candidates should be capped at " + DirectoryBidder.MAX_VIRTUAL_MEMBERS);
    }

    // ---- Orphan detection ----

    @Test
    void bid_noMatchingCandidates_isOrphan() {
        EnrichmentSignature sig = signature(
                List.of("quantum computing"),
                List.of("IBM Research"),
                "paper",
                "2026-Q1"
        );

        DirectoryCentroid unrelatedCentroid = centroid(
                List.of("renewable energy"),
                List.of("GreenField Energy"),
                List.of("proposal"),
                0.9
        );

        List<DirectoryBidder.BiddingCandidate> candidates = List.of(
                candidate("clients/greenfield", unrelatedCentroid, DirectoryWants.empty())
        );

        DirectoryBidder.BiddingResult result = bidder.bid(sig, candidates);

        assertTrue(result.isOrphan(), "Completely unrelated file should be orphan");
        assertNull(result.winner());
    }

    @Test
    void bid_emptySignature_isOrphan() {
        DirectoryBidder.BiddingResult result = bidder.bid(
                EnrichmentSignature.empty(),
                List.of(candidate("dir", centroid(List.of("a"), List.of("b"), List.of(), 0.9),
                        DirectoryWants.empty()))
        );
        assertTrue(result.isOrphan());
    }

    @Test
    void bid_emptyCandidates_isOrphan() {
        DirectoryBidder.BiddingResult result = bidder.bid(
                signature(List.of("a"), List.of("b"), "c", "2026"),
                List.of()
        );
        assertTrue(result.isOrphan());
    }

    @Test
    void bid_nullInputs_isOrphan() {
        assertTrue(bidder.bid(null, List.of()).isOrphan());
        assertTrue(bidder.bid(signature(List.of("a"), List.of(), null, null), null).isOrphan());
    }

    // ---- Wants-based bidding ----

    @Test
    void bid_wantsBasedCandidate_bidsWeakerThanCentroidBased() {
        EnrichmentSignature sig = signature(
                List.of("Nova Corp", "cloud infrastructure"),
                List.of("Nova Corp"),
                "proposal",
                "2026-Q1"
        );

        DirectoryCentroid strongCentroid = centroid(
                List.of("Nova Corp", "cloud infrastructure"),
                List.of("Nova Corp"),
                List.of("proposal"),
                0.85
        );

        DirectoryWants novaWants = wants(
                List.of("Nova Corp", "cloud infrastructure"),
                List.of("Nova Corp")
        );

        List<DirectoryBidder.BiddingCandidate> candidates = List.of(
                candidate("clients/nova-mature", strongCentroid, DirectoryWants.empty()),
                candidate("clients/nova-new", DirectoryCentroid.empty(), novaWants)
        );

        DirectoryBidder.BiddingResult result = bidder.bid(sig, candidates);

        // The centroid-based candidate should beat the wants-based one
        assertNotNull(result.winner());
        assertTrue(result.winner().directory().toString().contains("nova-mature"),
                "Centroid-based directory should outbid wants-based directory");
    }

    @Test
    void bid_wantsOnlyCandidate_canStillWin() {
        EnrichmentSignature sig = signature(
                List.of("Nova Corp", "CTO partnership"),
                List.of("Nova Corp"),
                "meeting-notes",
                "2026-Q1"
        );

        DirectoryWants novaWants = wants(
                List.of("Nova Corp", "CTO partnership"),
                List.of("Nova Corp")
        );

        List<DirectoryBidder.BiddingCandidate> candidates = List.of(
                candidate("clients/opportunity-nova", DirectoryCentroid.empty(), novaWants)
        );

        DirectoryBidder.BiddingResult result = bidder.bid(sig, candidates);

        // Wants-only candidate should still win when it's the only match
        assertFalse(result.isOrphan(), "Wants-only candidate should still bid");
        assertNotNull(result.winner());
    }

    // ---- Reasoning chains ----

    @Test
    void bid_winner_hasReasons() {
        EnrichmentSignature sig = signature(
                List.of("renewable energy"),
                List.of("GreenField Energy"),
                "proposal",
                "2026-Q1"
        );

        DirectoryCentroid c = centroid(
                List.of("renewable energy"),
                List.of("GreenField Energy"),
                List.of("proposal"),
                0.9
        );

        DirectoryBidder.BiddingResult result = bidder.bid(sig,
                List.of(candidate("clients/greenfield", c, DirectoryWants.empty())));

        assertNotNull(result.winner());
        assertFalse(result.winner().reasons().isEmpty(),
                "Winner should have reasoning chain");
    }

    // ---- Case insensitivity ----

    @Test
    void bid_caseInsensitive_topicsAndEntities() {
        EnrichmentSignature sig = signature(
                List.of("Renewable Energy"),
                List.of("greenfield energy"),
                "proposal",
                null
        );

        DirectoryCentroid c = centroid(
                List.of("renewable energy"),
                List.of("GreenField Energy"),
                List.of("proposal"),
                0.9
        );

        DirectoryBidder.BiddingResult result = bidder.bid(sig,
                List.of(candidate("dir", c, DirectoryWants.empty())));

        assertFalse(result.isOrphan(), "Case-insensitive matching should find match");
    }

    // ---- Confidence scaling ----

    @Test
    void bid_lowConfidenceCentroid_producesWeakerBid() {
        EnrichmentSignature sig = signature(
                List.of("energy"),
                List.of("Corp"),
                null,
                null
        );

        DirectoryCentroid highConf = centroid(
                List.of("energy"),
                List.of("Corp"),
                List.of(),
                0.9
        );

        DirectoryCentroid lowConf = centroid(
                List.of("energy"),
                List.of("Corp"),
                List.of(),
                0.3
        );

        Bid highBid = bidder.computeBid(sig,
                new DirectoryBidder.BiddingCandidate(Path.of("/high"), highConf, DirectoryWants.empty()));
        Bid lowBid = bidder.computeBid(sig,
                new DirectoryBidder.BiddingCandidate(Path.of("/low"), lowConf, DirectoryWants.empty()));

        assertTrue(highBid.strength() > lowBid.strength(),
                "Higher confidence centroid should produce stronger bid");
    }

    // ---- allBids sorted ----

    @Test
    void bid_allBids_sortedByStrengthDescending() {
        EnrichmentSignature sig = signature(
                List.of("energy", "sdd"),
                List.of("GreenField"),
                "report",
                "2026"
        );

        List<DirectoryBidder.BiddingCandidate> candidates = List.of(
                candidate("dir-a", centroid(List.of("energy"), List.of("Other"), List.of(), 0.5), DirectoryWants.empty()),
                candidate("dir-b", centroid(List.of("energy", "sdd"), List.of("GreenField"), List.of("report"), 0.9), DirectoryWants.empty()),
                candidate("dir-c", centroid(List.of("sdd"), List.of(), List.of(), 0.7), DirectoryWants.empty())
        );

        DirectoryBidder.BiddingResult result = bidder.bid(sig, candidates);

        List<Bid> allBids = result.allBids();
        for (int i = 1; i < allBids.size(); i++) {
            assertTrue(allBids.get(i - 1).strength() >= allBids.get(i).strength(),
                    "Bids should be sorted by strength descending");
        }
    }

    // ---- Tokenize timeframe ----

    @Test
    void tokenizeTimeframe_parsesQuarterRange() {
        Set<String> tokens = DirectoryBidder.tokenizeTimeframe("2025-Q4 / 2026-Q1");
        assertTrue(tokens.contains("2025"));
        assertTrue(tokens.contains("q4"));
        assertTrue(tokens.contains("2026"));
        assertTrue(tokens.contains("q1"));
    }

    @Test
    void tokenizeTimeframe_null_returnsEmpty() {
        assertEquals(Set.of(), DirectoryBidder.tokenizeTimeframe(null));
    }
}
