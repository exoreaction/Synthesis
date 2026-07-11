# Synthesis for AI Agents

**Context infrastructure for AI coding tools. Search, dependencies, architecture, impact analysis, and workspace intelligence -- programmatically.**

---

## What This Document Covers

This guide is for developers building AI agent integrations, teams configuring MCP/LSP servers, and AI systems that consume Synthesis output. It covers:
- MCP server setup and tool capabilities
- LSP server integration
- CLI integration patterns for agent frameworks
- The `exo ask` conversational RAG loop
- Directory identity system for agent-driven file routing
- Staging pipeline for ingesting and classifying new files
- Knowledge edge integrity signals
- Programmatic output formats
- Best practices for agent tool use

---

## MCP Server Integration

Synthesis runs as an MCP (Model Context Protocol) server, enabling Claude Desktop, Claude Code, Cursor, and other MCP-compatible clients to use Synthesis tools natively.

### Setup for Claude Desktop

Add to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "synthesis": {
      "command": "synthesis",
      "args": ["mcp", "server"],
      "env": {
        "SYNTHESIS_WORKSPACE": "/path/to/your/workspace"
      }
    }
  }
}
```

### Setup for Claude Code

Add to your Claude Code MCP configuration:

```json
{
  "mcpServers": {
    "synthesis": {
      "command": "synthesis",
      "args": ["mcp", "server"]
    }
  }
}
```

### Multi-Workspace MCP Setup

To search across multiple workspaces simultaneously:

```json
{
  "mcpServers": {
    "synthesis-source": {
      "command": "synthesis-mcp-server",
      "args": ["--workspaces", "/src/a,/src/b,/src/c", "--name", "source"]
    }
  }
}
```

The `SYNTHESIS_WORKSPACE` environment variable or `-d` flag determines which workspace the server operates on.

### Available MCP Tools

| Tool | Purpose | AI Required |
|------|---------|-------------|
| `search` | Full-text search across indexed files | No |
| `relate` | Bi-directional dependency analysis + knowledge edge enrichment | No |
| `graph` | Dependency graph generation (Mermaid, DOT, JSON) | No |
| `stats` | Workspace health, file counts, index freshness | No |
| `ask` | AI-powered Q&A about workspace | Yes |
| `enrich` | Generate companion files for binary assets | Optional |
| `explain` | AI explanation of a file, directory, or pattern | Yes |
| `summary` | AI executive summaries with temporal context (`--since`) | Yes |

### Tool Schema: search

```json
{
  "name": "search",
  "description": "Search Synthesis index across all file types (code, docs, videos, PDFs). Returns ranked results with snippets, metadata, and relevance scores. Supports Lucene query syntax.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string",
        "description": "Search query (supports Lucene syntax: terms, phrases, booleans, wildcards, field:value)"
      },
      "fileType": {
        "type": "string",
        "enum": ["CODE", "MARKDOWN", "PDF", "VIDEO", "YAML", "JSON", "CONFIG", "IMAGE", "AUDIO", "ALL"],
        "default": "ALL",
        "description": "Filter by file type"
      },
      "limit": {
        "type": "number",
        "default": 20,
        "description": "Maximum number of results (1-200)"
      },
      "subWorkspace": {
        "type": "string",
        "description": "Scope search to a named sub-workspace (e.g. 'eXOReaction', 'Cantara'). Useful in multi-workspace setups."
      },
      "workspace": {
        "type": "string",
        "description": "Workspace path (defaults to server's configured workspace)"
      }
    },
    "required": ["query"]
  }
}
```

### Tool Schema: relate

```json
{
  "name": "relate",
  "description": "Show bidirectional relationships for a file (imports, usages, references). Includes knowledge edge enrichment: documentation coverage, confidence, and drift signals.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "filePath": {
        "type": "string",
        "description": "File name or path to analyze relationships for"
      },
      "format": {
        "type": "string",
        "enum": ["json", "mermaid"],
        "default": "json",
        "description": "Output format: json (structured) or mermaid (diagram)"
      },
      "workspace": {
        "type": "string",
        "description": "Workspace path (defaults to server's configured workspace)"
      }
    },
    "required": ["filePath"]
  }
}
```

The JSON response now includes a `documentation` block with knowledge edge data:

```json
{
  "file": "/path/to/file",
  "outgoing": [...],
  "incoming": [...],
  "documentation": {
    "hasGap": false,
    "overallConfidence": 0.85,
    "skills": [
      {
        "skillPath": "synthesis-agent-patterns.md",
        "confidence": "HIGH",
        "driftDays": 2,
        "coveredEntities": ["SearchIndex", "handleSearch"]
      }
    ]
  }
}
```

### Tool Schema: graph

```json
{
  "name": "graph",
  "description": "Generate architecture graph showing modules, dependencies, and cross-repo relationships. Returns Mermaid, DOT, or structured JSON.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "mode": {
        "type": "string",
        "enum": ["modules", "dependencies", "cross-repo"],
        "default": "modules",
        "description": "Graph type: modules (directory-level), dependencies, or cross-repo"
      },
      "format": {
        "type": "string",
        "enum": ["mermaid", "json", "dot"],
        "default": "mermaid",
        "description": "Output format"
      },
      "filter": {
        "type": "string",
        "description": "Filter to specific subsystem, directory, or repository pattern"
      },
      "workspace": {
        "type": "string",
        "description": "Workspace path (defaults to server's configured workspace)"
      }
    }
  }
}
```

### Tool Schema: stats

```json
{
  "name": "stats",
  "description": "Get workspace statistics: file counts by type, index size, health status, and last scan time. Use to verify workspace is indexed and healthy.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "workspace": {
        "type": "string",
        "description": "Workspace path (defaults to server's configured workspace)"
      }
    }
  }
}
```

### Tool Schema: ask

```json
{
  "name": "ask",
  "description": "Ask a natural-language question about the workspace. Searches the index for relevant files, builds context, and generates an AI answer with file citations.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string",
        "description": "The question to ask about the codebase"
      },
      "workspace": {
        "type": "string",
        "description": "Workspace path (defaults to server's configured workspace)"
      }
    },
    "required": ["query"]
  }
}
```

### Tool Schema: enrich

```json
{
  "name": "enrich",
  "description": "Generate .synthesis.md companion files for binary assets (images, videos, PDFs, audio). Makes binary content searchable by extracting metadata, text, and AI descriptions.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "filePath": {
        "type": "string",
        "description": "Path to a specific file to enrich (omit for batch mode)"
      },
      "level": {
        "type": "string",
        "enum": ["basic", "local", "ai"],
        "default": "basic",
        "description": "Enrichment level: basic (metadata only), local (with tools), ai (with Claude)"
      },
      "force": {
        "type": "boolean",
        "default": false,
        "description": "Force regeneration even if companion file exists"
      },
      "workspace": {
        "type": "string",
        "description": "Workspace path (defaults to server's configured workspace)"
      }
    }
  }
}
```

### Tool Schema: explain

```json
{
  "name": "explain",
  "description": "AI-powered explanation of files, directories, or architectural patterns. Generates comprehensive explanations with code references and context.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "target": {
        "type": "string",
        "description": "File path, directory path, or pattern name to explain"
      },
      "includeContext": {
        "type": "boolean",
        "default": true,
        "description": "Include related files in the explanation context"
      },
      "depth": {
        "type": "string",
        "enum": ["brief", "standard", "deep"],
        "default": "standard",
        "description": "Explanation depth: brief (3-5 sentences), standard (sections), deep (comprehensive)"
      },
      "workspace": {
        "type": "string",
        "description": "Workspace path (defaults to server's configured workspace)"
      }
    },
    "required": ["target"]
  }
}
```

### Tool Schema: summary

```json
{
  "name": "summary",
  "description": "Generate executive summary of the codebase with AI-enhanced analysis. Choose detail level and role perspective. Use --since for temporally-grounded summaries with real change data injected into the AI prompt.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "level": {
        "type": "string",
        "enum": ["executive", "manager", "developer"],
        "default": "executive",
        "description": "Detail level: executive (30s overview), manager (5min briefing), developer (technical detail)"
      },
      "perspective": {
        "type": "string",
        "enum": ["general", "executive", "engineering_manager", "architect", "security", "devops", "product_manager", "developer"],
        "default": "general",
        "description": "Role-based perspective for interpreting metrics"
      },
      "format": {
        "type": "string",
        "enum": ["markdown", "json", "terminal"],
        "default": "markdown",
        "description": "Output format"
      },
      "since": {
        "type": "string",
        "description": "Include recent changes in the AI analysis. Supports durations (7d, 24h, 2w, 3m) and ISO dates (2026-01-15). Bypasses cache -- always generates fresh results."
      },
      "noAi": {
        "type": "boolean",
        "default": false,
        "description": "Skip AI-enhanced summary (faster, metrics-only)"
      },
      "noCache": {
        "type": "boolean",
        "default": false,
        "description": "Skip cache and force fresh generation"
      },
      "workspace": {
        "type": "string",
        "description": "Workspace path (defaults to server's configured workspace)"
      }
    }
  }
}
```

See [MCP Quick Start](../guides/MCP-QUICKSTART.md), [MCP Comprehensive Guide](../guides/MCP-COMPREHENSIVE-GUIDE.md), and [MCP Protocol Reference](../api/MCP-PROTOCOL-REFERENCE.md) for full details.

---

## LSP Server Integration

Synthesis provides a Language Server Protocol server for IDE integration.

### Capabilities

- **Diagnostics:** Architecture alerts (god classes, circular deps, missing tests) appear as warnings/errors in your IDE's Problems panel
- **Hover:** Dependency context on hover (incoming/outgoing reference counts)
- **Code Actions:** Trigger relationship analysis, graph generation

### Setup

See [LSP Quick Start](../guides/LSP-QUICKSTART.md) for per-IDE setup:
- VS Code
- IntelliJ IDEA
- Neovim
- Vim
- Emacs

See [LSP Protocol Reference](../api/LSP-PROTOCOL-REFERENCE.md) for protocol-level details.

---

## The `exo ask` Conversational RAG Loop

The `exo ask` command (a shell wrapper around Synthesis) provides a conversational RAG (Retrieval-Augmented Generation) loop that is ideal for agents that need to show their reasoning or for interactive exploration:

```bash
exo ask "how does the staging pipeline work?"
```

### What happens:

1. Runs `synthesis search -l 8` for relevant context
2. Displays which files were used as sources (with scores)
3. Streams an AI answer grounded in those sources (word-by-word)
4. Prompts for follow-up questions (with full conversation history)
5. Press Enter to exit

### Why this matters for agents:

| | `exo ask` | `synthesis ask` |
|---|---|---|
| Interface | Conversational REPL (bash) | Single-shot Java CLI |
| Sources shown | Yes, before answer | With `--verbose` |
| Streaming | Yes | Yes |
| Follow-up | Yes, with history | With `--interactive` |
| Best for | Executive Q&A, showing reasoning | Deep technical queries |

### Agent pattern:

```bash
# When an agent needs to explain its reasoning chain:
exo ask "what authentication approach does this project use?"
# → Sources listed → Answer with citations → Follow-up available

