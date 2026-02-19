package io.exoreaction.synthesis.changelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ActivityLogUpdaterTest {

    private final ActivityLogUpdater updater = new ActivityLogUpdater();

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    // --- findOrCreate ---

    @Test
    void findOrCreate_returnsExistingFile(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("ACTIVITY-LOG.md");
        Files.writeString(log, "# Activity Log\n\nexisting content\n");

        Path result = updater.findOrCreate(tmp);

        assertEquals(log, result);
        assertTrue(Files.readString(result).contains("existing content"),
                "Existing content must be preserved");
    }

    @Test
    void findOrCreate_createsFileWhenMissing(@TempDir Path tmp) throws IOException {
        Path result = updater.findOrCreate(tmp);

        assertTrue(Files.exists(result));
        String content = Files.readString(result);
        assertTrue(content.startsWith("# Activity Log"),
                "Created file must begin with '# Activity Log'");
    }

    // --- hasEntryForToday ---

    @Test
    void hasEntryForToday_returnsTrueWhenPresent(@TempDir Path tmp) throws IOException {
        String todayHeader = "## " + DATE_FMT.format(LocalDate.now());
        Path log = tmp.resolve("ACTIVITY-LOG.md");
        Files.writeString(log, "# Activity Log\n\n" + todayHeader + "\n\nSome entry.\n");

        assertTrue(updater.hasEntryForToday(log));
    }

    @Test
    void hasEntryForToday_returnsFalseWhenAbsent(@TempDir Path tmp) throws IOException {
        String yesterdayHeader = "## " + DATE_FMT.format(LocalDate.now().minusDays(1));
        Path log = tmp.resolve("ACTIVITY-LOG.md");
        Files.writeString(log, "# Activity Log\n\n" + yesterdayHeader + "\n\nOld entry.\n");

        assertFalse(updater.hasEntryForToday(log));
    }

    @Test
    void hasEntryForToday_returnsFalseForEmptyFile(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("ACTIVITY-LOG.md");
        Files.writeString(log, "");

        assertFalse(updater.hasEntryForToday(log));
    }

    // --- buildEntry ---

    @Test
    void buildEntry_groupsEventsByType() {
        List<ChangeEvent> events = List.of(
                event(ChangeEvent.ChangeType.ADDED, "src/Foo.java"),
                event(ChangeEvent.ChangeType.MODIFIED, "docs/guide.md"),
                event(ChangeEvent.ChangeType.DELETED, "tmp/scratch.txt")
        );

        String entry = updater.buildEntry(events, "my-workspace", Optional.empty());

        assertTrue(entry.contains("### Added"), "Should have Added section");
        assertTrue(entry.contains("src/Foo.java"));
        assertTrue(entry.contains("### Modified"), "Should have Modified section");
        assertTrue(entry.contains("docs/guide.md"));
        assertTrue(entry.contains("### Removed"), "Should have Removed section");
        assertTrue(entry.contains("tmp/scratch.txt"));
    }

    @Test
    void buildEntry_omitsEmptySection() {
        List<ChangeEvent> events = List.of(
                event(ChangeEvent.ChangeType.ADDED, "src/NewFeature.java")
        );

        String entry = updater.buildEntry(events, "my-workspace", Optional.empty());

        assertTrue(entry.contains("### Added"));
        assertFalse(entry.contains("### Modified"), "Modified section must be absent");
        assertFalse(entry.contains("### Removed"), "Removed section must be absent");
    }

    @Test
    void buildEntry_noAiClient_omitsNarrative() {
        List<ChangeEvent> events = List.of(
                event(ChangeEvent.ChangeType.ADDED, "src/Foo.java")
        );

        String entry = updater.buildEntry(events, "my-workspace", Optional.empty());

        // The entry should contain the date header and at least one section
        assertTrue(entry.contains("## " + DATE_FMT.format(LocalDate.now())));
        // No AI paragraph means no extra prose beyond the structured sections;
        // we just verify no exception and a valid structure
        assertTrue(entry.contains("<!-- synthesis:auto-generated"));
    }

    @Test
    void buildEntry_containsTodayDateHeader() {
        List<ChangeEvent> events = List.of(
                event(ChangeEvent.ChangeType.MODIFIED, "README.md")
        );

        String entry = updater.buildEntry(events, "workspace", Optional.empty());

        String expectedHeader = "## " + DATE_FMT.format(LocalDate.now());
        assertTrue(entry.startsWith(expectedHeader),
                "Entry must start with today's date header");
    }

    // --- appendEntry ---

    @Test
    void appendEntry_insertsAfterFirstHeading(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("ACTIVITY-LOG.md");
        String existing = "# Activity Log\n\n## March 1, 2026\nOld entry.\n";
        Files.writeString(log, existing);

        updater.appendEntry(log, "## February 19, 2026\nNew entry.\n");

        String content = Files.readString(log);
        // New entry should appear before the old one
        int newIdx = content.indexOf("February 19, 2026");
        int oldIdx = content.indexOf("March 1, 2026");
        assertTrue(newIdx < oldIdx, "New entry must appear before the old entry");
        // Heading must still be first
        assertTrue(content.startsWith("# Activity Log"), "Heading must remain at top");
    }

    @Test
    void appendEntry_worksOnNewFile(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("ACTIVITY-LOG.md");
        Files.writeString(log, "# Activity Log\n\n");

        updater.appendEntry(log, "## February 19, 2026\nFirst entry.\n");

        String content = Files.readString(log);
        assertTrue(content.contains("First entry."));
        assertTrue(content.startsWith("# Activity Log"));
    }

    // --- update ---

    @Test
    void update_skipsWhenAlreadyHasTodayEntry(@TempDir Path tmp) throws IOException {
        String todayHeader = "## " + DATE_FMT.format(LocalDate.now());
        Path log = tmp.resolve("ACTIVITY-LOG.md");
        Files.writeString(log, "# Activity Log\n\n" + todayHeader + "\nExisting.\n");

        List<ChangeEvent> events = List.of(event(ChangeEvent.ChangeType.ADDED, "new.md"));

        boolean written = updater.update(tmp, events, "workspace", Optional.empty());

        assertFalse(written, "Should return false when today's entry already exists");
        // File must be unchanged
        assertTrue(Files.readString(log).contains("Existing."));
    }

    @Test
    void update_skipsWhenNoEvents(@TempDir Path tmp) throws IOException {
        boolean written = updater.update(tmp, List.of(), "workspace", Optional.empty());

        assertFalse(written, "Should return false when event list is empty");
        // ACTIVITY-LOG.md must not have been created
        assertFalse(Files.exists(tmp.resolve("ACTIVITY-LOG.md")));
    }

    @Test
    void update_writesEntryWhenEventsPresent(@TempDir Path tmp) throws IOException {
        List<ChangeEvent> events = List.of(
                event(ChangeEvent.ChangeType.ADDED, "src/NewClass.java"),
                event(ChangeEvent.ChangeType.MODIFIED, "README.md")
        );

        boolean written = updater.update(tmp, events, "my-workspace", Optional.empty());

        assertTrue(written, "Should return true when events are present and no today entry exists");
        Path log = tmp.resolve("ACTIVITY-LOG.md");
        assertTrue(Files.exists(log));
        String content = Files.readString(log);
        assertTrue(content.contains("## " + DATE_FMT.format(LocalDate.now())));
        assertTrue(content.contains("src/NewClass.java"));
        assertTrue(content.contains("README.md"));
    }

    // --- Helper ---

    private ChangeEvent event(ChangeEvent.ChangeType type, String path) {
        return new ChangeEvent(0, "/workspace", Instant.now(), 1, 2,
                type, path, null, null, 0, null, ChangeSignificance.NORMAL);
    }
}
