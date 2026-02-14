package io.exoreaction.synthesis.org;

/**
 * Classification of organization type.
 */
public enum OrganizationType {

    /** A business company (e.g., eXOReaction AS). */
    COMPANY,

    /** An open source foundation (e.g., Cantara). */
    FOUNDATION,

    /** A holding/parent company (e.g., T-Hex Holding). */
    HOLDING,

    /** A conceptual or IP project (e.g., Merkabit). */
    CONCEPT,

    /** Other or unclassified. */
    OTHER
}
