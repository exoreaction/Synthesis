package io.exoreaction.synthesis.report;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates business-oriented AI prompts for executive report generation.
 *
 * <p>Key difference from ResearchPrompts: These prompts analyze BUSINESS DOCUMENTS
 * (pipeline status, activity logs, strategy files), NOT code metrics. The output
 * is structured for executive consumption, not technical analysis.
 */
public class ReportPrompts {

    private ReportPrompts() {}

    /**
     * Generates the pipeline analysis prompt.
     *
     * @param docs   pipeline-related documents
     * @param target the report target audience
     * @param period the coverage period description (e.g., "last 7 days")
     * @return the prompt string
     */
    public static String pipelinePass(List<ReportDocument> docs, ReportTarget target, String period) {
        String docContent = formatDocuments(docs);

        return """
                You are a business analyst generating a pipeline status report for %s.

                COVERAGE PERIOD: %s

                BUSINESS DOCUMENTS:
                %s

                INSTRUCTIONS:
                Analyze the pipeline documents and produce a structured pipeline report with:

                1. **Closed / Committed Deals**
                   - List each deal with company name, value (NOK or USD), and status
                   - Total closed value

                2. **Hot Pipeline (50-70%% probability)**
                   - Each opportunity with company, estimated value, and probability
                   - Weighted value calculation
                   - Key next steps for each

                3. **Warm Pipeline (20-30%% probability)**
                   - Each opportunity with company, estimated value, and probability
                   - What would move them to Hot

                4. **Total Weighted Forecast**
                   - Sum of (value x probability) across all pipeline stages

                5. **Key Risks and Opportunities**
                   - Deals at risk of slipping
                   - Upside opportunities
                   - Revenue concentration risks

                Format as clean markdown. Use tables where appropriate.
                Be specific with numbers -- do not round excessively.
                If information is missing from the documents, note what data would be needed.
                """.formatted(target.displayName(), period, docContent);
    }

    /**
     * Generates the activities analysis prompt.
     *
     * @param docs   activity-related documents
     * @param target the report target audience
     * @param period the coverage period description
     * @return the prompt string
     */
    public static String activitiesPass(List<ReportDocument> docs, ReportTarget target, String period) {
        String docContent = formatDocuments(docs);

        return """
                You are a business analyst generating an activities report for %s.

                COVERAGE PERIOD: %s

                BUSINESS DOCUMENTS:
                %s

                INSTRUCTIONS:
                Analyze the activity documents and produce a structured activities report with:

                1. **Key Meetings and Outcomes**
                   - Date, participants, company, and outcome for each meeting
                   - Action items from meetings
                   - Follow-up status

                2. **Deals Closed or Advanced**
                   - Deals that moved pipeline stages
                   - New deals entered pipeline
                   - Contracts signed

                3. **Content and Marketing Activities**
                   - LinkedIn posts and engagement metrics
                   - Content published (articles, presentations)
                   - Inbound leads generated

                4. **Events and Presentations**
                   - Upcoming events confirmed
                   - Presentations delivered
                   - Workshop sessions

                5. **Key Patterns**
                   - What activities are driving results
                   - Activity-to-outcome correlations
                   - Velocity trends

                Format as clean markdown. Be specific with dates and outcomes.
                Focus on business impact, not just activity volume.
                """.formatted(target.displayName(), period, docContent);
    }

    /**
     * Generates the decisions analysis prompt.
     *
     * @param docs   documents relevant to pending decisions
     * @param target the report target audience
     * @param period the coverage period description
     * @return the prompt string
     */
    public static String decisionsPass(List<ReportDocument> docs, ReportTarget target, String period) {
        String docContent = formatDocuments(docs);

        return """
                You are a business analyst identifying critical decisions for %s.

                COVERAGE PERIOD: %s

                BUSINESS DOCUMENTS:
                %s

                INSTRUCTIONS:
                Analyze the documents and identify ALL critical decisions that need to be made.
                For each decision, provide:

                1. **Decision Title** (clear, actionable statement)

                2. **Context** -- Why this decision is needed now

                3. **Options** (at least 2-3 options per decision)
                   - Option A: Description, pros, cons, estimated impact
                   - Option B: Description, pros, cons, estimated impact
                   - Option C: Description, pros, cons, estimated impact (if applicable)

                4. **Recommendation** -- Your recommended option with reasoning

                5. **Timeline** -- When the decision needs to be made

                6. **Impact** -- What happens if the decision is delayed

                Prioritize decisions by urgency and business impact.
                Be specific -- avoid generic recommendations.
                Reference specific data from the documents to support analysis.
                """.formatted(target.displayName(), period, docContent);
    }

