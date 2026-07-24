package io.exoreaction.synthesis.graph.lang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JavaLanguageExtractor}, relocated 1:1 from
 * {@code CodeGraphExtractorTest} with the Java parsing / file-discovery methods
 * they exercise (ADR-0001 test-coupling ruling A). Assertions are unchanged; the
 * two former {@code buildClassToFileMap} tests are re-expressed against
 * {@link JavaLanguageExtractor#declarations} (same FQN-key semantics), since that
 * method replaced the map builder.
 */
class JavaLanguageExtractorTest {

    @TempDir
    Path tempDir;

    private final JavaLanguageExtractor javaExtractor = new JavaLanguageExtractor();

    private static String fqn(List<Declaration> decls) {
        return ((ResolutionKey.FqnKey) decls.get(0).key()).fqn();
    }

    @Test
    void extractImports_finds_java_imports() {
        String content = """
                package com.example;
                import com.example.util.Helper;
                import java.util.List;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                public class Foo {}
                """;
        List<String> imports = javaExtractor.extractImports(content);
        assertTrue(imports.contains("com.example.util.Helper"));
        assertTrue(imports.contains("java.util.List"));
        assertTrue(imports.contains("org.junit.jupiter.api.Assertions.assertEquals"));
    }

    @Test
    void extractPackage_finds_package() {
        String content = "package com.example.core;\nimport java.util.List;\npublic class Foo {}";
        assertEquals("com.example.core", javaExtractor.extractPackage(content));
    }

    @Test
    void extractPackage_returns_null_for_no_package() {
        assertNull(javaExtractor.extractPackage("public class Foo {}"));
    }

    @Test
    void extractClassName_strips_java_extension() {
        Path file = Path.of("src/main/java/Foo.java");
        assertEquals("Foo", javaExtractor.extractClassName(file));
    }

    @Test
    void findJavaFiles_discovers_nested_java_files() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src/main/java/com"));
        Files.writeString(src.resolve("Foo.java"), "class Foo {}");
        Files.writeString(src.resolve("Bar.java"), "class Bar {}");
        Files.writeString(tempDir.resolve("project/README.md"), "# Readme");

        List<Path> found = javaExtractor.findFiles(tempDir.resolve("project"), new ExclusionRules(false));
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

        List<Path> found = javaExtractor.findFiles(tempDir.resolve("project"), new ExclusionRules(false));
        assertEquals(1, found.size(), "Should find only the source file, excluding target/build/out: " + found);
        assertTrue(found.get(0).toString().contains("src/main/java"));
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
        List<Path> found = javaExtractor.findFiles(wsRoot, new ExclusionRules(false));

        assertEquals(1, found.size());
        assertTrue(found.get(0).toString().contains("JavaProject"));
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

        List<Path> found = javaExtractor.findFiles(tempDir.resolve("project"), new ExclusionRules(false));
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

        List<Path> found = javaExtractor.findFiles(tempDir.resolve("project"), new ExclusionRules(true));
        assertEquals(2, found.size(),
                "With --include-archives, should find both source and archive files: " + found);
    }

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
        Set<String> skipped = javaExtractor.identifyNonJavaRepos(wsRoot);

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
        Set<String> skipped = javaExtractor.identifyNonJavaRepos(wsRoot);

        assertFalse(skipped.contains("Legacy"),
                "Repo with Java files should not be skipped even without build file");
    }

    @Test
    void declarations_key_is_simple_name_when_no_package() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src"));
        Path fooFile = Files.writeString(src.resolve("Foo.java"), "class Foo {}");
        Path barFile = Files.writeString(src.resolve("Bar.java"), "class Bar {}");

        // No package declaration -> simple class name as the declared key (fallback)
        assertEquals("Foo", fqn(javaExtractor.declarations(fooFile, Files.readString(fooFile))));
        assertEquals("Bar", fqn(javaExtractor.declarations(barFile, Files.readString(barFile))));
    }

    @Test
    void declarations_key_is_fqn_with_package() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("project/src"));
        Path fooFile = Files.writeString(src.resolve("Foo.java"),
                "package com.example;\nclass Foo {}");
        Path barFile = Files.writeString(src.resolve("Bar.java"),
                "package com.example.util;\nclass Bar {}");

        // With package declaration -> FQN as the declared key
        assertEquals("com.example.Foo", fqn(javaExtractor.declarations(fooFile, Files.readString(fooFile))));
        assertEquals("com.example.util.Bar", fqn(javaExtractor.declarations(barFile, Files.readString(barFile))));
    }
}
