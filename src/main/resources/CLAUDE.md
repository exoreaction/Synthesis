# Claude Code Project Context: Synthesis

Synthesis is an open-source (MIT) Java 21+ CLI tool and MCP server for knowledge infrastructure. It indexes everything a team creates -- code, docs, videos, PDFs -- and makes it instantly searchable with relationship tracking and AI-powered analysis.

**Repository:** https://github.com/exoreaction/Synthesis
**License:** MIT
**Status:** Production-ready (v1.11.1, Feb 2026)

---

## What It Solves

AI tools made developers 10x faster at creating code -- but comprehension speed stayed at 1x. 40-60% of time is spent searching for context, wasting the AI investment. Synthesis bridges that gap:

- Indexes 200-300 files/second across all formats
- Sub-second search (0.4s validated)
- Bi-directional relationship tracking ("what breaks if I change this?")
- Cross-repo dependency graphs (58 repos, 429 dependencies in <31 seconds)
- Directory identity system -- per-directory `.synthesis.md` files declare what each directory accepts
- Local-only processing -- zero cloud, privacy-first

**Validated:** 36,342 files indexed, 3,086 tests passing, 92-95% reduction in retrieval time.

---

## Technology Stack

- **Language:** Java 21+
- **Build:** Maven
- **CLI Framework:** picocli
- **Search:** Lucene (full-text index)
- **Database:** SQLite (via JDBC) -- 14 tables, managed by Flyway (V1-V6, V8, V9; V7 intentionally reserved)
- **Schema Migrations:** Flyway
- **Tests:** JUnit 5
- **Package root:** `io.exoreaction.synthesis.*` (30 packages)
- **Fat JARs:** 3 -- synthesis.jar (CLI), synthesis-mcp-server.jar, synthesis-lsp-server.jar

---

## Key Commands

```bash
# Build (skip tests for speed)
mvn clean package -DskipTests

# Run all tests
mvn test

# Run with local JAR
java -jar target/synthesis-*.jar <command>

# Install globally (symlink in ~/bin)
# synthesis is already on PATH at /home/totto/bin/synthesis
```

### Environment Setup (Critical for Agents/Subprocesses)

**Always export PATH before using synthesis in Bash or spawned agents:**

```bash
export PATH="$HOME/bin:/home/totto/bin:$PATH"
synthesis search -d /src/exoreaction "query" 2>/dev/null
```

Without this, subagents may report "synthesis CLI not available" even though it is installed.
Verify with: `which synthesis && synthesis --version`

### Workspace Routing (Always Use `-d` Flag)

| Task type | Correct command |
|---|---|
| Search Synthesis/eXOReaction source | `synthesis search -d /src/exoreaction "query"` |
| Search Cantara source | `synthesis search -d /src/cantara "query"` |
| Search Quadim source | `synthesis search -d /src/quadim "query"` |
| Search business docs / pipeline / clients | `synthesis search "query"` (defaults to ~/Documents) |
| Search everything | `synthesis search --all "query"` |

**Two different paths -- do not confuse:**
- **Synthesis workspace root:** `/src/exoreaction` (has `.synthesis/` index, is the `-d` target)
- **Project source tree:** `/home/totto/src/exoreaction/Synthesis/` (for file reads/edits)
- `/home/totto/src/exoreaction/` has NO `.synthesis/` -- using it as `-d` returns exit code 1

### Core CLI Commands

