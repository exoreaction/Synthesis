package io.exoreaction.synthesis.notion;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Manages storage of Notion OAuth tokens on the local filesystem.
 *
 * <p>Tokens are persisted to {@code ~/.synthesis/notion-oauth.json} with
 * file permissions restricted to owner read/write only (600). Notion OAuth
 * tokens do not expire, so no refresh logic is needed.
 *
 * <p>Thread safety: methods are synchronized on the instance to prevent
 * concurrent file access issues.
 */
public class NotionTokenStore {

    private static final Logger LOG = Logger.getLogger(NotionTokenStore.class.getName());

    private static final String TOKEN_FILE_NAME = "notion-oauth.json";

    private final Path tokenFile;
    private final ObjectMapper objectMapper;

    /**
     * Creates a token store using the default location: {@code ~/.synthesis/notion-oauth.json}.
     */
    public NotionTokenStore() {
        this(Path.of(System.getProperty("user.home"), ".synthesis", TOKEN_FILE_NAME));
    }

    /**
     * Creates a token store at a custom path (primarily for testing).
     *
     * @param tokenFile the path to store the token JSON file
     */
    public NotionTokenStore(Path tokenFile) {
        this.tokenFile = tokenFile;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Persists the given OAuth token to disk.
     *
     * <p>Creates the parent directory if it does not exist. Sets file
     * permissions to 600 (owner read/write only) on POSIX systems.
     *
     * @param token the OAuth token to store
     * @throws IOException if writing fails
     */
    public synchronized void save(NotionOAuthToken token) throws IOException {
        Path parent = tokenFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(token);
        Files.writeString(tokenFile, json);

        // Set file permissions to 600 (owner read/write only) on POSIX systems
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(tokenFile, perms);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. Windows) — skip permission setting
            LOG.fine("POSIX file permissions not supported, skipping chmod 600");
        }
    }

    /**
     * Loads the stored OAuth token from disk.
     *
     * @return the token if the file exists and is readable, empty otherwise
     */
    public synchronized Optional<NotionOAuthToken> load() {
        if (!Files.exists(tokenFile)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(tokenFile);
            var token = objectMapper.readValue(json, NotionOAuthToken.class);
            return Optional.of(token);
        } catch (IOException e) {
            LOG.warning("Failed to read Notion OAuth token: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns {@code true} if a token file exists on disk.
     */
    public synchronized boolean exists() {
        return Files.exists(tokenFile);
    }

    /**
     * Deletes the token file from disk.
     *
     * @throws IOException if deletion fails
     */
    public synchronized void clear() throws IOException {
        Files.deleteIfExists(tokenFile);
    }

    /**
     * Notion OAuth token data.
     *
     * <p>Notion tokens do not expire (no refresh_token, no expires_in in the
     * OAuth response), so {@code expiresAtEpochMs} is stored as {@link Long#MAX_VALUE}.
     *
     * @param accessToken    the bearer token for Notion API calls
     * @param workspaceName  the human-readable workspace name
     * @param workspaceId    the Notion workspace UUID
     * @param botId          the bot user ID within the workspace
     * @param expiresAtEpochMs  expiration timestamp (Long.MAX_VALUE for non-expiring tokens)
     */
    public record NotionOAuthToken(
            @JsonProperty("accessToken") String accessToken,
            @JsonProperty("workspaceName") String workspaceName,
            @JsonProperty("workspaceId") String workspaceId,
            @JsonProperty("botId") String botId,
            @JsonProperty("expiresAtEpochMs") long expiresAtEpochMs
    ) {}
}
