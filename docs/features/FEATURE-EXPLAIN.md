# Feature: `synthesis explain` -- AI-Powered Code Explanations

**Status:** Implemented (v1.0.4-SNAPSHOT)
**Priority:** P1 (Build Now)
**Effort:** 1 week (estimated) / completed
**Revenue Impact:** 20-40K NOK (Workshop demo tool + onboarding acceleration)

> **Implementation Note (Feb 2026):** This feature is fully implemented with CLI command (`synthesis explain`), MCP tool (`explain`), and CodeExplainer engine supporting file/module/pattern modes at brief/standard/deep depths. The MCP tool auto-detects the mode based on the target path.

---

## Problem Statement

Understanding unfamiliar code consumes 40-60% of developer time during onboarding. Synthesis already helps developers *find* code (`search`), *navigate* relationships (`relate`), and *answer questions* (`ask`). But none of these explain *what code does* or *why it exists* at a module or pattern level.

**The gap:** `search` finds files. `relate` shows connections. `ask` answers specific questions. But nobody answers: "I just opened this directory for the first time. What is it? How does it work? Where do I start reading?"

**Impact:** New developers at SpareBank 1 (200 devs, with turnover) spend their first 2-4 weeks building mental models of the codebase. `explain` compresses that to hours. For workshops (Item Consulting, 30 devs), `explain` is the killer demo: "Watch Synthesis explain YOUR codebase in 30 seconds."

## Solution Overview

A new command that generates comprehensive natural-language explanations at three granularity levels:

1. **File:** Purpose, key components, how it works, dependencies, usage
2. **Module:** Architecture, key files, entry points, conventions
3. **Pattern:** Cross-cutting concern implementation across multiple files

Uses the Synthesis index as context to ground explanations in actual workspace structure (relationships, metrics, related files).

## Architecture

```
synthesis explain --file src/auth/LoginController.java
    |
    v
+-----------------------------+
| Context Assembly             |
| 1. Read file content         |
| 2. Query relationships       |
| 3. Query insights metrics    |
| 4. Gather module context     |
+-----------------------------+
    |
    v
+-----------------------------+
| Prompt Construction          |
| depth=brief: 3-5 sentences  |
| depth=standard: structured   |
| depth=deep: comprehensive    |
+-----------------------------+
    |
    v
+-----------------------------+
| ClaudeClient.generate()      |
+-----------------------------+
    |
    v
Structured Output:

  ## Purpose
  LoginController handles user authentication via JWT tokens.
  It validates credentials against UserRepository and issues
  time-limited tokens for subsequent API calls.

  ## Key Components
  - login(credentials) - Main entry point, validates + issues token
  - refreshToken(token) - Extends session without re-authentication
  - logout(token) - Invalidates token in blacklist cache

  ## How It Works
  Lines 42-58: Credential validation against BCrypt hashes
  Lines 60-75: JWT token generation with 15-minute expiry
  Lines 77-90: Token refresh with sliding window

  ## Dependencies
  - JwtService.java (token operations)
  - UserRepository.java (credential lookup)
  - SecurityConfig.java (BCrypt rounds, token expiry)

  ## Usage
  Called by SecurityFilter on every /api/** request.
  Referenced by 12 test files.
```

## API Design

### CLI
```
synthesis explain --file <path>              # Explain a single file
synthesis explain --module <directory>        # Explain a module/package
synthesis explain --pattern "authentication"  # Explain a cross-cutting concept
synthesis explain --file <path> --depth brief    # Quick overview (3-5 sentences)
synthesis explain --file <path> --depth standard # Structured sections (default)
synthesis explain --file <path> --depth deep     # Comprehensive deep-dive
synthesis explain --file <path> --format json    # Machine-readable output
synthesis explain --file <path> --format markdown # Markdown output
```

