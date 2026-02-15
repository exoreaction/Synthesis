# Feature: MCP `ask` Tool -- AI Q&A for AI Agents

**Status:** Implemented (v1.0.4-SNAPSHOT)
**Priority:** P1 (Build Now)
**Effort:** 3-5 days (estimated) / completed
**Revenue Impact:** 30-50K NOK (SpareBank 1 Claude Code value + workshop killer demo)

> **Implementation Note (Feb 2026):** This feature is fully implemented in `SynthesisToolHandler.handleAsk()` with the `ask` MCP tool registered in `SynthesisMCPServer`. The implementation uses `query` (not `question`) as the required parameter, searches the index for top 10 relevant files, builds context with line-numbered content, and returns structured answers with citations. Tests in `McpToolHandlerExtensionsTest` (12 tests).

---

## Problem Statement

Claude Code, Cursor, and other AI agents can search the Synthesis index via MCP (`search`, `relate`, `graph`, `stats`) but cannot ask natural-language questions about the codebase. The `synthesis ask` CLI command is powerful and fully implemented, but it is only accessible from the terminal. AI agents need programmatic access to AI Q&A to make informed decisions about code changes.

**Impact:** SpareBank 1 rolled out Claude Code org-wide to 200 developers. They want Claude Code to understand their codebase. Without the MCP `ask` tool, Claude Code can search but cannot reason about what it finds. The gap between "find files" and "understand codebase" is where the real value lies.

**Competitive advantage:** No other MCP server offers AI-powered Q&A with structured file citations. This is immediate differentiation that competitors cannot easily replicate.

## Solution Overview

Add a fifth MCP tool (`ask`) to the existing `SynthesisMCPServer`. The tool accepts a natural-language question, gathers relevant context from the Synthesis index, sends it to Claude with the workspace context, and returns a structured answer with file citations.

## Architecture

```
AI Agent (Claude Code / Cursor / Aider)
    |
    | MCP Protocol: tools/call "ask"
    v
+------------------------------+
| SynthesisMCPServer           |
| handleToolsCall() dispatch   |
+------------------------------+
    |
    v
+------------------------------+
| SynthesisToolHandler         |
| handleAsk(params)            |
+------------------------------+
    |
    +-----> SearchIndex.search(question, 8)
    |           -> top 8 relevant files
    |
    +-----> Read file content (first 4KB each)
    |           -> numbered line content
    |
    +-----> PromptTemplates.buildAskPrompt()
    |           -> structured prompt with context
    |
    +-----> ClaudeClient.generate(prompt, maxTokens)
    |           -> AI-generated answer
    |
    v
JSON Response to AI Agent:
{
  "answer": "Authentication is handled in LoginController.java...",
  "sources": [
    {"path": "src/auth/LoginController.java", "relevance": 0.95},
    {"path": "docs/auth-flow.md", "relevance": 0.82}
  ],
  "model": "claude-sonnet-4-5-20250929",
  "contextDocuments": 8,
  "responseTimeMs": 2300
}
```

## API Design

### MCP Tool Definition
```json
{
  "name": "ask",
  "description": "Ask a natural-language question about the workspace. Uses AI to analyze indexed content and provide answers with file citations. Requires ANTHROPIC_API_KEY environment variable.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "question": {
        "type": "string",
        "description": "Natural-language question about the codebase, architecture, or documentation"
      },
      "contextFiles": {
        "type": "number",
        "default": 8,
        "description": "Number of relevant files to include as context (1-20). More context = better answers but slower."
      },
      "maxTokens": {
        "type": "number",
        "default": 2048,
        "description": "Maximum tokens in AI response (256-4096)"
      },
      "workspace": {
        "type": "string",
        "description": "Workspace path (defaults to server's configured workspace)"
      }
    },
    "required": ["question"]
  }
}
```

### Response Format
```json
{
  "answer": "The authentication system uses JWT tokens...",
  "sources": [
    {
      "path": "src/main/java/auth/JwtService.java",
      "relativePath": "auth/JwtService.java",
      "relevance": 0.95,
      "language": "Java"
    }
  ],
  "model": "claude-sonnet-4-5-20250929",
  "contextDocuments": 8,
  "responseTimeMs": 2300,
  "tokensUsed": 1847
}
```

