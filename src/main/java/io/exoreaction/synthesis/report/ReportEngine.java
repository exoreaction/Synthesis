package io.exoreaction.synthesis.report;

import io.exoreaction.synthesis.ai.ClaudeClient;
import io.exoreaction.synthesis.research.ResearchPassResult;

import java.nio.file.Path;
import java.util.List;

/**
 * Orchestrates business document discovery and AI analysis for executive reports.
 *
 * <p>Unlike {@link io.exoreaction.synthesis.research.ResearchEngine} which analyzes
 * code metrics, ReportEngine works with business documents: pipeline status,
 * activity logs, event files, and strategy documents.
 *
 * <p>Multi-pass analysis approach:
 * <ul>
 *   <li>WEEKLY/EXECUTIVE: pipeline pass + activities pass + decisions pass + executive synthesis</li>
 *   <li>PIPELINE: pipeline pass + brief synthesis</li>
 *   <li>ACTIVITIES: activities pass + brief synthesis</li>
 *   <li>DECISIONS: decisions pass + brief synthesis</li>
 * </ul>
 */
public class ReportEngine {

    private final ClaudeClient client;
    private final int maxTokensPerPass;

    /**
     * Creates a ReportEngine.
     *
     * @param client           the AI client for generation
     * @param maxTokensPerPass maximum tokens per pass output
     */
    public ReportEngine(ClaudeClient client, int maxTokensPerPass) {
        this.client = client;
        this.maxTokensPerPass = maxTokensPerPass;
    }

    /**
     * Returns the model being used.
     */
    public String getModel() {
        return client != null ? client.getModel() : "none";
    }

    /**
     * Generates a business report.
     *
     * @param workspaceRoot the workspace root directory
     * @param target        the report target audience
     * @param topic         the report topic
     * @param period        the coverage period (1w, 2w, 1m)
     * @param verbose       whether to print progress to stderr
     * @return the report result
     */
    public ReportResult generate(Path workspaceRoot, ReportTarget target,
                                  ReportTopic topic, String period, boolean verbose) {
        long startTime = System.currentTimeMillis();

        // Step 1: Discover business documents (period-filtered, #46)
        BusinessDocumentFinder finder = new BusinessDocumentFinder();
        List<ReportDocument> documents = finder.discover(workspaceRoot, topic, period);

        if (verbose) {
            System.err.println("  Discovered " + documents.size() + " business documents:");
            for (ReportDocument doc : documents) {
                System.err.println("    " + doc.briefDescription());
            }
        }

        if (documents.isEmpty()) {
            String emptyReport = "No business documents found in workspace. " +
                    "Expected files matching patterns like PIPELINE-STATUS.md, ACTIVITY-LOG.md, " +
                    "files in events/ directories, or files in business/strategy/ directories.";
            return ReportResult.fromGeneration(
                    target, topic, documents, emptyReport, getModel(), 0,
                    System.currentTimeMillis() - startTime, period);
        }

        String periodDescription = ReportRenderer.periodToDescription(period);
        int totalTokens = 0;

        // Step 2: Run analysis passes based on topic
        String reportContent;

        switch (topic) {
            case PIPELINE: {
                if (verbose) System.err.print("  Running pipeline analysis...");
                String pipelineResult = client.generate(
                        ReportPrompts.pipelinePass(documents, target, periodDescription),
                        maxTokensPerPass);
                totalTokens += ResearchPassResult.estimateTokens(pipelineResult);
                if (verbose) System.err.println(" done");
                reportContent = pipelineResult;
                break;
            }
            case ACTIVITIES: {
                if (verbose) System.err.print("  Running activities analysis...");
                String activitiesResult = client.generate(
                        ReportPrompts.activitiesPass(documents, target, periodDescription),
                        maxTokensPerPass);
                totalTokens += ResearchPassResult.estimateTokens(activitiesResult);
                if (verbose) System.err.println(" done");
                reportContent = activitiesResult;
                break;
            }
            case DECISIONS: {
                if (verbose) System.err.print("  Running decisions analysis...");
                String decisionsResult = client.generate(
                        ReportPrompts.decisionsPass(documents, target, periodDescription),
                        maxTokensPerPass);
                totalTokens += ResearchPassResult.estimateTokens(decisionsResult);
                if (verbose) System.err.println(" done");
                reportContent = decisionsResult;
                break;
            }
            case WEEKLY:
            case EXECUTIVE:
            default: {
                // Multi-pass: pipeline + activities + decisions + executive synthesis

                // Pass 1: Pipeline
                if (verbose) System.err.print("  Running pipeline pass...");
                List<ReportDocument> pipelineDocs = documents.stream()
                        .filter(d -> "pipeline".equals(d.category()) || "strategy".equals(d.category()))
                        .toList();
                String pipelineContent = null;
                if (!pipelineDocs.isEmpty()) {
                    pipelineContent = client.generate(
                            ReportPrompts.pipelinePass(pipelineDocs, target, periodDescription),
                            maxTokensPerPass);
                    totalTokens += ResearchPassResult.estimateTokens(pipelineContent);
                }
                if (verbose) System.err.println(" done");

                // Pass 2: Activities
                if (verbose) System.err.print("  Running activities pass...");
                List<ReportDocument> activityDocs = documents.stream()
                        .filter(d -> "activity".equals(d.category()) || "event".equals(d.category()))
                        .toList();
                String activitiesContent = null;
                if (!activityDocs.isEmpty()) {
                    activitiesContent = client.generate(
                            ReportPrompts.activitiesPass(activityDocs, target, periodDescription),
                            maxTokensPerPass);
                    totalTokens += ResearchPassResult.estimateTokens(activitiesContent);
                }
                if (verbose) System.err.println(" done");

                // Pass 3: Decisions
                if (verbose) System.err.print("  Running decisions pass...");
                String decisionsContent = client.generate(
                        ReportPrompts.decisionsPass(documents, target, periodDescription),
                        maxTokensPerPass);
                totalTokens += ResearchPassResult.estimateTokens(decisionsContent);
                if (verbose) System.err.println(" done");

                // Pass 4: Executive synthesis
                if (verbose) System.err.print("  Running executive synthesis...");
                int synthesisTokens = Math.min(maxTokensPerPass * 2, 16000);
                reportContent = client.generate(
                        ReportPrompts.executivePass(documents, target,
                                pipelineContent, activitiesContent, decisionsContent,
                                periodDescription),
                        synthesisTokens);
                totalTokens += ResearchPassResult.estimateTokens(reportContent);
                if (verbose) System.err.println(" done");
                break;
            }
        }

        long generationTime = System.currentTimeMillis() - startTime;

        ReportResult result = ReportResult.fromGeneration(
                target, topic, documents, reportContent, getModel(),
                totalTokens, generationTime, period);

        if (verbose) {
            System.err.println("  Total: " + result.totalTokenCount() + " tokens, " +
                    String.format("$%.4f", result.estimatedCostUsd()) + " estimated cost, " +
                    generationTime + "ms");
        }

        return result;
    }

