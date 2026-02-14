package io.exoreaction.synthesis.telemetry;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.model.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pilot approval service that checks whether a client UUID is approved.
 *
 * <p>The approval system works as follows:
 * <ol>
 *   <li>A Slack bot reads the approval channel (e.g., {@code #synthesis-pilots})</li>
 *   <li>Messages in the channel contain approved UUIDs (any format -- the bot
 *       extracts UUIDs via regex)</li>
 *   <li>The approval status is cached locally at {@code ~/.synthesis/approval-status}</li>
 *   <li>The cache is refreshed daily (first command after 24 hours triggers a refresh)</li>
 * </ol>
 *
 * <p>Enforcement is <b>soft</b>: unapproved installations show a nag message but
 * commands still execute normally.
 *
 * <p>Cache format ({@code ~/.synthesis/approval-status}):
 * <pre>
 * approved=true
 * last_check=2026-02-14T12:00:00Z
 * uuid=abc-123-def-456
 * </pre>
 *
 * @see ApprovalConfig
 */
public class ApprovalService {

    /** Cache file for approval status. */
    public static final String STATUS_FILENAME = "approval-status";

    /** How often to re-check approval (24 hours). */
    public static final Duration REFRESH_INTERVAL = Duration.ofHours(24);

    /** UUID regex pattern for extracting UUIDs from Slack messages. */
    static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            Pattern.CASE_INSENSITIVE
    );

    private final ApprovalConfig config;
    private final Path statusPath;

    // Cached state
    private Boolean approved;
    private Instant lastCheck;
    private boolean welcomeShown;

    /**
     * Creates an ApprovalService with the given configuration.
     */
    public ApprovalService(ApprovalConfig config, Path homeDir) {
        this.config = config;
        this.statusPath = homeDir.resolve(ClientUUID.GLOBAL_DIR).resolve(STATUS_FILENAME);
        this.approved = null;
        this.lastCheck = null;
        this.welcomeShown = false;
        loadCache();
    }

    /**
     * Creates an ApprovalService from the global configuration.
     */
    public static ApprovalService create() {
        ApprovalConfig config = ApprovalConfig.load();
        Path homeDir = Path.of(System.getProperty("user.home"));
        return new ApprovalService(config, homeDir);
    }

    /**
     * Creates an ApprovalService with a custom home directory (for testing).
     */
    public static ApprovalService create(Path homeDir) {
        ApprovalConfig config = ApprovalConfig.load(ApprovalConfig.getConfigPath(homeDir));
        return new ApprovalService(config, homeDir);
    }

    /**
     * Checks whether the given UUID is approved for the pilot program.
     *
     * <p>Uses cached status if available and fresh (less than 24 hours old).
     * If the cache is stale or missing, attempts a live check against the
     * Slack channel. If the live check fails (network, config, etc.),
     * falls back to the cached status or returns false.
     *
     * @param uuid the client UUID to check
     * @return true if the UUID is approved
     */
    public boolean isApproved(String uuid) {
        // If we have a fresh cache, use it
        if (approved != null && !shouldRefresh()) {
            return approved;
        }

        // Attempt refresh
        if (config.isConfigured()) {
            try {
                refreshApprovalStatus(uuid);
            } catch (Exception e) {
                // Refresh failed -- fall back to cached value
            }
        }

        return approved != null ? approved : false;
    }

    /**
     * Returns true if the approval status cache is stale (older than 24 hours)
     * or has never been checked.
     */
    public boolean shouldRefresh() {
        if (lastCheck == null) return true;
        return Duration.between(lastCheck, Instant.now()).compareTo(REFRESH_INTERVAL) > 0;
    }

    /**
     * Refreshes the approval status by reading the Slack approval channel.
     *
     * <p>Reads the last 200 messages in the approval channel, extracts all UUIDs
     * from the message text, and checks whether the given UUID is present.
     *
     * @param uuid the client UUID to look for
     * @throws IOException if the Slack API call fails
     */
    public void refreshApprovalStatus(String uuid) throws IOException {
        if (!config.isConfigured()) {
            throw new IOException("Approval system not configured (missing bot token or channel ID)");
        }

        try {
            Set<String> approvedUuids = fetchApprovedUUIDs();
            this.approved = approvedUuids.contains(uuid.toLowerCase());
            this.lastCheck = Instant.now();
            saveCache(uuid);
        } catch (Exception e) {
            throw new IOException("Failed to check approval status: " + e.getMessage(), e);
        }
    }

    /**
     * Fetches the set of approved UUIDs from the Slack approval channel.
     *
     * <p>Reads up to 200 messages from the channel and extracts all UUID patterns
     * from the message text. UUIDs are normalized to lowercase.
     *
     * @return set of approved UUID strings (lowercase)
     */
    public Set<String> fetchApprovedUUIDs() throws IOException {
        Set<String> uuids = new HashSet<>();

        try (Slack slack = Slack.getInstance()) {
            MethodsClient methods = slack.methods(config.getSlackBotToken());

            ConversationsHistoryResponse response = methods.conversationsHistory(r -> r
                    .channel(config.getApprovalChannelId())
                    .limit(200)
            );

            if (response.isOk() && response.getMessages() != null) {
                for (Message message : response.getMessages()) {
                    String text = message.getText();
                    if (text != null) {
                        Matcher matcher = UUID_PATTERN.matcher(text);
                        while (matcher.find()) {
                            uuids.add(matcher.group().toLowerCase());
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("Slack API error: " + e.getMessage(), e);
        }

        return uuids;
    }

    /**
     * Returns whether this is a newly-approved installation that should see a welcome message.
     * Returns true only once per approval (resets after the first call that returns true).
     */
    public boolean shouldShowWelcome() {
        if (approved != null && approved && !welcomeShown) {
            welcomeShown = true;
            return true;
        }
        return false;
    }

    /**
     * Returns the cached approval status (may be null if never checked).
     */
    public Boolean getCachedApproval() {
        return approved;
    }

    /**
     * Returns the timestamp of the last approval check (may be null).
     */
    public Instant getLastCheck() {
        return lastCheck;
    }

    /**
     * Returns the path to the approval status cache file.
     */
    public Path getStatusPath() {
        return statusPath;
    }

    // --- Cache persistence ---

    /**
     * Loads the cached approval status from disk.
     */
    void loadCache() {
        if (!Files.exists(statusPath)) {
            return;
        }

        try {
            Properties props = new Properties();
            props.load(Files.newBufferedReader(statusPath));

            String approvedStr = props.getProperty("approved");
            if (approvedStr != null) {
                this.approved = Boolean.parseBoolean(approvedStr);
            }

            String lastCheckStr = props.getProperty("last_check");
            if (lastCheckStr != null && !lastCheckStr.isBlank()) {
                this.lastCheck = Instant.parse(lastCheckStr);
            }
        } catch (Exception e) {
            // Cache is corrupt -- start fresh
            this.approved = null;
            this.lastCheck = null;
        }
    }

    /**
     * Saves the approval status to the cache file.
     */
    void saveCache(String uuid) {
        try {
            Files.createDirectories(statusPath.getParent());
            String content = """
                    # Synthesis Pilot Approval Status (cached)
                    # Automatically refreshed every 24 hours
                    approved=%s
                    last_check=%s
                    uuid=%s
                    """.formatted(
                    approved != null ? approved : "false",
                    lastCheck != null ? lastCheck.toString() : "",
                    uuid
            );
            Files.writeString(statusPath, content);
        } catch (IOException e) {
            // Silently ignore -- cache failures are not critical
        }
    }

    /**
     * Extracts all UUIDs from a text string.
     * Useful for testing and for processing channel messages.
     *
     * @param text the text to scan for UUIDs
     * @return set of UUIDs found (lowercase)
     */
    public static Set<String> extractUUIDs(String text) {
        Set<String> uuids = new HashSet<>();
        if (text == null) return uuids;

        Matcher matcher = UUID_PATTERN.matcher(text);
        while (matcher.find()) {
            uuids.add(matcher.group().toLowerCase());
        }
        return uuids;
    }
}