```bash
# Workspace lifecycle
synthesis init                          # Initialize workspace
synthesis scan                          # Index files (200-300/sec)
synthesis maintain                      # Housekeeping + change tracking (also runs staging ingest+route)
synthesis maintain --update-activity-log # Auto-append to ACTIVITY-LOG.md
synthesis maintain --sync               # Run directory identity sync after maintenance
synthesis maintain --rebalance          # Move archive files scoring >= 0.5 back to active dirs
synthesis status                        # Index health + metrics
synthesis health                        # Workspace health audit (score 0-100)
synthesis health --fix-config           # Auto-fix phantom sub-workspace paths

# Search & discovery
synthesis search -d /src/exoreaction "keyword"   # Search source code
synthesis search "keyword"              # Search business docs (~/Documents)
synthesis search --all "keyword"        # Search across all workspaces
synthesis relate "filename"             # What breaks if you change this?
synthesis impact "filename"             # Co-change file impact analysis
synthesis which "filename"              # Find which workspace(s) contain a file
synthesis discover                      # Find unindexed git repos in configured search paths

# AI-powered analysis
synthesis ask "question"                # AI Q&A grounded in indexed files
synthesis explain --file "name"         # Natural language code explanations
synthesis perspectives "question"       # Multi-perspective analysis
synthesis summary                       # AI executive summaries (8 perspectives)
synthesis summary --since 7d            # Temporal context from changelog data (24h, 2w, 3m, ISO dates)
synthesis research "topic"              # Generate prompts for external AI tools

# Graphs & architecture
synthesis graph --modules               # Architecture graph (Mermaid)
synthesis cross-repo-deps               # Cross-repository dependency analysis
synthesis architecture                  # Architecture health monitoring

# Change tracking
synthesis track                         # Track file movements
synthesis changelog --since 7d          # Cross-workspace change report
synthesis changed --since 7d            # Files changed since date
synthesis diff HEAD~1                   # Git diff integration
synthesis watch                         # File watcher daemon (real-time monitoring)

# Workspace hygiene (self-organizing workspace)
synthesis sync                          # Directory identity sync (discover dirs, write .synthesis.md)
synthesis sweep --dry-run               # Identify stale root-level files; route via directory identity
synthesis sweep --yes                   # Archive stale files (--archive-only skips routing)
synthesis prune --yes                   # Remove empty directories
synthesis consolidate "Entity"          # Consolidate scattered files into canonical locations
synthesis scatter --all                 # Find fragmented entity directories
synthesis naming                        # File naming consistency analysis
synthesis ttl set "*.tmp" --days 7      # Time-to-live management for files
synthesis archive audit                 # Archive space/duplicate audit

# Organization & enrichment
synthesis org scan                      # Auto-discover organizational structure
synthesis org list                      # Show companies, clients, products
synthesis enrich                        # AI enrichment for media/docs
synthesis enrich --path "*.pdf"         # Targeted enrichment (--exclude supported)

# Staging pipeline
synthesis staging ingest                # Ingest files from staging area
synthesis staging route                 # Route staged files using rules
synthesis staging route --enrich-first  # Generate companions before classification

# Export & reporting
synthesis export                        # Export index as JSON, Markdown, or AI docs
synthesis report                        # Generate verified reports
synthesis extract-slides "file.pdf"     # Extract slides from presentation PDFs

# Executive shell wrapper (bin/exo)
exo ask "question"                      # Conversational RAG: search -> sources -> streamed answer -> follow-up

# Release & distribution
synthesis release                       # Release management
synthesis update                        # Update all components
synthesis learn                         # Auto-generate skills from codebase
synthesis export-skills --overwrite     # Install bundled skills to ~/.claude/skills/
synthesis list --type source            # Navigate multiple workspaces
synthesis telemetry                     # View pilot status and telemetry info
synthesis validate                      # Validate workspace configuration
synthesis credentials                   # Manage credentials
```

---

## Directory Identity System (v1.11.1)

Per-directory `.synthesis.md` files declare what each directory accepts. This enables intelligent file routing without centralized rules.

- `synthesis sync` discovers directories and writes/updates identity files
- `SweepCommand` uses `DirectoryIdentityRouter` to route files to matching directories before falling back to archive
- `MaintainCommand --rebalance` periodically moves files from archive back to active directories when they score >= 0.5

**`.synthesis.md` format:** YAML front matter:
```yaml
synthesis:
  accepts:
    types: [report, analysis, presentation]
    formats: [pdf, md, docx]
    patterns: ["*-report-*", "*-analysis-*"]
  scope:
    level: organization
    organization: eXOReaction
    entity: Synthesis
  confidence: 0.85
```

