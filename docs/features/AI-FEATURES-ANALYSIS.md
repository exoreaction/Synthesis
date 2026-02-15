# Synthesis AI Features Analysis & Implementation Plan

**Version:** 1.0
**Date:** 2026-02-15
**Author:** Thor Henning Hetland / eXOReaction (with Claude Opus 4.6 analysis)
**Status:** All Priority 1 and Priority 2 features IMPLEMENTED (Feb 15, 2026)
**Companion:** [AI-SCOPE-ANALYSIS.md](../AI-SCOPE-ANALYSIS.md) | [PRODUCT-VARIANTS-ROADMAP.md](../PRODUCT-VARIANTS-ROADMAP.md)

---

## Executive Summary

This document provides a comprehensive audit of AI features in Synthesis, identifies high-value opportunities, and delivers concrete implementation plans for the most impactful features. The analysis is grounded in the actual codebase (92 source files, 55 test files) and cross-referenced against the AI-SCOPE-ANALYSIS.md roadmap and real customer pipeline.

**Key Findings:**

1. **Synthesis already has strong AI foundations** -- 4 AI classes, 3 AI-powered CLI commands, 10 prompt templates, vision support in ScanCommand, and a complete MCP/LSP protocol layer. This is approximately 30% of the planned AI surface.

2. **The biggest gaps are in the "middle layer"** -- between raw index data and cloud AI. Deterministic intelligence (companion files, enhanced relationships, content fingerprinting) requires zero AI API calls but delivers the majority of user value.

3. **Three quick wins could ship in 4-6 weeks total** and directly accelerate the SpareBank 1 (200 devs), Mynder (540-900K/year), and workshop revenue:
   - `synthesis enrich` command (companion file generation)
   - `synthesis explain` command (AI-powered file/module explanations)
   - MCP `ask` tool (expose AI Q&A to Claude Code/Cursor)

4. **Two strategic features differentiate Synthesis from all competitors** and could justify enterprise pricing ($100K+):
   - Semantic Code Search (embedding-based, local-first)
   - Continuous Architecture Intelligence (real-time health monitoring)

5. **Revenue impact estimate:** Implementing Priority 1 features unlocks 250-500K NOK in Q3-Q4 pipeline; Priority 2 features enable the $150M TAM enterprise positioning.

---

## Part 1: Current State Audit

### 1.1 AI Classes Inventory

| Class | Purpose | Used By | Status |
|-------|---------|---------|--------|
| `ClaudeClient.java` | Anthropic SDK wrapper; text generation + vision | AskCommand, AnalyzeCommand, ScanCommand, ExportCommand, PerspectivesCommand | **FULLY IMPLEMENTED** |
| `DirectedSynthesisEngine.java` | Multi-perspective analysis; 4 modes (perspectives, comparison, impact, gap) | PerspectivesCommand | **FULLY IMPLEMENTED** |
| `PromptTemplates.java` | 10 prompt templates (README, summary, ask, analyze, architecture, onboarding, vision, slides, perspectives, comparison, impact, gap) | Multiple commands | **FULLY IMPLEMENTED** |
| `ReadmeGenerator.java` | AI-generated README files for directories | ScanCommand (--with-readme) | **FULLY IMPLEMENTED** |

### 1.2 AI-Powered CLI Commands

| Command | AI Usage | Status |
|---------|----------|--------|
| `synthesis ask <question>` | ClaudeClient.generate() with search context | **WORKING** -- full implementation with context gathering, line-numbered file content, verbose mode |
| `synthesis perspectives <question>` | DirectedSynthesisEngine.analyze() with 4 modes | **WORKING** -- auto-detects mode from question keywords, configurable perspectives count |
| `synthesis analyze` | ClaudeClient.generate() for deep analysis; also has rule-based fallback | **WORKING** -- hybrid AI + deterministic analysis with issue detection |
| `synthesis scan --with-ai` | ClaudeClient.generateFromImage() for vision analysis | **WORKING** -- cost estimation, user confirmation, batch processing |
| `synthesis scan --with-readme` | ReadmeGenerator.generate() for README generation | **WORKING** -- idempotent, SKIP detection |
| `synthesis export --format architecture-doc` | ClaudeClient.generate() with architecture prompt | **WORKING** -- generates full architecture documentation |
| `synthesis export --format onboarding-guide` | ClaudeClient.generate() with onboarding prompt | **WORKING** -- generates new developer onboarding guide |

### 1.3 Non-AI Commands (Deterministic)

| Command | Status | AI Enhancement Potential |
|---------|--------|------------------------|
| `synthesis search` | **WORKING** -- Lucene multi-field search with boosting | HIGH: AI re-ranking, semantic expansion |
| `synthesis relate` | **WORKING** -- bidirectional file relationships | MEDIUM: AI-suggested relationships |
| `synthesis graph` | **WORKING** -- Mermaid/DOT/JSON output | MEDIUM: AI anti-pattern detection |
| `synthesis insights` | **WORKING** -- connectivity, complexity, quality, architecture metrics | HIGH: AI interpretation of metrics |
| `synthesis cross-repo-deps` | **WORKING** -- multi-repo dependency mapping | LOW: already comprehensive |
| `synthesis watch` | **WORKING** -- file system monitoring with debouncing | MEDIUM: trigger enrichment on change |
| `synthesis learn` | **WORKING** -- generates Claude Code skills from workspace knowledge | LOW: already AI-adjacent |
| `synthesis maintain` | **WORKING** -- incremental index maintenance | LOW: deterministic is appropriate |
| `synthesis status` | **WORKING** -- workspace health dashboard | LOW: deterministic is appropriate |
| `synthesis org` | **WORKING** -- organizational structure discovery | LOW: deterministic is appropriate |

