# Synthesis for AI Agents

**Context infrastructure for AI coding tools. Search, dependencies, and architecture -- programmatically.**

---

## What This Document Covers

This guide is for developers building AI agent integrations, teams configuring MCP/LSP servers, and AI systems that consume Synthesis output. It covers:
- MCP server setup and tool capabilities
- LSP server integration
- CLI integration patterns for agent frameworks
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

The `SYNTHESIS_WORKSPACE` environment variable or `-d` flag determines which workspace the server operates on.

### Available MCP Tools

| Tool | Purpose | AI Required |
|------|---------|-------------|
| `synthesis_search` | Full-text search across indexed files | No |
| `synthesis_relate` | Bi-directional dependency analysis | No |
| `synthesis_graph` | Dependency graph generation (Mermaid, PNG, SVG) | No |
| `synthesis_stats` | Workspace health and index statistics | No |
| `synthesis_ask` | AI-powered Q&A about workspace | Yes |
| `synthesis_enrich` | Generate companion files for binary assets | Optional |
| `synthesis_explain` | AI explanation of a file or pattern | Yes |
| `synthesis_summary` | Generate summaries at different abstraction levels | Yes |

### Tool Schema: synthesis_search

```json
{
  "name": "synthesis_search",
  "description": "Search across code, docs, videos, PDFs, configs",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string",
        "description": "Search query (supports multi-word, exact phrases in quotes)"
      },
      "fileType": {
        "type": "string",
        "description": "Filter by file type: java, python, markdown, yaml, json, pdf, video, image"
      },
      "limit": {
        "type": "integer",
        "description": "Maximum results to return (default: 20)"
      }
    },
    "required": ["query"]
  }
}
```

### Tool Schema: synthesis_relate

```json
{
  "name": "synthesis_relate",
  "description": "Show bi-directional relationships for a file (incoming and outgoing dependencies)",
  "inputSchema": {
    "type": "object",
    "properties": {
      "filePath": {
        "type": "string",
        "description": "Path to the file (relative to workspace root or filename)"
      },
      "depth": {
        "type": "integer",
        "description": "Depth of relationship traversal (default: 1, max: 5)"
      }
    },
    "required": ["filePath"]
  }
}
```

### Tool Schema: synthesis_graph

```json
{
  "name": "synthesis_graph",
  "description": "Generate a dependency graph for a file or the whole workspace",
  "inputSchema": {
    "type": "object",
    "properties": {
      "filePath": {
        "type": "string",
        "description": "Target file (optional; omit for module-level overview)"
      },
      "format": {
        "type": "string",
        "description": "Output format: mermaid (default), dot, png, svg"
      },
      "modules": {
        "type": "boolean",
        "description": "Generate module-level graph instead of file-level"
      },
      "depth": {
        "type": "integer",
        "description": "Traversal depth (default: 2)"
      }
    }
  }
}
```

### Tool Schema: synthesis_stats

```json
{
  "name": "synthesis_stats",
  "description": "Return workspace health: file count, index size, last scan time, storage overhead",
  "inputSchema": {
    "type": "object",
    "properties": {}
  }
}
```

### Tool Schema: synthesis_ask

```json
{
  "name": "synthesis_ask",
  "description": "Ask a natural-language question about the workspace. Uses indexed files as context.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "question": {
        "type": "string",
        "description": "Natural-language question about the codebase or workspace"
      }
    },
    "required": ["question"]
  }
}
```

### Tool Schema: synthesis_enrich

```json
{
  "name": "synthesis_enrich",
  "description": "Generate companion markdown files for binary assets (images, videos, PDFs) to make them searchable",
  "inputSchema": {
    "type": "object",
    "properties": {
      "type": {
        "type": "string",
        "description": "Asset type to enrich: video, image, pdf (omit for all)"
      },
      "level": {
        "type": "string",
        "description": "Enrichment level: basic (metadata only) or ai (AI-generated descriptions)"
      }
    }
  }
}
```

### Tool Schema: synthesis_explain

```json
{
  "name": "synthesis_explain",
  "description": "Generate an AI-powered explanation of a file, module, or code pattern",
  "inputSchema": {
    "type": "object",
    "properties": {
      "filePath": {
        "type": "string",
        "description": "Path to the file to explain (relative to workspace root)"
      },
      "depth": {
        "type": "string",
        "description": "Explanation depth: brief (3-5 sentences), standard (default), deep (comprehensive)"
      }
    },
    "required": ["filePath"]
  }
}
```

### Tool Schema: synthesis_summary

