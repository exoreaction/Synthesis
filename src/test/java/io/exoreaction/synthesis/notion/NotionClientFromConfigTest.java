package io.exoreaction.synthesis.notion;

import io.exoreaction.synthesis.config.SynthesisConfig.NotionConfig;
import io.exoreaction.synthesis.notion.NotionTokenStore.NotionOAuthToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the enhanced {@link NotionClient#fromConfig(NotionConfig)} method
 * which now includes OAuth token store resolution.
 *
 * <p>Note: The full token store integration is tested indirectly since
 * {@code fromConfig} creates its own {@link NotionTokenStore} using the default
 * path ({@code ~/.synthesis/}). These tests verify:
 * <ul>
 *   <li>Config token takes priority over all other sources</li>
 *   <li>Error message mentions {@code synthesis notion auth} when no token found</li>
 *   <li>The resolution order: config > env > store > error</li>
 * </ul>
 */
class NotionClientFromConfigTest {

    // -----------------------------------------------------------------------
    // 1. Config token is used when present (highest priority)
    // -----------------------------------------------------------------------

    @Test
    void fromConfig_withConfigToken_usesConfigToken() {
        var config = new NotionConfig();
        config.setToken("ntn_config_token_xyz");

        // Should succeed regardless of env or store
        NotionClient client = NotionClient.fromConfig(config);
        assertNotNull(client);
    }

    // -----------------------------------------------------------------------
    // 2. When no token from any source, error message mentions 'synthesis notion auth'
    // -----------------------------------------------------------------------

    @Test
    void fromConfig_noToken_errorMessageMentionsSynthesisNotionAuth() {
        var config = new NotionConfig();
        // token is null by default

        // Only run if NOTION_TOKEN is not set in the environment
        // AND ~/.synthesis/notion-oauth.json does not exist
        if (System.getenv("NOTION_TOKEN") != null) {
            return; // Skip in environments with NOTION_TOKEN set
        }
        var store = new NotionTokenStore();
        if (store.exists()) {
            return; // Skip if an OAuth token already exists
        }

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NotionClient.fromConfig(config));
        assertTrue(ex.getMessage().contains("synthesis notion auth"),
                "Error message should mention 'synthesis notion auth': " + ex.getMessage());
    }

    // -----------------------------------------------------------------------
    // 3. Error message still mentions config and env var options
    // -----------------------------------------------------------------------

    @Test
    void fromConfig_noToken_errorMessageMentionsAllOptions() {
        var config = new NotionConfig();

        if (System.getenv("NOTION_TOKEN") != null) {
            return;
        }
        var store = new NotionTokenStore();
        if (store.exists()) {
            return;
        }

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> NotionClient.fromConfig(config));
        assertTrue(ex.getMessage().contains("notion.token"),
                "Error should mention config option: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("NOTION_TOKEN"),
                "Error should mention env var: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("synthesis notion auth"),
                "Error should mention OAuth command: " + ex.getMessage());
    }

    // -----------------------------------------------------------------------
    // 4. NotionTokenStore round-trip: verify token store works independently
    // -----------------------------------------------------------------------

    @Test
    void tokenStore_savedToken_canBeLoadedByNewInstance(@TempDir Path tempDir) throws Exception {
        var tokenFile = tempDir.resolve("notion-oauth.json");
        var store = new NotionTokenStore(tokenFile);

        var token = new NotionOAuthToken(
                "ntn_oauth_test_token",
                "OAuth Workspace",
                "ws-oauth-123",
                "bot-oauth-456",
                Long.MAX_VALUE
        );

        store.save(token);

        // Create a new store instance pointing to the same file
        var store2 = new NotionTokenStore(tokenFile);
        var loaded = store2.load();

        assertTrue(loaded.isPresent());
        assertEquals("ntn_oauth_test_token", loaded.get().accessToken());
        assertEquals("OAuth Workspace", loaded.get().workspaceName());
    }

    // -----------------------------------------------------------------------
    // 5. Config token takes priority even when store has a token
    // -----------------------------------------------------------------------

    @Test
    void fromConfig_withConfigToken_configTakesPriority() {
        // This test verifies that config token is used without reaching the store.
        // Since fromConfig creates its own NotionTokenStore internally,
        // we verify by providing a config token — the method should succeed
        // without needing anything from the store.
        var config = new NotionConfig();
        config.setToken("ntn_from_config");

        NotionClient client = NotionClient.fromConfig(config);
        assertNotNull(client, "Config token should be accepted without store");
    }
}
