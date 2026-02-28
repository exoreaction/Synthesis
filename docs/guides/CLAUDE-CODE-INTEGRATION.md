# Claude Code Integration (MCP Server)

> **See also:** For comprehensive documentation, refer to the new dedicated guides:
> - **[MCP Quick Start](./MCP-QUICKSTART.md)** -- 5-minute setup
> - **[MCP Comprehensive Guide](./MCP-COMPREHENSIVE-GUIDE.md)** -- Full tool reference, advanced config, troubleshooting
> - **[MCP Protocol Reference](../api/MCP-PROTOCOL-REFERENCE.md)** -- JSON-RPC protocol details
> - **[MCP Performance Benchmarks](./MCP-PERFORMANCE-BENCHMARKS.md)** -- Response times and scaling data

Synthesis provides a native MCP (Model Context Protocol) server that gives Claude Code and other AI agents direct access to your workspace index -- enabling sub-second search, relationship analysis, architecture visualization, security scanning, change tracking, and much more.

## Quick Start

### 1. Install Synthesis

```bash
curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash
```

### 2. Index Your Workspace

```bash
cd ~/your-project
synthesis init
synthesis scan
```

### 3. Configure Claude Code

**Option A: HTTP Transport (Recommended)**

Start the server:

```bash
synthesis-mcp-server --workspace /absolute/path/to/your-project --http-port 8765
```

Add to `~/.claude/config.json`:

```json
{
  "mcpServers": {
    "synthesis": {
      "type": "http",
      "url": "http://localhost:8765/mcp"
    }
  }
}
```

HTTP is recommended because it survives session idle timeouts that can drop stdio connections.

**Option B: stdio Transport**

Add to `~/.claude/config.json`:

```json
{
  "mcpServers": {
    "synthesis": {
      "command": "synthesis-mcp-server",
      "args": ["--workspace", "/absolute/path/to/your-project"]
    }
  }
}
```

### 4. Verify

Start a new Claude Code session. You should see `synthesis` listed in the MCP tools. Try:

> "Use synthesis to search for authentication-related files"

For HTTP: verify with `curl http://localhost:8765/health` which returns `{"status":"ok"}`.

---

## Available Tools (43 total)

The MCP server exposes **43 tools** grouped by category. See the [MCP Comprehensive Guide](./MCP-COMPREHENSIVE-GUIDE.md) for full parameter documentation on all tools.

### Search & Discovery (6 tools)

| Tool | Description |
|------|-------------|
| `search` | Full-text search across all file types with Lucene query syntax |
| `relate` | Bidirectional relationships: what a file depends on and what depends on it |
| `which` | Locate files by class name, function name, or path pattern |
| `discover` | Surface hidden patterns and non-obvious relationships |
| `diff` | Synthesis-aware diff against a git ref |
| `changed` | Files changed since a date or duration |

### Architecture & Code (7 tools)

| Tool | Description |
|------|-------------|
| `graph` | Module, dependency, or cross-repo architecture graphs (Mermaid/DOT/JSON) |
| `code-graph` | Code-level dependency analysis with describe/health/gaps/security subcommands |
| `architecture` | Architecture overview: layers, modules, key abstractions |
| `knowledge-graph` | Knowledge graph of concepts, entities, and relationships |
| `trace` | Shortest dependency path between two files or symbols |
| `impact` | Transitive blast radius: all files affected by changing a given file |
| `cross-repo-deps` | Cross-repository dependency analysis |

### Insights & AI (7 tools)

| Tool | Description |
|------|-------------|
| `ask` | AI-powered Q&A grounded in indexed files (requires `ANTHROPIC_API_KEY`) |
| `insights` | AI codebase insights: patterns, anomalies, improvement suggestions |
| `perspectives` | Answer a question from multiple role perspectives (architect, security, devops, product) |
| `research` | Deep multi-pass research into a codebase topic |
| `analyze` | File type distribution, complexity metrics, structural overview |
| `summary` | Executive summary with role perspective and detail level options |
| `report` | AI business reports: weekly, pipeline, activities, executive, decisions |

### Content & Documentation (3 tools)

| Tool | Description |
|------|-------------|
| `export` | Export as Markdown, JSON, KCP, architecture-doc, or onboarding-guide |
| `enrich` | Generate `.synthesis.md` companion files for binary assets (images, videos, PDFs) |
| `explain` | AI-powered explanation of files, directories, or architectural patterns |

### Security & Quality (5 tools)

| Tool | Description |
|------|-------------|
| `security` | Security findings by severity (HIGH/MEDIUM/LOW/INFO) with optional refresh |
| `health` | Workspace structural health audit: health score 0-100 and grade |
| `validate` | Integrity validation: broken links, missing references, orphaned files |
| `metrics` | Codebase metrics: LOC, complexity, test coverage estimates, doc ratio |
| `scatter` | Detect scattered concerns: logic that should be consolidated |

### Change Tracking (3 tools)

| Tool | Description |
|------|-------------|
| `changelog` | Change history: added/modified/deleted files with significance classification |
| `track` | File movement tracking with hash-based detection and audit trail |
| `status` | Index freshness, pending changes, scan state, configuration summary |

### Operations (5 tools)

| Tool | Description |
|------|-------------|
| `stats` | File counts by type, index size, health status, last scan time |
| `scan` | Index all files in the workspace (creates or updates the Synthesis index) |
| `maintain` | Full maintenance: re-index, update relations, refresh snapshots, track movements |
| `mcp-stats` | MCP server usage statistics: invocation counts, response times, error rates |
| `upcoming` | Upcoming tasks, TODOs, FIXMEs, and deadlines from comments and docs |

