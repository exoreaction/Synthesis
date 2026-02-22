# Code Knowledge Graph Design

**Date:** 2026-02-22
**Authors:** Claude Opus 4.6 (design analysis session)
**Context:** Parallel to the document knowledge graph ([IMPLEMENTATION-PLAN.md](./IMPLEMENTATION-PLAN.md)), this document designs knowledge graph features specifically for source code repositories. All metadata lives outside the source tree (SQLite only — no `.synthesis.md` files inside repos).

---

## The Core Distinction

The document knowledge graph (Phases 1-4) applies centroid/wants/health to **document directories** — semantic filing systems where files are chosen and organized intentionally. Source code directories are different: they are structural containers governed by build systems and programming language conventions. They don't "want" more content — they contain whatever the build system places in them.

The `DirectoryClassifier` (being implemented now) suppresses the document system for code directories. But code repos deserve their OWN knowledge graph — with different semantics.

---

## Existing Capabilities (Already in Synthesis)

Substantial extraction capability already exists and can be leveraged:

| Class | Package | Capability |
|---|---|---|
| `GraphBuilder` | `graph` | File-level relationship graph via import/reference extraction |
| `RelationService` | `graph` | Outgoing/incoming reference analysis (Java, Python, JS/TS, Markdown) |
| `ViolationDetector` | `graph` | Layer violation + circular dependency detection |
| `TestCoverageAnalyzer` | `graph` | Convention-based test discovery, untested file detection |
| `CoChangeAnalyzer` | `graph` | Git co-change analysis, behavioral coupling |
| `CrossFormatLinker` | `graph` | SQL→Java, YAML→Java cross-format links |
| `KnowledgeEdgeScanner` | `graph` | Skill/doc→source edges, drift calculation |
| `ArchitectureMonitor` | `architecture` | God class, dead code, circular deps, high coupling |
| `RelateCommand` | `cli` | `synthesis relate` — file-level relationship mapping |
| `ImpactCommand` | `cli` | `synthesis impact` — transitive change blast radius |

**Key insight:** Synthesis already has *most of the extraction* needed for a code knowledge graph. What it lacks is (a) persistent storage, (b) package-level aggregation, (c) a unified query model, and (d) code-specific health signals.

---

## 1. The Module Profile (Code's "Centroid")

```java
/**
 * The code-centric equivalent of DirectoryCentroid.
 * Stored externally in SQLite, never inside the source tree.
 */
public record ModuleProfile(
    String modulePath,              // e.g., "io/exoreaction/synthesis/cli"
    String inferredPurpose,         // e.g., "CLI command implementations"
    List<String> domainConcepts,    // e.g., ["routing", "workspace management"]
    int publicClasses,
    int publicInterfaces,
    List<String> exportedTypes,
    List<String> dependsOn,         // packages this module imports from
    List<String> dependedOnBy,      // packages that import from this module
    int fanIn,
    int fanOut,
    double instability,             // fanOut / (fanIn + fanOut) — Martin metric
    int totalFiles,
    int totalLines,
    int commitsLast30Days,
    Instant lastModified,
    double confidence,
    Instant lastComputed
) {}
```

**Purpose inference heuristic (no AI required):**

| Package name contains | Inferred purpose |
|---|---|
| `cli`, `command` | CLI command implementations |
| `core`, `model`, `domain` | Core domain model |
| `db`, `persistence`, `repo` | Data persistence |
| `config`, `settings` | Configuration management |
| `util`, `common`, `shared` | Shared utilities |
| `api`, `rest`, `controller` | API endpoint handlers |
| `service`, `business` | Business logic services |

---

## 2. Quality Gaps (Code's "Wants")

Code directories "want" **structural completeness** — things that should exist but don't.

```java
public enum GapType {
    MISSING_TESTS,          // public class has no test class
    MISSING_INTERFACE,      // concrete impl with no interface (high fan-in)
    MISSING_PACKAGE_INFO,   // no package-info.java
    MISSING_README,         // no README.md in module root
    MISSING_JAVADOC,        // public API with no documentation
    MISSING_ERROR_HANDLING, // public methods throwing raw exceptions
    UNTESTED_PUBLIC_METHODS,
    MISSING_INTEGRATION_TESTS
}
```

**Completeness score:** analogous to `wants.satisfaction` — ratio of checkpoints filled to total checkpoints.

---

## 3. Cross-References and Dependency Graph

### Storage: New SQLite Tables (V12)