    /**
     * Generates the executive synthesis prompt that combines all analysis passes.
     *
     * @param allDocs            all discovered documents
     * @param target             the report target audience
     * @param pipelineContent    output from pipeline pass (may be null)
     * @param activitiesContent  output from activities pass (may be null)
     * @param decisionsContent   output from decisions pass (may be null)
     * @param period             the coverage period description
     * @return the prompt string
     */
    public static String executivePass(List<ReportDocument> allDocs, ReportTarget target,
                                        String pipelineContent, String activitiesContent,
                                        String decisionsContent, String period) {
        String docContent = formatDocuments(allDocs);

        StringBuilder previousAnalysis = new StringBuilder();
        if (pipelineContent != null && !pipelineContent.isBlank()) {
            previousAnalysis.append("=== PIPELINE ANALYSIS ===\n").append(pipelineContent).append("\n\n");
        }
        if (activitiesContent != null && !activitiesContent.isBlank()) {
            previousAnalysis.append("=== ACTIVITIES ANALYSIS ===\n").append(activitiesContent).append("\n\n");
        }
        if (decisionsContent != null && !decisionsContent.isBlank()) {
            previousAnalysis.append("=== DECISIONS ANALYSIS ===\n").append(decisionsContent).append("\n\n");
        }

        String targetInstructions = getTargetInstructions(target);

        return """
                You are generating a comprehensive executive report by synthesizing previous analysis passes.

                COVERAGE PERIOD: %s
                TARGET AUDIENCE: %s

                PREVIOUS ANALYSIS:
                %s

                ADDITIONAL SOURCE DOCUMENTS:
                %s

                TARGET FORMAT INSTRUCTIONS:
                %s

                REPORT STRUCTURE:

                1. **Executive Summary** (3-5 key bullet points)
                   - Top deliverables and business impact
                   - Critical numbers (revenue, pipeline, deals)
                   - Most important development or change

                2. **Pipeline Status**
                   - Synthesize from pipeline analysis above
                   - Include weighted forecast and quarter targets

                3. **Business Activities**
                   - Key meetings, deals, and outcomes
                   - Content/marketing results
                   - Events and presentations

                4. **Development Activity**
                   - Summarize any development milestones mentioned in documents
                   - Product releases, feature completions
                   - Technical achievements relevant to business

                5. **Strategic Insights**
                   - Patterns that are working
                   - Market signals observed
                   - Competitive positioning changes

                6. **Critical Decisions**
                   - Synthesize from decisions analysis above
                   - Prioritize by urgency and impact

                7. **Next Steps**
                   - This week: immediate actions
                   - Next two weeks: planned activities
                   - This quarter: strategic objectives

                Format as polished markdown. The report should be scannable in 5-7 minutes.
                Use bold for key numbers and outcomes. Use bullet points, not paragraphs.
                """.formatted(period, target.displayName(), previousAnalysis.toString(),
                docContent, targetInstructions);
    }

    /**
     * Returns target-specific formatting instructions.
     */
    private static String getTargetInstructions(ReportTarget target) {
        return switch (target) {
            case CEO -> """
                    FORMAT FOR: CEO
                    - Actionable and direct. What needs to happen? What decisions are needed?
                    - Lead with business impact, not activity
                    - Include specific numbers (NOK values, percentages, dates)
                    - Flag risks early with recommended mitigations
                    - Keep language professional but conversational
                    - Total report: 800-1200 words
                    """;
            case BOARD -> """
                    FORMAT FOR: Board of Directors
                    - Formal and structured. Strategic focus over operational detail
                    - Lead with key metrics and quarter-over-quarter progress
                    - Include governance-relevant items (risks, compliance, major decisions)
                    - Minimize operational detail -- focus on outcomes
                    - Professional, neutral tone
                    - Total report: 600-900 words
                    """;
            case INVESTOR -> """
                    FORMAT FOR: Investors
                    - Market-oriented. Growth signals and traction metrics
                    - Lead with revenue traction and pipeline momentum
                    - Include market validation signals (customer logos, sectors)
                    - Highlight competitive positioning and moat building
                    - Forward-looking with clear growth trajectory
                    - Total report: 700-1000 words
                    """;
        };
    }

    /**
     * Formats a list of documents into a string suitable for AI prompts.
     */
    private static String formatDocuments(List<ReportDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return "(No documents found for this category)";
        }

        return docs.stream()
                .map(doc -> "--- " + doc.category().toUpperCase() + ": " + doc.relativePath() + " ---\n" +
                        doc.content() + "\n")
                .collect(Collectors.joining("\n"));
    }
}
