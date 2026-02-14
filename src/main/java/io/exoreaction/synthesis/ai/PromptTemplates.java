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

    // --- Vision prompts ---

    /**
     * Prompt for describing an image for search indexing.
     * Used by the vision integration to generate searchable descriptions.
     */
    public static final String IMAGE_DESCRIPTION = """
            Describe this image concisely for a file search index. Include:
            1. What the image shows (subject, scene, content)
            2. Type of image (screenshot, diagram, photo, chart, logo, UI mockup, etc.)
            3. Key text visible in the image (if any)
            4. Technical details if relevant (architecture diagram, flowchart, data visualization)

            Respond with a concise description (2-4 sentences) followed by 5-10 keywords \
            on a separate line prefixed with "Keywords: ".

            Example format:
            Screenshot of a Java IDE showing a Spring Boot application with test results. \
            The code editor displays a REST controller with 3 endpoints. Test panel shows 42 passing tests.
            Keywords: screenshot, IDE, Java, Spring Boot, REST API, testing, code editor
            """;

    /**
     * Prompt for describing a PDF slide for search indexing.
     * Used when extracting slides from presentation PDFs.
     */
    public static final String SLIDE_DESCRIPTION = """
            Describe this presentation slide concisely for a search index. Include:
            1. Slide title and main message
            2. Key bullet points or data shown
            3. Any charts, diagrams, or images on the slide
            4. The context/topic of the presentation if discernible

            Respond with a concise description (2-3 sentences) followed by keywords.
            Format: Description text.
            Keywords: keyword1, keyword2, ...
            """;

    // --- Directed Synthesis prompts ---

    /**
     * Prompt template for generating analytical perspectives on a question.
     */
    private static final String PERSPECTIVES_TEMPLATE = """
            You are an analytical reasoning engine. Given a question and workspace context, \
            generate %d distinct analytical perspectives. Each perspective should examine the \
            question through a different lens.

            WORKSPACE CONTEXT:
            %s

            QUESTION: %s

            For each perspective, provide:
            ## Perspective [N]: [Lens Name]
            **Approach:** 1-sentence description of this analytical angle
            **Analysis:** 3-5 sentences examining the question through this lens
            **Key Insight:** 1 sentence capturing the unique insight from this perspective
            **Confidence:** High/Medium/Low based on available evidence

            After all perspectives, provide:
            ## Synthesis
            A 2-3 sentence synthesis combining the most valuable insights across perspectives.

            Perspectives to consider (use the most relevant %d):
            - Technical feasibility and implementation complexity
            - Business impact and ROI
            - Risk and trade-off analysis
            - Historical precedent and patterns
            - Team and organizational impact
            - Scalability and maintenance burden
            - Security and compliance implications
            - User experience impact
            """;

    /**
     * Builds a prompt for generating multiple analytical perspectives.
     *
     * @param question     the user's question
     * @param context      workspace context from search results
     * @param numPerspectives number of perspectives to generate (typically 3-5)
     * @return the formatted prompt
     */
    public static String buildPerspectivesPrompt(String question, String context, int numPerspectives) {
        return PERSPECTIVES_TEMPLATE.formatted(numPerspectives, context, question, numPerspectives);
    }

    /**
     * Prompt template for comparing two approaches or options.
     */
    private static final String COMPARISON_TEMPLATE = """
            You are a technical comparison analyst. Compare the following options \
            based on the workspace context provided.

            WORKSPACE CONTEXT:
            %s

            COMPARISON REQUEST: %s

            Provide a structured comparison:

            ## Option Analysis
            For each option identified:
            - **Description:** What this option entails
            - **Pros:** Key advantages (bullet points)
            - **Cons:** Key disadvantages (bullet points)
            - **Effort:** Estimated effort (Low/Medium/High)
            - **Risk:** Risk level (Low/Medium/High)

            ## Recommendation
            Which option best fits the current context and why (2-3 sentences).

            ## Decision Factors
            Key factors that could change this recommendation.
            """;

    /**
     * Builds a prompt for comparing options/approaches.
     *
     * @param question the comparison question
     * @param context  workspace context
     * @return the formatted prompt
     */
    public static String buildComparisonPrompt(String question, String context) {
        return COMPARISON_TEMPLATE.formatted(context, question);
    }

    /**
     * Prompt template for impact analysis (what-if scenarios).
     */
    private static final String IMPACT_TEMPLATE = """
            You are a systems thinking analyst. Analyze the potential impact of the \
            proposed change or decision based on the workspace context.

            WORKSPACE CONTEXT:
            %s

            CHANGE/DECISION: %s

            Provide an impact analysis:

            ## Direct Effects
            Immediate consequences of this change (3-5 bullet points).

            ## Ripple Effects
            Secondary and tertiary effects across the codebase/organization (3-5 points).

            ## Dependencies Affected
            Which components, files, or teams would be impacted.

            ## Risk Assessment
            - **Probability of Issues:** High/Medium/Low
            - **Severity if Issues Occur:** High/Medium/Low
            - **Reversibility:** Easy/Moderate/Difficult

            ## Recommended Approach
            How to implement this change safely (2-3 sentences).
            """;

    /**
     * Builds a prompt for impact analysis.
     *
     * @param question the change/decision to analyze
     * @param context  workspace context
     * @return the formatted prompt
     */
    public static String buildImpactPrompt(String question, String context) {
        return IMPACT_TEMPLATE.formatted(context, question);
    }

    /**
     * Prompt template for gap analysis.
     */
    private static final String GAP_ANALYSIS_TEMPLATE = """
            You are a strategic analyst. Identify gaps, missing pieces, and opportunities \
            based on the workspace context and the question.

            WORKSPACE CONTEXT:
            %s

            QUESTION: %s

            Provide a gap analysis:

            ## Current State
            What exists today based on the evidence (3-5 bullet points).

            ## Gaps Identified
            What is missing, incomplete, or could be improved (3-5 items).
            For each gap:
            - **Gap:** Description
            - **Impact:** Why this matters
            - **Effort to Address:** Low/Medium/High

            ## Opportunities
            Hidden opportunities revealed by this analysis (2-3 items).

            ## Priority Ranking
            Ranked list of gaps to address first, with rationale.
            """;

    /**
     * Builds a prompt for gap analysis.
     *
     * @param question the area to analyze for gaps
     * @param context  workspace context
     * @return the formatted prompt
     */
    public static String buildGapAnalysisPrompt(String question, String context) {
        return GAP_ANALYSIS_TEMPLATE.formatted(context, question);
    }
}