# When an agent needs a quick factual lookup:
synthesis ask "where is AuthService implemented?"
# → Direct answer, no follow-up loop
```

---

## CLI Integration for Agent Frameworks

For agents that invoke Synthesis via command-line (Claude Code, Aider, custom frameworks):

### Search Pattern

```bash
synthesis search "authentication service"
synthesis search "authentication" --type java
synthesis search --all "authentication"          # Cross-workspace search
```

### Dependency and Impact Analysis Pattern

```bash
# Basic dependency analysis
synthesis relate src/auth/AuthService.java
synthesis relate src/auth/AuthService.java --depth 2
synthesis relate src/auth/AuthService.java --mermaid

# Co-change analysis (new in v1.11.1)
synthesis impact src/auth/AuthService.java
# Shows files that historically change together -- reveals actual coupling
# beyond static imports. Use before refactoring to understand blast radius.
```

### Architecture Analysis Pattern

```bash
synthesis architecture --format json             # Machine-readable output
synthesis graph --modules --format mermaid
synthesis cross-repo-deps
```

### AI-Powered Analysis Pattern

```bash
synthesis ask "how does authentication work in this project?"
synthesis explain src/auth/AuthService.java
synthesis explain --module src/auth/ --depth deep
synthesis perspectives "should we refactor this to microservices?"

