# LSP Server Comprehensive Guide

Synthesis provides a Language Server Protocol (LSP 3.17) server that brings workspace intelligence directly into your IDE. This guide covers all features, configuration options, troubleshooting, and workflows.

**Version:** 1.0.4-SNAPSHOT | **Protocol:** LSP 3.17 | **Library:** Eclipse LSP4J 0.23.1 | **Transport:** JSON-RPC 2.0 over stdio

---

## Table of Contents

- [Setup and Installation](#setup-and-installation)
- [Features Reference](#features-reference)
  - [Workspace Symbols](#1-workspace-symbols-cmdt--ctrlt)
  - [Document Links](#2-document-links)
  - [Hover](#3-hover)
  - [Go to Definition](#4-go-to-definition)
  - [Find References](#5-find-references)
  - [Code Lens](#6-code-lens)
  - [Diagnostics](#7-diagnostics)
- [Advanced Configuration](#advanced-configuration)
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
| IDE | Any with LSP support | See IDE-specific guides |

### Installation

The LSP server is included with the standard Synthesis installation:

```bash
# Install Synthesis (includes LSP server)
curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash
```

The `synthesis-lsp-server` launcher script is installed to `~/.synthesis/bin/` and added to your PATH.

**Build from source:**

```bash
git clone https://github.com/exoreaction/Synthesis.git
cd Synthesis
mvn clean package -DskipTests
# The LSP server launcher is at bin/synthesis-lsp-server
```

### Workspace Preparation

Before the LSP server can provide features, your workspace must be indexed:

```bash
cd ~/your-project
synthesis init --name "My Project"
synthesis scan
```

Verify:

```bash
synthesis status
# Should show: Files indexed: <count>, Status: Initialized
```

### IDE Configuration

See the [LSP Quick Start](./LSP-QUICKSTART.md) for per-IDE setup, or the [IDE Integration Guides](./LSP-IDE-INTEGRATION-GUIDES.md) for detailed instructions.

---

## Features Reference

The Synthesis LSP server advertises the following capabilities during initialization:

| Capability | LSP Method | Description |
|------------|-----------|-------------|
| Workspace Symbols | `workspace/symbol` | Search files by name/content |
| Document Links | `textDocument/documentLink` | Clickable file references |
| Hover | `textDocument/hover` | File metadata popups |
| Go to Definition | `textDocument/definition` | Navigate to referenced file |
| Find References | `textDocument/references` | Find all referencing files |
| Code Lens | `textDocument/codeLens` | Inline relationship counts |
| Diagnostics | `textDocument/publishDiagnostics` | Broken link warnings |
| Text Document Sync | `textDocument/didOpen`, etc. | Incremental document tracking |

---

### 1. Workspace Symbols (Cmd+T / Ctrl+T)

**What it does:** Searches the Synthesis index and returns matching files as navigable symbols. Much faster than IDE file indexing for large or multi-format workspaces.

**How to use:**
- **VSCode:** Cmd+T (Mac) / Ctrl+T (Windows/Linux), then type your query
- **IntelliJ:** Shift+Shift (Search Everywhere), then type
- **Neovim:** `:lua vim.lsp.buf.workspace_symbol('query')`
- **Vim:** `:LspWorkspaceSymbol query`
- **Emacs:** `M-x lsp-ui-find-workspace-symbol`

**What you see:** A list of matching files with:
- **Name:** The file name
- **Container:** The relative path (shown as context)
- **Icon:** Based on file type (class icon for code, string icon for markdown, etc.)

**Search behavior:**
- Queries are sent to the Synthesis Lucene index
- Supports the same query syntax as the CLI `search` command
- Returns up to 50 results, ranked by relevance
- Searches across all file types (code, docs, PDFs, configs, media)

**Example queries:**
- `AuthService` -- finds AuthService.java, AuthServiceTest.java, auth-service.yaml
- `deployment config` -- finds deployment-related configuration files
- `README` -- finds all README files across the workspace
- `TODO` -- finds files containing TODO comments

**Performance:** 0.1-0.3 seconds for workspaces up to 10,000 files.

**File type to symbol kind mapping:**

| File Type | Symbol Kind | IDE Icon |
|-----------|-------------|----------|
| CODE | Class | Class/module icon |
| MARKDOWN | String | Text icon |
| YAML, JSON, CONFIG | Object | Config icon |
| PDF, DOCUMENT | Constant | Document icon |
| IMAGE | Null | Generic icon |
| VIDEO, AUDIO | Event | Media icon |

---

### 2. Document Links

**What it does:** Detects file references in your documents and makes them clickable. Supports markdown links, import statements, and string-literal file paths.

**How to use:** Open a file in your IDE. File references automatically become clickable links (usually shown with an underline).

**Reference types detected:**

| Pattern | Example | Description |
|---------|---------|-------------|
| Markdown links | `[Guide](../guides/QUICK-START.md)` | Standard markdown link syntax |
| Java imports | `import com.example.Service;` | Java import statements |
| JS/TS imports | `require('./utils/helper.js')` | CommonJS and ES module imports |
| String file paths | `"config/settings.yaml"` | Quoted file paths in code |

**Resolution order:**
1. Relative to the current file's directory
2. Relative to the workspace root
3. Search the Synthesis index by filename

**Supported file extensions in references:** `.java`, `.py`, `.js`, `.ts`, `.tsx`, `.jsx`, `.md`, `.yaml`, `.yml`, `.json`, `.xml`, `.go`, `.rs`, `.kt`, `.sh`, `.sql`, `.toml`, `.html`, `.css`, `.scss`

**Anchor handling:** Fragment identifiers (e.g., `README.md#installation`) are stripped for file resolution. The link navigates to the file; anchor navigation depends on IDE support.

---

### 3. Hover

**What it does:** When you hover over a file reference, a popup displays metadata about the referenced file, including type, language, size, path, and relationship counts.

**How to use:** Hover your mouse cursor over any recognized file reference.

**What you see:**

```
Synthesis: src/auth/AuthService.java

| Property   | Value            |
|------------|------------------|
| Type       | CODE             |
| Language   | Java             |
| Size       | 12.1 KB          |
| Path       | /full/path/...   |

Relationships:
- 5 outgoing references
- 12 incoming references
```

**Information displayed:**

| Field | Source | Description |
|-------|--------|-------------|
| Type | `FileUtils.classifyFile()` | File classification (CODE, MARKDOWN, PDF, etc.) |
| Language | `FileUtils.detectLanguage()` | Programming language (code files only) |
| Size | `Files.size()` | Human-readable file size |
| Path | File system | Absolute path to the file |
| Outgoing references | Synthesis index | Files this file depends on |
| Incoming references | Synthesis index | Files that depend on this file |

**When hover shows "File not found":** The referenced file does not exist on disk. This typically indicates a broken link. Check the Diagnostics panel for details.

---

### 4. Go to Definition

**What it does:** Navigates to the file referenced at the cursor position. Works on markdown links, import statements, and file path strings.

**How to use:**
- **VSCode:** Cmd+Click (Mac) / Ctrl+Click (Windows/Linux), or F12
- **IntelliJ:** Cmd+Click / Ctrl+Click, or Ctrl+B
- **Neovim:** `gd` or `:lua vim.lsp.buf.definition()`
- **Vim:** `:LspDefinition`
- **Emacs:** `M-.` (`xref-find-definitions`)

**Behavior:** Opens the referenced file at line 1, column 1. The server resolves the reference using the same resolution strategy as Document Links.

---

### 5. Find References

**What it does:** Shows all files in the workspace that reference the current file. Uses Synthesis bidirectional relationship analysis to find incoming references.

**How to use:**
- **VSCode:** Right-click > "Find All References", or Shift+F12
- **IntelliJ:** Right-click > "Find Usages", or Alt+F7
- **Neovim:** `:lua vim.lsp.buf.references()`
- **Vim:** `:LspReferences`
- **Emacs:** `M-?` (`xref-find-references`)

**What you see:** A list of files that import, reference, or link to the current file. Each result shows:
- File path (as a clickable link)
- Location: line 1, column 1 (file-level reference)

**How it works:** The server uses `RelateCommand.analyzeIncomingRefs()` to scan all indexed files for references to the current file. This detects:
- Java import statements referencing the file's class
- Markdown links pointing to the file
- String literals containing the file name
- Other file references in code

**Performance:** Completes in 0.5-2 seconds depending on workspace size (scans up to 5,000 files).

---

### 6. Code Lens

**What it does:** Displays an inline annotation at the top of each file showing the count of outgoing and incoming references. This provides an at-a-glance view of a file's connectivity and potential refactoring impact.

**How to use:** Open any file. If it has relationships tracked by Synthesis, a code lens annotation appears above line 1.

**What you see:**

```
Synthesis: 5 outgoing, 12 incoming references     <- Code Lens (line 0)
---
package com.example.auth;                         <- Your file content
import com.example.db.UserRepository;
...
```

**Interpretation:**
- **Outgoing references:** Files this file depends on (imports, links)
- **Incoming references:** Files that depend on this file (consumers, importers)
- **High incoming count:** Indicates a "hub" file -- changes have wide impact
- **Zero references:** File is either isolated or not yet indexed

**Refresh behavior:** Code lens data is computed when the file is opened. To refresh after re-scanning, close and reopen the file.

---

### 7. Diagnostics

**What it does:** Warns about broken file references in markdown documents. Diagnostics appear in the IDE's Problems panel and as squiggly underlines in the editor.

**How to use:** Diagnostics are computed automatically when you:
1. Open a file (`didOpen`)
2. Save a file (`didSave`)

**What you see:**
- **Warning:** Yellow squiggly underline on the broken link text
- **Problems panel:** "Broken link: file not found - missing-file.md" with severity Warning and source "synthesis"

**What is checked:**
- Markdown links: `[text](path/to/file.md)` -- checks if the target file exists
- HTTP/HTTPS links and anchor-only links (`#section`) are skipped
- Anchor fragments in file links are stripped before checking

**Severity:** Warning (not Error). Broken links are informational -- they do not prevent compilation or execution.

**Clearing diagnostics:** When you close a file, its diagnostics are cleared. When you fix a broken link and save, diagnostics are recomputed.

---

## Advanced Configuration

### Command-Line Options

```
synthesis-lsp-server [OPTIONS]

Options:
  --workspace, -w <path>  Default workspace root (overridden by IDE rootUri)
  --log-level <level>     Logging level: FINE, INFO, WARNING, SEVERE
  --version, -v           Print version and exit
  --help, -h              Print this help and exit
```

### Workspace Resolution

The LSP server determines the workspace root in this priority order:

1. **IDE rootUri** -- The `rootUri` from the `initialize` request (set by the IDE)
2. **IDE workspaceFolders** -- The first workspace folder from the `initialize` request
3. **Command-line `--workspace`** -- Fallback if the IDE does not provide workspace info
4. **Current directory** -- Last resort default

In most IDE configurations, the IDE sends the project root automatically, so the `--workspace` flag is optional. It is useful when:
- The IDE sends a different root than where `.synthesis/` is located
- You want to index a parent directory that contains multiple projects

### Log Level Configuration

```bash
synthesis-lsp-server --workspace /path/to/project --log-level FINE
```

Logs are written to `~/.synthesis/logs/lsp-server.log` (5 MB rotating, 3 files, append mode). Logging never writes to stdout (reserved for LSP protocol).

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SYNTHESIS_HOME` | Installation directory | `~/.synthesis` |
| `SYNTHESIS_JAVA_OPTS` | JVM options (e.g., `-Xmx2g`) | (none) |

### Performance Tuning

For large workspaces (>10,000 files), increase JVM memory:

```bash
export SYNTHESIS_JAVA_OPTS="-Xmx2g -XX:+UseG1GC"
```

### Multi-Workspace Support

The LSP server operates on a single workspace at a time (the workspace root). For multi-workspace setups:

**Option A:** Use the IDE's multi-root workspace feature. The server uses the first workspace folder.

**Option B:** Run separate LSP server instances for each workspace (IDE-dependent).

### Text Document Synchronization

The server uses **incremental** text document sync (`TextDocumentSyncKind.Incremental`). The server tracks:
- `didOpen` -- Stores document content, runs initial diagnostics
- `didChange` -- Updates stored content (full replacements only; partial incremental changes are tracked but not fully applied)
- `didClose` -- Removes document from tracking, clears diagnostics
- `didSave` -- Re-runs diagnostics

---

## Troubleshooting

### Server Won't Start

**Symptom:** IDE reports "Language server failed to start" or "Connection refused."

**Checks:**
1. Verify Java 17+: `java -version`
2. Verify the JAR exists: `ls ~/.synthesis/lib/synthesis-lsp-server.jar`
3. Test manually: `synthesis-lsp-server --version` (should print version to stderr)
4. Check logs: `tail -f ~/.synthesis/logs/lsp-server.log`

**Fix:** Install Synthesis or rebuild:

```bash
# Install
curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash

# Or build from source
cd /path/to/synthesis && mvn package -DskipTests
```

### No Symbols Found (Cmd+T Returns Empty)

**Symptom:** Workspace symbol search returns no results.

**Checks:**
1. Workspace is initialized: `synthesis status` (in the project directory)
2. Index is not empty: `synthesis status` shows file count > 0
3. The server is using the correct workspace path (check `lsp-server.log` for "Workspace from client:" or "Workspace:" messages)

**Fix:**

```bash
cd /path/to/your-project
synthesis init
synthesis scan
```

Then restart the LSP server (restart your IDE or reload the window).

### Features Not Working

**Symptom:** Hover, links, diagnostics, or other features are not appearing.

**Checks:**
1. Is the server connected? Check the IDE's LSP status indicator.
2. Is the file type included? The server must be configured for the file's language.
3. Are there references to detect? Open a markdown file with links to test document links.
4. Increase log level: `--log-level FINE` and check `lsp-server.log`.

### Broken Links Not Detected

**Symptom:** Known broken links in markdown files do not show warnings.

**Checks:**
1. The file must be **open** in the editor (diagnostics are per-document)
2. The link must use markdown syntax: `[text](path)` -- plain URLs are not checked
3. HTTP/HTTPS links are deliberately not checked (only local file references)
4. Save the file to trigger re-analysis

### Slow Responses

**Symptom:** Code lens, find references, or hover take several seconds.

**Causes and fixes:**
1. **Large workspace:** The server scans up to 5,000 files for relationship analysis. For very large workspaces, this can take 1-3 seconds.
2. **First request after startup:** JVM warm-up affects the first few requests. Subsequent requests are faster.
3. **Disk I/O:** Ensure the workspace is on SSD.
4. **Memory pressure:** Set `SYNTHESIS_JAVA_OPTS="-Xmx2g"` for large workspaces.

### Code Lens Shows Zero References

**Symptom:** Code lens shows "0 outgoing, 0 incoming" even for files with known dependencies.

**Checks:**
1. The index may be stale. Run `synthesis scan` and restart the IDE.
2. The file may not be in the index (check `synthesis search "filename"`).
3. The file's references may use patterns not yet detected by the relationship analyzer.

---

## Performance

### Response Times

All times measured on a 16 GB RAM laptop with SSD and an 8,934-file workspace.

| Feature | Typical Time | Notes |
|---------|-------------|-------|
| Workspace symbols | 0.1-0.3s | Lucene search, up to 50 results |
| Document links | <0.1s | Regex matching on open document |
| Hover | 0.1-0.5s | File metadata + optional relationship lookup |
| Go to definition | <0.1s | Path resolution only |
| Find references | 0.5-2.0s | Scans up to 5,000 files for incoming refs |
| Code lens | 0.5-2.0s | Full relationship analysis (both directions) |
| Diagnostics | <0.1s | Markdown link checking only |

### Concurrency

All LSP methods return `CompletableFuture` and execute asynchronously. The server can handle multiple concurrent requests (e.g., hover while code lens is computing). Each operation opens its own `SearchIndex` reader, ensuring thread-safe Lucene access.

### Memory

The LSP server maintains:
- Open document content (in `openDocuments` map)
- Lucene index readers (opened per-request, closed after use)
- No persistent caches

Typical memory usage: 200-500 MB (JVM heap).

---

## Example Workflows

### 1. Code Navigation

Navigating an unfamiliar codebase:

1. Press **Cmd+T** and type a concept (e.g., "payment")
2. Select a result to open the file
3. Hover over references to see file metadata and relationship counts
4. **Cmd+Click** on a reference to navigate to it
5. Use **Find References** to see what depends on the current file

### 2. Refactoring Impact Assessment

Before renaming or moving a file:

1. Open the file you want to change
2. Check the **Code Lens** at the top: "Synthesis: 5 outgoing, 12 incoming references"
3. Use **Find References** to see the 12 files that depend on this one
4. Open each referencing file and review the specific usage
5. Make changes with confidence that all consumers are identified

### 3. Documentation Audit

Finding and fixing broken links:

1. Open a markdown file
2. Check the **Problems panel** for "Broken link" warnings
3. The squiggly underline shows exactly which link is broken
4. Fix the link target and save -- diagnostics update automatically
5. Repeat across other markdown files

### 4. Architecture Exploration

Understanding module structure:

1. Press **Cmd+T** and search for "config" to find configuration files
2. Open a central configuration file
3. Check **Code Lens** to see how many files reference it
4. Use **Find References** to see all consumers
5. **Cmd+Click** through the reference chain to understand the dependency graph

### 5. Onboarding

Getting oriented in a new project:

1. Press **Cmd+T** and search for "README" to find documentation entry points
2. Open the main README
3. Use **Document Links** to click through to referenced guides
4. Hover over file references to understand file types and sizes
5. Use **Workspace Symbols** to find specific components as you explore

---

## See Also

- **[LSP Quick Start](./LSP-QUICKSTART.md)** -- 5-minute setup for each IDE
- **[LSP Protocol Reference](../api/LSP-PROTOCOL-REFERENCE.md)** -- Protocol-level details for extension developers
- **[IDE Integration Guides](./LSP-IDE-INTEGRATION-GUIDES.md)** -- Detailed per-IDE setup instructions
- **[MCP Server Guide](./MCP-COMPREHENSIVE-GUIDE.md)** -- AI agent integration via MCP
- **[Quick Start](./QUICK-START.md)** -- CLI usage guide
- **[User Guide](./USER-GUIDE.md)** -- Complete Synthesis reference
