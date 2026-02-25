# Synthesis User Guide v1.8.0

**AI-powered knowledge infrastructure for developers, teams, and executives.**

---

## What Is Synthesis?

Synthesis indexes your files -- code, documentation, videos, PDFs, images -- and makes them searchable in under a second. It tracks relationships between files, generates dependency graphs, and provides AI-powered analysis when configured.

**The problem it solves:** AI tools made developers 10x faster at creating code. But the time spent *finding* context -- searching for the right file, tracing dependencies, remembering which repository holds what -- stayed the same. On a typical team, 40-60% of developer time goes to retrieval, not creation. Synthesis closes that gap.

**Key numbers (validated February 2026):**

| Metric | Value |
|--------|-------|
| Indexing speed | 200-300 files/second |
| Search speed | Sub-second (<1 sec, typically 0.4s) |
| Cross-repo dependencies | 58 repos, 429 deps in <31 seconds |
| Storage overhead | 2-3% of indexed content |
| Index size (real) | 11.6 MB for 434 MB of content (8,934 files) |

---

## Choose Your Perspective

Different roles need different things from Synthesis. Start with the guide written for you.

| Role | Guide | Reading time | Primary commands |
|------|-------|-------------|-----------------|
| **Executive / CEO** | [EXECUTIVE.md](perspectives/EXECUTIVE.md) | 5 min | `exo`, `exo report`, `exo decisions` |
| **Developer** | [DEVELOPER.md](perspectives/DEVELOPER.md) | 10 min | `search`, `ask`, `explain`, `relate`, `watch` |
| **Engineering Manager** | [ENGINEERING-MANAGER.md](perspectives/ENGINEERING-MANAGER.md) | 10 min | `insights`, `metrics`, `cross-repo-deps`, `changelog` |
| **Architect** | [ARCHITECT.md](perspectives/ARCHITECT.md) | 12 min | `architecture`, `graph`, `cross-repo-deps`, `research` |
| **DevOps / Platform Eng** | [DEVOPS.md](perspectives/DEVOPS.md) | 10 min | `maintain`, `watch`, `staging`, `credentials` |
| **Product Manager** | [PRODUCT-MANAGER.md](perspectives/PRODUCT-MANAGER.md) | 8 min | `report`, `upcoming`, `org`, `enrich`, `export` |
| **Workshop Facilitator** | [WORKSHOP-FACILITATOR.md](perspectives/WORKSHOP-FACILITATOR.md) | 15 min | `search`, `relate`, `graph`, `research` |
| **AI Agent / MCP Client** | [AI-AGENT.md](perspectives/AI-AGENT.md) | 15 min | MCP tools, `search --all`, `ask` |

---

## Quick Start: 5 Commands to Value

```bash
# 1. Install (one-time)
curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash

# 2. Store your API key (one-time, optional -- enables AI features)
synthesis credentials set ANTHROPIC_API_KEY sk-ant-...

# 3. Initialize a workspace
cd ~/your-project
synthesis init

# 4. Index everything
synthesis scan

# 5. Search
synthesis search "authentication"
```

Time to first search result: approximately 2-5 minutes, depending on project size.

---

## Complete Command Reference

### Core Commands

| Command | Purpose | AI Required |
|---------|---------|-------------|
| `init` | Initialize a workspace (creates `.synthesis/` directory) | No |
| `scan` | Index files and build the search database | No |
| `search <query>` | Full-text search across all indexed files | No |
| `maintain` | Detect changes and update the index incrementally | No |
| `status` | Show workspace health, index stats, last scan time | No |
| `watch` | Monitor file changes and auto-update the index | No |

### AI-Powered Search & Explanation

| Command | Purpose | AI Required |
|---------|---------|-------------|
| `ask <question>` | Ask a natural-language question about your workspace | Yes |
| `explain <file>` | AI explanation of a file, module, or pattern | Yes |
| `perspectives <question>` | Analyze a question from multiple viewpoints | Yes |

### Analysis & Architecture

