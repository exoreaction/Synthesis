# Synthesis for Architects

**58 repositories. 429 cross-dependencies. Mapped in 2.3 seconds. Can your current tooling do that?**

---

## The Architecture Visibility Problem

AI-augmented teams generate code across repositories faster than architecture governance can track. Your architecture diagrams were drawn months ago. The actual dependency graph has drifted from the documented one, and you cannot see the drift without manual effort.

**What architects need but typically lack:**

| Need | Traditional tool | Limitation |
|------|-----------------|------------|
| Cross-repo dependency map | Manual Confluence diagram | Stale within weeks |
| Impact analysis | IDE "Find Usages" | Single project only |
| Architecture drift detection | Manual quarterly review | Always behind |
| Anti-pattern detection | Code review (human) | Inconsistent, slow |
| Multi-format search | grep + Drive + Slack | Fragmented |

Synthesis solves these by indexing all repositories together and building a queryable knowledge graph with bi-directional relationship tracking.

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

### File-Level Impact Analysis

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

### Cross-Repository Dependency Mapping

```bash
synthesis cross-repo-deps
```

Maps dependencies between repositories. For microservice architectures, this reveals:
- Which repos depend on which others
- Dependency direction and weight (number of references)
- Isolated modules (safe to modify independently)
- Hub modules (high blast radius, require extra care)

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

## Architecture Governance Patterns

### Pattern 1: ADR-Backed Impact Analysis

Before writing an Architecture Decision Record, quantify the impact:

```bash
synthesis relate src/auth/AuthService.java
synthesis cross-repo-deps | grep auth
```

Your ADR now includes concrete data: "This change affects 28 files across 4 services, 12 tests, and 12 examples." Not estimates -- measured dependencies.

### Pattern 2: Refactoring Safety Net

Require `relate` output attached to PRs for shared components:

```bash
synthesis relate src/shared/EventBus.java > impact-analysis.md
```

Reviewers verify: "All 28 dependent files were updated. None missed."

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

### Pattern 4: Generated Architecture Documentation

Instead of maintaining architecture diagrams manually:

```bash
synthesis graph --modules --format mermaid > docs/architecture.md
```

Run this weekly or in CI. Architecture documentation that is generated from code is never stale.

### Pattern 5: Multi-Perspective Architecture Review

For significant architectural decisions:

```bash
synthesis perspectives "should we migrate from monolith to microservices?"
synthesis perspectives "is event sourcing appropriate for our use case?"
synthesis perspectives "should we adopt gRPC for internal service communication?"
```

Generates analysis from multiple viewpoints (pragmatist, purist, risk analyst, performance engineer, etc.), grounded in your actual codebase structure and dependencies.

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
- **Database:** SQLite (metadata, relationships, caching)
- **Analysis:** Language-specific parsers for Java, Python, JS/TS, Go, Rust, Kotlin, C/C++, and more
- **Relationships:** Bi-directional detection via import/reference parsing
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

**Security model:** All processing is local. Core features require no network access. AI features (optional) require explicit opt-in and send only selected content to the Claude API.

---

## Quick Reference

```
synthesis relate <file>                     # Bi-directional dependencies
synthesis relate <file> --mermaid           # As Mermaid diagram
synthesis relate <file> --depth 2           # Follow 2 levels
synthesis graph --modules --format mermaid  # Module architecture
synthesis graph <file> --depth 2            # File-centered graph
synthesis graph --cross-repo                # Cross-repo dependencies
synthesis cross-repo-deps                   # Cross-repo summary
synthesis architecture                      # Anti-pattern detection
synthesis architecture --severity warning   # Warnings and errors only
synthesis architecture --format json        # Machine-readable output
synthesis research --topic architecture     # Deep AI analysis
synthesis research --passes architecture,security,synthesis
synthesis research --estimate               # Cost preview
synthesis insights                          # Codebase health metrics
synthesis perspectives "question"           # Multi-angle analysis
```

---

**Related guides:**
- [Developer Guide](./DEVELOPER.md) -- for your team members
- [Engineering Manager Guide](./ENGINEERING-MANAGER.md) -- team adoption and metrics
- [DevOps Guide](./DEVOPS.md) -- CI/CD integration
- [Full User Guide](../USER-GUIDE-V2.md) -- complete command reference
