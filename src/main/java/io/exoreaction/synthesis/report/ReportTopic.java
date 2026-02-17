package io.exoreaction.synthesis.report;

/**
 * Report topic controlling which business document types are analyzed.
 *
 * <p>Each topic maps to a different business focus:
 * <ul>
 *   <li>{@link #WEEKLY} -- Full weekly executive report (default)</li>
 *   <li>{@link #PIPELINE} -- Pipeline status and deal tracking only</li>
 *   <li>{@link #ACTIVITIES} -- Recent activities, meetings, and events</li>
 *   <li>{@link #EXECUTIVE} -- Full executive update (all sections)</li>
 *   <li>{@link #DECISIONS} -- Critical decisions needed with options and recommendations</li>
 * </ul>
 */
public enum ReportTopic {

    WEEKLY("weekly", "Weekly Executive Report"),
    PIPELINE("pipeline", "Pipeline Status"),
    ACTIVITIES("activities", "Recent Activities"),
    EXECUTIVE("executive", "Full Executive Update"),
    DECISIONS("decisions", "Critical Decisions"),
    PRODUCT("product", "Product Status Report"),
    CLIENT("client", "Client Status Report");

    private final String cliValue;
    private final String displayName;

    ReportTopic(String cliValue, String displayName) {
        this.cliValue = cliValue;
        this.displayName = displayName;
    }

    public String cliValue() { return cliValue; }
    public String displayName() { return displayName; }

    /**
     * Parses a CLI value to a ReportTopic.
     *
     * @param value the CLI string (e.g., "weekly", "pipeline", "activities")
     * @return the matching topic, defaults to WEEKLY
     */
    public static ReportTopic fromString(String value) {
        if (value == null) return WEEKLY;
        for (ReportTopic topic : values()) {
            if (topic.cliValue.equalsIgnoreCase(value)) return topic;
        }
        return WEEKLY;
    }
}
