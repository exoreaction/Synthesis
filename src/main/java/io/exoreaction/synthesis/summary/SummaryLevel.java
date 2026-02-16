package io.exoreaction.synthesis.summary;

/**
 * Summary detail level controlling output verbosity and focus.
 *
 * <ul>
 *   <li>EXECUTIVE -- 30-second overview: health, risk, key metrics</li>
 *   <li>MANAGER -- 5-minute briefing: trends, team impacts, action items</li>
 *   <li>DEVELOPER -- Deep technical detail: architecture, dependencies, hotspots</li>
 * </ul>
 */
public enum SummaryLevel {
    EXECUTIVE("executive", "30-second overview"),
    MANAGER("manager", "5-minute briefing"),
    DEVELOPER("developer", "Technical detail");

    private final String cliValue;
    private final String description;

    SummaryLevel(String cliValue, String description) {
        this.cliValue = cliValue;
        this.description = description;
    }

    public String cliValue() { return cliValue; }
    public String description() { return description; }

    public static SummaryLevel fromString(String value) {
        if (value == null) return EXECUTIVE;
        for (SummaryLevel level : values()) {
            if (level.cliValue.equalsIgnoreCase(value)) return level;
        }
        return EXECUTIVE;
    }
}
