package io.exoreaction.synthesis.report;

/**
 * Target audience for executive report generation.
 *
 * <p>Each target controls the level of detail, formality, and focus:
 * <ul>
 *   <li>{@link #CEO} -- Actionable executive summary with pipeline, activities, decisions</li>
 *   <li>{@link #BOARD} -- Formal board-ready format with strategic focus, less operational detail</li>
 *   <li>{@link #INVESTOR} -- Investor-oriented with market positioning, metrics, and growth signals</li>
 * </ul>
 */
public enum ReportTarget {

    CEO("ceo", "CEO Executive Report",
            "Actionable executive summary with pipeline status, activities, decisions, and next steps"),

    BOARD("board", "Board Report",
            "Formal board-ready format with strategic focus and high-level metrics"),

    INVESTOR("investor", "Investor Report",
            "Investor-oriented with market positioning, traction metrics, and growth signals");

    private final String cliValue;
    private final String displayName;
    private final String description;

    ReportTarget(String cliValue, String displayName, String description) {
        this.cliValue = cliValue;
        this.displayName = displayName;
        this.description = description;
    }

    public String cliValue() { return cliValue; }
    public String displayName() { return displayName; }
    public String description() { return description; }

    /**
     * Parses a CLI value to a ReportTarget.
     *
     * @param value the CLI string (e.g., "ceo", "board", "investor")
     * @return the matching target, defaults to CEO
     */
    public static ReportTarget fromString(String value) {
        if (value == null) return CEO;
        for (ReportTarget target : values()) {
            if (target.cliValue.equalsIgnoreCase(value)) return target;
        }
        return CEO;
    }

    /**
     * Parses a CLI value to a ReportTarget, throwing on invalid input.
     *
     * @param value the CLI string
     * @return the matching target
     * @throws IllegalArgumentException if value does not match any target
     */
    public static ReportTarget fromStringStrict(String value) {
        if (value == null) throw new IllegalArgumentException("Target value must not be null");
        for (ReportTarget target : values()) {
            if (target.cliValue.equalsIgnoreCase(value)) return target;
        }
        throw new IllegalArgumentException(
                "Unknown report target: '" + value + "'. Valid targets: ceo, board, investor");
    }
}
