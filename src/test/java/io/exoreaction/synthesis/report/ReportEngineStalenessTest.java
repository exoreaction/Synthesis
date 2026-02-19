package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the staleness warning in ReportEngine.
 *
 * <p>Verifies that a warning is printed to stderr when ACTIVITY-LOG documents
 * are older than the report period, and that PIPELINE-STATUS documents are not
 * flagged even when stale.
 *
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/81">#81</a>
 */
class ReportEngineStalenessTest {

    @TempDir
    Path tempDir;

    private ReportEngine engine() {
        return new ReportEngine(null, 4000);
    }

    private ReportDocument activityLog(Instant lastModified) {
        Path p = tempDir.resolve("ACTIVITY-LOG.md");
        return new ReportDocument(p, "ACTIVITY-LOG.md", "activity", "# Log", lastModified, 1024L);
    }

    private ReportDocument activityLogUnderscore(Instant lastModified) {
        Path p = tempDir.resolve("ACTIVITY_LOG.md");
        return new ReportDocument(p, "ACTIVITY_LOG.md", "activity", "# Log", lastModified, 1024L);
    }

    private ReportDocument pipelineStatus(Instant lastModified) {
        Path p = tempDir.resolve("PIPELINE-STATUS.md");
        return new ReportDocument(p, "PIPELINE-STATUS.md", "pipeline", "# Status", lastModified, 1024L);
    }

    private String captureStderr(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return buf.toString();
    }

    // ---- warning IS printed when activity log is stale ----

    @Test
    void warnIfStaleAnchorDocs_printsWarning_whenActivityLogOlderThanPeriod() {
        Instant twoWeeksAgo = Instant.now().minus(14, ChronoUnit.DAYS);
        ReportDocument staleLog = activityLog(twoWeeksAgo);

        String stderr = captureStderr(() ->
                engine().warnIfStaleAnchorDocs(List.of(staleLog), "1w"));

        assertTrue(stderr.contains("ACTIVITY-LOG.md"), "Warning should mention the file name");
        assertTrue(stderr.contains("old"), "Warning should describe the age");
        assertTrue(stderr.contains("1w"), "Warning should mention the period");
    }

    @Test
    void warnIfStaleAnchorDocs_printsWarning_activityLogUnderscoreVariant() {
        Instant twoWeeksAgo = Instant.now().minus(14, ChronoUnit.DAYS);
        ReportDocument staleLog = activityLogUnderscore(twoWeeksAgo);

        String stderr = captureStderr(() ->
                engine().warnIfStaleAnchorDocs(List.of(staleLog), "1w"));

        assertTrue(stderr.contains("ACTIVITY_LOG.md"),
                "Warning should also trigger for ACTIVITY_LOG (underscore) variant");
    }

    // ---- no warning when activity log is fresh ----

    @Test
    void warnIfStaleAnchorDocs_noWarning_whenActivityLogWithinPeriod() {
        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        ReportDocument freshLog = activityLog(yesterday);

        String stderr = captureStderr(() ->
                engine().warnIfStaleAnchorDocs(List.of(freshLog), "1w"));

        assertTrue(stderr.isBlank(),
                "No warning should appear when activity log was modified within the period");
    }

    @Test
    void warnIfStaleAnchorDocs_noWarning_emptyDocumentList() {
        String stderr = captureStderr(() ->
                engine().warnIfStaleAnchorDocs(List.of(), "1w"));
        assertTrue(stderr.isBlank(), "No warning for empty document list");
    }

    // ---- PIPELINE-STATUS does NOT trigger warning ----

    @Test
    void warnIfStaleAnchorDocs_noWarning_forStalePipelineStatus() {
        Instant twoWeeksAgo = Instant.now().minus(14, ChronoUnit.DAYS);
        ReportDocument stalePipeline = pipelineStatus(twoWeeksAgo);

        String stderr = captureStderr(() ->
                engine().warnIfStaleAnchorDocs(List.of(stalePipeline), "1w"));

        assertTrue(stderr.isBlank(),
                "PIPELINE-STATUS should not trigger staleness warning even when stale");
    }

    // ---- mixed: stale activity log + fresh pipeline ----

    @Test
    void warnIfStaleAnchorDocs_warnOnlyForActivityLog_inMixedList() {
        Instant twoWeeksAgo = Instant.now().minus(14, ChronoUnit.DAYS);
        ReportDocument staleLog = activityLog(twoWeeksAgo);
        ReportDocument freshPipeline = pipelineStatus(Instant.now());

        String stderr = captureStderr(() ->
                engine().warnIfStaleAnchorDocs(List.of(staleLog, freshPipeline), "1w"));

        assertTrue(stderr.contains("ACTIVITY-LOG.md"), "Should warn for stale activity log");
        assertFalse(stderr.contains("PIPELINE-STATUS.md"),
                "Should not warn for pipeline status");
    }
}