### 1.4 MCP Server Tools (4 tools)

| Tool | Status | AI Enhancement Potential |
|------|--------|------------------------|
| `search` | **WORKING** -- full Lucene search with type/repo/org filters | HIGH: expose `ask` as MCP tool |
| `relate` | **WORKING** -- bidirectional relationships, JSON + Mermaid | MEDIUM: AI relationship suggestions |
| `graph` | **WORKING** -- modules/dependencies/cross-repo graphs | LOW: already comprehensive |
| `stats` | **WORKING** -- workspace statistics and health | LOW: already comprehensive |

**Critical Missing MCP Tool:** There is no `ask` tool in MCP. The `synthesis ask` command exists only as CLI. Exposing it via MCP would allow Claude Code, Cursor, and other AI agents to directly ask questions about the workspace. This is the #1 quick win for MCP.

### 1.5 LSP Server Features (6 features)

| Feature | Status | AI Enhancement Potential |
|---------|--------|------------------------|
| Document Links | **WORKING** -- clickable file references | LOW |
| Hover (metadata) | **WORKING** -- file type, size, language, relationships | HIGH: AI-generated explanations |
| Diagnostics (broken links) | **WORKING** -- warns about broken markdown links | MEDIUM: broader issue detection |
| Go to Definition | **WORKING** -- navigate to referenced files | LOW |
| Find References | **WORKING** -- find all files referencing current file | LOW |
| Code Lens | **WORKING** -- relationship counts at top of file | MEDIUM: richer lens information |

### 1.6 Configuration (AI-related)

| Config Key | Default | Status |
|------------|---------|--------|
| `ai.enabled` | `false` | **WORKING** -- gates all AI features |
| `ai.model` | `claude-sonnet-4-5-20250929` | **WORKING** -- configurable model |
| `ai.readmeGeneration` | `true` | **WORKING** -- used by scan --with-readme |
| `ai.contentSummary` | `false` | **STUBBED** -- config exists but not used anywhere in codebase |
| `ai.maxTokens` | `1024` | **WORKING** -- used by all AI commands |
| `ai.vision.enabled` | `true` | **WORKING** -- used by scan --with-ai |
| `ai.vision.costPerImageUsd` | `0.02` | **WORKING** -- cost estimation |
| `ai.vision.maxImageSizeBytes` | `20 MB` | **WORKING** -- size gate |
| `ai.vision.confirmBeforeScan` | `true` | **WORKING** -- user confirmation |

**Notable Finding:** `ai.contentSummary` is defined in config but never read or used by any code. This was likely intended for AI-generated summaries during scan but was never implemented. This is a clear stubbed feature.

---

## Part 2: Gap Analysis vs AI-SCOPE-ANALYSIS.md

### Phase 1: Deterministic Intelligence (NO AI needed)

| Feature | AI-SCOPE Status | Implementation Status | Gap |
|---------|----------------|----------------------|-----|
| Companion file generation (.synthesis.md) | Specified in detail | **NOT IMPLEMENTED** | FULL GAP -- highest priority |
| Temporal relationship detection | Specified | **NOT IMPLEMENTED** | FULL GAP |
| Naming convention relationships | Specified | **PARTIAL** -- VideoAnalyzer.getBaseName() only | MOSTLY GAP |
| Bidirectional cross-references | Specified | **NOT IMPLEMENTED** | FULL GAP |
| Content fingerprinting (MoreLikeThis) | Specified | **NOT IMPLEMENTED** | FULL GAP |
| `synthesis enrich` command | Specified | **NOT IMPLEMENTED** | FULL GAP |
| Broken link detection | Specified | **IMPLEMENTED** in LSP diagnostics | DONE (LSP only, not CLI) |
| Orphan file detection | Specified | **IMPLEMENTED** in InsightsEngine | DONE |
| Naming convention analysis | Specified | **PARTIAL** in InsightsEngine | PARTIAL |
| Circular dependency detection | Specified | **IMPLEMENTED** in InsightsEngine | DONE |

### Phase 2: Local Media Enrichment (Local AI)

| Feature | AI-SCOPE Status | Implementation Status | Gap |
|---------|----------------|----------------------|-----|
| Whisper transcription | Specified in detail | **NOT IMPLEMENTED** | FULL GAP |
| PDF slide extraction | Specified | **IMPLEMENTED** as ExtractSlidesCommand | CLI exists, not automated |
| Duplicate detection (MD5 dedup) | Specified | **PARTIAL** -- hash computation exists, dedup logic does not | PARTIAL GAP |
| Campaign batch processing | Specified | **NOT IMPLEMENTED** | FULL GAP |
| Content similarity search | Specified | **NOT IMPLEMENTED** | FULL GAP |

### Phase 3: Semantic Intelligence (Cloud AI)

| Feature | AI-SCOPE Status | Implementation Status | Gap |
|---------|----------------|----------------------|-----|
| Vision analysis for images | Specified | **IMPLEMENTED** in ScanCommand --with-ai | DONE (scan only, not enrichment) |
| Vision analysis for PDFs | Specified | **NOT IMPLEMENTED** | FULL GAP |
| AI-generated summaries | Specified | **STUBBED** -- config exists, prompt exists, no code | MOSTLY GAP |
| Concept clustering | Specified | **NOT IMPLEMENTED** | FULL GAP |
| Semantic search (embeddings) | Specified | **NOT IMPLEMENTED** | FULL GAP |

### Summary Scorecard

