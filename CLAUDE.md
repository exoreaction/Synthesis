# Claude Code Project Context: Synthesis

Synthesis is an open-source (MIT) Java 17+ CLI tool and MCP server for knowledge infrastructure. It indexes everything a team creates — code, docs, videos, PDFs — and makes it instantly searchable with relationship tracking and AI-powered analysis.

**Repository:** https://github.com/exoreaction/Synthesis
**License:** MIT
**Status:** Production-ready (v1.10.5, Feb 2026)

---

## What It Solves

AI tools made developers 10x faster at creating code — but comprehension speed stayed at 1x. 40-60% of time is spent searching for context, wasting the AI investment. Synthesis bridges that gap:

- Indexes 200-300 files/second across all formats
- Sub-second search (0.4s validated)
- Bi-directional relationship tracking ("what breaks if I change this?")
- Cross-repo dependency graphs (58 repos, 429 dependencies in <31 seconds)
- Local-only processing — zero cloud, privacy-first

**Validated:** 36,342 files indexed, 2,751 tests passing, 92-95% reduction in retrieval time.

---

## Technology Stack

- **Language:** Java 17+
- **Build:** Maven
- **CLI Framework:** picocli
- **Search:** Lucene (full-text index)
- **Database:** SQLite (via JDBC)
- **Schema Migrations:** Flyway
- **Tests:** JUnit 5

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

**Lesson (benchmark Feb 19, 2026):** Omitting `-d` causes synthesis to search the docs workspace
and return irrelevant results — even when source code IS indexed. See issue #85.

**⚠️ Two different paths — do not confuse:**
- **Synthesis workspace root:** `/src/exoreaction` (has `.synthesis/` index, is the `-d` target)
- **Project source tree:** `/home/totto/src/exoreaction/Synthesis/` (for file reads/edits)
- `/home/totto/src/exoreaction/` has NO `.synthesis/` — using it as `-d` returns exit code 1

### Core CLI Commands

```bash
synthesis init                          # Initialize workspace
synthesis scan                          # Index files (200-300/sec)
synthesis search -d /src/exoreaction "keyword"   # Search source code
synthesis search "keyword"              # Search business docs (~/Documents)
synthesis relate "filename"             # What breaks if you change this?
synthesis graph --modules               # Architecture graph (Mermaid)
synthesis ask "question"                # AI Q&A grounded in indexed files
synthesis maintain                      # Housekeeping + change tracking (also runs staging ingest+route)

# Executive shell wrapper (bin/exo)
exo ask "question"                      # Conversational RAG: search → sources → streamed answer → follow-up
synthesis track                         # Track file movements
synthesis changelog --since 7d          # Cross-workspace change report
synthesis status                        # Index health + metrics
synthesis release                       # Release management
```

---

## Project Structure

```
/src/exoreaction/Synthesis/
├── src/main/java/io/exoreaction/synthesis/
│   ├── SynthesisApp.java              # Main entry point (picocli root)
│   ├── cli/                           # All CLI subcommands
│   ├── config/                        # Configuration management
│   ├── indexer/                       # File indexing pipeline
│   ├── search/                        # Lucene search engine
│   ├── graph/                         # Dependency graph engine
│   ├── mcp/                           # MCP server implementation
│   ├── enrichment/                    # AI enrichment (media, docs)
│   ├── staging/                       # Team staging areas
│   └── tracking/                      # Change tracking, file movements
├── src/test/                          # JUnit 5 tests (2,471+)
├── docs/                              # Multi-perspective documentation
│   └── perspectives/                  # 9 role guides (Engineering, Exec, etc.)
└── .claude/skills/                    # 25 Claude Code skills (see below)
```

---

## Skills Navigation

**Skills directory:** `.claude/skills/` (31 skills — 2 new added Feb 19, 2026: `synthesis-knowledge-integrity`, `synthesis-task-routing`)

### Using Synthesis as a Tool (also available globally)

These skills describe how to USE Synthesis features — valid both when working on this codebase and from any other project:

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

### Benchmark & Knowledge Integrity (NEW: Feb 19, 2026)

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
| `synthesis-workspace-management` | Workspace lifecycle (init, scan, maintain) |
| `synthesis-track-movements` | File movement tracking implementation |
| `synthesis-architecture-monitoring` | Architecture health monitoring |

**Start here for new work:** `synthesis-development` — covers architecture decisions, patterns, and how to navigate the codebase.

---

## Known Gotchas

