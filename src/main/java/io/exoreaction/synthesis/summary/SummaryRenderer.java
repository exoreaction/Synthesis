package io.exoreaction.synthesis.summary;

import io.exoreaction.synthesis.util.AnsiOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Map;

/**
 * Renders SummaryResult as terminal (ANSI), Markdown, or JSON output.
 */
public class SummaryRenderer {

    private final ObjectMapper mapper;

    public SummaryRenderer() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    public String renderTerminal(SummaryResult result) {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("\n");
        sb.append("  ").append(AnsiOutput.bold(AnsiOutput.blue("═══════════════════════════════════════"))).append("\n");
        sb.append("  ").append(AnsiOutput.bold("Synthesis - Codebase Summary")).append("\n");
        sb.append("  ").append(AnsiOutput.bold(AnsiOutput.blue("═══════════════════════════════════════"))).append("\n\n");

        // Metadata
        sb.append("  ").append(AnsiOutput.dim("Level: ")).append(result.level().description()).append("\n");
        sb.append("  ").append(AnsiOutput.dim("Perspective: ")).append(result.perspective().description()).append("\n");
        if (result.fromCache()) {
            sb.append("  ").append(AnsiOutput.dim("Source: ")).append(AnsiOutput.green("Cached")).append("\n");
        } else {
            sb.append("  ").append(AnsiOutput.dim("Generated in: ")).append(result.generationTimeMs()).append("ms\n");
        }
        sb.append("\n");

        CodebaseProfile.Profile profile = result.profile();

        // Profile section
        renderProfile(sb, profile, result.level());

        // AI Summary section (if present)
        if (result.aiSummary() != null) {
            sb.append("\n");
            sb.append("  ").append(AnsiOutput.bold(AnsiOutput.cyan("AI Analysis"))).append("\n");
            sb.append("  ").append(AnsiOutput.dim("─────────────────────────────────────")).append("\n");
            for (String line : result.aiSummary().split("\n")) {
                sb.append("  ").append(line).append("\n");
            }
        }

        // Temporal context (if present)
        if (result.temporalContext() != null) {
            sb.append("\n");
            sb.append("  ").append(AnsiOutput.bold(AnsiOutput.yellow("Recent Changes"))).append("\n");
            sb.append("  ").append(AnsiOutput.dim("─────────────────────────────────────")).append("\n");
            for (String line : result.temporalContext().split("\n")) {
                sb.append("  ").append(line).append("\n");
            }
        }

        return sb.toString();
    }

    private void renderProfile(StringBuilder sb, CodebaseProfile.Profile profile, SummaryLevel level) {
        // Health Indicators
        sb.append("  ").append(AnsiOutput.bold("Health Status")).append("\n");
        sb.append("  ").append(AnsiOutput.dim("─────────────────────────────────────")).append("\n");
        for (CodebaseProfile.HealthIndicator h : profile.health()) {
            String statusIcon = switch (h.status()) {
                case "green" -> AnsiOutput.green("●");
                case "yellow" -> AnsiOutput.yellow("●");
                case "red" -> AnsiOutput.red("●");
                default -> "○";
            };
            sb.append("  ").append(statusIcon).append(" ")
              .append(AnsiOutput.bold(h.category())).append(": ")
              .append(h.detail()).append("\n");
        }
        sb.append("\n");

        // Scale Metrics
        CodebaseProfile.ScaleMetrics scale = profile.scale();
        sb.append("  ").append(AnsiOutput.bold("Scale")).append("\n");
        sb.append("  ").append(AnsiOutput.dim("─────────────────────────────────────")).append("\n");
        sb.append(String.format("  Files:        %,d%n", scale.totalFiles()));
        sb.append(String.format("  Size:         %,d bytes (%s)%n",
            scale.totalSizeBytes(), formatBytes(scale.totalSizeBytes())));
        sb.append(String.format("  Directories:  %,d%n", scale.directoryCount()));
        if (!scale.repositories().isEmpty()) {
            sb.append(String.format("  Repositories: %d%n", scale.repositories().size()));
        }
        sb.append("\n");

        // Languages (top 5)
        if (!scale.filesByLanguage().isEmpty()) {
            sb.append("  ").append(AnsiOutput.bold("Languages")).append("\n");
            sb.append("  ").append(AnsiOutput.dim("─────────────────────────────────────")).append("\n");
            scale.filesByLanguage().entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> {
                    double pct = (e.getValue() * 100.0) / scale.totalFiles();
                    sb.append(String.format("  %-15s %,6d  (%3.0f%%)%n",
                        e.getKey(), e.getValue(), pct));
                });
            sb.append("\n");
        }

        // Quality Metrics (for MANAGER and DEVELOPER levels)
        if (level == SummaryLevel.MANAGER || level == SummaryLevel.DEVELOPER) {
            CodebaseProfile.QualityMetrics quality = profile.quality();
            sb.append("  ").append(AnsiOutput.bold("Quality")).append("\n");
            sb.append("  ").append(AnsiOutput.dim("─────────────────────────────────────")).append("\n");
            sb.append(String.format("  Documentation:  %.0f%% coverage%n",
                quality.documentationCoverage()));
            sb.append(String.format("  Test Ratio:     %.2f:1 (%d tests)%n",
                quality.testRatio(), quality.testFiles()));
            if (quality.deadCodeCandidates() > 0) {
                sb.append(String.format("  Dead Code:      %d candidates%n",
                    quality.deadCodeCandidates()));
            }
            sb.append("\n");
        }

