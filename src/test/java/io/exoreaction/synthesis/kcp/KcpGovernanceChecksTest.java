package io.exoreaction.synthesis.kcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KcpGovernanceChecks} — the sensitivity/governance-vs-reality
 * cross-check (issue #360).
 */
class KcpGovernanceChecksTest {

    // -----------------------------------------------------------------------
    // G001 — public sensitivity over a file with HIGH security findings
    // -----------------------------------------------------------------------

    @Test
    void g001FiresWhenPublicUnitHasHighSecurityFinding() {
        var units = List.of(unit("creds", "src/Creds.java", "{\"sensitivity\":\"public\"}"));
        var findings = KcpGovernanceChecks.check(units, null,
                Map.of("src/Creds.java", List.of("S007_HARDCODED_SECRET")), Set.of());

        assertEquals(1, findings.size());
        assertEquals("G001", findings.get(0).checkId());
        assertEquals("HIGH", findings.get(0).severity());
        assertTrue(findings.get(0).detail().contains("S007_HARDCODED_SECRET"));
    }

    @Test
    void g001SilentWhenSensitivityNotPublic() {
        var units = List.of(unit("creds", "src/Creds.java", "{\"sensitivity\":\"confidential\"}"));
        var findings = KcpGovernanceChecks.check(units, null,
                Map.of("src/Creds.java", List.of("S007_HARDCODED_SECRET")), Set.of());
        assertTrue(findings.isEmpty(), "Non-public sensitivity is not a contradiction: " + findings);
    }

    @Test
    void g001SilentWhenNoSecurityFindingOnPath() {
        var units = List.of(unit("readme", "README.md", "{\"sensitivity\":\"public\"}"));
        var findings = KcpGovernanceChecks.check(units, null, Map.of(), Set.of());
        assertTrue(findings.isEmpty(), "Public + clean file is fine: " + findings);
    }

    // -----------------------------------------------------------------------
    // G002 — share_externally over data-residency-restricted content
    // -----------------------------------------------------------------------

    @Test
    void g002FiresOnShareExternalWithUnitResidency() {
        var units = List.of(unit("eu-data", "data.md",
                "{\"authority\":{\"share_externally\":\"initiative\"},"
                        + "\"compliance\":{\"data_residency\":\"eu\"}}"));
        var findings = KcpGovernanceChecks.check(units, null, Map.of(), Set.of());
        assertEquals(1, findings.size());
        assertEquals("G002", findings.get(0).checkId());
        assertEquals("HIGH", findings.get(0).severity());
    }

    @Test
    void g002FiresWhenResidencyIsAtManifestLevel() {
        var units = List.of(unit("x", "x.md",
                "{\"authority\":{\"share_externally\":\"initiative\"}}"));
        var findings = KcpGovernanceChecks.check(units,
                "{\"compliance\":{\"data_residency\":\"us\"}}", Map.of(), Set.of());
        assertEquals(1, findings.size());
        assertEquals("G002", findings.get(0).checkId());
    }

    @Test
    void g002SilentWhenResidencyIsAny() {
        var units = List.of(unit("x", "x.md",
                "{\"authority\":{\"share_externally\":\"initiative\"},"
                        + "\"compliance\":{\"data_residency\":\"any\"}}"));
        assertTrue(KcpGovernanceChecks.check(units, null, Map.of(), Set.of()).isEmpty());
    }

    // -----------------------------------------------------------------------
    // G003 — visibility scopes unknown to the org registry
    // -----------------------------------------------------------------------

    @Test
    void g003FiresOnUnknownEnvironment() {
        var units = List.of(unit("x", "x.md",
                "{\"visibility\":[{\"environment\":\"mars\",\"agent_role\":\"ops\"}]}"));
        var findings = KcpGovernanceChecks.check(units, null, Map.of(),
                Set.of("dev", "prod"));
        assertEquals(1, findings.size());
        assertEquals("G003", findings.get(0).checkId());
        assertEquals("LOW", findings.get(0).severity());
    }

    @Test
    void g003SilentForKnownEnvironmentOrEmptyRegistry() {
        var units = List.of(unit("x", "x.md",
                "{\"visibility\":[{\"environment\":\"prod\"}]}"));
        assertTrue(KcpGovernanceChecks.check(units, null, Map.of(), Set.of("dev", "prod")).isEmpty());
        // Empty registry → G003 disabled (nothing to check against)
        assertTrue(KcpGovernanceChecks.check(units, null, Map.of(), Set.of()).isEmpty());
    }

    @Test
    void unitsWithoutExtensionsAreIgnored() {
        var units = List.of(unit("plain", "p.md", null));
        assertTrue(KcpGovernanceChecks.check(units, null,
                Map.of("p.md", List.of("S001")), Set.of()).isEmpty());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private KcpRepository.KcpUnitRow unit(String id, String path, String extensionsJson) {
        return new KcpRepository.KcpUnitRow(
                id, path, "intent?", "module", null, null, null,
                null, null, null, null, null, null, null, false, null, null,
                null, -1.0, null, null, extensionsJson);
    }
}
