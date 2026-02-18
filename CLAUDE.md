# Claude Code Project Context: Synthesis

Synthesis is an open-source (MIT) Java 17+ CLI tool and MCP server for knowledge infrastructure. It indexes everything a team creates — code, docs, videos, PDFs — and makes it instantly searchable with relationship tracking and AI-powered analysis.

**Repository:** https://github.com/exoreaction/Synthesis
**License:** MIT
**Status:** Production-ready (v1.8.4-SNAPSHOT, Feb 2026)

---

## What It Solves

AI tools made developers 10x faster at creating code — but comprehension speed stayed at 1x. 40-60% of time is spent searching for context, wasting the AI investment. Synthesis bridges that gap:

- Indexes 200-300 files/second across all formats
- Sub-second search (0.4s validated)
- Bi-directional relationship tracking ("what breaks if I change this?")
- Cross-repo dependency graphs (58 repos, 429 dependencies in <31 seconds)
- Local-only processing — zero cloud, privacy-first

**Validated:** 36,342 files indexed, 2,291 tests passing, 92-95% reduction in retrieval time.

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
synthesis maintain                      # Housekeeping + change tracking
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
├── src/test/                          # JUnit 5 tests (2,291+)
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
| `synthesis-explain-code` | `synthesis explain` | Natural language code explanations |
| `synthesis-scoped-search` | `synthesis search --scope` | Targeted search within a subset |
| `synthesis-relate-dependencies` | `synthesis relate` | What breaks if you change X? |
| `synthesis-graph-architecture` | `synthesis graph` | Mermaid dependency graphs |
| `synthesis-insights-metrics` | `synthesis insights` | Index health and metrics |
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
- **Flyway:** migration files must follow `V{n}__description.sql` naming exactly
- **Never use SNAPSHOT versions** in release artifacts — always bump to a release before tagging

---

## Related Resources

- **Business context:** `synthesis-product-context` skill (auto-loaded globally)
- **LinkedIn campaign:** `synthesis-linkedin-campaign` skill (global)
- **Proof points:** `~/Documents/eXOReaction/PROOF-POINTS.md`
- **Attention strategy:** `~/Documents/eXOReaction/marketing/SYNTHESIS-ATTENTION-STRATEGY.md`
- **Docs:** `docs/perspectives/` — 9 role-specific guides (~64,000 words)