# Temporally-grounded summaries (new in v1.11.1)
synthesis summary --since 7d
# → Injects real changelog data into the AI prompt, not just the output.
#   Requires `synthesis maintain` to have run at least once to populate snapshots.
synthesis summary --since 7d --perspective architect
```

### Workspace Health Pattern

```bash
# Check workspace health before relying on the index
synthesis health
# → Reports scan age, index integrity, configuration status

# Ensure directory identities are populated before routing decisions
synthesis sync
# → Populates .synthesis.md identity files for directories

# Find repos that should be indexed but are not
synthesis discover
```

### Agent Workflow Example: Refactoring

A recommended workflow for an AI agent performing a refactoring task:

```
1. synthesis health
   → Verify workspace is indexed and fresh

2. synthesis search "component to refactor"
   → Identify all relevant files

3. synthesis relate <primary-file>
   → Understand static dependencies (incoming = blast radius)
   → Check documentation.hasGap for stale docs

4. synthesis impact <primary-file>
   → Understand co-change patterns (what actually changes together)

5. [Make changes]

6. synthesis maintain
   → Update index with changes

7. synthesis architecture --format json
   → Verify no new anti-patterns introduced
```

### Agent Workflow Example: Ingesting New Files

For agents that receive or generate files and need to route them to the right place:

```
1. synthesis sync
   → Ensure directory identities are populated