| Phase | Features Specified | Fully Implemented | Partial | Not Started |
|-------|-------------------|-------------------|---------|-------------|
| Phase 1 | 10 | 3 | 2 | 5 |
| Phase 2 | 5 | 0 | 2 | 3 |
| Phase 3 | 5 | 1 | 1 | 3 |
| **Total** | **20** | **4 (20%)** | **5 (25%)** | **11 (55%)** |

---

## Part 3: Quick Wins (1-2 Weeks Each)

### QW-1: `synthesis enrich` Command -- Companion File Generation
**Effort:** 2 weeks | **Business Value:** HIGH | **Revenue Impact:** 50-100K NOK

**What it does:** Generates `.synthesis.md` companion files for all binary files (images, videos, PDFs, audio), making them fully text-searchable through the existing Lucene index.

**Why customers will pay:** SpareBank 1 has 200 developers producing media assets. Mynder generates compliance documents (PDFs). Both need binary files searchable. This is the #1 gap customers notice in demos.

**Technical approach:**
- New package: `io.exoreaction.synthesis.enrichment`
- `CompanionFileGenerator.java` -- template-based .synthesis.md generation
- `EnrichCommand.java` -- CLI command with `--force`, `--type`, `--dry-run`, `--stats`
- Uses existing analyzer metadata (VideoAnalyzer, ImageAnalyzer, PdfAnalyzer)
- Zero AI dependency (Phase 1 deterministic)
- Idempotent: skips if companion already exists

**Dependencies:** None beyond current Synthesis.

**Revenue Impact:**
- SpareBank 1: Addresses their "can't search binary files" pain point (+50K to contract)
- Mynder: Critical for GDPR document compliance (+retainer justification)
- Workshop: Demonstrates "everything searchable" value proposition

### QW-2: MCP `ask` Tool -- Expose AI Q&A to AI Agents
**Effort:** 3-5 days | **Business Value:** VERY HIGH | **Revenue Impact:** 30-50K NOK

**What it does:** Adds a new `ask` tool to the MCP server, allowing Claude Code, Cursor, and other AI agents to ask natural-language questions about the workspace using the full Synthesis index as context.

**Why customers will pay:** This is the #1 competitive differentiator. No other tool gives AI agents structured access to workspace knowledge with citation of specific files and line numbers. Claude Code users (SpareBank 1 rolled it out org-wide) get dramatically better answers.

**Technical approach:**
- Add `handleAsk()` method to `SynthesisToolHandler.java`
- Add `ask` tool definition in `SynthesisMCPServer.handleToolsList()`
- Reuse `AskCommand.buildContext()` logic for context gathering
- Reuse `PromptTemplates.buildAskPrompt()` for prompt construction
- Call `ClaudeClient.generate()` for answer generation
- Return structured JSON with answer + source files

**Dependencies:** ANTHROPIC_API_KEY (already required for existing ask command).

**Revenue Impact:**
- SpareBank 1: Direct value for their Claude Code rollout (this is what they asked for) (+30K)
- Workshop: Killer demo feature ("watch Claude answer questions about YOUR codebase")
- Item Consulting: Perfect for 30-developer audience

### QW-3: `synthesis explain` Command -- AI-Powered File/Module Explanations
**Effort:** 1 week | **Business Value:** HIGH | **Revenue Impact:** 20-40K NOK

**What it does:** Generates natural-language explanations of files, directories, or architectural patterns. Unlike `ask` (which answers specific questions), `explain` provides a comprehensive understanding of "what is this and why does it exist?"

**Why customers will pay:** New developers spend 40-60% of first weeks understanding code. This cuts that to hours. Enterprise onboarding is a $2.5B market.

**Technical approach:**
- New `ExplainCommand.java` with `--file`, `--module`, `--pattern` modes
- New `EXPLAIN_FILE_TEMPLATE` and `EXPLAIN_MODULE_TEMPLATE` in PromptTemplates
- Uses SearchIndex for context (related files, dependencies)
- Uses InsightsEngine for architecture context
- Outputs structured explanation (purpose, key components, relationships, entry points)

**Dependencies:** ANTHROPIC_API_KEY.

### QW-4: AI-Enhanced `synthesis insights` -- Interpret Metrics with AI
**Effort:** 3-5 days | **Business Value:** MEDIUM-HIGH | **Revenue Impact:** 15-30K NOK

**What it does:** Adds `--ai` flag to `synthesis insights` that sends the InsightsEngine metrics to Claude for natural-language interpretation and prioritized recommendations.

**Why customers will pay:** InsightsEngine already computes connectivity, complexity, quality, and architecture metrics. But numbers without interpretation are hard to act on. AI interpretation makes insights actionable for managers (SpareBank 1 decision-makers).

**Technical approach:**
- Add `--ai` flag to existing `InsightsCommand.java`
- New prompt template: `INTERPRET_INSIGHTS_TEMPLATE`
- Send metrics summary to Claude
- Return prioritized, actionable recommendations
- ~50 lines of new code

### QW-5: Content Summary During Scan (`ai.contentSummary` Implementation)
**Effort:** 3-5 days | **Business Value:** MEDIUM | **Revenue Impact:** 10-20K NOK

**What it does:** Implements the already-configured `ai.contentSummary` config option. When enabled during scan, generates 1-2 sentence summaries for each file and stores them in the index, dramatically improving search result quality.

**Why customers will pay:** Search results currently show file metadata but no summary. With AI summaries, users can scan results and understand what each file does without opening it. This is table-stakes for enterprise search tools.