        // Architecture (for DEVELOPER level only)
        if (level == SummaryLevel.DEVELOPER) {
            CodebaseProfile.ArchitectureMetrics arch = profile.architecture();
            sb.append("  ").append(AnsiOutput.bold("Architecture")).append("\n");
            sb.append("  ").append(AnsiOutput.dim("─────────────────────────────────────")).append("\n");
            sb.append(String.format("  Modules:         %d%n", arch.moduleCount()));
            sb.append(String.format("  Avg Refs/File:   %.1f%n", arch.averageRefsPerFile()));
            if (arch.circularDependencies() > 0) {
                sb.append(String.format("  Circular Deps:   %d%n",
                    arch.circularDependencies()));
            }
            if (!arch.topCoupledModules().isEmpty()) {
                sb.append("  Top Coupled:\n");
                arch.topCoupledModules().entrySet().stream()
                    .limit(3)
                    .forEach(e -> sb.append(String.format("    - %s (%d refs)%n",
                        e.getKey(), e.getValue())));
            }
            sb.append("\n");
        }

        // Warnings and Recommendations
        if (!profile.warnings().isEmpty()) {
            sb.append("  ").append(AnsiOutput.bold(AnsiOutput.yellow("Warnings"))).append("\n");
            sb.append("  ").append(AnsiOutput.dim("─────────────────────────────────────")).append("\n");
            profile.warnings().stream().limit(5).forEach(w ->
                sb.append("  ").append(AnsiOutput.yellow("⚠")).append(" ").append(w).append("\n"));
            sb.append("\n");
        }

        if (!profile.recommendations().isEmpty()) {
            sb.append("  ").append(AnsiOutput.bold(AnsiOutput.green("Recommendations"))).append("\n");
            sb.append("  ").append(AnsiOutput.dim("─────────────────────────────────────")).append("\n");
            profile.recommendations().stream().limit(5).forEach(r ->
                sb.append("  ").append(AnsiOutput.green("→")).append(" ").append(r).append("\n"));
            sb.append("\n");
        }
    }

    public String renderMarkdown(SummaryResult result) {
        StringBuilder sb = new StringBuilder();

        sb.append("# Synthesis - Codebase Summary\n\n");
        sb.append("**Level:** ").append(result.level().description()).append("\n");
        sb.append("**Perspective:** ").append(result.perspective().description()).append("\n");
        sb.append("**Generated:** ").append(result.generatedAt()).append("\n\n");

        CodebaseProfile.Profile profile = result.profile();

        // Health Status
        sb.append("## Health Status\n\n");
        for (CodebaseProfile.HealthIndicator h : profile.health()) {
            String statusIcon = switch (h.status()) {
                case "green" -> "🟢";
                case "yellow" -> "🟡";
                case "red" -> "🔴";
                default -> "⚪";
            };
            sb.append("- ").append(statusIcon).append(" **").append(h.category())
              .append(":** ").append(h.detail()).append("\n");
        }
        sb.append("\n");

        // Scale
        CodebaseProfile.ScaleMetrics scale = profile.scale();
        sb.append("## Scale\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append(String.format("| Files | %,d |\n", scale.totalFiles()));
        sb.append(String.format("| Size | %s |\n", formatBytes(scale.totalSizeBytes())));
        sb.append(String.format("| Directories | %,d |\n", scale.directoryCount()));
        sb.append("\n");

        // AI Summary
        if (result.aiSummary() != null) {
            sb.append("## AI Analysis\n\n");
            sb.append(result.aiSummary()).append("\n\n");
        }

        // Warnings and Recommendations
        if (!profile.warnings().isEmpty()) {
            sb.append("## Warnings\n\n");
            profile.warnings().forEach(w -> sb.append("- ⚠️ ").append(w).append("\n"));
            sb.append("\n");
        }

        if (!profile.recommendations().isEmpty()) {
            sb.append("## Recommendations\n\n");
            profile.recommendations().forEach(r -> sb.append("- ✅ ").append(r).append("\n"));
            sb.append("\n");
        }

        return sb.toString();
    }

    public String renderJson(SummaryResult result) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("level", result.level().cliValue());
            root.put("perspective", result.perspective().cliValue());
            root.put("generatedAt", result.generatedAt().toString());
            root.put("generationTimeMs", result.generationTimeMs());
            root.put("fromCache", result.fromCache());

            // Profile
            ObjectNode profileNode = mapper.valueToTree(result.profile());
            root.set("profile", profileNode);

            if (result.aiSummary() != null) {
                root.put("aiSummary", result.aiSummary());
            }

            if (result.temporalContext() != null) {
                root.put("temporalContext", result.temporalContext());
            }

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
