package io.exoreaction.synthesis.org;

import java.time.Instant;

/**
 * A forwarding pointer left behind when a file is migrated from a transient directory
 * to its permanent home. Stored in the source directory's {@code .synthesis.md}.
 *
 * @param fileName the original file name
 * @param movedTo  relative path from workspace root to the destination
 * @param movedAt  timestamp when the file was moved
 * @param movedBy  the operation that moved the file (e.g. "rebalance", "sweep", "manual")
 * @param reason   human-readable reason for the move
 */
public record ForwardingPointer(
        String fileName,
        String movedTo,
        Instant movedAt,
        String movedBy,
        String reason
) {}
