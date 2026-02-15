# MCP Protocol Reference

Technical reference for the Synthesis MCP (Model Context Protocol) server. This document describes the JSON-RPC 2.0 message format, protocol lifecycle, tool schemas, and error handling. Intended for platform engineers building MCP clients or evaluating Synthesis for integration.

**Protocol Version:** MCP v2024-11-05
**Transport:** JSON-RPC 2.0 over stdio (one JSON message per line)
**Server Name:** `synthesis`
**Implementation:** Java 17+, Jackson JSON, Apache Lucene

---

## Table of Contents

- [Protocol Overview](#protocol-overview)
- [Lifecycle](#lifecycle)
- [Methods](#methods)
  - [initialize](#initialize)
  - [initialized](#initialized)
  - [tools/list](#toolslist)
  - [tools/call](#toolscall)
  - [ping](#ping)
  - [shutdown](#shutdown)
- [Tool Schemas](#tool-schemas)
- [Error Handling](#error-handling)
- [Transport Details](#transport-details)

---

## Protocol Overview

The Synthesis MCP server implements the Model Context Protocol over JSON-RPC 2.0. Communication occurs over standard I/O (stdin/stdout). Each message is a single JSON object followed by a newline character (`\n`). The server never writes to stdout except for protocol messages. All logging goes to `~/.synthesis/logs/mcp-server.log`.

**Message types:**

| Type | Direction | Has `id` | Description |
|------|-----------|----------|-------------|
| Request | Client -> Server | Yes | Expects a response |
| Response | Server -> Client | Yes (matches request) | Result or error |
| Notification | Client -> Server | No | No response expected |

**JSON-RPC 2.0 envelope:**

Every message must include `"jsonrpc": "2.0"`. Requests include `id`, `method`, and optional `params`. Responses include `id` and either `result` or `error`.

---

## Lifecycle

```
Client                              Server
  |                                    |
  |--- initialize ------------------>  |
  |<-- initialize response ----------  |
  |                                    |
  |--- initialized (notification) -->  |
  |                                    |
  |--- tools/list ------------------>  |
  |<-- tools/list response ----------  |
  |                                    |
  |--- tools/call (search) --------->  |
  |<-- tools/call response ----------  |
  |                                    |
  |--- tools/call (relate) --------->  |
  |<-- tools/call response ----------  |
  |                                    |
  |--- shutdown --------------------->  |
  |<-- shutdown response ------------  |
  |                                    |
  |    [server exits]                  |
```

---

## Methods

### `initialize`

**Direction:** Client -> Server (Request)

Initiates the MCP session. The server responds with its capabilities and version information.

**Request:**

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": {
      "name": "claude-code",
      "version": "1.0.0"
    }
  }
}
```

**Response:**

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": {
      "tools": {
        "listChanged": false
      }
    },
    "serverInfo": {
      "name": "synthesis",
      "version": "1.0.4-SNAPSHOT"
    }
  }
}
```

**Capabilities advertised:**

| Capability | Value | Description |
|------------|-------|-------------|
| `tools.listChanged` | `false` | Tool list is static (does not change at runtime) |

---

### `initialized`

**Direction:** Client -> Server (Notification)

Confirms the client has processed the initialization response. No response is sent.

```json
{
  "jsonrpc": "2.0",
  "method": "initialized"
}
```

---

### `tools/list`

**Direction:** Client -> Server (Request)

Returns the list of available tools with their names, descriptions, and JSON Schema input definitions.

**Request:**

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {}
}
```

**Response:**

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "search",
        "description": "Search Synthesis index across all file types (code, docs, videos, PDFs). Returns ranked results with snippets, metadata, and relevance scores. Supports Lucene query syntax: simple terms, exact phrases, boolean operators, wildcards.",
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
            "workspace": {
              "type": "string",
              "description": "Workspace path (defaults to server's configured workspace)"
            }
          },
          "required": ["query"]
        }
      },
      {
        "name": "relate",
        "description": "Show bidirectional relationships for a file (imports, usages, references). Answers: 'What does this file depend on?' and 'What depends on this file?' Essential for understanding impact before making changes.",
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
      },
      {
        "name": "graph",
        "description": "Generate architecture graph showing modules, dependencies, and cross-repo relationships. Returns Mermaid, DOT, or structured JSON. Use for understanding system architecture at a glance.",
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
      },
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
    ]
  }
}
```

---

### `tools/call`

**Direction:** Client -> Server (Request)

Invokes a tool by name with arguments. The response wraps the tool output in MCP content blocks.

**Request format:**

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "<tool-name>",
    "arguments": { /* tool-specific parameters */ }
  }
}
```

**Successful response format:**

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{ ... pretty-printed JSON result ... }"
      }
    ],
    "isError": false
  }
}
```

**Tool error response format (application-level error):**

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Error: Missing required parameter: query"
      }
    ],
    "isError": true
  }
}
```

> **Note:** Application-level tool errors use the standard `result` field with `isError: true`, not the JSON-RPC `error` field. Protocol-level errors (malformed requests, unknown methods) use the JSON-RPC `error` field.

#### Example: search (minimal)

```json
// Request:
{
  "jsonrpc": "2.0",
  "id": 10,
  "method": "tools/call",
  "params": {
    "name": "search",
    "arguments": {
      "query": "authentication"
    }
  }
}

// Response (abbreviated):
{
  "jsonrpc": "2.0",
  "id": 10,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\n  \"results\" : [ ... ],\n  \"totalHits\" : 15,\n  \"searchTime\" : \"0.1s\",\n  \"workspace\" : \"/home/user/project\"\n}"
      }
    ],
    "isError": false
  }
}
```

#### Example: search (with filters)

```json
{
  "jsonrpc": "2.0",
  "id": 11,
  "method": "tools/call",
  "params": {
    "name": "search",
    "arguments": {
      "query": "deployment pipeline",
      "fileType": "YAML",
      "limit": 5
    }
  }
}
```

#### Example: relate (JSON format)

```json
{
  "jsonrpc": "2.0",
  "id": 12,
  "method": "tools/call",
  "params": {
    "name": "relate",
    "arguments": {
      "filePath": "AuthService.java",
      "format": "json"
    }
  }
}
```

#### Example: relate (Mermaid format)

```json
{
  "jsonrpc": "2.0",
  "id": 13,
  "method": "tools/call",
  "params": {
    "name": "relate",
    "arguments": {
      "filePath": "src/auth/AuthService.java",
      "format": "mermaid"
    }
  }
}
```

#### Example: graph (modules, Mermaid)

```json
{
  "jsonrpc": "2.0",
  "id": 14,
  "method": "tools/call",
  "params": {
    "name": "graph",
    "arguments": {
      "mode": "modules",
      "format": "mermaid"
    }
  }
}
```

#### Example: graph (cross-repo, JSON, filtered)

```json
{
  "jsonrpc": "2.0",
  "id": 15,
  "method": "tools/call",
  "params": {
    "name": "graph",
    "arguments": {
      "mode": "cross-repo",
      "format": "json",
      "filter": "payment"
    }
  }
}
```

#### Example: stats

```json
{
  "jsonrpc": "2.0",
  "id": 16,
  "method": "tools/call",
  "params": {
    "name": "stats",
    "arguments": {}
  }
}
```

---

### `ping`

**Direction:** Client -> Server (Request)

Health check. Returns an empty result.

```json
// Request:
{"jsonrpc": "2.0", "id": 99, "method": "ping", "params": {}}

