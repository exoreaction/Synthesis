package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.graph.RelationService.RelationshipMap;
import io.exoreaction.synthesis.index.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RelationService}.
 */
class RelationServiceTest {

    @TempDir
    Path tempDir;

    private final RelationService service = new RelationService();

    @Test
    void findBestMatch_exactPathMatch() {
        SearchResult exact = makeResult("src/main/Foo.java", "Foo.java");
        SearchResult other = makeResult("src/test/Foo.java", "Foo.java");
        assertEquals("src/main/Foo.java",
                service.findBestMatch(List.of(other, exact), "src/main/Foo.java").relativePath());
    }

    @Test
    void findBestMatch_exactFileNameMatch() {
        SearchResult target = makeResult("deep/nested/Config.java", "Config.java");
        SearchResult other = makeResult("somewhere/Other.java", "Other.java");
        assertEquals("deep/nested/Config.java",
                service.findBestMatch(List.of(other, target), "Config.java").relativePath());
    }

    @Test
    void findBestMatch_emptyList_returnsNull() {
        assertNull(service.findBestMatch(List.of(), "Foo.java"));
    }

    // #430: relate/impact silently resolve ambiguous bare filenames to the wrong file

    @Test
    void findAmbiguousMatches_bareFilenameWithTwoCandidates_returnsTheOther() {
        SearchResult chosen = makeResult("src/main/graph/ProbeMarker.java", "ProbeMarker.java");
        SearchResult other = makeResult("src/main/cli/ProbeMarker.java", "ProbeMarker.java");
        List<SearchResult> results = List.of(chosen, other);

        SearchResult best = service.findBestMatch(results, "ProbeMarker.java");
        List<SearchResult> ambiguous = service.findAmbiguousMatches(results, "ProbeMarker.java", best);

        assertEquals(1, ambiguous.size());
        assertEquals("src/main/cli/ProbeMarker.java", ambiguous.get(0).relativePath());
    }

    @Test
    void findAmbiguousMatches_exactPathRequested_returnsEmptyEvenWithSameNameElsewhere() {
        SearchResult chosen = makeResult("src/main/graph/ProbeMarker.java", "ProbeMarker.java");
        SearchResult other = makeResult("src/main/cli/ProbeMarker.java", "ProbeMarker.java");
        List<SearchResult> results = List.of(chosen, other);

        SearchResult best = service.findBestMatch(results, "src/main/graph/ProbeMarker.java");
        List<SearchResult> ambiguous = service.findAmbiguousMatches(results, "src/main/graph/ProbeMarker.java", best);

        assertTrue(ambiguous.isEmpty(),
                "an explicit, exact path was requested -- not ambiguous even if another file shares the name");
    }

    @Test
    void findAmbiguousMatches_singleCandidate_returnsEmpty() {
        SearchResult only = makeResult("src/main/Config.java", "Config.java");
        List<SearchResult> results = List.of(only);

        SearchResult best = service.findBestMatch(results, "Config.java");
        List<SearchResult> ambiguous = service.findAmbiguousMatches(results, "Config.java", best);

        assertTrue(ambiguous.isEmpty());
    }

    @Test
    void findAmbiguousMatches_nullChosen_returnsEmpty() {
        assertTrue(service.findAmbiguousMatches(List.of(), "Foo.java", null).isEmpty());
    }

    @Test
    void findAmbiguousMatches_pathSuffixMatch_notAmbiguousDespiteSameNameElsewhere() {
        // target has "/" but isn't a full path -- still resolves via findBestMatch's
        // path-suffix tier, and should be treated the same as an exact-path request.
        SearchResult chosen = makeResult("src/main/graph/ProbeMarker.java", "ProbeMarker.java");
        SearchResult other = makeResult("src/main/cli/ProbeMarker.java", "ProbeMarker.java");
        List<SearchResult> results = List.of(chosen, other);

        SearchResult best = service.findBestMatch(results, "graph/ProbeMarker.java");
        List<SearchResult> ambiguous = service.findAmbiguousMatches(results, "graph/ProbeMarker.java", best);

        assertTrue(ambiguous.isEmpty());
    }

    @Test
    void findAmbiguousMatches_nonMatchingSlashTarget_arbitraryFallbackIsAmbiguous() {
        // target contains "/" but matches neither tier 1 (no path suffix match) nor tier 2
        // (fileName never equals a path) -- findBestMatch falls through to results.get(0),
        // an arbitrary pick that should be flagged whenever there's more than one candidate.
        SearchResult chosen = makeResult("src/main/Unrelated.java", "Unrelated.java");
        SearchResult other = makeResult("src/main/OtherFile.java", "OtherFile.java");
        List<SearchResult> results = List.of(chosen, other);

        SearchResult best = service.findBestMatch(results, "bogus/does/not/exist.java");
        List<SearchResult> ambiguous = service.findAmbiguousMatches(results, "bogus/does/not/exist.java", best);

        assertEquals(1, ambiguous.size());
    }

