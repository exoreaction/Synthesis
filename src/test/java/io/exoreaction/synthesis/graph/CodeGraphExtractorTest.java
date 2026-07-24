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

    // -----------------------------------------------------------------------
    // Kotlin support (spike)
    // -----------------------------------------------------------------------

    @Test
    void extractAndPersist_resolves_kotlin_supertype_edge_as_internal() throws SQLException, IOException {
        Path pkgDir = tempDir.resolve("src/main/kotlin/com/example");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("Base.kt"), "package com.example\n\nopen class Base\n");
        Files.writeString(pkgDir.resolve("Foo.kt"), "package com.example\n\nclass Foo : Base()\n");

        extractor.extractAndPersist(tempDir, conn);

        List<CodeDependency> deps = new CodeGraphRepository()
                .getDependenciesFrom(conn, tempDir.toString(), "src/main/kotlin/com/example/Foo.kt");
        CodeDependency supertypeDep = deps.stream()
                .filter(d -> "supertype".equals(d.dependencyType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a supertype edge from Foo.kt"));
        assertEquals("Base", supertypeDep.targetClass());
        assertFalse(supertypeDep.isExternal(), "Base is in-workspace, should resolve internal");
        assertEquals("src/main/kotlin/com/example/Base.kt", supertypeDep.targetFile());
    }

    @Test
    void extractAndPersist_attributes_kotlin_edges_to_filename_matching_class_not_first_declared()
            throws SQLException, IOException {
        // Regression test for the HelloController.kt shape found in tvimenning-template:
        // HelloResponse (a data class) is declared before the file's real primary class,
        // HelloController. Before the choosePrimaryClass fix, every import edge in the file
        // was misattributed to HelloResponse -- querying by the file's actual public,
        // externally-referenceable class name (HelloController) returned nothing.
        Path pkgDir = tempDir.resolve("src/main/kotlin/com/example");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("Greeter.kt"), "package com.example.service\n\nclass Greeter\n");
        Files.writeString(pkgDir.resolve("HelloController.kt"), """
                package com.example

                import com.example.service.Greeter

                data class HelloResponse(val message: String)

                class HelloController(private val greeter: Greeter)
                """);

        extractor.extractAndPersist(tempDir, conn);

        List<CodeDependency> fromHelloController = new CodeGraphRepository()
                .getDependenciesFrom(conn, tempDir.toString(), "src/main/kotlin/com/example/HelloController.kt");
        assertFalse(fromHelloController.isEmpty(), "expected edges attributed to the file");
        assertTrue(fromHelloController.stream().allMatch(d -> "HelloController".equals(d.sourceClass())),
                "all edges from this file should be attributed to HelloController, not HelloResponse");
    }

    // -----------------------------------------------------------------------
    // Kotlin top-level function resolution (#438)
    // -----------------------------------------------------------------------

    @Test
    void extractAndPersist_resolves_kotlin_top_level_function_import_via_single_candidate()
            throws SQLException, IOException {
        // Regression test for #438: Utils.kt has no top-level class, only a top-level
        // function -- the compiler-synthesized UtilsKt facade is never named by source-level
        // imports (they name doThing directly), so buildKotlinClassToFileMap alone can't
        // resolve this. Exactly one function-only file in the imported package -> resolve it.
        Path utilsDir = tempDir.resolve("src/main/kotlin/com/example/utils");
        Files.createDirectories(utilsDir);
        Files.writeString(utilsDir.resolve("Utils.kt"), """
                package com.example.utils

                fun doThing() {}
                """);
        Path callerDir = tempDir.resolve("src/main/kotlin/com/example");
        Files.createDirectories(callerDir);
        Files.writeString(callerDir.resolve("Caller.kt"), """
                package com.example

                import com.example.utils.doThing

                class Caller
                """);

        extractor.extractAndPersist(tempDir, conn);

        List<CodeDependency> fromCaller = new CodeGraphRepository()
                .getDependenciesFrom(conn, tempDir.toString(), "src/main/kotlin/com/example/Caller.kt");
        CodeDependency importDep = fromCaller.stream()
                .filter(d -> "import".equals(d.dependencyType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected an import edge from Caller.kt"));
        assertFalse(importDep.isExternal(),
                "single function-only candidate in the imported package should resolve internal");
        assertEquals("src/main/kotlin/com/example/utils/Utils.kt", importDep.targetFile());
    }

    @Test
    void extractAndPersist_kotlin_import_stays_external_when_multiple_function_only_candidates()
            throws SQLException, IOException {
        Path utilsDir = tempDir.resolve("src/main/kotlin/com/example/utils");
        Files.createDirectories(utilsDir);
        Files.writeString(utilsDir.resolve("Utils.kt"), """
                package com.example.utils

                fun doThing() {}
                """);
        Files.writeString(utilsDir.resolve("Helpers.kt"), """
                package com.example.utils

                fun doOtherThing() {}
                """);
        Path callerDir = tempDir.resolve("src/main/kotlin/com/example");
        Files.createDirectories(callerDir);
        Files.writeString(callerDir.resolve("Caller.kt"), """
                package com.example

                import com.example.utils.doThing

                class Caller
                """);

        extractor.extractAndPersist(tempDir, conn);

        List<CodeDependency> fromCaller = new CodeGraphRepository()
                .getDependenciesFrom(conn, tempDir.toString(), "src/main/kotlin/com/example/Caller.kt");
        CodeDependency importDep = fromCaller.stream()
                .filter(d -> "import".equals(d.dependencyType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected an import edge from Caller.kt"));
        assertTrue(importDep.isExternal(),
                "ambiguous package (2 function-only candidates) should stay external, not guess");
        assertNull(importDep.targetFile());
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
    void isBuildArtifact_detects_common_build_dirs() {
        Path root = Path.of("/workspace");
        assertTrue(CodeGraphExtractor.isBuildArtifact(root, Path.of("/workspace/target/classes/Foo.java")));
        assertTrue(CodeGraphExtractor.isBuildArtifact(root, Path.of("/workspace/build/classes/Foo.java")));
        assertTrue(CodeGraphExtractor.isBuildArtifact(root, Path.of("/workspace/out/classes/Foo.java")));
        assertTrue(CodeGraphExtractor.isBuildArtifact(root, Path.of("/workspace/sub/target/Foo.java")));
        assertFalse(CodeGraphExtractor.isBuildArtifact(root, Path.of("/workspace/src/main/java/Foo.java")));
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

    // -----------------------------------------------------------------------
    // Non-Java repo skipping (#226)
    // -----------------------------------------------------------------------

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

}
