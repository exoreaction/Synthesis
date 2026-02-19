package io.exoreaction.synthesis.summary;

import io.exoreaction.synthesis.summary.CodebaseProfile.Profile;

/**
 * Generates perspective-specific prompts for AI-enhanced summaries.
 *
 * <p>Each perspective receives the same metrics but interprets them
 * through a different lens (executive vs architect vs security, etc.).
 */
public class SummaryPrompts {

    /**
     * Generates a prompt for AI summary based on perspective and level.
     */
    public static String generatePrompt(Profile profile,
                                       SummaryLevel level,
                                       SummaryPerspective perspective) {
        return generatePrompt(profile, level, perspective, null);
    }

    /**
     * Generates a prompt for AI summary, optionally including recent-change context.
     *
     * @param temporalContext compact change summary from ChangeReportGenerator (may be null)
     */
    public static String generatePrompt(Profile profile,
                                       SummaryLevel level,
                                       SummaryPerspective perspective,
                                       String temporalContext) {
        StringBuilder prompt = new StringBuilder();

        // Common context
        prompt.append("You are analyzing a codebase with the following metrics:\n\n");
        appendMetrics(prompt, profile);

        // Recent changes (if --since was provided)
        if (temporalContext != null && !temporalContext.isBlank()) {
            prompt.append("\n\n**Recent Changes:**\n");
            prompt.append(temporalContext).append("\n");
            prompt.append("Please factor these recent changes into your analysis.\n");
        }

        // Perspective-specific instructions
        prompt.append("\n\n");
        appendPerspectiveInstructions(prompt, perspective, level);

        // Length guidance based on level
        prompt.append("\n\n");
        appendLengthGuidance(prompt, level);

        return prompt.toString();
    }

    private static void appendMetrics(StringBuilder sb, Profile profile) {
        var scale = profile.scale();
        var quality = profile.quality();
        var arch = profile.architecture();

        sb.append("**Scale:**\n");
        sb.append("- Total files: ").append(String.format("%,d", scale.totalFiles())).append("\n");
        sb.append("- Total size: ").append(formatBytes(scale.totalSizeBytes())).append("\n");
        sb.append("- Directories: ").append(scale.directoryCount()).append("\n");
        if (!scale.repositories().isEmpty()) {
            sb.append("- Repositories: ").append(scale.repositories().size()).append("\n");
        }

        if (!scale.filesByLanguage().isEmpty()) {
            sb.append("\n**Languages:**\n");
            scale.filesByLanguage().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(e -> sb.append("- ").append(e.getKey()).append(": ")
                    .append(e.getValue()).append(" files\n"));
        }

        sb.append("\n**Quality:**\n");
        sb.append("- Documentation coverage: ").append(String.format("%.0f%%", quality.documentationCoverage())).append("\n");
        sb.append("- Test ratio: ").append(String.format("%.2f:1", quality.testRatio()))
            .append(" (").append(quality.testFiles()).append(" tests, ")
            .append(quality.sourceFiles()).append(" source files)\n");
        sb.append("- Dead code candidates: ").append(quality.deadCodeCandidates()).append("\n");

        sb.append("\n**Architecture:**\n");
        sb.append("- Modules: ").append(arch.moduleCount()).append("\n");
        sb.append("- Circular dependencies: ").append(arch.circularDependencies()).append("\n");
        sb.append("- Layering violations: ").append(arch.layeringViolations()).append("\n");
        sb.append("- Average refs per file: ").append(String.format("%.1f", arch.averageRefsPerFile())).append("\n");

        sb.append("\n**Health Indicators:**\n");
        for (var indicator : profile.health()) {
            sb.append("- ").append(indicator.category()).append(": ")
                .append(indicator.status()).append(" (").append(indicator.detail()).append(")\n");
        }

        if (!profile.warnings().isEmpty()) {
            sb.append("\n**Current Warnings:**\n");
            profile.warnings().stream().limit(5).forEach(w ->
                sb.append("- ").append(w).append("\n"));
        }

        if (!profile.recommendations().isEmpty()) {
            sb.append("\n**System Recommendations:**\n");
            profile.recommendations().stream().limit(5).forEach(r ->
                sb.append("- ").append(r).append("\n"));
        }
    }

    private static void appendPerspectiveInstructions(StringBuilder sb,
                                                     SummaryPerspective perspective,
                                                     SummaryLevel level) {
        String instructions = switch (perspective) {
            case EXECUTIVE -> generateExecutiveInstructions(level);
            case ENGINEERING_MANAGER -> generateEngineeringManagerInstructions(level);
            case ARCHITECT -> generateArchitectInstructions(level);
            case SECURITY -> generateSecurityInstructions(level);
            case DEVOPS -> generateDevOpsInstructions(level);
            case PRODUCT_MANAGER -> generateProductManagerInstructions(level);
            case DEVELOPER -> generateDeveloperInstructions(level);
            default -> generateGeneralInstructions(level);
        };

        sb.append(instructions);
    }