// Response:
{"jsonrpc": "2.0", "id": 99, "result": {}}
```

---

### `shutdown`

**Direction:** Client -> Server (Request)

Requests the server to shut down gracefully. Returns an empty result and stops the main loop.

```json
// Request:
{"jsonrpc": "2.0", "id": 100, "method": "shutdown", "params": {}}

// Response:
{"jsonrpc": "2.0", "id": 100, "result": {}}
```

After receiving the response, the client should close the connection. The server will exit after stdin is closed.

---

### `notifications/cancelled`

**Direction:** Client -> Server (Notification)

The client may send this to cancel an in-progress request. The server acknowledges by ignoring (no response sent). Currently, the server does not support cancellation of in-flight operations.

```json
{"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": 5}}
```

---

## Tool Schemas

The following JSON Schemas define the input parameters for each tool. These are the same schemas returned by the `tools/list` method.

### search

```json
{
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
    "workspace": {
      "type": "string",
      "description": "Workspace path (defaults to server's configured workspace)"
    }
  },
  "required": ["query"]
}
```

### relate

```json
{
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
```

### graph

```json
{
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
```

### stats

```json
{
  "type": "object",
  "properties": {
    "workspace": {
      "type": "string",
      "description": "Workspace path (defaults to server's configured workspace)"
    }
  }
}
```

### ask

```json
{
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
```

### enrich

```json
{
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
```

### explain

```json
{
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
```

---

## Error Handling

### JSON-RPC Protocol Errors

These are returned when the message itself is malformed or the method is unknown.

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "error": {
    "code": -32602,
    "message": "Missing tool 'name' in tools/call request"
  }
}
```

**Standard error codes:**

| Code | Name | Meaning |
|------|------|---------|
| `-32700` | Parse error | Invalid JSON received |
| `-32600` | Invalid request | Missing or invalid `jsonrpc` field, missing `method` field |
| `-32601` | Method not found | Unknown method name |
| `-32602` | Invalid params | Missing required parameters |
| `-32603` | Internal error | Unexpected server error (check logs) |

### Application-Level Tool Errors

When a tool fails (e.g., file not found, workspace not initialized), the server returns a successful JSON-RPC response with `isError: true`:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Error: File not found in index: MissingFile.java"
      }
    ],
    "isError": true
  }
}
```

Common tool errors:

| Error Message | Cause | Fix |
|---------------|-------|-----|
| `Missing required parameter: query` | `search` called without `query` | Include `query` in arguments |
| `Missing required parameter: filePath` | `relate` called without `filePath` | Include `filePath` in arguments |
| `Parameter 'query' must not be empty` | Empty string passed as query | Provide a non-empty query |
| `File not found in index: <name>` | `relate` target not in index | Verify file exists and index is current |
| `No files in index. Run 'synthesis scan' first.` | Empty index | Run `synthesis scan` |
| `No files match filter: <filter>` | `graph` filter matched nothing | Verify the filter pattern |
| `Missing required parameter: target` | `explain` called without `target` | Include `target` in arguments |
| `Parameter 'target' must not be empty` | Empty string passed as target | Provide a non-empty target |
| `AI not configured. Set ANTHROPIC_API_KEY environment variable.` | `ask` or `explain` without API key | Set `ANTHROPIC_API_KEY` |
| `File not found: <path>` | `enrich` target file missing | Verify file path |
| Workspace validation errors | Workspace not initialized | Run `synthesis init && synthesis scan` |

---

## Transport Details

### Wire Format

- **Encoding:** UTF-8
- **Framing:** One JSON object per line (newline-delimited JSON)
- **Direction:** Client writes to server's stdin; server writes to server's stdout
- **Logging:** Server logs to `~/.synthesis/logs/mcp-server.log` (never to stdout)

### Connection Lifecycle

1. Client spawns server process: `synthesis-mcp-server --workspace /path`
2. Client writes JSON-RPC messages to server's stdin
3. Server writes JSON-RPC responses to server's stdout
4. Client sends `shutdown` request
5. Server responds and stops the main loop
6. Client closes stdin
7. Server exits

### Concurrency

The server processes one request at a time in a single-threaded read loop. Write operations are synchronized on the stdout stream. The server does not support concurrent requests from multiple clients over the same stdio connection.

### Server Information

| Property | Value |
|----------|-------|
| Server name | `synthesis` |
| Protocol version | `2024-11-05` |
| Implementation | Java 17+ |
| JSON library | Jackson 2.x |
| Search engine | Apache Lucene 10.1.0 |
| LSP library | N/A (MCP only) |
| Log rotation | 5 MB, 3 files, append mode |

---

## See Also

- **[MCP Quick Start](../guides/MCP-QUICKSTART.md)** -- 5-minute setup
- **[MCP Comprehensive Guide](../guides/MCP-COMPREHENSIVE-GUIDE.md)** -- Full usage guide
- **[MCP Performance Benchmarks](../guides/MCP-PERFORMANCE-BENCHMARKS.md)** -- Performance data
- **[MCP Specification](https://modelcontextprotocol.org/)** -- Official MCP specification
- **[JSON-RPC 2.0 Specification](https://www.jsonrpc.org/specification)** -- Wire protocol specification
