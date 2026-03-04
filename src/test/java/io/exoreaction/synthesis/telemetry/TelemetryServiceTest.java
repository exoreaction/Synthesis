package io.exoreaction.synthesis.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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

    // ---- Throttle tests ----

    @Test
    void throttle_first_call_writes_state_file() throws IOException {
        TelemetryConfig config = new TelemetryConfig();
        config.setSlackWebhookUrl("https://hooks.slack.com/test");
        Path throttlePath = tempDir.resolve(".synthesis/telemetry-throttle.properties");

        TelemetryService service = new TelemetryService(config, "test-uuid", throttlePath);
        service.reportCommand("changelog", true, 100);
        service.shutdown();

        assertTrue(Files.exists(throttlePath), "Throttle file should be created on first call");
        Properties props = new Properties();
        try (var in = Files.newInputStream(throttlePath)) { props.load(in); }
        assertTrue(props.containsKey("changelog"), "changelog key should be stored in throttle file");
    }

    @Test
    void throttle_blocks_same_command_within_window() throws IOException {
        TelemetryConfig config = new TelemetryConfig();
        config.setSlackWebhookUrl("https://hooks.slack.com/test");
        Path throttlePath = tempDir.resolve(".synthesis/telemetry-throttle.properties");

        // Pre-populate with a "just sent" timestamp
        Files.createDirectories(throttlePath.getParent());
        long tsBefore = System.currentTimeMillis();
        Properties setup = new Properties();
        setup.setProperty("changelog", String.valueOf(tsBefore));
        try (var out = Files.newOutputStream(throttlePath)) { setup.store(out, null); }

        TelemetryService service = new TelemetryService(config, "test-uuid", throttlePath);
        service.reportCommand("changelog", true, 100); // should be throttled
        service.shutdown();

        // Throttle file timestamp should NOT have advanced
        Properties after = new Properties();
        try (var in = Files.newInputStream(throttlePath)) { after.load(in); }
        assertEquals(tsBefore, Long.parseLong(after.getProperty("changelog")),
                "Timestamp must not change when command is throttled");
    }

    @Test
    void throttle_allows_same_command_after_window_expires() throws IOException {
        TelemetryConfig config = new TelemetryConfig();
        config.setSlackWebhookUrl("https://hooks.slack.com/test");
        Path throttlePath = tempDir.resolve(".synthesis/telemetry-throttle.properties");

        // Pre-populate with a timestamp older than the throttle window
        Files.createDirectories(throttlePath.getParent());
        long oldTs = System.currentTimeMillis() - TelemetryService.THROTTLE_WINDOW_MS - 5_000;
        Properties setup = new Properties();
        setup.setProperty("changelog", String.valueOf(oldTs));
        try (var out = Files.newOutputStream(throttlePath)) { setup.store(out, null); }

        TelemetryService service = new TelemetryService(config, "test-uuid", throttlePath);
        service.reportCommand("changelog", true, 100); // should NOT be throttled
        service.shutdown();

        Properties after = new Properties();
        try (var in = Files.newInputStream(throttlePath)) { after.load(in); }
        long tsAfter = Long.parseLong(after.getProperty("changelog"));
        assertTrue(tsAfter > oldTs, "Timestamp should be refreshed after window expires");
    }

    @Test
    void throttle_different_commands_are_independent() throws IOException {
        TelemetryConfig config = new TelemetryConfig();
        config.setSlackWebhookUrl("https://hooks.slack.com/test");
        Path throttlePath = tempDir.resolve(".synthesis/telemetry-throttle.properties");

        // Pre-populate changelog as throttled
        Files.createDirectories(throttlePath.getParent());
        Properties setup = new Properties();
        setup.setProperty("changelog", String.valueOf(System.currentTimeMillis()));
        try (var out = Files.newOutputStream(throttlePath)) { setup.store(out, null); }

        TelemetryService service = new TelemetryService(config, "test-uuid", throttlePath);
        service.reportCommand("scan", true, 200); // different command, should NOT be throttled
        service.shutdown();

        Properties after = new Properties();
        try (var in = Files.newInputStream(throttlePath)) { after.load(in); }
        assertTrue(after.containsKey("scan"), "scan should be stored regardless of changelog throttle");
    }

    @Test
    void throttle_null_path_never_throttles() {
        TelemetryConfig config = new TelemetryConfig();
        config.setSlackWebhookUrl("https://hooks.slack.com/test");

        // throttlePath=null → throttling disabled, no file I/O, no exceptions
        TelemetryService service = new TelemetryService(config, "test-uuid", null);
        assertDoesNotThrow(() -> service.reportCommand("scan", true, 100));
        assertDoesNotThrow(() -> service.reportCommand("scan", true, 100));
        service.shutdown();
    }
}
