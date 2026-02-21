package io.exoreaction.synthesis.org;

import java.nio.file.Path;
import java.util.List;

/**
 * A single bid from a directory for a file.
 *
 * <p>Produced by {@link DirectoryBidder} when a directory's centroid or wants
 * align with a file's enrichment signature. The bid includes the strength of
 * the match and the reasoning chain that explains why the directory wants
 * this file.
 *
 * @param directory      the directory path that is bidding
 * @param strength       bid strength 0.0-1.0 (higher = stronger match)
 * @param membershipType PHYSICAL for the winner, VIRTUAL for runners-up
 * @param reasons        human-readable reasoning chain
 */
public record Bid(
        Path directory,
        double strength,
        MembershipType membershipType,
        List<String> reasons
) {

    /**
     * The type of membership the bid proposes.
     */
    public enum MembershipType {
        /** File should physically reside in this directory. */
        PHYSICAL,
        /** File is virtually indexed into this directory. */
        VIRTUAL,
        /** No meaningful match -- below threshold. */
        NONE
    }

    /**
     * Canonical constructor -- ensures reasons is never null.
     */
    public Bid {
        reasons = reasons != null ? reasons : List.of();
    }
}