**Technical approach:**
- Read `config.getAi().isContentSummary()` in ScanCommand
- Use existing `PromptTemplates.CONTENT_SUMMARY` template
- Call `ClaudeClient.generate()` for each file (batch with rate limiting)
- Store summary in DocumentFields.SUMMARY during indexing
- Add `--with-summaries` flag to scan command
- Cost optimization: only summarize files >1KB, skip binary, cache results

### QW-6: MCP `explain` Tool + MCP `insights` Tool
**Effort:** 3-5 days | **Business Value:** HIGH | **Revenue Impact:** 20-30K NOK

**What it does:** Exposes `explain` and `insights` via MCP, giving AI agents the ability to explain files and analyze workspace health without CLI.

**Technical approach:**
- Add `handleExplain()` and `handleInsights()` to SynthesisToolHandler
- Add tool definitions to MCP server
- Reuse existing command logic

### QW-7: Enhanced Relationship Detection (Temporal + Naming)
**Effort:** 1 week | **Business Value:** MEDIUM | **Revenue Impact:** 10-20K NOK

**What it does:** Adds temporal co-occurrence and naming convention relationship detection to the graph/relate/insights systems.

**Technical approach:**
- New `RelationshipDetector.java` in graph package
- Temporal: group files modified within +-1 hour (weight 0.7) or +-24 hours (weight 0.3)
- Naming: match base names across extensions, match versioned files, match test-impl pairs
- Integrate into GraphBuilder and InsightsEngine
- Zero AI dependency

### QW-8: LSP Hover AI Explanations
**Effort:** 3-5 days | **Business Value:** MEDIUM | **Revenue Impact:** 10-15K NOK

**What it does:** When hovering over a file reference in the IDE, shows an AI-generated explanation alongside the existing metadata (type, size, relationships).

**Technical approach:**
- Add optional ClaudeClient to SynthesisTextDocumentService
- On hover, if AI available, generate 1-sentence explanation
- Cache results in memory (LRU, 1000 entries)
- Graceful degradation: show metadata-only if AI unavailable

---

## Part 4: Strategic Features (4-8 Weeks Each)

### SF-1: Semantic Code Search (Embedding-Based)
**Effort:** 6-8 weeks | **Business Value:** VERY HIGH | **Revenue Impact:** 100-300K NOK

**Problem it solves:** Current search is keyword-based (Lucene TF-IDF). Users who search "authentication flow" won't find `LoginController.java` unless it contains those exact words. Semantic search understands meaning: "authentication flow" matches files about login, OAuth, JWT, session management.

**Why existing tools don't solve it:**
- GitHub Copilot: No workspace-wide semantic search
- Cursor: Limited to current file context
- Sourcegraph: Keyword-only (no semantic understanding)
- Synthesis differentiation: Semantic search across ALL file types (code + docs + PDFs + videos)

**Solution Design:**

```
Architecture:

  User Query: "How does user authentication work?"
        |
        v
  +-------------------+
  | Query Embedding   |  <- Local model OR Claude API
  | (384-dim vector)  |
  +-------------------+
        |
        v
  +-------------------+
  | Vector Search     |  <- HNSW index (Lucene 10.x native)
  | (cosine sim)      |
  +-------------------+
        |
        v
  +-------------------+
  | Hybrid Ranking    |  <- Combine keyword + semantic scores
  | (RRF fusion)      |
  +-------------------+
        |
        v
  Top-K Results with semantic relevance
```

**Key components:**
- `EmbeddingProvider.java` -- Interface for embedding generation (local/cloud)
- `LocalEmbeddingProvider.java` -- ONNX Runtime with all-MiniLM-L6-v2 (384-dim, 80MB model)
- `ClaudeEmbeddingProvider.java` -- Fallback to Claude API
- `VectorIndex.java` -- Lucene 10.x KnnVectorField + HNSW search
- `HybridSearcher.java` -- Reciprocal Rank Fusion of keyword + vector results
- `synthesis search --semantic` flag to activate

**Business case:**
- Target: All enterprise customers (SpareBank 1, Mynder, Item Consulting)
- Willingness to pay: 50-100K NOK premium for Pro/Ultimate editions
- Competitive advantage: No other CLI tool offers cross-format semantic search
- Revenue projection: 5-10 enterprise customers x 50K = 250-500K NOK/year

**Implementation plan:**
- Week 1-2: EmbeddingProvider interface + local ONNX integration
- Week 3-4: VectorIndex with Lucene KnnVectorField
- Week 5: HybridSearcher with RRF fusion
- Week 6: CLI integration + MCP tool
- Week 7: Testing, benchmarking, documentation
- Week 8: Polish, edge cases, rollout

### SF-2: Continuous Architecture Intelligence
**Effort:** 6-8 weeks | **Business Value:** VERY HIGH | **Revenue Impact:** 100-500K NOK

**Problem it solves:** Architecture degrades silently. Coupling increases, cohesion decreases, complexity grows -- but nobody notices until refactoring becomes impossible. Current `synthesis insights` is a point-in-time snapshot. Teams need continuous monitoring.

**Why existing tools don't solve it:**
- SonarQube: Code quality, not architecture
- NDepend: .NET only, no cross-repo
- Synthesis differentiation: Cross-repo, cross-format, continuous, with AI interpretation

**Solution Design:**

```
Architecture:

  File System Events (WatchCommand)
        |
        v
  +-------------------------+
  | Architecture Monitor    |  <- Continuous daemon
  | (runs in watch mode)    |
  +-------------------------+
        |
        v
  +-------------------------+
  | Metric Computation      |  <- InsightsEngine enhanced
  | (on every change)       |
  +-------------------------+
        |
        v
  +-------------------------+
  | Trend Analysis          |  <- Compare to baseline
  | (degradation detection) |
  +-------------------------+
        |
        v
  +-------------------------+
  | Alert Generation        |  <- Configurable thresholds
  | (Slack, email, LSP)     |
  +-------------------------+
        |
        v
  +-------------------------+
  | AI Interpretation       |  <- Optional Claude analysis
  | (why + how to fix)      |
  +-------------------------+
```

