# Claude Code Project Context: Synthesis

Synthesis is an open-source (MIT) Java 17+ CLI tool and MCP server for knowledge infrastructure. It indexes everything a team creates — code, docs, videos, PDFs — and makes it instantly searchable with relationship tracking and AI-powered analysis.

**Repository:** https://github.com/exoreaction/Synthesis
**License:** MIT
**Status:** Production-ready (v1.9.5, Feb 2026)

---

## What It Solves

AI tools made developers 10x faster at creating code — but comprehension speed stayed at 1x. 40-60% of time is spent searching for context, wasting the AI investment. Synthesis bridges that gap:

- Indexes 200-300 files/second across all formats
- Sub-second search (0.4s validated)
- Bi-directional relationship tracking ("what breaks if I change this?")
- Cross-repo dependency graphs (58 repos, 429 dependencies in <31 seconds)
- Local-only processing — zero cloud, privacy-first

**Validated:** 36,342 files indexed, 2,497 tests passing, 92-95% reduction in retrieval time.

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

### Core CLI Commands

```bash
synthesis init                          # Initialize workspace
synthesis scan                          # Index files (200-300/sec)
synthesis search "keyword"              # Sub-second full-text search
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

**Skills directory:** `.claude/skills/` (25 skills)

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
- **`staging route` content-intelligence fallback (issue #71):** When `autoClassify: true` (default), unmatched files are classified via `DownloadsClassifier.classifyWithCompanion()` — reads the companion `.synthesis.md` if present. Files above `classificationThreshold` (default 0.5) are auto-routed (`~` prefix in output); below-threshold matches become suggestions. Run `synthesis enrich` first to populate companions for PDFs/images.
- **`synthesis explain --file <name>` bare filename resolution:** Accepts absolute, workspace-relative, or bare filename. Falls back to `index.search()` if not on disk — exact filename match preferred over score. On failure suggests `synthesis search <name>`.
- **`synthesis summary --since` temporal context:** Parses `7d`/`24h`/`2w`/`3m`/`2026-01-15`, loads `ChangeEvent`s from `SnapshotManager.getChangesForWorkspace()`, injects compact change summary into the AI prompt (not just the output). Requires `synthesis maintain` to have run at least once to populate snapshots.

---

## Related Resources

- **Business context:** `synthesis-product-context` skill (auto-loaded globally)
- **LinkedIn campaign:** `synthesis-linkedin-campaign` skill (global)
- **Proof points:** `~/Documents/eXOReaction/PROOF-POINTS.md`
- **Attention strategy:** `~/Documents/eXOReaction/marketing/SYNTHESIS-ATTENTION-STRATEGY.md`
- **Docs:** `docs/perspectives/` — 9 role-specific guides (~64,000 words)
