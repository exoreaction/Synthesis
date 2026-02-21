package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 2 profile parsing: centroid and wants blocks in {@code .synthesis.md}.
 */
class DirectoryProfileParserTest {

    private final DirectoryIdentityParser parser = new DirectoryIdentityParser();

    @TempDir
    Path tempDir;

    // ---- parseProfile: backward compatibility ----

    @Test
    void parseProfile_legacyFile_returnsCentroidEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".synthesis.md");
        Files.writeString(file, """
                ---
                synthesis:
                  accepts:
                    types:
                      - "marketing"
                    formats:
                      - "md"
                  scope:
                    level: "ORGANIZATION"
                    organization: "eXOReaction"
                  confidence: 0.87
                  source: "inferred from 5 files"
                ---
                """);

        DirectoryProfile profile = parser.parseProfile(file);

        // Identity should be fully parsed
        assertEquals(List.of("marketing"), profile.identity().acceptsTypes());
        assertEquals(0.87, profile.identity().confidence(), 0.001);

        // Centroid and wants should be empty (legacy file)
        assertTrue(profile.centroid().isEmpty());
        assertTrue(profile.wants().isEmpty());
    }

    @Test
    void parseProfile_missingFile_returnsEmptyProfile() {
        Path file = Path.of("/tmp/nonexistent-profile-test/.synthesis.md");
        DirectoryProfile profile = parser.parseProfile(file);

        assertEquals(DirectoryIdentity.empty(), profile.identity());
        assertTrue(profile.centroid().isEmpty());
        assertTrue(profile.wants().isEmpty());
    }

    // ---- parseProfile: centroid block ----

    @Test
    void parseProfile_withCentroid_parsesTopicsAndEntities(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".synthesis.md");
        Files.writeString(file, """
                ---
                synthesis:
                  accepts:
                    types:
                      - "client-material"
                  scope:
                    level: "ENTITY"
                    organization: "eXOReaction"
                    entity: "GreenField"
                  confidence: 0.87
                  source: "inferred from 8 files"
                  centroid:
                    topics:
                      - "renewable energy"
                      - "SDD methodology"
                    entities:
                      - "GreenField Energy"
                      - "Jane Smith"
                    timeframe: "2025-Q4 / 2026-Q1"
                    document_types:
                      - "proposal"
                      - "contract"
                    confidence: 0.87
                    contributing_files: 8
                    last_updated: "2026-02-21T15:00:00Z"
                  transient: false
                ---
                """);

        DirectoryProfile profile = parser.parseProfile(file);

        // Identity should be parsed correctly
        assertEquals(List.of("client-material"), profile.identity().acceptsTypes());
        assertEquals(0.87, profile.identity().confidence(), 0.001);

        // Centroid should be fully parsed
        DirectoryCentroid centroid = profile.centroid();
        assertFalse(centroid.isEmpty());
        assertEquals(List.of("renewable energy", "SDD methodology"), centroid.topics());
        assertEquals(List.of("GreenField Energy", "Jane Smith"), centroid.entities());
        assertEquals("2025-Q4 / 2026-Q1", centroid.timeframe());
        assertEquals(List.of("proposal", "contract"), centroid.documentTypes());
        assertEquals(0.87, centroid.confidence(), 0.001);
        assertEquals(8, centroid.contributingFiles());
        assertEquals(Instant.parse("2026-02-21T15:00:00Z"), centroid.lastUpdated());
    }

    // ---- parseProfile: wants block ----

    @Test
    void parseProfile_withWants_parsesTopicsAndSource(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".synthesis.md");
        Files.writeString(file, """
                ---
                synthesis:
                  scope:
                    level: "CLIENT"
                    organization: "myorg"
                  confidence: 0.3
                  wants:
                    topics:
                      - "Nova Corp"
                      - "opportunity"
                    entities:
                      - "Nova Corp"
                    source: "inferred from directory name"
                    satisfaction: 0.0
                  transient: false
                ---
                """);

        DirectoryProfile profile = parser.parseProfile(file);

        DirectoryWants wants = profile.wants();
        assertFalse(wants.isEmpty());
        assertEquals(List.of("Nova Corp", "opportunity"), wants.topics());
        assertEquals(List.of("Nova Corp"), wants.entities());
        assertEquals("inferred from directory name", wants.source());
        assertEquals(0.0, wants.satisfaction());
    }

    // ---- parseProfile: centroid + wants together ----

    @Test
    void parseProfile_withBothCentroidAndWants(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".synthesis.md");
        Files.writeString(file, """
                ---
                synthesis:
                  accepts:
                    types:
                      - "client-material"
                  scope:
                    level: "ENTITY"
                    organization: "eXOReaction"
                  confidence: 0.6
                  source: "inferred from 3 files"
                  centroid:
                    topics:
                      - "renewable energy"
                    entities:
                      - "GreenField Energy"
                    confidence: 0.45
                    contributing_files: 3
                  wants:
                    topics:
                      - "GreenField opportunity lifecycle"
                    entities:
                      - "GreenField Energy"
                    source: "inferred from directory name + 3 files"
                    satisfaction: 0.45
                ---
                """);

        DirectoryProfile profile = parser.parseProfile(file);

        // Both should be present
        assertFalse(profile.centroid().isEmpty());
        assertFalse(profile.wants().isEmpty());

        assertEquals(List.of("renewable energy"), profile.centroid().topics());
        assertEquals(0.45, profile.centroid().confidence(), 0.001);

        assertEquals(List.of("GreenField opportunity lifecycle"), profile.wants().topics());
        assertEquals(0.45, profile.wants().satisfaction(), 0.001);
    }

    // ---- Round-trip: writeProfile then parseProfile ----

    @Test
    void writeProfile_roundTrip_preservesAllFields(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".synthesis.md");

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("client-material"), List.of("md", "pdf"), List.of(),
                ScopeLevel.ENTITY, "eXOReaction", "GreenField",
                0.87, null, "inferred from 8 files", ""
        );

        Instant centroidTime = Instant.parse("2026-02-21T15:00:00Z");
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("renewable energy", "SDD methodology"),
                List.of("GreenField Energy", "Jane Smith"),
                "2025-Q4 / 2026-Q1",
                List.of("proposal", "contract"),
                0.87,
                8,
                0,
                centroidTime
        );

        DirectoryWants wants = new DirectoryWants(
                List.of("GreenField opportunity lifecycle"),
                List.of("GreenField Energy"),
                List.of(), // alsoLookingFor
                "inferred from directory name + 8 files",
                0.87
        );

        DirectoryProfile original = new DirectoryProfile(identity, centroid, wants);
        parser.writeProfile(file, original);

        // Parse back
        DirectoryProfile parsed = parser.parseProfile(file);

        // Identity fields
        assertEquals(original.identity().acceptsTypes(), parsed.identity().acceptsTypes());
        assertEquals(original.identity().acceptsFormats(), parsed.identity().acceptsFormats());
        assertEquals(original.identity().scopeLevel(), parsed.identity().scopeLevel());
        assertEquals(original.identity().scopeOrganization(), parsed.identity().scopeOrganization());
        assertEquals(original.identity().scopeEntity(), parsed.identity().scopeEntity());
        assertEquals(original.identity().confidence(), parsed.identity().confidence(), 0.001);
        assertEquals(original.identity().source(), parsed.identity().source());

        // Centroid fields
        assertEquals(original.centroid().topics(), parsed.centroid().topics());
        assertEquals(original.centroid().entities(), parsed.centroid().entities());
        assertEquals(original.centroid().timeframe(), parsed.centroid().timeframe());
        assertEquals(original.centroid().documentTypes(), parsed.centroid().documentTypes());
        assertEquals(original.centroid().confidence(), parsed.centroid().confidence(), 0.001);
        assertEquals(original.centroid().contributingFiles(), parsed.centroid().contributingFiles());
        assertEquals(original.centroid().lastUpdated(), parsed.centroid().lastUpdated());

        // Wants fields
        assertEquals(original.wants().topics(), parsed.wants().topics());
        assertEquals(original.wants().entities(), parsed.wants().entities());
        assertEquals(original.wants().source(), parsed.wants().source());
        assertEquals(original.wants().satisfaction(), parsed.wants().satisfaction(), 0.001);
    }

    @Test
    void writeProfile_withEmptyCentroidAndWants_omitsBlocks(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".synthesis.md");

        DirectoryProfile profile = DirectoryProfile.fromIdentity(new DirectoryIdentity(
                List.of("marketing"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", ""
        ));

        parser.writeProfile(file, profile);

        String content = Files.readString(file);

        // Should NOT contain centroid or wants blocks
        assertFalse(content.contains("centroid:"), "Empty centroid should not be written");
        assertFalse(content.contains("wants:"), "Empty wants should not be written");

        // Should contain identity fields
        assertTrue(content.contains("marketing"));
        assertTrue(content.contains("confidence: 0.6"));
    }

    @Test
    void writeProfile_centroidConfidenceNotLeakedToIdentity(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".synthesis.md");

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("reports"), List.of("pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", ""
        );
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("energy"), List.of(), null, List.of(),
                0.9, 5, 0, null  // centroid confidence is 0.9
        );

        DirectoryProfile profile = new DirectoryProfile(identity, centroid, DirectoryWants.empty());
        parser.writeProfile(file, profile);

        // Parse back
        DirectoryProfile parsed = parser.parseProfile(file);

        // Identity confidence should remain 0.6, not be overwritten by centroid's 0.9
        assertEquals(0.6, parsed.identity().confidence(), 0.001);
        assertEquals(0.9, parsed.centroid().confidence(), 0.001);
    }

    @Test
    void writeProfile_wantsSourceNotLeakedToIdentitySource(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".synthesis.md");

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("reports"), List.of("pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "inferred from 5 files", ""
        );
        DirectoryWants wants = new DirectoryWants(
                List.of("energy"), List.of(), List.of(),
                "inferred from directory name", 0.0
        );

        DirectoryProfile profile = new DirectoryProfile(identity, DirectoryCentroid.empty(), wants);
        parser.writeProfile(file, profile);

        // Parse back
        DirectoryProfile parsed = parser.parseProfile(file);

        // Identity source should remain the original, not be overwritten by wants source
        assertEquals("inferred from 5 files", parsed.identity().source());
        assertEquals("inferred from directory name", parsed.wants().source());
    }

    // ---- Legacy parse() still works ----

    @Test
    void parse_withCentroidAndWants_returnsIdentityOnly(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".synthesis.md");
        Files.writeString(file, """
                ---
                synthesis:
                  accepts:
                    types:
                      - "marketing"
                  scope:
                    level: "WORKSPACE"
                    organization: null
                  confidence: 0.6
                  centroid:
                    topics:
                      - "energy"
                    confidence: 0.9
                    contributing_files: 5
                  wants:
                    topics:
                      - "more stuff"
                    source: "test"
                    satisfaction: 0.0
                ---
                """);

        // The legacy parse() method should still return a valid identity
        DirectoryIdentity identity = parser.parse(file);

        assertEquals(List.of("marketing"), identity.acceptsTypes());
        // Identity confidence should be 0.6, not affected by centroid's 0.9
        assertEquals(0.6, identity.confidence(), 0.001);
    }

    // ---- Health block round-trip (Phase 3) ----

    @Test
    void writeProfile_withHealth_roundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".synthesis.md");

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("client"), List.of("pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", ""
        );
        DirectoryCentroid centroid = new DirectoryCentroid(
                List.of("renewable energy"), List.of("GreenField Energy"),
                "2026-Q1", List.of("proposal"), 0.9, 10, 0, Instant.now()
        );
        DirectoryWants wants = new DirectoryWants(
                List.of("renewable energy"), List.of("GreenField Energy"),
                List.of(), "test", 0.85
        );
        DirectoryHealth health = new DirectoryHealth(
                0.9, false, 0.85, "healthy", List.of()
        );

        DirectoryProfile profile = new DirectoryProfile(identity, centroid, wants, health);
        parser.writeProfile(file, profile);

        DirectoryProfile parsed = parser.parseProfile(file);

        assertFalse(parsed.health().isEmpty(), "Health should be present");
        assertEquals("healthy", parsed.health().status());
        assertEquals(0.9, parsed.health().cohesion(), 0.01);
        assertFalse(parsed.health().drift());
        assertEquals(0.85, parsed.health().satisfaction(), 0.01);
        assertTrue(parsed.health().outliers().isEmpty());
    }

    @Test
    void writeProfile_withDriftingHealth_roundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".synthesis.md");

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("client"), List.of("pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", ""
        );
        DirectoryHealth health = new DirectoryHealth(
                0.7, true, 0.2, "drifting", List.of("stray-report.pdf")
        );

        DirectoryProfile profile = new DirectoryProfile(identity, DirectoryCentroid.empty(),
                DirectoryWants.empty(), health);
        parser.writeProfile(file, profile);

        DirectoryProfile parsed = parser.parseProfile(file);

        assertFalse(parsed.health().isEmpty());
        assertEquals("drifting", parsed.health().status());
        assertTrue(parsed.health().drift());
        assertEquals(0.7, parsed.health().cohesion(), 0.01);
        assertEquals(0.2, parsed.health().satisfaction(), 0.01);
        assertEquals(List.of("stray-report.pdf"), parsed.health().outliers());
    }

    @Test
    void parseProfile_noHealthBlock_returnsEmptyHealth(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".synthesis.md");
        Files.writeString(file, """
                ---
                synthesis:
                  accepts:
                    types:
                      - "docs"
                  scope:
                    level: "WORKSPACE"
                  confidence: 0.5
                ---
                """);

        DirectoryProfile parsed = parser.parseProfile(file);

        assertTrue(parsed.health().isEmpty(),
                "Missing health block should produce empty health");
    }

    @Test
    void writeProfile_emptyHealth_notWritten(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".synthesis.md");

        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("docs"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.5, null, "test", ""
        );
        DirectoryProfile profile = new DirectoryProfile(identity, DirectoryCentroid.empty(),
                DirectoryWants.empty(), DirectoryHealth.empty());
        parser.writeProfile(file, profile);

        String content = Files.readString(file);
        assertFalse(content.contains("health:"),
                "Empty health should not be written to file");
    }
}
