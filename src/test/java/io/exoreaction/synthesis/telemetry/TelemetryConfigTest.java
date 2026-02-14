package io.exoreaction.synthesis.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TelemetryConfig} -- always-on telemetry configuration.
 */
class TelemetryConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultConfigHasEmbeddedWebhook() {
        // Production build has embedded webhook for pilot distribution
        TelemetryConfig config = new TelemetryConfig();

        assertTrue(config.isWebhookConfigured(), "Production config should have embedded webhook");
        assertTrue(config.getSlackWebhookUrl().startsWith("https://hooks.slack.com/"),
                "Webhook should be valid Slack URL");
        assertEquals("", config.getInstalledAt());
    }

    @Test
    void loadReturnsDefaultsWhenNoConfigFile() {
        Path configPath = tempDir.resolve(".synthesis/telemetry.properties");

        TelemetryConfig config = TelemetryConfig.load(configPath);

        // Production build has embedded webhook as default
        assertTrue(config.isWebhookConfigured(), "Should load embedded webhook when no config file");
    }

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        Path configPath = tempDir.resolve(".synthesis/telemetry.properties");

        TelemetryConfig original = new TelemetryConfig();
        original.setSlackWebhookUrl("https://hooks.slack.com/test");
        original.setInstalledAt("2026-02-14T12:00:00Z");
        original.save(configPath);

        assertTrue(Files.exists(configPath));

        TelemetryConfig loaded = TelemetryConfig.load(configPath);

        assertEquals("https://hooks.slack.com/test", loaded.getSlackWebhookUrl());
        assertEquals("2026-02-14T12:00:00Z", loaded.getInstalledAt());
        assertTrue(loaded.isWebhookConfigured());
    }

    @Test
    void savedFileContainsPrivacyDocumentation() throws IOException {
        Path configPath = tempDir.resolve(".synthesis/telemetry.properties");

        TelemetryConfig config = new TelemetryConfig();
        config.save(configPath);

        String content = Files.readString(configPath);
        assertTrue(content.contains("mandatory"), "Should mention mandatory telemetry");
        assertTrue(content.contains("NEVER"), "Should document what is never sent");
    }

    @Test
    void loadHandlesMalformedFile() throws IOException {
        Path configPath = tempDir.resolve(".synthesis/telemetry.properties");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, "this is not valid properties format\n\n===\n");

        // Should not throw -- returns defaults (which include embedded webhook)
        TelemetryConfig config = TelemetryConfig.load(configPath);
        assertNotNull(config);
        assertTrue(config.isWebhookConfigured(), "Malformed file should fall back to embedded defaults");
    }

    @Test
    void loadParsesWebhookUrl() throws IOException {
        Path configPath = tempDir.resolve(".synthesis/telemetry.properties");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, "telemetry.slack.webhook_url=https://hooks.slack.com/test\n");

        TelemetryConfig config = TelemetryConfig.load(configPath);
        assertEquals("https://hooks.slack.com/test", config.getSlackWebhookUrl());
        assertTrue(config.isWebhookConfigured());
    }

    @Test
    void getConfigPathUsesCustomHome() {
        Path configPath = TelemetryConfig.getConfigPath(tempDir);
        assertEquals(tempDir.resolve(".synthesis/telemetry.properties"), configPath);
    }

    @Test
    void toStringDoesNotLeakWebhookUrl() {
        TelemetryConfig config = new TelemetryConfig();
        config.setSlackWebhookUrl("https://hooks.slack.com/services/SECRET/TOKEN");

        String str = config.toString();
        assertFalse(str.contains("SECRET"), "toString should not contain webhook URL");
        assertFalse(str.contains("TOKEN"), "toString should not contain webhook URL");
        assertTrue(str.contains("webhookConfigured=true"));
    }

    @Test
    void saveCreatesParentDirectories() throws IOException {
        Path configPath = tempDir.resolve("deep/nested/path/.synthesis/telemetry.properties");

        TelemetryConfig config = new TelemetryConfig();
        config.save(configPath);

        assertTrue(Files.exists(configPath));
    }

    @Test
    void markInstalledSetsTimestamp() {
        TelemetryConfig config = new TelemetryConfig();
        assertTrue(config.getInstalledAt().isBlank());

        config.markInstalled();

        assertFalse(config.getInstalledAt().isBlank());
        // Should be a valid ISO-8601 timestamp
        assertDoesNotThrow(() -> java.time.Instant.parse(config.getInstalledAt()));
    }

    @Test
    void markInstalledDoesNotOverwriteExisting() {
        TelemetryConfig config = new TelemetryConfig();
        config.setInstalledAt("2026-01-01T00:00:00Z");

        config.markInstalled();

        assertEquals("2026-01-01T00:00:00Z", config.getInstalledAt());
    }

    @Test
    void isWebhookConfiguredReturnsFalseForEmpty() {
        TelemetryConfig config = new TelemetryConfig();
        config.setSlackWebhookUrl("");
        assertFalse(config.isWebhookConfigured());
    }

    @Test
    void isWebhookConfiguredReturnsFalseForNull() {
        TelemetryConfig config = new TelemetryConfig();
        config.setSlackWebhookUrl(null);
        assertFalse(config.isWebhookConfigured());
    }

    @Test
    void isWebhookConfiguredReturnsFalseForBlank() {
        TelemetryConfig config = new TelemetryConfig();
        config.setSlackWebhookUrl("   ");
        assertFalse(config.isWebhookConfigured());
    }

    @Test
    void isWebhookConfiguredReturnsTrueForUrl() {
        TelemetryConfig config = new TelemetryConfig();
        config.setSlackWebhookUrl("https://hooks.slack.com/test");
        assertTrue(config.isWebhookConfigured());
    }
}
