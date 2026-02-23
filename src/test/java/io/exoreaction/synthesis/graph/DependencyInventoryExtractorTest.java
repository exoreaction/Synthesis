package io.exoreaction.synthesis.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DependencyInventoryExtractor} -- pom.xml parsing
 * and vulnerability catalog loading.
 *
 * @since v1.14.0 (Security)
 */
class DependencyInventoryExtractorTest {

    private DependencyInventoryExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new DependencyInventoryExtractor();
    }

    // -----------------------------------------------------------------------
    // pom.xml parsing
    // -----------------------------------------------------------------------

    @Test
    void parsePomDependencies_extracts_dependencies() {
        String pom = """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.logging.log4j</groupId>
                            <artifactId>log4j-core</artifactId>
                            <version>2.14.0</version>
                        </dependency>
                        <dependency>
                            <groupId>junit</groupId>
                            <artifactId>junit</artifactId>
                            <version>4.13.2</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """;

        List<DeclaredDependency> deps = extractor.parsePomDependencies(pom, "pom.xml");
        assertEquals(2, deps.size());

        DeclaredDependency log4j = deps.stream()
                .filter(d -> d.artifactId().equals("log4j-core"))
                .findFirst().orElse(null);
        assertNotNull(log4j);
        assertEquals("org.apache.logging.log4j", log4j.groupId());
        assertEquals("2.14.0", log4j.version());
        assertNull(log4j.scope());

        DeclaredDependency junit = deps.stream()
                .filter(d -> d.artifactId().equals("junit"))
                .findFirst().orElse(null);
        assertNotNull(junit);
        assertEquals("test", junit.scope());
    }

    @Test
    void parsePomDependencies_handles_property_versions() {
        String pom = """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>lib</artifactId>
                            <version>${project.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """;

        List<DeclaredDependency> deps = extractor.parsePomDependencies(pom, "pom.xml");
        assertEquals(1, deps.size());
        assertNull(deps.get(0).version(), "Property references should be null");
    }

    @Test
    void parsePomDependencies_handles_no_version() {
        String pom = """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>managed</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        List<DeclaredDependency> deps = extractor.parsePomDependencies(pom, "pom.xml");
        assertEquals(1, deps.size());
        assertNull(deps.get(0).version());
    }

    @Test
    void parsePomDependencies_empty_pom() {
        String pom = "<project></project>";
        List<DeclaredDependency> deps = extractor.parsePomDependencies(pom, "pom.xml");
        assertTrue(deps.isEmpty());
    }

    @Test
    void parsePomDependencies_skips_commented_out_dependencies() {
        String pom = """
                <project>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-text</artifactId>
                            <version>1.9</version>
                        </dependency>
                        <!--
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>commented-out-lib</artifactId>
                            <version>9.9.9</version>
                        </dependency>
                        -->
                    </dependencies>
                </project>
                """;

        List<DeclaredDependency> deps = extractor.parsePomDependencies(pom, "pom.xml");
        assertEquals(1, deps.size(), "Only the active dependency should be returned");
        assertEquals("commons-text", deps.get(0).artifactId());
        assertTrue(deps.stream().noneMatch(d -> d.artifactId().equals("commented-out-lib")),
                "Commented-out dependencies must not be extracted");
    }

    // -----------------------------------------------------------------------
    // Known vulnerabilities catalog
    // -----------------------------------------------------------------------

    @Test
    void loadKnownVulnerabilities_loads_catalog() {
        List<DependencyInventoryExtractor.KnownVulnerability> vulns =
                extractor.loadKnownVulnerabilities();
        assertFalse(vulns.isEmpty(), "Should load at least one known vulnerability");

        // Verify Log4Shell is in the catalog
        assertTrue(vulns.stream().anyMatch(v ->
                        v.cveId().equals("CVE-2021-44228")
                                && v.groupId().equals("org.apache.logging.log4j")),
                "Should contain Log4Shell CVE");
    }

    @Test
    void knownVulnerability_matches_vulnerable_version() {
        DependencyInventoryExtractor.KnownVulnerability vuln =
                new DependencyInventoryExtractor.KnownVulnerability(
                        "org.apache.logging.log4j", "log4j-core",
                        "2.17.1", "CVE-2021-44228", "Log4Shell");

        DeclaredDependency vulnerable = new DeclaredDependency(
                "org.apache.logging.log4j", "log4j-core",
                "2.14.0", null, "pom.xml");

        assertTrue(vuln.matches(vulnerable), "Version 2.14.0 should be flagged as vulnerable");
    }

    @Test
    void knownVulnerability_does_not_match_fixed_version() {
        DependencyInventoryExtractor.KnownVulnerability vuln =
                new DependencyInventoryExtractor.KnownVulnerability(
                        "org.apache.logging.log4j", "log4j-core",
                        "2.17.1", "CVE-2021-44228", "Log4Shell");

        DeclaredDependency fixed = new DeclaredDependency(
                "org.apache.logging.log4j", "log4j-core",
                "2.17.1", null, "pom.xml");

        assertFalse(vuln.matches(fixed), "Fixed version should not be flagged");
    }

    @Test
    void knownVulnerability_does_not_match_newer_version() {
        DependencyInventoryExtractor.KnownVulnerability vuln =
                new DependencyInventoryExtractor.KnownVulnerability(
                        "org.apache.logging.log4j", "log4j-core",
                        "2.17.1", "CVE-2021-44228", "Log4Shell");

        DeclaredDependency newer = new DeclaredDependency(
                "org.apache.logging.log4j", "log4j-core",
                "2.20.0", null, "pom.xml");

        assertFalse(vuln.matches(newer), "Newer version should not be flagged");
    }

    @Test
    void knownVulnerability_does_not_match_different_artifact() {
        DependencyInventoryExtractor.KnownVulnerability vuln =
                new DependencyInventoryExtractor.KnownVulnerability(
                        "org.apache.logging.log4j", "log4j-core",
                        "2.17.1", "CVE-2021-44228", "Log4Shell");

        DeclaredDependency different = new DeclaredDependency(
                "org.apache.logging.log4j", "log4j-api",
                "2.14.0", null, "pom.xml");

        assertFalse(vuln.matches(different), "Different artifact should not match");
    }

    @Test
    void knownVulnerability_does_not_match_null_version() {
        DependencyInventoryExtractor.KnownVulnerability vuln =
                new DependencyInventoryExtractor.KnownVulnerability(
                        "org.apache.logging.log4j", "log4j-core",
                        "2.17.1", "CVE-2021-44228", "Log4Shell");

        DeclaredDependency noVersion = new DeclaredDependency(
                "org.apache.logging.log4j", "log4j-core",
                null, null, "pom.xml");

        assertFalse(vuln.matches(noVersion), "Null version should not be flagged");
    }
}
