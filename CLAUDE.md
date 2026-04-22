# Claude Code Project Context: Synthesis

Synthesis is an open-source (MIT) Java 21+ CLI tool and MCP server for knowledge infrastructure. It indexes everything a team creates -- code, docs, videos, PDFs -- and makes it instantly searchable with relationship tracking and AI-powered analysis.

**Repository:** https://github.com/exoreaction/Synthesis
**License:** MIT
**Status:** Production-ready (v1.28.0, April 2026)

---

## Contribution Policy (for Claude)

This is an **upstream open-source repository** owned by eXOReaction — we are contributors, not maintainers.

- **Never commit or push to `main`.** All work goes on a feature branch → PR for maintainer review.
- **Never push to `origin/main`** or any protected branch. Assume push permissions are revoked.
- `git checkout main` is fine for reading; do not modify files while on `main`.
- New work: `git checkout -b <feat|fix|docs>/<topic> origin/main`, commit there, push the branch, open a PR.
- Do not delete or force-push remote branches unless explicitly asked.

If a task seems to require a `main` change, stop and surface it — the maintainer decides.

---

## What It Solves

AI tools made developers 10x faster at creating code -- but comprehension speed stayed at 1x. 40-60% of time is spent searching for context, wasting the AI investment. Synthesis bridges that gap:

- Indexes 200-300 files/second across all formats
- Sub-second search (0.4s validated)
- Bi-directional relationship tracking ("what breaks if I change this?")
- Cross-repo dependency graphs (58 repos, 429 dependencies in <31 seconds)
- Directory identity system -- per-directory `.synthesis.md` files declare what each directory accepts
- Local-only processing -- zero cloud, privacy-first

