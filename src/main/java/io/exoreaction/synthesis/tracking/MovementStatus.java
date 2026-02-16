package io.exoreaction.synthesis.tracking;

/**
 * Lifecycle status of a detected file movement.
 *
 * <pre>
 * DETECTED -> CONFIRMED -> CLEANUP_ELIGIBLE -> CLEANED
 *                |
 *                v
 *            REVERTED (if source file reappears)
 * </pre>
 */
public enum MovementStatus {
    /** Movement detected via hash correlation. Awaiting confirmation. */
    DETECTED("detected"),

    /** Movement confirmed (target file verified to exist). */
    CONFIRMED("confirmed"),

    /** Safety period expired. Source location eligible for cleanup. */
    CLEANUP_ELIGIBLE("cleanup_eligible"),

    /** Source cleanup completed or acknowledged. */
    CLEANED("cleaned"),

    /** Movement was reverted (source file reappeared or target deleted). */
    REVERTED("reverted");

    private final String dbValue;

    MovementStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static MovementStatus fromDbValue(String value) {
        for (MovementStatus s : values()) {
            if (s.dbValue.equals(value)) return s;
        }
        throw new IllegalArgumentException("Unknown movement status: " + value);
    }
}