```sql
-- Class-level dependency edges
CREATE TABLE code_dependencies (
    workspace_path TEXT NOT NULL,
    source_file TEXT NOT NULL,
    source_class TEXT NOT NULL,
    source_package TEXT NOT NULL,
    target_file TEXT,
    target_class TEXT NOT NULL,
    target_package TEXT NOT NULL,
    dependency_type TEXT NOT NULL,  -- "import", "extends", "implements", "annotation"
    is_external INTEGER DEFAULT 0,
    last_computed INTEGER NOT NULL,
    UNIQUE(workspace_path, source_file, target_class, target_package)
);

-- Module profiles
CREATE TABLE module_profiles (
    workspace_path TEXT NOT NULL,
    module_path TEXT NOT NULL,
    package_name TEXT,
    inferred_purpose TEXT,
    domain_concepts_json TEXT,
    public_classes INTEGER DEFAULT 0,
    public_interfaces INTEGER DEFAULT 0,
    exported_types_json TEXT,
    fan_in INTEGER DEFAULT 0,
    fan_out INTEGER DEFAULT 0,
    instability REAL DEFAULT 0.5,
    total_files INTEGER DEFAULT 0,
    commits_last_30_days INTEGER DEFAULT 0,
    confidence REAL DEFAULT 0.0,
    last_computed INTEGER NOT NULL,
    UNIQUE(workspace_path, module_path)
);

-- Cross-format links (SQL→Java, YAML→Java, doc→code)
CREATE TABLE cross_format_links (
    workspace_path TEXT NOT NULL,
    source_file TEXT NOT NULL,
    target_file TEXT NOT NULL,
    link_type TEXT NOT NULL,        -- "table-reference", "config-key", "file-reference"
    entity_name TEXT,
    last_computed INTEGER NOT NULL,
    UNIQUE(workspace_path, source_file, target_file, entity_name)
);

-- Quality gaps
CREATE TABLE code_quality_gaps (
    workspace_path TEXT NOT NULL,
    module_path TEXT NOT NULL,
    gap_type TEXT NOT NULL,
    description TEXT NOT NULL,
    severity TEXT NOT NULL,         -- HIGH, MEDIUM, LOW
    file_path TEXT,
    suggestion TEXT,
    last_computed INTEGER NOT NULL,
    UNIQUE(workspace_path, module_path, gap_type, file_path)
);
```

### High-Value Queries

| Question | SQL pattern |
|---|---|
| "Who calls this class?" | `SELECT source_file FROM code_dependencies WHERE target_class = ?` |
| "What does this package depend on?" | `SELECT DISTINCT target_package FROM code_dependencies WHERE source_package = ?` |
| "What breaks if I change this?" | BFS up `code_dependencies` from target |
| "Which packages are unstable?" | `SELECT * FROM module_profiles WHERE instability > 0.7` |
| "What has no tests?" | `SELECT * FROM code_quality_gaps WHERE gap_type = 'MISSING_TESTS'` |
| "What SQL migrations affect this class?" | `SELECT * FROM cross_format_links WHERE target_file = ?` |

---

## 4. Health Signals for Code

| Signal | Condition | Analogy to document signal |
|---|---|---|
| `C001_CIRCULAR_DEPENDENCY` | Package A ↔ Package B mutual imports | I021 (want conflict) |
| `C002_LAYER_VIOLATION` | Lower layer imports higher layer | — |
| `C010_HIGH_FAN_IN_NO_INTERFACE` | `fan_in > 5` and no interface | W020 (starvation) |
| `C011_HIGH_FAN_IN_NO_TESTS` | `fan_in > 5` and no test | W021 (drift) |
| `C012_GOD_PACKAGE` | `total_files > 20` | W021 (drift) |
| `C013_UNSTABLE_CORE` | Core package with `instability > 0.5` | — |
| `C014_ORPHAN_CODE` | Public class with `fan_in = 0` | W020 (starvation) |
| `C015_BEHAVIORAL_COUPLING` | Co-committed but no import link | — |
| `C020_HOTSPOT` | `commits_last_30_days > 10` | — |
| `C021_DOCUMENTATION_GAP` | No README + no skill coverage | W021 (drift) |
| `C022_TEST_COVERAGE_GAP` | <50% test coverage by file count | — |

---

## 5. CLI: `synthesis code-graph`

New command namespace (separate from existing `synthesis graph` and `synthesis knowledge-graph`):

```bash
synthesis code-graph extract              # build/rebuild the persistent graph
synthesis code-graph extract --incremental  # only changed files

synthesis code-graph                      # package dependency DAG (ASCII)
synthesis code-graph --format mermaid     # Mermaid output
synthesis code-graph --cycles             # circular dependencies
synthesis code-graph --hotspots           # most actively changed packages
synthesis code-graph --instability        # packages by instability metric
synthesis code-graph --layers             # 4-tier layer diagram with violations
synthesis code-graph --impact Foo.java    # transitive change blast radius
synthesis code-graph --cross-format       # SQL/YAML→Java links

synthesis code-graph describe             # all module profiles
synthesis code-graph describe --module cli  # specific module

synthesis code-graph health               # code health signals (C001-C022)
synthesis code-graph health --errors-only

synthesis code-graph gaps                 # quality gaps
synthesis code-graph gaps --type MISSING_TESTS
synthesis code-graph gaps --severity HIGH
```

### Example Output: Package DAG

