package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.org.ScopeResolver.ResolvedScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScopeCheckerTest {

    private ScopeChecker checker;

    @BeforeEach
    void setUp() {
        checker = new ScopeChecker();
    }

    @Test
    void isCompatible_nullFileOrg_isCompatible() {
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);
        ResolvedScope dirScope = new ResolvedScope(ScopeLevel.ORGANIZATION, "Acme", null);
        assertTrue(checker.isCompatible(fileScope, dirScope));
    }

    @Test
    void isCompatible_nullDirOrg_isCompatible() {
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.ORGANIZATION, "Acme", null);
        ResolvedScope dirScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);
        assertTrue(checker.isCompatible(fileScope, dirScope));
    }

    @Test
    void isCompatible_sameOrg_isCompatible() {
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.ORGANIZATION, "Acme", null);
        ResolvedScope dirScope = new ResolvedScope(ScopeLevel.ORGANIZATION, "Acme", null);
        assertTrue(checker.isCompatible(fileScope, dirScope));
    }

    @Test
    void isCompatible_differentOrg_isBlocked() {
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.ORGANIZATION, "Acme", null);
        ResolvedScope dirScope = new ResolvedScope(ScopeLevel.ORGANIZATION, "Globex", null);
        assertFalse(checker.isCompatible(fileScope, dirScope));
    }

    @Test
    void scopeBonus_noOrgMatch_returnsZero() {
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);
        ResolvedScope dirScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);
        assertEquals(0.0, checker.scopeBonus(fileScope, dirScope), 0.001);
    }

    @Test
    void scopeBonus_orgMatch_returns0point24() {
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.ORGANIZATION, "Acme", null);
        ResolvedScope dirScope = new ResolvedScope(ScopeLevel.ORGANIZATION, "Acme", null);
        assertEquals(0.24, checker.scopeBonus(fileScope, dirScope), 0.001);
    }

    @Test
    void scopeBonus_orgAndEntityMatch_returns0point64() {
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.ENTITY, "Acme", "ProjectX");
        ResolvedScope dirScope = new ResolvedScope(ScopeLevel.ENTITY, "Acme", "ProjectX");
        assertEquals(0.64, checker.scopeBonus(fileScope, dirScope), 0.001);
    }

    @Test
    void scopeBonus_entityMatchOnly_returnsEntityBonus() {
        // Entity matches but orgs differ (shouldn't happen normally but test the math)
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.ENTITY, "Acme", "ProjectX");
        ResolvedScope dirScope = new ResolvedScope(ScopeLevel.ENTITY, "Globex", "ProjectX");
        // Orgs don't match so no org bonus; entity matches so +0.40
        assertEquals(0.40, checker.scopeBonus(fileScope, dirScope), 0.001);
    }
}
