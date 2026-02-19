package io.exoreaction.synthesis.cli;

import io.exoreaction.synthesis.analyzer.AnalysisResult;
import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.graph.RelationService;
import io.exoreaction.synthesis.index.FileIndexer;
import io.exoreaction.synthesis.index.SearchIndex;
import io.exoreaction.synthesis.index.SearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TraceCommand} — import-graph path tracing.
 *
 * <p>Uses a real Lucene index written to a TempDir with Java files
 * that contain import statements forming a known dependency graph.
 */
class TraceCommandTest {

    @TempDir
    Path tempDir;

    private SearchIndex index;
    private FileIndexer fileIndexer;
    private RelationService relationService;

    @BeforeEach
    void setUp() throws IOException {
        index = new SearchIndex(tempDir.resolve("index"));
        fileIndexer = new FileIndexer();
        relationService = new RelationService();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (index != null) index.close();
    }

    @Test
    void pathFound_chainA_B_C() throws IOException {
        // A imports B, B imports C => path A -> B -> C
        addJavaFile("A.java", """
                package com.example;
                import com.example.B;
                public class A {}
                """);
        addJavaFile("B.java", """
                package com.example;
                import com.example.C;
                public class B {}
                """);
        addJavaFile("C.java", """
                package com.example;
                public class C {}
                """);

        List<SearchResult> allFiles = index.listAll(null, 100);
        Map<String, List<String>> fileNameIndex = relationService.buildFileNameIndex(allFiles);
        Map<String, SearchResult> fileMap = buildFileMap(allFiles);

        SearchResult startFile = findByName(allFiles, "A.java");
        SearchResult endFile = findByName(allFiles, "C.java");

        TraceCommand cmd = new TraceCommand(relationService);
        cmd.maxDepth = 10;
        List<String> path = cmd.bfsTrace(startFile, endFile, tempDir, allFiles, fileNameIndex, fileMap);

        assertNotNull(path, "Path should be found from A to C");
        assertEquals(3, path.size());
        assertTrue(path.get(0).endsWith("A.java"));
        assertTrue(path.get(1).endsWith("B.java"));
        assertTrue(path.get(2).endsWith("C.java"));
    }

    @Test
    void noPath_disconnectedFiles() throws IOException {
        // A and C have no import relationship
        addJavaFile("A.java", """
                package com.example;
                public class A {}
                """);
        addJavaFile("C.java", """
                package com.example;
                public class C {}
                """);

        List<SearchResult> allFiles = index.listAll(null, 100);
        Map<String, List<String>> fileNameIndex = relationService.buildFileNameIndex(allFiles);
        Map<String, SearchResult> fileMap = buildFileMap(allFiles);

        SearchResult startFile = findByName(allFiles, "A.java");
        SearchResult endFile = findByName(allFiles, "C.java");

        TraceCommand cmd = new TraceCommand(relationService);
        cmd.maxDepth = 10;
        List<String> path = cmd.bfsTrace(startFile, endFile, tempDir, allFiles, fileNameIndex, fileMap);

        assertNull(path, "No path should exist between disconnected files");
    }

    @Test
    void directConnection_oneHop() throws IOException {
        // A imports B directly
        addJavaFile("A.java", """
                package com.example;
                import com.example.B;
                public class A {}
                """);
        addJavaFile("B.java", """
                package com.example;
                public class B {}
                """);

        List<SearchResult> allFiles = index.listAll(null, 100);
        Map<String, List<String>> fileNameIndex = relationService.buildFileNameIndex(allFiles);
        Map<String, SearchResult> fileMap = buildFileMap(allFiles);

        SearchResult startFile = findByName(allFiles, "A.java");
        SearchResult endFile = findByName(allFiles, "B.java");

        TraceCommand cmd = new TraceCommand(relationService);
        cmd.maxDepth = 10;
        List<String> path = cmd.bfsTrace(startFile, endFile, tempDir, allFiles, fileNameIndex, fileMap);

        assertNotNull(path, "Direct path should be found");
        assertEquals(2, path.size());
        assertTrue(path.get(0).endsWith("A.java"));
        assertTrue(path.get(1).endsWith("B.java"));
    }