    /**
     * Generates a report for a specific named entity (product or client).
     *
     * <p>2-pass analysis:
     * <ol>
     *   <li>Evidence pass: extract and structure all relevant facts</li>
     *   <li>Synthesis pass: interpret evidence, produce actionable report</li>
     * </ol>
     *
     * @param workspaceRoot the workspace root directory
     * @param target        the report target audience
     * @param topic         PRODUCT or CLIENT
     * @param entityName    the product or client name (e.g., "Synthesis", "Mynder")
     * @param period        the coverage period (1w, 2w, 1m)
     * @param verbose       whether to print progress to stderr
     * @return the report result
     */
    public ReportResult generateForEntity(Path workspaceRoot, ReportTarget target,
                                           ReportTopic topic, String entityName,
                                           String period, boolean verbose) {
        long startTime = System.currentTimeMillis();
        String entityType = topic == ReportTopic.CLIENT ? "client" : "product";

        // Step 1: Discover entity documents
        EntityDocumentFinder entityFinder = new EntityDocumentFinder();
        List<ReportDocument> documents = topic == ReportTopic.CLIENT
                ? entityFinder.discoverForClient(workspaceRoot, entityName)
                : entityFinder.discoverForProduct(workspaceRoot, entityName);

        if (verbose) {
            System.err.println("  Entity: " + entityName + " (" + entityType + ")");
            System.err.println("  Discovered " + documents.size() + " relevant documents:");
            for (ReportDocument doc : documents) {
                System.err.println("    " + doc.briefDescription());
            }
        }

        if (documents.isEmpty()) {
            String emptyReport = "No documents found for " + entityType + " \"" + entityName + "\". " +
                    "Check that the name matches a directory or file in the workspace. " +
                    "Expected locations: eXOReaction/clients/, eXOReaction/products/, /src/exoreaction/.";
            return ReportResult.fromGeneration(
                    target, topic, documents, emptyReport, getModel(), 0,
                    System.currentTimeMillis() - startTime, period);
        }

        int totalTokens = 0;

        // Pass 1: Evidence collection
        if (verbose) System.err.print("  Running evidence pass...");
        String evidenceResult = client.generate(
                ReportPrompts.entityEvidencePass(entityName, entityType, documents),
                maxTokensPerPass);
        totalTokens += ResearchPassResult.estimateTokens(evidenceResult);
        if (verbose) System.err.println(" done");

        // Pass 2: Synthesis and recommendations
        if (verbose) System.err.print("  Running synthesis pass...");
        int synthesisTokens = Math.min(maxTokensPerPass, 8000);
        String reportContent = client.generate(
                ReportPrompts.entitySynthesisPass(entityName, entityType, evidenceResult, target),
                synthesisTokens);
        totalTokens += ResearchPassResult.estimateTokens(reportContent);
        if (verbose) System.err.println(" done");

        long generationTime = System.currentTimeMillis() - startTime;

        ReportResult result = ReportResult.fromGeneration(
                target, topic, documents, reportContent, getModel(),
                totalTokens, generationTime, period);

        if (verbose) {
            System.err.println("  Total: " + result.totalTokenCount() + " tokens, " +
                    String.format("$%.4f", result.estimatedCostUsd()) + " estimated cost, " +
                    generationTime + "ms");
        }

        return result;
    }

