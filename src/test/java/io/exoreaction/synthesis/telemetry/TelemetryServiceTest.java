package io.exoreaction.synthesis.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TelemetryService} -- always-on telemetry service lifecycle.
 *
 * <p>Note: These tests verify the service behavior without actually sending
 * Slack messages. The webhook URL is either empty or points to nothing.
 */
class TelemetryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void serviceWithDefaultWebhookIsActive() {
        // Production build has embedded webhook in defaults
        TelemetryConfig config = new TelemetryConfig();

        TelemetryService service = new TelemetryService(config, "test-uuid");

        assertTrue(service.isActive(), "Service should be active with embedded webhook");
        service.shutdown();
    }

    @Test
    void serviceWithEmptyWebhookIsNotActive() {
        TelemetryConfig config = new TelemetryConfig();
        config.setSlackWebhookUrl("");

        TelemetryService service = new TelemetryService(config, "test-uuid");

        assertFalse(service.isActive());
        service.shutdown();
    }

    @Test
    void serviceWithWebhookIsActive() {
        TelemetryConfig config = new TelemetryConfig();
        config.setSlackWebhookUrl("https://hooks.slack.com/test");

        TelemetryService service = new TelemetryService(config, "test-uuid");

        assertTrue(service.isActive());
        service.shutdown();
    }

    @Test
    void reportCommandDoesNotThrowWhenNoWebhook() {
        TelemetryConfig config = new TelemetryConfig();

        TelemetryService service = new TelemetryService(config, "test-uuid");

        // Should not throw even without webhook
        assertDoesNotThrow(() -> service.reportCommand("scan", true, 100));
        assertDoesNotThrow(() -> service.reportCommand("search", false, 50));
        service.shutdown();
    }

    @Test
    void reportInstallDoesNotThrowWhenNoWebhook() {
        TelemetryConfig config = new TelemetryConfig();

        TelemetryService service = new TelemetryService(config, "test-uuid");

        assertDoesNotThrow(service::reportInstall);
        service.shutdown();
    }

    @Test
    void reportHeartbeatDoesNotThrowWhenNoWebhook() {
        TelemetryConfig config = new TelemetryConfig();

        TelemetryService service = new TelemetryService(config, "test-uuid");

        assertDoesNotThrow(service::reportHeartbeat);
        service.shutdown();
    }

    @Test
    void shutdownIsIdempotent() {
        TelemetryConfig config = new TelemetryConfig();

        TelemetryService service = new TelemetryService(config, "test-uuid");

        // Multiple shutdowns should not throw
        assertDoesNotThrow(service::shutdown);
        assertDoesNotThrow(service::shutdown);
        assertDoesNotThrow(service::shutdown);
    }

    @Test
    void getClientUuidReturnsProvidedUuid() {
        TelemetryService service = new TelemetryService(new TelemetryConfig(), "my-test-uuid");

        assertEquals("my-test-uuid", service.getClientUuid());
        service.shutdown();
    }

    @Test
    void createFromHomeDirLoadsConfig() throws IOException {
        // Set up a test home with telemetry config
        Path synthDir = tempDir.resolve(".synthesis");
        Files.createDirectories(synthDir);

        TelemetryConfig config = new TelemetryConfig();
        config.save(TelemetryConfig.getConfigPath(tempDir));

        TelemetryService service = TelemetryService.create(tempDir);

        // Default config has embedded webhook = active
        assertTrue(service.isActive(), "Service should be active with embedded webhook");
        assertNotNull(service.getClientUuid());
        service.shutdown();
    }

    @Test
    void describeWhatIsSentContainsPrivacyInfo() {
        TelemetryService service = new TelemetryService(new TelemetryConfig(), "test-uuid");

        String description = service.describeWhatIsSent();

        assertTrue(description.contains("NEVER"));
        assertTrue(description.contains("Command name"));
        assertTrue(description.contains("Workspace content"));
        assertTrue(description.contains("credentials"));
        assertTrue(description.contains("User identity"));
        assertTrue(description.contains("mandatory"));
        service.shutdown();
    }

    @Test
    void describeWhatIsSentShowsActiveStatus() {
        TelemetryConfig config = new TelemetryConfig();
        config.setSlackWebhookUrl("https://hooks.slack.com/test");

        TelemetryService service = new TelemetryService(config, "test-uuid");

        String description = service.describeWhatIsSent();
        assertTrue(description.contains("ACTIVE"));
        service.shutdown();
    }

    @Test
    void describeWhatIsSentShowsActiveWithEmbeddedWebhook() {
        // Production build has embedded webhook in defaults
        TelemetryConfig config = new TelemetryConfig();

        TelemetryService service = new TelemetryService(config, "test-uuid");

        String description = service.describeWhatIsSent();
        assertTrue(description.contains("ACTIVE"), "Should show ACTIVE with embedded webhook");
        service.shutdown();
    }

    @Test
    void reportCommandWorksWithActiveWebhook() {
        TelemetryConfig config = new TelemetryConfig();
        config.setSlackWebhookUrl("https://hooks.slack.com/test");

        TelemetryService service = new TelemetryService(config, "test-uuid");

        // Should not throw even with webhook (will fail silently on network)
        assertDoesNotThrow(() -> service.reportCommand("scan", true, 100));
        service.shutdown();
    }

    @Test
    void serviceVersionIsSet() {
        assertNotNull(TelemetryService.SYNTHESIS_VERSION);
        assertFalse(TelemetryService.SYNTHESIS_VERSION.isBlank());
    }
}