    @Test
    void maxDepthLimit_respected() throws IOException {
        // Chain: A -> B -> C -> D, but maxDepth=2 should not find A->D
        addJavaFile("A.java", """
                package com.example;
                import com.example.B;
                public class A {}
                """);
        addJavaFile("B.java", """
                package com.example;
                import com.example.C;
                public class B {}
                """);
        addJavaFile("C.java", """
                package com.example;
                import com.example.D;
                public class C {}
                """);
        addJavaFile("D.java", """
                package com.example;
                public class D {}
                """);

        List<SearchResult> allFiles = index.listAll(null, 100);
        Map<String, List<String>> fileNameIndex = relationService.buildFileNameIndex(allFiles);
        Map<String, SearchResult> fileMap = buildFileMap(allFiles);

        SearchResult startFile = findByName(allFiles, "A.java");
        SearchResult endFile = findByName(allFiles, "D.java");

        // maxDepth=2: can reach depth 2 (A at 0, B at 1, C at 2), but D is at depth 3
        TraceCommand cmd = new TraceCommand(relationService);
        cmd.maxDepth = 2;
        List<String> path = cmd.bfsTrace(startFile, endFile, tempDir, allFiles, fileNameIndex, fileMap);

        assertNull(path, "Path to D should not be found within depth 2");

        // maxDepth=3 should find it
        cmd.maxDepth = 3;
        List<String> pathDeeper = cmd.bfsTrace(startFile, endFile, tempDir, allFiles, fileNameIndex, fileMap);

        assertNotNull(pathDeeper, "Path to D should be found with depth 3");
        assertEquals(4, pathDeeper.size());
    }

    @Test
    void startClassNotFound_returnsNull() throws IOException {
        // Just one file in the index, searching for something else
        addJavaFile("A.java", """
                package com.example;
                public class A {}
                """);

        List<SearchResult> allFiles = index.listAll(null, 100);
        SearchResult result = relationService.findBestMatch(List.of(), "NonExistent.java");
        assertNull(result, "findBestMatch should return null for empty results");
    }

    @Test
    void endClassNotFound_returnsNull() throws IOException {
        addJavaFile("A.java", """
                package com.example;
                public class A {}
                """);

        List<SearchResult> allFiles = index.listAll(null, 100);
        SearchResult result = relationService.findBestMatch(List.of(), "NonExistent.java");
        assertNull(result, "findBestMatch should return null when target not found");
    }

    @Test
    void sameStartAndEnd_trivialPath() throws IOException {
        addJavaFile("A.java", """
                package com.example;
                public class A {}
                """);

        List<SearchResult> allFiles = index.listAll(null, 100);
        Map<String, List<String>> fileNameIndex = relationService.buildFileNameIndex(allFiles);
        Map<String, SearchResult> fileMap = buildFileMap(allFiles);

        SearchResult file = findByName(allFiles, "A.java");

        TraceCommand cmd = new TraceCommand(relationService);
        cmd.maxDepth = 10;
        List<String> path = cmd.bfsTrace(file, file, tempDir, allFiles, fileNameIndex, fileMap);

        assertNotNull(path);
        assertEquals(1, path.size());
        assertTrue(path.get(0).endsWith("A.java"));
    }

    @Test
    void shortestPathChosen_whenMultipleRoutesExist() throws IOException {
        // A -> B -> D (length 2)
        // A -> C -> D (length 2)
        // A -> B -> C -> D (length 3) — should NOT be returned since shorter exists
        addJavaFile("A.java", """
                package com.example;
                import com.example.B;
                import com.example.C;
                public class A {}
                """);
        addJavaFile("B.java", """
                package com.example;
                import com.example.D;
                public class B {}
                """);
        addJavaFile("C.java", """
                package com.example;
                import com.example.D;
                public class C {}
                """);
        addJavaFile("D.java", """
                package com.example;
                public class D {}
                """);

        List<SearchResult> allFiles = index.listAll(null, 100);
        Map<String, List<String>> fileNameIndex = relationService.buildFileNameIndex(allFiles);
        Map<String, SearchResult> fileMap = buildFileMap(allFiles);

        SearchResult startFile = findByName(allFiles, "A.java");
        SearchResult endFile = findByName(allFiles, "D.java");

        TraceCommand cmd = new TraceCommand(relationService);
        cmd.maxDepth = 10;
        List<String> path = cmd.bfsTrace(startFile, endFile, tempDir, allFiles, fileNameIndex, fileMap);

        assertNotNull(path);
        // BFS guarantees shortest path: A -> (B or C) -> D = 3 nodes
        assertEquals(3, path.size(), "BFS should find shortest 2-hop path");
        assertTrue(path.get(0).endsWith("A.java"));
        assertTrue(path.get(2).endsWith("D.java"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void addJavaFile(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content);
        FileMetadata metadata = FileMetadata.of(file, tempDir, content.length(), Instant.now(), null);
        AnalysisResult analysis = AnalysisResult.builder()
                .summary(fileName)
                .contentPreview(content)
                .build();
        index.addDocument(fileIndexer.createDocument(metadata, analysis));
        index.commit();
    }

    private SearchResult findByName(List<SearchResult> files, String fileName) {
        return files.stream()
                .filter(f -> f.fileName().equals(fileName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("File not found in index: " + fileName));
    }

    private Map<String, SearchResult> buildFileMap(List<SearchResult> allFiles) {
        Map<String, SearchResult> map = new LinkedHashMap<>();
        for (SearchResult f : allFiles) {
            map.put(f.relativePath(), f);
        }
        return map;
    }
}
