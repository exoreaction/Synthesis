package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DiscoveredOrganization}.
 */
class DiscoveredOrganizationTest {

    @TempDir
    Path tempDir;

    // --- Normalized confidence ---

    @Test
    void normalizedConfidence_maxScore_returns10() {
        // Max possible raw score: 14
        DiscoveredOrganization d = create(14);
        assertEquals(10, d.normalizedConfidence());
    }

    @Test
    void normalizedConfidence_halfScore_returns5() {
        // 7 / 14 * 10 = 5
        DiscoveredOrganization d = create(7);
        assertEquals(5, d.normalizedConfidence());
    }

    @Test
    void normalizedConfidence_minThreshold_returnsAtLeast1() {
        DiscoveredOrganization d = create(1);
        assertTrue(d.normalizedConfidence() >= 1);
    }

    @Test
    void normalizedConfidence_zero_returns1() {
        // Min clamp to 1
        DiscoveredOrganization d = create(0);
        assertEquals(1, d.normalizedConfidence());
    }

    @Test
    void normalizedConfidence_aboveMax_clamps() {
        DiscoveredOrganization d = create(20);
        assertEquals(10, d.normalizedConfidence());
    }

    // --- Confidence levels ---

    @Test
    void isHighConfidence_highScore_true() {
        // Score 10 -> normalized 7
        DiscoveredOrganization d = create(10);
        assertTrue(d.isHighConfidence());
    }

    @Test
    void isHighConfidence_lowScore_false() {
        DiscoveredOrganization d = create(3);
        assertFalse(d.isHighConfidence());
    }

    @Test
    void isMediumConfidence_midScore_true() {
        // Score 6 -> normalized ~4
        DiscoveredOrganization d = create(6);
        assertTrue(d.isMediumConfidence());
    }

    @Test
    void isLowConfidence_lowScore_true() {
        // Score 3 -> normalized ~2
        DiscoveredOrganization d = create(3);
        assertTrue(d.isLowConfidence());
    }

    // --- Record accessors ---

    @Test
    void accessors_returnCorrectValues() {
        Organization org = new Organization("TestOrg", OrganizationType.COMPANY,
                tempDir.resolve("TestOrg"));
        DiscoveredOrganization d = new DiscoveredOrganization(
                org, 8, "README.md, clients/");

        assertEquals(org, d.organization());
        assertEquals(8, d.confidence());
        assertEquals("README.md, clients/", d.signals());
    }

    // --- Helper ---

    private DiscoveredOrganization create(int confidence) {
        Organization org = new Organization("TestOrg", OrganizationType.COMPANY,
                tempDir.resolve("TestOrg"));
        return new DiscoveredOrganization(org, confidence, "test signals");
    }
}