Parsed by `DirectoryIdentityParser`. Written by `SyncCommand`.

---

## Project Structure

```
src/main/java/io/exoreaction/synthesis/
+-- SynthesisApp.java              # Main entry point (picocli root, 51 subcommands)
+-- cli/                           # All CLI subcommands
+-- config/                        # Configuration management
+-- core/                          # Core utilities
+-- index/                         # File indexing pipeline
+-- search/                        # Lucene search engine
+-- graph/                         # Dependency graph engine
+-- mcp/                           # MCP server implementation
+-- lsp/                           # LSP server implementation
+-- enrichment/                    # AI enrichment (media, docs)
+-- staging/                       # Team staging areas
+-- tracking/                      # Change tracking, file movements
+-- workspace/                     # Directory identity system
+-- validate/                      # Workspace validation
+-- org/                           # Organization registry
+-- ai/                            # AI service integration
+-- report/                        # Report engine
+-- summary/                       # Summary generation
+-- changelog/                     # Changelog engine
+-- db/                            # Database access layer
+-- telemetry/                     # Telemetry and pilot approval
+-- metrics/                       # Metrics collection
+-- update/                        # Self-update mechanism
+-- util/                          # Shared utilities
```

---

## Skills Navigation

**Skills directory:** `.claude/skills/` (32 skills)

### Using Synthesis as a Tool (also available globally)

| Skill | Command | Purpose |
|-------|---------|---------|
| `synthesis-search-workspace` | `synthesis search` | Full-text search across indexed files |
| `synthesis-ask-workspace` | `synthesis ask` | AI Q&A grounded in actual content |
| `exo-ask` | `exo ask` | Conversational RAG with sources + follow-up loop (executive-facing) |
| `synthesis-explain-code` | `synthesis explain` | Natural language code explanations |
| `synthesis-scoped-search` | `synthesis search --scope` | Targeted search within a subset |
| `synthesis-relate-dependencies` | `synthesis relate` | What breaks if you change X? |
| `synthesis-graph-architecture` | `synthesis graph` | Mermaid dependency graphs |
| `synthesis-insights-metrics` | `synthesis insights` | Index health and metrics |
| `synthesis-summary` | `synthesis summary` | AI executive summaries (8 perspectives, `--since` temporal context) |
| `synthesis-perspectives-analysis` | `synthesis perspectives` | Multi-perspective analysis |
| `synthesis-export-docs` | `synthesis export` | Documentation export |
| `synthesis-enrich-media` | `synthesis enrich` | Local AI media enrichment |
| `synthesis-learn-skills` | `synthesis learn` | Auto-generate skills from codebase |
| `synthesis-report-verification` | `synthesis report` | Verify and generate reports |
| `synthesis-changelog-reports` | `synthesis changelog` | Cross-workspace change reports |
| `synthesis-subworkspace-navigation` | `synthesis list` | Navigate multiple workspaces |

### Benchmark & Knowledge Integrity

| Skill | Purpose |
|-------|---------|
| `synthesis-benchmark` | Full benchmark history, conditions, results, design flaws |
| `synthesis-knowledge-integrity` | Three failure modes (stale/silent/ambiguous), trust calibration, roadmap |
| `synthesis-task-routing` | Task-shape taxonomy: when each approach wins (routing tier) |
| `synthesis-agent-patterns` | 8 patterns for agents using synthesis effectively |

### Product Context

| Skill | Purpose |
|-------|---------|
| `synthesis-product-context` | Comprehensive product knowledge, metrics, business context |

### Development & Operations (project-specific)

