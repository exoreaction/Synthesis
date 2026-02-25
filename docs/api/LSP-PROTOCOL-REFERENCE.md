# LSP Protocol Reference

Technical reference for the Synthesis Language Server Protocol (LSP) server. This document describes the protocol lifecycle, server capabilities, message formats, and feature implementations. Intended for IDE extension developers, LSP client maintainers, and platform engineers evaluating Synthesis for integration.

**Protocol Version:** LSP 3.17
**Transport:** JSON-RPC 2.0 over stdio
**Library:** Eclipse LSP4J 0.23.1
**Implementation:** Java 21+

---

## Table of Contents

- [Protocol Overview](#protocol-overview)
- [Lifecycle](#lifecycle)
- [Initialize](#initialize)
- [Text Document Features](#text-document-features)
  - [Document Links](#document-links)
  - [Hover](#hover)
  - [Go to Definition](#go-to-definition)
  - [Find References](#find-references)
  - [Code Lens](#code-lens)
  - [Diagnostics](#diagnostics)
- [Workspace Features](#workspace-features)
  - [Workspace Symbols](#workspace-symbols)
- [Document Synchronization](#document-synchronization)
- [Error Handling](#error-handling)

---

## Protocol Overview

The Synthesis LSP server implements the Language Server Protocol specification version 3.17. It communicates with IDE clients over standard I/O using JSON-RPC 2.0. The server is built with Eclipse LSP4J, which handles protocol serialization, deserialization, and dispatching.

**Key characteristics:**
- **Transport:** stdin/stdout (JSON-RPC 2.0)
- **Concurrency:** All methods return `CompletableFuture` (async, non-blocking)
- **Index:** Apache Lucene 10.1.0 (shared with CLI and MCP server)
- **Logging:** File-only (`~/.synthesis/logs/lsp-server.log`), never stdout

---

## Lifecycle

```
Client (IDE)                         Server
  |                                    |
  |--- initialize ------------------>  |
  |<-- initialize response ----------  |
  |                                    |
  |--- initialized (notification) -->  |
  |                                    |
  |--- textDocument/didOpen --------->  |
  |<-- textDocument/publishDiagnostics |
  |                                    |
  |--- workspace/symbol ------------->  |
  |<-- workspace/symbol response ----  |
  |                                    |
  |--- textDocument/hover ----------->  |
  |<-- hover response ---------------  |
  |                                    |
  |--- shutdown --------------------->  |
  |<-- shutdown response ------------  |
  |                                    |
  |--- exit (notification) ---------->  |
  |    [server exits]                  |
```

---

## Initialize

### Request

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "processId": 12345,
    "rootUri": "file:///home/user/my-project",
    "capabilities": {
      "textDocument": {
        "hover": { "contentFormat": ["markdown", "plaintext"] },
        "documentLink": { "dynamicRegistration": true },
        "definition": {},
        "references": {},
        "codeLens": {}
      },
      "workspace": {
        "symbol": { "dynamicRegistration": true }
      }
    },
    "workspaceFolders": [
      {
        "uri": "file:///home/user/my-project",
        "name": "my-project"
      }
    ]
  }
}
```

### Response

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "capabilities": {
      "textDocumentSync": 2,
      "workspaceSymbolProvider": true,
      "documentLinkProvider": {
        "resolveProvider": true
      },
      "hoverProvider": true,
      "definitionProvider": true,
      "referencesProvider": true,
      "codeLensProvider": {
        "resolveProvider": true
      }
    },
    "serverInfo": {
      "name": "Synthesis Language Server",
      "version": "1.0.4-SNAPSHOT"
    }
  }
}
```

### Server Capabilities

| Capability | Value | LSP Specification |
|------------|-------|-------------------|
| `textDocumentSync` | `2` (Incremental) | Documents tracked with incremental changes |
| `workspaceSymbolProvider` | `true` | `workspace/symbol` requests supported |
| `documentLinkProvider` | `{ resolveProvider: true }` | Clickable file references |
| `hoverProvider` | `true` | File metadata on hover |
| `definitionProvider` | `true` | Navigate to referenced file |
| `referencesProvider` | `true` | Find all files referencing a file |
| `codeLensProvider` | `{ resolveProvider: true }` | Inline relationship counts |

### Workspace Root Resolution

The server determines the workspace root from the `initialize` params:

1. `rootUri` -- if starts with `file://`, extract path
2. `workspaceFolders[0].uri` -- fallback if `rootUri` is null
3. `--workspace` CLI argument -- fallback if IDE provides neither
4. Current working directory -- last resort

---

## Text Document Features

### Document Links

**Method:** `textDocument/documentLink`

Returns clickable links for file references detected in the document.

**Request:**

```json
{
  "jsonrpc": "2.0",
  "id": 10,
  "method": "textDocument/documentLink",
  "params": {
    "textDocument": {
      "uri": "file:///home/user/project/README.md"
    }
  }
}
```

**Response:**

```json
{
  "jsonrpc": "2.0",
  "id": 10,
  "result": [
    {
      "range": {
        "start": { "line": 5, "character": 15 },
        "end": { "line": 5, "character": 35 }
      },
      "target": "file:///home/user/project/docs/QUICK-START.md"
    },
    {
      "range": {
        "start": { "line": 12, "character": 8 },
        "end": { "line": 12, "character": 42 }
      },
      "target": "file:///home/user/project/src/auth/AuthService.java"
    }
  ]
}
```

**Detection patterns:**

| Pattern | Regex | Description |
|---------|-------|-------------|
| Markdown links | `\[([^\]]*)\]\(([^)]+)\)` | `[text](path)` syntax |
| Import/require | `import\s+\|from\s+\|require\s*\(?['"]` + file extensions | Code import statements |
| Quoted file paths | `['"\`]path.ext['"\`]` | String literal file references |

**File extensions detected:** `.java`, `.py`, `.js`, `.ts`, `.tsx`, `.jsx`, `.md`, `.yaml`, `.yml`, `.json`, `.xml`, `.go`, `.rs`, `.kt`, `.sh`, `.sql`, `.toml`, `.html`, `.css`, `.scss`

**Resolution:** The server resolves link targets by:
1. Resolving relative to the document's parent directory
2. Checking if the resolved path exists on disk
3. HTTP/HTTPS URLs and anchor-only links (`#section`) are skipped

---

### Hover

**Method:** `textDocument/hover`

Returns metadata about the file referenced at the cursor position.

**Request:**

```json
{
  "jsonrpc": "2.0",
  "id": 11,
  "method": "textDocument/hover",
  "params": {
    "textDocument": {
      "uri": "file:///home/user/project/README.md"
    },
    "position": {
      "line": 5,
      "character": 20
    }
  }
}
```

**Response:**

```json
{
  "jsonrpc": "2.0",
  "id": 11,
  "result": {
    "contents": {
      "kind": "markdown",
      "value": "**Synthesis:** `docs/QUICK-START.md`\n\n| Property | Value |\n|----------|-------|\n| **Type** | MARKDOWN |\n| **Size** | 11.4 KB |\n| **Path** | `/home/user/project/docs/QUICK-START.md` |\n\n**Relationships:**\n- 3 outgoing references\n- 7 incoming references\n"
    }
  }
}
```

**Hover content includes:**
- File type classification (CODE, MARKDOWN, PDF, etc.)
- Programming language (for code files)
- File size (human-readable)
- Absolute path
- Outgoing reference count (files this file depends on)
- Incoming reference count (files that depend on this file)

**Returns null when:**
- The cursor is not on a recognized file reference
- The referenced file does not exist
- The file is not tracked in the Synthesis index

---

### Go to Definition

**Method:** `textDocument/definition`

Navigates to the file referenced at the cursor position.

**Request:**

```json
{
  "jsonrpc": "2.0",
  "id": 12,
  "method": "textDocument/definition",
  "params": {
    "textDocument": {
      "uri": "file:///home/user/project/README.md"
    },
    "position": {
      "line": 5,
      "character": 20
    }
  }
}
```

**Response:**

```json
{
  "jsonrpc": "2.0",
  "id": 12,
  "result": [
    {
      "uri": "file:///home/user/project/docs/QUICK-START.md",
      "range": {
        "start": { "line": 0, "character": 0 },
        "end": { "line": 0, "character": 0 }
      }
    }
  ]
}
```

**Returns empty array when:**
- No file reference found at cursor position
- Referenced file does not exist on disk

---

### Find References

**Method:** `textDocument/references`

Returns all files in the workspace that reference the current file.

**Request:**

```json
{
  "jsonrpc": "2.0",
  "id": 13,
  "method": "textDocument/references",
  "params": {
    "textDocument": {
      "uri": "file:///home/user/project/src/auth/AuthService.java"
    },
    "position": {
      "line": 0,
      "character": 0
    },
    "context": {
      "includeDeclaration": true
    }
  }
}
```

**Response:**

```json
{
  "jsonrpc": "2.0",
  "id": 13,
  "result": [
    {
      "uri": "file:///home/user/project/src/api/LoginController.java",
      "range": {
        "start": { "line": 0, "character": 0 },
        "end": { "line": 0, "character": 0 }
      }
    },
    {
      "uri": "file:///home/user/project/src/api/RefreshController.java",
      "range": {
        "start": { "line": 0, "character": 0 },
        "end": { "line": 0, "character": 0 }
      }
    },
    {
      "uri": "file:///home/user/project/test/auth/AuthServiceTest.java",
      "range": {
        "start": { "line": 0, "character": 0 },
        "end": { "line": 0, "character": 0 }
      }
    }
  ]
}
```

**Implementation notes:**
- The server scans up to 5,000 files from the Lucene index
- Uses `RelateCommand.analyzeIncomingRefs()` for relationship detection
- Location ranges are file-level (line 0, character 0) -- the server does not track line-level references
- The `context.includeDeclaration` parameter is accepted but not used differently

---

### Code Lens

**Method:** `textDocument/codeLens`

Returns inline annotations showing relationship counts at the top of each file.

**Request:**

```json
{
  "jsonrpc": "2.0",
  "id": 14,
  "method": "textDocument/codeLens",
  "params": {
    "textDocument": {
      "uri": "file:///home/user/project/src/auth/AuthService.java"
    }
  }
}
```

**Response:**

```json
{
  "jsonrpc": "2.0",
  "id": 14,
  "result": [
    {
      "range": {
        "start": { "line": 0, "character": 0 },
        "end": { "line": 0, "character": 0 }
      },
      "command": {
        "title": "Synthesis: 5 outgoing, 12 incoming references",
        "command": ""
      }
    }
  ]
}
```

**Implementation notes:**
- Only one code lens is returned per file (at line 0)
- The command has an empty `command` field (display-only, not clickable)
- Returns empty array if the file has zero relationships or is not in the index
- Full relationship analysis is performed (both outgoing and incoming)

---

### Diagnostics

**Method:** `textDocument/publishDiagnostics` (Server -> Client notification)

The server publishes diagnostics for broken file references in markdown documents.

**Notification (server pushes to client):**

```json
{
  "jsonrpc": "2.0",
  "method": "textDocument/publishDiagnostics",
  "params": {
    "uri": "file:///home/user/project/README.md",
    "diagnostics": [
      {
        "range": {
          "start": { "line": 15, "character": 22 },
          "end": { "line": 15, "character": 45 }
        },
        "severity": 2,
        "source": "synthesis",
        "message": "Broken link: file not found - docs/old-guide.md"
      }
    ]
  }
}
```

**Diagnostic severity values:**

| Value | Name | Usage |
|-------|------|-------|
| 2 | Warning | Broken file references |

**Trigger events:**
- `textDocument/didOpen` -- initial diagnostics on file open
- `textDocument/didSave` -- re-run diagnostics on save
- `textDocument/didClose` -- clear diagnostics (empty array)

**What is checked:**
- Markdown links: `[text](path)` where path is a relative file path
- HTTP/HTTPS links are skipped
- Anchor-only links (`#section`) are skipped
- Anchor fragments in file links (`file.md#section`) are stripped before checking

---

## Workspace Features

### Workspace Symbols

**Method:** `workspace/symbol`

Searches the Synthesis index and returns matching files as workspace symbols.

**Request:**

```json
{
  "jsonrpc": "2.0",
  "id": 20,
  "method": "workspace/symbol",
  "params": {
    "query": "AuthService"
  }
}
```

**Response:**

```json
{
  "jsonrpc": "2.0",
  "id": 20,
  "result": [
    {
      "name": "AuthService.java",
      "kind": 5,
      "containerName": "src/auth/AuthService.java",
      "location": {
        "uri": "file:///home/user/project/src/auth/AuthService.java",
        "range": {
          "start": { "line": 0, "character": 0 },
          "end": { "line": 0, "character": 0 }
        }
      }
    },
    {
      "name": "AuthServiceTest.java",
      "kind": 5,
      "containerName": "test/auth/AuthServiceTest.java",
      "location": {
        "uri": "file:///home/user/project/test/auth/AuthServiceTest.java",
        "range": {
          "start": { "line": 0, "character": 0 },
          "end": { "line": 0, "character": 0 }
        }
      }
    }
  ]
}
```

**Symbol kind mapping:**

| File Type | SymbolKind | Value | IDE Display |
|-----------|------------|-------|-------------|
| CODE | Class | 5 | Class/module icon |
| MARKDOWN | String | 15 | Text icon |
| YAML | Object | 19 | Config icon |
| JSON | Object | 19 | Config icon |
| CONFIG | Object | 19 | Config icon |
| PDF | Constant | 14 | Document icon |
| DOCUMENT | Constant | 14 | Document icon |
| IMAGE | Null | 21 | Generic icon |
| VIDEO | Event | 24 | Media icon |
| AUDIO | Event | 24 | Media icon |
| (unknown) | File | 1 | File icon |

**Implementation notes:**
- Returns up to 50 results, ranked by Lucene relevance score
- Empty or blank queries return an empty result
- The `containerName` field contains the relative file path (shown as context in the IDE)
- Location ranges are file-level (line 0, character 0)

---

## Document Synchronization

The server tracks open documents using the standard LSP text document synchronization methods.

### didOpen

```json
{
  "jsonrpc": "2.0",
  "method": "textDocument/didOpen",
  "params": {
    "textDocument": {
      "uri": "file:///home/user/project/README.md",
      "languageId": "markdown",
      "version": 1,
      "text": "# My Project\n\n[Quick Start](docs/QUICK-START.md)\n..."
    }
  }
}
```

**Server behavior:** Stores document content in memory. Runs diagnostics and publishes results.

### didChange

```json
{
  "jsonrpc": "2.0",
  "method": "textDocument/didChange",
  "params": {
    "textDocument": {
      "uri": "file:///home/user/project/README.md",
      "version": 2
    },
    "contentChanges": [
      {
        "text": "# My Updated Project\n\n..."
      }
    ]
  }
}
```

**Server behavior:** Updates stored document content. Full-document changes replace the stored content. Range-based incremental changes are received but the server currently uses the latest full-text fallback.

### didSave

```json
{
  "jsonrpc": "2.0",
  "method": "textDocument/didSave",
  "params": {
    "textDocument": {
      "uri": "file:///home/user/project/README.md"
    }
  }
}
```

**Server behavior:** Re-runs diagnostics on the stored document content.

### didClose

```json
{
  "jsonrpc": "2.0",
  "method": "textDocument/didClose",
  "params": {
    "textDocument": {
      "uri": "file:///home/user/project/README.md"
    }
  }
}
```

**Server behavior:** Removes document from the tracking map. Publishes empty diagnostics to clear warnings.

---

## Error Handling

The server handles errors gracefully:

| Scenario | Behavior |
|----------|----------|
| Workspace not initialized | Returns empty results (no error) |
| File not in index | Hover returns null, references returns empty |
| Index read failure | Logs warning, returns empty results |
| Invalid file URI | Logs warning, returns empty results |
| Concurrent access | Each operation opens its own SearchIndex reader |

The server does not crash or disconnect on errors. All exceptions are caught and logged.

---

## Configuration Changes

### didChangeConfiguration

```json
{
  "jsonrpc": "2.0",
  "method": "workspace/didChangeConfiguration",
  "params": {
    "settings": {}
  }
}
```

**Server behavior:** Logged but not currently acted upon. Future versions may support runtime configuration updates.

### didChangeWatchedFiles

```json
{
  "jsonrpc": "2.0",
  "method": "workspace/didChangeWatchedFiles",
  "params": {
    "changes": [
      {
        "uri": "file:///home/user/project/src/NewFile.java",
        "type": 1
      }
    ]
  }
}
```

**Server behavior:** Logged but not currently acted upon. Future versions may trigger automatic re-indexing.

---

## Server Information

| Property | Value |
|----------|-------|
| Server name | `Synthesis Language Server` |
| Protocol version | LSP 3.17 |
| Implementation language | Java 21+ |
| LSP library | Eclipse LSP4J 0.23.1 |
| Search engine | Apache Lucene 10.1.0 |
| Log rotation | 5 MB, 3 files, append mode |

---

## See Also

- **[LSP Quick Start](../guides/LSP-QUICKSTART.md)** -- 5-minute IDE setup
- **[LSP Comprehensive Guide](../guides/LSP-COMPREHENSIVE-GUIDE.md)** -- Full feature and configuration guide
- **[IDE Integration Guides](../guides/LSP-IDE-INTEGRATION-GUIDES.md)** -- Per-IDE setup instructions
- **[LSP 3.17 Specification](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/)** -- Official specification
- **[MCP Protocol Reference](./MCP-PROTOCOL-REFERENCE.md)** -- AI agent integration protocol
