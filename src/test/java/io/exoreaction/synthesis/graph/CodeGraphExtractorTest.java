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

    // -----------------------------------------------------------------------
    // Kotlin support (spike)
    // -----------------------------------------------------------------------

    @Test
    void extractKotlinImports_handles_semicolon_optional_alias_and_wildcard() {
        String content = """
                package no.tvimenning.samtygd.config

                import org.springframework.context.annotation.Bean
                import com.example.Foo as Bar
                import kotlin.collections.*
                import java.util.List;

                class Foo
                """;
        List<String> imports = extractor.extractKotlinImports(content);
        assertTrue(imports.contains("org.springframework.context.annotation.Bean"));
        assertTrue(imports.contains("com.example.Foo"), "alias should be dropped, FQN kept");
        assertTrue(imports.contains("java.util.List"), "trailing ; is optional, not required");
        assertFalse(imports.stream().anyMatch(i -> i.contains("*")), "wildcard imports are dropped");
    }

    @Test
    void extractKotlinPackage_semicolon_optional() {
        assertEquals("no.tvimenning.samtygd.config",
                extractor.extractKotlinPackage("package no.tvimenning.samtygd.config\n\nclass Foo"));
    }

    @Test
    void findKotlinTopLevelDecls_single_class_no_supertype() {
        // Real shape: tvimenning-template SecurityConfig.kt (api-internal)
        String content = """
                package no.tvimenning.samtygd.config

                import org.springframework.context.annotation.Configuration

                @Configuration
                @EnableWebSecurity
                class SecurityConfig {
                    fun securityFilterChain() {}
                }
                """;
        List<CodeGraphExtractor.KotlinDecl> decls = extractor.findKotlinTopLevelDecls(content);
        assertEquals(1, decls.size());
        assertEquals("SecurityConfig", decls.get(0).name());
        assertTrue(decls.get(0).supertypes().isEmpty());
    }

    @Test
    void findKotlinTopLevelDecls_single_supertype_with_constructor_call() {
        // Real shape: tvimenning-template GdprMaskingConverter.kt
        String content = "class GdprMaskingConverter : ClassicConverter() {\n}\n";
        List<CodeGraphExtractor.KotlinDecl> decls = extractor.findKotlinTopLevelDecls(content);
        assertEquals(1, decls.size());
        assertEquals("GdprMaskingConverter", decls.get(0).name());
        assertEquals(List.of("ClassicConverter"), decls.get(0).supertypes());
    }

    @Test
    void findKotlinTopLevelDecls_known_limitation_constructor_default_value_call() {
        // Documents the known, non-regression limitation noted on KOTLIN_TOPLEVEL_DECL's
        // javadoc: constructor-arg parens are matched non-greedily and assumed non-nested, so
        // a default-value call like `= bar()` inside the primary constructor breaks the
        // optional constructor-params group, which in turn stops the trailing `: Base()`
        // supertype from being captured on this declaration. The declaration's own name is
        // still found correctly (no crash, no misattribution of the file's identity) -- only
        // this specific declaration's supertype edge is missed. Same naiveté level as the
        // pre-existing JAVA_IMPLEMENTS comma-split; pin the current behavior so a future
        // change to this regex is a deliberate choice, not a silent drift.
        String content = "class Foo(x: Int = bar()) : Base()\n";
        List<CodeGraphExtractor.KotlinDecl> decls = extractor.findKotlinTopLevelDecls(content);
        assertEquals(1, decls.size());
        assertEquals("Foo", decls.get(0).name());
        assertEquals(List.of(), decls.get(0).supertypes(),
                "known limitation: default-value call in constructor args truncates supertype capture");
    }

    @Test
    void findKotlinTopLevelDecls_interface_supertype_no_parens() {
        // Real shape: tvimenning-template WebMvcConfig.kt
        String content = "class WebMvcConfig : WebMvcConfigurer {\n}\n";
        List<CodeGraphExtractor.KotlinDecl> decls = extractor.findKotlinTopLevelDecls(content);
        assertEquals(1, decls.size());
        assertEquals(List.of("WebMvcConfigurer"), decls.get(0).supertypes());
    }

    @Test
    void findKotlinTopLevelDecls_ignores_nested_indented_class() {
        // Real shape: tvimenning-template WebMvcConfig.kt has a nested `private class
        // TraceIdInterceptor : HandlerInterceptor` inside the outer class body -- only the
        // outer, column-0 declaration should be picked up as a top-level entity.
        String content = """
                class WebMvcConfig : WebMvcConfigurer {

                    private class TraceIdInterceptor : HandlerInterceptor {
                        override fun preHandle(): Boolean { return true }
                    }
                }
                """;
        List<CodeGraphExtractor.KotlinDecl> decls = extractor.findKotlinTopLevelDecls(content);
        assertEquals(1, decls.size(), "nested indented class must not be picked up as top-level");
        assertEquals("WebMvcConfig", decls.get(0).name());
    }

    @Test
    void findKotlinTopLevelDecls_multiple_top_level_declarations_in_one_file() {
        String content = """
                package com.example

                sealed class Result

                data class Ok(val value: String) : Result()

                class Err(val message: String) : Result()

                object Empty : Result()
                """;
        List<CodeGraphExtractor.KotlinDecl> decls = extractor.findKotlinTopLevelDecls(content);
        assertEquals(4, decls.size());
        assertEquals(List.of("Result", "Ok", "Err", "Empty"),
                decls.stream().map(CodeGraphExtractor.KotlinDecl::name).toList());
        assertEquals(List.of("Result"), decls.get(1).supertypes());
        assertEquals(List.of("Result"), decls.get(3).supertypes());
    }

    @Test
    void findKotlinTopLevelDecls_fun_interface_is_matched() {
        // Regression test for #442: `fun interface` (SAM) declarations were invisible
        // because `fun` was missing from the modifier alternation. A top-level function
        // must still NOT match (covered by the extension-function-only test below) --
        // the regex requires a class/interface/object keyword after the modifiers.
        String content = """
                package com.example

                fun interface TokenValidator {
                    fun validate(token: String): Boolean
                }

                private fun interface Scorer : Weighted {
                    fun score(x: Int): Double
                }
                """;
        List<CodeGraphExtractor.KotlinDecl> decls = extractor.findKotlinTopLevelDecls(content);
        assertEquals(2, decls.size());
        assertEquals("TokenValidator", decls.get(0).name());
        assertTrue(decls.get(0).supertypes().isEmpty());
        assertEquals("Scorer", decls.get(1).name());
        assertEquals(List.of("Weighted"), decls.get(1).supertypes());
    }

    @Test
    void findKotlinTopLevelDecls_empty_for_extension_function_only_file() {
        String content = """
                package com.example

                fun String.truncate(n: Int): String = take(n)
                fun String.isBlankOrNull(): Boolean = this.isBlank()
                """;
        assertTrue(extractor.findKotlinTopLevelDecls(content).isEmpty());
    }

    @Test
    void extractKotlinFileClassName_strips_kt_extension() {
        assertEquals("StringExt", extractor.extractKotlinFileClassName(Path.of("util/StringExt.kt")));
    }

    @Test
    void choosePrimaryClass_prefers_filename_match_over_first_declared() {
        // Real shape: tvimenning-template HelloController.kt -- HelloResponse (a data class)
        // is declared before HelloController itself. "First declared" would misattribute
        // every import edge in the file to HelloResponse instead of the file's real class.
        List<CodeGraphExtractor.KotlinDecl> decls = List.of(
                new CodeGraphExtractor.KotlinDecl("HelloResponse", List.of()),
                new CodeGraphExtractor.KotlinDecl("HelloController", List.of()));
        assertEquals("HelloController",
                extractor.choosePrimaryClass(decls, Path.of("web/HelloController.kt")));
    }

    @Test
    void choosePrimaryClass_falls_back_to_first_declared_when_no_filename_match() {
        // No declaration matches the filename at all (e.g. a poorly-named file) -- fall back
        // to today's behavior rather than silently dropping the file's identity.
        List<CodeGraphExtractor.KotlinDecl> decls = List.of(
                new CodeGraphExtractor.KotlinDecl("Ok", List.of()),
                new CodeGraphExtractor.KotlinDecl("Err", List.of()));
        assertEquals("Ok", extractor.choosePrimaryClass(decls, Path.of("Result.kt")));
    }

    @Test
    void choosePrimaryClass_uses_filename_when_no_declarations_found() {
        assertEquals("StringExt",
                extractor.choosePrimaryClass(List.of(), Path.of("util/StringExt.kt")));
    }

    @Test
    void splitKotlinSupertypes_strips_generics_and_multiple_supertypes() {
        List<String> names = extractor.splitKotlinSupertypes("Bar<String>(), Baz, com.example.Qux");
        assertEquals(List.of("Bar", "Baz", "Qux"), names);
    }

    @Test
    void buildKotlinClassToFileMap_registers_every_top_level_declaration() throws IOException {
        Path pkgDir = tempDir.resolve("src/main/kotlin/com/example");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("Result.kt"), """
                package com.example

                sealed class Result
                data class Ok(val value: String) : Result()
                class Err(val message: String) : Result()
                """);

        Map<String, String> map = extractor.buildKotlinClassToFileMap(
                List.of(pkgDir.resolve("Result.kt")), tempDir);

        assertEquals("src/main/kotlin/com/example/Result.kt", map.get("com.example.Result"));
        assertEquals("src/main/kotlin/com/example/Result.kt", map.get("com.example.Ok"));
        assertEquals("src/main/kotlin/com/example/Result.kt", map.get("com.example.Err"));
    }

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
    void buildKotlinPackageFunctionFileIndex_indexes_single_function_only_file() throws IOException {
        Path pkgDir = tempDir.resolve("src/main/kotlin/com/example/utils");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("Utils.kt"), """
                package com.example.utils

                fun doThing() {}
                """);

        Map<String, List<String>> index = extractor.buildKotlinPackageFunctionFileIndex(
                List.of(pkgDir.resolve("Utils.kt")), tempDir);

        assertEquals(List.of("src/main/kotlin/com/example/utils/Utils.kt"), index.get("com.example.utils"));
    }

    @Test
    void buildKotlinPackageFunctionFileIndex_excludes_file_with_top_level_function_and_class()
            throws IOException {
        Path pkgDir = tempDir.resolve("src/main/kotlin/com/example/utils");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("Mixed.kt"), """
                package com.example.utils

                fun doThing() {}

                private class Helper
                """);

        Map<String, List<String>> index = extractor.buildKotlinPackageFunctionFileIndex(
                List.of(pkgDir.resolve("Mixed.kt")), tempDir);

        assertNull(index.get("com.example.utils"),
                "file declares a top-level class, so it isn't function-only and must be excluded");
    }

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
