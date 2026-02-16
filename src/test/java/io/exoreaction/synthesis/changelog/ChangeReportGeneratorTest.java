package io.exoreaction.synthesis.changelog;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChangeReportGeneratorTest {

    private final ChangeReportGenerator generator = new ChangeReportGenerator();

    @Test
    void generateReport_includesAllSections() {
        List<ChangeEvent> events = List.of(
                new ChangeEvent(1, "/ws", Instant.now(), 1, 2,
                        ChangeEvent.ChangeType.ADDED, "new-file.md", null,
                        "hash1", 500, "MARKDOWN", ChangeSignificance.NORMAL),
                new ChangeEvent(2, "/ws", Instant.now(), 1, 2,
                        ChangeEvent.ChangeType.MODIFIED, ".env", null,
                        "hash2", 50, "TEXT", ChangeSignificance.CRITICAL),
                new ChangeEvent(3, "/ws", Instant.now(), 1, 2,
                        ChangeEvent.ChangeType.DELETED, "old.txt", null,
                        "hash3", 100, "TEXT", ChangeSignificance.NORMAL)
        );

        String report = generator.generateReport(events,
                Instant.now().minusSeconds(86400), Instant.now(), ChangeSignificance.NORMAL);

        assertTrue(report.contains("# Change Report"));
        assertTrue(report.contains("## Summary"));
        assertTrue(report.contains("## Critical Changes"));
        assertTrue(report.contains(".env"));
        assertTrue(report.contains("## By Workspace"));
        assertTrue(report.contains("total changes detected"));
    }

    @Test
    void generateSummary_formatsCorrectly() {
        List<ChangeEvent> events = List.of(
                new ChangeEvent(1, "/ws", Instant.now(), 1, 2,
                        ChangeEvent.ChangeType.ADDED, "a.txt", null,
                        null, 100, null, ChangeSignificance.NOTABLE),
                new ChangeEvent(2, "/ws", Instant.now(), 1, 2,
                        ChangeEvent.ChangeType.MODIFIED, "b.txt", null,
                        null, 200, null, ChangeSignificance.NORMAL)
        );

        String summary = generator.generateSummary(events);
        assertTrue(summary.contains("2 changes"));
        assertTrue(summary.contains("1 added"));
        assertTrue(summary.contains("1 modified"));
        assertTrue(summary.contains("1 notable"));
    }

    @Test
    void generateReport_handlesEmptyEvents() {
        String report = generator.generateReport(List.of(),
                Instant.now().minusSeconds(86400), Instant.now(), null);

        assertTrue(report.contains("# Change Report"));
        assertTrue(report.contains("total changes detected"));
    }
}
