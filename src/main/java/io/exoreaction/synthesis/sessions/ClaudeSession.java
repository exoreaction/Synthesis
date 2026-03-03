package io.exoreaction.synthesis.sessions;

import java.time.Instant;
import java.util.List;

/**
 * Immutable record representing an indexed Claude Code session.
 *
 * <p>Each session corresponds to a single {@code .jsonl} file found under
 * {@code ~/.claude/projects/}. The scanner extracts user messages and tool
 * calls from the JSONL events and stores the aggregated data here.
 */
public record ClaudeSession(
        String sessionId,
        String projectDir,
        Instant startedAt,
        Instant endedAt,       // null if only one message
        int turnCount,
        int toolCallCount,
        List<String> toolNames,
        String firstMessage,
        String allUserText
) {
    /**
     * Compact representation for list views.
     */
    public String toSummaryLine() {
        String ts = startedAt != null
                ? startedAt.toString().substring(0, 16).replace("T", " ")
                : "unknown";
        String project = projectDir != null
                ? abbreviate(projectDir, 40)
                : "(unknown)";
        String msg = firstMessage != null
                ? abbreviate(firstMessage, 60)
                : "";
        return String.format("  [%s] %-40s  turns=%-3d  tools=%-2d  %s",
                ts, project, turnCount, toolCallCount, msg);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
