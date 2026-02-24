# IDE Integration (LSP Server)

> **See also:** For comprehensive documentation, refer to the new dedicated guides:
> - **[LSP Quick Start](./LSP-QUICKSTART.md)** -- 5-minute setup for all IDEs
> - **[LSP Comprehensive Guide](./LSP-COMPREHENSIVE-GUIDE.md)** -- Full feature reference, advanced config, troubleshooting
> - **[LSP Protocol Reference](../api/LSP-PROTOCOL-REFERENCE.md)** -- LSP 3.17 protocol details
> - **[IDE Integration Guides](./LSP-IDE-INTEGRATION-GUIDES.md)** -- Detailed per-IDE setup

Synthesis provides a Language Server Protocol (LSP 3.17) server that brings workspace intelligence directly into your IDE -- workspace symbol search, document links, hover metadata, broken link diagnostics, go-to-definition, find references, and code lens.

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

### 3. Configure Your IDE

See IDE-specific sections below.

---

## VSCode

### Option A: Manual Configuration (settings.json)

Until the VSCode extension is published, use a generic LSP client extension:

1. Install the "LSP Client" extension (e.g., `vscode-languageclient`)
2. Add to `.vscode/settings.json`:

```json
{
  "synthesis.server.path": "synthesis-lsp-server",
  "synthesis.workspace": "/path/to/your-project"
}
```

### Option B: Using a Generic LSP Extension

Install an extension like "Generic LSP Client" and configure:

```json
{
  "genericLSP.servers": [
    {
      "name": "Synthesis",
      "command": "synthesis-lsp-server",
      "args": ["--workspace", "/path/to/project"],
      "fileTypes": ["markdown", "java", "python", "typescript", "javascript", "yaml", "json"]
    }
  ]
}
```

### Features in VSCode

- **Cmd+T / Ctrl+T**: Search workspace symbols (files by name/content)
- **Hover**: See file metadata (type, size, language, relationship counts)
- **Ctrl+Click**: Navigate to referenced files
- **Document Links**: Clickable file references in markdown
- **Problems Panel**: Broken link warnings
- **Code Lens**: Relationship counts at top of files

---

## IntelliJ IDEA

### Using Built-in LSP Support (2023.2+)

1. Go to **Settings > Languages & Frameworks > Language Server Protocol**
2. Click **Add** and configure:
   - **Name:** Synthesis
   - **Command:** `synthesis-lsp-server --workspace /path/to/project`
   - **File patterns:** `*.md;*.java;*.py;*.ts;*.js;*.yaml;*.yml;*.json`

### Using LSP4IJ Plugin

1. Install the "LSP4IJ" plugin from JetBrains Marketplace
2. Configure the language server:
   - **Server command:** `synthesis-lsp-server`
   - **Arguments:** `--workspace /path/to/project`

---

## Neovim

### Using nvim-lspconfig

Add to your `init.lua`:

```lua
local lspconfig = require('lspconfig')
local configs = require('lspconfig.configs')

-- Define Synthesis LSP configuration
if not configs.synthesis then
  configs.synthesis = {
    default_config = {
      cmd = { 'synthesis-lsp-server', '--workspace', vim.fn.getcwd() },
      filetypes = { 'markdown', 'java', 'python', 'typescript', 'javascript', 'yaml', 'json' },
      root_dir = lspconfig.util.root_pattern('.synthesis', '.git'),
      settings = {},
    },
  }
end

lspconfig.synthesis.setup{}
```

### Using coc.nvim

Add to `coc-settings.json`:

```json
{
  "languageserver": {
    "synthesis": {
      "command": "synthesis-lsp-server",
      "args": ["--workspace", "."],
      "filetypes": ["markdown", "java", "python", "typescript", "javascript", "yaml", "json"],
      "rootPatterns": [".synthesis", ".git"]
    }
  }
}
```

---

## Vim

### Using vim-lsp

Add to `.vimrc`:

```vim
if executable('synthesis-lsp-server')
  au User lsp_setup call lsp#register_server({
    \ 'name': 'synthesis',
    \ 'cmd': {server_info->['synthesis-lsp-server', '--workspace', getcwd()]},
    \ 'allowlist': ['markdown', 'java', 'python', 'typescript', 'javascript', 'yaml', 'json'],
    \ })
endif
```

---

## Emacs

### Using lsp-mode

Add to your Emacs configuration:

```elisp
(use-package lsp-mode
  :config
  (lsp-register-client
   (make-lsp-client
    :new-connection (lsp-stdio-connection '("synthesis-lsp-server" "--workspace" "."))
    :activation-fn (lsp-activate-on "markdown" "java" "python" "typescript" "javascript" "yaml" "json")
    :server-id 'synthesis)))
```

### Using eglot (built-in Emacs 29+)

```elisp
(add-to-list 'eglot-server-programs
  '((markdown-mode java-mode python-mode typescript-mode) . ("synthesis-lsp-server")))
```

---

## LSP Features

### Workspace Symbols (Cmd+T / Ctrl+T)

Searches the Synthesis index and returns matching files as navigable symbols. Much faster than IDE file indexing for large workspaces.

### Document Links

Automatically detects file references in:
- Markdown links: `[text](path/to/file.md)`
- Import statements: `import com.example.Service`
- String literals: `"config/settings.yaml"`

Links become clickable in the editor.

### Hover

Hovering over a file reference shows:
- File type and language
- File size
- Absolute path
- Relationship counts (outgoing/incoming references)

### Diagnostics

Warns about broken file references in markdown documents. Shown in the Problems panel (VSCode) or equivalent.

### Go to Definition

Ctrl+Click or F12 on a file reference navigates to that file.

### Find References

Shows all files that reference the current file. Uses Synthesis relationship analysis.

### Code Lens

Shows relationship counts (e.g., "Synthesis: 3 outgoing, 12 incoming references") at the top of each file.

---

## Configuration

### Command-Line Options

```
synthesis-lsp-server [OPTIONS]

Options:
  --workspace, -w <path>  Default workspace root (overridden by IDE rootUri)
  --log-level <level>     Logging level: FINE, INFO, WARNING, SEVERE
  --version, -v           Print version and exit
  --help, -h              Print help and exit
```

### Logging

Logs are written to `~/.synthesis/logs/lsp-server.log` (never to stdout, which is reserved for LSP protocol).

---

## Troubleshooting

### "Server not starting"

1. Verify Java 21+ is installed: `java -version`
2. Verify the JAR exists: `ls ~/.synthesis/lib/synthesis-lsp-server.jar`
3. Check logs: `tail -f ~/.synthesis/logs/lsp-server.log`

### "No symbols found"

1. Ensure workspace is initialized: `synthesis status`
2. Re-scan if needed: `synthesis scan`

### "Features not working"

1. Check that the IDE is connecting to the server (look for initialization logs)
2. Verify file types are included in the server configuration
3. Try increasing log level: `--log-level FINE`

---

## Technical Details

- **Protocol:** LSP 3.17 over JSON-RPC 2.0 (stdio)
- **Library:** Eclipse LSP4J 0.23.1
- **Java:** 17+ required
- **Index:** Shared Apache Lucene index (same as CLI and MCP server)
- **Thread Safety:** All LSP methods return CompletableFuture (async, non-blocking)