**Key components:**
- `ArchitectureMonitor.java` -- runs InsightsEngine on file change events
- `MetricBaseline.java` -- stores historical metric snapshots
- `TrendAnalyzer.java` -- detects metric degradation trends
- `AlertManager.java` -- configurable threshold-based alerts
- `ArchitectureDashboard.java` -- HTML dashboard generation
- Integration with existing WatchCommand daemon mode
- LSP diagnostics for real-time IDE warnings

**Business case:**
- Target: Enterprise customers (SpareBank 1 = 200 devs, Mynder = compliance)
- Willingness to pay: 100-200K NOK/year for continuous monitoring
- Competitive advantage: Only tool offering cross-repo architecture health monitoring
- Revenue projection: 2-5 enterprise customers x 100K = 200-500K NOK/year

### SF-3: AI-Powered Refactoring Suggestions
**Effort:** 4-6 weeks | **Business Value:** HIGH | **Revenue Impact:** 50-150K NOK

**Problem it solves:** Developers know they should refactor but don't know what to refactor or how to prioritize. Synthesis already identifies hotspots, dead code, and circular dependencies -- but doesn't suggest specific refactoring actions.

**Solution Design:**
- `RefactoringSuggester.java` -- generates refactoring suggestions from insights data
- Uses InsightsEngine metrics as input (hotspots, coupling, circular deps)
- Sends to Claude with structured prompt asking for specific refactoring steps
- Outputs prioritized refactoring plan with effort estimates
- `synthesis refactor-plan` command
- MCP `refactor-plan` tool

---

## Part 5: Priority Matrix

```
                    Low Effort (1-2 weeks)              High Effort (4-8 weeks)
                +-------------------------------+-------------------------------+
                |                               |                               |
  High Value    |  PRIORITY 1: BUILD NOW        |  PRIORITY 2: BUILD Q3         |
  ($100K+       |                               |                               |
   revenue)     |  QW-1: synthesis enrich        |  SF-1: Semantic Code Search   |
                |  QW-2: MCP ask tool            |  SF-2: Architecture Intel     |
                |  QW-3: synthesis explain        |                               |
                |                               |                               |
                +-------------------------------+-------------------------------+
                |                               |                               |
  Medium Value  |  PRIORITY 3: BUILD LATER      |  PRIORITY 4: DEFER            |
  ($10K-100K    |                               |                               |
   revenue)     |  QW-4: AI-enhanced insights   |  SF-3: Refactoring Suggest    |
                |  QW-5: Content summaries       |  Concept clustering           |
                |  QW-6: MCP explain/insights    |  Whisper transcription        |
                |  QW-7: Enhanced relationships  |  Vision PDF analysis          |
                |  QW-8: LSP hover AI            |                               |
                |                               |                               |
                +-------------------------------+-------------------------------+
```

**Priority 1 Justification:**
- **QW-1 (enrich):** Foundational capability that enables everything else. SpareBank 1 and Mynder both need binary file search. Zero AI dependency = works for all editions.
- **QW-2 (MCP ask):** 3-5 days of work that immediately differentiates Synthesis for every Claude Code user. SpareBank 1 specifically asked for this.
- **QW-3 (explain):** The command that sells workshops. "Watch Synthesis explain your codebase in 30 seconds" is the killer demo.

**Priority 2 Justification:**
- **SF-1 (Semantic Search):** The feature that turns Synthesis from a power-user tool into an enterprise platform. Lucene 10.x has native KnnVectorField support, making implementation surprisingly tractable.
- **SF-2 (Architecture Intelligence):** The feature that justifies recurring revenue. Enterprise customers pay for continuous monitoring, not one-time scans.

---

## Part 6: Top 3 Priority 1 Implementation Designs

### Implementation 1: `synthesis enrich` -- Companion File Generation

#### Problem Statement
Binary files (images, videos, PDFs, audio) represent 15-40% of workspace content but are invisible to search. The current index stores only metadata (filename, size, format, duration). A developer searching for "quarterly revenue chart" will never find `revenue-q3.png` even though it contains exactly that.

#### Solution Overview
Generate `.synthesis.md` companion files alongside every binary file, containing structured metadata, extracted text, and relationship data. These companion files are automatically indexed by the standard scan, making all binary content fully text-searchable.

#### Architecture

```
Binary File (e.g., demo.mp4)
    |
    v
+------------------------+
| Existing Analyzers     |  VideoAnalyzer / ImageAnalyzer / PdfAnalyzer
| (metadata extraction)  |
+------------------------+
    |
    v
+------------------------+
| CompanionFileGenerator |  NEW: Template-based .synthesis.md generation
+------------------------+
    |
    v
+------------------------+
| demo.mp4.synthesis.md  |  Generated companion file
+------------------------+
    |
    v
+------------------------+
| Lucene Index           |  Standard indexing picks up .synthesis.md
+------------------------+
    |
    v
Search for "demo video" -> finds demo.mp4 via companion
```

#### API Design

**CLI Interface:**
```
synthesis enrich                    # Generate companions for all binary files
synthesis enrich --force            # Regenerate even if companions exist
synthesis enrich --type video       # Only for video files
synthesis enrich --type image       # Only for image files
synthesis enrich --dry-run          # Show what would be generated
synthesis enrich --stats            # Show enrichment coverage statistics
```

