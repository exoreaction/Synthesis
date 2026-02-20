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
| See what changes together | Manual git log archaeology | `synthesis impact` (co-change analysis) |
| Understand a new codebase | Read files for hours | `synthesis ask "how does auth work?"` |
| Check what changed | `git log` per repo | `synthesis changed --since 2026-01-01` |
| Get an architecture overview | Ask a colleague or read stale docs | `synthesis graph --modules` |
| Clean up stale files | Manual scanning and moving | `synthesis sweep --dry-run` |
| Check workspace health | Eyeball it | `synthesis health` |

---

## Getting Started

### Install

```bash
curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash
```

Verify: `synthesis --version` should print `Synthesis 1.11.1`.

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

### Run Maintenance

```bash
synthesis maintain
```

Beyond updating the index, `maintain` now powers several subsystems: it populates the co-change graph from git history, builds knowledge graph edges linking docs to the source files they reference, and takes snapshots for change tracking.

Add flags for additional automation:

```bash
synthesis maintain --update-activity-log   # Auto-append today's changes to ACTIVITY-LOG.md
synthesis maintain --sync                  # Write/update directory identity files after maintenance
synthesis maintain --rebalance             # Move misplaced archive files back to active dirs
```

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

### Co-Change Analysis: What Actually Changes Together

Static dependencies (`relate`) tell you what *could* break. Co-change analysis tells you what *actually* changes together in practice:

```bash
synthesis impact src/api/UserController.java
```

`impact` uses historical commit co-occurrence data to show which files are modified together with the target file. This reveals coupling that import analysis cannot see -- config files that always need updating, test files that need changes, documentation that drifts when the source changes.

Use this before refactoring: if two files always change together but have no direct import relationship, there is hidden coupling you need to understand before separating them.

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

## Workspace Hygiene

Synthesis v1.11.1 includes a full suite of workspace cleanup commands. These are particularly useful for document-heavy workspaces where files accumulate at the root level, but they work equally well for code projects.

### Health Check

```bash
synthesis health
```

Audits your workspace and reports a health score (0-100). Detects phantom sub-workspace paths, build artifacts at root, empty directories, and loose files. Grades range from A (90+) to F (<40).

```bash
synthesis health --fix-config    # Auto-fix phantom sub-workspace paths in config
```

### Naming Consistency

```bash
synthesis naming
```

Reports naming inconsistencies across your workspace: singular/plural collisions (`client/` vs `clients/`), semantic duplicates (`workshop/` vs `workshops/`), and convention drift (mixed kebab-case and CamelCase in the same parent). Analysis only -- never moves anything.

### Prune Empty Directories

```bash
synthesis prune --dry-run        # Preview what would be removed
synthesis prune --yes            # Prune without prompting
```

Recursively removes directories that contain no regular files. Skips dot-directories, directories with a README.md, and paths referenced in config.

### Sweep Stale Files

```bash
synthesis sweep --dry-run        # Preview: shows ROUTABLE and ARCHIVE groups
synthesis sweep --yes            # Execute routing + archival
synthesis sweep --archive-only   # Skip routing, send everything to archive/
```

Scans root-level files for stale items (session scripts, ephemeral docs, dated reports, archives) and routes them intelligently using the Directory Identity System before falling back to archival. Dry-run output shows which files would be routed to active directories and which would go to `archive/`.

### TTL Rules for Ephemeral Files

```bash
synthesis ttl set "TONIGHT-*.md" --days 3    # Auto-expire in 3 days
synthesis ttl set "*.tmp" --days 1           # Temp files expire in 1 day
synthesis ttl check                          # See what's expired
synthesis ttl check --archive               # Move expired files to archive
```

### Find and Fix Fragmentation

```bash
synthesis scatter --all                      # Find content spread across multiple dirs
synthesis consolidate "Entity Name"          # Preview consolidation
synthesis consolidate "Entity Name" --execute  # Actually move files
```

---

## The Directory Identity System

New in v1.11.1, the Directory Identity System lets each directory declare what kind of files it accepts. This is the foundation for intelligent file routing in `sweep`, `maintain --rebalance`, and `staging route`.

### How It Works

Run `synthesis sync` to have Synthesis analyze your workspace directories and write identity files:

```bash
synthesis sync
```

This creates or updates `.synthesis.md` files in each directory with YAML front matter:

```yaml
---
synthesis:
  accepts:
    types:
      - "documentation"
      - "meeting-notes"
    formats:
      - "md"
      - "pdf"
    patterns:
      - "*meeting*"
  scope:
    level: "WORKSPACE"
    organization: null
    entity: null
  confidence: 0.8
  last_synced: "2026-02-19T..."
  source: "directory sync"
---
```

