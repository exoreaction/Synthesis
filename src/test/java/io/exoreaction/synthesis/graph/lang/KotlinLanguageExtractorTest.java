package io.exoreaction.synthesis.graph.lang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link KotlinLanguageExtractor}, relocated 1:1 from
 * {@code CodeGraphExtractorTest} with the Kotlin parsing methods they exercise
 * (ADR-0001 test-coupling ruling A). Assertions -- including the pinned
 * {@code findKotlinTopLevelDecls_known_limitation_*} regex expectations -- are
 * unchanged. The three former {@code buildKotlin*} index tests are re-expressed
 * against {@link KotlinLanguageExtractor#declarations} /
 * {@link KotlinLanguageExtractor#packageFallbackFiles}, which replaced those builders.
 */
class KotlinLanguageExtractorTest {

    @TempDir
    Path tempDir;

    private final KotlinLanguageExtractor kotlinExtractor = new KotlinLanguageExtractor();

    /** Builds the FQN -> relative-path map the former buildKotlinClassToFileMap produced. */
    private Map<String, String> classToFile(List<Path> files, Path root) throws IOException {
        Map<String, String> map = new HashMap<>();
        for (Path f : files) {
            String rel = root.relativize(f).toString();
            for (Declaration d : kotlinExtractor.declarations(f, Files.readString(f))) {
                map.put(((ResolutionKey.FqnKey) d.key()).fqn(), rel);
            }
        }
        return map;
    }

    /** Builds the package -> function-only-files map the former index produced. */
    private Map<String, List<String>> packageFunctionFiles(List<Path> files, Path root) throws IOException {
        Map<String, List<String>> index = new HashMap<>();
        for (Path f : files) {
            String content = Files.readString(f);
            kotlinExtractor.packageFallbackFiles(f, content, kotlinExtractor.declarations(f, content))
                    .forEach((pkg, pfiles) -> {
                        List<String> bucket = index.computeIfAbsent(pkg, k -> new ArrayList<>());
                        for (Path pf : pfiles) bucket.add(root.relativize(pf).toString());
                    });
        }
        return index;
    }

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
        List<String> imports = kotlinExtractor.extractKotlinImports(content);
        assertTrue(imports.contains("org.springframework.context.annotation.Bean"));
        assertTrue(imports.contains("com.example.Foo"), "alias should be dropped, FQN kept");
        assertTrue(imports.contains("java.util.List"), "trailing ; is optional, not required");
        assertFalse(imports.stream().anyMatch(i -> i.contains("*")), "wildcard imports are dropped");
    }

    @Test
    void extractKotlinPackage_semicolon_optional() {
        assertEquals("no.tvimenning.samtygd.config",
                kotlinExtractor.extractKotlinPackage("package no.tvimenning.samtygd.config\n\nclass Foo"));
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
        List<KotlinLanguageExtractor.KotlinDecl> decls = kotlinExtractor.findKotlinTopLevelDecls(content);
        assertEquals(1, decls.size());
        assertEquals("SecurityConfig", decls.get(0).name());
        assertTrue(decls.get(0).supertypes().isEmpty());
    }

    @Test
    void findKotlinTopLevelDecls_single_supertype_with_constructor_call() {
        // Real shape: tvimenning-template GdprMaskingConverter.kt
        String content = "class GdprMaskingConverter : ClassicConverter() {\n}\n";
        List<KotlinLanguageExtractor.KotlinDecl> decls = kotlinExtractor.findKotlinTopLevelDecls(content);
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
        List<KotlinLanguageExtractor.KotlinDecl> decls = kotlinExtractor.findKotlinTopLevelDecls(content);
        assertEquals(1, decls.size());
        assertEquals("Foo", decls.get(0).name());
        assertEquals(List.of(), decls.get(0).supertypes(),
                "known limitation: default-value call in constructor args truncates supertype capture");
    }

    @Test
    void findKotlinTopLevelDecls_interface_supertype_no_parens() {
        // Real shape: tvimenning-template WebMvcConfig.kt
        String content = "class WebMvcConfig : WebMvcConfigurer {\n}\n";
        List<KotlinLanguageExtractor.KotlinDecl> decls = kotlinExtractor.findKotlinTopLevelDecls(content);
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
        List<KotlinLanguageExtractor.KotlinDecl> decls = kotlinExtractor.findKotlinTopLevelDecls(content);
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
        List<KotlinLanguageExtractor.KotlinDecl> decls = kotlinExtractor.findKotlinTopLevelDecls(content);
        assertEquals(4, decls.size());
        assertEquals(List.of("Result", "Ok", "Err", "Empty"),
                decls.stream().map(KotlinLanguageExtractor.KotlinDecl::name).toList());
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
        List<KotlinLanguageExtractor.KotlinDecl> decls = kotlinExtractor.findKotlinTopLevelDecls(content);
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
        assertTrue(kotlinExtractor.findKotlinTopLevelDecls(content).isEmpty());
    }

    @Test
    void extractKotlinFileClassName_strips_kt_extension() {
        assertEquals("StringExt", kotlinExtractor.extractKotlinFileClassName(Path.of("util/StringExt.kt")));
    }

    @Test
    void choosePrimaryClass_prefers_filename_match_over_first_declared() {
        // Real shape: tvimenning-template HelloController.kt -- HelloResponse (a data class)
        // is declared before HelloController itself. "First declared" would misattribute
        // every import edge in the file to HelloResponse instead of the file's real class.
        List<KotlinLanguageExtractor.KotlinDecl> decls = List.of(
                new KotlinLanguageExtractor.KotlinDecl("HelloResponse", List.of()),
                new KotlinLanguageExtractor.KotlinDecl("HelloController", List.of()));
        assertEquals("HelloController",
                kotlinExtractor.choosePrimaryClass(decls, Path.of("web/HelloController.kt")));
    }

    @Test
    void choosePrimaryClass_falls_back_to_first_declared_when_no_filename_match() {
        // No declaration matches the filename at all (e.g. a poorly-named file) -- fall back
        // to today's behavior rather than silently dropping the file's identity.
        List<KotlinLanguageExtractor.KotlinDecl> decls = List.of(
                new KotlinLanguageExtractor.KotlinDecl("Ok", List.of()),
                new KotlinLanguageExtractor.KotlinDecl("Err", List.of()));
        assertEquals("Ok", kotlinExtractor.choosePrimaryClass(decls, Path.of("Result.kt")));
    }

    @Test
    void choosePrimaryClass_uses_filename_when_no_declarations_found() {
        assertEquals("StringExt",
                kotlinExtractor.choosePrimaryClass(List.of(), Path.of("util/StringExt.kt")));
    }

    @Test
    void splitKotlinSupertypes_strips_generics_and_multiple_supertypes() {
        List<String> names = kotlinExtractor.splitKotlinSupertypes("Bar<String>(), Baz, com.example.Qux");
        assertEquals(List.of("Bar", "Baz", "Qux"), names);
    }

    @Test
    void declarations_register_every_top_level_declaration() throws IOException {
        Path pkgDir = tempDir.resolve("src/main/kotlin/com/example");
        Files.createDirectories(pkgDir);
        Path f = Files.writeString(pkgDir.resolve("Result.kt"), """
                package com.example

                sealed class Result
                data class Ok(val value: String) : Result()
                class Err(val message: String) : Result()
                """);

        Map<String, String> map = classToFile(List.of(f), tempDir);

        assertEquals("src/main/kotlin/com/example/Result.kt", map.get("com.example.Result"));
        assertEquals("src/main/kotlin/com/example/Result.kt", map.get("com.example.Ok"));
        assertEquals("src/main/kotlin/com/example/Result.kt", map.get("com.example.Err"));
    }

    @Test
    void package_fallback_indexes_single_function_only_file() throws IOException {
        Path pkgDir = tempDir.resolve("src/main/kotlin/com/example/utils");
        Files.createDirectories(pkgDir);
        Path f = Files.writeString(pkgDir.resolve("Utils.kt"), """
                package com.example.utils

                fun doThing() {}
                """);

        Map<String, List<String>> index = packageFunctionFiles(List.of(f), tempDir);

        assertEquals(List.of("src/main/kotlin/com/example/utils/Utils.kt"), index.get("com.example.utils"));
    }

    @Test
    void package_fallback_excludes_file_with_top_level_function_and_class() throws IOException {
        Path pkgDir = tempDir.resolve("src/main/kotlin/com/example/utils");
        Files.createDirectories(pkgDir);
        Path f = Files.writeString(pkgDir.resolve("Mixed.kt"), """
                package com.example.utils

                fun doThing() {}

                private class Helper
                """);

        Map<String, List<String>> index = packageFunctionFiles(List.of(f), tempDir);

        assertNull(index.get("com.example.utils"),
                "file declares a top-level class, so it isn't function-only and must be excluded");
    }
}