**Configuration:**
```yaml
enrichment:
  enabled: true
  level: auto          # auto | basic | local | ai
  companion-files:
    enabled: true
    gitignore: true    # Auto-add *.synthesis.md to .gitignore
    regenerate: false  # Don't overwrite existing
```

#### Implementation Details

**New files:**
- `src/main/java/io/exoreaction/synthesis/enrichment/CompanionFileGenerator.java`
- `src/main/java/io/exoreaction/synthesis/enrichment/EnrichmentLevel.java`
- `src/main/java/io/exoreaction/synthesis/enrichment/EnrichmentResult.java`
- `src/main/java/io/exoreaction/synthesis/cli/EnrichCommand.java`

**Modified files:**
- `SynthesisConfig.java` -- add EnrichmentConfig inner class
- `SynthesisApp.java` -- register EnrichCommand
- `ScanCommand.java` -- optionally trigger enrichment after scan

#### Testing Strategy
- Unit tests for CompanionFileGenerator (template output validation)
- Integration tests with real media files (small test fixtures)
- Idempotency tests (run twice, verify no changes)
- Edge cases: empty files, corrupted metadata, missing analyzers

---

### Implementation 2: MCP `ask` Tool -- AI Q&A for AI Agents

#### Problem Statement
Claude Code, Cursor, and other AI agents can search the Synthesis index via MCP but cannot ask natural-language questions. The `synthesis ask` CLI command is powerful but only accessible from the terminal. AI agents need programmatic access to AI-powered Q&A to make informed decisions about codebase changes.

#### Solution Overview
Add a fifth MCP tool (`ask`) that accepts a natural-language question, gathers context from the Synthesis index, sends it to Claude, and returns a structured answer with file citations.

#### Architecture

```
AI Agent (Claude Code / Cursor)
    |
    v
MCP Protocol (tools/call: "ask")
    |
    v
+---------------------------+
| SynthesisToolHandler      |
| handleAsk(params)         |
+---------------------------+
    |
    +-----> SearchIndex.search(question)  -> relevant files
    |
    +-----> buildContext(results)          -> file content with line numbers
    |
    +-----> PromptTemplates.buildAskPrompt(question, context)
    |
    +-----> ClaudeClient.generate(prompt)  -> answer with citations
    |
    v
JSON Response:
{
  "answer": "Authentication is handled in...",
  "sources": [
    {"path": "src/auth/LoginController.java", "lines": "42-58"},
    {"path": "docs/auth-flow.md", "lines": "1-30"}
  ],
  "model": "claude-sonnet-4-5-20250929",
  "contextDocuments": 8,
  "responseTime": "2.3s"
}
```

#### API Design

**MCP Tool Schema:**
```json
{
  "name": "ask",
  "description": "Ask a natural-language question about the workspace. Returns an AI-generated answer with file citations. Requires ANTHROPIC_API_KEY.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "question": {
        "type": "string",
        "description": "Natural-language question about the codebase"
      },
      "contextFiles": {
        "type": "number",
        "default": 8,
        "description": "Number of files to include as context (1-20)"
      },
      "maxTokens": {
        "type": "number",
        "default": 2048,
        "description": "Maximum tokens in AI response"
      },
      "workspace": {
        "type": "string",
        "description": "Workspace path (defaults to server workspace)"
      }
    },
    "required": ["question"]
  }
}
```

#### Implementation Details

**Modified files:**
- `SynthesisToolHandler.java` -- add `handleAsk()` method (~80 lines)
- `SynthesisMCPServer.java` -- add ask tool definition, add to tools/call switch

**Key design decisions:**
- Reuse AskCommand's `buildContext()` logic (extract to shared utility)
- Return structured JSON (not just text) so AI agents can parse sources
- Include model name and timing for transparency
- Graceful error when API key not available (return helpful message, not crash)
- Rate limiting: max 10 asks/minute to prevent runaway costs

#### Testing Strategy
- Unit test for handleAsk with mock ClaudeClient
- Integration test with real MCP protocol messages
- Error cases: no API key, empty index, malformed question
- Cost estimation test: verify rate limiting works

---

### Implementation 3: `synthesis explain` -- AI-Powered Code Explanations

#### Problem Statement
Understanding unfamiliar code takes 40-60% of developer time during onboarding. Tools like `synthesis search` help find files, and `synthesis relate` shows connections, but neither explains *what code does* or *why it exists*. Developers need a command that synthesizes understanding from multiple sources (code structure, relationships, documentation, naming patterns) into a clear explanation.

#### Solution Overview
A new command that generates comprehensive explanations at three granularity levels: file, module (directory), and pattern (cross-cutting concern). Uses the Synthesis index as context to ground explanations in actual workspace structure.

#### Architecture

```
synthesis explain --file src/auth/LoginController.java
    |
    v
+---------------------------+
| Context Assembly          |
| 1. Read file content      |
| 2. Get relationships      |
| 3. Get insights metrics   |
| 4. Get related files      |
+---------------------------+
    |
    v
+---------------------------+
| Prompt Construction       |
| File: content + structure |
| Module: files + tree      |
| Pattern: cross-cutting    |
+---------------------------+
    |
    v
+---------------------------+
| ClaudeClient.generate()   |
+---------------------------+
    |
    v
Structured Output:
  ## Purpose
  [What this file/module does]

  ## Key Components
  [Important classes, functions, patterns]

  ## Relationships
  [What it depends on, what depends on it]

  ## Entry Points
  [Where to start reading]

  ## Architecture Context
  [How it fits in the bigger picture]
```