Synthesis infers types from well-known directory names (like "meetings", "docs", "automation") and from the patterns of existing files. The `DirectoryIdentityRouter` then scores any file against all candidate directories to find the best destination.

### Practical Uses

- **`synthesis sweep`** routes stale files to matching directories before archiving
- **`synthesis maintain --rebalance`** moves misplaced archive files back to identity-matched active directories (score >= 0.5)
- **`synthesis maintain --sync`** updates identity files after maintenance
- **Scope bonuses** ensure organization-scoped directories score higher for files belonging to that organization (+0.24 bonus), and entity-scoped directories score even higher (+0.40 bonus)

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

### Discover Unindexed Repositories

```bash
synthesis discover
```

Finds git repositories in your search paths that are not yet indexed by Synthesis. Useful for making sure your cross-repo dependency map is complete.

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

### Activity Log

```bash
synthesis maintain --update-activity-log
```

Auto-appends a dated entry to `ACTIVITY-LOG.md` at your workspace root listing Added/Modified/Removed files with an optional AI narrative. Idempotent -- skips if today already has an entry. Useful for daily standups or weekly reports.

### Temporal Summaries

```bash
synthesis summary --since 7d                    # What happened this week
synthesis summary --since 24h                   # What happened today
synthesis summary --level manager --since 2w    # Manager briefing, last 2 weeks
```

Uses changelog data from `maintain` snapshots to inject temporal context into the AI prompt -- the summary reflects actual recent changes, not just a static index snapshot.

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
synthesis enrich                           # Generate companions for all binary files
synthesis enrich --type video              # Only for videos
synthesis enrich --level ai                # Use AI for rich descriptions (requires API key)
synthesis enrich --dry-run                 # Preview what would be generated
synthesis enrich --path "docs/**"          # Target a specific subtree
synthesis enrich --exclude "archive/**"    # Exclude paths
synthesis scan                             # Re-scan to index the new companion files
```

Companion files are markdown files (e.g., `demo.mp4.synthesis.md`) that contain metadata, descriptions, and related file references. These companions also enable content-based routing in `sweep` and `staging route`.

---

## Organization Management

```bash
synthesis org scan                  # Auto-discover organizations from workspace content
synthesis org list                  # Show companies, clients, products
```

Organization data powers scope bonuses in the Directory Identity System and enables content-based classification in `staging route`.

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

### Workspace health issues

```bash
synthesis health                  # Diagnose problems
synthesis health --fix-config     # Auto-fix config issues
```

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
synthesis relate <file>                 # Show static dependencies
synthesis relate <file> --mermaid       # Mermaid diagram
synthesis impact <file>                 # Co-change analysis (dynamic coupling)
synthesis graph --modules               # Architecture overview
synthesis insights                      # Codebase health
synthesis architecture                  # Anti-pattern detection
synthesis diff HEAD~5                   # Recent changes
synthesis changed --since 2026-01-01    # Files changed since date
synthesis watch                         # Auto-update on changes
synthesis maintain                      # Manual incremental update
synthesis maintain --update-activity-log # Append today's changes to ACTIVITY-LOG.md
synthesis maintain --sync               # Update directory identity files
synthesis maintain --rebalance          # Recover misplaced archive files
synthesis cross-repo-deps               # Cross-repo dependencies
synthesis discover                      # Find unindexed git repos
synthesis which <file>                  # Find which workspace has a file
synthesis status                        # Workspace health
synthesis health                        # Workspace health diagnostics
synthesis health --fix-config           # Auto-fix config issues
synthesis naming                        # Naming consistency audit
synthesis prune                         # Remove empty directories
synthesis sweep --dry-run               # Preview stale file routing
synthesis sweep --yes                   # Execute stale file routing + archival
synthesis consolidate "entity"          # Merge fragmented directories
synthesis scatter --all                 # Find fragmented entities
synthesis ttl set "*.tmp" --days 1      # Set file expiry rules
synthesis ttl check --archive           # Move expired files to archive
synthesis sync                          # Write directory identity files
synthesis archive audit                 # Audit archive for savings
synthesis enrich                        # Make binary files searchable
synthesis enrich --path "docs/**"       # Target enrichment to subtree
synthesis summary --since 7d            # Temporal AI summary
synthesis org scan                      # Discover organizations
synthesis credentials status            # Check API key
```

---

**Synthesis v1.11.1 -- ~2,500 tests passing -- February 2026**

**Related guides:**
- [Architecture Guide](./ARCHITECT.md) -- deep dependency analysis
- [Engineering Manager Guide](./ENGINEERING-MANAGER.md) -- team adoption and metrics
- [AI Agent Guide](./AI-AGENT.md) -- MCP integration for tool builders
- [Full User Guide](../USER-GUIDE-V2.md) -- complete command reference
