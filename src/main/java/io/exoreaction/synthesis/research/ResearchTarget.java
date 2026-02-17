package io.exoreaction.synthesis.research;

/**
 * Target AI tool for research report generation.
 *
 * <p>Each target has different output characteristics:
 * <ul>
 *   <li>{@link #CHATGPT_DEEP_RESEARCH} -- Structured research doc (15K-25K tokens)</li>
 *   <li>{@link #NOTEBOOKLM_INFOGRAPHIC} -- Exhaustive data dump (30K-60K tokens)</li>
 *   <li>{@link #NOTEBOOKLM_PRESENTATION} -- Narrative chapters (20K-40K tokens)</li>
 * </ul>
 */
public enum ResearchTarget {

    CHATGPT_DEEP_RESEARCH("chatgpt", "ChatGPT Deep Research",
            "Structured research document with executive summary, methodology, risk matrix, and research questions"),

    NOTEBOOKLM_INFOGRAPHIC("notebooklm-infographic", "NotebookLM Infographic",
            "Exhaustive data dump optimized for visualization with complete file inventory and dependency maps"),

    NOTEBOOKLM_PRESENTATION("notebooklm-presentation", "NotebookLM Presentation",
            "Narrative chapter-based structure with slide boundaries and speaker notes");

    private final String cliValue;
    private final String displayName;
    private final String description;

    ResearchTarget(String cliValue, String displayName, String description) {
        this.cliValue = cliValue;
        this.displayName = displayName;
        this.description = description;
    }

    public String cliValue() { return cliValue; }
    public String displayName() { return displayName; }
    public String description() { return description; }

    /**
     * Parses a CLI value to a ResearchTarget.
     *
     * @param value the CLI string (e.g., "chatgpt", "notebooklm-infographic")
     * @return the matching target, defaults to CHATGPT_DEEP_RESEARCH
     */
    public static ResearchTarget fromString(String value) {
        if (value == null) return CHATGPT_DEEP_RESEARCH;
        for (ResearchTarget target : values()) {
            if (target.cliValue.equalsIgnoreCase(value)) return target;
        }
        return CHATGPT_DEEP_RESEARCH;
    }

    /**
     * Parses a CLI value to a ResearchTarget, throwing on invalid input.
     *
     * @param value the CLI string
     * @return the matching target
     * @throws IllegalArgumentException if value does not match any target
     */
    public static ResearchTarget fromStringStrict(String value) {
        if (value == null) throw new IllegalArgumentException("Target value must not be null");
        for (ResearchTarget target : values()) {
            if (target.cliValue.equalsIgnoreCase(value)) return target;
        }
        throw new IllegalArgumentException(
                "Unknown research target: '" + value + "'. Valid targets: chatgpt, notebooklm-infographic, notebooklm-presentation");
    }
}
