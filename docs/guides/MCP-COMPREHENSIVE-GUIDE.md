# MCP Server Comprehensive Guide

Synthesis provides a native MCP (Model Context Protocol) server that gives Claude Code, Cursor, and other MCP-compatible AI agents direct access to your workspace index. This guide covers everything from initial setup to advanced configuration and troubleshooting.

**Version:** 1.0.4-SNAPSHOT | **Protocol:** MCP v2024-11-05 | **Transport:** JSON-RPC 2.0 over stdio

---

## Table of Contents

- [Setup and Installation](#setup-and-installation)
- [Tools Reference](#tools-reference)
  - [search](#search---full-text-search)
  - [relate](#relate---relationship-analysis)
  - [graph](#graph---architecture-visualization)
  - [stats](#stats---workspace-health)
  - [ask](#ask---ai-powered-qa) (AI)
  - [enrich](#enrich---companion-file-generation)
  - [explain](#explain---ai-code-explanation) (AI)
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
| Java | 17+ | `java -version` |
| Synthesis | 1.0.0+ | `synthesis --version` |
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
  synthesis-mcp-server --workspace /workspace
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

### Configuration

Add to `~/.claude/config.json`:

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

### Verification

Start a new Claude Code session. The MCP server should appear in the tool list. Test with:

> "Use synthesis to show workspace stats"

If the agent successfully calls the `stats` tool and returns file counts, the server is working.

---

## Tools Reference

The MCP server exposes seven tools. Each tool accepts JSON parameters and returns structured JSON results wrapped in MCP content blocks. The first four tools (search, relate, graph, stats) work offline. The AI-powered tools (ask, explain) require an `ANTHROPIC_API_KEY` environment variable. The enrich tool works at basic level without AI, or at AI level with an API key.

### `search` -- Full-Text Search

Search across all file types (code, docs, videos, PDFs, configs) with Apache Lucene query syntax. Returns ranked results with snippets, metadata, and relevance scores.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `query` | string | **Yes** | -- | Search query (Lucene syntax) |
| `fileType` | string | No | `ALL` | Filter: `CODE`, `MARKDOWN`, `PDF`, `VIDEO`, `YAML`, `JSON`, `CONFIG`, `IMAGE`, `AUDIO`, `ALL` |
| `limit` | number | No | `20` | Max results (1-200) |
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
| Combined | `security AND fileType:CODE` | Combine multiple criteria |

**Available search fields:** `language`, `fileType`, `repository`, `fileName`, `headings`, `keywords`, `content`

**Example Queries:**

```
# Find all Java authentication code
"authentication" with fileType="CODE"

# Find deployment configurations
"deployment" with fileType="YAML"

# Find all files referencing a specific class
"AuthService"

# Find PDF documentation about security
"security policy" with fileType="PDF"

# Find files in a specific repository (multi-repo workspace)
"pipeline" -- results include repository metadata

# Boolean search for testing strategy docs
"testing AND strategy" with fileType="MARKDOWN"

# Wildcard search for all config patterns
"config*"

# Search for exact error message
"\"NullPointerException in line\""

# Find all files by a specific language
"language:Python"

# Search with result limit
"TODO" with limit=50
```

**Response Format:**

```json
{
  "results": [
    {
      "path": "/home/user/project/src/auth/AuthService.java",
      "relativePath": "src/auth/AuthService.java",
      "type": "CODE",
      "score": 2.45,
      "fileName": "AuthService.java",
      "snippet": "Authentication service handling OAuth2 flows...",
      "metadata": {
        "size": 12345,
        "language": "Java",
        "headings": "AuthService, authenticate, refreshToken",
        "structure": "class AuthService { authenticate(), refreshToken() }",
        "repository": "main-app"
      }
    }
  ],
  "totalHits": 23,
  "searchTime": "0.1s",
  "workspace": "/home/user/project"
}
```

**Response fields explained:**

| Field | Description |
|-------|-------------|
| `path` | Absolute file path |
| `relativePath` | Path relative to workspace root |
| `type` | File classification (CODE, MARKDOWN, PDF, etc.) |
| `score` | Lucene relevance score (higher = more relevant) |
| `fileName` | Just the file name |
| `snippet` | Content preview (up to 300 characters) |
| `metadata.size` | File size in bytes |
| `metadata.language` | Detected programming language (code files only) |
| `metadata.headings` | Extracted headings/declarations |
| `metadata.structure` | Code structure summary (classes, methods) |
| `metadata.repository` | Repository name (multi-repo workspaces) |

---

### `relate` -- Relationship Analysis

Show bidirectional relationships for any file. Answers two questions: "What does this file depend on?" (outgoing) and "What depends on this file?" (incoming). Essential for understanding impact before making changes.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `filePath` | string | **Yes** | -- | File name or path to analyze |
| `format` | string | No | `json` | Output format: `json` or `mermaid` |
| `workspace` | string | No | server default | Override workspace path |

**File path resolution:** The `filePath` parameter is flexible. You can provide:
- A full file name: `AuthService.java`
- A relative path: `src/auth/AuthService.java`
- A partial path: `auth/AuthService.java`

The tool searches the index and finds the best match.

**Example -- JSON format:**

```json
// Input: {"filePath": "AuthService.java", "format": "json"}
// Output:
{
  "file": "/home/user/project/src/auth/AuthService.java",
  "relativePath": "src/auth/AuthService.java",
  "outgoing": [
    {"path": "src/auth/TokenManager.java", "type": "imports/references"},
    {"path": "src/db/UserRepository.java", "type": "imports/references"},
    {"path": "src/config/SecurityConfig.java", "type": "imports/references"}
  ],
  "incoming": [
    {"path": "src/api/LoginController.java", "type": "references"},
    {"path": "src/api/RefreshController.java", "type": "references"},
    {"path": "test/auth/AuthServiceTest.java", "type": "references"}
  ],
  "stats": {
    "outgoingCount": 3,
    "incomingCount": 3,
    "totalConnections": 6
  }
}
```

**Example -- Mermaid format:**

```json
// Input: {"filePath": "AuthService.java", "format": "mermaid"}
// Output:
{
  "format": "mermaid",
  "file": "src/auth/AuthService.java",
  "diagram": "graph LR\n  AuthService --> TokenManager\n  AuthService --> UserRepository\n  LoginController --> AuthService\n  RefreshController --> AuthService"
}
```

**Relationship types detected:**
- Java `import` statements
- Markdown links (`[text](path)`)
- String literal file references (`"config/settings.yaml"`)
- Require/import in JavaScript/TypeScript
- General filename mentions in file content

---

### `graph` -- Architecture Visualization

Generate module-level, dependency, or cross-repository architecture graphs. Returns Mermaid, DOT (Graphviz), or structured JSON for visualization.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `mode` | string | No | `modules` | Graph type: `modules`, `dependencies`, `cross-repo` |
| `format` | string | No | `mermaid` | Output format: `mermaid`, `json`, `dot` |
| `filter` | string | No | -- | Filter to directory or repository pattern |
| `workspace` | string | No | server default | Override workspace path |

**Graph modes:**

| Mode | Description | Best for |
|------|-------------|----------|
| `modules` | Directory-level dependency graph | Understanding project structure |
| `dependencies` | Module dependency graph | Dependency analysis |
| `cross-repo` | Relationships across repositories | Multi-repo architectures |

**Example -- Mermaid output:**

```json
// Input: {"mode": "modules", "format": "mermaid"}
// Output:
{
  "format": "mermaid",
  "nodes": 12,
  "edges": 18,
  "title": "Module Dependencies",
  "generationTime": "0.2s",
  "graph": "graph TD\n  cli --> core\n  cli --> index\n  core --> config\n  analyzer --> core\n  mcp --> core\n  mcp --> index\n  lsp --> core\n  lsp --> index"
}
```

**Example -- JSON output (structured):**

```json
// Input: {"mode": "modules", "format": "json"}
// Output:
{
  "format": "json",
  "nodes": 12,
  "edges": 18,
  "title": "Module Dependencies",
  "generationTime": "0.2s",
  "nodesData": [
    {
      "id": "src/auth",
      "label": "auth",
      "type": "CODE",
      "language": "Java",
      "repository": "main-app",
      "directory": "src/auth",
      "size": 45678
    }
  ],
  "edgesData": [
    {
      "source": "src/auth",
      "target": "src/db",
      "type": "imports",
      "weight": 3
    }
  ]
}
```

**Example -- DOT output (Graphviz):**

```json
// Input: {"mode": "modules", "format": "dot"}
// Output:
{
  "format": "dot",
  "nodes": 12,
  "edges": 18,
  "title": "Module Dependencies",
  "generationTime": "0.2s",
  "graph": "digraph G {\n  rankdir=LR;\n  \"cli\" -> \"core\";\n  \"cli\" -> \"index\";\n  \"core\" -> \"config\";\n}"
}
```

**Using the filter parameter:**

```json
// Only show the "auth" subsystem
{"mode": "modules", "filter": "auth"}

// Only show a specific repository in a multi-repo workspace
{"mode": "cross-repo", "filter": "payment-service"}
```

---

### `stats` -- Workspace Health

Get workspace statistics including file counts by type, index size, health status, and last scan timestamp. Use this to verify the workspace is indexed and healthy before running other tools.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `workspace` | string | No | server default | Override workspace path |

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

**Health status values:**

| Status | Meaning | Action |
|--------|---------|--------|
| `healthy` | Workspace is initialized and has valid config | None needed |
| `missing-config` | `.synthesis/config.yaml` not found | Run `synthesis init` |

**Interpreting the stats:**
- `totalFiles`: Total documents in the Lucene index
- `fileTypes`: Breakdown by Synthesis file classification
- `indexSize`: Physical size of the Lucene index on disk
- `lastScan`: ISO 8601 timestamp of the most recent scan state file modification
- `timestamp`: Current server time (for staleness detection)

---

### `ask` -- AI-Powered Q&A

Ask natural language questions about the codebase. The tool searches the Synthesis index for relevant files, builds context with file content and line numbers, and generates an answer with citations using Claude. Requires `ANTHROPIC_API_KEY`.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `query` | string | **Yes** | -- | The question to ask about the codebase |
| `workspace` | string | No | server default | Override workspace path |

**Example Queries:**

```
# Understand authentication flow
{"query": "How does authentication work in this project?"}

# Find usage patterns
{"query": "Where and how is the database connection pool configured?"}

# Architecture questions
{"query": "What design patterns are used in the service layer?"}
```

**Response Format:**

```json
{
  "answer": "Authentication is handled by AuthService.java which uses OAuth2...",
  "citations": [
    "src/auth/AuthService.java",
    "src/config/SecurityConfig.java",
    "src/auth/TokenManager.java"
  ],
  "contextFiles": 10,
  "workspace": "/home/user/project"
}
```

**Response fields explained:**

| Field | Description |
|-------|-------------|
| `answer` | AI-generated answer with code references |
| `citations` | Files used as context for generating the answer |
| `contextFiles` | Number of files retrieved from the index |
| `workspace` | Workspace path used |

**Error cases:**
- Missing or empty `query` parameter: returns `INVALID_PARAMS` error
- No `ANTHROPIC_API_KEY` set: returns error with setup instructions

---

### `enrich` -- Companion File Generation

Generate `.synthesis.md` companion files for binary assets (images, videos, PDFs, audio). These companion files contain structured metadata, extracted text, and AI descriptions that make binary content fully text-searchable. Run with `filePath` for a single file, or without for batch processing of all binary files in the index.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `filePath` | string | No | -- | Path to a specific file (omit for batch mode) |
| `level` | string | No | `basic` | Enrichment level: `basic`, `local`, `ai` |
| `force` | boolean | No | `false` | Regenerate even if companion exists |
| `workspace` | string | No | server default | Override workspace path |

**Enrichment Levels:**

| Level | Description | Requirements |
|-------|-------------|--------------|
| `basic` | Metadata extraction only (size, type, format) | None |
| `local` | Metadata + local tool analysis (ffprobe, image dimensions) | None |
| `ai` | Metadata + local tools + AI description (vision for images, content summary for PDFs) | `ANTHROPIC_API_KEY` |

**Example -- Single file:**

```json
// Input: {"filePath": "docs/architecture-diagram.png", "level": "basic"}
// Output:
{
  "generated": true,
  "sourcePath": "/home/user/project/docs/architecture-diagram.png",
  "companionPath": "/home/user/project/docs/architecture-diagram.png.synthesis.md",
  "level": "BASIC"
}
```

**Example -- Batch mode:**

```json
// Input: {"level": "basic"}
// Output:
{
  "generated": 12,
  "skipped": 3,
  "errors": 0,
  "level": "BASIC",
  "workspace": "/home/user/project"
}
```

**Companion file format:**

The generated `.synthesis.md` file contains YAML front matter and markdown body:

```markdown
---
companion_for: diagram.png
type: IMAGE
enrichment_level: BASIC
generated: 2026-02-15T10:30:00Z
---

# diagram.png

**Type:** IMAGE | **Size:** 45.2 KB

## Metadata
- Dimensions: 1920x1080
- Format: PNG

## Description
A diagram image.
```

**Batch behavior:**
- Processes all VIDEO, IMAGE, PDF, and AUDIO files in the index
- Skips files that already have companion files (unless `force: true`)
- Reports counts of generated, skipped, and errored files

---

### `explain` -- AI Code Explanation

Generate comprehensive AI-powered explanations of files, directories, or architectural patterns. Uses the Synthesis index as context to ground explanations in the actual workspace structure. Requires `ANTHROPIC_API_KEY`.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `target` | string | **Yes** | -- | File path, directory path, or pattern name |
| `includeContext` | boolean | No | `true` | Include related files in explanation context |
| `depth` | string | No | `standard` | Depth: `brief`, `standard`, `deep` |
| `workspace` | string | No | server default | Override workspace path |

**Explanation Modes:**

The tool auto-detects the mode based on the `target`:

| Mode | Detection | Description |
|------|-----------|-------------|
| `file` | Target resolves to a regular file | Explains the file's purpose, structure, and relationships |
| `module` | Target resolves to a directory | Explains the module's role, internal structure, and external dependencies |
| `pattern` | Target does not resolve to a file or directory | Searches the index for the concept and explains how it is implemented |

**Explanation Depths:**

| Depth | Output | Best for |
|-------|--------|----------|
| `brief` | 3-5 sentences | Quick overview, code review comments |
| `standard` | Multiple sections with code references | Day-to-day understanding |
| `deep` | Comprehensive analysis with architecture context | Onboarding, documentation |

**Example -- File explanation:**

```json
// Input: {"target": "src/auth/AuthService.java", "depth": "standard"}
// Output:
{
  "target": "src/auth/AuthService.java",
  "mode": "file",
  "explanation": "## AuthService.java\n\nThis is the core authentication service...",
  "contextDocuments": 8,
  "durationMs": 2340
}
```

**Example -- Pattern explanation:**

```json
// Input: {"target": "authentication", "depth": "brief"}
// Output:
{
  "target": "authentication",
  "mode": "pattern",
  "explanation": "Authentication in this project follows OAuth2 with JWT tokens...",
  "contextDocuments": 15,
  "durationMs": 3120
}
```

**Error cases:**
- Missing or empty `target` parameter: returns `INVALID_PARAMS` error
- No `ANTHROPIC_API_KEY` set: returns error with setup instructions
- File/directory not found and no matching pattern: attempts pattern explanation

---

## Advanced Configuration

### Multiple Workspaces

Configure separate MCP server instances for different workspaces:

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

Each instance runs as a separate process with its own Lucene index. The agent can choose which workspace to query.

### Custom Index Paths

The index is stored in `.synthesis/index/` within the workspace. To change this, modify `.synthesis/config.yaml` in the workspace before scanning.

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

### Command-Line Options

```
synthesis-mcp-server [OPTIONS]

Options:
  --workspace, -w <path>  Workspace root directory (default: current dir)
  --log-level <level>     Logging level: FINE, INFO, WARNING, SEVERE
  --version, -v           Print version and exit
  --help, -h              Print this help and exit
```

---

## Integration

### Claude Code

**Configuration file:** `~/.claude/config.json`

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

The agent automatically discovers all seven tools (`search`, `relate`, `graph`, `stats`, `ask`, `enrich`, `explain`) via the `tools/list` MCP method. No additional setup is required. The AI-powered tools (`ask`, `explain`) require `ANTHROPIC_API_KEY` to be set.

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

Aider supports MCP servers. Configure in `.aider.conf.yml`:

```yaml
mcp-servers:
  - name: synthesis
    command: synthesis-mcp-server
    args: ["--workspace", "/path/to/project"]
```

### Custom MCP Clients

Any MCP-compatible client can connect to the Synthesis MCP server. The server communicates over stdio using JSON-RPC 2.0. See the [MCP Protocol Reference](../api/MCP-PROTOCOL-REFERENCE.md) for full protocol details.

**Minimal client connection:**

1. Spawn the server process: `synthesis-mcp-server --workspace /path`
2. Send `initialize` request via stdin
3. Receive capabilities response via stdout
4. Send `initialized` notification
5. Call tools via `tools/call` requests
6. Send `shutdown` request when done

---

## Troubleshooting

### Server Won't Start

**Symptom:** `Error: Java not found` or `Error: Java 11 found, but Java 17+ is required.`

**Fix:** Install Java 17 or later. Verify with `java -version`.

```bash
# Ubuntu/Debian
sudo apt install openjdk-17-jre

# macOS (Homebrew)
brew install openjdk@17

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

**Symptom:** Tool calls return errors about the workspace not being valid.

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

**Symptom:** The AI agent reports errors communicating with the MCP server.

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
| Ask | 2-8s | Depends on context size + Claude API latency |
| Enrich (single) | 0.1-3s | Depends on enrichment level (basic < local < ai) |
| Enrich (batch, 50 files) | 5-30s | Parallel processing, varies with level |
| Explain (file) | 2-5s | Depends on file size + Claude API latency |
| Explain (module) | 3-10s | Depends on module size + Claude API latency |
| Explain (pattern) | 3-8s | Depends on search results + Claude API latency |

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

Before renaming a class or moving a file, use `relate` to see the full impact:

> "I want to rename TokenManager to SessionManager. Use synthesis relate to show me all files that reference TokenManager."

The agent will:
1. Call `relate` with `filePath: "TokenManager.java"`
2. Identify all incoming references (files that import or reference TokenManager)
3. List every file that needs updating
4. Make changes with confidence that nothing is missed

### 2. Architecture Review

When onboarding to a new project or preparing for an architecture discussion:

> "Show me the architecture of this project using synthesis."

The agent will:
1. Call `stats` to understand workspace scope
2. Call `graph` with `mode: "modules"` to visualize the module structure
3. Call `search` to find key configuration files
4. Provide an informed architectural overview with a dependency diagram

### 3. Cross-Repository Discovery

When working with microservices or multi-repo setups:

> "Find all services that depend on the payment API."

The agent will:
1. Call `search` for "payment" to locate the payment API files
2. Call `relate` on the payment API entry point
3. Call `graph` with `mode: "cross-repo"` to show inter-service dependencies
4. Present a clear map of which services would be affected

### 4. Onboarding New Developers

When a new team member needs to understand the codebase:

> "I'm new to this project. Help me understand how authentication works."

The agent will:
1. Call `search` for "authentication" to find relevant files
2. Call `relate` on the main auth service to see the component graph
3. Call `graph` to show where auth fits in the overall architecture
4. Provide a guided walkthrough with file references

### 5. Documentation Audit

When checking for outdated or broken references:

> "Find all markdown files that reference files that no longer exist."

The agent will:
1. Call `search` with `fileType: "MARKDOWN"` to find all docs
2. Call `relate` on each document to check outgoing references
3. Identify references where the target file is missing
4. Report the broken links with specific file paths and line references

### 6. AI-Powered Codebase Q&A

When the agent needs deep understanding of a concept:

> "How does the payment processing pipeline work in this codebase?"

The agent will:
1. Call `ask` with the question
2. The server searches the index for relevant files (payment, pipeline, processing)
3. Claude generates an answer grounded in the actual code with file citations
4. The agent presents the answer with references to specific files

### 7. Enriching Binary Assets

When binary files need to be searchable:

> "Make all the images and videos in this project searchable"

The agent will:
1. Call `enrich` in batch mode (no `filePath`)
2. The server generates `.synthesis.md` companion files for each binary asset
3. Report the results (generated, skipped, errors)
4. Suggest running `synthesis scan` to index the new companion files

### 8. Understanding Unfamiliar Code

When a developer needs to understand a complex module:

> "Explain how the authentication module works"

The agent will:
1. Call `explain` with `target: "src/auth"` (module mode)
2. The server analyzes all files in the directory, their relationships, and structure
3. Claude generates a comprehensive explanation with code references
4. The agent presents the explanation with navigation suggestions

---

## See Also

- **[MCP Quick Start](./MCP-QUICKSTART.md)** -- 5-minute setup guide
- **[MCP Protocol Reference](../api/MCP-PROTOCOL-REFERENCE.md)** -- JSON-RPC protocol details
- **[MCP Performance Benchmarks](./MCP-PERFORMANCE-BENCHMARKS.md)** -- Detailed performance data
- **[LSP Server Guide](./LSP-COMPREHENSIVE-GUIDE.md)** -- IDE integration via Language Server Protocol
- **[Quick Start](./QUICK-START.md)** -- CLI usage guide
- **[User Guide](./USER-GUIDE.md)** -- Complete Synthesis reference
