package io.exoreaction.synthesis.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ArchaeologyCommand regex signal detection.
 */
class ArchaeologyCommandTest {

    // -------------------------------------------------------------------------
    // Inline markers (confidence 0.95)
    // -------------------------------------------------------------------------

    @Test
    void inlineMarker_WHY_detected() {
        assertTrue(ArchaeologyCommand.INLINE_MARKERS.matcher("WHY: we chose JGit over native git CLI").find());
    }

    @Test
    void inlineMarker_DECISION_detected() {
        assertTrue(ArchaeologyCommand.INLINE_MARKERS.matcher("DECISION: use FTS5 instead of vector search").find());
    }

    @Test
    void inlineMarker_TRADEOFF_detected() {
        assertTrue(ArchaeologyCommand.INLINE_MARKERS.matcher("TRADEOFF: performance vs memory footprint").find());
    }

    @Test
    void inlineMarker_ADR_detected() {
        assertTrue(ArchaeologyCommand.INLINE_MARKERS.matcher("ADR: adopt picocli for CLI framework").find());
    }

    @Test
    void inlineMarker_RATIONALE_detected() {
        assertTrue(ArchaeologyCommand.INLINE_MARKERS.matcher("RATIONALE: SQLite is sufficient for local-only use").find());
    }

    @Test
    void inlineMarker_REJECTED_detected() {
        assertTrue(ArchaeologyCommand.INLINE_MARKERS.matcher("REJECTED: central sync — privacy blast radius too large").find());
    }

    @Test
    void inlineMarker_caseInsensitive() {
        assertTrue(ArchaeologyCommand.INLINE_MARKERS.matcher("why: lowercase also works").find());
        assertTrue(ArchaeologyCommand.INLINE_MARKERS.matcher("Decision: mixed case").find());
    }

    @Test
    void inlineMarker_noFalsePositiveOnNormalText() {
        assertFalse(ArchaeologyCommand.INLINE_MARKERS.matcher("fix NPE in payment reconciliation").find());
        assertFalse(ArchaeologyCommand.INLINE_MARKERS.matcher("update README").find());
    }

    // -------------------------------------------------------------------------
    // Migration signals (confidence 0.80)
    // -------------------------------------------------------------------------

    @Test
    void migration_migrate_detected() {
        assertTrue(ArchaeologyCommand.MIGRATION_SIGNALS.matcher("migrate from Spring to Quarkus").find());
        assertTrue(ArchaeologyCommand.MIGRATION_SIGNALS.matcher("migrated payment service to new DB").find());
    }

    @Test
    void migration_switchTo_detected() {
        assertTrue(ArchaeologyCommand.MIGRATION_SIGNALS.matcher("switch to Flyway for schema management").find());
        assertTrue(ArchaeologyCommand.MIGRATION_SIGNALS.matcher("switched to Maven from Gradle").find());
    }

    @Test
    void migration_replaceWith_detected() {
        assertTrue(ArchaeologyCommand.MIGRATION_SIGNALS.matcher("replace Hibernate with JDBC templates").find());
        assertTrue(ArchaeologyCommand.MIGRATION_SIGNALS.matcher("replaced with lighter dependency").find());
    }

    @Test
    void migration_deprecate_detected() {
        assertTrue(ArchaeologyCommand.MIGRATION_SIGNALS.matcher("deprecate legacy auth endpoint").find());
    }

    @Test
    void migration_adopt_detected() {
        assertTrue(ArchaeologyCommand.MIGRATION_SIGNALS.matcher("adopt picocli for CLI").find());
    }

    @Test
    void migration_rewrite_detected() {
        assertTrue(ArchaeologyCommand.MIGRATION_SIGNALS.matcher("rewrite indexer for performance").find());
        assertTrue(ArchaeologyCommand.MIGRATION_SIGNALS.matcher("rewritten from scratch").find());
    }

    @Test
    void migration_redesign_detected() {
        assertTrue(ArchaeologyCommand.MIGRATION_SIGNALS.matcher("redesign workspace routing model").find());
    }

    @Test
    void migration_noFalsePositiveOnNormalCommit() {
        assertFalse(ArchaeologyCommand.MIGRATION_SIGNALS.matcher("add unit tests for payment service").find());
        assertFalse(ArchaeologyCommand.MIGRATION_SIGNALS.matcher("bump version to 1.27.0").find());
    }

    // -------------------------------------------------------------------------
    // Fix signals (confidence 0.65)
    // -------------------------------------------------------------------------

    @Test
    void fix_workaround_detected() {
        assertTrue(ArchaeologyCommand.FIX_SIGNALS.matcher("workaround for SQLite WAL mode issue").find());
    }

    @Test
    void fix_hotfix_detected() {
        assertTrue(ArchaeologyCommand.FIX_SIGNALS.matcher("hotfix: null pointer in session indexer").find());
        assertTrue(ArchaeologyCommand.FIX_SIGNALS.matcher("hot-fix for production crash").find());
    }

    @Test
    void fix_noFalsePositiveOnBugfix() {
        // "fix.*bug" was removed from pattern — plain "fix" should not match
        assertFalse(ArchaeologyCommand.FIX_SIGNALS.matcher("fix NPE in authenticator").find());
    }
}