    /**
     * Estimates the cost of generating a report without running AI.
     *
     * @param workspaceRoot the workspace root directory
     * @param target        the report target
     * @param topic         the report topic
     * @param period        the coverage period
     * @return cost estimate information
     */
    public CostEstimate estimateCost(Path workspaceRoot, ReportTarget target,
                                      ReportTopic topic, String period) {
        // Discover documents to count them (period-filtered, #46)
        BusinessDocumentFinder finder = new BusinessDocumentFinder();
        List<ReportDocument> documents = finder.discover(workspaceRoot, topic, period);

        int docCount = documents.size();

        // Build actual prompts to estimate token counts accurately (#53)
        // (~4 chars per token heuristic)
        String periodDisplay = (period != null && !period.isBlank()) ? period : "1w";
        int estimatedInputTokens;
        int passCount;
        int estimatedOutputTokens;

        switch (topic) {
            case PIPELINE: {
                String prompt = ReportPrompts.pipelinePass(documents, target, periodDisplay);
                estimatedInputTokens = prompt.length() / 4;
                passCount = 1;
                estimatedOutputTokens = maxTokensPerPass;
                break;
            }
            case ACTIVITIES: {
                String prompt = ReportPrompts.activitiesPass(documents, target, periodDisplay);
                estimatedInputTokens = prompt.length() / 4;
                passCount = 1;
                estimatedOutputTokens = maxTokensPerPass;
                break;
            }
            case DECISIONS: {
                String prompt = ReportPrompts.decisionsPass(documents, target, periodDisplay);
                estimatedInputTokens = prompt.length() / 4;
                passCount = 1;
                estimatedOutputTokens = maxTokensPerPass;
                break;
            }
            case WEEKLY:
            case EXECUTIVE:
            default: {
                // Build each pass prompt with actual documents to measure total chars
                String p1 = ReportPrompts.pipelinePass(documents, target, periodDisplay);
                String p2 = ReportPrompts.activitiesPass(documents, target, periodDisplay);
                String p3 = ReportPrompts.decisionsPass(documents, target, periodDisplay);
                String p4 = ReportPrompts.executivePass(
                        documents, target,
                        "(pipeline output)", "(activities output)", "(decisions output)",
                        periodDisplay);
                estimatedInputTokens = (p1.length() + p2.length() + p3.length() + p4.length()) / 4;
                // Synthesis pass also receives previous pass outputs as input
                estimatedInputTokens += maxTokensPerPass * 3;
                passCount = 4;
                estimatedOutputTokens = maxTokensPerPass * 3 + Math.min(maxTokensPerPass * 2, 16000);
                break;
            }
        }

        String model = getModel();
        double inputCostPerMToken;
        double outputCostPerMToken;

        if (model.contains("opus")) {
            inputCostPerMToken = 15.0;
            outputCostPerMToken = 75.0;
        } else {
            inputCostPerMToken = 3.0;
            outputCostPerMToken = 15.0;
        }

        double inputCost = estimatedInputTokens * inputCostPerMToken / 1_000_000;
        double outputCost = estimatedOutputTokens * outputCostPerMToken / 1_000_000;
        double totalCost = inputCost + outputCost;

        return new CostEstimate(
                passCount, docCount, estimatedInputTokens, estimatedOutputTokens,
                inputCost, outputCost, totalCost, model);
    }

    /**
     * Cost estimation result.
     */
    public record CostEstimate(
            int passCount,
            int documentCount,
            int estimatedInputTokens,
            int estimatedOutputTokens,
            double inputCostUsd,
            double outputCostUsd,
            double totalCostUsd,
            String model
    ) {
        /**
         * Formats the cost estimate for display.
         */
        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("Business Report Cost Estimate\n");
            sb.append("========================================\n\n");
            sb.append("Model:          ").append(model).append("\n");
            sb.append("Passes:         ").append(passCount).append("\n");
            sb.append("Documents:      ").append(documentCount).append("\n");
            sb.append("Input tokens:   ~").append(String.format("%,d", estimatedInputTokens)).append("\n");
            sb.append("Output tokens:  ~").append(String.format("%,d", estimatedOutputTokens)).append("\n");
            sb.append("\n");
            sb.append("Input cost:     $").append(String.format("%.4f", inputCostUsd)).append("\n");
            sb.append("Output cost:    $").append(String.format("%.4f", outputCostUsd)).append("\n");
            sb.append("Total cost:     $").append(String.format("%.4f", totalCostUsd)).append("\n");
            sb.append("\n");
            sb.append("Note: This is an estimate. Actual cost depends on document sizes and response length.\n");
            return sb.toString();
        }
    }
}
