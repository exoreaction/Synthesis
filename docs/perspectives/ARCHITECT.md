# Synthesis for Architects

**58 repositories. 429 cross-dependencies. Mapped in 2.3 seconds. Co-change analysis reveals the coupling your import graph cannot see.**

---

## The Architecture Visibility Problem

AI-augmented teams generate code across repositories faster than architecture governance can track. Your architecture diagrams were drawn months ago. The actual dependency graph has drifted from the documented one, and you cannot see the drift without manual effort.

**What architects need but typically lack:**

| Need | Traditional tool | Limitation |
|------|-----------------|------------|
| Cross-repo dependency map | Manual Confluence diagram | Stale within weeks |
| Impact analysis (static) | IDE "Find Usages" | Single project only |
| Impact analysis (dynamic) | Manual git log archaeology | Time-consuming, incomplete |
| Architecture drift detection | Manual quarterly review | Always behind |
| Architecture self-documentation | Convention docs nobody reads | Never enforced |
| Anti-pattern detection | Code review (human) | Inconsistent, slow |
| Naming consistency | Style guides | No automated enforcement |
| Multi-format search | grep + Drive + Slack | Fragmented |

Synthesis solves these by indexing all repositories together and building a queryable knowledge graph with bi-directional relationship tracking, co-change analysis, knowledge graph edges, and a directory identity system that makes your architecture self-documenting.

---

## Dependency Analysis

### Module-Level Architecture Graph

```bash
synthesis graph --modules --format mermaid
```

Generates a high-level architecture view from your actual code. Not from documentation that may be stale, but from the current state of imports and references.

**Real example (Cantara codebase, 58 repositories, validated February 2026):**
- 58 nodes (repositories), 429 edges (dependencies)
- Generated in 2.3 seconds
- Hub nodes identified automatically by centrality
- Clusters visible (tightly coupled groups of repos)

### File-Level Impact Analysis (Static)

Before any architectural change, see exactly what depends on the component you plan to modify:

```bash
synthesis relate src/core/Projection.java
```

Output shows:
- **Outgoing (5 files):** What Projection.java depends on (Configuration, EventStoreService, etc.)
- **Incoming (28 files):** What depends on Projection.java (services, tests, examples, integrations)
- **Blast radius:** 28 files across 4 categories

```bash
synthesis relate src/core/Projection.java --mermaid    # Mermaid diagram
synthesis relate src/core/Projection.java --depth 2     # Follow 2 levels deep
```

### Co-Change Analysis (Dynamic Coupling)

Static dependencies show what *could* break. Co-change analysis shows what *actually* changes together:

```bash
synthesis impact src/core/Projection.java
```

`impact` uses historical commit co-occurrence data (stored in SQLite via `CoChangeGraph`, updated during `maintain`) to reveal coupling that import analysis cannot see:

- **Config files** that always need updating when a source file changes
- **Documentation** that drifts when the implementation changes
- **Test files** that are tightly coupled to specific implementations
- **Cross-module dependencies** where files in separate packages consistently change together

This is the gap between *intended* architecture and *actual* architecture. When `relate` says two components are independent but `impact` says they always change together, you have hidden coupling that your architecture diagrams do not show.

**Architecture decision use case:** Before splitting a module, run `impact` on each file in the module. If files consistently co-change with files outside the module boundary, the split will create cross-module coupling that makes independent deployment harder, not easier.

### Cross-Repository Dependency Mapping

```bash
synthesis cross-repo-deps
```

Maps dependencies between repositories. For microservice architectures, this reveals:
- Which repos depend on which others
- Dependency direction and weight (number of references)
- Isolated modules (safe to modify independently)
- Hub modules (high blast radius, require extra care)

### Discovering Unindexed Repositories

```bash
synthesis discover
```

Finds git repositories in your search paths that Synthesis has not yet indexed. Run this periodically to ensure your cross-repo dependency map is complete -- missing repos mean missing edges in your architecture graph.

---

## Architecture Intelligence

### Anti-Pattern Detection

```bash
synthesis architecture
```

Automatically detects structural issues:

| Category | Detection | Severity |
|----------|----------|----------|
| God classes | Files >1,000 lines (warning), >2,000 (error) | WARNING/ERROR |
| Circular dependencies | Import chains that loop back | ERROR |
| Dead code | Files with zero incoming references | INFO |
| Missing documentation | Code directories without README | WARNING |
| Test coverage gaps | Source files without test counterparts | WARNING |
| High coupling | Files with excessive incoming references | WARNING |
| Feature envy | Files referencing another module more than their own | INFO |

Filter by severity or category:

