package io.exoreaction.synthesis.tracking;

/**
 * How a file movement was detected.
 */
public enum DetectionMethod {
    /** Hash matched between deleted file and newly added file across workspaces. */
    HASH_MATCH("hash_match"),

    /** Real-time detection via WatchService events. */
    WATCH_EVENT("watch_event"),

    /** Manually recorded by user. */
    MANUAL("manual");

    private final String dbValue;

    DetectionMethod(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static DetectionMethod fromDbValue(String value) {
        for (DetectionMethod m : values()) {
            if (m.dbValue.equals(value)) return m;
        }
        throw new IllegalArgumentException("Unknown detection method: " + value);
    }
}