| Skill | Purpose |
|-------|---------|
| `synthesis-development` | Architecture, patterns, how to add features |
| `synthesis-release-manager` | Release workflow, version bumps, changelog |
| `synthesis-database-migrations` | Flyway migration patterns, SQLite JDBC gotchas |
| `synthesis-interactive-cli` | CLI interaction patterns, picocli specifics |
| `synthesis-metrics-tracking` | How metrics are collected and reported |
| `synthesis-pilot-dist` | Pilot distribution and user onboarding |
| `synthesis-staging-management` | Team staging area management |
| `synthesis-workspace-cleanup` | Workspace hygiene: sweep, prune, health, ttl, scatter/consolidate, naming, archive-audit |
| `synthesis-workspace-management` | Workspace lifecycle (init, scan, maintain) |
| `synthesis-track-movements` | File movement tracking implementation |
| `synthesis-architecture-monitoring` | Architecture health monitoring |

**Start here for new work:** `synthesis-development` -- covers architecture decisions, patterns, and how to navigate the codebase.

---

## Known Gotchas

- **SQLite JDBC:** `getObject(col, Integer.class)` fails for NULL columns -- use non-null values in tests or `getLong()`/`getInt()` with null checks
- **JUnit 5:** `assertDoesNotThrow(() -> new Foo())` is ambiguous -- cast to `(Executable)` explicitly
- **Flyway:** migration files must follow `V{n}__description.sql` naming exactly; V7 is intentionally absent/reserved (migration was deleted, version permanently reserved)
- **Never use SNAPSHOT versions** in release artifacts -- always bump to a release before tagging
- **SearchResult constructor field order:** `(path, relativePath, score, fileName, fileType, language, summary, headings, structure, sizeBytes)` -- `structure` is position 9 (not 7). Tests that detect method counts must put the structure string in position 9, not 7 (summary).
- **`synthesis research`** uses claude-haiku and generates prompts for external tools (ChatGPT, NotebookLM) -- it does NOT produce standalone analysis reports. Use `synthesis perspectives` for real deep-dives grounded in indexed source files.
- **`synthesis export-skills`** uses `--overwrite` (not `--install`) to update `~/.claude/skills/`. The `--install` flag belongs to `synthesis learn`.
- **`synthesis learn`** requires `synthesis org scan` first or errors with "No organizations found".
- **Staging `_processed` suffix:** `routeTo()` copies file to destination and renames source to `*_processed.*` (not delete). The cron should run `staging ingest && staging route && maintain` -- `maintain` alone does NOT trigger staging ingest/route. Use `retentionDays: -1` in tests to force expiry (0 sets `expiresAt = now`, which is not strictly less than `now`).
- **`staging route` content-intelligence fallback (issue #71):** When `autoClassify: true` (default), unmatched files are classified via `DownloadsClassifier.classifyWithCompanion()` -- reads the companion `.synthesis.md` if present. Files above `classificationThreshold` (default 0.5) are auto-routed (`~` prefix in output); below-threshold matches become suggestions. Use `staging route --enrich-first` to generate companions on-the-fly for unmatched IMAGE/PDF files before the classification pass.
- **`synthesis explain --file <name>` bare filename resolution:** Accepts absolute, workspace-relative, or bare filename. Falls back to `index.search()` if not on disk -- exact filename match preferred over score. On failure suggests `synthesis search <name>`.
- **`synthesis summary --since` temporal context:** Parses `7d`/`24h`/`2w`/`3m`/`2026-01-15`, loads `ChangeEvent`s from `SnapshotManager.getChangesForWorkspace()`, injects compact change summary into the AI prompt (not just the output). Requires `synthesis maintain` to have run at least once to populate snapshots.
- **Directory identity `.synthesis.md` format:** YAML front matter with `synthesis.accepts.types/formats/patterns`, `synthesis.scope`, `synthesis.confidence`. Parsed by `DirectoryIdentityParser`. Written by `SyncCommand`.

---

## Related Resources

- **Business context:** `synthesis-product-context` skill (auto-loaded globally)
- **LinkedIn campaign:** `synthesis-linkedin-campaign` skill (global)
- **Proof points:** `~/Documents/eXOReaction/PROOF-POINTS.md`
- **Attention strategy:** `~/Documents/eXOReaction/marketing/SYNTHESIS-ATTENTION-STRATEGY.md`
- **Docs:** `docs/perspectives/` -- 9 role-specific guides (~64,000 words)
