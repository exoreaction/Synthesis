package io.exoreaction.synthesis.research;

/**
 * Research topic controlling which domain passes are included.
 *
 * <p>Each topic maps to a different analytical focus:
 * <ul>
 *   <li>{@link #FULL_ANALYSIS} -- All passes (default)</li>
 *   <li>{@link #ARCHITECTURE} -- Module structure, dependencies, patterns</li>
 *   <li>{@link #SECURITY} -- Vulnerability surface, compliance, attack surface</li>
 *   <li>{@link #QUALITY} -- Test coverage, dead code, documentation</li>
 *   <li>{@link #DEPENDENCIES} -- Language distribution, growth trajectory, cross-repo health</li>
 *   <li>{@link #EVOLUTION} -- Naming conventions, technology evaluation, migration opportunities</li>
 * </ul>
 */
public enum ResearchTopic {

    FULL_ANALYSIS("full", "Full Analysis"),
    ARCHITECTURE("architecture", "Architecture"),
    SECURITY("security", "Security & Compliance"),
    QUALITY("quality", "Quality & Testing"),
    DEPENDENCIES("dependencies", "Dependencies & Scale"),
    EVOLUTION("evolution", "Code Patterns & Evolution");

    private final String cliValue;
    private final String displayName;

    ResearchTopic(String cliValue, String displayName) {
        this.cliValue = cliValue;
        this.displayName = displayName;
    }

    public String cliValue() { return cliValue; }
    public String displayName() { return displayName; }

    /**
     * Parses a CLI value to a ResearchTopic.
     *
     * @param value the CLI string (e.g., "full", "architecture", "security")
     * @return the matching topic, defaults to FULL_ANALYSIS
     */
    public static ResearchTopic fromString(String value) {
        if (value == null) return FULL_ANALYSIS;
        for (ResearchTopic topic : values()) {
            if (topic.cliValue.equalsIgnoreCase(value)) return topic;
        }
        return FULL_ANALYSIS;
    }
}
