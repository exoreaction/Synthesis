# IDE Integration Guides

Detailed setup instructions for the Synthesis LSP server in each supported IDE. For a quick overview, see the [LSP Quick Start](./LSP-QUICKSTART.md).

**Prerequisites for all IDEs:**
- Java 21+ installed
- Synthesis installed (`synthesis --version` to verify)
- Workspace indexed (`synthesis init && synthesis scan` in your project directory)

---

## Table of Contents

- [VSCode](#vscode)
- [IntelliJ IDEA](#intellij-idea)
- [Neovim](#neovim)
- [Vim](#vim)
- [Emacs](#emacs)
- [Verifying the Connection](#verifying-the-connection)

---

## VSCode

### Option A: Generic LSP Client Extension

Until a dedicated Synthesis VSCode extension is published, use a generic LSP client extension.

**Step 1:** Install a generic LSP extension from the marketplace. Options:
- "Generic LSP Client"
- "vscode-lsp-client"
- Any extension that supports custom language servers

**Step 2:** Configure the extension in `.vscode/settings.json`:

```json
{
  "genericLSP.servers": [
    {
      "name": "Synthesis",
      "command": "synthesis-lsp-server",
      "args": ["--workspace", "/absolute/path/to/your-project"],
      "fileTypes": [
        "markdown",
        "java",
        "python",
        "typescript",
        "javascript",
        "yaml",
        "json",
        "go",
        "rust",
        "kotlin"
      ]
    }
  ]
}
```

> **Note:** Use an absolute path for `--workspace`. The `~` shorthand may not be expanded correctly by all extensions.

**Step 3:** Reload the VSCode window (Cmd+Shift+P > "Reload Window").

### Features Available in VSCode

| Feature | Access |
|---------|--------|
| Workspace Symbols | Cmd+T (Mac) / Ctrl+T (Windows/Linux) |
| Document Links | Click on underlined references |
| Hover | Hover mouse over file references |
| Go to Definition | Cmd+Click / Ctrl+Click, or F12 |
| Find References | Shift+F12, or right-click > "Find All References" |
| Code Lens | Displayed inline above line 1 |
| Diagnostics | Problems panel (Cmd+Shift+M / Ctrl+Shift+M) |

### Debugging in VSCode

To see LSP communication in VSCode:

1. Open the Output panel (Cmd+Shift+U / Ctrl+Shift+U)
2. Select the language server's output channel from the dropdown
3. Watch request/response messages in real time

Additionally, check the server log:

```bash
tail -f ~/.synthesis/logs/lsp-server.log
```

### Workspace Settings vs User Settings

- **Workspace settings** (`.vscode/settings.json`): Use for project-specific paths
- **User settings** (`~/.config/Code/User/settings.json`): Use for default configuration

For team projects, add `.vscode/settings.json` to `.gitignore` and document the setup in your project's README.

---

## IntelliJ IDEA

### Option A: Built-in LSP Support (2023.2+)

IntelliJ IDEA 2023.2 and later has built-in LSP client support.

**Step 1:** Open **Settings** > **Languages & Frameworks** > **Language Server Protocol** > **Language Servers**.

**Step 2:** Click **+** (Add) and configure:

| Field | Value |
|-------|-------|
| **Name** | Synthesis |
| **Server command** | `synthesis-lsp-server --workspace /path/to/project` |
| **File patterns** | `*.md;*.java;*.py;*.ts;*.js;*.yaml;*.yml;*.json;*.go;*.rs;*.kt` |

**Step 3:** Click **Apply** and restart the IDE.

### Option B: LSP4IJ Plugin

For older IntelliJ versions or more control:

**Step 1:** Install the **LSP4IJ** plugin from the JetBrains Marketplace.

**Step 2:** Configure via **Settings** > **Languages & Frameworks** > **LSP4IJ**:

| Field | Value |
|-------|-------|
| **Server command** | `synthesis-lsp-server` |
| **Arguments** | `--workspace /path/to/project` |
| **File types** | Select relevant file types |

**Step 3:** Restart IntelliJ.

### Features Available in IntelliJ

| Feature | Access |
|---------|--------|
| Workspace Symbols | Shift+Shift (Search Everywhere) |
| Document Links | Cmd+Click / Ctrl+Click on links |
| Hover | Hover mouse, or Ctrl+Q (Quick Documentation) |
| Go to Definition | Cmd+Click / Ctrl+Click, or Ctrl+B |
| Find References | Alt+F7, or right-click > "Find Usages" |
| Code Lens | Inline annotations above line 1 |
| Diagnostics | Editor warnings + Inspection Results |

### Troubleshooting IntelliJ

**"Language server not starting":** Check that `synthesis-lsp-server` is on your PATH. In IntelliJ's terminal, run `which synthesis-lsp-server` (or `where synthesis-lsp-server` on Windows).

**"No features visible":** Ensure the file patterns match the file you have open. Try opening a `.md` file first.

**Logs:** IntelliJ logs LSP communication in **Help** > **Diagnostic Tools** > **Debug Log Settings**. Add `#com.intellij.lsp` to enable LSP debug logging.

---

## Neovim

### Using nvim-lspconfig (Recommended)

**Step 1:** Install [nvim-lspconfig](https://github.com/neovim/nvim-lspconfig) if you have not already.

**Step 2:** Add to your `init.lua`:

```lua
local lspconfig = require('lspconfig')
local configs = require('lspconfig.configs')

-- Define Synthesis LSP configuration
if not configs.synthesis then
  configs.synthesis = {
    default_config = {
      cmd = { 'synthesis-lsp-server', '--workspace', vim.fn.getcwd() },
      filetypes = {
        'markdown', 'java', 'python', 'typescript', 'javascript',
        'yaml', 'json', 'go', 'rust', 'kotlin'
      },
      root_dir = lspconfig.util.root_pattern('.synthesis', '.git'),
      settings = {},
    },
  }
end

-- Activate the server
lspconfig.synthesis.setup{
  on_attach = function(client, bufnr)
    -- Optional: set up keybindings
    local opts = { buffer = bufnr, noremap = true, silent = true }
    vim.keymap.set('n', 'gd', vim.lsp.buf.definition, opts)
    vim.keymap.set('n', 'gr', vim.lsp.buf.references, opts)
    vim.keymap.set('n', 'K', vim.lsp.buf.hover, opts)
  end,
}
```

**Step 3:** Restart Neovim or run `:LspRestart`.

### Using coc.nvim (Alternative)

Add to `coc-settings.json` (`:CocConfig`):

```json
{
  "languageserver": {
    "synthesis": {
      "command": "synthesis-lsp-server",
      "args": ["--workspace", "."],
      "filetypes": [
        "markdown", "java", "python", "typescript", "javascript",
        "yaml", "json", "go", "rust", "kotlin"
      ],
      "rootPatterns": [".synthesis", ".git"]
    }
  }
}
```

### Key Bindings for Neovim

| Action | Default Binding | Command |
|--------|----------------|---------|
| Workspace Symbols | -- | `:lua vim.lsp.buf.workspace_symbol('query')` |
| Hover | K | `:lua vim.lsp.buf.hover()` |
| Go to Definition | gd | `:lua vim.lsp.buf.definition()` |
| Find References | gr | `:lua vim.lsp.buf.references()` |
| Code Lens | -- | `:lua vim.lsp.codelens.run()` |
| Diagnostics | -- | `:lua vim.diagnostic.open_float()` |

### Troubleshooting Neovim

**Check server status:**

```vim
:LspInfo
```

**Check server logs:**

```bash
tail -f ~/.synthesis/logs/lsp-server.log
```

**Manual test:**

```vim
:lua print(vim.inspect(vim.lsp.get_active_clients()))
```

---

## Vim

### Using vim-lsp

**Step 1:** Install [vim-lsp](https://github.com/prabirshrestha/vim-lsp) and [async.vim](https://github.com/prabirshrestha/async.vim).

**Step 2:** Add to your `.vimrc`:

```vim
if executable('synthesis-lsp-server')
  au User lsp_setup call lsp#register_server({
    \ 'name': 'synthesis',
    \ 'cmd': {server_info->['synthesis-lsp-server', '--workspace', getcwd()]},
    \ 'allowlist': ['markdown', 'java', 'python', 'typescript', 'javascript', 'yaml', 'json'],
    \ })
endif
```

**Step 3:** Restart Vim.

### Key Mappings for vim-lsp

Add to `.vimrc`:

```vim
" Synthesis LSP key mappings
nmap <buffer> gd <plug>(lsp-definition)
nmap <buffer> gr <plug>(lsp-references)
nmap <buffer> K <plug>(lsp-hover)
nmap <buffer> <leader>ws <plug>(lsp-workspace-symbol)
```

### Using ALE (Alternative)

Add to `.vimrc`:

```vim
let g:ale_linters = {
\   'markdown': ['synthesis'],
\   'java': ['synthesis'],
\}

let g:ale_lsp_settings = {
\   'synthesis': {
\     'cmd': ['synthesis-lsp-server', '--workspace', '.'],
\   },
\}
```

---

## Emacs

### Using lsp-mode

**Step 1:** Install [lsp-mode](https://emacs-lsp.github.io/lsp-mode/).

**Step 2:** Add to your Emacs configuration (`init.el` or equivalent):

```elisp
(use-package lsp-mode
  :config
  (lsp-register-client
   (make-lsp-client
    :new-connection (lsp-stdio-connection
                     '("synthesis-lsp-server" "--workspace" "."))
    :activation-fn (lsp-activate-on
                    "markdown" "java" "python" "typescript"
                    "javascript" "yaml" "json")
    :server-id 'synthesis
    :priority -1)))

;; Auto-start LSP for supported modes
(add-hook 'markdown-mode-hook #'lsp)
(add-hook 'java-mode-hook #'lsp)
(add-hook 'python-mode-hook #'lsp)
(add-hook 'typescript-mode-hook #'lsp)
```

**Step 3:** Restart Emacs or evaluate the configuration.

### Using eglot (Built-in Emacs 29+)

```elisp
(add-to-list 'eglot-server-programs
  '((markdown-mode java-mode python-mode typescript-mode
     javascript-mode yaml-mode json-mode)
    . ("synthesis-lsp-server" "--workspace" ".")))

;; Auto-start eglot for supported modes
(add-hook 'markdown-mode-hook #'eglot-ensure)
(add-hook 'java-mode-hook #'eglot-ensure)
```

### Key Bindings for Emacs

| Action | lsp-mode | eglot |
|--------|----------|-------|
| Workspace Symbols | `M-x lsp-ui-find-workspace-symbol` | `M-x xref-find-apropos` |
| Hover | `M-x lsp-ui-doc-show` | `M-x eldoc` (auto) |
| Go to Definition | `M-.` | `M-.` |
| Find References | `M-?` | `M-?` |
| Diagnostics | `M-x lsp-ui-flycheck-list` | `M-x flymake-show-diagnostics-buffer` |

### Troubleshooting Emacs

**Check server status (lsp-mode):**

```
M-x lsp-describe-session
```

**Check server status (eglot):**

```
M-x eglot-events-buffer
```

**Server logs:**

```bash
tail -f ~/.synthesis/logs/lsp-server.log
```

---

## Verifying the Connection

After configuring any IDE, verify the LSP server is working:

### Test 1: Workspace Symbols

1. Use your IDE's symbol search (Cmd+T, Shift+Shift, etc.)
2. Type a known file name or keyword
3. You should see matching files from the Synthesis index

### Test 2: Hover

1. Open a markdown file with links to other files
2. Hover over a `[text](path)` link
3. You should see a popup with file type, size, and relationship counts

### Test 3: Diagnostics

1. Open a markdown file
2. Add a broken link: `[broken](this-file-does-not-exist.md)`
3. Save the file
4. You should see a warning in the Problems panel or as an underline

### Test 4: Check Logs

```bash
tail -20 ~/.synthesis/logs/lsp-server.log
```

You should see log entries for:
- `LSP Initialize request received`
- `Workspace from client: /path/to/project`
- `LSP Server initialized. Capabilities registered.`

---

## See Also

- **[LSP Quick Start](./LSP-QUICKSTART.md)** -- Condensed setup for all IDEs
- **[LSP Comprehensive Guide](./LSP-COMPREHENSIVE-GUIDE.md)** -- Full feature reference and configuration
- **[LSP Protocol Reference](../api/LSP-PROTOCOL-REFERENCE.md)** -- Protocol-level details
- **[MCP Quick Start](./MCP-QUICKSTART.md)** -- AI agent integration (Claude Code, Cursor)
