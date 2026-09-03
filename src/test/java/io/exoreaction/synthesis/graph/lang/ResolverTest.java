package io.exoreaction.synthesis.graph.lang;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Resolver}, relocated 1:1 from {@code CodeGraphExtractorTest}
 * with their FQN / simple-name resolution methods (ADR-0001 test-coupling ruling A).
 * Assertions are unchanged from the originals.
 */
class ResolverTest {

    @Test
    void getSimpleClassName_extracts_last_segment() {
        assertEquals("Foo", Resolver.getSimpleClassName("com.example.Foo"));
        assertEquals("Bar", Resolver.getSimpleClassName("Bar"));
    }

    @Test
    void getPackageFromImport_extracts_package() {
        assertEquals("com.example", Resolver.getPackageFromImport("com.example.Foo"));
        assertEquals("", Resolver.getPackageFromImport("Foo"));
    }

    @Test
    void buildSimpleNameIndex_groups_by_simple_name() {
        Map<String, String> classToFile = Map.of(
                "com.example.Service", "src/Service.java",
                "com.example.util.Service", "src/util/Service.java",
                "com.example.Config", "src/Config.java"
        );
        Map<String, List<String>> index = Resolver.buildSimpleNameIndex(classToFile);

        assertEquals(2, index.get("Service").size());
        assertEquals(1, index.get("Config").size());
    }

    @Test
    void lookupBySimpleName_prefers_same_package() {
        Map<String, String> classToFile = Map.of(
                "com.example.Service", "src/Service.java",
                "com.example.util.Service", "src/util/Service.java"
        );
        Map<String, List<String>> index = Resolver.buildSimpleNameIndex(classToFile);

        String result = Resolver.lookupBySimpleName("Service", "com.example",
                classToFile, index);
        assertEquals("src/Service.java", result);

        String result2 = Resolver.lookupBySimpleName("Service", "com.example.util",
                classToFile, index);
        assertEquals("src/util/Service.java", result2);
    }

    @Test
    void lookupBySimpleName_returns_null_for_unknown() {
        Map<String, String> classToFile = Map.of("com.example.Config", "src/Config.java");
        Map<String, List<String>> index = Resolver.buildSimpleNameIndex(classToFile);

        assertNull(Resolver.lookupBySimpleName("NonExistent", "com.example",
                classToFile, index));
    }
}
