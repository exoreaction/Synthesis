# Synthesis for Developers

**Index your codebase in seconds. Search everything in under a second. Understand what breaks before you change it.**

---

## Why Developers Use Synthesis

Your IDE shows you one project. You work across ten. Grep is slow, finds only text, and does not know about relationships. Synthesis indexes everything -- code, docs, config, media -- and gives you sub-second search with bi-directional dependency tracking.

**What changes in your daily workflow:**

| Task | Before Synthesis | With Synthesis |
|------|-----------------|----------------|
| Find a file by content | `grep -r` across repos (5-15 seconds) | `synthesis search` (<1 second) |
| Find what depends on a file | IDE "Find Usages" (one project) | `synthesis relate` (all projects) |
| Understand a new codebase | Read files for hours | `synthesis ask "how does auth work?"` |
| Check what changed | `git log` per repo | `synthesis changed --since 2026-01-01` |
| Get an architecture overview | Ask a colleague or read stale docs | `synthesis graph --modules` |

---

## Getting Started

### Install

```bash
curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash
```

Verify: `synthesis --version` should print `Synthesis 1.8.0-SNAPSHOT`.

### Initialize and Scan

```bash
cd ~/your-project
synthesis init
synthesis scan
```

`init` creates a `.synthesis/` directory with configuration. `scan` indexes all files matching the default patterns (code, docs, config, media). A typical 2,000-file project indexes in 5-10 seconds.

### First Search

```bash
synthesis search "authentication"
```

Results show file type, path, description, and size. Ranked by relevance.

---

## Daily Workflow

### Morning: Update Your Index

```bash
synthesis scan
```

Incremental scan -- only processes new or modified files. Takes 1-5 seconds.

Or run `synthesis watch` in a background terminal for continuous auto-indexing as you edit files.

### Search for Anything

```bash
synthesis search "payment processing"         # Multi-word search
synthesis search "EventStoreService"           # Exact class name
synthesis search "TODO"                        # Find technical debt
synthesis search "database connection"         # Config and code
```

Search works across all file types: Java, Python, TypeScript, Markdown, YAML, JSON, PDF, even video metadata.

### Ask Questions (AI-Powered)

Requires an API key. Set it once:

```bash
synthesis credentials set ANTHROPIC_API_KEY sk-ant-...
```

Then ask natural-language questions:

```bash
synthesis ask "how does authentication work in this project?"
synthesis ask "what is the purpose of EventStoreService?"
synthesis ask "where is the database configuration?"
```

Synthesis uses your indexed files as context and generates an answer referencing specific files.

### Understand a File

```bash
synthesis explain src/auth/JwtService.java
synthesis explain --module src/auth/
synthesis explain --pattern "retry logic"
```

`explain` generates an AI-powered explanation covering the file's purpose, key methods, relationships, and how it fits into the broader codebase.

**Depth options:**
- `--depth brief` -- 3-5 sentences
- `--depth standard` -- Multiple sections (default)
- `--depth deep` -- Comprehensive analysis

---

## Understanding Code Relationships

### See What a File Connects To

```bash
synthesis relate src/api/UserController.java
```

Shows:
- **Outgoing:** What this file imports or references
- **Incoming:** What other files import or reference this file

The incoming list is your "blast radius" -- everything that might break if you change this file.

### Generate a Mermaid Diagram

```bash
synthesis relate src/api/UserController.java --mermaid
```

Outputs a Mermaid graph you can paste into GitHub, GitLab, or any Mermaid renderer.

### Follow Dependencies Deeper

```bash
synthesis relate src/api/UserController.java --depth 2
```

Follows relationships two levels deep: what this file depends on, and what *those* files depend on.

---

## Architecture and Analysis

### Codebase Health

```bash
synthesis insights
```

Analyzes your codebase and reports:
- File count by type and language
- Largest files
- Most connected files (highest dependency count)
- Potential issues (dead code, god classes, missing tests)

### Architecture Anti-Patterns

```bash
synthesis architecture
```

Detects:
- **God classes** -- files over 1,000 lines
- **Circular dependencies** -- A imports B imports A
- **Dead code** -- files with zero incoming references
- **Missing documentation** -- directories without README files
- **Test coverage gaps** -- source files without test counterparts
- **High coupling** -- files with excessive incoming references

### Visual Dependency Graphs

```bash
synthesis graph --modules --format mermaid          # Module-level overview
synthesis graph src/auth/AuthService.java --depth 2  # File-level graph
synthesis graph --cross-repo --format mermaid        # Cross-repository view
```

Output formats: `mermaid` (default), `png`, `svg`, `dot`.

PNG and SVG require Graphviz installed. Mermaid works everywhere.

### Cross-Repository Dependencies

```bash
synthesis cross-repo-deps
```

Maps dependencies between repositories. Useful for monorepos and microservice architectures. Outputs a summary of which repos depend on which others.

---

