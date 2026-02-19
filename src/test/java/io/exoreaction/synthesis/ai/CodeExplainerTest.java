package io.exoreaction.synthesis.ai;

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
 * Unit tests for {@link CodeExplainer}.
 *
 * <p>Tests the relationship-gathering logic by exercising {@link RelationService}
 * directly — the same path {@code CodeExplainer.gatherRelationships()} uses —
 * without requiring a real AI client.
 */
class CodeExplainerTest {

    @TempDir
    Path tempDir;

    // --- gatherRelationships (via RelationService directly) ---

    @Test
    void gatherRelationships_javaFileWithImports_detectsOutgoingRefs() throws IOException {
        // Service.java imports Repository.java and Config.java
        Path serviceFile = tempDir.resolve("Service.java");
        Files.writeString(serviceFile, """
                package com.example;
                import com.example.Repository;
                import com.example.Config;

                public class Service {}
                """);

        SearchResult target = makeResult(serviceFile, "Service.java", "CODE", "Java");

        Map<String, List<String>> fileNameIndex = new HashMap<>();
        fileNameIndex.put("Repository.java", List.of("src/Repository.java"));
        fileNameIndex.put("Config.java", List.of("src/Config.java"));

        RelationService relater = new RelationService();
        RelationshipMap map = new RelationshipMap(target.relativePath());
        relater.analyzeOutgoingRefs(target, tempDir, map, fileNameIndex);

        assertTrue(map.outgoing().containsKey("src/Repository.java"),
                "Expected outgoing ref to Repository.java");
        assertTrue(map.outgoing().containsKey("src/Config.java"),
                "Expected outgoing ref to Config.java");
    }

    @Test
    void gatherRelationships_incomingRefs_detectedFromOtherFiles() throws IOException {
        // Controller.java references Service.java by name
        Path serviceFile = tempDir.resolve("Service.java");
        Files.writeString(serviceFile, "public class Service {}");

        Path controllerFile = tempDir.resolve("Controller.java");
        Files.writeString(controllerFile, """
                import com.example.Service;
                public class Controller {
                    private Service service;
                }
                """);

        SearchResult target = makeResult(serviceFile, "Service.java", "CODE", "Java");
        SearchResult controller = makeResult(controllerFile, "Controller.java", "CODE", "Java");

        RelationService relater = new RelationService();
        RelationshipMap map = new RelationshipMap(target.relativePath());
        relater.analyzeIncomingRefs(target, List.of(target, controller), tempDir, map);

        assertTrue(map.incoming().containsKey("Controller.java"),
                "Expected Controller.java to reference Service.java");
    }

    @Test
    void gatherRelationships_noRelationships_returnsEmptyMap() throws IOException {
        Path isolatedFile = tempDir.resolve("Isolated.java");
        Files.writeString(isolatedFile, "public class Isolated {}");

        SearchResult target = makeResult(isolatedFile, "Isolated.java", "CODE", "Java");

        RelationService relater = new RelationService();
        RelationshipMap map = new RelationshipMap(target.relativePath());
        relater.analyzeOutgoingRefs(target, tempDir, map, Collections.emptyMap());
        relater.analyzeIncomingRefs(target, List.of(target), tempDir, map);

        assertTrue(map.outgoing().isEmpty(), "Expected no outgoing refs");
        assertTrue(map.incoming().isEmpty(), "Expected no incoming refs");
    }

    @Test
    void gatherRelationships_markdownFile_detectsLinks() throws IOException {
        Path mdFile = tempDir.resolve("README.md");
        Files.writeString(mdFile, """
                # Readme
                See [setup](INSTALL.md) and [contributing](CONTRIBUTING.md).
                """);

        SearchResult target = makeResult(mdFile, "README.md", "MARKDOWN", null);

        Map<String, List<String>> fileNameIndex = new HashMap<>();
        fileNameIndex.put("INSTALL.md", List.of("docs/INSTALL.md"));

        RelationService relater = new RelationService();
        RelationshipMap map = new RelationshipMap(target.relativePath());
        relater.analyzeOutgoingRefs(target, tempDir, map, fileNameIndex);

        assertTrue(map.outgoing().containsKey("docs/INSTALL.md"),
                "Expected outgoing link to INSTALL.md");
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private SearchResult makeResult(Path absolutePath, String fileName, String fileType, String language) {
        String relativePath = tempDir.relativize(absolutePath).toString();
        long size = 0;
        try { size = Files.size(absolutePath); } catch (IOException ignored) {}
        return new SearchResult(absolutePath, relativePath, 1.0f, fileName,
                fileType, language, "", "", "", size);
    }
}