**Validated:** 36,342 files indexed, 4,153 tests passing, 92-95% reduction in retrieval time. Includes document knowledge graph (Phases 1-4), DirectoryClassifier, Code Knowledge Graph (CKG-1 through CKG-5, all complete), and KCP v0.5 support (Phases 2-5, PRs #284-#287).

---

## Technology Stack

- **Language:** Java 21+
- **Build:** Maven
- **CLI Framework:** picocli
- **Search:** Lucene (full-text index)
- **Database:** SQLite (via JDBC) -- 20+ tables, managed by Flyway (V1-V6, V8-V20; V7 intentionally reserved). V10-V13: knowledge graph; V14: repo isolation; V15: security analysis; V16: report history; V17: KCP tables; V18: Claude sessions + FTS5; V19: subagent session linking; V20: git metrics (git_file_metrics, git_cochange).
- **Schema Migrations:** Flyway
- **Tests:** JUnit 5
- **Package root:** `io.exoreaction.synthesis.*` (31 packages, new `kcp` package)
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

# Install globally (CRITICAL: copy to the correct location!)
# The synthesis launcher uses ~/.synthesis/lib/current.jar (symlink)
# ~/bin/synthesis.jar is NOT used by the launcher — it's ignored
cp target/synthesis-*.jar ~/.synthesis/lib/synthesis-<version>.jar
ln -sf ~/.synthesis/lib/synthesis-<version>.jar ~/.synthesis/lib/current.jar
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

**Lesson (benchmark Feb 19, 2026):** Omitting `-d` causes synthesis to search the docs workspace
and return irrelevant results -- even when source code IS indexed. See issue #85.

**Two different paths -- do not confuse:**
- **Synthesis workspace root:** `/src/exoreaction` (has `.synthesis/` index, is the `-d` target)
- **Project source tree:** `/home/totto/src/exoreaction/Synthesis/` (for file reads/edits)
- `/home/totto/src/exoreaction/` has NO `.synthesis/` -- using it as `-d` returns exit code 1

### Core CLI Commands

```bash
# Workspace lifecycle
synthesis init                          # Initialize workspace
synthesis scan                          # Index files (200-300/sec)
synthesis maintain                      # 9-phase housekeeping pipeline (Ingest→Route→Sync→Sweep→Rebalance→Expire→Index→Track→Prune)
synthesis maintain --dry-run            # Preview all phases without making changes
synthesis maintain --quiet              # One summary line only (for cron)
synthesis maintain --json               # Machine-readable JSON output (for monitoring)
synthesis maintain --skip-downloads     # Skip Ingest and Route phases (phases 1-2)
synthesis maintain --skip-git           # Skip git fetch for client codebases
synthesis maintain --update-activity-log # Auto-append to ACTIVITY-LOG.md
synthesis maintain --sync               # Run directory identity sync after maintenance
synthesis maintain --rebalance          # Move archive files scoring >= 0.7 back to active dirs
synthesis status                        # Index health + metrics
synthesis health                        # Workspace health audit (score 0-100)
synthesis health --fix-config           # Auto-fix E001 (phantom paths) and E002 (build artifacts) interactively

# Search & discovery
synthesis search -d /src/exoreaction "keyword"   # Search source code
synthesis search "keyword"              # Search business docs (~/Documents)
synthesis search --all "keyword"        # Search across all workspaces
synthesis relate "filename"             # What breaks if you change this?
synthesis impact "filename"             # Co-change file impact analysis (+ git co-change partners)
synthesis hotspots                      # Files ranked by temporal hotspot score (git churn, decay half-life 180d)
synthesis hotspots --refresh            # Recompute from git history before display
synthesis hotspots --path src/          # Filter to path prefix
synthesis archaeology                   # Surface architectural decisions from git commit messages
synthesis archaeology --since 180       # Last N days of history
synthesis archaeology --min-confidence 0.80  # Only migration/inline signals (skip fix-signals)
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

# Session lifecycle (Claude Code integration)
synthesis session-context                  # Codebase freshness snapshot for session injection
synthesis session-context --compact        # Single-line output for hook injection
synthesis session-context --since 7d       # Look back 7 days for changes
synthesis session-context --no-security    # Skip security posture line
synthesis hooks generate                   # Generate Claude Code hook config (~/.claude/settings.json)
synthesis hooks generate --dry-run         # Print merged JSON without writing
synthesis hooks generate --type PreToolUse # Use PreToolUse hook type
synthesis claude-md refresh                # Update Synthesis Stats section in CLAUDE.md
synthesis claude-md refresh --dry-run      # Print result without modifying
synthesis claude-md refresh -f /path/CLAUDE.md  # Specific file

# KCP (Knowledge Context Protocol) v0.5
synthesis export --format kcp                        # Generate KCP v0.5 manifest from index
synthesis export --format knowledge-context-protocol # Alias
synthesis export --format kcp -o knowledge.yaml      # Write to file

# Knowledge graph (document workspaces)
synthesis route-explain "filename"          # Explain routing decision for a file
synthesis describe                          # Show knowledge profiles for all directories
synthesis describe --path "docs/features"   # Show profile for specific directory
synthesis feedback accept "filename" "path" # Accept a routing suggestion
synthesis feedback reject "filename" "path" # Reject a routing suggestion
synthesis knowledge-graph                   # Full knowledge graph view (alias: kg)
synthesis structure                         # Structural analysis of workspace
synthesis evolution                         # Long-term evolution report (alias: evo)

# Code graph (source code workspaces) — CKG-1 through CKG-5 complete
synthesis code-graph extract                # Build/rebuild persistent code dependency graph (alias: cg)
synthesis code-graph extract --dry-run      # Preview: count Java files, no changes
synthesis code-graph extract --incremental  # Incremental update (changed files only, git-aware)
synthesis code-graph extract --stats        # Show current graph statistics
synthesis code-graph describe               # Show module profiles: fan-in, fan-out, instability (CKG-2)
synthesis code-graph describe --module X    # Filter to a specific module path
synthesis code-graph describe --instability # Sort by instability descending
synthesis code-graph describe --refresh     # Force recompute profiles from raw deps
synthesis code-graph health                 # Show health signals C001-C021 (CKG-2)
synthesis code-graph health --errors-only   # HIGH severity signals only
synthesis code-graph gaps                   # Show quality gaps: missing tests, interfaces, docs (CKG-3)
synthesis code-graph gaps --severity HIGH   # Filter by severity (HIGH/MEDIUM/LOW)
synthesis code-graph gaps --type MISSING_TESTS  # Filter by gap type
synthesis code-graph gaps --score           # Show completeness scores per module (0.0-1.0)
synthesis code-graph                        # Package DAG grouped by architectural layer (CKG-4, default)
synthesis code-graph --cycles               # Show circular dependency pairs (A ↔ B)
synthesis code-graph --hotspots             # Unstable high-coupling packages (instability >0.7, fan-in >2)
synthesis code-graph --instability          # All packages sorted by instability descending
synthesis code-graph --layers               # Full layer diagram with SDP violations
synthesis code-graph --cross-format         # SQL/YAML → Java cross-format links
synthesis code-graph --format mermaid       # Mermaid graph TD output (max 30 packages)
synthesis code-graph security               # Security analysis: 21 signals across traditional + agentic surfaces (CKG-5)
synthesis code-graph security --refresh     # Re-analyze before display
synthesis code-graph security --severity HIGH  # Filter to HIGH severity only (--errors-only alias)
synthesis code-graph security --type S001_SQL_INJECTION  # Filter by signal type
synthesis code-graph security --attack-surface  # Show attack surface map (entry points → sinks)
synthesis code-graph security --scan-secrets    # Also scan non-Java files for secrets
synthesis code-graph security --format json     # JSON output for CI/automation

# Workspace hygiene (self-organizing workspace)
synthesis sync                          # Directory identity sync (discover dirs, write .synthesis.md identity files)
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

## Directory Identity System (v1.12.0)

Per-directory `.synthesis.md` files declare what each directory accepts. This enables intelligent file routing without centralized rules.

- `synthesis sync` discovers directories and writes/updates identity files
- `SweepCommand` uses `DirectoryIdentityRouter` to route files to matching directories before falling back to archive
- `MaintainCommand --rebalance` periodically moves files from archive back to active directories when they score >= 0.7
- Frozen subtrees (`old-*`, `snapshot-*`, `frozen-*` at archive top level) are excluded from rebalance
- `.git` internals are always excluded from rebalance walks

**Key classes:** `DirectoryIdentity`, `DirectoryIdentityParser`, `DirectoryNameVocabulary`, `DirectorySignalExtractor`, `DirectoryScorer`, `DirectoryIdentityRouter`, `ScopeChecker`, `ScopeResolver`

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

## Knowledge Graph System (v1.12.2)

The knowledge graph adds semantic awareness to the directory identity system. All data stored in SQLite — no changes inside source trees.

### Document Knowledge Graph (Phases 1-4)

**Centroid** (what a directory IS): Computed from file enrichment signatures — ranked topics, named entities, document types, confidence score. Aggregated by `CentroidComputer` from per-file `EnrichmentSignature` data.

**Wants** (what a directory WANTS TO BECOME): Inferred from README + directory name + parent centroid + overrides. Satisfaction score = topicCoverage*0.5 + entityCoverage*0.3 + gapsFilled*0.2. Bootstrapped by `WantsBootstrapper` (4-tier cold start).

**Bidding pull model**: `DirectoryBidder` uses Jaccard similarity to let directories bid for enriched files (topics 40%, entities 45%, type 10%, timeframe 5%). A directory wins if its wants overlap the file's enrichment signature above threshold.

**Health signals**: W020 (want starvation: satisfaction < 0.1), W021 (want drift: satisfaction < 0.4 with confident centroid), I020 (want fulfillment), I021 (want conflict).

**Archetypes**: 6 built-in patterns (client-opportunity, project, methodology, marketing-campaign, product, archive) matched against centroid+wants for automatic classification.

**Routing cascade**: RoutingHints (learned) → ConfigRules (glob/keyword) → DirectoryBidder (enrichment bidding) → DirectoryScorer (identity-based).

**Key classes**: `DirectoryCentroid`, `DirectoryWants`, `DirectoryProfile`, `DirectoryHealth`, `EnrichmentSignature`, `EnrichmentSignatureExtractor`, `CentroidComputer`, `WantsBootstrapper`, `DirectoryBidder`, `WantSatisfactionComputer`, `VirtualMembershipManager`, `DirectoryArchetype`, `ArchetypeRegistry`, `GapAnalyzer`, `RoutingLearner`

**SQLite tables (V10-V11)**: `directory_centroids`, `file_enrichment_signatures`, `virtual_memberships`, `routing_feedback`

### DirectoryClassifier — Gating Logic

Prevents document knowledge graph features (centroid/wants/health) from polluting source code directories.

```
DOCUMENT — centroid ✓, wants ✓, health ✓, routing ✓
CODE      — centroid ✗, wants ✗, health ✗, routing ✗  (inferred from build files)
MEDIA     — centroid ✓, wants ✗, health ✓, routing ✓
GENERATED — centroid ✗, wants ✗, health ✗, routing ✗
```

**4-tier heuristic**:
1. Ancestor build file (pom.xml, Cargo.toml, go.mod, package.json, etc.) → CODE
2. Path patterns (src/main/java, src/test/, node_modules, __pycache__) → CODE/GENERATED
3. Content signals (majority .java/.py/.rs/.go files) → CODE
4. Carve-out: `docs/` inside code repos → DOCUMENT (override)

V12 migration: `classification` column in `directory_centroids`.

### Code Knowledge Graph (CKG-1)

Parallel system for source code repos. All metadata in SQLite only — no `.synthesis.md` inside source trees.

**What it stores** (V13 migration, 4 tables):
- `code_dependencies`: class-level import/extends/implements edges
- `module_profiles`: fan-in, fan-out, instability (Martin metric), domain concepts (future: CKG-2)
- `cross_format_links`: SQL→Java, YAML→Java, doc→code links
- `code_quality_gaps`: missing tests, interfaces, docs (future: CKG-3)

**`synthesis code-graph extract`**: persists dependency graph. `--incremental` uses git to detect changed files. `--dry-run` counts Java files without writing. `--stats` shows current graph status.

**`synthesis relate` / `synthesis impact`**: now query persisted SQLite graph first (instant); fall back to live file-reading when graph empty. Use `--refresh` to force re-extraction.

**`synthesis maintain` Phase 10**: automatically runs incremental code graph update after indexing.

**Key classes**: `CodeGraphExtractor`, `CodeGraphRepository`, `CodeGraphStats`, `CodeDependency`, `CrossFormatLinkRecord`

---

## KCP (Knowledge Context Protocol) v0.5

KCP is a structured YAML manifest format (`knowledge.yaml`) that tells AI agents which files
matter, what each is for, and the recommended read order. Spec: github.com/cantara/knowledge-context-protocol.
Synthesis provides full-stack v0.5 support across four capabilities.

**Detection:** `YamlAnalyzer` identifies `knowledge.yaml` as KCP when ALL THREE hold:
filename == `knowledge.yaml`, top-level `units` is a list, `project` or `id` key exists.
Extracts `KcpUnit` + `KcpRelationship` records with full field data.

**Persistence (V17):** Three SQLite tables — `kcp_manifests`, `kcp_units`, `kcp_relationships`.
`KcpRepository` provides idempotent upsert/delete. `ScanCommand` and `MaintainCommand` auto-persist
on detection and clean up on deletion.

**Export:** `synthesis export --format kcp` generates a v0.5 conformant YAML from the Lucene index.
Header: `kcp_version`, `language`, `indexing`, `hints.unit_count`. Per-unit fields inferred:
`format` (from extension), `kind` (policy/schema/omit), `triggers` (up to 8 headings),
`validated`/`updated` (quoted ISO dates).

**Knowledge graph:** `synthesis kg` surfaces KCP units as first-class nodes. ASCII groups by project.
Mermaid adds pill nodes + `kcp-unit` edges. JSON adds `kcpUnits` + `kcpRelationships` arrays.
`--scope` filters KCP units by manifest path prefix.

**Key classes:** `KcpUnit`, `KcpRelationship`, `KcpRepository` (`kcp` package);
`YamlAnalyzer.extractKcpManifestInfo()`; `ExportCommand.exportAsKcp()`;
`KnowledgeGraphCommand.collectKcpUnits()` / `collectKcpRelEdges()`.

---

## .synthesisignore — Indexing Exclusions

A `.synthesisignore` file at the workspace root tells `DirectoryScanner` to skip directories during
indexing. It uses `.gitignore`-style patterns (directory names or paths, one per line; `#` = comment).

```
# Exclude build artifacts from indexing
node_modules/
target/
.gradle/
__pycache__/
.venv/
```

**Key design invariants:**
- `DirectoryScanner` respects `.synthesisignore` → excluded dirs are NOT indexed.
- `HealthCommand.findBuildArtifacts()` (E002) always scans unconditionally — it is **blind to
  `.synthesisignore`**. This is intentional: health checks report what exists on disk, independently
  of what is indexed.
- **`synthesis health --fix-config` E002 flow:** Lists each detected build-artifact directory and
  asks for explicit `y/N` confirmation before appending to `.synthesisignore`. Never auto-applies.
- **`synthesis init`** proposes a default `.synthesisignore` (node_modules/, target/, .gradle/,
  __pycache__/, .venv/) and creates it with confirmation (`--yes`/`-y` auto-accepts).
- `appendToSynthesisIgnore()` is idempotent — will not duplicate an existing pattern.

**Parsing:** `DirectoryScanner.parseSynthesisIgnore(Path)` strips blank lines and `#` comments,
returning only real patterns. Matching is component-based: a pattern `node_modules/` excludes any
directory named `node_modules` at any depth.

---

## Project Structure

```
/src/exoreaction/Synthesis/
+-- src/main/java/io/exoreaction/synthesis/
|   +-- SynthesisApp.java              # Main entry point (picocli root, 51 subcommands)
|   +-- cli/                           # All CLI subcommands
|   +-- config/                        # Configuration management
|   +-- core/                          # Core utilities
|   +-- indexer/                       # File indexing pipeline (via index/)
|   +-- search/                        # Lucene search engine
|   +-- graph/                         # Dependency graph engine
|   +-- mcp/                           # MCP server implementation
|   +-- lsp/                           # LSP server implementation
|   +-- enrichment/                    # AI enrichment (media, docs)
|   +-- staging/                       # Team staging areas
|   +-- tracking/                      # Change tracking, file movements
|   +-- workspace/                     # Directory identity system
|   +-- validate/                      # Workspace validation
|   +-- org/                           # Organization registry
|   +-- ai/                            # AI service integration
|   +-- report/                        # Report engine
|   +-- summary/                       # Summary generation
|   +-- changelog/                     # Changelog engine
|   +-- db/                            # Database access layer
|   +-- telemetry/                     # Telemetry and pilot approval
|   +-- metrics/                       # Metrics collection
|   +-- update/                        # Self-update mechanism
|   +-- util/                          # Shared utilities
+-- src/test/                          # JUnit 5 tests (4,153)
+-- docs/                              # Multi-perspective documentation
|   +-- perspectives/                  # 9 role guides (Engineering, Exec, etc.)
+-- .claude/skills/                    # 32 Claude Code skills (see below)
```

---

## Skills Navigation

**Skills directory:** `.claude/skills/` (33 skills)

### Using Synthesis as a Tool (also available globally)

These skills describe how to USE Synthesis features -- valid both when working on this codebase and from any other project:

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
| `synthesis-knowledge-graph` | `synthesis knowledge-graph`, `describe`, `feedback`, `structure`, `evolution` | Document knowledge graph: centroid, wants, health, bidding, archetypes |
| `synthesis-code-graph` | `synthesis code-graph extract`, `cg` | Code dependency persistence, fast relate/impact |
| `synthesis-kcp` | `synthesis export --format kcp`, `synthesis kg` | KCP v0.5 support |

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
- **`staging route` content-intelligence fallback (issue #71):** When `autoClassify: true` (default), unmatched files are classified via `DownloadsClassifier.classifyWithCompanion()` -- reads the companion `.synthesis.md` if present. Files above `classificationThreshold` (default 0.5) are auto-routed (`~` prefix in output); below-threshold matches become suggestions. Use `staging route --enrich-first` to generate companions on-the-fly for unmatched IMAGE/PDF files before the classification pass (replaces the separate `synthesis enrich` step for images with UUID/hash names).
- **`synthesis explain --file <name>` bare filename resolution:** Accepts absolute, workspace-relative, or bare filename. Falls back to `index.search()` if not on disk -- exact filename match preferred over score. On failure suggests `synthesis search <name>`.
- **`synthesis summary --since` temporal context:** Parses `7d`/`24h`/`2w`/`3m`/`2026-01-15`, loads `ChangeEvent`s from `SnapshotManager.getChangesForWorkspace()`, injects compact change summary into the AI prompt (not just the output). Requires `synthesis maintain` to have run at least once to populate snapshots.
- **Directory identity `.synthesis.md` format:** YAML front matter with `synthesis.accepts.types/formats/patterns`, `synthesis.scope`, `synthesis.confidence`. Parsed by `DirectoryIdentityParser`. Written by `SyncCommand`.
- **`.synthesis.md` files in source repos**: `.synthesis.md` is now in `.gitignore` for the Synthesis repo. If you see stray `.synthesis.md` files in a source tree (left from before DirectoryClassifier was active), delete them — they should never be committed to source repos.
- **DirectoryClassifier gating**: `SyncCommand.syncDirectory()` now checks `DirectoryClassifier.classify()` before computing centroid/wants/health. Directories classified as CODE skip these phases entirely. `docs/` subdirectories inside code repos are carved out as DOCUMENT.
- **`synthesis code-graph extract` prerequisite**: Must be run before `synthesis relate --format json` can use the fast SQLite path. If graph is empty, relate falls back to live extraction (slower). Use `synthesis code-graph extract --stats` to check.
- **V7 permanently reserved**: Flyway migration V7 was deleted and the version permanently reserved. Current migrations: V1-V6, V8-V20.
- **V20**: `git_file_metrics` + `git_cochange` tables. Populated by `synthesis hotspots --refresh` via `GitMetricsComputer`. Both are reconstructible caches — losing them loses no information.
- **KCP detection heuristic**: `knowledge.yaml` files are detected as KCP manifests when ALL THREE hold: filename == `knowledge.yaml`, top-level `units` is a list, `project` or `id` key exists. Files failing any condition are indexed as generic YAML.
- **Security remediations (PRs #242, #243, #245)**: Synthesis dogfooded its own CKG-5 scanner and fixed the findings:
  - `PromptTemplates.java`: `sanitizeUserInput()` + XML boundary tags (`<system>`, `<user>`, `<document>`) on all prompts
  - `SynthesisDatabase.java`: `CLEANUP_TABLE_ALLOWLIST` guard prevents arbitrary table names in DELETE operations
  - `SynthesisToolHandler.java`: `dryRun` parameter check before file writes (S018 fix)
  - `DependencyInventoryExtractor.java`: strips XML comments before pom.xml parsing (S010 false positive fix, commit 11edd4e)
- **S010 false positive in XML comments**: Before commit 11edd4e, the S010 scanner would flag dependencies inside XML comment blocks (`<!-- ... -->`) in pom.xml files. Now fixed -- commented-out dependencies are stripped before parsing.

---

## Knowledge Integrity Direction (Feb 19, 2026)

A Phase 5 benchmark session identified a product direction shift: from retrieval to knowledge integrity.

**The insight:** All 90 benchmark sessions scored 3/3 correctness, but agents with skills sometimes
got faster but less complete/current answers than agents exploring from scratch. Skills can contain
stale data, miss undocumented patterns, or provide structural facts without semantic intent.

**Three failure modes:**
1. **Stale** -- skill says X, source now says X + more
2. **Silent** -- complex patterns exist in code, no skill documents them
3. **Ambiguous** -- same fact, missing intent ("V7 missing" vs "V7 intentionally reserved")

**Product direction:** `synthesis verify` (#93), `synthesis gaps` (#94), confidence metadata (#95/#102),
unified knowledge graph (#100->#101->#102).

**Key quote (Opus analysis, Feb 19):**
> *"The market for faster search is crowded. The market for trustworthy AI context is empty."*

**Documentation:**
- Opus analysis: `~/Documents/benchmark-phase4/opus-knowledge-integrity-analysis-2026-02-19.md`
- Session chronicle: `~/Documents/benchmark-phase4/SESSION-CHRONICLE-2026-02-19.md`
- Phase 5 results: `~/Documents/benchmark-phase4/phase5-results.md`
- Phase 6 results: `~/Documents/benchmark-phase4/phase6-results.md` -- Fixed CLI: **-23.8% vs Baseline** (beats Knowledge at -15%)
- GitHub issues filed: #93-#113 (20 issues from this session)

---

## Related Resources

- **Business context:** `synthesis-product-context` skill (auto-loaded globally)
- **LinkedIn campaign:** `synthesis-linkedin-campaign` skill (global)
- **Proof points:** `~/Documents/eXOReaction/PROOF-POINTS.md`
- **Attention strategy:** `~/Documents/eXOReaction/marketing/SYNTHESIS-ATTENTION-STRATEGY.md`
- **Docs:** `docs/perspectives/` -- 9 role-specific guides (~64,000 words)
