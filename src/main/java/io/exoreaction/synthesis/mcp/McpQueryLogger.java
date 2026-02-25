package io.exoreaction.synthesis.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Append-only structured query log for the Synthesis MCP server.
 *
 * <p>Each search invocation is recorded as a JSON line in
 * {@code ~/.synthesis/logs/mcp-queries.jsonl}.  The log is intentionally
 * simple: no external dependency, no locking complexity — just
 * {@link StandardOpenOption#APPEND} writes that the OS makes atomic for
 * single-line records on all major filesystems.
 *
 * <p>Log entry format (one JSON object per line):
 * <pre>
 * {"ts":"2026-02-25T12:00:00Z","query":"hazelcast config",
 *  "workspace":"/home/totto/Documents","resultCount":3,
 *  "latencyMs":45,"zeroResult":false}
 * </pre>
 *
 * <p>Use {@link McpQueryLogger#create()} for the default location.
 * Pass {@code null} as the log file to obtain a no-op instance (useful
 * in tests and backward-compatible constructors).
 */
public class McpQueryLogger {

    private static final Logger LOG = Logger.getLogger(McpQueryLogger.class.getName());

    /** Null instance — silently discards all log calls. */
    private static final McpQueryLogger NO_OP = new McpQueryLogger(null);

    private final Path logFile;

    /**
     * Creates a logger that writes to {@code logFile}.
     * Pass {@code null} for a no-op instance.
     */
    public McpQueryLogger(Path logFile) {
        this.logFile = logFile;
        if (logFile != null) {
            try {
                Files.createDirectories(logFile.getParent());
            } catch (IOException e) {
                LOG.warning("Could not create MCP query log directory: " + e.getMessage());
            }
        }
    }

    /**
     * Returns a logger writing to the default location:
     * {@code ~/.synthesis/logs/mcp-queries.jsonl}.
     */
    public static McpQueryLogger create() {
        Path logDir = Path.of(System.getProperty("user.home"), ".synthesis", "logs");
        return new McpQueryLogger(logDir.resolve("mcp-queries.jsonl"));
    }

    /**
     * Returns a no-op logger that discards all entries.
     * Equivalent to {@code new McpQueryLogger(null)}.
     */
    public static McpQueryLogger noOp() {
        return NO_OP;
    }

    /**
     * Appends one log entry.  Never throws — logging failures are swallowed
     * so that they cannot break the MCP search response.
     *
     * @param query       the search query string
     * @param workspace   the workspace path that was searched
     * @param resultCount number of results returned (0 = zero-result query)
     * @param latencyMs   elapsed time in milliseconds
     */
    public void log(String query, String workspace, int resultCount, long latencyMs) {
        if (logFile == null) return;
        try {
            String entry = buildEntry(query, workspace, resultCount, latencyMs);
            Files.writeString(logFile, entry + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.fine("Could not write MCP query log entry: " + e.getMessage());
        }
    }

    /**
     * Builds a JSON log entry without an external JSON library.
     * The values are simple enough that manual escaping is sufficient.
     */
    private String buildEntry(String query, String workspace, int resultCount, long latencyMs) {
        return "{\"ts\":\"" + Instant.now() + "\""
                + ",\"query\":\"" + escapeJson(query) + "\""
                + ",\"workspace\":\"" + escapeJson(workspace) + "\""
                + ",\"resultCount\":" + resultCount
                + ",\"latencyMs\":" + latencyMs
                + ",\"zeroResult\":" + (resultCount == 0)
                + "}";
    }

    /**
     * Minimal JSON string escaping: backslash, double-quote, and control characters.
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
