package io.exoreaction.synthesis.research;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders ResearchResult into target-specific output formats.
 *
 * <p>Three rendering modes:
 * <ul>
 *   <li>ChatGPT: Structured research document with research questions</li>
 *   <li>NotebookLM Infographic: Exhaustive data dump for visualization</li>
 *   <li>NotebookLM Presentation: Chapter-based narrative with slide markers</li>
 * </ul>
 */
public class ResearchRenderer {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                    .withZone(ZoneId.systemDefault());

    private ResearchRenderer() {}

    /**
     * Renders a ResearchResult based on its target type.
     *
     * @param result the research result to render
     * @return formatted markdown output
     */
    public static String render(ResearchResult result) {
        if (result == null) return "";

        return switch (result.target()) {
            case CHATGPT_DEEP_RESEARCH -> renderForChatGpt(result);
            case NOTEBOOKLM_INFOGRAPHIC -> renderForNotebookLmInfographic(result);
            case NOTEBOOKLM_PRESENTATION -> renderForNotebookLmPresentation(result);
        };
    }

    /**
     * Renders for ChatGPT Deep Research: structured research document.
     */
    public static String renderForChatGpt(ResearchResult result) {
        StringBuilder sb = new StringBuilder();

        sb.append("# Codebase Research Report\n\n");
        sb.append("**Generated:** ").append(formatTimestamp(result.generatedAt())).append("\n");
        sb.append("**Model:** ").append(result.model()).append("\n");
        sb.append("**Topic:** ").append(result.topic().displayName()).append("\n");
        sb.append("**Passes:** ").append(result.passes().size()).append("\n");
        sb.append("**Total Tokens:** ").append(String.format("%,d", result.totalTokenCount())).append("\n\n");

        sb.append("---\n\n");

        // If we have a synthesis pass, use it as the main report body
        ResearchPassResult synthesisPass = findPass(result.passes(), "synthesis");
        if (synthesisPass != null && synthesisPass.content() != null && !synthesisPass.content().isBlank()) {
            sb.append(synthesisPass.content()).append("\n\n");
        } else {
            // No synthesis pass -- render domain passes directly
            sb.append("## Executive Summary\n\n");
            sb.append("This report contains ").append(result.passes().size())
                    .append(" analysis passes covering the codebase.\n\n");

            for (ResearchPassResult pass : result.passes()) {
                if (pass.content() != null && !pass.content().isBlank()) {
                    sb.append("## ").append(capitalizeFirst(pass.passName())).append(" Analysis\n\n");
                    sb.append(pass.content()).append("\n\n");
                }
            }
        }

        // Append domain pass details as appendices
        if (synthesisPass != null) {
            sb.append("---\n\n");
            sb.append("# Appendix: Detailed Pass Results\n\n");
            for (ResearchPassResult pass : result.passes()) {
                if ("synthesis".equals(pass.passName())) continue;
                if (pass.content() != null && !pass.content().isBlank()) {
                    sb.append("## Appendix: ").append(capitalizeFirst(pass.passName())).append(" Pass\n\n");
                    sb.append(pass.content()).append("\n\n");
                }
            }
        }

        // Research Questions section (always present for ChatGPT)
        sb.append("---\n\n");
        sb.append("## Research Questions for Further Investigation\n\n");
        sb.append("The following questions are suggested for ChatGPT Deep Research validation:\n\n");
        sb.append("1. How does this codebase's architecture compare to industry best practices?\n");
        sb.append("2. What are the known vulnerabilities in the dependency versions identified?\n");
        sb.append("3. How does the test coverage ratio compare to similar projects in this domain?\n");
        sb.append("4. What refactoring strategies have proven most effective for codebases of this scale?\n");
        sb.append("5. What are the industry benchmarks for the metrics identified in this report?\n");

        return sb.toString();
    }

