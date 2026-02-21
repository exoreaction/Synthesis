package io.exoreaction.synthesis.org;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryIdentityParserTest {

    private final DirectoryIdentityParser parser = new DirectoryIdentityParser();

    @Test
    void parse_validFile_returnsFullIdentity(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".synthesis.md");
        Files.writeString(file, """
                ---
                synthesis:
                  role: "Shell scripts and automation tools"
                  accepts:
                    types:
                      - "meeting-notes"
                      - "minutes"
                    formats:
                      - "md"
                      - "pdf"
                    patterns:
                      - "*meeting*"
                  scope:
                    level: "ORGANIZATION"
                    organization: "eXOReaction"
                    entity: null
                  confidence: 0.94
                  last_synced: "2026-02-20T14:30:00Z"
                  source: "inferred from 12 existing files"
                ---

                # automation/

                Human-readable description goes here.
                """);

        DirectoryIdentity identity = parser.parse(file);

        assertEquals(List.of("meeting-notes", "minutes"), identity.acceptsTypes());
        assertEquals(List.of("md", "pdf"), identity.acceptsFormats());
        assertEquals(List.of("*meeting*"), identity.acceptsPatterns());
        assertEquals(ScopeLevel.ORGANIZATION, identity.scopeLevel());
        assertEquals("eXOReaction", identity.scopeOrganization());
        assertNull(identity.scopeEntity());
        assertEquals(0.94, identity.confidence(), 0.001);
        assertEquals(Instant.parse("2026-02-20T14:30:00Z"), identity.lastSynced());
        assertEquals("inferred from 12 existing files", identity.source());
        assertTrue(identity.description().contains("Human-readable description goes here."));
        assertTrue(identity.description().contains("# automation/"));
    }

    @Test
    void parse_emptyFile_returnsEmpty(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".synthesis.md");
        Files.writeString(file, "");

        DirectoryIdentity identity = parser.parse(file);

        assertEquals(DirectoryIdentity.empty(), identity);
    }

    @Test
    void parse_fileWithOnlyMarkdown_returnsDescription(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".synthesis.md");
        Files.writeString(file, """
                # My Directory

                This directory contains important files.
                No YAML front matter here.
                """);

        DirectoryIdentity identity = parser.parse(file);

        assertTrue(identity.acceptsTypes().isEmpty());
        assertTrue(identity.acceptsFormats().isEmpty());
        assertTrue(identity.acceptsPatterns().isEmpty());
        assertEquals(ScopeLevel.WORKSPACE, identity.scopeLevel());
        assertNull(identity.scopeOrganization());
        assertNull(identity.scopeEntity());
        assertEquals(0.0, identity.confidence());
        assertNull(identity.lastSynced());
        assertEquals("", identity.source());
        assertTrue(identity.description().contains("This directory contains important files."));
    }

    @Test
    void parse_missingFile_returnsEmpty() {
        Path nonExistent = Path.of("/tmp/does-not-exist-synthesis-test/.synthesis.md");

        DirectoryIdentity identity = parser.parse(nonExistent);

        assertEquals(DirectoryIdentity.empty(), identity);
    }

    @Test
    void write_roundTrip_preservesAllFields(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".synthesis.md");

        DirectoryIdentity original = new DirectoryIdentity(
                List.of("meeting-notes", "minutes"),
                List.of("md", "pdf", "docx"),
                List.of("*meeting*", "*minutes*"),
                ScopeLevel.ORGANIZATION,
                "eXOReaction",
                "NordicEnergy",
                0.87,
                Instant.parse("2026-02-20T10:00:00Z"),
                "inferred from 12 existing files",
                "# meetings/\n\nThis directory contains meeting notes."
        );

        parser.write(file, original);

        DirectoryIdentity parsed = parser.parse(file);

        assertEquals(original.acceptsTypes(), parsed.acceptsTypes());
        assertEquals(original.acceptsFormats(), parsed.acceptsFormats());
        assertEquals(original.acceptsPatterns(), parsed.acceptsPatterns());
        assertEquals(original.scopeLevel(), parsed.scopeLevel());
        assertEquals(original.scopeOrganization(), parsed.scopeOrganization());
        assertEquals(original.scopeEntity(), parsed.scopeEntity());
        assertEquals(original.confidence(), parsed.confidence(), 0.001);
        // last_synced will be updated to now(), so it won't match original
        assertNotNull(parsed.lastSynced());
        assertEquals(original.source(), parsed.source());
        assertTrue(parsed.description().contains("This directory contains meeting notes."));
    }

    @Test
    void write_preservesExistingMarkdownBody(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".synthesis.md");

        // Write initial file with a body
        DirectoryIdentity initial = new DirectoryIdentity(
                List.of("reports"),
                List.of("pdf"),
                List.of(),
                ScopeLevel.WORKSPACE,
                null,
                null,
                0.5,
                null,
                "initial scan",
                "# reports/\n\nWeekly status reports go here."
        );
        parser.write(file, initial);

        // Now write an updated identity with empty description — body should be preserved
        DirectoryIdentity updated = new DirectoryIdentity(
                List.of("reports", "summaries"),
                List.of("pdf", "md"),
                List.of(),
                ScopeLevel.ORGANIZATION,
                "eXOReaction",
                null,
                0.75,
                null,
                "updated scan",
                ""
        );
        parser.write(file, updated);

        DirectoryIdentity parsed = parser.parse(file);

        assertEquals(List.of("reports", "summaries"), parsed.acceptsTypes());
        assertEquals(List.of("pdf", "md"), parsed.acceptsFormats());
        assertEquals(ScopeLevel.ORGANIZATION, parsed.scopeLevel());
        assertEquals("eXOReaction", parsed.scopeOrganization());
        assertEquals(0.75, parsed.confidence(), 0.001);
        // The existing markdown body should be preserved
        assertTrue(parsed.description().contains("Weekly status reports go here."));
    }

    @Test
    void merge_preservesExistingUserDeclaredTypes() {
        DirectoryIdentity existing = new DirectoryIdentity(
                List.of("meeting-notes"),
                List.of("md"),
                List.of("*meeting*"),
                ScopeLevel.ORGANIZATION,
                "eXOReaction",
                null,
                0.8,
                Instant.parse("2026-02-19T10:00:00Z"),
                "user declared",
                "Existing description"
        );

        DirectoryIdentity discovered = new DirectoryIdentity(
                List.of("meeting-notes", "minutes"),
                List.of("md", "pdf"),
                List.of("*meeting*", "*notes*"),
                ScopeLevel.WORKSPACE,
                "OtherOrg",
                "SomeEntity",
                0.6,
                Instant.parse("2026-02-20T10:00:00Z"),
                "auto discovered",
                "Discovered description"
        );

        DirectoryIdentity merged = parser.merge(existing, discovered);

        // Types: union — existing kept + new added
        assertEquals(List.of("meeting-notes", "minutes"), merged.acceptsTypes());
        assertEquals(List.of("md", "pdf"), merged.acceptsFormats());
        assertEquals(List.of("*meeting*", "*notes*"), merged.acceptsPatterns());

        // Scope: existing ORGANIZATION preserved (not overwritten by WORKSPACE)
        assertEquals(ScopeLevel.ORGANIZATION, merged.scopeLevel());
        assertEquals("eXOReaction", merged.scopeOrganization());
        // Entity: existing is null, so discovered fills in
        assertEquals("SomeEntity", merged.scopeEntity());

        // Confidence: max
        assertEquals(0.8, merged.confidence(), 0.001);

        // Source: existing preserved
        assertEquals("user declared", merged.source());

        // Description: existing preserved
        assertEquals("Existing description", merged.description());
    }

    // ---- P1-01: confidence-weighted transient merge ----

    @Test
    void merge_transient_bothAgree_resultMatches() {
        DirectoryIdentity transientExisting = identityWithTransient(true, 0.6);
        DirectoryIdentity transientDiscovered = identityWithTransient(true, 0.5);
        assertTrue(parser.merge(transientExisting, transientDiscovered).transient_(),
                "both true → true");

        DirectoryIdentity permanentExisting = identityWithTransient(false, 0.8);
        DirectoryIdentity permanentDiscovered = identityWithTransient(false, 0.7);
        assertFalse(parser.merge(permanentExisting, permanentDiscovered).transient_(),
                "both false → false");
    }

    @Test
    void merge_transient_highConfidencePermanentWinsOverLowConfidenceTransient() {
        // existing: permanent (false) at high confidence 0.94 (signals: many files)
        // discovered: transient (true) at low confidence 0.6 (vocabulary match)
        DirectoryIdentity existing = identityWithTransient(false, 0.94);
        DirectoryIdentity discovered = identityWithTransient(true, 0.6);
        assertFalse(parser.merge(existing, discovered).transient_(),
                "high-confidence permanent should override low-confidence transient");
    }

    @Test
    void merge_transient_highConfidenceTransientWinsOverLowConfidencePermanent() {
        // existing: transient (true) at 0.6, discovered: permanent (false) at 0.3
        DirectoryIdentity existing = identityWithTransient(true, 0.6);
        DirectoryIdentity discovered = identityWithTransient(false, 0.3);
        assertTrue(parser.merge(existing, discovered).transient_(),
                "high-confidence transient should win over low-confidence permanent");
    }

    @Test
    void merge_transient_closeConfidencePreservesExistingDesignation() {
        // discovered does not exceed existing by > 0.2 → existing intent preserved
        DirectoryIdentity existing = identityWithTransient(true, 0.55);
        DirectoryIdentity discovered = identityWithTransient(false, 0.50);
        assertTrue(parser.merge(existing, discovered).transient_(),
                "close confidence → existing (vocabulary) designation preserved");
    }

    @Test
    void merge_transient_significantlyHigherDiscoveredConfidenceOverrides() {
        // Simulates 11+ files (signals confidence 0.9) vs vocabulary confidence 0.6:
        // 0.9 > 0.6 + 0.2 → discovered wins → directory has settled, no longer transient
        DirectoryIdentity existing = identityWithTransient(true, 0.6);
        DirectoryIdentity discovered = identityWithTransient(false, 0.9);
        assertFalse(parser.merge(existing, discovered).transient_(),
                "11+ files (confidence 0.9) should override vocabulary transient=true");
    }

    /** Helper: minimal DirectoryIdentity with specific transient + confidence. */
    private DirectoryIdentity identityWithTransient(boolean transient_, double confidence) {
        return new DirectoryIdentity(
                List.of(), List.of(), List.of(),
                ScopeLevel.WORKSPACE, "", "",
                confidence, null, "test", "",
                List.of(), List.of(), transient_, List.of()
        );
    }

    @Test
    void merge_fillsEmptyFieldsFromDiscovered() {
        DirectoryIdentity existing = DirectoryIdentity.empty();

        DirectoryIdentity discovered = new DirectoryIdentity(
                List.of("reports"),
                List.of("pdf", "docx"),
                List.of("*report*"),
                ScopeLevel.ENTITY,
                "eXOReaction",
                "NordicEnergy",
                0.92,
                Instant.parse("2026-02-20T12:00:00Z"),
                "inferred from 5 files",
                "Auto-generated description"
        );

        DirectoryIdentity merged = parser.merge(existing, discovered);

        assertEquals(List.of("reports"), merged.acceptsTypes());
        assertEquals(List.of("pdf", "docx"), merged.acceptsFormats());
        assertEquals(List.of("*report*"), merged.acceptsPatterns());
        assertEquals(ScopeLevel.ENTITY, merged.scopeLevel());
        assertEquals("eXOReaction", merged.scopeOrganization());
        assertEquals("NordicEnergy", merged.scopeEntity());
        assertEquals(0.92, merged.confidence(), 0.001);
        assertEquals("inferred from 5 files", merged.source());
        assertEquals("Auto-generated description", merged.description());
    }
}