    @Test
    void formatAmbiguityWarning_ambiguous_includesOtherPaths() {
        SearchResult chosen = makeResult("src/main/graph/ProbeMarker.java", "ProbeMarker.java");
        SearchResult other = makeResult("src/main/cli/ProbeMarker.java", "ProbeMarker.java");
        List<SearchResult> results = List.of(chosen, other);

        SearchResult best = service.findBestMatch(results, "ProbeMarker.java");
        String warning = service.formatAmbiguityWarning(results, "ProbeMarker.java", best);

        assertNotNull(warning);
        assertTrue(warning.contains("src/main/cli/ProbeMarker.java"));
    }

    @Test
    void formatAmbiguityWarning_unambiguous_returnsNull() {
        SearchResult only = makeResult("src/main/Config.java", "Config.java");
        List<SearchResult> results = List.of(only);
        SearchResult best = service.findBestMatch(results, "Config.java");
        assertNull(service.formatAmbiguityWarning(results, "Config.java", best));
    }

    @Test
    void analyzeOutgoingRefs_javaImports_resolved() throws IOException {
        Path javaFile = tempDir.resolve("Service.java");
        Files.writeString(javaFile, """
                package com.example;
                import com.example.Repository;
                import com.example.Config;
                public class Service {}
                """);
        SearchResult target = makeResultWithFile(javaFile, "Service.java", "CODE", "Java");
        Map<String, List<String>> idx = new HashMap<>();
        idx.put("Repository.java", List.of("src/Repository.java"));
        idx.put("Config.java", List.of("src/Config.java"));

        RelationshipMap map = new RelationshipMap(target.relativePath());
        service.analyzeOutgoingRefs(target, tempDir, map, idx);

        assertTrue(map.outgoing().containsKey("src/Repository.java"));
        assertTrue(map.outgoing().containsKey("src/Config.java"));
    }

    @Test
    void analyzeOutgoingRefs_kotlinImports_resolved() throws IOException {
        Path ktFile = tempDir.resolve("Service.kt");
        Files.writeString(ktFile, """
                package com.example
                import com.example.Repository
                import com.example.Config
                class Service
                """);
        SearchResult target = makeResultWithFile(ktFile, "Service.kt", "CODE", "Kotlin");
        Map<String, List<String>> idx = new HashMap<>();
        idx.put("Repository.kt", List.of("src/Repository.kt"));
        idx.put("Config.kt", List.of("src/Config.kt"));

        RelationshipMap map = new RelationshipMap(target.relativePath());
        service.analyzeOutgoingRefs(target, tempDir, map, idx);

        assertTrue(map.outgoing().containsKey("src/Repository.kt"));
        assertTrue(map.outgoing().containsKey("src/Config.kt"));
    }

    @Test
    void analyzeOutgoingRefs_kotlinWildcardImport_notResolved() throws IOException {
        Path ktFile = tempDir.resolve("Service.kt");
        Files.writeString(ktFile, """
                package com.example
                import com.example.*
                class Service
                """);
        SearchResult target = makeResultWithFile(ktFile, "Service.kt", "CODE", "Kotlin");
        Map<String, List<String>> idx = Map.of("Config.kt", List.of("src/Config.kt"));

        RelationshipMap map = new RelationshipMap(target.relativePath());
        service.analyzeOutgoingRefs(target, tempDir, map, idx);

        assertTrue(map.outgoing().isEmpty());
    }

    @Test
    void analyzeOutgoingRefs_kotlinAliasedImport_resolved() throws IOException {
        Path ktFile = tempDir.resolve("Service.kt");
        Files.writeString(ktFile, """
                package com.example
                import com.example.Repository as Repo
                class Service
                """);
        SearchResult target = makeResultWithFile(ktFile, "Service.kt", "CODE", "Kotlin");
        Map<String, List<String>> idx = Map.of("Repository.kt", List.of("src/Repository.kt"));

        RelationshipMap map = new RelationshipMap(target.relativePath());
        service.analyzeOutgoingRefs(target, tempDir, map, idx);

        assertTrue(map.outgoing().containsKey("src/Repository.kt"));
    }