- **SQLite JDBC:** `getObject(col, Integer.class)` fails for NULL columns — use non-null values in tests or `getLong()`/`getInt()` with null checks
- **JUnit 5:** `assertDoesNotThrow(() -> new Foo())` is ambiguous — cast to `(Executable)` explicitly
- **Flyway:** migration files must follow `V{n}__description.sql` naming exactly; V7 is intentionally absent/reserved
- **Never use SNAPSHOT versions** in release artifacts — always bump to a release before tagging
- **SearchResult constructor field order:** `(path, relativePath, score, fileName, fileType, language, summary, headings, structure, sizeBytes)` — `structure` is position 9 (not 7). Tests that detect method counts must put the structure string in position 9, not 7 (summary).
- **`synthesis research`** uses claude-haiku and generates prompts for external tools (ChatGPT, NotebookLM) — it does NOT produce standalone analysis reports. Use `synthesis perspectives` for real deep-dives grounded in indexed source files.
- **`synthesis export-skills`** uses `--overwrite` (not `--install`) to update `~/.claude/skills/`. The `--install` flag belongs to `synthesis learn`.
- **`synthesis learn`** requires `synthesis org scan` first or errors with "No organizations found".
- **Staging `_processed` suffix:** `routeTo()` copies file to destination and renames source to `*_processed.*` (not delete). The cron should run `staging ingest && staging route && maintain` — `maintain` alone does NOT trigger staging ingest/route. Use `retentionDays: -1` in tests to force expiry (0 sets `expiresAt = now`, which is not strictly less than `now`).
- **`staging route` content-intelligence fallback (issue #71):** When `autoClassify: true` (default), unmatched files are classified via `DownloadsClassifier.classifyWithCompanion()` — reads the companion `.synthesis.md` if present. Files above `classificationThreshold` (default 0.5) are auto-routed (`~` prefix in output); below-threshold matches become suggestions. Use `staging route --enrich-first` to generate companions on-the-fly for unmatched IMAGE/PDF files before the classification pass (replaces the separate `synthesis enrich` step for images with UUID/hash names).
- **`synthesis explain --file <name>` bare filename resolution:** Accepts absolute, workspace-relative, or bare filename. Falls back to `index.search()` if not on disk — exact filename match preferred over score. On failure suggests `synthesis search <name>`.
- **`synthesis summary --since` temporal context:** Parses `7d`/`24h`/`2w`/`3m`/`2026-01-15`, loads `ChangeEvent`s from `SnapshotManager.getChangesForWorkspace()`, injects compact change summary into the AI prompt (not just the output). Requires `synthesis maintain` to have run at least once to populate snapshots.

---

## Knowledge Integrity Direction (Feb 19, 2026)

A Phase 5 benchmark session identified a product direction shift: from retrieval to knowledge integrity.

**The insight:** All 90 benchmark sessions scored 3/3 correctness, but agents with skills sometimes
got faster but less complete/current answers than agents exploring from scratch. Skills can contain
stale data, miss undocumented patterns, or provide structural facts without semantic intent.

**Three failure modes:**
1. **Stale** — skill says X, source now says X + more
2. **Silent** — complex patterns exist in code, no skill documents them
3. **Ambiguous** — same fact, missing intent ("V7 missing" vs "V7 intentionally reserved")

**Product direction:** `synthesis verify` (#93), `synthesis gaps` (#94), confidence metadata (#95/#102),
unified knowledge graph (#100→#101→#102).

**Key quote (Opus analysis, Feb 19):**
> *"The market for faster search is crowded. The market for trustworthy AI context is empty."*

**Documentation:**
- Opus analysis: `~/Documents/benchmark-phase4/opus-knowledge-integrity-analysis-2026-02-19.md`
- Session chronicle: `~/Documents/benchmark-phase4/SESSION-CHRONICLE-2026-02-19.md`
- Phase 5 results: `~/Documents/benchmark-phase4/phase5-results.md`
- Phase 6 results: `~/Documents/benchmark-phase4/phase6-results.md` — Fixed CLI: **-23.8% vs Baseline** (beats Knowledge at -15%)
- GitHub issues filed: #93–#113 (20 issues from this session)

---

## Related Resources

- **Business context:** `synthesis-product-context` skill (auto-loaded globally)
- **LinkedIn campaign:** `synthesis-linkedin-campaign` skill (global)
- **Proof points:** `~/Documents/eXOReaction/PROOF-POINTS.md`
- **Attention strategy:** `~/Documents/eXOReaction/marketing/SYNTHESIS-ATTENTION-STRATEGY.md`
- **Docs:** `docs/perspectives/` — 9 role-specific guides (~64,000 words)
