package io.exoreaction.synthesis.graph;

import io.exoreaction.synthesis.db.SynthesisDatabase;
import io.exoreaction.synthesis.graph.CodeGraphRepository.CodeDependency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CodeGraphExtractor} -- code dependency extraction and persistence.
 */
class CodeGraphExtractorTest {

    @TempDir
    Path tempDir;

    private SynthesisDatabase db;
    private Connection conn;
    private CodeGraphExtractor extractor;

    @BeforeEach
    void setUp() throws SQLException {
        db = new SynthesisDatabase(tempDir.resolve("test.db"));
        conn = db.getConnection();
        extractor = new CodeGraphExtractor();
    }

    @AfterEach
    void tearDown() throws SQLException {
        db.close();
    }

    // -----------------------------------------------------------------------
    // Import extraction (unit-level)
    // -----------------------------------------------------------------------

    @Test
    void extractImports_finds_java_imports() {
        String content = """
                package com.example;
                import com.example.util.Helper;
                import java.util.List;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                public class Foo {}
                """;
        List<String> imports = extractor.extractImports(content);
        assertTrue(imports.contains("com.example.util.Helper"));
        assertTrue(imports.contains("java.util.List"));
        assertTrue(imports.contains("org.junit.jupiter.api.Assertions.assertEquals"));
    }

    @Test
    void extractPackage_finds_package() {
        String content = "package com.example.core;\nimport java.util.List;\npublic class Foo {}";
        assertEquals("com.example.core", extractor.extractPackage(content));
    }

    @Test
    void extractPackage_returns_null_for_no_package() {
        assertNull(extractor.extractPackage("public class Foo {}"));
    }

    @Test
    void extractClassName_strips_java_extension() {
        Path file = Path.of("src/main/java/Foo.java");
        assertEquals("Foo", extractor.extractClassName(file));
    }

    @Test
    void getSimpleClassName_extracts_last_segment() {
        assertEquals("Foo", extractor.getSimpleClassName("com.example.Foo"));
        assertEquals("Bar", extractor.getSimpleClassName("Bar"));
    }

    @Test
    void getPackageFromImport_extracts_package() {
        assertEquals("com.example", extractor.getPackageFromImport("com.example.Foo"));
        assertEquals("", extractor.getPackageFromImport("Foo"));
    }

    // -----------------------------------------------------------------------
    // Full extraction with temp workspace
    // -----------------------------------------------------------------------

    @Test
    void extractAndPersist_processes_java_files() throws SQLException, IOException {
        // Create a mini Java project
        Path srcDir = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(srcDir.resolve("Config.java"), """
                package com.example;
                public class Config {
                    private String name;
                }
                """);
        Files.writeString(srcDir.resolve("Service.java"), """
                package com.example;
                import com.example.Config;
                import java.util.List;

                public class Service {
                    private Config config;
                }
                """);
        Files.writeString(srcDir.resolve("App.java"), """
                package com.example;
                import com.example.Service;

                public class App {
                    private Service service;
                }
                """);

        Path projectRoot = tempDir.resolve("project");
        CodeGraphStats stats = extractor.extractAndPersist(projectRoot, conn);

        assertEquals(3, stats.filesProcessed());
        assertTrue(stats.dependenciesFound() >= 2, "Should find at least 2 import deps");
        assertTrue(stats.elapsedMs() >= 0);

        // Verify persistence
        CodeGraphRepository repo = extractor.getRepository();
        assertTrue(repo.isPopulated(conn, projectRoot.toString()));

        // Service.java imports Config
        List<CodeDependency> serviceDeps = repo.getDependenciesFrom(conn,
                projectRoot.toString(), "src/Service.java");
        assertTrue(serviceDeps.stream().anyMatch(d -> d.targetClass().equals("Config")),
                "Service should depend on Config");
    }