### Error Response (No API Key)
```json
{
  "error": "AI Q&A requires ANTHROPIC_API_KEY. Set the environment variable and restart the MCP server.",
  "suggestion": "For keyword search without AI, use the 'search' tool instead."
}
```

## Implementation Details

### Modified Files

**1. `SynthesisToolHandler.java`** -- Add handleAsk() method (~80 lines)

```java
/**
 * Handles the MCP 'ask' tool -- AI-powered Q&A about the workspace.
 *
 * Steps:
 * 1. Parse question and parameters
 * 2. Search index for relevant files
 * 3. Read file content (first 4KB each)
 * 4. Build prompt with context
 * 5. Call Claude API
 * 6. Return structured response
 */
public ObjectNode handleAsk(JsonNode args) throws McpToolException {
    String question = requireString(args, "question");
    int contextFiles = optionalInt(args, "contextFiles", 8);
    int maxTokens = optionalInt(args, "maxTokens", 2048);

    // Validate AI availability
    if (claudeClient == null) {
        throw new McpToolException(INTERNAL_ERROR,
            "AI Q&A requires ANTHROPIC_API_KEY. Set the environment variable.");
    }

    // Search for relevant context
    List<SearchResult> results = searchIndex.search(question, contextFiles);

    // Build context (reuse AskCommand.buildContext logic)
    StringBuilder context = buildAskContext(results);

    // Build prompt
    String prompt = PromptTemplates.buildAskPrompt(question, context.toString());

    // Call Claude
    long start = System.currentTimeMillis();
    String answer = claudeClient.generate(prompt, maxTokens);
    long duration = System.currentTimeMillis() - start;

    // Build structured response
    ObjectNode response = mapper.createObjectNode();
    response.put("answer", answer);
    // ... sources, model, timing
    return response;
}
```

**2. `SynthesisMCPServer.java`** -- Add ask tool definition

```java
// In handleToolsList():
toolsArray.add(createToolDefinition(
    "ask",
    "Ask a natural-language question about the workspace. " +
    "Returns AI-generated answer with file citations. " +
    "Requires ANTHROPIC_API_KEY.",
    createAskSchema()
));

// In handleToolsCall() switch:
case "ask" -> toolHandler.handleAsk(toolArgs);
```

**3. `SynthesisToolHandler` constructor** -- Add optional ClaudeClient

```java
// Load AI config and create client if API key available
private final ClaudeClient claudeClient;  // null if no API key

public SynthesisToolHandler(ObjectMapper mapper, Path workspace) {
    // ... existing init ...
    this.claudeClient = createClaudeClientIfAvailable();
}
```

### Key Design Decisions

1. **Graceful degradation:** If no API key, the tool is still registered but returns a helpful error. This is better than not registering the tool (which would confuse agents).

2. **Reuse existing logic:** Extract `buildContext()` from `AskCommand` into a shared utility so both CLI and MCP use the same context-gathering logic.

3. **Rate limiting:** Max 10 asks per minute to prevent runaway API costs. Return informative error when limit hit.

4. **Structured sources:** Include file paths and relevance scores so AI agents can follow up with `relate` or `search` tools.

## Testing Strategy

1. **Unit test:** handleAsk with mock ClaudeClient -- verify prompt construction and response format
2. **Integration test:** Full MCP protocol flow (initialize -> tools/list -> tools/call ask)
3. **Error cases:** No API key, empty index, malformed question, rate limit exceeded
4. **Response format:** Verify JSON structure matches schema
5. **Cost estimation:** Verify rate limiting prevents runaway costs

## Rollout Plan

1. **Day 1-2:** handleAsk implementation + tool definition
2. **Day 3:** Testing + error handling + rate limiting
3. **Day 4-5:** Documentation update (MCP protocol reference, Claude Code integration guide)
4. **Same week:** Update Claude Code config at SpareBank 1 pilot

## Dependencies

- ANTHROPIC_API_KEY (same as existing `synthesis ask` command)
- ClaudeClient already implemented and tested
- PromptTemplates.buildAskPrompt already exists
