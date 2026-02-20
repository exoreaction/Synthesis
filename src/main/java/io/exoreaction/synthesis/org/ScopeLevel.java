package io.exoreaction.synthesis.org;

/**
 * Defines the resolution level for positional scope inference.
 *
 * <p>Used by {@link ScopeResolver} to determine what context a given
 * directory path falls under — from broad workspace-level down to a
 * specific entity (client/opportunity).
 */
public enum ScopeLevel {

    /** Entire workspace — no organization context detected. */
    WORKSPACE,

    /** Within a known organization directory, but not a specific client/entity. */
    ORGANIZATION,

    /** Within a specific entity (client or opportunity) of an organization. */
    ENTITY
}
