package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReportPrompts — validates Fix #40 (date anchoring).
 *
 * <p>Before Fix #40, prompts had no current-date injection, causing AI to generate
 * deadlines based on dates found in source documents (often stale). Fix #40 added
 * {@code todayForPrompt()} and injected it into all deadline-generating prompts.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>todayForPrompt() returns the correct ISO date and day name</li>
 *   <li>All prompts that generate deadlines inject TODAY'S DATE</li>
 *   <li>decisionsPass() explicitly instructs AI that deadlines must be today or later</li>
 *   <li>entitySynthesisPass() explicitly instructs AI that deadlines must be today or later</li>
 * </ul>
 */
class ReportPromptsTest {

    // --- todayForPrompt() ---

    @Test
    void todayForPrompt_containsIsoDate() {
        String result = ReportPrompts.todayForPrompt();
        String expectedDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        assertTrue(result.contains(expectedDate),
                "todayForPrompt() should contain today's ISO date (%s) but was: %s"
                        .formatted(expectedDate, result));
    }

    @Test
    void todayForPrompt_containsDayName() {
        String result = ReportPrompts.todayForPrompt();
        // Should contain day name in format "(Monday)", "(Tuesday)", etc.
        assertTrue(result.contains("(") && result.contains(")"),
                "todayForPrompt() should contain day name in parentheses, but was: " + result);

        // Day name should be title-cased
        String[] days = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
        boolean matchesADay = false;
        for (String day : days) {
            if (result.contains(day)) {
                matchesADay = true;
                break;
            }
        }
        assertTrue(matchesADay, "todayForPrompt() should contain a valid day name, but was: " + result);
    }

    @Test
    void todayForPrompt_notHardcoded() {
        // The date should be dynamic (today's date), not a hardcoded value
        String result = ReportPrompts.todayForPrompt();
        assertFalse(result.contains("2026-01-01"),
                "todayForPrompt() must not return hardcoded date");
        assertFalse(result.contains("2025-"),
                "todayForPrompt() must not return a date in 2025");
    }

    // --- pipelinePass() ---

