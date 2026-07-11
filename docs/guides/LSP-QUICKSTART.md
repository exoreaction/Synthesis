# LSP Server Quick Start

Get Synthesis working in your IDE in under 5 minutes. Supports VSCode, IntelliJ IDEA, Neovim, Vim, and Emacs.

## Prerequisites

- **Java 21+** installed (`java -version` to verify)
- **Synthesis** installed ([Installation Guide](https://github.com/exoreaction/Synthesis/blob/main/README.md#installation))
- A **workspace** already indexed (`synthesis init && synthesis scan`)

---

## VSCode

**Step 1:** Install a generic LSP client extension (e.g., "Generic LSP Client" or "vscode-languageclient").

**Step 2:** Add to `.vscode/settings.json`:

```json
{
  "genericLSP.servers": [
    {
      "name": "Synthesis",
      "command": "synthesis-lsp-server",
      "args": ["--workspace", "/path/to/your-project"],
      "fileTypes": ["markdown", "java", "python", "typescript", "javascript", "yaml", "json"]
    }
  ]
}
```

**Step 3:** Reload VSCode. Press **Cmd+T** (Mac) or **Ctrl+T** (Windows/Linux) and type a search query.

---

## IntelliJ IDEA

**Step 1:** Go to **Settings > Languages & Frameworks > Language Server Protocol**.

**Step 2:** Click **Add** and configure:
- **Name:** Synthesis
- **Command:** `synthesis-lsp-server --workspace /path/to/your-project`
- **File patterns:** `*.md;*.java;*.py;*.ts;*.js;*.yaml;*.yml;*.json`

**Step 3:** Restart IntelliJ. Synthesis features are active in matching files.

---

## Neovim

Add to your `init.lua`:

```lua
local lspconfig = require('lspconfig')
local configs = require('lspconfig.configs')

if not configs.synthesis then
  configs.synthesis = {
    default_config = {
      cmd = { 'synthesis-lsp-server', '--workspace', vim.fn.getcwd() },
      filetypes = { 'markdown', 'java', 'python', 'typescript', 'javascript', 'yaml', 'json' },
      root_dir = lspconfig.util.root_pattern('.synthesis', '.git'),
    },
  }
end

lspconfig.synthesis.setup{}
```

---

## What You Get

| Feature | Shortcut | Description |
|---------|----------|-------------|
| **Workspace Symbols** | Cmd+T / Ctrl+T | Search all indexed files instantly |
| **Document Links** | Click | Clickable file references in markdown and code |
| **Hover** | Hover over path | File metadata (type, size, language, relationships) |
| **Go to Definition** | Cmd+Click / F12 | Navigate to referenced file |
| **Find References** | Right-click > Find References | All files referencing current file |
| **Code Lens** | (inline, top of file) | "Synthesis: 3 outgoing, 12 incoming references" |
| **Diagnostics** | Problems panel | Warnings for broken file links |

## Performance

All operations return in under 1 second. Workspace symbol search completes in 0.1-0.3 seconds for workspaces up to 10,000 files.

## Next Steps

- **[LSP Comprehensive Guide](./LSP-COMPREHENSIVE-GUIDE.md)** -- Full feature reference, advanced configuration, troubleshooting
- **[LSP Protocol Reference](../api/LSP-PROTOCOL-REFERENCE.md)** -- Protocol-level details for extension developers
- **[IDE Integration Guides](./LSP-IDE-INTEGRATION-GUIDES.md)** -- Detailed setup for each IDE
