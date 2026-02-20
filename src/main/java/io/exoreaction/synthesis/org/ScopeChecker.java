package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.org.ScopeResolver.ResolvedScope;

/**
 * Checks scope compatibility and calculates scope bonuses for routing decisions.
 *
 * <p>Hard block rule: if both the file and directory have non-null organizations
 * that differ, routing is blocked (incompatible).
 *
 * <p>Bonus stacking:
 * <ul>
 *   <li>Organization match (same non-null organization): +0.24</li>
 *   <li>Entity match (same non-null entity): +0.40</li>
 *   <li>Maximum total bonus: 0.64</li>
 * </ul>
 */
public class ScopeChecker {

    private static final double ORG_BONUS = 0.24;
    private static final double ENTITY_BONUS = 0.40;
    private static final double MAX_BONUS = 0.64;

    /**
     * Returns true if the file's detected scope is compatible with the directory's scope.
     *
     * <p>Incompatible only when both scopes have non-null organizations that differ.
     *
     * @param fileScope      the resolved scope of the file
     * @param directoryScope the resolved scope of the target directory
     * @return true if compatible, false if blocked
     */
    public boolean isCompatible(ResolvedScope fileScope, ResolvedScope directoryScope) {
        if (fileScope.organization() == null || directoryScope.organization() == null) {
            return true;
        }
        return fileScope.organization().equals(directoryScope.organization());
    }

    /**
     * Returns the scope bonus score (0.0 to 0.64).
     *
     * <p>Organization match contributes +0.24, entity match contributes +0.40.
     * Bonuses stack but are capped at 0.64.
     *
     * @param fileScope      the resolved scope of the file
     * @param directoryScope the resolved scope of the target directory
     * @return the bonus score between 0.0 and 0.64
     */
    public double scopeBonus(ResolvedScope fileScope, ResolvedScope directoryScope) {
        double bonus = 0.0;

        if (fileScope.organization() != null && directoryScope.organization() != null
                && fileScope.organization().equals(directoryScope.organization())) {
            bonus += ORG_BONUS;
        }

        if (fileScope.entity() != null && directoryScope.entity() != null
                && fileScope.entity().equals(directoryScope.entity())) {
            bonus += ENTITY_BONUS;
        }

        return Math.min(bonus, MAX_BONUS);
    }
}