    @Test
    void pipelinePass_containsTodaysDate() {
        List<ReportDocument> docs = List.of(sampleDoc("pipeline"));
        String prompt = ReportPrompts.pipelinePass(docs, ReportTarget.CEO, "Last 7 days");

        assertTrue(prompt.contains("TODAY'S DATE:"),
                "pipelinePass() must inject TODAY'S DATE for deadline anchoring");
        assertTrue(prompt.contains(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)),
                "pipelinePass() must inject today's actual date");
    }

    @Test
    void pipelinePass_containsTargetDisplayName() {
        List<ReportDocument> docs = List.of(sampleDoc("pipeline"));
        String prompt = ReportPrompts.pipelinePass(docs, ReportTarget.CEO, "Last 7 days");
        assertTrue(prompt.contains(ReportTarget.CEO.displayName()),
                "pipelinePass() must include target display name");
    }

    @Test
    void pipelinePass_containsCoverageperiod() {
        String period = "Last 7 days";
        List<ReportDocument> docs = List.of(sampleDoc("pipeline"));
        String prompt = ReportPrompts.pipelinePass(docs, ReportTarget.CEO, period);
        assertTrue(prompt.contains(period),
                "pipelinePass() must include coverage period");
    }

    // --- activitiesPass() ---

    @Test
    void activitiesPass_containsCoveragePeriod() {
        List<ReportDocument> docs = List.of(sampleDoc("activity"));
        String prompt = ReportPrompts.activitiesPass(docs, ReportTarget.CEO, "Last 14 days");
        assertTrue(prompt.contains("Last 14 days"),
                "activitiesPass() must include coverage period");
    }

    @Test
    void activitiesPass_containsDocumentContent() {
        List<ReportDocument> docs = List.of(
                new ReportDocument(
                        Path.of("/workspace/ACTIVITY-LOG.md"),
                        "ACTIVITY-LOG.md",
                        "activity",
                        "Meeting with client on Feb 15",
                        Instant.now(),
                        40L));
        String prompt = ReportPrompts.activitiesPass(docs, ReportTarget.CEO, "Last 7 days");
        assertTrue(prompt.contains("Meeting with client on Feb 15"),
                "activitiesPass() must include document content in prompt");
    }

    // --- decisionsPass() ---

    @Test
    void decisionsPass_containsTodaysDate() {
        List<ReportDocument> docs = List.of(sampleDoc("strategy"));
        String prompt = ReportPrompts.decisionsPass(docs, ReportTarget.CEO, "Last 7 days");

        assertTrue(prompt.contains("TODAY'S DATE:"),
                "decisionsPass() must inject TODAY'S DATE");
        assertTrue(prompt.contains(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)),
                "decisionsPass() must inject today's actual date");
    }

    @Test
    void decisionsPass_containsDeadlineConstraintText() {
        List<ReportDocument> docs = List.of(sampleDoc("strategy"));
        String prompt = ReportPrompts.decisionsPass(docs, ReportTarget.CEO, "Last 7 days");

        // Fix #40: The prompt must explicitly tell AI that past deadlines should be updated.
        // Actual text: "All deadlines must be TODAY (date) or later."
        assertTrue(prompt.contains("or later"),
                "decisionsPass() must instruct AI that deadlines must be today or later");
        assertTrue(prompt.contains("past deadline"),
                "decisionsPass() must instruct AI how to handle past deadlines from source documents");
    }

    @Test
    void decisionsPass_mentionsTodayMultipleTimes() {
        // decisionsPass injects TODAY'S DATE in the instructions AND in the deadline constraint twice
        List<ReportDocument> docs = List.of(sampleDoc("strategy"));
        String prompt = ReportPrompts.decisionsPass(docs, ReportTarget.CEO, "Last 7 days");

        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        long occurrences = prompt.chars()
                .filter(c -> prompt.indexOf(today, 0) != -1)
                .count();

        // The date appears in TODAY'S DATE header, IMPORTANT note, and twice in timeline section
        int count = 0;
        int idx = 0;
        while ((idx = prompt.indexOf(today, idx)) != -1) {
            count++;
            idx++;
        }
        assertTrue(count >= 2,
                "decisionsPass() should reference today's date at least twice for strong anchoring, found: " + count);
    }

    // --- entityEvidencePass() ---

    @Test
    void entityEvidencePass_containsTodaysDate() {
        List<ReportDocument> docs = List.of(sampleDoc("client"));
        String prompt = ReportPrompts.entityEvidencePass("TestCorp", "client", docs);

        assertTrue(prompt.contains("TODAY'S DATE:"),
                "entityEvidencePass() must inject TODAY'S DATE");
        assertTrue(prompt.contains(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)),
                "entityEvidencePass() must inject today's actual date");
    }

    @Test
    void entityEvidencePass_containsStalenessInstruction() {
        List<ReportDocument> docs = List.of(sampleDoc("client"));
        String prompt = ReportPrompts.entityEvidencePass("TestCorp", "client", docs);

        // Fix #40: evidence pass should flag old information as potentially stale
        assertTrue(prompt.contains("stale") || prompt.contains("14 days"),
                "entityEvidencePass() must instruct AI to flag potentially stale information");
    }

    @Test
    void entityEvidencePass_containsEntityName() {
        List<ReportDocument> docs = List.of(sampleDoc("client"));
        String prompt = ReportPrompts.entityEvidencePass("Mynder", "client", docs);
        assertTrue(prompt.contains("Mynder"),
                "entityEvidencePass() must include entity name in prompt");
    }

    // --- entitySynthesisPass() ---

    @Test
    void entitySynthesisPass_containsTodaysDate() {
        String prompt = ReportPrompts.entitySynthesisPass(
                "TestCorp", "client", "Some evidence content", ReportTarget.CEO);

        assertTrue(prompt.contains("TODAY'S DATE:"),
                "entitySynthesisPass() must inject TODAY'S DATE");
        assertTrue(prompt.contains(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)),
                "entitySynthesisPass() must inject today's actual date");
    }

    @Test
    void entitySynthesisPass_containsDeadlineConstraint() {
        String prompt = ReportPrompts.entitySynthesisPass(
                "TestCorp", "client", "Some evidence content", ReportTarget.CEO);

        // Fix #40: synthesis pass must enforce deadline is today or later
        assertTrue(prompt.contains("today or later"),
                "entitySynthesisPass() must instruct AI that deadlines must be today or later");
    }

    @Test
    void entitySynthesisPass_clientFocusQuestions_forClientType() {
        String prompt = ReportPrompts.entitySynthesisPass(
                "TestCorp", "client", "Evidence", ReportTarget.CEO);
        assertTrue(prompt.contains("relationship") || prompt.contains("client"),
                "entitySynthesisPass() for client type must include client relationship focus");
    }

    @Test
    void entitySynthesisPass_productFocusQuestions_forProductType() {
        String prompt = ReportPrompts.entitySynthesisPass(
                "Synthesis", "product", "Evidence", ReportTarget.CEO);
        assertTrue(prompt.contains("product") || prompt.contains("business status"),
                "entitySynthesisPass() for product type must include product business focus");
    }

    // --- executivePass() ---

    @Test
    void executivePass_incorporatesPreviousAnalysis() {
        List<ReportDocument> docs = List.of(sampleDoc("pipeline"));
        String pipelineContent = "Pipeline analysis result";
        String activitiesContent = "Activities result";
        String decisionsContent = "Decisions result";

        String prompt = ReportPrompts.executivePass(
                docs, ReportTarget.CEO,
                pipelineContent, activitiesContent, decisionsContent,
                "Last 7 days");

        assertTrue(prompt.contains("Pipeline analysis result"),
                "executivePass() must include pipeline analysis content");
        assertTrue(prompt.contains("Activities result"),
                "executivePass() must include activities analysis content");
        assertTrue(prompt.contains("Decisions result"),
                "executivePass() must include decisions analysis content");
    }

    @Test
    void executivePass_handlesNullAnalysisPasses() {
        List<ReportDocument> docs = List.of(sampleDoc("pipeline"));

        // Should not throw NPE when analysis passes are null
        assertDoesNotThrow(() -> ReportPrompts.executivePass(
                docs, ReportTarget.CEO,
                null, null, null,
                "Last 7 days"));
    }

    @Test
    void executivePass_handlesEmptyDocumentList() {
        assertDoesNotThrow(() -> ReportPrompts.executivePass(
                List.of(), ReportTarget.BOARD,
                "pipeline content", null, null,
                "Last 14 days"));
    }

    // --- Target-specific formatting ---

    @Test
    void pipelinePass_boardTarget_includesBoardFormatting() {
        List<ReportDocument> docs = List.of(sampleDoc("pipeline"));
        String boardPrompt = ReportPrompts.executivePass(
                docs, ReportTarget.BOARD, "pipeline", null, null, "Last 7 days");
        assertTrue(boardPrompt.contains("Board") || boardPrompt.contains("board"),
                "Board target prompt must reference board format");
    }

    @Test
    void pipelinePass_investorTarget_includesInvestorFormatting() {
        List<ReportDocument> docs = List.of(sampleDoc("pipeline"));
        String investorPrompt = ReportPrompts.executivePass(
                docs, ReportTarget.INVESTOR, "pipeline", null, null, "Last 7 days");
        assertTrue(investorPrompt.contains("Investor") || investorPrompt.contains("investor"),
                "Investor target prompt must reference investor format");
    }

    // --- Helper ---

    private ReportDocument sampleDoc(String category) {
        return new ReportDocument(
                Path.of("/workspace/test-doc.md"),
                "test-doc.md",
                category,
                "Sample content for testing",
                Instant.now(),
                30L);
    }
}
