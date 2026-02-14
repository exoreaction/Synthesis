package io.exoreaction.synthesis.telemetry;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Debug test to see what UUIDs the bot can read from the approval channel.
 */
class DebugApprovalTest {

    @Test
    void showApprovedUUIDs() throws Exception {
        System.out.println("\n=== Debug: Reading Approval Channel ===\n");

        ApprovalConfig config = new ApprovalConfig();
        System.out.println("Bot Token: " + mask(config.getSlackBotToken()));
        System.out.println("Channel ID: " + config.getApprovalChannelId());
        System.out.println();

        // Try to read channel directly with Slack API
        System.out.println("Testing direct Slack API access...");
        com.slack.api.Slack slack = com.slack.api.Slack.getInstance();
        com.slack.api.methods.MethodsClient methods = slack.methods(config.getSlackBotToken());

        try {
            var response = methods.conversationsHistory(r -> r
                    .channel(config.getApprovalChannelId())
                    .limit(10));

            if (!response.isOk()) {
                System.out.println("ERROR: " + response.getError());
                System.out.println("Needed: " + response.getNeeded());
                System.out.println("Provided: " + response.getProvided());
            } else {
                System.out.println("Successfully read channel!");
                System.out.println("Messages found: " + response.getMessages().size());
                System.out.println("\nRecent messages:");
                for (var msg : response.getMessages()) {
                    System.out.println("  - " + (msg.getText() != null ? msg.getText().substring(0, Math.min(100, msg.getText().length())) : "(no text)"));
                }
            }
        } catch (Exception e) {
            System.out.println("Exception: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\nNow testing ApprovalService...");
        ApprovalService service = ApprovalService.create();

        // Manually call fetchApprovedUUIDs to see what the bot reads
        System.out.println("Fetching approved UUIDs from channel...");
        java.util.Set<String> approvedUuids = service.fetchApprovedUUIDs();

        System.out.println("\nFound " + approvedUuids.size() + " approved UUIDs:");
        for (String uuid : approvedUuids) {
            System.out.println("  - " + uuid);
        }

        System.out.println("\nTest UUID: 323aeabd-6331-4ddb-ad6d-7f1a54e9fd4f");
        System.out.println("Is approved: " + approvedUuids.contains("323aeabd-6331-4ddb-ad6d-7f1a54e9fd4f"));
        System.out.println();
    }

    private String mask(String token) {
        if (token == null || token.length() < 20) {
            return "***";
        }
        return token.substring(0, 15) + "...";
    }
}
