# MCP Server Quick Start

Get Synthesis working with Claude Code or Cursor in under 5 minutes.

## Prerequisites

- **Java 17+** installed (`java -version` to verify)
- **Synthesis** installed ([Installation Guide](../../README.md#installation))
- A **workspace** you want to search (any project directory)

## Step 1: Index Your Workspace

```bash
cd ~/your-project
synthesis init
synthesis scan
```

This creates a local Lucene index of all files in your project (typically under 30 seconds for projects up to 10,000 files).

## Step 2: Configure Claude Code

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

Synthesis exposes four tools to the AI agent:

| Tool | What it does | Example prompt |
|------|-------------|----------------|
| **search** | Full-text search across all file types | "Search for database migration files" |
| **relate** | Show what depends on a file (and vice versa) | "What files reference AuthService.java?" |
| **graph** | Visualize module structure as Mermaid/DOT/JSON | "Show me the module dependency graph" |
| **stats** | Workspace health: file counts, index size | "How many files are indexed?" |

## Performance

- **Search:** < 0.5 seconds (validated on 8,934 files)
- **Relate:** < 1 second
- **Graph:** < 2 seconds
- **Stats:** < 0.1 seconds

## Next Steps

- **[MCP Comprehensive Guide](./MCP-COMPREHENSIVE-GUIDE.md)** -- Full tool reference, advanced configuration, troubleshooting
- **[MCP Protocol Reference](../api/MCP-PROTOCOL-REFERENCE.md)** -- JSON-RPC protocol details for platform engineers
- **[MCP Performance Benchmarks](./MCP-PERFORMANCE-BENCHMARKS.md)** -- Detailed performance data and scaling characteristics
