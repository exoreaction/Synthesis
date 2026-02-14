package io.exoreaction.synthesis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadReturnsDefaultsWhenNoConfigExists() throws IOException {
        SynthesisConfig config = ConfigLoader.load(tempDir);

        assertNotNull(config);
        assertNotNull(config.getWorkspace());
        assertNotNull(config.getScan());
        assertNotNull(config.getSearch());
        assertNotNull(config.getAi());
        assertEquals("general", config.getWorkspace().getType());
        assertFalse(config.getAi().isEnabled());
    }

    @Test
    void loadReadsConfigFromFile() throws IOException {
        String yamlContent = """
                workspace:
                  name: "test-workspace"
                  type: "plugin-ecosystem"
                  description: "A test workspace"
                search:
                  maxResults: 50
                ai:
                  enabled: false
                """;

        Path configDir = tempDir.resolve(".synthesis");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yaml"), yamlContent);

        SynthesisConfig config = ConfigLoader.load(tempDir);

        assertEquals("test-workspace", config.getWorkspace().getName());
        assertEquals("plugin-ecosystem", config.getWorkspace().getType());
        assertEquals(50, config.getSearch().getMaxResults());
    }

    @Test
    void rootConfigTakesPrecedence() throws IOException {
        // Internal config
        Path configDir = tempDir.resolve(".synthesis");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yaml"), """
                workspace:
                  name: "internal"
                """);

        // Root config (should take precedence)
        Files.writeString(tempDir.resolve("synthesis-config.yaml"), """
                workspace:
                  name: "root-level"
                """);

        SynthesisConfig config = ConfigLoader.load(tempDir);

        assertEquals("root-level", config.getWorkspace().getName());
    }

    @Test
    void generateDefaultConfigProducesValidYaml() throws IOException {
        String yaml = ConfigLoader.generateDefaultConfig("my-project", "monorepo");

        assertTrue(yaml.contains("my-project"));
        assertTrue(yaml.contains("monorepo"));
        assertTrue(yaml.contains("includePatterns"));
        assertTrue(yaml.contains("excludePatterns"));
        assertTrue(yaml.contains("enabled: false"));

        // Write and read back to verify it's valid YAML
        Path configFile = tempDir.resolve("test-config.yaml");
        Files.writeString(configFile, yaml);

        SynthesisConfig config = ConfigLoader.loadFromFile(configFile);
        assertEquals("my-project", config.getWorkspace().getName());
        assertEquals("monorepo", config.getWorkspace().getType());
    }

    @Test
    void validateRejectsInvalidConfig() {
        SynthesisConfig config = new SynthesisConfig();
        config.getScan().setMaxFileSizeBytes(-1);

        var error = ConfigLoader.validate(config);
        assertTrue(error.isPresent(), "Should reject negative maxFileSizeBytes");
    }

    @Test
    void validateAcceptsValidConfig() {
        SynthesisConfig config = new SynthesisConfig();

        var error = ConfigLoader.validate(config);
        assertTrue(error.isEmpty(), "Default config should be valid");
    }
}
