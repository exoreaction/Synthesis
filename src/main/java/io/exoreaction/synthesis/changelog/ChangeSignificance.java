package io.exoreaction.synthesis.changelog;

/**
 * Significance level of a detected change.
 * Used to filter noise from executive reports.
 */
public enum ChangeSignificance {
    /** Internal/generated files, temp files, build artifacts. */
    NOISE("noise"),

    /** Standard document or code changes. */
    NORMAL("normal"),

    /** New directories, README changes, large files, config changes. */
    NOTABLE("notable"),

    /** Security files, mass deletions, credential changes. */
    CRITICAL("critical");

    private final String dbValue;

    ChangeSignificance(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static ChangeSignificance fromDbValue(String value) {
        if (value == null) return NORMAL;
        for (ChangeSignificance s : values()) {
            if (s.dbValue.equals(value)) return s;
        }
        return NORMAL;
    }

    /**
     * Returns true if this significance is at least as important as the given minimum.
     */
    public boolean isAtLeast(ChangeSignificance minimum) {
        return this.ordinal() >= minimum.ordinal();
    }
}
