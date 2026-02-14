package io.exoreaction.synthesis.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ApprovalConfig} -- approval system configuration.
 */
class ApprovalConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultConfigHasEmbeddedCredentials() {
        // Production build has embedded bot token and channel ID
        ApprovalConfig config = new ApprovalConfig();

        assertTrue(config.isConfigured(), "Default should have embedded credentials");
        assertTrue(config.getSlackBotToken().startsWith("xoxb-"), "Should have valid bot token");
        assertTrue(config.getApprovalChannelId().startsWith("C"), "Should have valid channel ID");
    }

    @Test
    void loadReturnsDefaultsWhenNoConfigFile() {
        Path configPath = tempDir.resolve(".synthesis/approval.properties");

        ApprovalConfig config = ApprovalConfig.load(configPath);

        // Production build has embedded bot token and channel ID
        assertTrue(config.isConfigured(), "Should load embedded credentials when no config file");
    }

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        Path configPath = tempDir.resolve(".synthesis/approval.properties");

        ApprovalConfig original = new ApprovalConfig();
        original.setSlackBotToken("xoxb-test-token-123");
        original.setApprovalChannelId("C01234567");
        original.save(configPath);

        assertTrue(Files.exists(configPath));

        ApprovalConfig loaded = ApprovalConfig.load(configPath);

        assertEquals("xoxb-test-token-123", loaded.getSlackBotToken());
        assertEquals("C01234567", loaded.getApprovalChannelId());
        assertTrue(loaded.isConfigured());
    }

    @Test
    void isConfiguredRequiresBothTokenAndChannel() {
        ApprovalConfig config = new ApprovalConfig();

        // Only token
        config.setSlackBotToken("xoxb-test");
        config.setApprovalChannelId("");
        assertFalse(config.isConfigured());

        // Only channel
        config.setSlackBotToken("");
        config.setApprovalChannelId("C01234567");
        assertFalse(config.isConfigured());

        // Both present
        config.setSlackBotToken("xoxb-test");
        config.setApprovalChannelId("C01234567");
        assertTrue(config.isConfigured());
    }

    @Test
    void isConfiguredReturnsFalseForNullValues() {
        ApprovalConfig config = new ApprovalConfig();
        config.setSlackBotToken(null);
        config.setApprovalChannelId(null);

        assertFalse(config.isConfigured());
    }

    @Test
    void isConfiguredReturnsFalseForBlankValues() {
        ApprovalConfig config = new ApprovalConfig();
        config.setSlackBotToken("   ");
        config.setApprovalChannelId("   ");

        assertFalse(config.isConfigured());
    }

    @Test
    void loadHandlesMalformedFile() throws IOException {
        Path configPath = tempDir.resolve(".synthesis/approval.properties");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, "garbage content ===\n");

        ApprovalConfig config = ApprovalConfig.load(configPath);
        assertNotNull(config);
        assertFalse(config.isConfigured());
    }

    @Test
    void getConfigPathUsesCustomHome() {
        Path configPath = ApprovalConfig.getConfigPath(tempDir);
        assertEquals(tempDir.resolve(".synthesis/approval.properties"), configPath);
    }

    @Test
    void saveCreatesParentDirectories() throws IOException {
        Path configPath = tempDir.resolve("deep/nested/.synthesis/approval.properties");

        ApprovalConfig config = new ApprovalConfig();
        config.setSlackBotToken("xoxb-test");
        config.setApprovalChannelId("C01234567");
        config.save(configPath);

        assertTrue(Files.exists(configPath));
    }

    @Test
    void toStringDoesNotLeakToken() {
        ApprovalConfig config = new ApprovalConfig();
        config.setSlackBotToken("xoxb-secret-token-12345");
        config.setApprovalChannelId("C01234567");

        String str = config.toString();
        assertFalse(str.contains("xoxb-secret"), "toString should not contain bot token");
        assertTrue(str.contains("hasToken=true"));
        assertTrue(str.contains("hasChannel=true"));
    }

    @Test
    void savedFileContainsDocumentation() throws IOException {
        Path configPath = tempDir.resolve(".synthesis/approval.properties");

        ApprovalConfig config = new ApprovalConfig();
        config.save(configPath);

        String content = Files.readString(configPath);
        assertTrue(content.contains("Slack Bot Token"));
        assertTrue(content.contains("Channel ID"));
    }
}