2. synthesis staging ingest
   → Move files from downloads/inbox into staging area

3. synthesis staging route
   → Route to org folders using keyword matching
   → Falls back to AI content classification for unmatched files
   → Uses companion .synthesis.md descriptions when available

4. synthesis maintain
   → Update index with newly routed files
```

---

## Directory Identity System

The directory identity system (v1.11.1) enables AI agents to understand workspace organization and make intelligent file-placement decisions.

### How it works:

Each directory with a `.synthesis.md` companion file declares its purpose using YAML front matter:

```yaml
---
synthesis:
  accepts:
    types: [CODE, MARKDOWN, YAML]
    formats: [java, md, yml]
    patterns: ["*Service.java", "*Config.yaml"]
  scope: "Authentication and authorization subsystem"
---
# auth/

This directory contains the authentication and authorization services...
```

### Agent pattern for file placement:

```bash
# 1. Populate directory identities
synthesis sync

# 2. Before writing or placing a file, check the target directory's identity:
#    Read <target-dir>/.synthesis.md to verify it accepts the file type
#    The DirectoryIdentityRouter logic matches files to directories by type, format, and pattern

# 3. Preview automated cleanup recommendations
synthesis sweep --dry-run
# → Shows which files are misplaced and where they should go
```

### Why agents should use this:

- Before placing a generated file, an agent can verify the target directory accepts that file type
- `synthesis sync` populates these -- run before agent-driven routing tasks
- Prevents agents from placing files in incorrect directories
- Enables the self-organizing workspace cycle: `sync` -> `sweep` -> `maintain --rebalance`

---

## Staging Pipeline for AI Agents

The staging pipeline provides a structured way to ingest, classify, and route new files:

```bash
# Full pipeline:
synthesis staging ingest          # Move files from inbox into staging area
synthesis staging route           # Route to org folders
synthesis maintain                # Update index
```

### AI content classification fallback:

When `staging route` encounters a file that does not match any keyword-based routing rule:

1. It reads the companion `.synthesis.md` file (if present) via `DownloadsClassifier.classifyWithCompanion()`
2. Files scoring above `classificationThreshold` (default 0.5) are auto-routed (marked with `~` in output)
3. Files below threshold become suggestions (marked with `?` in verbose mode)

**Agent tip:** Run `synthesis enrich` first to populate companion `.synthesis.md` files for PDFs and images, enabling content-based classification.

---

## Knowledge Edge Integrity

Synthesis tracks relationships between documentation (skills, guides) and source code through knowledge edges. This helps agents assess how trustworthy documentation is before acting on it.

### How agents should use knowledge edges:

```bash
# Check if documentation for a file is current:
synthesis relate src/auth/AuthService.java
# → The "documentation" block in the response shows:
#   - hasGap: true/false (is there documentation at all?)
#   - overallConfidence: 0.0-1.0 (how current is it?)
#   - driftDays: how many days since last verification
```

### Integrity signals during maintain:

```bash
synthesis maintain
# May output warnings like:
# "Warning: Knowledge edge degraded: [synthesis-agent-patterns.md] [HIGH -> MEDIUM confidence]"
```

**Agent behavior:** Treat these warnings as signals that related documentation may be stale. When a knowledge edge degrades, verify the skill/doc against source before relying on its claims.

### Trust evaluation for agents:

| Signal | What it means | Action |
|---|---|---|
| `driftDays < 3` | Documentation recently verified | Trust the content |
| `driftDays 3-14` | Possibly outdated | Verify specific claims against source |
| `driftDays > 14` | Likely stale | Read source directly, do not rely on docs |
| `hasGap: true` | No documentation covers this file | Read source, consider generating docs |
| `confidence: LOW` | Known discrepancy between docs and source | Read source, docs are unreliable |

---

## KCP Knowledge Manifests

KCP (Knowledge Context Protocol) manifests (`knowledge.yaml`) give agents a curated reading
list for a repository: which files matter, what each one is for, and the recommended read order.
Synthesis detects, parses, and stores KCP data automatically during `scan` and `maintain`.

### Check KCP coverage

```bash
# See all KCP units in the workspace
synthesis kg -d /path/to/workspace --format json | jq '.kcpUnits'