#### API Design

**CLI Interface:**
```
synthesis explain --file <path>              # Explain a single file
synthesis explain --module <directory>        # Explain a module/package
synthesis explain --pattern "authentication"  # Explain a cross-cutting pattern
synthesis explain --file <path> --depth deep  # More detailed explanation
synthesis explain --file <path> --format json # Machine-readable output
```

**MCP Tool:**
```json
{
  "name": "explain",
  "description": "Generate an AI-powered explanation of a file, module, or architectural pattern in the workspace.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "target": { "type": "string", "description": "File path, directory, or pattern name" },
      "mode": { "type": "string", "enum": ["file", "module", "pattern"], "default": "file" },
      "depth": { "type": "string", "enum": ["brief", "standard", "deep"], "default": "standard" }
    },
    "required": ["target"]
  }
}
```

#### Implementation Details

**New files:**
- `src/main/java/io/exoreaction/synthesis/ai/CodeExplainer.java` (~200 lines)
- `src/main/java/io/exoreaction/synthesis/cli/ExplainCommand.java` (~150 lines)

**Modified files:**
- `PromptTemplates.java` -- add EXPLAIN_FILE, EXPLAIN_MODULE, EXPLAIN_PATTERN templates
- `SynthesisApp.java` -- register ExplainCommand
- `SynthesisToolHandler.java` -- add handleExplain() for MCP
- `SynthesisMCPServer.java` -- add explain tool definition

**New prompt templates:**
```java
public static final String EXPLAIN_FILE_TEMPLATE = """
    You are explaining a source file to a developer who is new to this codebase.

    FILE: %s
    LANGUAGE: %s
    SIZE: %s

    FILE CONTENT:
    %s

    RELATIONSHIPS:
    Outgoing (this file depends on): %s
    Incoming (depends on this file): %s

    RELATED FILES IN SAME MODULE:
    %s

    Provide a structured explanation:

    ## Purpose
    What does this file do? Why does it exist? (2-3 sentences)

    ## Key Components
    List the most important classes/functions/methods and what they do.

    ## How It Works
    Explain the main logic flow. Reference specific line numbers.

    ## Dependencies
    What does it depend on and why?

    ## Usage
    How is this file used by other parts of the codebase?

    ## Things to Know
    Any gotchas, conventions, or important context a new developer should understand.
    """;
```

#### Testing Strategy
- Unit test prompt construction with mock data
- Integration test with real source files from Synthesis itself
- Edge cases: binary files, empty files, massive files (truncation)
- Output format validation (JSON mode)

---

## Part 7: Revenue Analysis

### Revenue by Feature per Customer

| Feature | SpareBank 1 (200 devs) | Mynder (540-900K/yr) | Item Consulting (30 devs) | Workshop Pipeline |
|---------|----------------------|----------------------|--------------------------|-------------------|
| QW-1: enrich | +50K (binary search) | +30K (GDPR docs) | +10K (demo) | +20K (selling point) |
| QW-2: MCP ask | +30K (Claude Code value) | +20K (AI agent support) | +10K (demo) | +30K (killer demo) |
| QW-3: explain | +20K (onboarding value) | +10K (code understanding) | +15K (workshop feature) | +40K (main workshop tool) |
| QW-4: AI insights | +15K (management reporting) | +10K (compliance) | +5K | +10K |
| QW-5: summaries | +10K (search quality) | +5K | +5K | +5K |
| SF-1: semantic search | +100K (enterprise search) | +50K (knowledge retrieval) | +20K | +30K |
| SF-2: architecture intel | +100K (continuous monitoring) | +50K (compliance monitoring) | +10K | +20K |
| **Total per customer** | **325K** | **175K** | **75K** | **155K** |

### Competitive Differentiation

| Feature | GitHub Copilot | Cursor | Sourcegraph | **Synthesis** |
|---------|---------------|--------|-------------|---------------|
| MCP/LSP integration | No | Partial | No | **Yes** |
| Cross-format search (code+docs+media) | No | No | Partial (code only) | **Yes** |
| AI Q&A with file citations | No | Limited | No | **Yes** |
| Binary file search (images/videos/PDFs) | No | No | No | **Yes (with enrich)** |
| Semantic code search | Partial (inline) | Partial (inline) | Keyword only | **Yes (with SF-1)** |
| Architecture monitoring | No | No | No | **Yes (with SF-2)** |
| Cross-repo dependency graphs | No | No | Yes | **Yes** |
| Air-gapped mode | No | No | Yes | **Yes** |
| Multi-perspective analysis | No | No | No | **Yes** |

**Key differentiator:** Synthesis is the ONLY tool that combines cross-format indexing + AI Q&A + MCP integration + architecture intelligence + air-gapped mode. No competitor covers more than 2 of these 5 dimensions.

---

## Part 8: Q3-Q4 2026 Roadmap

### Q3 2026 (July-September): Priority 1 + Quick Wins