    @Test
    void extractAndPersist_detects_extends_and_implements() throws SQLException, IOException {
        Path srcDir = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(srcDir.resolve("Animal.java"), """
                package com.example;
                public class Animal {}
                """);
        Files.writeString(srcDir.resolve("Runnable.java"), """
                package com.example;
                public interface Runnable { void run(); }
                """);
        Files.writeString(srcDir.resolve("Dog.java"), """
                package com.example;
                public class Dog extends Animal implements Runnable {
                    public void run() {}
                }
                """);

        Path projectRoot = tempDir.resolve("project");
        CodeGraphStats stats = extractor.extractAndPersist(projectRoot, conn);

        CodeGraphRepository repo = extractor.getRepository();
        List<CodeDependency> dogDeps = repo.getDependenciesFrom(conn,
                projectRoot.toString(), "src/Dog.java");

        boolean hasExtends = dogDeps.stream()
                .anyMatch(d -> d.targetClass().equals("Animal") && d.dependencyType().equals("extends"));
        boolean hasImplements = dogDeps.stream()
                .anyMatch(d -> d.targetClass().equals("Runnable") && d.dependencyType().equals("implements"));

        assertTrue(hasExtends, "Dog should extend Animal: " + dogDeps);
        assertTrue(hasImplements, "Dog should implement Runnable: " + dogDeps);
    }

    @Test
    void extractAndPersist_clears_old_data_first() throws SQLException, IOException {
        Path srcDir = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(srcDir.resolve("Foo.java"), """
                package com.example;
                import java.util.List;
                public class Foo {}
                """);

        Path projectRoot = tempDir.resolve("project");
        extractor.extractAndPersist(projectRoot, conn);
        int firstCount = extractor.getRepository().countDependencies(conn, projectRoot.toString());

        // Run again -- should not accumulate duplicates
        extractor.extractAndPersist(projectRoot, conn);
        int secondCount = extractor.getRepository().countDependencies(conn, projectRoot.toString());

        assertEquals(firstCount, secondCount, "Second extraction should replace, not accumulate");
    }

    // -----------------------------------------------------------------------
    // Incremental update
    // -----------------------------------------------------------------------

    @Test
    void incrementalUpdate_replaces_changed_file_deps() throws SQLException, IOException {
        Path srcDir = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(srcDir.resolve("Config.java"), """
                package com.example;
                public class Config {}
                """);
        Files.writeString(srcDir.resolve("Service.java"), """
                package com.example;
                import com.example.Config;
                public class Service {}
                """);

        Path projectRoot = tempDir.resolve("project");
        extractor.extractAndPersist(projectRoot, conn);

        // Modify Service.java to no longer import Config
        Files.writeString(srcDir.resolve("Service.java"), """
                package com.example;
                public class Service {}
                """);

        // Run incremental for just Service.java
        Set<Path> changed = Set.of(Path.of("src/Service.java"));
        CodeGraphStats stats = extractor.incrementalUpdate(projectRoot, conn, changed);

        assertEquals(1, stats.filesProcessed());

        // Service.java should no longer depend on Config
        List<CodeDependency> serviceDeps = extractor.getRepository().getDependenciesFrom(
                conn, projectRoot.toString(), "src/Service.java");
        assertFalse(serviceDeps.stream().anyMatch(d -> d.targetClass().equals("Config")),
                "After incremental update, Service should no longer depend on Config");
    }

    @Test
    void incrementalUpdate_skips_non_java_files() throws SQLException, IOException {
        Path srcDir = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(srcDir.resolve("readme.md"), "# Hello");

        Path projectRoot = tempDir.resolve("project");
        Set<Path> changed = Set.of(Path.of("src/readme.md"));
        CodeGraphStats stats = extractor.incrementalUpdate(projectRoot, conn, changed);

        assertEquals(0, stats.filesProcessed());
    }

    // -----------------------------------------------------------------------
    // Helper: findJavaFiles
    // -----------------------------------------------------------------------

    @Test
    void findJavaFiles_discovers_nested_java_files() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src/main/java/com"));
        Files.writeString(src.resolve("Foo.java"), "class Foo {}");
        Files.writeString(src.resolve("Bar.java"), "class Bar {}");
        Files.writeString(tempDir.resolve("project/README.md"), "# Readme");