# Count units per project
synthesis kg -d /path/to/workspace --format json \
  | jq '[.kcpUnits | group_by(.project)[] | {project: .[0].project, units: length}]'
```

### Generate a manifest

For repos without a `knowledge.yaml`:

```bash
synthesis -d /path/to/repo export --format kcp -o knowledge.yaml
```

After committing and rescanning, units appear in `synthesis kg` and via MCP automatically.

### Field-to-decision table

When consuming a KCP manifest, agents should use fields as follows:

| Field | Agent action |
|-------|-------------|
| `intent` | Use as the answer to "what will I learn here?" before deciding whether to read the file |
| `scope: global` | Read first — file provides cross-cutting context for the whole project |
| `scope: module` | Read when the query matches the file's subject area |
| `scope: focused` | Read only for deep dives into a specific sub-topic |
| `triggers` | Match against the agent's current query keywords to decide relevance |
| `audience: [agent]` | File is specifically optimised for machine consumption |
| `kind: policy` | Governance/legal file — read for compliance, not implementation details |
| `kind: schema` | API contract — read when understanding interfaces or data shapes |
| `relationships` | Follow `context` edges to find broader background for a specific unit |

### MCP integration

The `synthesis knowledge-graph` MCP tool (`mcp__synthesis__knowledge-graph`) returns `kcpUnits`
and `kcpRelationships` arrays in its JSON response. Pass `--format json` to get the full
structured data. No extra tool calls needed — KCP data is included in every knowledge graph
response once manifests are indexed.

---

## Cross-Workspace Operations

Agents working across multiple projects can use cross-workspace commands:

```bash
synthesis search --all "authentication"          # Search all workspaces
synthesis which EventStoreService.java           # Find which workspace(s) have a file
synthesis list                                    # List all registered workspaces
synthesis list --type source                      # Filter by type
synthesis cross-repo-deps                         # Cross-repository dependencies
```

---

## Workspace Resolution

When an agent needs to target a specific workspace:

```bash
synthesis search "query" -d /path/to/workspace    # Explicit directory
SYNTHESIS_WORKSPACE=/path synthesis search "query"  # Environment variable
```

**Resolution order:**
1. `-d` / `--directory` flag (highest priority)
2. `SYNTHESIS_WORKSPACE` environment variable
3. `~/.synthesis/workspace` file
4. Current working directory

---

## Credential Management for Agents

Agents needing AI features should ensure credentials are configured:

```bash
# Check if credentials are available
synthesis credentials status

