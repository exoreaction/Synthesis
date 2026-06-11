package io.exoreaction.synthesis.changelog;

import io.exoreaction.synthesis.ai.AiClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Auto-generates and appends draft activity log entries to ACTIVITY-LOG.md.
 *
 * <p>Reads change events from the SnapshotManager and produces a structured
 * Markdown entry grouped by change type (Added, Modified, Moved, Removed).
 * If an AiClient is available, appends an AI-generated narrative paragraph.
 *
 * <p>Entry insertion is newest-first: each new entry is placed immediately
 * after the top-level heading line.
 */
public class ActivityLogUpdater {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    /**
     * Locates or creates ACTIVITY-LOG.md at the workspace root.
     *
     * @param workspaceRoot path to the workspace directory
     * @return path to the log file (existing or newly created)
     * @throws IOException if the file cannot be created
     */
    public Path findOrCreate(Path workspaceRoot) throws IOException {
        Path log = workspaceRoot.resolve("ACTIVITY-LOG.md");
        if (!Files.exists(log)) {
            Files.writeString(log, "# Activity Log\n\n");
        }
        return log;
    }

    /**
     * Returns true if the log file already contains an entry for today.
     *
     * @param logFile path to the activity log
     * @return true if a {@code ## <today's date>} header is present
     * @throws IOException if the file cannot be read
     */
    public boolean hasEntryForToday(Path logFile) throws IOException {
        String todayHeader = "## " + DATE_FMT.format(LocalDate.now());
        return Files.readString(logFile).contains(todayHeader);
    }

    /**
     * Builds the Markdown entry string from a list of change events.
     *
     * <p>Groups events by {@link ChangeEvent.ChangeType}: Added, Modified,
     * Moved, Removed. Omits empty sections. If {@code aiClient} is present,
     * appends a brief AI-generated narrative paragraph.
     *
     * @param events        change events for the entry
     * @param workspaceName display name of the workspace
     * @param aiClient      optional Claude client for AI narrative
     * @return fully-formatted Markdown entry string (without trailing newline)
     */
    public String buildEntry(List<ChangeEvent> events, String workspaceName,
                             Optional<AiClient> aiClient) {
        StringBuilder sb = new StringBuilder();

        String today = DATE_FMT.format(LocalDate.now());
        sb.append("## ").append(today).append("\n");
        sb.append("<!-- synthesis:auto-generated — review and edit -->\n");

        // Group by type
        List<String> added = events.stream()
                .filter(e -> e.changeType() == ChangeEvent.ChangeType.ADDED)
                .map(ChangeEvent::relativePath)
                .sorted()
                .collect(Collectors.toList());

        List<String> modified = events.stream()
                .filter(e -> e.changeType() == ChangeEvent.ChangeType.MODIFIED)
                .map(ChangeEvent::relativePath)
                .sorted()
                .collect(Collectors.toList());

        List<String> moved = events.stream()
                .filter(e -> e.changeType() == ChangeEvent.ChangeType.MOVED)
                .map(ChangeEvent::relativePath)
                .sorted()
                .collect(Collectors.toList());

        List<String> deleted = events.stream()
                .filter(e -> e.changeType() == ChangeEvent.ChangeType.DELETED)
                .map(ChangeEvent::relativePath)
                .sorted()
                .collect(Collectors.toList());

        if (!added.isEmpty()) {
            sb.append("\n### Added\n");
            for (String path : added) {
                sb.append("- ").append(path).append("\n");
            }
        }

        if (!modified.isEmpty()) {
            sb.append("\n### Modified\n");
            for (String path : modified) {
                sb.append("- ").append(path).append("\n");
            }
        }

        if (!moved.isEmpty()) {
            sb.append("\n### Moved\n");
            for (String path : moved) {
                sb.append("- ").append(path).append("\n");
            }
        }

        if (!deleted.isEmpty()) {
            sb.append("\n### Removed\n");
            for (String path : deleted) {
                sb.append("- ").append(path).append("\n");
            }
        }

        if (aiClient.isPresent()) {
            String narrative = generateNarrative(events, workspaceName, today, aiClient.get());
            if (narrative != null && !narrative.isBlank()) {
                sb.append("\n").append(narrative.strip()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Inserts the entry immediately after the first heading line (newest-first order).
     *
     * @param logFile path to the activity log
     * @param entry   the entry string to insert
     * @throws IOException if the file cannot be read or written
     */
    public void appendEntry(Path logFile, String entry) throws IOException {
        String existing = Files.readString(logFile);
        int insertAt = existing.indexOf('\n') + 1; // after the first line
        if (insertAt == 0) {
            // No newline found — prepend before everything
            Files.writeString(logFile, entry + "\n" + existing);
            return;
        }
        String updated = existing.substring(0, insertAt)
                + "\n" + entry + "\n"
                + existing.substring(insertAt);
        Files.writeString(logFile, updated);
    }

    /**
     * Main entry point called from MaintainCommand.
     *
     * <p>Skips writing if there are no events or today's entry already exists.
     *
     * @param workspaceRoot workspace root path
     * @param events        change events since the last scan
     * @param workspaceName display name of the workspace
     * @param aiClient      optional Claude client for AI narrative
     * @return true if an entry was written, false if skipped
     * @throws IOException if the log file cannot be read or written
     */
    public boolean update(Path workspaceRoot, List<ChangeEvent> events,
                          String workspaceName,
                          Optional<AiClient> aiClient) throws IOException {
        if (events == null || events.isEmpty()) {
            return false;
        }
        Path logFile = findOrCreate(workspaceRoot);
        if (hasEntryForToday(logFile)) {
            return false;
        }
        String entry = buildEntry(events, workspaceName, aiClient);
        appendEntry(logFile, entry);
        return true;
    }

    // --- Private helpers ---

    private String generateNarrative(List<ChangeEvent> events, String workspaceName,
                                     String date, AiClient client) {
        long added = events.stream().filter(e -> e.changeType() == ChangeEvent.ChangeType.ADDED).count();
        long modified = events.stream().filter(e -> e.changeType() == ChangeEvent.ChangeType.MODIFIED).count();
        long deleted = events.stream().filter(e -> e.changeType() == ChangeEvent.ChangeType.DELETED).count();
        long moved = events.stream().filter(e -> e.changeType() == ChangeEvent.ChangeType.MOVED).count();

        // Sample key files for the prompt (up to 5)
        String keyFiles = events.stream()
                .map(ChangeEvent::relativePath)
                .limit(5)
                .collect(Collectors.joining(", "));

        String prompt = "Write a single concise paragraph (2-3 sentences, no headers or bullet points) "
                + "summarizing the development activity in the '" + workspaceName + "' workspace on " + date + ". "
                + "Changes: " + added + " added, " + modified + " modified, "
                + deleted + " deleted, " + moved + " moved. "
                + "Key files: " + keyFiles + ". "
                + "Focus on the impact and significance of these changes for the team.";

        try {
            return client.generate(prompt, 200);
        } catch (Exception e) {
            return null;
        }
    }
}
