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
 * Tests for YamlAnalyzer -- type detection, key extraction, and special formats.
 */
class YamlAnalyzerTest {

    @TempDir
    Path tempDir;

    private final YamlAnalyzer analyzer = new YamlAnalyzer();

    @Test
    void testCanAnalyzeYamlFiles() {
        FileMetadata yaml = createYamlMetadata("config.yaml");
        FileMetadata java = new FileMetadata(
                tempDir.resolve("Test.java"), "Test.java", "Test.java",
                ".java", FileUtils.FileType.CODE, "Java", 100, Instant.now(), null);

        assertTrue(analyzer.canAnalyze(yaml));
        assertFalse(analyzer.canAnalyze(java));
    }

    @Test
    void testGenericYaml() throws IOException {
        Path yamlFile = tempDir.resolve("config.yaml");
        Files.writeString(yamlFile, """
                database:
                  host: localhost
                  port: 5432
                  name: mydb
                server:
                  port: 8080
                logging:
                  level: INFO
                """);

        AnalysisResult result = analyzer.analyze(createYamlMetadata(yamlFile));

        assertTrue(result.summary().contains("database"));
        assertTrue(result.headings().contains("database"));
        assertTrue(result.headings().contains("server"));
        assertTrue(result.keywords().contains("yaml"));
    }

    @Test
    void testDockerCompose() throws IOException {
        Path yamlFile = tempDir.resolve("docker-compose.yml");
        Files.writeString(yamlFile, """
                version: "3.8"
                services:
                  web:
                    image: nginx:latest
                    ports:
                      - "80:80"
                  db:
                    image: postgres:15
                    environment:
                      POSTGRES_DB: mydb
                networks:
                  default:
                    driver: bridge
                """);

        AnalysisResult result = analyzer.analyze(createYamlMetadata(yamlFile));

        assertTrue(result.summary().contains("Docker Compose"), "Should detect Docker Compose: " + result.summary());
        assertTrue(result.summary().contains("2 services"), "Should count services: " + result.summary());
        assertTrue(result.keywords().contains("docker"));
        assertTrue(result.keywords().contains("compose"));
    }

    @Test
    void testGitHubActions() throws IOException {
        Path yamlFile = tempDir.resolve("ci.yml");
        Files.writeString(yamlFile, """
                name: CI Pipeline
                on:
                  push:
                    branches: [main]
                  pull_request:
                    branches: [main]
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                  test:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """);

        AnalysisResult result = analyzer.analyze(createYamlMetadata(yamlFile));

        assertTrue(result.summary().contains("GitHub Actions"), "Should detect GitHub Actions: " + result.summary());
        assertTrue(result.summary().contains("CI Pipeline"), "Should include workflow name: " + result.summary());
        assertTrue(result.summary().contains("2 jobs"), "Should count jobs: " + result.summary());
        assertTrue(result.keywords().contains("github-actions"));
    }

    @Test
    void testKubernetesManifest() throws IOException {
        Path yamlFile = tempDir.resolve("deployment.yaml");
        Files.writeString(yamlFile, """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: my-app
                  labels:
                    app: my-app
                spec:
                  replicas: 3
                  selector:
                    matchLabels:
                      app: my-app
                """);

        AnalysisResult result = analyzer.analyze(createYamlMetadata(yamlFile));

        assertTrue(result.summary().contains("Kubernetes"), "Should detect Kubernetes: " + result.summary());
        assertTrue(result.summary().contains("Deployment"), "Should include kind: " + result.summary());
        assertTrue(result.keywords().contains("kubernetes"));
    }

    @Test
    void testClaudeSkill() throws IOException {
        Path yamlFile = tempDir.resolve("search-skill.yaml");
        Files.writeString(yamlFile, """
                name: synthesis-search
                description: Search the workspace for files and content
                steps:
                  - run: synthesis search
                  - analyze: results
                """);

        AnalysisResult result = analyzer.analyze(createYamlMetadata(yamlFile));

        assertTrue(result.summary().contains("Claude Code skill"), "Should detect Claude skill: " + result.summary());
        assertTrue(result.keywords().contains("claude-code"));
        assertTrue(result.keywords().contains("skill"));
    }

    @Test
    void testInvalidYaml() throws IOException {
        Path yamlFile = tempDir.resolve("broken.yaml");
        Files.writeString(yamlFile, """
                this: is
                  broken: yaml
                    because: indentation
                  is: wrong
                """);

        AnalysisResult result = analyzer.analyze(createYamlMetadata(yamlFile));

        // Should not throw -- should return minimal result
        assertNotNull(result);
        assertFalse(result.contentPreview().isEmpty());
    }

    @Test
    void testEmptyYaml() throws IOException {
        Path yamlFile = tempDir.resolve("empty.yaml");
        Files.writeString(yamlFile, "");

        AnalysisResult result = analyzer.analyze(createYamlMetadata(yamlFile));
        assertEquals("", result.summary());
    }

    private FileMetadata createYamlMetadata(String name) {
        return createYamlMetadata(tempDir.resolve(name));
    }

    private FileMetadata createYamlMetadata(Path path) {
        try {
            long size = Files.exists(path) ? Files.size(path) : 0;
            return new FileMetadata(
                    path, path.getFileName().toString(), path.getFileName().toString(),
                    ".yaml", FileUtils.FileType.YAML, null, size, Instant.now(), null);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
