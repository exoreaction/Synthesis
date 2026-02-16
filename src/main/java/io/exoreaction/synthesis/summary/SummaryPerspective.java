package io.exoreaction.synthesis.summary;

/**
 * Role-based perspective for summary generation.
 * Each perspective filters and emphasizes different aspects.
 */
public enum SummaryPerspective {
    GENERAL("general", "Balanced overview across all dimensions"),
    EXECUTIVE("executive", "Business impact, ROI, strategic risk"),
    ENGINEERING_MANAGER("engineering_manager", "Team velocity, tech debt, hiring implications"),
    ARCHITECT("architect", "Architecture quality, dependency health, patterns"),
    SECURITY("security", "Vulnerability surface, credential exposure, compliance"),
    DEVOPS("devops", "Build health, deployment risk, infrastructure"),
    PRODUCT_MANAGER("product_manager", "Feature coverage, documentation quality, user impact"),
    DEVELOPER("developer", "Code quality, test coverage, hotspots");

    private final String cliValue;
    private final String description;

    SummaryPerspective(String cliValue, String description) {
        this.cliValue = cliValue;
        this.description = description;
    }

    public String cliValue() { return cliValue; }
    public String description() { return description; }

    public static SummaryPerspective fromString(String value) {
        if (value == null) return GENERAL;
        for (SummaryPerspective p : values()) {
            if (p.cliValue.equalsIgnoreCase(value)) return p;
        }
        return GENERAL;
    }
}
