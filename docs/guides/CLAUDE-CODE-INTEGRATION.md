# Claude Code Integration (MCP Server)

> **See also:** For comprehensive documentation, refer to the new dedicated guides:
> - **[MCP Quick Start](./MCP-QUICKSTART.md)** -- 5-minute setup
> - **[MCP Comprehensive Guide](./MCP-COMPREHENSIVE-GUIDE.md)** -- Full tool reference, advanced config, troubleshooting
> - **[MCP Protocol Reference](../api/MCP-PROTOCOL-REFERENCE.md)** -- JSON-RPC protocol details
> - **[MCP Performance Benchmarks](./MCP-PERFORMANCE-BENCHMARKS.md)** -- Response times and scaling data

Synthesis provides a native MCP (Model Context Protocol) server that gives Claude Code and other AI agents direct access to your workspace index -- enabling sub-second search, relationship analysis, and architecture visualization without manual tool invocation.

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

---

## Available Tools

### `search` -- Full-Text Search

Search across all file types (code, docs, videos, PDFs) with Lucene query syntax.

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `query` | string | Yes | -- | Search query (Lucene syntax) |
| `fileType` | string | No | `ALL` | Filter: CODE, MARKDOWN, PDF, VIDEO, YAML, JSON, CONFIG, IMAGE, AUDIO, ALL |
| `limit` | number | No | 20 | Max results (1-200) |
| `workspace` | string | No | server default | Override workspace path |

**Example Queries:**
- Simple: `"authentication"`
- Phrase: `"\"error handling\""`
- Boolean: `"testing AND strategy"`
- Wildcard: `"auth*"`
- Field: `"language:Java"`
- Combined: `"security AND fileType:CODE"`

**Response:**
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
        "headings": "AuthService, authenticate, refreshToken"
      }
    }
  ],
  "totalHits": 23,
  "searchTime": "0.3s"
}
```

### `relate` -- Relationship Analysis

Show bidirectional relationships for any file. Essential for understanding impact before refactoring.

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `filePath` | string | Yes | -- | File name or path |
| `format` | string | No | `json` | Output: `json` or `mermaid` |
| `workspace` | string | No | server default | Override workspace path |

**Response (JSON):**
```json
{
  "file": "/home/user/project/src/auth/AuthService.java",
  "outgoing": [
    {"path": "src/auth/TokenManager.java", "type": "imports/references"},
    {"path": "src/db/UserRepository.java", "type": "imports/references"}
  ],
  "incoming": [
    {"path": "src/api/LoginController.java", "type": "references"},
    {"path": "src/api/RefreshController.java", "type": "references"}
  ],
  "stats": {
    "outgoingCount": 2,
    "incomingCount": 2,
    "totalConnections": 4
  }
}
```

**Response (Mermaid):**
```json
{
  "format": "mermaid",
  "diagram": "```mermaid\ngraph LR\n  AuthService --> TokenManager\n  LoginController --> AuthService\n```"
}
```

### `graph` -- Architecture Visualization

Generate module-level or cross-repo dependency graphs.

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `mode` | string | No | `modules` | Graph type: `modules`, `dependencies`, `cross-repo` |
| `format` | string | No | `mermaid` | Output: `mermaid`, `json`, `dot` |
| `filter` | string | No | -- | Filter to specific subsystem pattern |
| `workspace` | string | No | server default | Override workspace path |

### `stats` -- Workspace Health

Get workspace statistics including file counts, index size, and health status.

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `workspace` | string | No | server default | Override workspace path |

**Response:**
```json
{
  "workspace": "/home/user/project",
  "totalFiles": 8934,
  "fileTypes": {
    "CODE": 3241,
    "MARKDOWN": 1567,
    "YAML": 423,
    "JSON": 312,
    "PDF": 89
  },
  "indexSizeBytes": 12189456,
  "indexSize": "11.6 MB",
  "lastScan": "2026-02-15T08:30:00Z",
  "health": "healthy"
}
```

---

## Agent Workflows

### Workflow 1: Safe Refactoring

> "I want to rename TokenManager to SessionManager. What would break?"

Claude Code will:
1. Use `search` to find TokenManager
2. Use `relate` to find all files that import/reference it
3. List every file that needs updating
4. Make changes with confidence

### Workflow 2: Onboarding / Exploration

> "What's the architecture of this project?"

Claude Code will:
1. Use `graph` to get a module dependency diagram
2. Use `stats` to understand the workspace scope
3. Use `search` to find key configuration files
4. Provide an informed architectural overview

### Workflow 3: Impact Analysis

> "What does changing the database schema affect?"

Claude Code will:
1. Use `search` for schema-related files
2. Use `relate` on each to find downstream dependencies
3. Build a change impact report

---

## Configuration

### Multiple Workspaces

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

- **Protocol:** MCP v2024-11-05 over JSON-RPC 2.0 (stdio)
- **Transport:** stdin/stdout (JSON lines, one message per line)
- **Index:** Apache Lucene (same index as CLI)
- **Java:** 17+ required
- **Logging:** File-based (`~/.synthesis/logs/mcp-server.log`), not stdout
