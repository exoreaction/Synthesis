package io.exoreaction.synthesis.notion;

import io.exoreaction.synthesis.notion.NotionTokenStore.NotionOAuthToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NotionTokenStore} — file-based storage for Notion OAuth tokens.
 */
class NotionTokenStoreTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // 1. save and load round-trip
    // -----------------------------------------------------------------------

    @Test
    void saveAndLoad_roundTrip_returnsOriginalToken() throws Exception {
        Path tokenFile = tempDir.resolve("notion-oauth.json");
        var store = new NotionTokenStore(tokenFile);

        var token = new NotionOAuthToken(
                "ntn_abc123",
                "My Workspace",
                "ws-id-456",
                "bot-789",
                Long.MAX_VALUE
        );

        store.save(token);
        Optional<NotionOAuthToken> loaded = store.load();

        assertTrue(loaded.isPresent());
        assertEquals("ntn_abc123", loaded.get().accessToken());
        assertEquals("My Workspace", loaded.get().workspaceName());
        assertEquals("ws-id-456", loaded.get().workspaceId());
        assertEquals("bot-789", loaded.get().botId());
        assertEquals(Long.MAX_VALUE, loaded.get().expiresAtEpochMs());
    }

    // -----------------------------------------------------------------------
    // 2. load when file does not exist returns empty
    // -----------------------------------------------------------------------

    @Test
    void load_fileDoesNotExist_returnsEmpty() {
        Path tokenFile = tempDir.resolve("nonexistent.json");
        var store = new NotionTokenStore(tokenFile);

        Optional<NotionOAuthToken> loaded = store.load();

        assertTrue(loaded.isEmpty());
    }

    // -----------------------------------------------------------------------
    // 3. file permissions are 600 (POSIX only)
    // -----------------------------------------------------------------------

    @Test
    void save_setsFilePermissions600() throws Exception {
        Path tokenFile = tempDir.resolve("notion-oauth.json");
        var store = new NotionTokenStore(tokenFile);

        var token = new NotionOAuthToken("tok", "ws", "id", "bot", Long.MAX_VALUE);
        store.save(token);

        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tokenFile);
            Set<PosixFilePermission> expected = Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            );
            assertEquals(expected, perms, "Token file should have permissions rw-------");
        } catch (UnsupportedOperationException e) {
            // Non-POSIX system (Windows) — skip this assertion
        }
    }

    // -----------------------------------------------------------------------
    // 4. clear deletes the file
    // -----------------------------------------------------------------------

    @Test
    void clear_deletesTokenFile() throws Exception {
        Path tokenFile = tempDir.resolve("notion-oauth.json");
        var store = new NotionTokenStore(tokenFile);

        var token = new NotionOAuthToken("tok", "ws", "id", "bot", Long.MAX_VALUE);
        store.save(token);
        assertTrue(store.exists());

        store.clear();
        assertFalse(store.exists());
        assertFalse(Files.exists(tokenFile));
    }

    // -----------------------------------------------------------------------
    // 5. exists returns false when no file
    // -----------------------------------------------------------------------

    @Test
    void exists_noFile_returnsFalse() {
        Path tokenFile = tempDir.resolve("notion-oauth.json");
        var store = new NotionTokenStore(tokenFile);

        assertFalse(store.exists());
    }

    // -----------------------------------------------------------------------
    // 6. exists returns true after save
    // -----------------------------------------------------------------------

    @Test
    void exists_afterSave_returnsTrue() throws Exception {
        Path tokenFile = tempDir.resolve("notion-oauth.json");
        var store = new NotionTokenStore(tokenFile);

        var token = new NotionOAuthToken("tok", "ws", "id", "bot", Long.MAX_VALUE);
        store.save(token);

        assertTrue(store.exists());
    }

    // -----------------------------------------------------------------------
    // 7. save creates parent directories
    // -----------------------------------------------------------------------

    @Test
    void save_createsParentDirectories() throws Exception {
        Path tokenFile = tempDir.resolve("nested/dir/notion-oauth.json");
        var store = new NotionTokenStore(tokenFile);

        var token = new NotionOAuthToken("tok", "ws", "id", "bot", Long.MAX_VALUE);
        store.save(token);

        assertTrue(Files.exists(tokenFile));
    }

    // -----------------------------------------------------------------------
    // 8. clear on nonexistent file does not throw
    // -----------------------------------------------------------------------

    @Test
    void clear_nonexistentFile_doesNotThrow() throws IOException {
        Path tokenFile = tempDir.resolve("notion-oauth.json");
        var store = new NotionTokenStore(tokenFile);

        assertDoesNotThrow(() -> store.clear());
    }
}