    private static String generateExecutiveInstructions(SummaryLevel level) {
        return """
        **Your Role:** C-level executive evaluating business risk and ROI.

        **Focus on:**
        - Business impact and risk exposure
        - ROI implications of technical debt
        - Strategic decisions needed
        - Competitive positioning
        - Investment priorities

        **Avoid:**
        - Technical jargon
        - Implementation details
        - Specific tools or frameworks

        **Provide:**
        - Overall health assessment (1 sentence)
        - Top 3 business risks
        - Top 2 strategic opportunities
        - Recommended executive action (if any)
        """;
    }

    private static String generateEngineeringManagerInstructions(SummaryLevel level) {
        return """
        **Your Role:** Engineering Manager responsible for team velocity and quality.

        **Focus on:**
        - Team productivity impact
        - Technical debt burden
        - Hiring and onboarding implications
        - Process improvements needed
        - Sprint planning considerations

        **Provide:**
        - Team health assessment
        - Velocity blockers (top 3)
        - Quality risks (top 3)
        - Recommended team actions
        - Estimated effort for improvements
        """;
    }

    private static String generateArchitectInstructions(SummaryLevel level) {
        return """
        **Your Role:** Software Architect ensuring system quality and maintainability.

        **Focus on:**
        - Architectural health and patterns
        - Dependency management
        - Modularity and coupling
        - Design quality
        - Refactoring priorities

        **Provide:**
        - Architecture assessment
        - Critical coupling issues (top 3)
        - Circular dependency impact
        - Refactoring recommendations (prioritized)
        - Long-term architecture strategy
        """;
    }

    private static String generateSecurityInstructions(SummaryLevel level) {
        return """
        **Your Role:** Security Engineer assessing vulnerability surface and compliance.

        **Focus on:**
        - Security risk indicators
        - Dependency vulnerabilities
        - Compliance gaps (GDPR, SOC2, etc.)
        - Credential exposure risk
        - Attack surface

        **Provide:**
        - Security posture assessment
        - Critical vulnerabilities (if detectable)
        - Compliance concerns
        - Recommended security actions
        - Risk mitigation priorities
        """;
    }

    private static String generateDevOpsInstructions(SummaryLevel level) {
        return """
        **Your Role:** DevOps Engineer managing deployment and infrastructure.

        **Focus on:**
        - Build and deployment health
        - CI/CD pipeline risks
        - Infrastructure complexity
        - Deployment frequency impact
        - Monitoring and observability

        **Provide:**
        - Deployment risk assessment
        - Build health concerns
        - Infrastructure optimization opportunities
        - Recommended DevOps improvements
        - Automation priorities
        """;
    }

    private static String generateProductManagerInstructions(SummaryLevel level) {
        return """
        **Your Role:** Product Manager balancing features, quality, and velocity.

        **Focus on:**
        - Feature velocity impact
        - User-facing quality risks
        - Documentation for customers
        - Technical debt vs features trade-offs
        - Release planning implications

        **Provide:**
        - Product velocity assessment
        - User impact risks (top 3)
        - Documentation quality for users
        - Feature vs debt recommendations
        - Release planning considerations
        """;
    }

    private static String generateDeveloperInstructions(SummaryLevel level) {
        return """
        **Your Role:** Senior Developer working in this codebase daily.

        **Focus on:**
        - Code quality and maintainability
        - Developer experience pain points
        - Testing coverage and reliability
        - Hotspots and complexity
        - Refactoring opportunities

        **Provide:**
        - Code quality assessment
        - Top 3 developer pain points
        - Testing improvements needed
        - Refactoring priorities (with file paths)
        - Quick wins for code health
        """;
    }

    private static String generateGeneralInstructions(SummaryLevel level) {
        return """
        **Your Role:** Technical analyst providing balanced overview.

        **Focus on:**
        - Overall codebase health
        - Balance across dimensions
        - Key trends and patterns
        - Actionable improvements

        **Provide:**
        - Health assessment (balanced)
        - Top concerns across all areas
        - Recommended actions (prioritized)
        - Positive highlights
        """;
    }

    private static void appendLengthGuidance(StringBuilder sb, SummaryLevel level) {
        String guidance = switch (level) {
            case EXECUTIVE -> """
                **Length:** 4-6 sentences maximum. Be extremely concise.
                **Tone:** Executive summary style - clear, direct, action-oriented.
                """;
            case MANAGER -> """
                **Length:** 2-3 short paragraphs (10-15 sentences total).
                **Tone:** Manager briefing style - actionable, prioritized, effort-aware.
                """;
            case DEVELOPER -> """
                **Length:** 3-5 paragraphs with specific details.
                **Tone:** Technical peer style - specific, detailed, implementation-aware.
                Include file paths and concrete examples where relevant.
                """;
        };

        sb.append(guidance);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
