package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.graph.RelationService;
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
 * Tests for the RelateCommand / RelationService (Relationship Mapping).
 */
class RelateCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void findBestMatchExactPath() {
        SearchResult exact = makeResult("src/main/Main.java", "CODE", "Java");
        SearchResult other = makeResult("test/MainTest.java", "CODE", "Java");

        RelationService svc = new RelationService();
        SearchResult match = svc.findBestMatch(List.of(other, exact), "src/main/Main.java");

        assertEquals("src/main/Main.java", match.relativePath());
    }

    @Test
    void findBestMatchByFileName() {
        SearchResult match = makeResult("deep/path/Config.yaml", "YAML", null);
        SearchResult other = makeResult("some/other/file.txt", "OTHER", null);

        RelationService svc = new RelationService();
        SearchResult result = svc.findBestMatch(List.of(other, match), "Config.yaml");

        assertEquals("deep/path/Config.yaml", result.relativePath());
    }

    @Test
    void findBestMatchFallsBackToSearchScore() {
        SearchResult best = makeResult("similar.java", "CODE", "Java");
        SearchResult ok = makeResult("other.java", "CODE", "Java");

        RelationService svc = new RelationService();
        SearchResult result = svc.findBestMatch(List.of(best, ok), "nonexistent.java");

        // Should return first result (highest score)
        assertEquals("similar.java", result.relativePath());
    }

    @Test
    void findBestMatchReturnsNullForEmpty() {
        RelationService svc = new RelationService();
        assertNull(svc.findBestMatch(List.of(), "anything.java"));
    }

    @Test
    void analyzeOutgoingRefsFindsJavaImports() throws IOException {
        Path javaFile = tempDir.resolve("Service.java");
        Files.writeString(javaFile, """
                package com.example;
                import com.example.Repository;
                import com.example.Config;

                public class Service {
                }
                """);

        SearchResult target = new SearchResult(
                javaFile, "Service.java", 1.0f, "Service.java",
                "CODE", "Java", "", "", "", Files.size(javaFile)
        );

        Map<String, List<String>> fileNameIndex = new HashMap<>();
        fileNameIndex.put("Repository.java", List.of("src/Repository.java"));
        fileNameIndex.put("Config.java", List.of("src/Config.java"));

        RelationService svc = new RelationService();
        RelationshipMap map = new RelationshipMap("Service.java");
        svc.analyzeOutgoingRefs(target, tempDir, map, fileNameIndex);

        assertFalse(map.outgoing().isEmpty(), "Should find outgoing references from imports");
    }

    @Test
    void analyzeOutgoingRefsFindsMarkdownLinks() throws IOException {
        Path mdFile = tempDir.resolve("README.md");
        Files.writeString(mdFile, """
                # Project

                See [setup guide](docs/SETUP.md) for details.
                Also check [the API](api/reference.md).
                And an external link [Google](https://google.com) which should be ignored.
                """);

        SearchResult target = new SearchResult(
                mdFile, "README.md", 1.0f, "README.md",
                "MARKDOWN", null, "", "", "", Files.size(mdFile)
        );

        Map<String, List<String>> fileNameIndex = new HashMap<>();
        fileNameIndex.put("SETUP.md", List.of("docs/SETUP.md"));
        fileNameIndex.put("reference.md", List.of("api/reference.md"));

        RelationService svc = new RelationService();
        RelationshipMap map = new RelationshipMap("README.md");
        svc.analyzeOutgoingRefs(target, tempDir, map, fileNameIndex);

        assertFalse(map.outgoing().isEmpty(), "Should find outgoing references from markdown links");
    }

    @Test
    void analyzeIncomingRefsFindsReferences() throws IOException {
        // Target file
        Path targetFile = tempDir.resolve("Config.java");
        Files.writeString(targetFile, "public class Config {}");

        // Referencing file
        Path referencingFile = tempDir.resolve("Service.java");
        Files.writeString(referencingFile, """
                import com.example.Config;
                public class Service {
                    private Config config;
                }
                """);

        SearchResult target = new SearchResult(
                targetFile, "Config.java", 1.0f, "Config.java",
                "CODE", "Java", "", "", "", Files.size(targetFile)
        );

        SearchResult referencing = new SearchResult(
                referencingFile, "Service.java", 1.0f, "Service.java",
                "CODE", "Java", "", "", "", Files.size(referencingFile)
        );

        RelationService svc = new RelationService();
        RelationshipMap map = new RelationshipMap("Config.java");
        svc.analyzeIncomingRefs(target, List.of(target, referencing), tempDir, map);

        assertTrue(map.incoming().containsKey("Service.java"),
                "Should find Service.java as referencing Config.java");
    }

    @Test
    void analyzeIncomingRefsExcludesTargetFile() throws IOException {
        Path targetFile = tempDir.resolve("Self.java");
        Files.writeString(targetFile, "public class Self { // references Self }");

        SearchResult target = new SearchResult(
                targetFile, "Self.java", 1.0f, "Self.java",
                "CODE", "Java", "", "", "", Files.size(targetFile)
        );

        RelationService svc = new RelationService();
        RelationshipMap map = new RelationshipMap("Self.java");
        svc.analyzeIncomingRefs(target, List.of(target), tempDir, map);

        assertTrue(map.incoming().isEmpty(),
                "Should not include self-references");
    }

    @Test
    void resolveReferenceFindsFileByName() {
        Map<String, List<String>> index = new HashMap<>();
        index.put("Config.java", List.of("src/main/Config.java"));

        RelationService svc = new RelationService();
        String resolved = svc.resolveReference("Config.java", "Service.java", index);

        assertEquals("src/main/Config.java", resolved);
    }

    @Test
    void resolveReferenceFindsJavaImport() {
        Map<String, List<String>> index = new HashMap<>();
        index.put("Repository.java", List.of("src/data/Repository.java"));

        RelationService svc = new RelationService();
        String resolved = svc.resolveReference("com.example.data.Repository", "Service.java", index);

        assertEquals("src/data/Repository.java", resolved);
    }

    @Test
    void resolveReferenceReturnsNullForUnknown() {
        Map<String, List<String>> index = new HashMap<>();

        RelationService svc = new RelationService();
        String resolved = svc.resolveReference("Unknown.java", "Service.java", index);

        assertNull(resolved);
    }

    @Test
    void generateMermaidProducesValidDiagram() {
        RelationshipMap map = new RelationshipMap("Main.java");
        map.addOutgoing("Config.java", "imports");
        map.addOutgoing("Service.java", "imports");
        map.addIncoming("Test.java", "references");

        RelationService svc = new RelationService();
        String mermaid = svc.generateMermaid(map);

        assertTrue(mermaid.contains("```mermaid"), "Should have mermaid code block");
        assertTrue(mermaid.contains("graph LR"), "Should be left-right graph");
        assertTrue(mermaid.contains("Main_java"), "Should include target node");
        assertTrue(mermaid.contains("Config_java"), "Should include outgoing nodes");
        assertTrue(mermaid.contains("-->"), "Should have edges");
        assertTrue(mermaid.contains("```"), "Should close code block");
    }

    @Test
    void relationshipMapMaintainsBidirectionalData() {
        RelationshipMap map = new RelationshipMap("target.java");
        map.addOutgoing("dep1.java", "imports");
        map.addOutgoing("dep2.java", "references");
        map.addIncoming("user1.java", "references");

        assertEquals("target.java", map.targetFile());
        assertEquals(2, map.outgoing().size());
        assertEquals(1, map.incoming().size());
        assertTrue(map.outgoing().containsKey("dep1.java"));
        assertTrue(map.incoming().containsKey("user1.java"));
    }

    // Helper method
    private SearchResult makeResult(String path, String type, String language) {
        String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        return new SearchResult(
                tempDir.resolve(path), path, 1.0f, fileName,
                type, language, "", "", "", 100
        );
    }
}
