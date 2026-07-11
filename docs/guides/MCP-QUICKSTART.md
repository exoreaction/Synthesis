# MCP Server Quick Start

Get Synthesis working with Claude Code or Cursor in under 5 minutes.

## Prerequisites

- **Java 21+** installed (`java -version` to verify)
- **Synthesis** installed ([Installation Guide](https://github.com/exoreaction/Synthesis/blob/main/README.md#installation))
- A **workspace** you want to search (any project directory)

## Step 1: Index Your Workspace

```bash
cd ~/your-project
synthesis init
synthesis scan
```

This creates a local Lucene index of all files in your project (typically under 30 seconds for projects up to 10,000 files).

## Step 2: Configure Claude Code

Synthesis supports two transport modes. **HTTP is recommended** because it survives session idle timeouts that can drop stdio connections.

### Option A: HTTP Transport (Recommended)

Start the server as a persistent background service:

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

Verify the server is running: `curl http://localhost:8765/health` returns `{"status":"ok"}`.

### Option B: stdio Transport

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

> **Note:** Use an absolute path. The `~` shorthand is not expanded in JSON config files.

### Cursor Setup

Add to Cursor's MCP configuration (Settings > MCP Servers):

```json
{
  "synthesis": {
    "command": "synthesis-mcp-server",
    "args": ["--workspace", "/absolute/path/to/your-project"]
  }
}
```

## Step 3: Verify

Start a new Claude Code session. Ask:

> "Use synthesis to search for authentication-related files"

You should see Claude invoke the `search` tool and return ranked results with file paths, snippets, and metadata.

## What You Get

Synthesis exposes **41 tools** to the AI agent, covering search, architecture, code analysis, security, change tracking, and operations. A representative selection:

| Tool | Category | What it does | Example prompt |
|------|----------|-------------|----------------|
| **search** | Discovery | Full-text search across all file types | "Search for database migration files" |
| **relate** | Discovery | Show what depends on a file (and vice versa) | "What files reference AuthService.java?" |
| **graph** | Architecture | Visualize module structure as Mermaid/DOT/JSON | "Show me the module dependency graph" |
| **ask** | Insights | AI-powered Q&A grounded in indexed files | "How does authentication work here?" |
| **impact** | Architecture | Transitive blast radius before refactoring | "What breaks if I change PaymentService?" |
| **security** | Quality | Vulnerability findings by severity | "Show security findings for this workspace" |
| **changelog** | Tracking | Files added/modified/deleted recently | "What changed in the last 7 days?" |
| **stats** | Operations | Workspace health: file counts, index size | "How many files are indexed?" |
| **scan** | Operations | Index all files in the workspace | "Re-index the workspace" |
| **maintain** | Operations | Full maintenance: re-index, refresh snapshots | "Run workspace maintenance" |

See the [MCP Comprehensive Guide](./MCP-COMPREHENSIVE-GUIDE.md) for all 41 tools with full parameter reference.

## Performance

- **Search:** < 0.5 seconds (validated on 8,934 files)
- **Relate:** < 1 second
- **Graph:** < 2 seconds
- **Stats:** < 0.1 seconds

## Next Steps

- **[MCP Comprehensive Guide](./MCP-COMPREHENSIVE-GUIDE.md)** -- Full tool reference (all 41 tools), advanced configuration, troubleshooting
- **[MCP Protocol Reference](../api/MCP-PROTOCOL-REFERENCE.md)** -- JSON-RPC protocol details for platform engineers
- **[MCP Performance Benchmarks](./MCP-PERFORMANCE-BENCHMARKS.md)** -- Detailed performance data and scaling characteristics