# Store credentials (one-time setup)
synthesis credentials set ANTHROPIC_API_KEY sk-ant-...

# Environment variable overrides credential store
export ANTHROPIC_API_KEY=sk-ant-...
```

---

## Performance Characteristics

For agents planning their query strategy:

| Operation | Typical time | Notes |
|-----------|-------------|-------|
| `search` | <1 second | Indexed full-text search (0.4s validated) |
| `relate` | <1 second | Pre-computed relationships + knowledge edges |
| `impact` | 1-3 seconds | Co-change analysis from changelog data |
| `graph` | 1-3 seconds | Depends on graph size |
| `architecture` | 1-5 seconds | Depends on codebase size |
| `insights` | 2-5 seconds | Full codebase analysis |
| `health` | <1 second | Quick workspace validation |
| `ask` | 5-15 seconds | Requires AI API call |
| `explain` | 5-15 seconds | Requires AI API call |
| `summary` | 5-30 seconds | AI call; cached results return instantly |
| `summary --since` | 10-30 seconds | Always fresh (bypasses cache) |
| `research` | 30-120 seconds | Multi-pass AI analysis |
| `scan` (incremental) | <1 second | Only changed files |
| `scan` (full) | 5-60 seconds | Depends on file count |
| `sync` | 1-5 seconds | Populates directory identities |

---

## Benchmark Data (Synthesis Impact Benchmark, Feb 2026)

Validated across 25+ sessions measuring AI agent tool call efficiency:

| Condition | Avg API calls | vs Baseline |
|---|---|---|
| Baseline (no Synthesis) | 6.1 | -- |
| Synthesis + Skills (Condition C) | 3.2 | -48% reduction |

| Scenario | Avg tool call reduction |
|---|---|
| All searches worked (7/12 tasks) | **-39.4%** |
| Mixed success/lock (3/12) | **-29.0%** |
| Overall average (12 tasks) | **-31.3%** |

Phase 5 results: Knowledge graph -15% API calls, CLI +11% vs Phase 4 baseline.

**Production deployment:**
- 36,342 files indexed
- ~2,500 tests passing
- Sub-second search: 0.4s validated
- 58 repositories, 429 cross-dependencies mapped in <31 seconds

---

## Best Practices for AI Tool Use

### 1. Check Health Before Searching

Run `synthesis health` or `synthesis status` at the start of a session to verify the workspace is indexed and fresh. Stale indexes produce stale results.

### 2. Search Before Acting

Always search before making changes. Synthesis finds related files that may not be in the agent's immediate context.

### 3. Check Dependencies AND Impact Before Refactoring

Run `synthesis relate` for static dependencies and `synthesis impact` for co-change patterns. Together they reveal the true blast radius.

### 4. Use Temporal Summaries for Change Awareness

```bash
synthesis summary --since 7d --perspective developer
```

This injects real changelog data into the AI prompt, giving the agent a grounded understanding of recent changes -- not a generic overview.

### 5. Use Incremental Operations

After making changes, run `synthesis maintain` (not `synthesis scan --full`) to update the index efficiently. The `maintain` command also updates change tracking for `--since` queries and checks knowledge edge integrity.

### 6. Prefer Structured Output

Use `--format json` where available for machine-readable output:

```bash
synthesis architecture --format json
```

### 7. Minimize API Calls

AI-powered commands (`ask`, `explain`, `summary`) have API costs. Use non-AI commands (`search`, `relate`, `impact`, `graph`, `architecture`, `insights`) when they suffice.

### 8. Verify Knowledge Edge Integrity

When a skill or doc makes specific claims (field counts, enum values, algorithms), check the `relate` response's `documentation.driftDays` before trusting it. Skills are most reliable for architecture and patterns; least reliable for specific counts and config defaults.

### 9. Cache Awareness

Summary and research results are cached. Repeated identical queries return instantly. Use `--no-cache` only when you need fresh results after code changes. Note: `--since` queries always bypass the cache automatically.

### 10. Parallel Search Is Safe (v1.10.0+)

Multiple agents can search the same index simultaneously. `SearchIndex.openReadOnly()` opens via `DirectoryReader` with no write lock contention.

---

## Editions and Air-Gapped Environments

| Edition | AI commands | Network |
|---------|------------|---------|
| `core` | Disabled (`ask`, `perspectives` removed) | None required |
| `pro` (default) | Enabled | Required for AI features |
| `enterprise` | Disabled | None required |
| `ultimate` | Enabled | Required for AI features |

In air-gapped editions, agents should use non-AI commands only: `search`, `relate`, `impact`, `graph`, `architecture`, `insights`, `cross-repo-deps`.

Set edition via: `SYNTHESIS_EDITION=core`

---

## Index Freshness

The quality of Synthesis results depends on index freshness. For agents:

```bash
# Check workspace health and when last scan occurred
synthesis health
synthesis status

