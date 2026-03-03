package io.exoreaction.synthesis.sessions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Scans {@code ~/.claude/projects/} for Claude Code session JSONL files and
 * indexes them into {@link SessionStore}.
 *
 * <h2>JSONL format</h2>
 * Each line in a session file is a JSON object. Relevant fields:
 * <ul>
 *   <li>{@code type="file-history-snapshot"} — skip</li>
 *   <li>{@code type="user"} — extract {@code sessionId}, {@code cwd}, {@code timestamp},
 *       {@code message.content} (string or array)</li>
 *   <li>{@code type="assistant"} — scan {@code message.content} array for
 *       {@code {type:"tool_use", name:"..."}} entries</li>
 * </ul>
 *
 * <h2>Incremental scanning</h2>
 * Files whose {@code lastModified} epoch-second is &lt;= the stored {@code scanned_at}
 * are skipped, avoiding redundant work on large session archives.
 */
public class ClaudeSessionScanner {

    private static final Logger LOG = Logger.getLogger(ClaudeSessionScanner.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path claudeProjectsDir;
    private final SessionStore store;

    /**
     * Creates a scanner that reads from {@code ~/.claude/projects/}.
     */
    public ClaudeSessionScanner(SessionStore store) {
        this(store, Path.of(System.getProperty("user.home"), ".claude", "projects"));
    }

    /**
     * Creates a scanner with an explicit projects directory (for testing).
     */
    public ClaudeSessionScanner(SessionStore store, Path claudeProjectsDir) {
        this.store = store;
        this.claudeProjectsDir = claudeProjectsDir;
    }

    /**
     * Scans all JSONL files under the projects directory and upserts them.
     *
     * @return the number of sessions processed (new or updated)
     */
    public int scan() throws SQLException, IOException {
        if (!Files.exists(claudeProjectsDir)) {
            LOG.info("Claude projects directory not found: " + claudeProjectsDir);
            return 0;
        }

        Map<String, Long> known = store.getKnownSessions();
        int processed = 0;

        try (Stream<Path> files = Files.walk(claudeProjectsDir)) {
            List<Path> jsonlFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .toList();

            for (Path file : jsonlFiles) {
                try {
                    // Extract session UUID from filename (without .jsonl extension)
                    String filename = file.getFileName().toString();
                    String sessionId = filename.substring(0, filename.length() - 6); // strip .jsonl

                    // Skip non-UUID filenames (safety check)
                    if (sessionId.isBlank() || sessionId.contains(" ")) {
                        continue;
                    }

                    // Incremental: skip if file hasn't changed since last scan
                    long lastModified = Files.getLastModifiedTime(file).toInstant().getEpochSecond();
                    Long prevScanned = known.get(sessionId);
                    if (prevScanned != null && prevScanned >= lastModified) {
                        continue; // unchanged
                    }

                    ClaudeSession session = parseFile(file, sessionId);
                    if (session != null) {
                        store.upsert(session);
                        processed++;
                    }
                } catch (Exception e) {
                    LOG.fine("Skipping " + file + ": " + e.getMessage());
                }
            }
        }

        return processed;
    }

    /**
     * Parses a single JSONL file into a {@link ClaudeSession}.
     *
     * @return a session record, or {@code null} if the file has no user messages
     */
    ClaudeSession parseFile(Path file, String sessionId) throws IOException {
        String projectDir = null;
        Instant startedAt = null;
        Instant endedAt = null;
        int turnCount = 0;
        int toolCallCount = 0;
        Set<String> toolNames = new LinkedHashSet<>();
        String firstMessage = null;
        StringBuilder allUserText = new StringBuilder();

        try (Stream<String> lines = Files.lines(file)) {
            for (String line : (Iterable<String>) lines::iterator) {
                line = line.strip();
                if (line.isEmpty()) continue;

                JsonNode event;
                try {
                    event = MAPPER.readTree(line);
                } catch (Exception e) {
                    LOG.fine("Unparseable line in " + file + ": " + e.getMessage());
                    continue;
                }

                String type = event.path("type").asText("");

                if ("file-history-snapshot".equals(type)) {
                    continue; // first line — skip
                }

                if ("user".equals(type)) {
                    // Extract metadata from first user message
                    if (projectDir == null) {
                        projectDir = event.path("cwd").asText(null);
                        if (projectDir == null || projectDir.isBlank()) {
                            projectDir = file.getParent().getFileName().toString();
                        }
                    }

                    Instant ts = parseTimestamp(event.path("timestamp").asText(null));
                    if (ts != null) {
                        if (startedAt == null) startedAt = ts;
                        endedAt = ts;
                    }

                    // Extract text content
                    String text = extractUserText(event.path("message").path("content"));
                    if (text != null && !text.isBlank()) {
                        if (firstMessage == null) {
                            firstMessage = truncate(text, 500);
                        }
                        if (allUserText.length() > 0) allUserText.append(' ');
                        allUserText.append(truncate(text, 1000));
                        turnCount++;
                    }
                } else if ("assistant".equals(type)) {
                    // Scan content array for tool_use entries
                    JsonNode content = event.path("message").path("content");
                    if (content.isArray()) {
                        for (JsonNode item : content) {
                            if ("tool_use".equals(item.path("type").asText(""))) {
                                String toolName = item.path("name").asText(null);
                                if (toolName != null && !toolName.isBlank()) {
                                    toolNames.add(toolName);
                                    toolCallCount++;
                                }
                            }
                        }

                        // Also track timestamp from assistant messages for ended_at
                        Instant ts = parseTimestamp(event.path("timestamp").asText(null));
                        if (ts != null && (endedAt == null || ts.isAfter(endedAt))) {
                            endedAt = ts;
                        }
                    }
                }
            }
        }

        if (startedAt == null || projectDir == null) {
            return null; // empty or unparseable session
        }

        return new ClaudeSession(
                sessionId,
                projectDir,
                startedAt,
                endedAt,
                turnCount,
                toolCallCount,
                new ArrayList<>(toolNames),
                firstMessage,
                allUserText.toString().strip()
        );
    }

    /**
     * Extracts plain text from a message content node.
     * Content can be a plain string OR a JSON array of typed blocks.
     */
    private String extractUserText(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode()) return null;

        if (contentNode.isTextual()) {
            return contentNode.asText();
        }

        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : contentNode) {
                String blockType = block.path("type").asText("");
                if ("text".equals(blockType)) {
                    String text = block.path("text").asText(null);
                    if (text != null && !text.isBlank()) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(text.strip());
                    }
                }
                // skip tool_result, image, etc.
            }
            return sb.toString();
        }

        return null;
    }

    /**
     * Parses an ISO 8601 timestamp string to an Instant.
     * Returns null if unparseable.
     */
    private Instant parseTimestamp(String ts) {
        if (ts == null || ts.isBlank()) return null;
        try {
            return Instant.parse(ts);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