```json
{
  "name": "synthesis_summary",
  "description": "Generate a summary of the workspace or a specific area at the requested abstraction level",
  "inputSchema": {
    "type": "object",
    "properties": {
      "level": {
        "type": "string",
        "description": "Abstraction level: high (executive overview), medium (module level), low (file level)"
      },
      "scope": {
        "type": "string",
        "description": "Scope to summarize: workspace (default) or a specific directory/module path"
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

## CLI Integration for Agent Frameworks

For agents that invoke Synthesis via command-line (Claude Code, Aider, custom frameworks):

### Search Pattern

```bash
synthesis search "authentication service"
synthesis search "authentication" --type java
synthesis search --all "authentication"          # Cross-workspace search
```

### Dependency Analysis Pattern

```bash
synthesis relate src/auth/AuthService.java
synthesis relate src/auth/AuthService.java --depth 2
synthesis relate src/auth/AuthService.java --mermaid
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
```

### Research Pattern

```bash
synthesis research --topic architecture --output report.md
synthesis research --passes architecture,security,synthesis
synthesis research --estimate                     # Cost preview
```

### Agent Workflow Example

A recommended workflow for an AI agent performing a refactoring task:

```
1. synthesis search "component to refactor"
   → Identify all relevant files

2. synthesis relate <primary-file>
   → Understand dependencies (incoming = blast radius)

3. synthesis architecture --format json
   → Check for existing anti-patterns to avoid

4. [Make changes]

5. synthesis scan
   → Update index with changes

6. synthesis architecture --format json
   → Verify no new anti-patterns introduced
```

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
| `search` | <1 second | Indexed full-text search |
| `relate` | <1 second | Pre-computed relationships |
| `graph` | 1-3 seconds | Depends on graph size |
| `architecture` | 1-5 seconds | Depends on codebase size |
| `insights` | 2-5 seconds | Full codebase analysis |
| `ask` | 5-15 seconds | Requires AI API call |
| `explain` | 5-15 seconds | Requires AI API call |
| `research` | 30-120 seconds | Multi-pass AI analysis |
| `scan` (incremental) | <1 second | Only changed files |
| `scan` (full) | 5-60 seconds | Depends on file count |

---

## Best Practices for AI Tool Use

### 1. Search Before Acting

Always search before making changes. Synthesis finds related files that may not be in the agent's immediate context.

### 2. Check Dependencies Before Refactoring

Run `relate` on any file you plan to modify. The incoming references list is the set of files that may break.

### 3. Use Incremental Operations

After making changes, run `synthesis maintain` (not `synthesis scan --full`) to update the index efficiently.

### 4. Prefer Structured Output

Use `--format json` where available for machine-readable output:

```bash
synthesis architecture --format json
```

### 5. Minimize API Calls

AI-powered commands (`ask`, `explain`, `research`) have API costs. Use non-AI commands (`search`, `relate`, `graph`, `architecture`, `insights`) when they suffice.

### 6. Cache Awareness

Research and report results are cached. Repeated identical queries return instantly. Use `--no-cache` only when you need fresh results after code changes.

---

## Editions and Air-Gapped Environments

| Edition | AI commands | Network |
|---------|------------|---------|
| `core` | Disabled (`ask`, `perspectives` removed) | None required |
| `pro` (default) | Enabled | Required for AI features |
| `enterprise` | Disabled | None required |
| `ultimate` | Enabled | Required for AI features |

In air-gapped editions, agents should use non-AI commands only: `search`, `relate`, `graph`, `architecture`, `insights`, `cross-repo-deps`.

Set edition via: `SYNTHESIS_EDITION=core`

---

## Index Freshness

The quality of Synthesis results depends on index freshness. For agents:

```bash
# Check when last scan occurred
synthesis status

# Update index (fast, incremental)
synthesis maintain

# Full rebuild (when needed)
synthesis scan --full
```

An agent should run `synthesis maintain` or `synthesis scan` before performing searches if the codebase may have changed since the last scan.

---

## Quick Reference

```
# Search and discovery
synthesis search "query"                    # Full-text search
synthesis search --all "query"              # Cross-workspace search
synthesis which <file>                      # Find workspace for file
synthesis list                              # List workspaces

# Dependency analysis
synthesis relate <file>                     # Bi-directional dependencies
synthesis relate <file> --depth 2           # Deep traversal
synthesis relate <file> --mermaid           # Visual output
synthesis cross-repo-deps                   # Cross-repo dependencies

# Architecture
synthesis architecture --format json        # Machine-readable anti-patterns
synthesis graph --modules --format mermaid  # Module graph
synthesis insights                          # Codebase health

# AI-powered (requires API key)
synthesis ask "question"                    # Natural-language Q&A
synthesis explain <file>                    # File explanation
synthesis perspectives "question"           # Multi-angle analysis
synthesis research --topic <topic>          # Deep analysis

# Index management
synthesis maintain                          # Incremental update
synthesis scan                              # Full/incremental scan
synthesis status                            # Index health

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
- [Full User Guide](../USER-GUIDE-V2.md) -- Complete command reference