| Command | Purpose | AI Required |
|---------|---------|-------------|
| `analyze` | Smart project analysis (structure, patterns, issues) | No |
| `relate <file>` | Show bi-directional file relationships | No |
| `insights` | Deep codebase analysis with actionable metrics | No |
| `graph <file>` | Generate visual dependency graphs (Mermaid, PNG, SVG) | No |
| `cross-repo-deps` | Map dependencies across repositories | No |
| `architecture` | Detect anti-patterns, coupling issues, quality gaps | No |
| `metrics` | View performance statistics | No |

### Reports & Research

| Command | Purpose | AI Required |
|---------|---------|-------------|
| `report` | Executive business reports (pipeline, decisions, client health) | Yes |
| `research` | Deep multi-pass codebase analysis (architecture, security, quality) | Yes |
| `summary` | Generate summaries at different abstraction levels | Yes |

### Content & Enrichment

| Command | Purpose | AI Required |
|---------|---------|-------------|
| `enrich` | Generate companion files for binary assets (images, videos, PDFs) | Optional |
| `export` | Export index as JSON or Markdown | No |
| `export-skills` | Export bundled Claude Code skills to `~/.claude/skills/` | No |
| `extract-slides <pdf>` | Extract slides from presentation PDFs as PNG images | No |

### Organization & Structure

| Command | Purpose | AI Required |
|---------|---------|-------------|
| `org scan` | Auto-discover organizational structure (companies, clients, products) | No |
| `org list` | Show discovered companies, clients, products | No |
| `org classify` | Classify files by organization | No |
| `staging` | Manage staging sub-workspace files (ingest, promote, expire) | No |
| `migrate-repos` | Migrate repositories and orgs to sub-workspaces | No |

### Change Tracking

| Command | Purpose | AI Required |
|---------|---------|-------------|
| `track` | Query and manage file movement tracking | No |
| `changelog` | Cross-workspace change reporting and snapshots | No |
| `changed --since <date>` | Show files changed since a date (Git history) | No |
| `diff <ref>` | Show changed files between Git refs | No |

### Cross-Workspace

| Command | Purpose | AI Required |
|---------|---------|-------------|
| `search --all <query>` | Search across all registered workspaces | No |
| `which <file>` | Find which workspace(s) contain a file or pattern | No |
| `list` | List all Synthesis workspaces | No |

### System & Configuration

| Command | Purpose | AI Required |
|---------|---------|-------------|
| `credentials` | Manage locally stored API credentials (set, status, clear) | No |
| `dashboard` | Interactive workspace navigator | No |
| `learn` | Generate Claude Code skills from workspace knowledge | No |
| `telemetry` | View pilot status and telemetry information | No |
| `update` | Update Synthesis to the latest version | No |
| `upcoming` | Show planned events, actions, and deadlines from UPCOMING.md | No |

---

## The `exo` Command

The `exo` command is an executive wrapper script that simplifies access to Synthesis business reports. It uses the `SYNTHESIS_WORKSPACE` environment variable (defaults to `~/Documents`).

| Command | What it does |
|---------|-------------|
| `exo` | Opens the interactive dashboard |
| `exo report` | Generates a weekly CEO briefing |
| `exo decisions` | Shows critical decisions that need attention |
| `exo pipeline` | Pipeline status summary |
| `exo activities` | Recent activities summary |
| `exo client <name>` | Client relationship health report |
| `exo product <name>` | Product status report |
| `exo <name>` | Smart mode: tries client first, then product |

**Setup:** The `exo` script lives in `~/bin/exo`. Ensure `~/bin` is in your `PATH`.

---

## Configuration Reference

### Workspace Configuration

**Location:** `.synthesis/config.yaml` (in each workspace root)