### Workspace Intelligence (5 tools)

| Tool | Description |
|------|-------------|
| `describe` | Describe a file or directory: purpose, contents, key observations |
| `structure` | Smart tree view with directory annotations and file counts |
| `evolution` | Evolution analysis: growth trends, churn hotspots, maturity by module |
| `naming` | Naming convention analysis: inconsistencies and improvement suggestions |
| `learn` | Learning guide: key concepts, entry points, recommended reading order |

---

## Agent Workflows

### Workflow 1: Safe Refactoring

> "I want to rename TokenManager to SessionManager. What would break?"

Claude Code will:
1. Use `impact` to find the full transitive blast radius
2. Use `relate` to find all direct imports/references
3. List every file that needs updating
4. Make changes with confidence

### Workflow 2: Onboarding / Exploration

> "What's the architecture of this project?"

Claude Code will:
1. Use `architecture` for a layered architecture overview
2. Use `graph` to get a module dependency diagram
3. Use `stats` to understand the workspace scope
4. Use `learn` for a recommended reading order
5. Provide an informed architectural overview

### Workflow 3: Security Review

> "What security issues does this workspace have?"

Claude Code will:
1. Use `security` with `refresh: true` to scan and return findings
2. Use `code-graph` with `subcommand: "security"` for vulnerability paths
3. Present findings by severity

### Workflow 4: Understanding Recent Changes

> "What changed in the last week?"

Claude Code will:
1. Use `changelog` with `since: "7d"` for a classified change summary
2. Use `changed` with `since: "7d"` for file-level listing
3. Use `summary` with `since: "7d"` for an AI narrative of the changes

### Workflow 5: Session Lifecycle Integration

> "Set up automatic codebase context injection for every Claude Code session"

This bridges the session lifecycle gap: every Claude Code session automatically receives a fresh codebase context snapshot on startup, without any manual steps.

**Step 1: Generate the hook configuration**

```bash
synthesis hooks generate
```

Writes a `UserPromptSubmit` hook to `~/.claude/settings.json` that runs `synthesis session-context --compact` on every session start. Idempotent — running it again is safe.

**Step 2: Verify what will be injected**

```bash
synthesis hooks generate --dry-run
synthesis session-context --compact
# workspace:13041files·20.3MB | changed:0files(24h) | security:91HIGH·13MEDIUM
```

**Step 3: Every session gets automatic context**

On each session start, the hook injects a compact line with workspace size, recent changes, security posture, and active packages — before the first tool call.

Use `session_context` MCP tool to retrieve this snapshot programmatically:
```json
{"name": "session_context", "arguments": {"since": "24h", "compact": true}}
```

**Step 4: Keep CLAUDE.md fresh (optional)**

```bash
synthesis claude-md refresh
```

Maintains a `<!-- synthesis-stats:start -->` / `<!-- synthesis-stats:end -->` managed section in your CLAUDE.md with current stats. Only the managed section is touched — your existing content is preserved completely.

**Complementary to personal knowledge tools (e.g., Ars Contexta):** Synthesis provides codebase knowledge (what exists, what changed, what's at risk). Combine with personal knowledge management for complete session context — both local-first, both MCP.

---

## Configuration

### Multiple Workspaces

**Separate instances (stdio):**

```json
{
  "mcpServers": {
    "synthesis-docs": {
      "command": "synthesis-mcp-server",
      "args": ["--workspace", "/home/user/Documents"]
    },
    "synthesis-code": {
      "command": "synthesis-mcp-server",
      "args": ["--workspace", "/home/user/src/myproject"]
    }
  }
}
```

**Unified server (HTTP):**

```bash
synthesis-mcp-server \
  --workspaces /home/user/Documents,/home/user/src/myproject \
  --name myorg \
  --http-port 8765
```

### Logging

```json
{
  "mcpServers": {
    "synthesis": {
      "command": "synthesis-mcp-server",
      "args": ["--workspace", "/path/to/project", "--log-level", "FINE"]
    }
  }
}
```

Logs are written to `~/.synthesis/logs/mcp-server.log`.

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SYNTHESIS_HOME` | Installation directory | `~/.synthesis` |
| `SYNTHESIS_JAVA_OPTS` | JVM options (e.g., `-Xmx2g`) | (none) |
| `ANTHROPIC_API_KEY` | Required for AI-powered tools | (none) |

---

## Troubleshooting

### "Synthesis JAR not found"

Install Synthesis or build from source:
```bash
cd /path/to/synthesis && mvn package -DskipTests
```

### "Not a Synthesis workspace"

Initialize and scan first:
```bash
cd /path/to/project
synthesis init
synthesis scan
```

### "No results found"

The index may be stale. Re-scan:
```bash
synthesis maintain   # Incremental update
synthesis scan       # Full rebuild
```

### Performance

- Search: < 0.5 seconds (validated on 8,934 files)
- Relate: < 1 second (depends on file count)
- Graph: < 2 seconds (module-level)
- Stats: < 0.1 seconds

---

## Technical Details

- **Version:** 1.18.0
- **Protocol:** MCP v2024-11-05 over JSON-RPC 2.0
- **Transports:** stdio (JSON lines) and HTTP (`--http-port`)
- **Health endpoint (HTTP):** `GET /health` returns `{"status":"ok"}`
- **Index:** Apache Lucene (same index as CLI)
- **Java:** 21+ required
- **Logging:** File-based (`~/.synthesis/logs/mcp-server.log`), not stdout
