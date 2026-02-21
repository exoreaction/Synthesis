package io.exoreaction.synthesis.org;

import io.exoreaction.synthesis.org.DirectoryScorer.DirectoryCandidate;
import io.exoreaction.synthesis.org.DirectoryScorer.ScoredCandidate;
import io.exoreaction.synthesis.org.ScopeResolver.ResolvedScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for issue #199/#205: transient flag, aliases, rejectsTypes,
 * and movedFiles fields on DirectoryIdentity.
 */
class TransientIdentityTest {

    @TempDir
    Path tempDir;

    private final DirectoryIdentityParser parser = new DirectoryIdentityParser();

    // ---- Vocabulary defaults ----

    @Test
    void marketing_hasTransientTrue_byDefault() {
        DirectoryNameVocabulary vocabulary = new DirectoryNameVocabulary();
        ResolvedScope scope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        Optional<DirectoryIdentity> result = vocabulary.inferFromName("marketing", scope);

        assertTrue(result.isPresent());
        assertTrue(result.get().transient_(),
                "marketing/ should have transient=true by default from vocabulary");
    }

    @Test
    void staging_hasTransientTrue_byDefault() {
        DirectoryNameVocabulary vocabulary = new DirectoryNameVocabulary();
        ResolvedScope scope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        Optional<DirectoryIdentity> result = vocabulary.inferFromName("staging", scope);

        assertTrue(result.isPresent());
        assertTrue(result.get().transient_(),
                "staging/ should have transient=true by default from vocabulary");
    }

    @Test
    void incoming_hasTransientTrue_byDefault() {
        DirectoryNameVocabulary vocabulary = new DirectoryNameVocabulary();
        ResolvedScope scope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        Optional<DirectoryIdentity> result = vocabulary.inferFromName("incoming", scope);

        assertTrue(result.isPresent());
        assertTrue(result.get().transient_(),
                "incoming/ should have transient=true by default from vocabulary");
    }

    @Test
    void articles_hasRejectsTypes_videoMediaAudio() {
        DirectoryNameVocabulary vocabulary = new DirectoryNameVocabulary();
        ResolvedScope scope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        Optional<DirectoryIdentity> result = vocabulary.inferFromName("articles", scope);

        assertTrue(result.isPresent());
        assertEquals(List.of("video", "media", "audio"), result.get().rejectsTypes(),
                "articles/ should reject video, media, audio by default");
        assertFalse(result.get().transient_(),
                "articles/ should NOT be transient");
    }

    // ---- Parser roundtrip: transient ----