### MCP Tool
```json
{
  "name": "explain",
  "description": "Generate an AI-powered explanation of a file, module, or architectural pattern. Returns structured natural-language explanation with file citations.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "target": {
        "type": "string",
        "description": "File path, directory path, or pattern name to explain"
      },
      "mode": {
        "type": "string",
        "enum": ["file", "module", "pattern"],
        "default": "file",
        "description": "Explanation granularity"
      },
      "depth": {
        "type": "string",
        "enum": ["brief", "standard", "deep"],
        "default": "standard",
        "description": "Explanation detail level"
      },
      "workspace": {
        "type": "string",
        "description": "Workspace path"
      }
    },
    "required": ["target"]
  }
}
```

### LSP Integration (future)
- **Code Lens:** "Explain this file" lens at top of every file
- **Hover:** Brief explanation when hovering on import/require statements
- **Command Palette:** "Synthesis: Explain Current File" command

## Implementation Details

### New Files
| File | Lines (est.) | Purpose |
|------|-------------|---------|
| `ai/CodeExplainer.java` | ~350 | Explanation engine with 3 modes |
| `cli/ExplainCommand.java` | ~200 | CLI command with picocli |

### Modified Files
| File | Change |
|------|--------|
| `ai/PromptTemplates.java` | Add EXPLAIN_FILE, EXPLAIN_MODULE, EXPLAIN_PATTERN templates |
| `SynthesisApp.java` | Register ExplainCommand, add to AI_COMMAND_NAMES |
| `SynthesisToolHandler.java` | Add handleExplain() for MCP |
| `SynthesisMCPServer.java` | Add explain tool definition |

### Prompt Design

The prompt quality is critical. Key principles:

1. **Ground in actual code:** Always include file content with line numbers
2. **Include relationships:** Show what the file depends on and what depends on it
3. **Include context:** Show other files in the same module/directory
4. **Structured output:** Request specific sections for consistent format
5. **Reference line numbers:** Enables users to jump to specific code

**File Prompt (standard depth):**
```
You are explaining a source file to a developer who is new to this codebase.
Be specific, reference line numbers, and ground your explanation in the actual code.

FILE: src/main/java/auth/LoginController.java
LANGUAGE: Java
SIZE: 4.2 KB

FILE CONTENT:
[numbered content, first 8KB]

RELATIONSHIPS:
Outgoing: JwtService.java, UserRepository.java, SecurityConfig.java
Incoming: SecurityFilter.java, AuthTest.java, LoginControllerTest.java

MODULE CONTEXT:
src/main/java/auth/JwtService.java (Java): JWT token operations
src/main/java/auth/UserRepository.java (Java): User credential store
src/main/java/auth/SecurityFilter.java (Java): Request authentication filter

Provide a structured explanation:

## Purpose
What does this file do? Why does it exist? (2-3 sentences)

## Key Components
List the most important classes/functions and what they do.

## How It Works
Explain the main logic flow. Reference specific line numbers.

## Dependencies
What does it depend on and why?

## Usage
How is this file used by other parts of the codebase?
```

### Response Time Optimization

Target: <3 seconds for standard depth.

Strategies:
1. Use Sonnet (fast) by default, not Opus (thorough)
2. Limit file content to 8KB (covers 95% of files)
3. Limit module context to 20 files max
4. Cache recent explanations (LRU, 100 entries, 10-minute TTL)
5. Pre-fetch relationships during index query

## Testing Strategy

1. **Unit tests:** Prompt construction with mock data for each mode/depth combo
2. **Integration tests:** Full explain cycle on Synthesis's own source files
3. **Output format:** Verify JSON mode produces valid JSON
4. **Edge cases:**
   - Binary files (should handle gracefully)
   - Empty files
   - Very large files (verify truncation)
   - Non-existent files (clear error message)
   - Directories with >100 files (verify truncation)
5. **Quality tests:** Verify explanations reference actual line numbers from content

## Rollout Plan

1. **Day 1-2:** CodeExplainer with file mode + PromptTemplates
2. **Day 3:** Module mode + pattern mode
3. **Day 4:** ExplainCommand CLI
4. **Day 5:** MCP tool integration + testing
5. **Day 6-7:** Documentation + integration testing

## Dependencies

- ANTHROPIC_API_KEY (same as `synthesis ask`)
- ClaudeClient (fully implemented)
- SearchIndex (for context gathering)
- InsightsEngine (for architecture context in deep mode)
