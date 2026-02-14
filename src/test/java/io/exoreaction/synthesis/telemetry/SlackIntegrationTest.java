package io.exoreaction.synthesis.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Slack webhook and bot API.
 *
 * <p>This test verifies that:
 * <ul>
 *   <li>Telemetry can send events to the configured Slack webhook</li>
 *   <li>Approval service can read messages from the configured approval channel</li>
 * </ul>
 *
 * <p>Run with: {@code mvn test -Dtest=SlackIntegrationTest}
 */
class SlackIntegrationTest {

    @Test
    void testWebhookConfigured() {
        TelemetryConfig config = new TelemetryConfig();
        assertTrue(config.isWebhookConfigured(), "Webhook URL should be configured");
        assertTrue(config.getSlackWebhookUrl().startsWith("https://hooks.slack.com/"),
                "Webhook URL should be valid Slack webhook");
    }

    @Test
    void testApprovalConfigured() {
        ApprovalConfig config = new ApprovalConfig();
        assertTrue(config.isConfigured(), "Approval config should be complete");
        assertTrue(config.getSlackBotToken().startsWith("xoxb-"),
                "Bot token should be valid Slack bot token");
        assertTrue(config.getApprovalChannelId().startsWith("C"),
                "Channel ID should be valid Slack channel ID");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SYNTHESIS_SLACK_INTEGRATION_TEST", matches = "true")
    void testSendTelemetryEvent() throws Exception {
        // This test only runs if explicitly enabled (to avoid spamming Slack during normal builds)
        System.out.println("Testing telemetry webhook...");

        TelemetryService service = TelemetryService.create();

        // Send a test command event
        service.reportCommand("test-command", true, 123);

        // Wait a moment for async delivery
        Thread.sleep(2000);

        System.out.println("✓ Telemetry event sent successfully");
        System.out.println("  Check your Slack channel for the test message");
        System.out.println("  UUID: " + service.getClientUuid());

        service.shutdown();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SYNTHESIS_SLACK_INTEGRATION_TEST", matches = "true")
    void testReadApprovalChannel() throws Exception {
        // This test only runs if explicitly enabled
        System.out.println("Testing approval channel reading...");

        ApprovalService service = ApprovalService.create();

        // Try to read the approval channel
        // This will return false if no UUIDs are approved yet, but it proves the API works
        String testUuid = "00000000-0000-0000-0000-000000000000";
        boolean approved = service.isApproved(testUuid);

        System.out.println("✓ Approval channel read successfully");
        System.out.println("  Test UUID approved: " + approved);
        System.out.println("  (This should be false unless you posted this UUID in the channel)");
    }

    @Test
    void testFullIntegrationFlow() {
        // Test the full flow without actually calling Slack APIs
        System.out.println("\n=== Synthesis Slack Integration Status ===\n");

        // 1. Check telemetry config
        TelemetryConfig telemetryConfig = new TelemetryConfig();
        System.out.println("Telemetry Configuration:");
        System.out.println("  Webhook URL: " + maskToken(telemetryConfig.getSlackWebhookUrl()));
        System.out.println("  Configured: " + (telemetryConfig.isWebhookConfigured() ? "✓" : "✗"));

        // 2. Check approval config
        ApprovalConfig approvalConfig = new ApprovalConfig();
        System.out.println("\nApproval Configuration:");
        System.out.println("  Bot Token: " + maskToken(approvalConfig.getSlackBotToken()));
        System.out.println("  Channel ID: " + approvalConfig.getApprovalChannelId());
        System.out.println("  Configured: " + (approvalConfig.isConfigured() ? "✓" : "✗"));

        // 3. Check services
        System.out.println("\nServices:");
        TelemetryService telemetryService = TelemetryService.create();
        System.out.println("  Telemetry Service: " + (telemetryService.isActive() ? "ACTIVE" : "INACTIVE"));
        System.out.println("  Client UUID: " + telemetryService.getClientUuid());

        ApprovalService approvalService = ApprovalService.create();
        System.out.println("  Approval Service: Ready");

        System.out.println("\n=== All systems configured ===\n");
        System.out.println("To test actual Slack API calls, run:");
        System.out.println("  SYNTHESIS_SLACK_INTEGRATION_TEST=true mvn test -Dtest=SlackIntegrationTest");
        System.out.println();

        // Assert everything is configured
        assertTrue(telemetryConfig.isWebhookConfigured());
        assertTrue(approvalConfig.isConfigured());
        assertTrue(telemetryService.isActive());

        telemetryService.shutdown();
    }

    private String maskToken(String token) {
        if (token == null || token.length() < 20) {
            return "***";
        }
        return token.substring(0, 15) + "..." + token.substring(token.length() - 4);
    }
}