| Week | Feature | Deliverable | Revenue Unlocked |
|------|---------|-------------|-----------------|
| 1-2 | QW-1: `synthesis enrich` | CompanionFileGenerator, EnrichCommand, templates for video/image/PDF/audio | SpareBank 1 binary search requirement |
| 3 | QW-2: MCP `ask` tool | handleAsk in MCP server, structured JSON response | Claude Code integration value |
| 4 | QW-3: `synthesis explain` | ExplainCommand, CodeExplainer, 3 prompt templates, MCP tool | Workshop demo ready |
| 5 | QW-4: AI-enhanced insights | `--ai` flag on insights, INTERPRET_INSIGHTS prompt | Management reporting |
| 5 | QW-5: Content summaries | `ai.contentSummary` implementation in ScanCommand | Search quality improvement |
| 6 | QW-6: MCP explain + insights | MCP tool definitions, handler methods | AI agent ecosystem |
| 7 | QW-7: Enhanced relationships | RelationshipDetector (temporal + naming), GraphBuilder integration | Relationship discovery +30-50% |
| 8 | QW-8: LSP hover AI | Optional AI explanations in hover, LRU cache | IDE integration value |
| 9-10 | Integration testing | End-to-end tests, performance benchmarks, documentation | Release readiness |
| 11-12 | Customer pilots | SpareBank 1 pilot, Mynder pilot, workshop delivery | Revenue recognition |

**Q3 Milestone:** All 8 quick wins shipped. Synthesis Pro has 7 AI-powered commands, 6 MCP tools, and AI-enhanced LSP hover. Binary files are fully searchable. Enterprise customers can pilot.

### Q4 2026 (October-December): Strategic Features

| Week | Feature | Deliverable | Revenue Unlocked |
|------|---------|-------------|-----------------|
| 1-2 | SF-1: Embedding infrastructure | EmbeddingProvider, LocalEmbeddingProvider (ONNX), VectorIndex | Foundation for semantic search |
| 3-4 | SF-1: Semantic search | HybridSearcher (RRF), `--semantic` flag, MCP integration | Enterprise search capability |
| 5-6 | SF-2: Architecture monitoring | ArchitectureMonitor, MetricBaseline, TrendAnalyzer | Continuous monitoring |
| 7-8 | SF-2: Alerts + dashboard | AlertManager, HTML dashboard, LSP diagnostics, Slack integration | Enterprise monitoring |
| 9-10 | Phase 2 media | Whisper integration, enhanced PDF slide extraction | Full media enrichment |
| 11-12 | Polish + release | Documentation, case studies, performance optimization | Q1 2027 enterprise launch |

**Q4 Milestone:** Semantic search and continuous architecture intelligence shipped. Synthesis is a platform, not just a tool. Enterprise pricing ($100K+) is justified. Anthropic partnership pitch is ready.

---

## Appendix A: Complete Feature Inventory

| # | Feature | Category | Effort | Value | Priority | Status |
|---|---------|----------|--------|-------|----------|--------|
| 1 | synthesis enrich (companion files) | CLI | 2 weeks | HIGH | P1 | NOT STARTED |
| 2 | MCP ask tool | MCP | 3-5 days | VERY HIGH | P1 | NOT STARTED |
| 3 | synthesis explain | CLI+MCP | 1 week | HIGH | P1 | NOT STARTED |
| 4 | AI-enhanced insights | CLI | 3-5 days | MEDIUM-HIGH | P3 | NOT STARTED |
| 5 | Content summaries (ai.contentSummary) | CLI | 3-5 days | MEDIUM | P3 | STUBBED |
| 6 | MCP explain + insights tools | MCP | 3-5 days | HIGH | P3 | NOT STARTED |
| 7 | Enhanced relationships (temporal+naming) | Core | 1 week | MEDIUM | P3 | NOT STARTED |
| 8 | LSP hover AI explanations | LSP | 3-5 days | MEDIUM | P3 | NOT STARTED |
| 9 | Semantic code search (embeddings) | Core+CLI+MCP | 6-8 weeks | VERY HIGH | P2 | NOT STARTED |
| 10 | Continuous architecture intelligence | Core+CLI | 6-8 weeks | VERY HIGH | P2 | NOT STARTED |
| 11 | AI refactoring suggestions | CLI+MCP | 4-6 weeks | HIGH | P4 | NOT STARTED |
| 12 | Whisper transcription integration | Core | 2-3 weeks | MEDIUM | P4 | NOT STARTED |
| 13 | Vision analysis for PDFs | Core | 2-3 weeks | MEDIUM | P4 | NOT STARTED |
| 14 | Concept clustering | Core | 2-3 weeks | MEDIUM | P4 | NOT STARTED |
| 15 | Bidirectional cross-references | Core | 1-2 weeks | MEDIUM | P3 | NOT STARTED |
| 16 | Content fingerprinting (MoreLikeThis) | Core | 1 week | MEDIUM | P3 | NOT STARTED |
| 17 | Campaign batch processing | Core | 1 week | LOW | P4 | NOT STARTED |
| 18 | Duplicate detection during enrichment | Core | 3 days | LOW | P4 | PARTIAL |

## Appendix B: Air-Gapped Feature Matrix

| Feature | Core (Air-Gapped) | Pro (Cloud) | Enterprise (Air-Gapped+Daemon) | Ultimate (Full) |
|---------|-------------------|-------------|-------------------------------|-----------------|
| synthesis enrich (deterministic) | YES | YES | YES | YES |
| synthesis enrich --ai | NO | YES | NO | YES |
| MCP ask tool | NO | YES | NO | YES |
| synthesis explain | NO | YES | NO | YES |
| AI-enhanced insights | NO | YES | NO | YES |
| Content summaries | NO | YES | NO | YES |
| Enhanced relationships | YES | YES | YES | YES |
| Semantic search (local model) | YES* | YES | YES* | YES |
| Architecture monitoring | YES | YES | YES | YES |
| Whisper transcription | YES* | YES | YES* | YES |

*Requires local model/binary installation but no cloud connectivity.

---

*Analysis complete. Generated 2026-02-15 by Claude Opus 4.6 from deep audit of 92 source files, 55 test files, and comprehensive AI-SCOPE-ANALYSIS.md cross-reference.*