        List<Path> found = extractor.findJavaFiles(tempDir.resolve("project"));
        assertEquals(2, found.size());
        assertTrue(found.stream().allMatch(p -> p.toString().endsWith(".java")));
    }

    @Test
    void findJavaFiles_excludes_build_artifact_directories() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src/main/java/com"));
        Files.writeString(src.resolve("Foo.java"), "class Foo {}");

        // Create files inside build artifact directories
        Path target = Files.createDirectories(tempDir.resolve("project/target/classes/com"));
        Files.writeString(target.resolve("Foo.java"), "class Foo {}");
        Path build = Files.createDirectories(tempDir.resolve("project/build/classes/com"));
        Files.writeString(build.resolve("Foo.java"), "class Foo {}");
        Path out = Files.createDirectories(tempDir.resolve("project/out/classes/com"));
        Files.writeString(out.resolve("Foo.java"), "class Foo {}");

        // Also test nested target/ (multi-module)
        Path nested = Files.createDirectories(tempDir.resolve("project/submodule/target/classes/com"));
        Files.writeString(nested.resolve("Bar.java"), "class Bar {}");

        List<Path> found = extractor.findJavaFiles(tempDir.resolve("project"));
        assertEquals(1, found.size(), "Should find only the source file, excluding target/build/out: " + found);
        assertTrue(found.get(0).toString().contains("src/main/java"));
    }

    @Test
    void isBuildArtifact_detects_common_build_dirs() {
        Path root = Path.of("/workspace");
        assertTrue(CodeGraphExtractor.isBuildArtifact(root, Path.of("/workspace/target/classes/Foo.java")));
        assertTrue(CodeGraphExtractor.isBuildArtifact(root, Path.of("/workspace/build/classes/Foo.java")));
        assertTrue(CodeGraphExtractor.isBuildArtifact(root, Path.of("/workspace/out/classes/Foo.java")));
        assertTrue(CodeGraphExtractor.isBuildArtifact(root, Path.of("/workspace/sub/target/Foo.java")));
        assertFalse(CodeGraphExtractor.isBuildArtifact(root, Path.of("/workspace/src/main/java/Foo.java")));
    }

    @Test
    void buildClassToFileMap_maps_classname_to_relpath_no_package() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src"));
        Path fooFile = Files.writeString(src.resolve("Foo.java"), "class Foo {}");
        Path barFile = Files.writeString(src.resolve("Bar.java"), "class Bar {}");

        Path root = tempDir.resolve("project");
        Map<String, String> map = extractor.buildClassToFileMap(
                List.of(fooFile, barFile), root);

        // No package declaration -> simple class name as key (fallback)
        assertEquals("src/Foo.java", map.get("Foo"));
        assertEquals("src/Bar.java", map.get("Bar"));
    }

    @Test
    void buildClassToFileMap_uses_fqn_keys_with_package() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src"));
        Path fooFile = Files.writeString(src.resolve("Foo.java"),
                "package com.example;\nclass Foo {}");
        Path barFile = Files.writeString(src.resolve("Bar.java"),
                "package com.example.util;\nclass Bar {}");

        Path root = tempDir.resolve("project");
        Map<String, String> map = extractor.buildClassToFileMap(
                List.of(fooFile, barFile), root);

        // With package declaration -> FQN as key
        assertEquals("src/Foo.java", map.get("com.example.Foo"));
        assertEquals("src/Bar.java", map.get("com.example.util.Bar"));
        // Simple name should NOT be a key when package is present
        assertNull(map.get("Foo"));
        assertNull(map.get("Bar"));
    }

    @Test
    void fqn_lookup_correctly_marks_stdlib_as_external() throws SQLException, IOException {
        // This test verifies that stdlib/framework imports are correctly marked external
        // even when a project class has the same simple name (issue #223)
        Path srcDir = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(srcDir.resolve("Service.java"), """
                package com.example;
                public class Service {}
                """);
        Files.writeString(srcDir.resolve("App.java"), """
                package com.example;
                import org.springframework.stereotype.Service;
                import com.example.Service;

                public class App {}
                """);

        Path projectRoot = tempDir.resolve("project");
        CodeGraphStats stats = extractor.extractAndPersist(projectRoot, conn);

        CodeGraphRepository repo = extractor.getRepository();
        List<CodeDependency> appDeps = repo.getDependenciesFrom(conn,
                projectRoot.toString(), "src/App.java");

        // The Spring import should be external (not matched to project's Service)
        boolean springExternal = appDeps.stream()
                .anyMatch(d -> d.targetClass().equals("Service")
                        && d.targetPackage().equals("org.springframework.stereotype")
                        && d.isExternal());
        assertTrue(springExternal,
                "Spring Service import should be external: " + appDeps);

        // The project import should be internal
        boolean projectInternal = appDeps.stream()
                .anyMatch(d -> d.targetClass().equals("Service")
                        && d.targetPackage().equals("com.example")
                        && !d.isExternal());
        assertTrue(projectInternal,
                "Project Service import should be internal: " + appDeps);
    }

    @Test
    void buildSimpleNameIndex_groups_by_simple_name() {
        Map<String, String> classToFile = Map.of(
                "com.example.Service", "src/Service.java",
                "com.example.util.Service", "src/util/Service.java",
                "com.example.Config", "src/Config.java"
        );
        Map<String, List<String>> index = extractor.buildSimpleNameIndex(classToFile);

        assertEquals(2, index.get("Service").size());
        assertEquals(1, index.get("Config").size());
    }

    @Test
    void lookupBySimpleName_prefers_same_package() {
        Map<String, String> classToFile = Map.of(
                "com.example.Service", "src/Service.java",
                "com.example.util.Service", "src/util/Service.java"
        );
        Map<String, List<String>> index = extractor.buildSimpleNameIndex(classToFile);

        // When source is in com.example, prefer com.example.Service
        String result = extractor.lookupBySimpleName("Service", "com.example",
                classToFile, index);
        assertEquals("src/Service.java", result);

        // When source is in com.example.util, prefer com.example.util.Service
        String result2 = extractor.lookupBySimpleName("Service", "com.example.util",
                classToFile, index);
        assertEquals("src/util/Service.java", result2);
    }

    @Test
    void lookupBySimpleName_returns_null_for_unknown() {
        Map<String, String> classToFile = Map.of("com.example.Config", "src/Config.java");
        Map<String, List<String>> index = extractor.buildSimpleNameIndex(classToFile);

        assertNull(extractor.lookupBySimpleName("NonExistent", "com.example",
                classToFile, index));
    }

    // -----------------------------------------------------------------------
    // Non-Java repo skipping (#226)
    // -----------------------------------------------------------------------

    @Test
    void identifyNonJavaRepos_skips_repos_with_no_java_files() throws IOException {
        // Java repo with pom.xml
        Path javaRepo = Files.createDirectories(tempDir.resolve("workspace/JavaProject/src"));
        Files.writeString(tempDir.resolve("workspace/JavaProject/pom.xml"), "<project/>");
        Files.writeString(javaRepo.resolve("App.java"), "class App {}");

        // Non-Java repo (Nuxt frontend)
        Path nuxtRepo = Files.createDirectories(tempDir.resolve("workspace/NuxtFrontend/pages"));
        Files.writeString(nuxtRepo.resolve("index.vue"), "<template></template>");

        // Non-Java repo (CloudFormation)
        Path cfnRepo = Files.createDirectories(tempDir.resolve("workspace/AwsInfra/stacks"));
        Files.writeString(cfnRepo.resolve("vpc.yaml"), "AWSTemplateFormatVersion: ...");

        Path wsRoot = tempDir.resolve("workspace");
        Set<String> skipped = extractor.identifyNonJavaRepos(wsRoot);

        assertTrue(skipped.contains("NuxtFrontend"), "Nuxt repo should be skipped");
        assertTrue(skipped.contains("AwsInfra"), "CloudFormation repo should be skipped");
        assertFalse(skipped.contains("JavaProject"), "Java repo should not be skipped");
    }

    @Test
    void identifyNonJavaRepos_keeps_repos_with_java_files_but_no_build_file() throws IOException {
        // Repo with .java files but no pom.xml/build.gradle (legacy project)
        Path legacyRepo = Files.createDirectories(tempDir.resolve("workspace/Legacy/src"));
        Files.writeString(legacyRepo.resolve("Main.java"), "class Main {}");

        Path wsRoot = tempDir.resolve("workspace");
        Set<String> skipped = extractor.identifyNonJavaRepos(wsRoot);

        assertFalse(skipped.contains("Legacy"),
                "Repo with Java files should not be skipped even without build file");
    }

    @Test
    void findJavaFiles_excludes_non_java_repos() throws IOException {
        // Java repo
        Path javaRepo = Files.createDirectories(tempDir.resolve("workspace/JavaProject/src"));
        Files.writeString(tempDir.resolve("workspace/JavaProject/pom.xml"), "<project/>");
        Files.writeString(javaRepo.resolve("App.java"), "class App {}");

        // Non-Java repo with no .java files at all
        Path nuxtRepo = Files.createDirectories(tempDir.resolve("workspace/NuxtFrontend/pages"));
        Files.writeString(nuxtRepo.resolve("index.vue"), "<template></template>");

        Path wsRoot = tempDir.resolve("workspace");
        List<Path> found = extractor.findJavaFiles(wsRoot);

        assertEquals(1, found.size());
        assertTrue(found.get(0).toString().contains("JavaProject"));
    }

    // -----------------------------------------------------------------------
    // #279: archive/ directory exclusion
    // -----------------------------------------------------------------------

    @Test
    void isArchiveDirectory_detects_archive_dir() {
        Path root = Path.of("/workspace");
        assertTrue(CodeGraphExtractor.isArchiveDirectory(root,
                Path.of("/workspace/archive/old/Foo.java")));
        assertTrue(CodeGraphExtractor.isArchiveDirectory(root,
                Path.of("/workspace/project/archive/Foo.java")));
    }

    @Test
    void isArchiveDirectory_detects_vendor_dir() {
        Path root = Path.of("/workspace");
        assertTrue(CodeGraphExtractor.isArchiveDirectory(root,
                Path.of("/workspace/vendor/lib/Foo.java")));
    }

    @Test
    void isArchiveDirectory_detects_node_modules_dir() {
        Path root = Path.of("/workspace");
        assertTrue(CodeGraphExtractor.isArchiveDirectory(root,
                Path.of("/workspace/node_modules/pkg/Foo.java")));
    }

    @Test
    void isArchiveDirectory_does_not_match_normal_dirs() {
        Path root = Path.of("/workspace");
        assertFalse(CodeGraphExtractor.isArchiveDirectory(root,
                Path.of("/workspace/src/main/java/Foo.java")));
        assertFalse(CodeGraphExtractor.isArchiveDirectory(root,
                Path.of("/workspace/archiver/Foo.java")));
    }

    @Test
    void findJavaFiles_excludes_archive_directories() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src/main/java/com"));
        Files.writeString(src.resolve("Foo.java"), "class Foo {}");

        // Create files inside archive directories
        Path archiveDir = Files.createDirectories(
                tempDir.resolve("project/archive/old-version/src/com"));
        Files.writeString(archiveDir.resolve("Foo.java"), "class Foo {}");

        Path vendorDir = Files.createDirectories(
                tempDir.resolve("project/vendor/lib/src"));
        Files.writeString(vendorDir.resolve("Bar.java"), "class Bar {}");

        Path nodeModules = Files.createDirectories(
                tempDir.resolve("project/node_modules/some-pkg"));
        Files.writeString(nodeModules.resolve("Gen.java"), "class Gen {}");

        List<Path> found = extractor.findJavaFiles(tempDir.resolve("project"));
        assertEquals(1, found.size(),
                "Should find only the source file, excluding archive/vendor/node_modules: " + found);
        assertTrue(found.get(0).toString().contains("src/main/java"));
    }

    @Test
    void findJavaFiles_includesArchives_when_flag_set() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src/main/java/com"));
        Files.writeString(src.resolve("Foo.java"), "class Foo {}");

        Path archiveDir = Files.createDirectories(
                tempDir.resolve("project/archive/old-version/src/com"));
        Files.writeString(archiveDir.resolve("Foo.java"), "class Foo {}");

        extractor.setIncludeArchives(true);
        List<Path> found = extractor.findJavaFiles(tempDir.resolve("project"));
        assertEquals(2, found.size(),
                "With --include-archives, should find both source and archive files: " + found);
    }
}