```yaml
workspace:
  name: "my-project"
  type: "general"          # general, monorepo, documentation

scan:
  includePatterns:
    - "**/*.java"
    - "**/*.py"
    - "**/*.js"
    - "**/*.ts"
    - "**/*.md"
    - "**/*.yaml"
    - "**/*.json"
    - "**/*.pdf"
    - "**/*.png"
    - "**/*.mp4"
  excludePatterns:
    - "**/node_modules/**"
    - "**/.git/**"
    - "**/target/**"
    - "**/build/**"
  maxFileSizeBytes: 10485760   # 10 MB

search:
  maxResults: 20
  previewLength: 200

ai:
  enabled: true
  model: "claude-sonnet-4-5-20250929"
  maxTokens: 1024
```

### Credentials

Synthesis stores API keys locally with obfuscation. No environment variable needed after initial setup.

```bash
synthesis credentials set ANTHROPIC_API_KEY sk-ant-...   # Store key
synthesis credentials status                              # Check stored credentials
synthesis credentials clear ANTHROPIC_API_KEY             # Remove key
```

**Resolution order:** Environment variable > Credential store (`~/.synthesis/credentials`) > Disabled.

### Environment Variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `SYNTHESIS_WORKSPACE` | Default workspace root directory | Current directory |
| `SYNTHESIS_EDITION` | Edition: `core`, `pro`, `enterprise`, `ultimate` | `pro` |
| `ANTHROPIC_API_KEY` | API key for AI features (overrides credential store) | Not set |
| `NO_COLOR` | Disable terminal color output | Not set |

### Workspace Resolution Order

When Synthesis needs to determine the workspace root:

1. Explicit `-d` / `--directory` flag
2. `SYNTHESIS_WORKSPACE` environment variable
3. `~/.synthesis/workspace` file
4. Current directory (fallback)

---

## Editions

| Edition | AI Features | Telemetry | Cloud | Daemon |
|---------|------------|-----------|-------|--------|
| **Core** | No | No | No (air-gapped) | No |
| **Pro** | Yes | Yes | Yes | No |
| **Enterprise** | No | No | No (air-gapped) | Yes |
| **Ultimate** | Yes | Yes | Yes | Yes |

Set via `SYNTHESIS_EDITION` environment variable. Default is `pro`.

In air-gapped editions (`core`, `enterprise`), the `ask` and `perspectives` commands are disabled, telemetry is off, and update checks are skipped.

---

## Installation

**Requirements:** Java 21+ (tested with Java 24)

```bash
# Option 1: Install script (recommended)
curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash

# Option 2: Direct download
curl -L https://github.com/exoreaction/Synthesis/releases/latest/download/synthesis.jar \
  -o ~/bin/synthesis.jar

# Verify
synthesis --version
# Synthesis 1.8.0-SNAPSHOT
```

**Storage:** The JAR is approximately 136 MB. Index data is stored in `.synthesis/` within each workspace. Shared data (credentials, ffprobe binary) is stored in `~/.synthesis/`.

---

## Getting Help

```bash
synthesis --help              # List all commands
synthesis <command> --help    # Help for a specific command
```

**Resources:**
- GitHub: https://github.com/exoreaction/Synthesis
- Issues: https://github.com/exoreaction/Synthesis/issues

---

## Perspective Guides

Each guide is tailored to a specific role. They assume you have Synthesis installed and a workspace initialized.

- [Executive Guide](perspectives/EXECUTIVE.md) -- For CEOs and business leaders using `exo`
- [Developer Guide](perspectives/DEVELOPER.md) -- Daily development workflows
- [Engineering Manager Guide](perspectives/ENGINEERING-MANAGER.md) -- Team productivity and codebase health
- [Architect Guide](perspectives/ARCHITECT.md) -- Dependency analysis and architectural intelligence
- [DevOps Guide](perspectives/DEVOPS.md) -- CI/CD integration, automation, MCP/LSP servers
- [Product Manager Guide](perspectives/PRODUCT-MANAGER.md) -- Product status, client insights, content management
- [Workshop Facilitator Guide](perspectives/WORKSHOP-FACILITATOR.md) -- Running Synthesis workshops and demos
- [AI Agent Guide](perspectives/AI-AGENT.md) -- MCP integration, programmatic access, tool capabilities
