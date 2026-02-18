package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BusinessDocumentFinder — validates document discovery patterns,
 * category routing, exclusions, false-positive fixes, and period-based filtering.
 *
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/43">#43</a>
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/45">#45</a>
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/46">#46</a>
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/50">#50</a>
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/51">#51</a>
 */
class BusinessDocumentFinderTest {

    @TempDir
    Path tempDir;

    private final BusinessDocumentFinder finder = new BusinessDocumentFinder();

    // ---- Basic discovery ----

    @Test
    void discover_findsPipelineStatusFile() throws IOException {
        Path dir = tempDir.resolve("eXOReaction/business");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("PIPELINE-STATUS.md"), "# Pipeline\nActive deals...");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE);

        assertFalse(docs.isEmpty(), "Should find PIPELINE-STATUS.md");
        assertEquals("pipeline", docs.get(0).category());
    }

    @Test
    void discover_findsActivityLogFile() throws IOException {
        Files.writeString(tempDir.resolve("ACTIVITY-LOG.md"), "# Activity Log\nMeetings...");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.ACTIVITIES);

        assertTrue(docs.stream().anyMatch(d -> "activity".equals(d.category())),
                "Should find ACTIVITY-LOG.md as activity category");
    }

    @Test
    void discover_findsEventsDirectory() throws IOException {
        Path eventsDir = tempDir.resolve("eXOReaction/events");
        Files.createDirectories(eventsDir);
        Files.writeString(eventsDir.resolve("workshop-feb.md"), "# Workshop\nFeb 2026");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.ACTIVITIES);

        assertTrue(docs.stream().anyMatch(d -> "event".equals(d.category())),
                "Should find files in events/ directory");
    }

    @Test
    void discover_findsStrategyFiles() throws IOException {
        Path strategyDir = tempDir.resolve("eXOReaction/business/strategy");
        Files.createDirectories(strategyDir);
        Files.writeString(strategyDir.resolve("EXECUTIVE-SUMMARY.md"), "# Strategy");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.DECISIONS);

        assertTrue(docs.stream().anyMatch(d -> "strategy".equals(d.category())),
                "Should find strategy files in business/strategy/");
    }

    @Test
    void discover_findsExecutiveUpdateFiles() throws IOException {
        Files.writeString(tempDir.resolve("EXECUTIVE-UPDATE-2026-02.md"), "# Update");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.EXECUTIVE);

        assertTrue(docs.stream().anyMatch(d -> "executive".equals(d.category())),
                "Should find EXECUTIVE-UPDATE files");
    }

    // ---- Exclusions ----

    @Test
    void discover_excludesReadmeFiles() throws IOException {
        Files.writeString(tempDir.resolve("README.md"), "# README with pipeline info");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.WEEKLY);

        assertTrue(docs.isEmpty(), "README.md should be excluded from all categories");
    }

    @Test
    void discover_excludesDotSynthesisDirectory() throws IOException {
        Path synthDir = tempDir.resolve(".synthesis");
        Files.createDirectories(synthDir);
        Files.writeString(synthDir.resolve("PIPELINE-STATUS.md"), "# Cached pipeline");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE);

        assertTrue(docs.isEmpty(), "Files under .synthesis/ should be excluded");
    }

    @Test
    void discover_excludesGitDirectory() throws IOException {
        Path gitDir = tempDir.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("ACTIVITY-LOG.md"), "# Git internal");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.ACTIVITIES);

        assertTrue(docs.isEmpty(), "Files under .git/ should be excluded");
    }

    // ---- Topic routing ----

    @Test
    void discover_pipelineTopicOnlyFindsRelevantCategories() throws IOException {
        Files.writeString(tempDir.resolve("PIPELINE-STATUS.md"), "# Pipeline");
        Files.writeString(tempDir.resolve("ACTIVITY-LOG.md"), "# Activity");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE);

        assertTrue(docs.stream().allMatch(d -> "pipeline".equals(d.category())),
                "PIPELINE topic should only return pipeline-category documents");
    }

    @Test
    void discover_weeklyTopicFindsAllCategories() throws IOException {
        Files.writeString(tempDir.resolve("PIPELINE-STATUS.md"), "# Pipeline");
        Files.writeString(tempDir.resolve("ACTIVITY-LOG.md"), "# Activity");
        Path eventsDir = tempDir.resolve("eXOReaction/events");
        Files.createDirectories(eventsDir);
        Files.writeString(eventsDir.resolve("workshop.md"), "# Event");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.WEEKLY);

        assertTrue(docs.size() >= 3, "WEEKLY topic should find docs from multiple categories");
    }

    // ---- Sorting and dedup ----

    @Test
    void discover_sortsByLastModifiedMostRecentFirst() throws IOException {
        Path p1 = tempDir.resolve("PIPELINE-STATUS.md");
        Path p2 = tempDir.resolve("pipeline-archive.md");
        Files.writeString(p1, "# Current");
        Files.writeString(p2, "# Old");
        Files.setLastModifiedTime(p1, FileTime.from(Instant.now()));
        Files.setLastModifiedTime(p2, FileTime.from(Instant.now().minus(30, ChronoUnit.DAYS)));

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE);

        assertTrue(docs.size() >= 2, "Should find both pipeline files");
        assertTrue(docs.get(0).lastModified().isAfter(docs.get(1).lastModified()),
                "Most recently modified document should be first");
    }

    @Test
    void discover_deduplicatesByPath() throws IOException {
        Files.writeString(tempDir.resolve("PIPELINE-STATUS.md"), "# Pipeline");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.WEEKLY);

        long pipelineCount = docs.stream()
                .filter(d -> d.path().getFileName().toString().equals("PIPELINE-STATUS.md"))
                .count();
        assertEquals(1, pipelineCount, "Same file should not appear twice after deduplication");
    }

    // ---- False positives: #43 technical pipeline files ----

    @Test
    void discover_excludesTechnicalPipelineFilesInSkillsDir_issue43() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("multi-stage-pipeline-architecture.yaml"),
                "# Technical pipeline config");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE);

        assertTrue(docs.isEmpty(),
                "Technical pipeline files in /skills/ should NOT be discovered (#43)");
    }

    @Test
    void discover_excludesTechnicalPipelineFilesInMethodologyDir_issue43() throws IOException {
        Path methodDir = tempDir.resolve("methodology");
        Files.createDirectories(methodDir);
        Files.writeString(methodDir.resolve("pipeline-overview.md"),
                "# Methodology: Pipeline Overview");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE);

        assertTrue(docs.isEmpty(),
                "Methodology pipeline files should NOT be discovered (#43)");
    }

    @Test
    void discover_excludesTechnicalPipelineFilesInArchitectureDir_issue43() throws IOException {
        Path archDir = tempDir.resolve("docs/technical");
        Files.createDirectories(archDir);
        Files.writeString(archDir.resolve("pipeline-design.md"),
                "# Architecture: Pipeline Design");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE);

        assertTrue(docs.isEmpty(),
                "Technical architecture pipeline files should NOT be discovered (#43)");
    }

    // ---- False positives: #45 personal events ----

    @Test
    void discover_excludesPersonalEventsDirectory_issue45() throws IOException {
        Path personalEvents = tempDir.resolve("personal/events");
        Files.createDirectories(personalEvents);
        Files.writeString(personalEvents.resolve("sommerfest.md"), "# Sommerfest 2026");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.ACTIVITIES);

        boolean hasPersonalEvent = docs.stream()
                .anyMatch(d -> d.path().toString().contains("personal"));
        assertFalse(hasPersonalEvent,
                "Personal events in /personal/events/ should NOT be discovered (#45)");
    }

    @Test
    void discover_stillFindsBusinessEvents_regressionGuardFor45() throws IOException {
        Path businessEvents = tempDir.resolve("eXOReaction/events");
        Files.createDirectories(businessEvents);
        Files.writeString(businessEvents.resolve("workshop-feb.md"), "# Workshop February 2026");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.ACTIVITIES);

        assertTrue(docs.stream().anyMatch(d -> "event".equals(d.category())),
                "Business events must still be discovered after #45 fix (regression guard)");
    }

    // ---- False positives: #50 presentation materials in events ----

    @Test
    void discover_excludesPresentationNotesFromEvents_issue50() throws IOException {
        Path eventsDir = tempDir.resolve("eXOReaction/events");
        Files.createDirectories(eventsDir);
        Files.writeString(eventsDir.resolve("conference-presentation-notes.md"),
                "# Presentation: AI Development Workshop");
        Files.writeString(eventsDir.resolve("javazone-slides-summary.md"),
                "# Slides: JavaZone 2026 Talk");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.ACTIVITIES);

        boolean hasPresentationMaterial = docs.stream()
                .anyMatch(d -> d.path().getFileName().toString().contains("presentation")
                        || d.path().getFileName().toString().contains("slides"));
        assertFalse(hasPresentationMaterial,
                "Presentation materials in events/ should NOT be categorized as events (#50)");
    }

    @Test
    void discover_stillFindsNonPresentationEventsFiles_regressionGuardFor50() throws IOException {
        Path eventsDir = tempDir.resolve("eXOReaction/events");
        Files.createDirectories(eventsDir);
        Files.writeString(eventsDir.resolve("item-consulting-feb13.md"),
                "# Item Consulting Internal Conference Feb 13");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.ACTIVITIES);

        assertTrue(docs.stream().anyMatch(d -> "event".equals(d.category())),
                "Regular event files must still be found after #50 fix (regression guard)");
    }

    // ---- Archive detection: #51 ----

    @Test
    void reportDocument_isArchived_trueForArchivePaths_issue51() {
        ReportDocument doc = new ReportDocument(
                Path.of("/workspace/archive/old-strategy.md"),
                "archive/old-strategy.md", "strategy", "Old content", Instant.now(), 100L);
        assertTrue(doc.isArchived(), "Documents in /archive/ should be detected as archived (#51)");
    }

    @Test
    void reportDocument_isArchived_trueForLegacyPaths_issue51() {
        ReportDocument doc = new ReportDocument(
                Path.of("/workspace/legacy/2024-plan.md"),
                "legacy/2024-plan.md", "strategy", "Legacy content", Instant.now(), 100L);
        assertTrue(doc.isArchived(), "Documents in /legacy/ should be detected as archived (#51)");
    }

    @Test
    void reportDocument_isArchived_trueForHistoricalPaths_issue51() {
        ReportDocument doc = new ReportDocument(
                Path.of("/workspace/historical/q4-report.md"),
                "historical/q4-report.md", "pipeline", "Historical", Instant.now(), 100L);
        assertTrue(doc.isArchived(), "Documents in /historical/ should be archived (#51)");
    }

    @Test
    void reportDocument_isArchived_falseForNormalPaths_issue51() {
        ReportDocument doc = new ReportDocument(
                Path.of("/workspace/eXOReaction/business/strategy/EXECUTIVE-SUMMARY.md"),
                "eXOReaction/business/strategy/EXECUTIVE-SUMMARY.md", "strategy",
                "Current content", Instant.now(), 100L);
        assertFalse(doc.isArchived(),
                "Normal business documents should NOT be flagged as archived (#51)");
    }

    // ---- Period-based filtering: #46 ----

    @Test
    void discover_withPeriod1w_excludesOldNonAnchorDocuments_issue46() throws IOException {
        Path strategyDir = tempDir.resolve("eXOReaction/business/strategy");
        Files.createDirectories(strategyDir);
        Path oldFile = strategyDir.resolve("old-strategy.md");
        Files.writeString(oldFile, "# Old Strategy");
        Files.setLastModifiedTime(oldFile,
                FileTime.from(Instant.now().minus(30, ChronoUnit.DAYS)));

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.DECISIONS, "1w");

        assertFalse(docs.stream().anyMatch(d ->
                        d.path().getFileName().toString().equals("old-strategy.md")),
                "Documents older than 1 week should be excluded with --period 1w (#46)");
    }

    @Test
    void discover_withPeriod1m_includesThreeWeekOldDocuments_issue46() throws IOException {
        Path strategyDir = tempDir.resolve("eXOReaction/business/strategy");
        Files.createDirectories(strategyDir);
        Path recentFile = strategyDir.resolve("recent-strategy.md");
        Files.writeString(recentFile, "# Recent Strategy");
        Files.setLastModifiedTime(recentFile,
                FileTime.from(Instant.now().minus(21, ChronoUnit.DAYS)));

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.DECISIONS, "1m");

        assertTrue(docs.stream().anyMatch(d ->
                        d.path().getFileName().toString().equals("recent-strategy.md")),
                "Documents 21 days old should be included with --period 1m (#46)");
    }

    @Test
    void discover_withPeriod_alwaysIncludesPipelineStatus_issue46() throws IOException {
        Path pipeline = tempDir.resolve("PIPELINE-STATUS.md");
        Files.writeString(pipeline, "# Pipeline");
        Files.setLastModifiedTime(pipeline,
                FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE, "1w");

        assertTrue(docs.stream().anyMatch(d ->
                        d.path().getFileName().toString().equals("PIPELINE-STATUS.md")),
                "PIPELINE-STATUS.md is anchor doc and must always be included (#46)");
    }

    @Test
    void discover_withPeriod_alwaysIncludesActivityLog_issue46() throws IOException {
        Path activityLog = tempDir.resolve("ACTIVITY-LOG.md");
        Files.writeString(activityLog, "# Activity Log");
        Files.setLastModifiedTime(activityLog,
                FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.ACTIVITIES, "1w");

        assertTrue(docs.stream().anyMatch(d ->
                        d.path().getFileName().toString().equals("ACTIVITY-LOG.md")),
                "ACTIVITY-LOG.md is anchor doc and must always be included (#46)");
    }

    @Test
    void discover_noPeriodOverload_defaultsToNoFiltering() throws IOException {
        // Original discover(workspace, topic) must still work without period
        Path pipeline = tempDir.resolve("PIPELINE-STATUS.md");
        Files.writeString(pipeline, "# Pipeline");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE);

        assertFalse(docs.isEmpty(), "discover() without period should still find docs");
    }

    @Test
    void parsePeriodCutoff_1w_returnsApproximatelySevenDaysAgo_issue46() {
        Instant cutoff = BusinessDocumentFinder.parsePeriodCutoff("1w");
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        assertTrue(Math.abs(cutoff.toEpochMilli() - sevenDaysAgo.toEpochMilli()) < 86_400_000L,
                "1w cutoff should be approximately 7 days ago");
    }

    @Test
    void parsePeriodCutoff_2w_returnsApproximatelyFourteenDaysAgo_issue46() {
        Instant cutoff = BusinessDocumentFinder.parsePeriodCutoff("2w");
        Instant fourteenDaysAgo = Instant.now().minus(14, ChronoUnit.DAYS);
        assertTrue(Math.abs(cutoff.toEpochMilli() - fourteenDaysAgo.toEpochMilli()) < 86_400_000L,
                "2w cutoff should be approximately 14 days ago");
    }

    @Test
    void parsePeriodCutoff_1m_returnsApproximatelyThirtyDaysAgo_issue46() {
        Instant cutoff = BusinessDocumentFinder.parsePeriodCutoff("1m");
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        assertTrue(Math.abs(cutoff.toEpochMilli() - thirtyDaysAgo.toEpochMilli()) < 86_400_000L * 2,
                "1m cutoff should be approximately 30 days ago");
    }

    @Test
    void parsePeriodCutoff_unknown_defaultsToOneWeek_issue46() {
        Instant cutoff = BusinessDocumentFinder.parsePeriodCutoff("3m");
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        assertTrue(Math.abs(cutoff.toEpochMilli() - sevenDaysAgo.toEpochMilli()) < 86_400_000L,
                "Unknown period should default to 1w");
    }
}
