package io.exoreaction.synthesis.graph;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeEdgeScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void scanFile_findsJavaClassReferences() throws IOException {
        Path skill = tempDir.resolve("my-skill.md");
        Files.writeString(skill, "Use SearchIndex to search. FileIndexer handles indexing.");

        Map<String, String> index = new HashMap<>();
        index.put("SearchIndex.java", "src/index/SearchIndex.java");
        index.put("FileIndexer.java", "src/indexer/FileIndexer.java");

        Path src = Files.createDirectory(tempDir.resolve("src"));
        Files.createDirectory(src.resolve("index"));
        Files.createDirectory(src.resolve("indexer"));
        Files.writeString(src.resolve("index/SearchIndex.java"), "// stub");
        Files.writeString(src.resolve("indexer/FileIndexer.java"), "// stub");

        KnowledgeEdgeScanner scanner = new KnowledgeEdgeScanner();
        List<KnowledgeEdge> edges = scanner.scanFile(skill, index, tempDir);

        assertTrue(edges.size() >= 2, "Should find at least 2 edges");
        assertTrue(edges.stream().anyMatch(e -> e.sourcePath().equals("src/index/SearchIndex.java")));
        assertTrue(edges.stream().anyMatch(e -> e.sourcePath().equals("src/indexer/FileIndexer.java")));
    }

    @Test
    void scanFile_noClassReferences_returnsEmpty() throws IOException {
        Path skill = tempDir.resolve("no-refs.md");
        Files.writeString(skill, "This file has no Java class names at all.");

        KnowledgeEdgeScanner scanner = new KnowledgeEdgeScanner();
        List<KnowledgeEdge> edges = scanner.scanFile(skill, Map.of(), tempDir);
        assertTrue(edges.isEmpty());
    }

    @Test
    void computeConfidence_currentSkill_returnsHigh() {
        assertEquals("HIGH", KnowledgeEdge.computeConfidence(-1));
        assertEquals("HIGH", KnowledgeEdge.computeConfidence(0));
    }

    @Test
    void computeConfidence_oneWeek_returnsMedium() {
        assertEquals("MEDIUM", KnowledgeEdge.computeConfidence(7));
    }

    @Test
    void computeConfidence_oneMonth_returnsLow() {
        assertEquals("LOW", KnowledgeEdge.computeConfidence(20));
    }

    @Test
    void computeConfidence_overMonth_returnsStale() {
        assertEquals("STALE", KnowledgeEdge.computeConfidence(31));
    }

    @Test
    void scanFile_skipsNonIndexedClasses() throws IOException {
        Path skill = tempDir.resolve("skill.md");
        Files.writeString(skill, "Use CamelCaseWord or AnotherThing for processing.");

        KnowledgeEdgeScanner scanner = new KnowledgeEdgeScanner();
        List<KnowledgeEdge> edges = scanner.scanFile(skill, Map.of(), tempDir);
        assertTrue(edges.isEmpty(), "Words not in index should not create edges");
    }

    @Test
    void scanFile_entityNameIsClassName() throws IOException {
        Path skill = tempDir.resolve("skill.md");
        Files.writeString(skill, "The MaintainCommand handles scheduled runs.");

        Map<String, String> index = new HashMap<>();
        index.put("MaintainCommand.java", "src/cli/MaintainCommand.java");
        Path srcDir = Files.createDirectories(tempDir.resolve("src/cli"));
        Files.writeString(srcDir.resolve("MaintainCommand.java"), "// stub");

        KnowledgeEdgeScanner scanner = new KnowledgeEdgeScanner();
        List<KnowledgeEdge> edges = scanner.scanFile(skill, index, tempDir);

        assertFalse(edges.isEmpty());
        assertTrue(edges.stream().anyMatch(e -> e.entityName().equals("MaintainCommand")));
    }

    @Test
    void scanFile_coverageTypeIsMentioned() throws IOException {
        Path skill = tempDir.resolve("skill.md");
        Files.writeString(skill, "The SearchIndex provides search functionality.");

        Map<String, String> index = new HashMap<>();
        index.put("SearchIndex.java", "src/SearchIndex.java");
        Path srcFile = tempDir.resolve("src/SearchIndex.java");
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, "// stub");

        KnowledgeEdgeScanner scanner = new KnowledgeEdgeScanner();
        List<KnowledgeEdge> edges = scanner.scanFile(skill, index, tempDir);

        assertFalse(edges.isEmpty());
        assertEquals("mentioned", edges.get(0).coverageType());
    }

    @Test
    void scanFile_multipleRefsToSameFile_createsOneEdgePerEntity() throws IOException {
        Path skill = tempDir.resolve("skill.md");
        Files.writeString(skill, "Use SearchIndex for search. SearchIndex is the core.");

        Map<String, String> index = new HashMap<>();
        index.put("SearchIndex.java", "src/SearchIndex.java");
        Path srcFile = tempDir.resolve("src/SearchIndex.java");
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, "// stub");

        KnowledgeEdgeScanner scanner = new KnowledgeEdgeScanner();
        List<KnowledgeEdge> edges = scanner.scanFile(skill, index, tempDir);

        assertEquals(1, edges.size(), "Multiple refs to same class should produce one edge");
        assertEquals("SearchIndex", edges.get(0).entityName());
    }

}
