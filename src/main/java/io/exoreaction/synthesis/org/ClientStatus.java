package io.exoreaction.synthesis.org;

/**
 * Status of a client relationship with an organization.
 *
 * <p>Lifecycle: OPPORTUNITY -> SIGNED -> ACTIVE -> PAST
 */
public enum ClientStatus {

    /** Active client with ongoing work. */
    ACTIVE,

    /** Historical client, engagement completed. */
    PAST,

    /** Pipeline lead, prospect, not yet signed. */
    OPPORTUNITY,

    /** Contract signed but work not yet started or just beginning. */
    SIGNED;

    /**
     * Detects client status from a directory name.
     *
     * <p>Naming conventions:
     * <ul>
     *   <li>{@code opportunity-<Name>} -> OPPORTUNITY</li>
     *   <li>{@code <Name>-past} -> PAST</li>
     *   <li>anything else -> ACTIVE</li>
     * </ul>
     *
     * @param directoryName the client directory name
     * @return detected status
     */
    public static ClientStatus fromDirectoryName(String directoryName) {
        if (directoryName.startsWith("opportunity-")) {
            return OPPORTUNITY;
        }
        if (directoryName.endsWith("-past")) {
            return PAST;
        }
        return ACTIVE;
    }

    /**
     * Extracts the clean client name from a directory name,
     * stripping status prefixes/suffixes.
     *
     * @param directoryName the raw directory name
     * @return clean client name
     */
    public static String extractClientName(String directoryName) {
        if (directoryName.startsWith("opportunity-")) {
            return directoryName.substring("opportunity-".length());
        }
        if (directoryName.endsWith("-past")) {
            return directoryName.substring(0, directoryName.length() - "-past".length());
        }
        return directoryName;
    }
}