    @Test
    void analyzeOutgoingRefs_markdownLinks() throws IOException {
        Path mdFile = tempDir.resolve("README.md");
        Files.writeString(mdFile, "See [setup](INSTALL.md) and [contributing](CONTRIBUTING.md).");
        SearchResult target = makeResultWithFile(mdFile, "README.md", "MARKDOWN", null);
        Map<String, List<String>> idx = Map.of("INSTALL.md", List.of("docs/INSTALL.md"));

        RelationshipMap map = new RelationshipMap(target.relativePath());
        service.analyzeOutgoingRefs(target, tempDir, map, idx);

        assertTrue(map.outgoing().containsKey("docs/INSTALL.md"));
    }

    @Test
    void analyzeIncomingRefs_detectedByFileName() throws IOException {
        Path svc = tempDir.resolve("Service.java");
        Files.writeString(svc, "public class Service {}");
        Path ctrl = tempDir.resolve("Controller.java");
        Files.writeString(ctrl, "import com.example.Service;\npublic class Controller { private Service s; }");

        SearchResult target = makeResultWithFile(svc, "Service.java", "CODE", "Java");
        SearchResult controller = makeResultWithFile(ctrl, "Controller.java", "CODE", "Java");

        RelationshipMap map = new RelationshipMap(target.relativePath());
        service.analyzeIncomingRefs(target, List.of(target, controller), tempDir, map);

        assertTrue(map.incoming().containsKey("Controller.java"));
    }

    @Test
    void resolveReference_javaPackageNotation() {
        Map<String, List<String>> idx = Map.of("Config.java", List.of("src/Config.java"));
        assertEquals("src/Config.java", service.resolveReference("com.example.Config", "src/Main.java", idx));
    }

    @Test
    void resolveReference_kotlinPackageNotation() {
        Map<String, List<String>> idx = Map.of("Config.kt", List.of("src/Config.kt"));
        assertEquals("src/Config.kt", service.resolveReference("com.example.Config", "src/Main.kt", idx));
    }

    @Test
    void resolveReference_javaKotlinStemCollision_prefersJava() {
        // Documents current (imperfect) behavior: resolveReference has no visibility into the
        // source file's language, so a same-simple-name Java/Kotlin collision deterministically
        // resolves to .java first (#439 known limitation -- see RelationService.java comment).
        Map<String, List<String>> idx = Map.of(
                "Config.java", List.of("src/Config.java"),
                "Config.kt", List.of("src/Config.kt"));
        assertEquals("src/Config.java", service.resolveReference("com.example.Config", "src/Main.kt", idx));
    }

    @Test
    void resolveReference_nullOrBlank_returnsNull() {
        assertNull(service.resolveReference(null, "src/Main.java", Map.of()));
        assertNull(service.resolveReference("  ", "src/Main.java", Map.of()));
    }

    @Test
    void generateMermaid_producesValidDiagram() {
        RelationshipMap map = new RelationshipMap("src/Main.java");
        map.addOutgoing("src/Config.java", "imports/references");
        map.addIncoming("src/App.java", "references");

        String mermaid = service.generateMermaid(map);
        assertTrue(mermaid.contains("```mermaid"));
        assertTrue(mermaid.contains("graph LR"));
        assertTrue(mermaid.contains("-->"));
    }

    @Test
    void buildFileNameIndex_groupsByFileName() {
        SearchResult a = makeResult("src/Config.java", "Config.java");
        SearchResult b = makeResult("test/Config.java", "Config.java");
        SearchResult c = makeResult("src/Main.java", "Main.java");

        Map<String, List<String>> idx = service.buildFileNameIndex(List.of(a, b, c));
        assertEquals(2, idx.get("Config.java").size());
        assertEquals(1, idx.get("Main.java").size());
    }

    @Test
    void relationshipMap_collectionsAreUnmodifiable() {
        RelationshipMap map = new RelationshipMap("target.java");
        map.addOutgoing("a.java", "imports");
        assertThrows(UnsupportedOperationException.class, () -> map.outgoing().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> map.incoming().put("x", "y"));
    }

    private SearchResult makeResult(String relativePath, String fileName) {
        return new SearchResult(tempDir.resolve(relativePath), relativePath, 1.0f, fileName,
                "CODE", "Java", "", "", "", 100L);
    }

    private SearchResult makeResultWithFile(Path absolutePath, String fileName, String fileType, String language) {
        String relativePath = tempDir.relativize(absolutePath).toString();
        long size = 0;
        try { size = Files.size(absolutePath); } catch (IOException ignored) {}
        return new SearchResult(absolutePath, relativePath, 1.0f, fileName, fileType, language, "", "", "", size);
    }
}