    @Test
    void transientFlag_roundtrips_throughParser() throws IOException {
        Path file = tempDir.resolve(".synthesis.md");

        DirectoryIdentity original = new DirectoryIdentity(
                List.of("marketing"), List.of("md", "pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of()
        );
        parser.write(file, original);

        DirectoryIdentity parsed = parser.parse(file);

        assertTrue(parsed.transient_(),
                "transient flag should roundtrip through .synthesis.md parser");
    }

    @Test
    void transientFalse_notEmitted_inYaml() throws IOException {
        Path file = tempDir.resolve(".synthesis.md");

        DirectoryIdentity original = new DirectoryIdentity(
                List.of("docs"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), false, List.of()
        );
        parser.write(file, original);

        String content = Files.readString(file);
        assertFalse(content.contains("transient:"),
                "transient: should be omitted when false");

        DirectoryIdentity parsed = parser.parse(file);
        assertFalse(parsed.transient_());
    }

    // ---- Parser roundtrip: aliases ----

    @Test
    void aliases_roundtrip_throughParser() throws IOException {
        Path file = tempDir.resolve(".synthesis.md");

        DirectoryIdentity original = new DirectoryIdentity(
                List.of("media"), List.of("mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "",
                List.of(), List.of("synthesis", "knowledge-infrastructure"), false, List.of()
        );
        parser.write(file, original);

        DirectoryIdentity parsed = parser.parse(file);

        assertEquals(List.of("synthesis", "knowledge-infrastructure"), parsed.aliases(),
                "aliases should roundtrip through .synthesis.md parser");
    }

    // ---- Parser roundtrip: rejectsTypes ----

    @Test
    void rejectsTypes_roundtrip_throughParser() throws IOException {
        Path file = tempDir.resolve(".synthesis.md");

        DirectoryIdentity original = new DirectoryIdentity(
                List.of("article"), List.of("md", "pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.7, null, "test", "",
                List.of("video", "media", "audio"), List.of(), false, List.of()
        );
        parser.write(file, original);

        DirectoryIdentity parsed = parser.parse(file);

        assertEquals(List.of("video", "media", "audio"), parsed.rejectsTypes(),
                "rejectsTypes should roundtrip through .synthesis.md parser");
    }

    // ---- Parser roundtrip: movedFiles (forwarding pointers) ----

    @Test
    void movedFiles_roundtrip_throughParser() throws IOException {
        Path file = tempDir.resolve(".synthesis.md");

        Instant movedAt = Instant.parse("2026-02-20T10:00:00Z");
        ForwardingPointer pointer = new ForwardingPointer(
                "synthesis-demo.mp4", "products/Synthesis/media", movedAt, "rebalance", "score 0.89");

        DirectoryIdentity original = new DirectoryIdentity(
                List.of("marketing"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of(pointer)
        );
        parser.write(file, original);

        DirectoryIdentity parsed = parser.parse(file);

        assertEquals(1, parsed.movedFiles().size(),
                "Should have 1 forwarding pointer after roundtrip");
        ForwardingPointer parsedPointer = parsed.movedFiles().get(0);
        assertEquals("synthesis-demo.mp4", parsedPointer.fileName());
        assertEquals("products/Synthesis/media", parsedPointer.movedTo());
        assertEquals(movedAt, parsedPointer.movedAt());
        assertEquals("rebalance", parsedPointer.movedBy());
        assertEquals("score 0.89", parsedPointer.reason());
    }

    // ---- DirectoryScorer: rejectsTypes hard rejection ----

    @Test
    void scorer_returns0_forMp4Against_articlesWithRejectsTypes() {
        ScopeChecker scopeChecker = new ScopeChecker();
        DirectoryScorer scorer = new DirectoryScorer(scopeChecker);

        Path file = tempDir.resolve("video-demo.mp4");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        // articles/ identity with rejectsTypes
        DirectoryIdentity articlesIdentity = new DirectoryIdentity(
                List.of("article", "documentation"), List.of("md", "pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "",
                List.of("video", "media", "audio"), List.of(), false, List.of()
        );

        List<DirectoryCandidate> candidates = List.of(
                new DirectoryCandidate(tempDir.resolve("articles"), articlesIdentity));
        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        assertEquals(0.0, results.get(0).totalScore(),
                "mp4 should score 0.0 against directory with rejectsTypes=[video, media, audio]");
        assertTrue(results.get(0).blocked(),
                "mp4 should be blocked against articles/ with rejectsTypes");
    }

    @Test
    void scorer_returnsPositive_forMp4Against_marketingTransient() {
        ScopeChecker scopeChecker = new ScopeChecker();
        DirectoryScorer scorer = new DirectoryScorer(scopeChecker);

        Path file = tempDir.resolve("promo-video.mp4");
        ResolvedScope fileScope = new ResolvedScope(ScopeLevel.WORKSPACE, null, null);

        // marketing/ identity: transient but no rejectsTypes
        DirectoryIdentity marketingIdentity = new DirectoryIdentity(
                List.of("marketing"), List.of("md", "pdf", "png", "mp4"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "test", "",
                List.of(), List.of(), true, List.of()
        );

        List<DirectoryCandidate> candidates = List.of(
                new DirectoryCandidate(tempDir.resolve("marketing"), marketingIdentity));
        List<ScoredCandidate> results = scorer.score(file, fileScope, candidates);

        assertEquals(1, results.size());
        assertTrue(results.get(0).totalScore() > 0,
                "mp4 should score > 0 against marketing/ (transient, no rejectsTypes), "
                + "score=" + results.get(0).totalScore());
    }

    // ---- merge() preserves new fields ----

    @Test
    void merge_preserves_transient_aliases_rejectsTypes() {
        DirectoryIdentity existing = new DirectoryIdentity(
                List.of("marketing"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.6, null, "existing", "",
                List.of("video"), List.of("promo"), true, List.of()
        );

        DirectoryIdentity discovered = new DirectoryIdentity(
                List.of("marketing", "sales"), List.of("md", "pdf"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.7, null, "discovered", "",
                List.of("audio"), List.of("campaign"), false, List.of()
        );

        DirectoryIdentity merged = parser.merge(existing, discovered);

        // transient: true from existing (OR semantics)
        assertTrue(merged.transient_(),
                "merge() should preserve transient=true from existing");
        // rejectsTypes: union
        assertTrue(merged.rejectsTypes().contains("video"),
                "merge() should preserve existing rejectsTypes");
        assertTrue(merged.rejectsTypes().contains("audio"),
                "merge() should add discovered rejectsTypes");
        // aliases: union
        assertTrue(merged.aliases().contains("promo"),
                "merge() should preserve existing aliases");
        assertTrue(merged.aliases().contains("campaign"),
                "merge() should add discovered aliases");
    }

    // ---- backward compatibility ----

    @Test
    void backwardCompatible10ArgConstructor_setsDefaultsForNewFields() {
        DirectoryIdentity identity = new DirectoryIdentity(
                List.of("docs"), List.of("md"), List.of(),
                ScopeLevel.WORKSPACE, null, null,
                0.8, null, "test", "desc"
        );

        assertFalse(identity.transient_());
        assertTrue(identity.aliases().isEmpty());
        assertTrue(identity.rejectsTypes().isEmpty());
        assertTrue(identity.movedFiles().isEmpty());
    }

    @Test
    void empty_hasDefaultsForNewFields() {
        DirectoryIdentity empty = DirectoryIdentity.empty();

        assertFalse(empty.transient_());
        assertTrue(empty.aliases().isEmpty());
        assertTrue(empty.rejectsTypes().isEmpty());
        assertTrue(empty.movedFiles().isEmpty());
    }
}
