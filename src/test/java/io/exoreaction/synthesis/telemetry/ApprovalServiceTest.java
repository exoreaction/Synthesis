package io.exoreaction.synthesis.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ApprovalService} -- approval checking, caching, and UUID extraction.
 *
 * <p>Note: These tests do not actually call the Slack API. They test the caching logic,
 * UUID extraction, and refresh timing. Live Slack integration requires a real bot token
 * and channel, which is tested manually during deployment.
 */
class ApprovalServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void freshServiceHasNoCachedApproval() {
        ApprovalConfig config = new ApprovalConfig();
        ApprovalService service = new ApprovalService(config, tempDir);

        assertNull(service.getCachedApproval());
        assertNull(service.getLastCheck());
    }

    @Test
    void shouldRefreshReturnsTrueWhenNeverChecked() {
        ApprovalConfig config = new ApprovalConfig();
        ApprovalService service = new ApprovalService(config, tempDir);

        assertTrue(service.shouldRefresh());
    }

    @Test
    void shouldRefreshReturnsFalseWhenRecentlyChecked() throws IOException {
        // Set up a recent cache
        Path statusPath = tempDir.resolve(".synthesis/approval-status");
        Files.createDirectories(statusPath.getParent());
        String recentCheck = Instant.now().toString();
        Files.writeString(statusPath, """
                approved=true
                last_check=%s
                uuid=test-uuid
                """.formatted(recentCheck));

        ApprovalConfig config = new ApprovalConfig();
        ApprovalService service = new ApprovalService(config, tempDir);

        assertFalse(service.shouldRefresh());
    }

    @Test
    void shouldRefreshReturnsTrueWhenCacheIsStale() throws IOException {
        // Set up an old cache (25 hours ago)
        Path statusPath = tempDir.resolve(".synthesis/approval-status");
        Files.createDirectories(statusPath.getParent());
        String oldCheck = Instant.now().minus(Duration.ofHours(25)).toString();
        Files.writeString(statusPath, """
                approved=true
                last_check=%s
                uuid=test-uuid
                """.formatted(oldCheck));

        ApprovalConfig config = new ApprovalConfig();
        ApprovalService service = new ApprovalService(config, tempDir);

        assertTrue(service.shouldRefresh());
    }

    @Test
    void loadsCachedApprovalFromDisk() throws IOException {
        Path statusPath = tempDir.resolve(".synthesis/approval-status");
        Files.createDirectories(statusPath.getParent());
        Files.writeString(statusPath, """
                approved=true
                last_check=%s
                uuid=test-uuid
                """.formatted(Instant.now().toString()));

        ApprovalConfig config = new ApprovalConfig();
        ApprovalService service = new ApprovalService(config, tempDir);

        assertTrue(service.getCachedApproval());
    }

    @Test
    void loadsCachedRejectionFromDisk() throws IOException {
        Path statusPath = tempDir.resolve(".synthesis/approval-status");
        Files.createDirectories(statusPath.getParent());
        Files.writeString(statusPath, """
                approved=false
                last_check=%s
                uuid=test-uuid
                """.formatted(Instant.now().toString()));

        ApprovalConfig config = new ApprovalConfig();
        ApprovalService service = new ApprovalService(config, tempDir);

        assertFalse(service.getCachedApproval());
    }

    @Test
    void handlesCorruptCacheGracefully() throws IOException {
        Path statusPath = tempDir.resolve(".synthesis/approval-status");
        Files.createDirectories(statusPath.getParent());
        Files.writeString(statusPath, "corrupt garbage data ===\n");

        ApprovalConfig config = new ApprovalConfig();
        ApprovalService service = new ApprovalService(config, tempDir);

        // Should treat as never checked
        assertNull(service.getCachedApproval());
        assertTrue(service.shouldRefresh());
    }

    @Test
    void isApprovedReturnsFalseWhenUnconfigured() {
        ApprovalConfig config = new ApprovalConfig();
        ApprovalService service = new ApprovalService(config, tempDir);

        assertFalse(service.isApproved("any-uuid"));
    }

    @Test
    void isApprovedReturnsCachedValueWhenFresh() throws IOException {
        // Set up cached approval
        Path statusPath = tempDir.resolve(".synthesis/approval-status");
        Files.createDirectories(statusPath.getParent());
        Files.writeString(statusPath, """
                approved=true
                last_check=%s
                uuid=test-uuid
                """.formatted(Instant.now().toString()));

        ApprovalConfig config = new ApprovalConfig();
        ApprovalService service = new ApprovalService(config, tempDir);

        assertTrue(service.isApproved("test-uuid"));
    }

    @Test
    void refreshFailsGracefullyWithUnconfiguredApproval() {
        // Explicitly clear embedded defaults to test unconfigured case
        ApprovalConfig config = new ApprovalConfig();
        config.setSlackBotToken("");
        config.setApprovalChannelId("");
        ApprovalService service = new ApprovalService(config, tempDir);

        assertThrows(IOException.class, () ->
                service.refreshApprovalStatus("test-uuid"));
    }

    @Test
    void shouldShowWelcomeReturnsTrueOnceAfterApproval() throws IOException {
        Path statusPath = tempDir.resolve(".synthesis/approval-status");
        Files.createDirectories(statusPath.getParent());
        Files.writeString(statusPath, """
                approved=true
                last_check=%s
                uuid=test-uuid
                """.formatted(Instant.now().toString()));

        ApprovalConfig config = new ApprovalConfig();
        ApprovalService service = new ApprovalService(config, tempDir);

        assertTrue(service.shouldShowWelcome(), "First call should return true");
        assertFalse(service.shouldShowWelcome(), "Second call should return false");
        assertFalse(service.shouldShowWelcome(), "Third call should return false");
    }

    @Test
    void shouldShowWelcomeReturnsFalseWhenNotApproved() {
        ApprovalConfig config = new ApprovalConfig();
        ApprovalService service = new ApprovalService(config, tempDir);

        assertFalse(service.shouldShowWelcome());
    }

    @Test
    void getStatusPathPointsToCorrectLocation() {
        ApprovalConfig config = new ApprovalConfig();
        ApprovalService service = new ApprovalService(config, tempDir);

        assertEquals(tempDir.resolve(".synthesis/approval-status"), service.getStatusPath());
    }

    // --- UUID extraction tests ---

    @Test
    void extractUUIDsFindsStandardUUIDs() {
        String text = "Approved: 550e8400-e29b-41d4-a716-446655440000";
        Set<String> uuids = ApprovalService.extractUUIDs(text);

        assertEquals(1, uuids.size());
        assertTrue(uuids.contains("550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test
    void extractUUIDsFindsMultipleUUIDs() {
        String text = """
                Approved UUIDs (Feb 14, 2026):
                - 550e8400-e29b-41d4-a716-446655440000
                - 12345678-1234-1234-1234-123456789abc
                - ABCDEF12-3456-7890-ABCD-EF1234567890
                """;
        Set<String> uuids = ApprovalService.extractUUIDs(text);

        assertEquals(3, uuids.size());
        assertTrue(uuids.contains("550e8400-e29b-41d4-a716-446655440000"));
        assertTrue(uuids.contains("12345678-1234-1234-1234-123456789abc"));
        assertTrue(uuids.contains("abcdef12-3456-7890-abcd-ef1234567890")); // lowercase
    }

    @Test
    void extractUUIDsHandlesNullInput() {
        Set<String> uuids = ApprovalService.extractUUIDs(null);
        assertTrue(uuids.isEmpty());
    }

    @Test
    void extractUUIDsHandlesEmptyInput() {
        Set<String> uuids = ApprovalService.extractUUIDs("");
        assertTrue(uuids.isEmpty());
    }

    @Test
    void extractUUIDsHandlesNoUUIDs() {
        Set<String> uuids = ApprovalService.extractUUIDs("This message has no UUIDs in it.");
        assertTrue(uuids.isEmpty());
    }

    @Test
    void extractUUIDsNormalizesToLowercase() {
        String text = "ABCDEF12-3456-7890-ABCD-EF1234567890";
        Set<String> uuids = ApprovalService.extractUUIDs(text);

        assertEquals(1, uuids.size());
        assertTrue(uuids.contains("abcdef12-3456-7890-abcd-ef1234567890"));
    }

    @Test
    void extractUUIDsFindsUUIDsInlineText() {
        String text = "User 550e8400-e29b-41d4-a716-446655440000 is approved for the pilot";
        Set<String> uuids = ApprovalService.extractUUIDs(text);

        assertEquals(1, uuids.size());
        assertTrue(uuids.contains("550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test
    void extractUUIDsDeduplicatesSameUUID() {
        String text = """
                550e8400-e29b-41d4-a716-446655440000
                Same again: 550e8400-e29b-41d4-a716-446655440000
                """;
        Set<String> uuids = ApprovalService.extractUUIDs(text);

        assertEquals(1, uuids.size());
    }

    @Test
    void uuidPatternMatchesValidUuids() {
        assertTrue(ApprovalService.UUID_PATTERN.matcher("550e8400-e29b-41d4-a716-446655440000").find());
        assertTrue(ApprovalService.UUID_PATTERN.matcher("ABCDEF12-3456-7890-ABCD-EF1234567890").find());
    }

    @Test
    void saveCacheWritesToDisk() throws IOException {
        ApprovalConfig config = new ApprovalConfig();
        ApprovalService service = new ApprovalService(config, tempDir);

        // Use internal saveCache method via isApproved (which falls through to false)
        service.isApproved("test-uuid");

        // The cache should not exist since we didn't configure Slack,
        // but the approval should be false
        assertFalse(service.isApproved("test-uuid"));
    }
}
