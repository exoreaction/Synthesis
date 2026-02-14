package io.exoreaction.synthesis.ai;

/**
 * Centralized prompt templates for all AI operations.
 * Keeping all prompts in one place makes them maintainable and reviewable.
 *
 * <p>These prompts are ported from the Python implementation at
 * ~/Documents/Synthesis/automation/readme-generator/generate-readme.py
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    /**
     * Prompt for generating a README.md for a directory.
     * Expects the following variables to be interpolated:
     * - directoryPath: relative path of the directory
     * - directoryContents: listing of files and subdirectories
     * - fileSnippets: content previews of key files
     */
    public static final String README_GENERATION = """
            You are a technical documentation expert. Generate a clear, concise README.md \
            for the following directory.

            Directory: %s

            Contents:
            %s

            File previews:
            %s

            Instructions:
            1. Start with a clear H1 title that describes the directory's purpose
            2. Add a brief description (2-3 sentences) explaining what this directory contains
            3. If there are subdirectories, describe their purpose
            4. If there are code files, briefly describe what they do
            5. If you can identify patterns (tests, configs, scripts), mention them
            6. Keep it concise -- this is a navigational aid, not full documentation
            7. Use standard Markdown formatting
            8. Do NOT include a table of contents for small directories
            9. If the directory purpose is unclear, write "SKIP: No clear purpose" and nothing else

            Generate ONLY the README.md content, no explanations or preamble.
            """;

    /**
     * Prompt for generating a brief content summary for search index enrichment.
     * Used to create human-readable summaries stored alongside the index.
     */
    public static final String CONTENT_SUMMARY = """
            Summarize this file in 1-2 sentences. Focus on what it does or contains, \
            not how it's structured. Be specific and useful for someone searching for this file.

            File: %s
            Content:
            %s

            Summary (1-2 sentences only):
            """;

    /**
     * Prompt template for AI-powered Q&A about workspace contents.
     * The context is pre-built with file content snippets and line numbers.
     */
    private static final String ASK_TEMPLATE = """
            You are a knowledgeable assistant answering questions about a codebase/workspace.

            The user has a workspace with indexed files. Below is relevant context from files \
            that matched their question. Each file section shows the relative path and line-numbered \
            content.

            CONTEXT:
            %s

            QUESTION: %s

            Instructions:
            1. Answer the question based on the provided file context
            2. Cite specific files and line numbers when referencing code or content (e.g., "In PluginRegistry.java:L42")
            3. If the context doesn't contain enough information to fully answer, say so
            4. Be concise but thorough
            5. If the question is about code, explain the relevant patterns and architecture
            6. Use Markdown formatting for code snippets in your answer

            Answer:
            """;

    /**
     * Builds a prompt for the ask command.
     *
     * @param question the user's question
     * @param context  file content context with line numbers
     * @return the formatted prompt
     */
    public static String buildAskPrompt(String question, String context) {
        return ASK_TEMPLATE.formatted(context, question);
    }

    /**
     * Prompt template for AI-powered project analysis.
     */
    private static final String ANALYZE_TEMPLATE = """
            You are a senior software architect reviewing a project workspace.

            Analyze the following workspace statistics and file samples. Identify issues, \
            patterns, and provide actionable recommendations.

            WORKSPACE STATISTICS:
            %s

            FILE SAMPLES:
            %s

            Provide your analysis in the following format:

            ## Project Structure
            - Describe the overall architecture and organization

            ## Strengths
            - What's well-organized or follows good practices

            ## Issues Found
            - Missing documentation (directories without README, undocumented code)
            - Test coverage gaps (code directories without corresponding test files)
            - Code smells (large files, unclear naming, potential issues)
            - Configuration problems

            ## Recommendations
            - Prioritized list of improvements
            - Quick wins vs longer-term improvements

            Be specific. Reference actual file paths. Prioritize actionable insights.
            """;

    /**
     * Builds a prompt for the analyze command.
     *
     * @param statistics workspace statistics summary
     * @param samples    file content samples
     * @return the formatted prompt
     */
    public static String buildAnalyzePrompt(String statistics, String samples) {
        return ANALYZE_TEMPLATE.formatted(statistics, samples);
    }

    /**
     * Prompt template for generating architecture documentation.
     */
    private static final String ARCHITECTURE_DOC_TEMPLATE = """
            You are a technical writer creating architecture documentation for a software project.

            Based on the following workspace index, generate a comprehensive architecture document.

            WORKSPACE INFO:
            Name: %s
            Type: %s
            Root: %s

            FILE INDEX:
            %s

            Generate an architecture document with:

            ## Overview
            - Project purpose and scope (inferred from files)

            ## Directory Structure
            - Explain what each major directory contains and why

            ## Key Components
            - Identify and describe the main modules/components
            - How they relate to each other

            ## Technology Stack
            - Languages, frameworks, tools detected

            ## Data Flow
            - How data moves through the system (if discernible)

            ## Entry Points
            - Main classes, scripts, or configuration files

            Write in clear, professional prose. Reference specific files.
            """;

    /**
     * Builds a prompt for architecture document generation.
     */
    public static String buildArchitectureDocPrompt(String name, String type, String root, String fileIndex) {
        return ARCHITECTURE_DOC_TEMPLATE.formatted(name, type, root, fileIndex);
    }

    /**
     * Prompt template for generating onboarding guide.
     */
    private static final String ONBOARDING_GUIDE_TEMPLATE = """
            You are creating an onboarding guide for a new developer joining a project.

            Based on the following workspace index, generate a friendly but thorough onboarding guide.

            WORKSPACE INFO:
            Name: %s
            Type: %s
            Root: %s

            FILE INDEX:
            %s

            Generate an onboarding guide with:

            ## Welcome
            - Brief project introduction

            ## Getting Started
            - Key files to read first
            - How to build/run the project (inferred from build files)

            ## Project Layout
            - Directory-by-directory tour
            - Where to find things

            ## Key Concepts
            - Important patterns and conventions used

            ## Common Tasks
            - Where to add new features
            - Where tests live
            - Configuration locations

            ## Resources
            - Important documentation files
            - README locations

            Write in a welcoming, practical tone. Reference specific files and paths.
            """;

    /**
     * Builds a prompt for onboarding guide generation.
     */
    public static String buildOnboardingGuidePrompt(String name, String type, String root, String fileIndex) {
        return ONBOARDING_GUIDE_TEMPLATE.formatted(name, type, root, fileIndex);
    }
}