```
Package dependency graph (14 packages)

  Layer 1 (Foundation):
    [core]     fan-in: 23  fan-out: 0   instability: 0.00 ✓
    [config]   fan-in: 15  fan-out: 1   instability: 0.06 ✓
    [util]     fan-in: 20  fan-out: 0   instability: 0.00 ✓

  Layer 2 (Index/Graph):
    [index]    fan-in: 14  fan-out: 2   instability: 0.13 ✓
    [graph]    fan-in:  5  fan-out: 3   instability: 0.38 ✓

  Layer 3 (Services):
    [org]      fan-in:  6  fan-out: 5   instability: 0.45 ✓
    [staging]  fan-in:  2  fan-out: 4   instability: 0.67 ⚠

  Layer 4 (CLI):
    [cli]      fan-in:  0  fan-out: 12  instability: 1.00 (expected)

  [!] Circular: org ↔ cli (3 imports each way)
  [!] Layer violation: graph → cli (ViolationDetector imports ArchitectureAlert)
```

---

## 6. Metadata Storage Strategy

| Data | Storage | Rationale |
|---|---|---|
| Module profiles | SQLite | Fast queries, no filesystem pollution |
| Class-level dependencies | SQLite | Graph traversal, JOIN with other tables |
| Cross-format links | SQLite | Same |
| Quality gaps | SQLite | Filterable, sortable |
| Rendered graphs (Mermaid, DOT) | `~/.synthesis/codegraphs/<workspace-hash>/` | Cacheable artifacts |

**No `.synthesis.md` files inside source trees.** Classification result cached in SQLite.

### Sync Strategy

**Git-aware incremental (preferred):**
1. `git diff --name-only <last-hash>..HEAD`
2. Re-extract only changed files
3. Recompute module profiles for affected packages
4. Store HEAD hash as last_computed_hash

**File-hash fallback:** for non-git workspaces.

**Integration with `maintain`:** A new Phase 10 in the maintain pipeline runs incremental code graph updates for source code workspaces.

---

## 7. Integration with Existing Features

| Feature | Enhancement |
|---|---|
| `synthesis relate` | Query persisted `code_dependencies` (ms vs seconds). Add `--refresh` flag. |
| `synthesis impact` | BFS on SQLite graph (instant) vs file reads (slow). |
| `synthesis search` | Append caller count + module stability to code search results. |
| `synthesis architecture` | Feed quality gaps as additional `ArchitectureAlert` entries. |
| `synthesis health` | Show code health signals (C001-C022) alongside document signals. |
| LSP server | Use persisted graph for go-to-definition, find-references, call hierarchy. |
| Document routing | Match document entities against `module_profiles.exported_types_json` → cross-format links. |

---

## 8. Implementation Phases

| Phase | Version | Effort | Key deliverables |
|---|---|---|---|
| **CKG-1** | v1.13.x | ~2 weeks | Persist dependency graph; fast `relate`/`impact`; `code-graph extract` |
| **CKG-2** | v1.14.x | ~2 weeks | Module profiles; health signals (C001-C022); `code-graph describe/health` |
| **CKG-3** | v1.15.x | ~2 weeks | Quality gap detection; completeness scoring; `code-graph gaps` |
| **CKG-4** | v1.16.x | ~2 weeks | DAG visualization; cycles; hotspots; instability; full `code-graph` |

**Total: ~8 weeks, 20 issues, 10 new classes.** Half the effort of the document graph because extraction capability already exists.

### CKG-1 Issues (highest priority — delivers the most value first)

1. **CKG-1.01:** Flyway V12 — `code_dependencies`, `module_profiles`, `cross_format_links`, `code_quality_gaps` tables
2. **CKG-1.02:** `CodeGraphExtractor` — persist dependency extraction (wraps existing `ViolationDetector`, `GraphBuilder`, `CrossFormatLinker`)
3. **CKG-1.03:** Refactor `RelateCommand` to query persisted graph + `--refresh` flag
4. **CKG-1.04:** Refactor `ImpactCommand` to BFS on SQLite
5. **CKG-1.05:** Add code graph extraction to `maintain` pipeline (Phase 10, incremental)
6. **CKG-1.06:** `synthesis code-graph extract` command

---

## Relationship to Document Knowledge Graph

| Document Concept | Code Equivalent | Storage |
|---|---|---|
| `DirectoryCentroid` | `ModuleProfile` | SQLite (both) |
| `DirectoryWants` | `QualityGap` list | SQLite (both) |
| `virtual_memberships` | `cross_format_links` | SQLite (both) |
| Health signals W020/W021 | Code health signals C001-C022 | SQLite (both) |
| `.synthesis.md` per directory | SQLite only | Different — no source tree pollution |
| `synthesis knowledge-graph` | `synthesis code-graph` | Parallel commands, different semantics |
| Enrichment pipeline (AI) | Static analysis pipeline | Document = AI enrichment; Code = import extraction |

The two systems are **parallel but independent**. In a mixed workspace (repo with `src/` and `docs/`), both run: the document graph covers `docs/`, the code graph covers `src/`.

---

*Related documents:*
*[KNOWLEDGE-GRAPH-VISION.md](../vision/KNOWLEDGE-GRAPH-VISION.md) — document knowledge graph vision*
*[IMPLEMENTATION-PLAN.md](./IMPLEMENTATION-PLAN.md) — document knowledge graph implementation plan*