```bash
synthesis architecture --severity warning      # Only warnings and errors
synthesis architecture --category GOD_CLASS    # Only god class issues
synthesis architecture --format json           # Machine-readable output
```

### Knowledge Graph Edges

During `maintain`, the `KnowledgeEdgeScanner` builds links between documentation/skills and the source files they reference. The `KnowledgeReconciler` then detects when source changes degrade those links -- this is drift detection for your documentation, not just your architecture.

These edges are stored in SQLite and surfaced via `synthesis relate`. When you run `relate` on a source file, you now see not only code dependencies but also which docs, skills, and guides reference that file. When you change the file, you know exactly which documentation needs updating.

**Practical use:** After a refactoring, run `synthesis maintain` followed by `synthesis relate` on changed files. The knowledge graph edges will show you which architecture docs, README files, and skills are now stale.

### Workspace Health Diagnostics

```bash
synthesis health
```

Audits the workspace and reports a health score (0-100):

| Code | Severity | Description |
|------|----------|-------------|
| E001 | ERROR | Phantom sub-workspaces (configured paths that don't exist) |
| E002 | ERROR | Build artifacts at root (target/, node_modules/) |
| W001 | WARNING | Empty directories (no files anywhere in subtree) |
| W002 | WARNING | Loose root-level files (5+) |
| I001 | INFO | Archive percentage |

```bash
synthesis health --fix-config    # Auto-fix phantom sub-workspace paths
synthesis health --format json   # Machine-readable for CI
```

### Naming Consistency Enforcement

```bash
synthesis naming
```

Reports naming inconsistencies that erode architecture clarity:
- **Singular/plural collisions** -- `client/` and `clients/` at the same level
- **Semantic duplicates** -- directory names within Levenshtein distance 3
- **Convention drift** -- mixed kebab-case, snake_case, CamelCase in the same parent

This is architectural style enforcement without manual code review. Run it in CI to prevent naming drift as teams grow.

```bash
synthesis naming --scope src/         # Limit to source directories
synthesis naming --format json        # Machine-readable output
```

### Deep Research Reports

For comprehensive architectural analysis:

```bash
synthesis research --topic architecture
```

Runs a multi-pass AI analysis covering:
1. **Architecture pass:** Structure, layering, coupling, cohesion
2. **Dependencies pass:** External dependencies, version risks, upgrade paths
3. **Quality pass:** Code quality patterns, consistency, maintainability
4. **Security pass:** Security patterns, vulnerability surface, credential handling
5. **Evolution pass:** Technical debt trajectory, complexity trends
6. **Synthesis pass:** Combines all findings into a coherent report

Each pass examines your codebase from a different angle using your indexed content as context.

**Run specific passes:**

```bash
synthesis research --passes architecture,security,synthesis
synthesis research --topic security         # Security pass + synthesis only
synthesis research --topic dependencies     # Dependencies pass + synthesis only
```

**Cost preview (no API call):**

```bash
synthesis research --estimate
```

**Output targets:**

```bash
synthesis research --target chatgpt                    # Markdown report (default)
synthesis research --target notebooklm-infographic     # Data for NotebookLM infographic
synthesis research --target notebooklm-presentation    # Chapter-based narrative
synthesis research --output arch-report.md             # Save to file
```

**Caching:** Results are cached. Same analysis on unchanged code returns instantly. Force fresh: `--no-cache`.

---

## The Directory Identity System

New in v1.11.1, the Directory Identity System makes your workspace architecture self-documenting. Each directory declares what it accepts via a `.synthesis.md` file with YAML front matter, and Synthesis uses these declarations for intelligent file routing, workspace organization, and architecture enforcement.

### Generating Identity Files

```bash
synthesis sync
```

Walks the workspace and writes or updates `.synthesis.md` identity files in each directory. Inference uses two sources:
- **`DirectoryNameVocabulary`** -- well-known names like "meetings", "docs", "automation", "clients"
- **`DirectorySignalExtractor`** -- infers types from patterns of existing files in the directory

### Identity File Format

```yaml
---
synthesis:
  accepts:
    types:
      - "documentation"
      - "architecture-decisions"
    formats:
      - "md"
      - "pdf"
    patterns:
      - "*ADR*"
      - "*decision*"
  scope:
    level: "WORKSPACE"
    organization: null
    entity: null
  confidence: 0.8
  last_synced: "2026-02-19T..."
  source: "directory sync"
---
# Architecture Decision Records

This directory contains ADRs for the project.
```

### Why This Matters for Architecture

**1. Architecture as Code:** Your directory structure becomes a declared, machine-readable architecture. Instead of hoping that developers put files in the right place, the identity system defines what belongs where and enforces it through routing.

**2. Scope-Based Routing:** The `scope` field enables organization-aware and entity-aware file routing:
- Organization match = +0.24 score bonus
- Entity match = +0.40 score bonus
- Cross-organization routing is hard-blocked

This means files about Client A cannot accidentally end up in Client B's directory, even if the file patterns match.

**3. Drift Recovery:** `synthesis maintain --rebalance` scans the archive directory and moves files scoring >= 0.5 back to identity-matched active directories. Files that were misplaced or hastily archived can be automatically recovered.

**4. Continuous Architecture Enforcement:**

```bash
# After maintenance, sync identity files to reflect current state
synthesis maintain --sync

# Sweep stale root files to their declared destinations
synthesis sweep --dry-run

# Recover misplaced files from archive
synthesis maintain --rebalance
```

### Combining with Architecture Commands

The directory identity system complements existing architecture commands:

```bash
# Static dependencies: what does this file import?
synthesis relate src/core/EventBus.java

# Dynamic coupling: what actually changes with this file?
synthesis impact src/core/EventBus.java

# Architecture structure: what does the directory declare it accepts?
# (Read the .synthesis.md in the directory)

# Naming consistency: are directories named coherently?
synthesis naming

# Health: are there structural issues?
synthesis health
```

Together, these give you a multi-layered architecture view: declared structure (identity), static dependencies (relate), dynamic coupling (impact), and structural health (architecture, health, naming).

---

## Architecture Governance Patterns

### Pattern 1: ADR-Backed Impact Analysis

Before writing an Architecture Decision Record, quantify the impact with both static and dynamic analysis:

```bash
synthesis relate src/auth/AuthService.java
synthesis impact src/auth/AuthService.java
synthesis cross-repo-deps | grep auth
```

Your ADR now includes concrete data: "This change affects 28 files across 4 services (static), and historically co-changes with 12 additional files (dynamic), including 3 config files and 2 documentation files." Not estimates -- measured dependencies.

### Pattern 2: Refactoring Safety Net

Require both `relate` and `impact` output attached to PRs for shared components:

```bash
synthesis relate src/shared/EventBus.java > impact-analysis.md
synthesis impact src/shared/EventBus.java >> impact-analysis.md
```

Reviewers verify: "All 28 statically dependent files were updated. The 5 dynamically coupled files (config, docs) were also checked."

### Pattern 3: Architecture Drift Detection

Generate architecture graphs quarterly and diff them:

```bash
synthesis graph --modules --format mermaid > architecture-Q1-2026.md
# Next quarter:
synthesis graph --modules --format mermaid > architecture-Q2-2026.md
diff architecture-Q1-2026.md architecture-Q2-2026.md
```

Questions the diff answers:
- Is complexity increasing? (more nodes, more edges)
- Are we reducing coupling? (fewer cross-module edges)
- Did unexpected dependencies appear? (architecture violations)
- Are new modules properly integrated?

Supplement with co-change analysis for a dynamic view:

```bash
# Run impact on key hub files to see if coupling patterns changed
synthesis impact src/core/EventBus.java
```

### Pattern 4: Generated Architecture Documentation

Instead of maintaining architecture diagrams manually:

```bash
synthesis graph --modules --format mermaid > docs/architecture.md
synthesis sync   # Update directory identity files
```

Run this weekly or in CI. Architecture documentation that is generated from code is never stale. The directory identity files serve as living documentation of what each directory is for.

### Pattern 5: Multi-Perspective Architecture Review

For significant architectural decisions:

```bash
synthesis perspectives "should we migrate from monolith to microservices?"
synthesis perspectives "is event sourcing appropriate for our use case?"
synthesis perspectives "should we adopt gRPC for internal service communication?"
```

Generates analysis from multiple viewpoints (pragmatist, purist, risk analyst, performance engineer, etc.), grounded in your actual codebase structure and dependencies.

### Pattern 6: Architecture Health in CI

Run health, naming, and architecture checks as part of your CI pipeline:

```bash
# CI step: Architecture governance
synthesis health --format json
synthesis naming --format json
synthesis architecture --severity warning --format json
```

Fail the build if the health score drops below a threshold, new naming inconsistencies appear, or new anti-patterns are introduced.

### Pattern 7: Knowledge Graph Maintenance

After any significant refactoring, check which documentation is now stale:

```bash
synthesis maintain                          # Update knowledge graph edges
synthesis relate src/refactored/Module.java  # See which docs reference changed files
```

The knowledge graph edges (built by `KnowledgeEdgeScanner`, validated by `KnowledgeReconciler`) show exactly which docs, skills, and guides need updating. This turns "update the docs" from a vague reminder into a concrete checklist.

---

## Visual Dependency Graphs

### Graph Types

```bash
# Module-level (recommended starting point)
synthesis graph --modules --format mermaid

# File-level (centered on a specific file)
synthesis graph src/auth/AuthService.java --depth 2 --format mermaid

# Cross-repository
synthesis graph --cross-repo --format png --output repo-deps.png
```

### Output Formats

| Format | Use case | Requirements |
|--------|----------|-------------|
| `mermaid` | GitHub, GitLab, markdown docs | None |
| `png` | Presentations, reports | Graphviz installed |
| `svg` | Scalable diagrams, web embedding | Graphviz installed |
| `dot` | Processing with external tools | None |

### Interpretation

When reading a generated graph:
- **Hub nodes** (many connections) = high centrality, high risk on change
- **Clusters** (groups of tightly connected nodes) = treat as a unit for versioning
- **Isolated nodes** (few connections) = safe to modify independently
- **Bidirectional edges** = potential circular dependency, investigate

---

## Technical Architecture of Synthesis

For architects evaluating the tool itself:

**Core stack:**
- **Indexing:** Apache Lucene (full-text search, relevance ranking)
- **Database:** SQLite (metadata, relationships, caching, co-change graph, knowledge edges)
- **Analysis:** Language-specific parsers for Java, Python, JS/TS, Go, Rust, Kotlin, C/C++, and more
- **Relationships:** Bi-directional detection via import/reference parsing + co-change analysis from git history
- **Knowledge Graph:** Edges linking docs/skills to source files, with drift detection via reconciliation
- **Directory Identity:** Per-directory `.synthesis.md` declarations with type/format/pattern/scope matching
- **Graphs:** Mermaid, PNG (Graphviz), SVG, DOT output
- **Media:** Bundled ffprobe for video metadata, PDFBox for PDF extraction
- **Storage:** Local `.synthesis/` per workspace, 2-3% overhead

**Performance (measured, February 2026):**

| Metric | Value |
|--------|-------|
| Indexing throughput | 258-300 files/sec |
| Index overhead | 2.7% of content size |
| Search response | <1 second (10,000+ files) |
| Graph generation | 2.3 sec (58 nodes, 429 edges) |
| Incremental scan | 156-345 ms (1,000 files) |

**Test coverage:** ~2,500 tests (JUnit 5).

**Security model:** All processing is local. Core features require no network access. AI features (optional) require explicit opt-in and send only selected content to the Claude API.

---

## Quick Reference

```
synthesis relate <file>                     # Bi-directional static dependencies
synthesis relate <file> --mermaid           # As Mermaid diagram
synthesis relate <file> --depth 2           # Follow 2 levels
synthesis impact <file>                     # Co-change analysis (dynamic coupling)
synthesis graph --modules --format mermaid  # Module architecture
synthesis graph <file> --depth 2            # File-centered graph
synthesis graph --cross-repo                # Cross-repo dependencies
synthesis cross-repo-deps                   # Cross-repo summary
synthesis discover                          # Find unindexed git repos
synthesis architecture                      # Anti-pattern detection
synthesis architecture --severity warning   # Warnings and errors only
synthesis architecture --format json        # Machine-readable output
synthesis health                            # Workspace health diagnostics
synthesis health --fix-config               # Auto-fix config issues
synthesis naming                            # Naming consistency audit
synthesis naming --format json              # Machine-readable naming report
synthesis sync                              # Write directory identity files
synthesis maintain --sync                   # Maintain + update identity files
synthesis maintain --rebalance              # Recover misplaced archive files
synthesis maintain --update-activity-log    # Append today's changes to log
synthesis sweep --dry-run                   # Preview stale file routing
synthesis prune --yes                       # Remove empty directories
synthesis research --topic architecture     # Deep AI analysis
synthesis research --passes architecture,security,synthesis
synthesis research --estimate               # Cost preview
synthesis insights                          # Codebase health metrics
synthesis perspectives "question"           # Multi-angle analysis
synthesis summary --since 7d               # Temporal AI summary
```

---

**Synthesis v1.11.1 -- ~2,500 tests passing -- February 2026**

**Related guides:**
- [Developer Guide](./DEVELOPER.md) -- for your team members
- [Engineering Manager Guide](./ENGINEERING-MANAGER.md) -- team adoption and metrics
- [DevOps Guide](./DEVOPS.md) -- CI/CD integration
- [Full User Guide](../USER-GUIDE-V2.md) -- complete command reference