    /**
     * Renders for NotebookLM Infographic: exhaustive data dump.
     */
    public static String renderForNotebookLmInfographic(ResearchResult result) {
        StringBuilder sb = new StringBuilder();

        sb.append("# Complete Codebase Analysis Data\n\n");
        sb.append("**Generated:** ").append(formatTimestamp(result.generatedAt())).append("\n");
        sb.append("**Model:** ").append(result.model()).append("\n");
        sb.append("**Analysis Depth:** Exhaustive (optimized for NotebookLM infographic generation)\n\n");

        sb.append("---\n\n");

        sb.append("## Complete File Inventory\n\n");
        sb.append("This section contains an exhaustive inventory of all files analyzed.\n\n");

        // Render all passes as exhaustive data sections
        for (ResearchPassResult pass : result.passes()) {
            if ("synthesis".equals(pass.passName())) continue;
            if (pass.content() != null && !pass.content().isBlank()) {
                sb.append("## Complete ").append(capitalizeFirst(pass.passName())).append(" Data\n\n");
                sb.append(pass.content()).append("\n\n");
            }
        }

        // Synthesis as the combined view
        ResearchPassResult synthesisPass = findPass(result.passes(), "synthesis");
        if (synthesisPass != null && synthesisPass.content() != null) {
            sb.append("## Synthesized Overview\n\n");
            sb.append(synthesisPass.content()).append("\n\n");
        }

        sb.append("---\n\n");
        sb.append("## Metadata\n\n");
        sb.append("| Property | Value |\n");
        sb.append("|----------|-------|\n");
        sb.append("| Total tokens | ").append(String.format("%,d", result.totalTokenCount())).append(" |\n");
        sb.append("| Pass count | ").append(result.passes().size()).append(" |\n");
        sb.append("| Model | ").append(result.model()).append(" |\n");
        sb.append("| Estimated cost | $").append(String.format("%.4f", result.estimatedCostUsd())).append(" |\n");

        return sb.toString();
    }

    /**
     * Renders for NotebookLM Presentation: chapter-based narrative.
     */
    public static String renderForNotebookLmPresentation(ResearchResult result) {
        StringBuilder sb = new StringBuilder();

        sb.append("# Codebase Analysis Presentation\n\n");
        sb.append("**Generated:** ").append(formatTimestamp(result.generatedAt())).append("\n");
        sb.append("**Model:** ").append(result.model()).append("\n\n");

        sb.append("---\n\n");

        // If we have a synthesis pass with chapters, use it directly
        ResearchPassResult synthesisPass = findPass(result.passes(), "synthesis");
        if (synthesisPass != null && synthesisPass.content() != null &&
                synthesisPass.content().contains("## Chapter")) {
            sb.append(synthesisPass.content()).append("\n\n");
        } else {
            // Build chapters from domain passes
            int chapterNum = 1;

            for (ResearchPassResult pass : result.passes()) {
                if ("synthesis".equals(pass.passName())) continue;
                if (pass.content() != null && !pass.content().isBlank()) {
                    sb.append("## Chapter ").append(chapterNum).append(": ")
                            .append(capitalizeFirst(pass.passName())).append("\n");
                    sb.append("<!-- SLIDE -->\n\n");
                    sb.append(pass.content()).append("\n\n");
                    sb.append("**Speaker Notes:** This section covers the ")
                            .append(pass.passName()).append(" analysis findings.\n\n");
                    chapterNum++;
                }
            }

            // Summary chapter from synthesis (if available)
            if (synthesisPass != null && synthesisPass.content() != null) {
                sb.append("## Chapter ").append(chapterNum).append(": Summary & Recommendations\n");
                sb.append("<!-- SLIDE -->\n\n");
                sb.append(synthesisPass.content()).append("\n\n");
                sb.append("**Speaker Notes:** End with actionable next steps and key priorities.\n\n");
            }
        }

        return sb.toString();
    }

    /**
     * Finds a pass by name in the results list.
     */
    private static ResearchPassResult findPass(List<ResearchPassResult> passes, String name) {
        if (passes == null) return null;
        return passes.stream()
                .filter(p -> name.equals(p.passName()))
                .findFirst()
                .orElse(null);
    }

    private static String capitalizeFirst(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String formatTimestamp(Instant instant) {
        if (instant == null) return "N/A";
        return TIMESTAMP_FORMAT.format(instant);
    }
}
