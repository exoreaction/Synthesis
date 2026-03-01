package io.exoreaction.synthesis.kcp;

/**
 * Represents a declared relationship between two units in a KCP manifest.
 *
 * <p>Relationships are defined in the optional {@code relationships} section
 * of a {@code knowledge.yaml} file, e.g.:
 * <pre>
 * relationships:
 *   - from: agents-tldr
 *     to: agents
 *     type: context
 * </pre>
 */
public record KcpRelationship(
        /** ID of the source unit. */
        String fromUnit,

        /** ID of the target unit. */
        String toUnit,

        /** Relationship type, e.g. "context", "extends", "summary_of". May be null. */
        String type
) {}
