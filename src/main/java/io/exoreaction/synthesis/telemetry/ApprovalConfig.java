package io.exoreaction.synthesis.telemetry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuration for the Synthesis pilot approval system.
 *
 * <p>The approval system reads a Slack channel to determine which client UUIDs
 * are approved for the pilot program. Configuration is stored globally at
 * {@code ~/.synthesis/approval.properties}.
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>{@code slack_bot_token} -- Slack Bot token (xoxb-...) for reading channel messages</li>
 *   <li>{@code approval_channel_id} -- Slack channel ID (C...) containing approved UUIDs</li>
 * </ul>
 *
 * <p>The bot needs {@code channels:read} and {@code channels:history} scopes.
 * See {@code docs/SLACK-SETUP.md} for setup instructions.
 */
public class ApprovalConfig {

    /** Config file for approval settings. */
    public static final String CONFIG_FILENAME = "approval.properties";

    /** Default Slack bot token for reading approval channel. */
    // NOTE: This bot token has channels:read and channels:history scopes.
    // It can only read public channel messages, not DMs or private channels.
    private static final String DEFAULT_BOT_TOKEN = "xoxb-2741852652450-10496267091351-5mZK8KgCMrjxbq6mXZKlEOMU";

    /** Default approval channel ID (#synthesis-pilots). */
    private static final String DEFAULT_APPROVAL_CHANNEL_ID = "C0AEY8HHS7P";

    private String slackBotToken;
    private String approvalChannelId;

    public ApprovalConfig() {
        this.slackBotToken = DEFAULT_BOT_TOKEN;
        this.approvalChannelId = DEFAULT_APPROVAL_CHANNEL_ID;
    }

    // --- Getters and Setters ---

    public String getSlackBotToken() {
        return slackBotToken;
    }

    public void setSlackBotToken(String slackBotToken) {
        this.slackBotToken = slackBotToken;
    }

    public String getApprovalChannelId() {
        return approvalChannelId;
    }

    public void setApprovalChannelId(String approvalChannelId) {
        this.approvalChannelId = approvalChannelId;
    }

    /**
     * Returns whether the approval system is fully configured
     * (bot token and channel ID both present).
     */
    public boolean isConfigured() {
        return slackBotToken != null && !slackBotToken.isBlank()
                && approvalChannelId != null && !approvalChannelId.isBlank();
    }

    /**
     * Returns the path to the global approval config file.
     */
    public static Path getConfigPath() {
        return Path.of(System.getProperty("user.home"))
                .resolve(ClientUUID.GLOBAL_DIR)
                .resolve(CONFIG_FILENAME);
    }

    /**
     * Returns the config path under a custom home directory (for testing).
     */
    public static Path getConfigPath(Path homeDir) {
        return homeDir.resolve(ClientUUID.GLOBAL_DIR).resolve(CONFIG_FILENAME);
    }

    /**
     * Loads approval config from the global config file.
     */
    public static ApprovalConfig load() {
        return load(getConfigPath());
    }

    /**
     * Loads approval config from a specific path.
     */
    public static ApprovalConfig load(Path configPath) {
        ApprovalConfig config = new ApprovalConfig();

        if (!Files.exists(configPath)) {
            return config;
        }

        try {
            Properties props = new Properties();
            props.load(Files.newBufferedReader(configPath));

            config.slackBotToken = props.getProperty("slack_bot_token", "");
            config.approvalChannelId = props.getProperty("approval_channel_id", "");
        } catch (IOException e) {
            // Silently return defaults
        }

        return config;
    }

    /**
     * Saves the approval config to the global config file.
     */
    public void save() throws IOException {
        save(getConfigPath());
    }

    /**
     * Saves the approval config to a specific path.
     */
    public void save(Path configPath) throws IOException {
        Files.createDirectories(configPath.getParent());

        String content = """
                # Synthesis Pilot Approval Configuration
                #
                # The approval system checks a Slack channel for approved UUIDs.
                # The bot needs channels:read and channels:history scopes.
                # See docs/SLACK-SETUP.md for setup instructions.

                # Slack Bot Token (starts with xoxb-)
                slack_bot_token=%s

                # Slack Channel ID for the approval list (starts with C)
                approval_channel_id=%s
                """.formatted(slackBotToken, approvalChannelId);

        Files.writeString(configPath, content);
    }

    @Override
    public String toString() {
        return "ApprovalConfig{configured=" + isConfigured()
                + ", hasToken=" + (slackBotToken != null && !slackBotToken.isBlank())
                + ", hasChannel=" + (approvalChannelId != null && !approvalChannelId.isBlank()) + "}";
    }
}
