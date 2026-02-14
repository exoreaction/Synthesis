package io.exoreaction.synthesis.analyzer;

import io.exoreaction.synthesis.core.FileMetadata;
import io.exoreaction.synthesis.util.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CodeAnalyzer -- language detection, LOC counting,
 * framework detection, and import extraction.
 */
class CodeAnalyzerTest {

    @TempDir
    Path tempDir;

    private final CodeAnalyzer analyzer = new CodeAnalyzer();

    @Test
    void testCanAnalyzeCodeFiles() {
        FileMetadata javaMd = createMetadata("Test.java", FileUtils.FileType.CODE, "Java");
        FileMetadata mdMd = createMetadata("README.md", FileUtils.FileType.MARKDOWN, null);

        assertTrue(analyzer.canAnalyze(javaMd));
        assertFalse(analyzer.canAnalyze(mdMd));
    }

    @Test
    void testJavaAnalysis() throws IOException {
        Path javaFile = tempDir.resolve("HelloWorld.java");
        Files.writeString(javaFile, """
                package com.example;

                import java.util.List;
                import java.util.Map;

                /**
                 * A simple class.
                 */
                public class HelloWorld {

                    // field
                    private String name;

                    public static void main(String[] args) {
                        System.out.println("Hello");
                    }

                    public String getName() {
                        return name;
                    }
                }
                """);

        FileMetadata fm = FileMetadata.of(javaFile, tempDir, Files.size(javaFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("Java"));
        assertTrue(result.summary().contains("lines of code"));
        // Should find class and method declarations
        assertTrue(result.headings().contains("HelloWorld"), "Should find class name");
        assertTrue(result.headings().contains("main") || result.headings().contains("getName"),
                "Should find method names");
        // Should extract imports
        assertTrue(result.links().contains("java.util.List"), "Should extract imports");
    }

    @Test
    void testJavaDoesNotDetectReactFramework() throws IOException {
        // This was the bug: Java files with "io.exoreaction" were detected as React
        Path javaFile = tempDir.resolve("SynthesisApp.java");
        Files.writeString(javaFile, """
                package io.exoreaction.synthesis;

                import io.exoreaction.synthesis.cli.InitCommand;
                import io.exoreaction.synthesis.core.WorkspaceManager;

                public class SynthesisApp {
                    public static void main(String[] args) {
                        System.out.println("Hello");
                    }
                }
                """);

        FileMetadata fm = FileMetadata.of(javaFile, tempDir, Files.size(javaFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        // Should NOT contain "React"
        assertFalse(result.summary().contains("React"),
                "Java files should not be falsely detected as React: " + result.summary());
        // Should NOT have "react" in keywords
        assertFalse(result.keywords().contains("react"),
                "Keywords should not include 'react': " + result.keywords());
    }

    @Test
    void testSpringBootDetection() throws IOException {
        Path javaFile = tempDir.resolve("Application.java");
        Files.writeString(javaFile, """
                package com.example;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class Application {
                    public static void main(String[] args) {
                        SpringApplication.run(Application.class, args);
                    }
                }
                """);

        FileMetadata fm = FileMetadata.of(javaFile, tempDir, Files.size(javaFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("Spring Boot") || result.summary().contains("Spring"),
                "Should detect Spring Boot: " + result.summary());
    }

    @Test
    void testPythonAnalysis() throws IOException {
        Path pyFile = tempDir.resolve("script.py");
        Files.writeString(pyFile, """
                #!/usr/bin/env python3
                import os
                from pathlib import Path

                # A comment
                def process_files(directory):
                    \"\"\"Process files in directory.\"\"\"
                    for f in Path(directory).iterdir():
                        print(f.name)

                class FileProcessor:
                    pass
                """);

        FileMetadata fm = FileMetadata.of(pyFile, tempDir, Files.size(pyFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("Python"));
        assertTrue(result.headings().contains("process_files"), "Should find function name");
        assertTrue(result.headings().contains("FileProcessor"), "Should find class name");
    }

    @Test
    void testJavaScriptReactDetection() throws IOException {
        Path jsFile = tempDir.resolve("App.jsx");
        Files.writeString(jsFile, """
                import React from 'react';
                import { useState } from 'react';

                function App() {
                    const [count, setCount] = useState(0);
                    return <div>{count}</div>;
                }

                export default App;
                """);

        FileMetadata fm = FileMetadata.of(jsFile, tempDir, Files.size(jsFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("React"),
                "Should detect React in JavaScript files: " + result.summary());
    }

    @Test
    void testLineOfCodeCounting() throws IOException {
        Path javaFile = tempDir.resolve("Counter.java");
        Files.writeString(javaFile, """
                package test;

                // Line count test
                public class Counter {
                    // A comment

                    /* Multi-line
                     * comment
                     */
                    public int count() {
                        return 42;
                    }
                }
                """);

        FileMetadata fm = FileMetadata.of(javaFile, tempDir, Files.size(javaFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        // Verify metrics are present
        assertNotNull(result.metrics().get("totalLines"));
        assertNotNull(result.metrics().get("codeLines"));
        assertNotNull(result.metrics().get("commentLines"));
        assertNotNull(result.metrics().get("blankLines"));

        int totalLines = (int) result.metrics().get("totalLines");
        int codeLines = (int) result.metrics().get("codeLines");
        int commentLines = (int) result.metrics().get("commentLines");
        int blankLines = (int) result.metrics().get("blankLines");

        assertTrue(totalLines > 0, "Should count total lines");
        assertTrue(codeLines > 0, "Should count code lines");
        assertTrue(commentLines > 0, "Should count comment lines");
        assertTrue(blankLines > 0, "Should count blank lines");
        assertEquals(totalLines, codeLines + commentLines + blankLines,
                "Code + comments + blank should equal total");
    }

    @Test
    void testEmptyFile() throws IOException {
        Path javaFile = tempDir.resolve("Empty.java");
        Files.writeString(javaFile, "");

        FileMetadata fm = FileMetadata.of(javaFile, tempDir, 0,
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertEquals("", result.summary());
    }

    @Test
    void testShellScript() throws IOException {
        Path shFile = tempDir.resolve("build.sh");
        Files.writeString(shFile, """
                #!/bin/bash
                # Build script

                build_project() {
                    mvn clean package
                }

                run_tests() {
                    mvn test
                }

                build_project
                run_tests
                """);

        FileMetadata fm = FileMetadata.of(shFile, tempDir, Files.size(shFile),
                Instant.now(), "hash1");
        AnalysisResult result = analyzer.analyze(fm);

        assertTrue(result.summary().contains("Shell"));
        assertTrue(result.headings().contains("build_project"), "Should find shell functions");
        assertTrue(result.headings().contains("run_tests"), "Should find shell functions");
    }

    private FileMetadata createMetadata(String name, FileUtils.FileType type, String language) {
        return new FileMetadata(
                tempDir.resolve(name), name, name,
                name.contains(".") ? name.substring(name.lastIndexOf('.')) : "",
                type, language, 100, Instant.now(), null
        );
    }
}