## Git Integration

### What Changed Recently?

```bash
synthesis diff HEAD~5                           # Changes in the last 5 commits
synthesis diff main..feature-branch             # Changes between branches
synthesis changed --since 2026-01-01            # All files changed since a date
```

### Watch for Changes

```bash
synthesis watch
```

Runs in the foreground, monitors your workspace for file changes, and automatically updates the index. Search always returns current results.

### Incremental Maintenance

```bash
synthesis maintain
```

Detects changes since the last scan and updates the index. Lighter than `scan` -- good for automation.

---

## Multi-Workspace and Cross-Workspace Search

### Register Multiple Workspaces

Each workspace gets its own `.synthesis/` directory. Initialize and scan each one separately:

```bash
cd ~/project-a && synthesis init && synthesis scan
cd ~/project-b && synthesis init && synthesis scan
cd ~/project-c && synthesis init && synthesis scan
```

### Search Across All Workspaces

```bash
synthesis search --all "authentication"
```

Searches every registered workspace simultaneously.

### Find Which Workspace Has a File

```bash
synthesis which EventStoreService.java
```

Returns the workspace(s) containing files matching the pattern.

### List All Workspaces

```bash
synthesis list
synthesis list --type source      # Filter by workspace type
```

---

## Enriching Binary Files

Make images, videos, and PDFs searchable by generating companion files:

```bash
synthesis enrich                  # Generate companions for all binary files
synthesis enrich --type video     # Only for videos
synthesis enrich --level ai       # Use AI for rich descriptions (requires API key)
synthesis enrich --dry-run        # Preview what would be generated
synthesis scan                    # Re-scan to index the new companion files
```

Companion files are markdown files (e.g., `demo.mp4.synthesis.md`) that contain metadata, descriptions, and related file references.

---

## Skills and Learning

### Generate Claude Code Skills

```bash
synthesis learn                   # Generate skills from workspace knowledge
synthesis learn --install         # Install skills to ~/.claude/skills/
```

Skills help Claude Code understand your project's patterns and conventions.

### Export Bundled Skills

```bash
synthesis export-skills           # Export built-in Synthesis skills
```

---

## Configuration Tips

### Customize File Patterns

Edit `.synthesis/config.yaml`:

```yaml
scan:
  includePatterns:
    - "**/*.java"
    - "**/*.py"
    - "**/*.ts"
    - "**/*.md"
    - "**/*.yaml"
  excludePatterns:
    - "**/node_modules/**"
    - "**/target/**"
    - "**/build/**"
    - "**/.venv/**"
```

### Increase File Size Limit

Default is 10 MB. For large files:

```yaml
scan:
  maxFileSizeBytes: 52428800    # 50 MB
```

### Shell Alias

Add to `~/.bashrc` or `~/.zshrc`:

```bash
alias syn='synthesis'
```

---

## Troubleshooting

### "Not a Synthesis workspace"

Run `synthesis init` in your project directory first.

### No search results

1. Check that you ran `synthesis scan`
2. Verify your file types are in `includePatterns` in `.synthesis/config.yaml`
3. Check that files are not excluded by `excludePatterns`

### Slow scanning

Exclude build artifacts: `node_modules`, `target`, `build`, `.venv`, `dist`.

### AI features not working

```bash
synthesis credentials status      # Check if key is stored
synthesis credentials set ANTHROPIC_API_KEY sk-ant-...   # Store key
```

Also verify `ai.enabled: true` in `.synthesis/config.yaml`.

---

## Command Quick Reference

```
synthesis init                          # Initialize workspace
synthesis scan                          # Index files (incremental)
synthesis scan --full                   # Full rebuild
synthesis search "query"                # Search everything
synthesis search --all "query"          # Search all workspaces
synthesis ask "question"                # AI-powered Q&A
synthesis explain <file>                # AI file explanation
synthesis relate <file>                 # Show dependencies
synthesis relate <file> --mermaid       # Mermaid diagram
synthesis graph --modules               # Architecture overview
synthesis insights                      # Codebase health
synthesis architecture                  # Anti-pattern detection
synthesis diff HEAD~5                   # Recent changes
synthesis changed --since 2026-01-01    # Files changed since date
synthesis watch                         # Auto-update on changes
synthesis maintain                      # Manual incremental update
synthesis cross-repo-deps               # Cross-repo dependencies
synthesis which <file>                  # Find which workspace has a file
synthesis status                        # Workspace health
synthesis enrich                        # Make binary files searchable
synthesis credentials status            # Check API key
```

---

**Related guides:**
- [Architecture Guide](./ARCHITECT.md) -- deep dependency analysis
- [Engineering Manager Guide](./ENGINEERING-MANAGER.md) -- team adoption and metrics
- [AI Agent Guide](./AI-AGENT.md) -- MCP integration for tool builders
- [Full User Guide](../USER-GUIDE-V2.md) -- complete command reference
