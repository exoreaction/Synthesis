package io.exoreaction.synthesis.kcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the K-series KCP health signals (issue #355).
 */
class KcpHealthChecksTest {

    private static final String TODAY = "2026-07-06";

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // K001 — expired unit still referenced
    // -----------------------------------------------------------------------

    @Test
    void k001FiresWhenExpiredUnitIsReferenced() {
        List<KcpRepository.KcpUnitRow> units = List.of(
                unit("old-api", "2020-01-01", null, null),
                unit("guide", null, null, null));
        List<KcpRelationship> rels = List.of(new KcpRelationship("guide", "old-api", "context"));

        List<KcpHealthChecks.Signal> signals =
                KcpHealthChecks.checkManifest(manifest(null), units, rels, TODAY);

        assertEquals(1, signals.size());
        assertEquals("K001", signals.get(0).code());
        assertEquals("MEDIUM", signals.get(0).severity());
        assertTrue(signals.get(0).detail().contains("old-api"));
    }

    @Test
    void k001SilentWhenExpiredUnitIsUnreferenced() {
        List<KcpRepository.KcpUnitRow> units = List.of(unit("old-api", "2020-01-01", null, null));
        List<KcpHealthChecks.Signal> signals =
                KcpHealthChecks.checkManifest(manifest(null), units, List.of(), TODAY);
        assertTrue(signals.isEmpty(), "Unreferenced expired units are not K001: " + signals);
    }

    @Test
    void k001SilentForFutureValidUntil() {
        List<KcpRepository.KcpUnitRow> units = List.of(unit("api", "2099-01-01", null, null));
        List<KcpRelationship> rels = List.of(new KcpRelationship("guide", "api", "context"));
        assertTrue(KcpHealthChecks.checkManifest(manifest(null), units, rels, TODAY).isEmpty());
    }

    // -----------------------------------------------------------------------
    // K002 — supersession cycle
    // -----------------------------------------------------------------------

    @Test
    void k002FiresOnSupersessionCycle() {
        List<KcpRepository.KcpUnitRow> units = List.of(
                unit("a", null, "b", null),
                unit("b", null, "a", null));

        List<KcpHealthChecks.Signal> signals =
                KcpHealthChecks.checkManifest(manifest(null), units, List.of(), TODAY);

        assertEquals(1, signals.size(), "Cycle reported once: " + signals);
        assertEquals("K002", signals.get(0).code());
        assertEquals("HIGH", signals.get(0).severity());
    }

    @Test
    void k002SilentOnAcyclicSupersessionChain() {
        List<KcpRepository.KcpUnitRow> units = List.of(
                unit("v1", null, "v2", null),
                unit("v2", null, "v3", null),
                unit("v3", null, null, null));
        assertTrue(KcpHealthChecks.checkManifest(manifest(null), units, List.of(), TODAY).isEmpty());
    }

    // -----------------------------------------------------------------------
    // K004 — freshness_policy violation
    // -----------------------------------------------------------------------

    @Test
    void k004FiresWhenUnitOlderThanMaxAge() {
        String rootExt = "{\"freshness_policy\":{\"max_age_days\":90,\"on_stale\":\"block\"}}";
        List<KcpRepository.KcpUnitRow> units = List.of(
                unit("stale", null, null, "2025-01-01T12:00:00+00:00"));

        List<KcpHealthChecks.Signal> signals =
                KcpHealthChecks.checkManifest(manifest(rootExt), units, List.of(), TODAY);

        assertEquals(1, signals.size());
        assertEquals("K004", signals.get(0).code());
        assertEquals("HIGH", signals.get(0).severity(), "on_stale: block maps to HIGH");
        assertTrue(signals.get(0).detail().contains("max_age_days=90"));
    }

    @Test
    void k004SeverityMapsFromOnStale() {
        List<KcpRepository.KcpUnitRow> units = List.of(unit("stale", null, null, "2025-01-01"));

        String warn = "{\"freshness_policy\":{\"max_age_days\":30,\"on_stale\":\"warn\"}}";
        assertEquals("LOW", KcpHealthChecks.checkManifest(manifest(warn), units, List.of(), TODAY)
                .get(0).severity());

        String degrade = "{\"freshness_policy\":{\"max_age_days\":30,\"on_stale\":\"degrade\"}}";
        assertEquals("MEDIUM", KcpHealthChecks.checkManifest(manifest(degrade), units, List.of(), TODAY)
                .get(0).severity());
    }

    @Test
    void k004SilentWithoutPolicyOrWithFreshUnits() {
        List<KcpRepository.KcpUnitRow> fresh = List.of(unit("fresh", null, null, TODAY));
        String policy = "{\"freshness_policy\":{\"max_age_days\":90,\"on_stale\":\"block\"}}";
        assertTrue(KcpHealthChecks.checkManifest(manifest(policy), fresh, List.of(), TODAY).isEmpty(),
                "Fresh units do not violate the policy");

        List<KcpRepository.KcpUnitRow> old = List.of(unit("old", null, null, "2020-01-01"));
        assertTrue(KcpHealthChecks.checkManifest(manifest(null), old, List.of(), TODAY).isEmpty(),
                "No policy declared → no K004");
    }

    @Test
    void freshnessPolicyParserToleratesGarbage() {
        assertNull(KcpHealthChecks.parseFreshnessPolicy(null));
        assertNull(KcpHealthChecks.parseFreshnessPolicy("not json at all"));
        assertNull(KcpHealthChecks.parseFreshnessPolicy("{\"other\":1}"));
        assertNull(KcpHealthChecks.parseFreshnessPolicy(
                "{\"freshness_policy\":{\"on_stale\":\"warn\"}}"), "max_age_days required");
    }

    // -----------------------------------------------------------------------
    // K003 — gitignored manifest
    // -----------------------------------------------------------------------

    @Test
    void k003SilentOutsideGitRepo() {
        // tempDir is not a git repository → no signal, no crash
        assertTrue(KcpHealthChecks.checkGitignored(
                tempDir, tempDir.resolve("knowledge.yaml").toString()).isEmpty());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private KcpRepository.KcpManifestRow manifest(String rootExtensionsJson) {
        return new KcpRepository.KcpManifestRow(
                "/ws/knowledge.yaml", "test-project", "0.25", 0, 0, 0L,
                null, null, null, null, -1.0, null, null, null, null, null, null, null,
                rootExtensionsJson);
    }

    private KcpRepository.KcpUnitRow unit(String id, String validUntil,
                                          String supersededBy, String recordedAt) {
        return new KcpRepository.KcpUnitRow(
                id, id + ".md", "intent?", "global", null, null, null,
                null, validUntil, recordedAt, supersededBy,
                null, null, null, false, null, null,
                null, -1.0, null, null, null);
    }
}