# Update index (fast, incremental + change tracking)
synthesis maintain

# Full rebuild (when needed)
synthesis scan --full
```

An agent should run `synthesis maintain` or `synthesis scan` before performing searches if the codebase may have changed since the last scan.

---

## Quick Reference

```
# Health and discovery
synthesis health                           # Check workspace health
synthesis status                           # Index health + metrics
synthesis discover                         # Find unindexed repos
synthesis sync                             # Populate directory identities

# Search and discovery
synthesis search "query"                    # Full-text search
synthesis search --all "query"              # Cross-workspace search
synthesis which <file>                      # Find workspace for file
synthesis list                              # List workspaces

# Dependency and impact analysis
synthesis relate <file>                     # Bi-directional dependencies + knowledge edges
synthesis relate <file> --depth 2           # Deep traversal
synthesis relate <file> --mermaid           # Visual output
synthesis impact <file>                     # Co-change analysis (blast radius)
synthesis cross-repo-deps                   # Cross-repo dependencies

# Architecture
synthesis architecture --format json        # Machine-readable anti-patterns
synthesis graph --modules --format mermaid  # Module graph
synthesis insights                          # Codebase health

# AI-powered (requires API key)
synthesis ask "question"                    # Natural-language Q&A
exo ask "question"                          # Conversational RAG with sources + follow-up
synthesis explain <file>                    # File explanation
synthesis perspectives "question"           # Multi-angle analysis
synthesis summary --since 7d               # Temporally-grounded summary
synthesis research --topic <topic>          # Deep analysis

# Staging pipeline
synthesis staging ingest                    # Ingest files into staging
synthesis staging route                     # Route with AI content classification
synthesis enrich                            # Generate companions for binary files

# Index management
synthesis maintain                          # Incremental update + change tracking + knowledge edges
synthesis scan                              # Full/incremental scan
synthesis sweep --dry-run                   # Preview automated file cleanup

# Credentials
synthesis credentials status                # Check API key
synthesis credentials set KEY value         # Store API key
```

---

**Related guides:**
- [MCP Quick Start](../guides/MCP-QUICKSTART.md) -- 5-minute MCP setup
- [MCP Comprehensive Guide](../guides/MCP-COMPREHENSIVE-GUIDE.md) -- Full MCP reference
- [MCP Protocol Reference](../api/MCP-PROTOCOL-REFERENCE.md) -- Protocol-level details
- [LSP Quick Start](../guides/LSP-QUICKSTART.md) -- IDE integration
- [LSP Protocol Reference](../api/LSP-PROTOCOL-REFERENCE.md) -- LSP protocol details
- [Developer Guide](./DEVELOPER.md) -- Human developer workflows
- [Full User Guide](../guides/USER-GUIDE.md) -- Complete command reference
