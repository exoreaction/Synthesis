# MCP Server Comprehensive Guide

Synthesis provides a native MCP (Model Context Protocol) server that gives Claude Code, Cursor, and other MCP-compatible AI agents direct access to your workspace index. This guide covers everything from initial setup to advanced configuration, all 41 tools, and troubleshooting.

**Version:** 1.18.0 | **Java:** 21+ | **Protocol:** MCP v2024-11-05 | **Transports:** stdio, HTTP

---

## Table of Contents

- [Setup and Installation](#setup-and-installation)
- [Transport Modes](#transport-modes)
  - [HTTP Transport (Recommended)](#http-transport-recommended)
  - [stdio Transport](#stdio-transport)
  - [Running as a systemd Service](#running-as-a-systemd-service)
- [Tools Reference (41 tools)](#tools-reference)
  - [Search & Discovery](#search--discovery-6-tools)
  - [Architecture & Code](#architecture--code-7-tools)
  - [Insights & AI](#insights--ai-7-tools)
  - [Content & Documentation](#content--documentation-3-tools)
  - [Security & Quality](#security--quality-5-tools)
  - [Change Tracking](#change-tracking-3-tools)
  - [Operations](#operations-5-tools)
  - [Workspace Intelligence](#workspace-intelligence-5-tools)
- [Advanced Configuration](#advanced-configuration)
- [Integration](#integration)
- [Troubleshooting](#troubleshooting)
- [Performance](#performance)
- [Example Workflows](#example-workflows)

---

## Setup and Installation

### Prerequisites

| Requirement | Minimum | Check Command |
|-------------|---------|---------------|
| Java | 21+ | `java -version` |
| Synthesis | 1.18.0+ | `synthesis --version` |
| Workspace indexed | Yes | `synthesis status` |

### Installation

**Option A: Via installer (recommended)**

```bash
# Install Synthesis (includes MCP server)
curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash
```

This installs the `synthesis-mcp-server` launcher script to `~/.synthesis/bin/` and adds it to your PATH.

**Option B: Build from source**

```bash
git clone https://github.com/exoreaction/Synthesis.git
cd Synthesis
mvn clean package -DskipTests
# The MCP server launcher is at bin/synthesis-mcp-server
```

**Option C: Docker**

```bash
docker run -v /path/to/project:/workspace \
  exoreaction/synthesis:latest \
  synthesis-mcp-server --workspace /workspace --http-port 8765
```

### Initial Workspace Setup

Before the MCP server can serve queries, you must index your workspace:

```bash
cd ~/your-project
synthesis init --name "My Project"
synthesis scan
```

Verify the index is healthy:

```bash
synthesis status
# Expected output:
#   Workspace: /home/user/your-project
#   Status: Initialized
#   Files indexed: 1,234
#   Index size: 5.2 MB
```

---

## Transport Modes

Synthesis supports two transports. **HTTP is recommended** for most setups because stdio connections can be dropped when a Claude Code session goes idle.

### HTTP Transport (Recommended)

Start the server with `--http-port`:

```bash
synthesis-mcp-server --workspace /path/to/project --http-port 8765
```

Configure Claude Code (`~/.claude/config.json`):

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

**Health endpoint:** `GET http://localhost:8765/health` returns `{"status":"ok"}`. Use this to verify the server is running.

**Multi-workspace HTTP example:**

```bash
synthesis-mcp-server \
  --workspaces /src/backend,/src/frontend,/home/user/Documents \
  --name myorg \
  --http-port 8765
```

### stdio Transport

The classic mode. The client process spawns the server as a subprocess:

```json
{
  "mcpServers": {
    "synthesis": {
      "command": "synthesis-mcp-server",
      "args": ["--workspace", "/home/user/your-project"]
    }
  }
}
```

### Running as a systemd Service

For persistent HTTP transport on Linux, create a user-level systemd service:

```ini
# ~/.config/systemd/user/synthesis-mcp-http.service
[Unit]
Description=Synthesis MCP HTTP Server
After=network.target

[Service]
ExecStart=%h/.synthesis/bin/synthesis-mcp-server \
    --workspace %h/your-project \
    --http-port 8765
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

Enable and start:

```bash
systemctl --user enable synthesis-mcp-http
systemctl --user start synthesis-mcp-http
systemctl --user status synthesis-mcp-http
```

The service starts automatically on login and survives session idle.

---

## Tools Reference

The MCP server exposes **41 tools** grouped into seven categories. Tools marked **(AI)** require the `ANTHROPIC_API_KEY` environment variable. All other tools work offline.

### Search & Discovery (6 tools)

---

#### `search` -- Full-Text Search

Search across all file types (code, docs, videos, PDFs, configs) with Apache Lucene query syntax. Returns ranked results with snippets, metadata, and relevance scores.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `query` | string | **Yes** | -- | Search query (Lucene syntax: terms, phrases, booleans, wildcards, field:value) |
| `fileType` | string | No | `ALL` | Filter: `CODE`, `MARKDOWN`, `PDF`, `VIDEO`, `YAML`, `JSON`, `CONFIG`, `IMAGE`, `AUDIO`, `ALL` |
| `limit` | number | No | `20` | Max results (1-200) |
| `previewLength` | number | No | `300` | Snippet length in characters (100-3000). Increase to 1000-2000 to reduce follow-up reads. Excerpt is centred on the matching section. |
| `subWorkspace` | string | No | -- | Scope to a named sub-workspace (e.g., `eXOReaction`, `Cantara`) |
| `workspace` | string | No | server default | Override workspace path |

**Lucene Query Syntax:**

| Pattern | Example | Description |
|---------|---------|-------------|
| Simple term | `authentication` | Matches files containing the term |
| Exact phrase | `"error handling"` | Matches the exact phrase |
| Boolean AND | `testing AND strategy` | Both terms must appear |
| Boolean OR | `auth OR login` | Either term must appear |
| Boolean NOT | `testing NOT unit` | First term present, second absent |
| Wildcard | `auth*` | Matches auth, authentication, authorize, etc. |
| Field query | `language:Java` | Search specific metadata fields |

**Available search fields:** `language`, `fileType`, `repository`, `fileName`, `headings`, `keywords`, `content`

---

#### `relate` -- Relationship Analysis

Show bidirectional relationships for any file. Answers "What does this file depend on?" (outgoing) and "What depends on this file?" (incoming). Essential for understanding impact before making changes.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `filePath` | string | **Yes** | -- | File name or path to analyze |
| `format` | string | No | `json` | Output format: `json` or `mermaid` |
| `workspace` | string | No | server default | Override workspace path |

---

#### `which` -- Symbol/File Locator

Find which file(s) match a pattern or contain a symbol. Like the shell `which` command but for your codebase: resolves class names, function names, or path patterns to actual files.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `pattern` | string | **Yes** | -- | Class name, function name, or file path pattern to locate |
| `workspace` | string | No | server default | Override workspace path |

---

#### `discover` -- Hidden Pattern Discovery

Discover interesting patterns, hidden dependencies, and non-obvious relationships in the workspace. Surfaces things you did not know to look for.

**Parameters:** `workspace` (optional)

---

#### `diff` -- Synthesis-Aware Git Diff

Show a synthesis-aware diff against a git ref (e.g., `HEAD~1`, `main`, a commit SHA). Categorizes changes by type and significance.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `ref` | string | **Yes** | -- | Git ref to diff against (e.g., `HEAD~1`, `main`, commit SHA) |
| `workspace` | string | No | server default | Override workspace path |

---

#### `changed` -- Files Changed Since Date

List files changed since a date or duration (e.g., `2026-02-20` or `7d`). Groups by change type: added, modified, deleted.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `since` | string | **Yes** | -- | Date (e.g., `2026-02-20`) or duration (e.g., `7d`, `24h`, `2w`) |
| `workspace` | string | No | server default | Override workspace path |

---

### Architecture & Code (7 tools)

---

#### `graph` -- Architecture Visualization

Generate module-level, dependency, or cross-repository architecture graphs. Returns Mermaid, DOT (Graphviz), or structured JSON.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `mode` | string | No | `modules` | Graph type: `modules`, `dependencies`, `cross-repo` |
| `format` | string | No | `mermaid` | Output format: `mermaid`, `json`, `dot` |
| `filter` | string | No | -- | Filter to directory or repository pattern |
| `workspace` | string | No | server default | Override workspace path |

---

#### `code-graph` -- Code-Level Dependency Graph

Code-level dependency graph analysis with subcommands: `describe` (overview), `health` (quality metrics), `gaps` (missing coverage), `security` (vulnerability paths). Optional flags: `--cycles`, `--hotspots`.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `subcommand` | string | No | `""` | Subcommand: `describe`, `health`, `gaps`, `security` |
| `flags` | string | No | -- | Optional extra flags (e.g., `--cycles --hotspots`) |
| `workspace` | string | No | server default | Override workspace path |

---

#### `architecture` -- Architecture Overview

Generate an architecture overview of the workspace: layers, modules, key abstractions, and cross-cutting concerns.

**Parameters:** `workspace` (optional)

---

#### `knowledge-graph` -- Knowledge Graph

Build and display a knowledge graph of concepts, entities, and relationships extracted from the workspace. Use to understand the domain model and connections.

**Parameters:** `workspace` (optional)

---

#### `trace` -- Dependency Path Tracer

Trace the dependency path between two files or symbols. Shows the shortest connection chain. Use to understand how components relate.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `from` | string | **Yes** | -- | Source file or symbol to trace from |
| `to` | string | **Yes** | -- | Target file or symbol to trace to |
| `workspace` | string | No | server default | Override workspace path |

---

#### `impact` -- Change Impact Analysis

Transitive change impact analysis. Given a file, shows the full blast radius: all files that would be affected if it changes. Essential before refactoring.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `filePath` | string | **Yes** | -- | File path or class name to analyze change impact for |
| `depth` | number | No | `3` | Maximum transitive dependency depth (1-10) |
| `workspace` | string | No | server default | Override workspace path |

---

#### `cross-repo-deps` -- Cross-Repository Dependencies

Analyze cross-repository dependencies across all repos in the workspace. Shows which repos depend on which, with version and artifact details.

**Parameters:** `workspace` (optional)

---

### Insights & AI (7 tools)

---

#### `ask` -- AI-Powered Q&A **(AI)**

Ask natural language questions about the codebase. Searches the Synthesis index for relevant files, builds context, and generates an answer with file citations. Requires `ANTHROPIC_API_KEY`.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `query` | string | **Yes** | -- | The question to ask about the codebase |
| `workspace` | string | No | server default | Override workspace path |

---

#### `insights` -- AI Codebase Insights **(AI)**

Generate AI-powered codebase insights: patterns, anomalies, improvement suggestions. Higher-level than `analyze` -- focuses on actionable observations.

**Parameters:** `workspace` (optional)

---

#### `perspectives` -- Multi-Role Perspectives **(AI)**

Answer a question about the codebase from multiple role perspectives (architect, security, devops, product). Requires `ANTHROPIC_API_KEY`.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `question` | string | **Yes** | -- | Question to answer from multiple role perspectives |
| `workspace` | string | No | server default | Override workspace path |

---

#### `research` -- Deep Research **(AI)**

Deep research into a codebase topic. Searches the index, follows references, and synthesizes a comprehensive answer. Requires `ANTHROPIC_API_KEY`.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `query` | string | **Yes** | -- | Research query to investigate in the codebase |
| `workspace` | string | No | server default | Override workspace path |

---

#### `analyze` -- Workspace Analysis

Run comprehensive workspace analysis. Returns file type distribution, complexity metrics, and structural overview. Use to understand a codebase quickly.

**Parameters:** `workspace` (optional)

---

#### `summary` -- Executive Summary **(AI optional)**

Generate executive summary of the codebase with AI-enhanced analysis. Choose detail level and role perspective. Results are cached for instant retrieval.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `level` | string | No | `executive` | Detail level: `executive`, `manager`, `developer` |
| `perspective` | string | No | `general` | Role: `general`, `executive`, `engineering_manager`, `architect`, `security`, `devops`, `product_manager`, `developer` |
| `format` | string | No | `markdown` | Output format: `markdown`, `json`, `terminal` |
| `since` | string | No | -- | Include recent changes: duration (`7d`, `24h`, `2w`, `3m`) or ISO date (`2026-01-15`). Bypasses cache. |
| `noAi` | boolean | No | `false` | Skip AI-enhanced summary (faster, metrics-only) |
| `noCache` | boolean | No | `false` | Skip cache and force fresh generation |
| `workspace` | string | No | server default | Override workspace path |

---

#### `report` -- AI Business Reports **(AI)**

Generate AI-powered business reports. Topics: weekly executive, pipeline status, activities, decisions. Target audiences: CEO, board, investor. Requires `ANTHROPIC_API_KEY`.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `topic` | string | No | `weekly` | Report topic: `weekly`, `pipeline`, `activities`, `executive`, `decisions` |
| `target` | string | No | `ceo` | Audience: `ceo`, `board`, `investor` |
| `period` | string | No | `1w` | Coverage period: `1w`, `2w`, `1m` |
| `noCache` | boolean | No | `false` | Skip cache and force fresh generation |
| `workspace` | string | No | server default | Override workspace path |

---

### Content & Documentation (3 tools)

---

#### `export` -- Export Workspace Index

Export the workspace index as Markdown, JSON, KCP, architecture doc, or onboarding guide. Useful for sharing workspace overviews, generating AI context, or creating documentation.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `format` | string | No | `markdown` | Export format: `markdown`, `json`, `kcp`, `architecture-doc`, `onboarding-guide` |
| `fileType` | string | No | -- | Filter by file type (e.g., `CODE`, `MARKDOWN`, `YAML`, `PDF`) |
| `limit` | number | No | `1000` | Maximum number of entries to export (1-50000) |
| `workspace` | string | No | server default | Override workspace path |

Note: `kcp` format produces Knowledge Context Protocol output compatible with external AI tools.

---

#### `enrich` -- Companion File Generation

Generate `.synthesis.md` companion files for binary assets (images, videos, PDFs, audio). Makes binary content searchable by extracting metadata, text, and AI descriptions. Run with `filePath` for a single file or without for batch mode.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `filePath` | string | No | -- | Path to a specific file to enrich (omit for batch mode) |
| `level` | string | No | `basic` | Enrichment level: `basic` (metadata only), `local` (with local tools), `ai` (with Claude) |
| `force` | boolean | No | `false` | Force regeneration even if companion file exists |
| `workspace` | string | No | server default | Override workspace path |

---

#### `explain` -- AI Code Explanation **(AI)**

AI-powered explanation of files, directories, or architectural patterns. Generates comprehensive explanations with code references and context. Requires `ANTHROPIC_API_KEY`.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `target` | string | **Yes** | -- | File path, directory path, or pattern name to explain |
| `includeContext` | boolean | No | `true` | Include related files in explanation context |
| `depth` | string | No | `standard` | Depth: `brief` (3-5 sentences), `standard` (sections), `deep` (comprehensive) |
| `workspace` | string | No | server default | Override workspace path |

---

### Security & Quality (5 tools)

---

#### `security` -- Security Analysis

Security analysis findings for the workspace. Shows vulnerability counts by severity (HIGH/MEDIUM/LOW/INFO), including both traditional and agentic security signals.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `severity` | string | No | -- | Filter findings by severity level: `HIGH`, `MEDIUM`, `LOW`, `INFO` |
| `refresh` | boolean | No | `false` | Re-run security analysis before returning results |
| `format` | string | No | `summary` | Output format: `summary` (counts) or `json` (full findings) |
| `workspace` | string | No | server default | Override workspace path |

---

#### `health` -- Workspace Health Audit

Run workspace structural health audit. Checks for phantom paths, build artifacts, empty directories, and loose root files. Returns a health score (0-100) and grade.

**Parameters:** `workspace` (optional)

---

#### `validate` -- Workspace Integrity Validation

Validate workspace integrity: broken links, missing references, orphaned files, and configuration issues. Returns pass/fail with actionable fixes.

**Parameters:** `workspace` (optional)

---

#### `metrics` -- Codebase Metrics

Compute codebase metrics: lines of code, complexity, test coverage estimates, documentation ratio, and dependency counts.

**Parameters:** `workspace` (optional)

---

#### `scatter` -- Scattered Concerns Detection

Detect scattered concerns: logic spread across many files that should be consolidated. Identifies code duplication patterns and cohesion issues.

**Parameters:** `workspace` (optional)

---

### Change Tracking (3 tools)

---

#### `changelog` -- Change History

Show workspace change history. Returns added, modified, and deleted files with significance classification. Use to understand what changed recently.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `since` | string | No | `24h` | Time period: `24h`, `7d`, `2w`, `30d` |
| `workspace` | string | No | server default | Override workspace path |

---

#### `track` -- File Movement Tracking

Track file movements using hash-based detection. Shows files that were moved or renamed, with confidence scores and audit trail.

**Parameters:** `workspace` (optional)

---

#### `status` -- Workspace Status

Show current workspace status: index freshness, pending changes, scan state, and configuration summary.

**Parameters:** `workspace` (optional)

---

### Operations (5 tools)

---

#### `scan` -- Index Workspace

Scan and index all files in the workspace. Creates or updates the Synthesis index. Run after adding new files or on first setup.

**Parameters:** `workspace` (optional)

---

#### `maintain` -- Full Workspace Maintenance

Run full workspace maintenance: re-index changed files, update relations, refresh snapshots, and track movements. Long-running (may take minutes).

**Parameters:** `workspace` (optional)

---

#### `stats` -- Workspace Statistics

Get workspace statistics: file counts by type, index size, health status, and last scan time. Use to verify the workspace is indexed and healthy.

**Parameters:** `workspace` (optional)

**Response Format:**

```json
{
  "workspace": "/home/user/project",
  "totalFiles": 8934,
  "fileTypes": {
    "CODE": 3241,
    "MARKDOWN": 1567,
    "YAML": 423,
    "JSON": 312,
    "CONFIG": 89,
    "PDF": 45,
    "IMAGE": 234,
    "VIDEO": 12,
    "AUDIO": 5,
    "DOCUMENT": 6
  },
  "indexSizeBytes": 12189456,
  "indexSize": "11.6 MB",
  "lastScan": "2026-02-15T08:30:00Z",
  "health": "healthy",
  "timestamp": "2026-02-15T10:45:23Z"
}
```

---

#### `mcp-stats` -- MCP Usage Statistics

Show MCP server usage statistics: tool invocation counts, response times, error rates, and popular queries. Reads the global MCP query log.

**Parameters:** none

---

#### `upcoming` -- Upcoming Tasks & Deadlines

Show upcoming tasks, TODOs, FIXMEs, and deadlines found in the codebase. Extracts actionable items from comments and documentation.

**Parameters:** `workspace` (optional)

---

### Workspace Intelligence (5 tools)

---

#### `describe` -- Describe File or Directory

Describe a file or directory within the workspace. Without a path, describes the workspace root. Returns purpose, contents, and key observations.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `path` | string | No | workspace root | File or directory path to describe |
| `workspace` | string | No | server default | Override workspace path |

---

#### `structure` -- Smart Tree View

Show workspace directory structure with annotations: purpose of each directory, file counts, and notable patterns.

**Parameters:** `workspace` (optional)

---

#### `evolution` -- Evolution Analysis

Analyze how the workspace has evolved over time: growth trends, churn hotspots, and maturity assessment by module.

**Parameters:** `workspace` (optional)

---

#### `naming` -- Naming Convention Analysis

Analyze naming conventions across the codebase. Detects inconsistencies, suggests improvements, and checks adherence to project naming patterns.

**Parameters:** `workspace` (optional)

---

#### `learn` -- Learning Guide Generation

Generate a learning guide for the codebase: key concepts, entry points, recommended reading order, and architectural patterns to understand first.

**Parameters:** `workspace` (optional)

---

## Advanced Configuration

### Multiple Workspaces

**Option A: Separate server instances (stdio)**

```json
{
  "mcpServers": {
    "synthesis-docs": {
      "command": "synthesis-mcp-server",
      "args": ["--workspace", "/home/user/Documents"]
    },
    "synthesis-backend": {
      "command": "synthesis-mcp-server",
      "args": ["--workspace", "/home/user/src/backend"]
    },
    "synthesis-frontend": {
      "command": "synthesis-mcp-server",
      "args": ["--workspace", "/home/user/src/frontend"]
    }
  }
}
```

**Option B: Single unified server (HTTP, multi-workspace)**

```bash
synthesis-mcp-server \
  --workspaces /home/user/Documents,/home/user/src/backend,/home/user/src/frontend \
  --name myorg \
  --http-port 8765
```

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

### Log Level Configuration

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

Available log levels: `FINE` (verbose), `INFO`, `WARNING` (default), `SEVERE`

Logs are written to `~/.synthesis/logs/mcp-server.log` (5 MB rotating, 3 files).

### Performance Tuning

**JVM options** via environment variable:

```bash
export SYNTHESIS_JAVA_OPTS="-Xmx2g -XX:+UseG1GC"
```

Or in Claude Code config:

```json
{
  "mcpServers": {
    "synthesis": {
      "command": "synthesis-mcp-server",
      "args": ["--workspace", "/path/to/project"],
      "env": {
        "SYNTHESIS_JAVA_OPTS": "-Xmx2g"
      }
    }
  }
}
```

**Index optimization:** After scanning, run `synthesis maintain` to compact the index for faster queries.

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SYNTHESIS_HOME` | Installation directory | `~/.synthesis` |
| `SYNTHESIS_JAVA_OPTS` | JVM options (memory, GC, etc.) | (none) |
| `ANTHROPIC_API_KEY` | Required for AI-powered tools (ask, explain, insights, etc.) | (none) |

### Command-Line Options

```
synthesis-mcp-server [OPTIONS]

Options:
  --workspace, -w <path>     Single workspace root directory (default: current dir)
  --workspaces <p1,p2,...>   Multiple workspace paths (comma-separated)
  --name <name>              Display name for this MCP server
  --http-port <port>         Enable HTTP transport on the given port
  --log-level <level>        Logging level: FINE, INFO, WARNING, SEVERE
  --version, -v              Print version and exit
  --help, -h                 Print this help and exit
```

---

## Integration

### Claude Code

**HTTP transport (recommended):**

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

**stdio transport:**

```json
{
  "mcpServers": {
    "synthesis": {
      "command": "synthesis-mcp-server",
      "args": ["--workspace", "/path/to/project"]
    }
  }
}
```

The agent automatically discovers all 41 tools via the `tools/list` MCP method. AI-powered tools (`ask`, `explain`, `insights`, `perspectives`, `research`, `report`, `summary`) require `ANTHROPIC_API_KEY` to be set.

### Cursor

**Configuration:** Settings > MCP Servers, or in `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "synthesis": {
      "command": "synthesis-mcp-server",
      "args": ["--workspace", "/path/to/project"]
    }
  }
}
```

### Aider

Configure in `.aider.conf.yml`:

```yaml
mcp-servers:
  - name: synthesis
    command: synthesis-mcp-server
    args: ["--workspace", "/path/to/project"]
```

### Custom MCP Clients

Any MCP-compatible client can connect to the Synthesis MCP server. The server communicates over stdio (JSON-RPC 2.0) or HTTP.

**Minimal stdio client connection:**

1. Spawn the server process: `synthesis-mcp-server --workspace /path`
2. Send `initialize` request via stdin
3. Receive capabilities response via stdout
4. Send `initialized` notification
5. Call tools via `tools/call` requests
6. Send `shutdown` request when done

**HTTP client:** POST `application/json` JSON-RPC 2.0 messages to `http://localhost:8765/mcp`.

See the [MCP Protocol Reference](../api/MCP-PROTOCOL-REFERENCE.md) for full protocol details.

---

## Troubleshooting

### Server Won't Start

**Symptom:** `Error: Java not found` or `Error: Java 11 found, but Java 21+ is required.`

**Fix:** Install Java 21 or later. Verify with `java -version`.

```bash
# Ubuntu/Debian
sudo apt install openjdk-21-jre

# macOS (Homebrew)
brew install openjdk@21

# Verify
java -version
```

**Symptom:** `Error: Synthesis MCP Server JAR not found`

**Fix:** Install Synthesis or build from source:

```bash
# Option 1: Install
curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash

# Option 2: Build from source
cd /path/to/synthesis
mvn package -DskipTests
```

### "Not a Synthesis workspace" / "Workspace not initialized"

**Fix:** Initialize and scan the workspace:

```bash
cd /path/to/your-project
synthesis init
synthesis scan
```

Then verify:

```bash
synthesis status
```

### HTTP Server Not Responding

**Symptom:** `curl http://localhost:8765/health` returns connection refused.

**Checks:**

1. Verify the server started: `ps aux | grep synthesis-mcp-server`
2. Check the log: `tail ~/.synthesis/logs/mcp-server.log`
3. Confirm the port is not in use by another process: `ss -tlnp | grep 8765`
4. If using systemd: `systemctl --user status synthesis-mcp-http`

### Empty Search Results

**Symptom:** `search` returns `{"results": [], "totalHits": 0}`.

**Possible causes and fixes:**

1. **Index is stale.** Re-scan: `synthesis scan`
2. **Index is empty.** Check that `synthesis status` shows files. If not, verify your `includePatterns` in `.synthesis/config.yaml`.
3. **Query syntax error.** Lucene treats some characters as special. Escape with backslash: `\+`, `\-`, `\(`, `\)`.
4. **Wrong fileType filter.** Use `ALL` or omit the `fileType` parameter.
5. **Wrong workspace path.** Verify the `--workspace` argument points to the directory containing `.synthesis/`.

### Slow Responses

**Symptom:** Tool calls take more than 2-3 seconds.

**Possible causes and fixes:**

1. **First query after startup.** JVM warm-up can make the first query slower. Subsequent queries use warm caches.
2. **Very large workspace.** For workspaces > 50,000 files, increase JVM memory: `SYNTHESIS_JAVA_OPTS="-Xmx2g"`
3. **Fragmented index.** Run `synthesis maintain` to optimize the index.
4. **Disk I/O bottleneck.** Ensure the workspace is on SSD, not HDD.

### JSON-RPC Errors

**Common error codes:**

| Code | Meaning | Fix |
|------|---------|-----|
| `-32700` | Parse error (invalid JSON) | Check that only JSON-RPC messages are sent to stdin |
| `-32600` | Invalid request (missing `jsonrpc` field) | Ensure messages include `"jsonrpc": "2.0"` |
| `-32601` | Method not found | Use only supported methods: `initialize`, `tools/list`, `tools/call`, `shutdown`, `ping` |
| `-32602` | Invalid params (missing required parameter) | Check tool parameter requirements |
| `-32603` | Internal error | Check `~/.synthesis/logs/mcp-server.log` for stack traces |

### Debugging

Enable verbose logging to diagnose issues:

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

Then inspect the log:

```bash
tail -f ~/.synthesis/logs/mcp-server.log
```

The log shows every JSON-RPC message received and sent, tool invocations, and timing information.

---

## Performance

### Benchmarks

Measured on a standard development laptop (16 GB RAM, SSD) with an 8,934-file workspace (1.0 GB content, 11.6 MB index).

| Operation | Typical Time | Notes |
|-----------|-------------|-------|
| Search (limit=5) | 0.1-0.2s | Sub-second for any query |
| Search (limit=20) | 0.1-0.3s | Default limit |
| Search (limit=200) | 0.2-0.5s | Maximum limit |
| Relate (small file, <10 refs) | 0.3-0.5s | Fast for focused files |
| Relate (large file, 50+ refs) | 0.5-1.5s | Scales with reference count |
| Graph (modules) | 0.1-0.3s | Aggregated directory view |
| Graph (dependencies, full) | 0.5-2.0s | Depends on workspace size |
| Graph (cross-repo) | 0.5-3.0s | Depends on repository count |
| Stats | 0.05-0.1s | Metadata only, no search |
| Impact (depth=3) | 0.3-1.0s | Transitive lookup via index |
| Ask | 2-8s | Depends on context size + Claude API latency |
| Enrich (single) | 0.1-3s | Depends on enrichment level (basic < local < ai) |
| Enrich (batch, 50 files) | 5-30s | Parallel processing, varies with level |
| Explain (file) | 2-5s | Depends on file size + Claude API latency |
| Explain (module) | 3-10s | Depends on module size + Claude API latency |

### Scaling Characteristics

| Metric | Scaling | Notes |
|--------|---------|-------|
| Search time | O(log n) | Lucene inverted index |
| Relate time | O(m) | m = number of relationships |
| Graph time | O(n + e) | n = nodes, e = edges |
| Index size | ~500 KB per 1,000 files | 2-5% of source content |
| Memory usage | 200-500 MB typical | JVM heap |

### Agent Productivity Metrics

Based on real-world usage with 8,934 indexed files:

| Metric | Without Synthesis | With Synthesis | Improvement |
|--------|------------------|----------------|-------------|
| File discovery time | 5-15 min (grep/find) | 10-30 sec | 92-95% reduction |
| Relationship mapping | Manual (error-prone) | Automated (bidirectional) | 0% missed refs |
| Architecture overview | Hours (manual) | Seconds (graph tool) | 100x faster |
| Breaking changes during refactoring | ~38% | ~0% | Eliminated |

---

## Example Workflows

### 1. Refactoring Without Breaking Changes

Before renaming a class or moving a file, use `impact` and `relate` to see the full effect:

> "I want to rename TokenManager to SessionManager. Use synthesis impact and relate to show me all affected files."

The agent will:
1. Call `impact` with `filePath: "TokenManager.java"` to see the full blast radius
2. Call `relate` with `filePath: "TokenManager.java"` for direct references
3. List every file that needs updating
4. Make changes with confidence that nothing is missed

### 2. Architecture Review

When onboarding to a new project or preparing for an architecture discussion:

> "Show me the architecture of this project using synthesis."

The agent will:
1. Call `stats` to understand workspace scope
2. Call `architecture` for a layered architecture overview
3. Call `graph` with `mode: "modules"` to visualize the module structure
4. Call `search` to find key configuration files
5. Provide an informed architectural overview with a dependency diagram

### 3. Security Audit

When preparing a security review:

> "Run a security audit on this workspace."

The agent will:
1. Call `security` with `refresh: true` to get fresh findings
2. Call `code-graph` with `subcommand: "security"` for vulnerability paths
3. Present findings by severity (HIGH first)
4. Suggest remediation for each finding

### 4. Understanding Recent Changes

When returning to a project after time away:

> "What changed in this codebase in the last week?"

The agent will:
1. Call `changelog` with `since: "7d"` for workspace changes
2. Call `changed` with `since: "7d"` for file-level listing
3. Call `summary` with `since: "7d"` for an AI-generated change summary
4. Present a clear picture of what evolved

### 5. Cross-Repository Discovery

When working with microservices or multi-repo setups:

> "Find all services that depend on the payment API."

The agent will:
1. Call `search` for "payment" to locate the payment API files
2. Call `relate` on the payment API entry point
3. Call `cross-repo-deps` to show inter-service dependencies
4. Call `graph` with `mode: "cross-repo"` to visualize the graph

### 6. Onboarding New Developers

When a new team member needs to understand the codebase:

> "I'm new to this project. Help me understand how authentication works."

The agent will:
1. Call `learn` to get a recommended reading order and entry points
2. Call `ask` with the authentication question for a grounded AI answer
3. Call `explain` on the auth module for a comprehensive explanation
4. Call `graph` to show where auth fits in the overall architecture

### 7. KCP Export for External Tools

When you need to share workspace context with tools outside Claude Code:

> "Export the workspace index in KCP format."

The agent will:
1. Call `export` with `format: "kcp"` to generate Knowledge Context Protocol output
2. The export can then be loaded into compatible AI tools

---

## See Also

- **[MCP Quick Start](./MCP-QUICKSTART.md)** -- 5-minute setup guide
- **[MCP Protocol Reference](../api/MCP-PROTOCOL-REFERENCE.md)** -- JSON-RPC protocol details
- **[MCP Performance Benchmarks](./MCP-PERFORMANCE-BENCHMARKS.md)** -- Detailed performance data
- **[LSP Server Guide](./LSP-COMPREHENSIVE-GUIDE.md)** -- IDE integration via Language Server Protocol
- **[Quick Start](./QUICK-START.md)** -- CLI usage guide
- **[User Guide](./USER-GUIDE.md)** -- Complete Synthesis reference
