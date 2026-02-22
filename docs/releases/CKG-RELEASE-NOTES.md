# Code Knowledge Graph (CKG) -- Release Notes

**Version:** v1.12.2
**Date:** February 22, 2026
**Commits:** 5 (CKG-1 through CKG-4 + skill documentation)
**Lines changed:** +7,346 / -75 across 40 files
**Tests added:** 138 new tests (3,704 baseline to 3,842 total)

---

## 1. Overview

The Code Knowledge Graph (CKG) transforms Synthesis from a file-and-document indexer into a **structural code intelligence** system. Before CKG, every `relate` or `impact` invocation re-read source files from disk, parsing imports on the fly. For a workspace with hundreds of Java files, this meant multi-second delays per query. CKG solves three problems:

1. **Performance.** Dependency data is extracted once and persisted to SQLite. Subsequent `relate` and `impact` queries execute against indexed database rows -- instant lookups instead of filesystem scans.

2. **Architectural insight.** Raw dependency edges are aggregated into module profiles with fan-in/fan-out counts, Robert C. Martin's instability metric, inferred purpose labels, and completeness scores. These profiles answer questions that raw file listings cannot: "Which packages are structurally risky?", "Where does coupling concentrate?", "Which modules violate the Stable Dependencies Principle?"

3. **Quality enforcement.** The system detects health signals (circular dependencies, god packages, unstable core modules, hotspots) and quality gaps (missing tests, missing interfaces, undocumented high-value modules) automatically. Completeness scoring quantifies how far each module is from structural completeness.

CKG was implemented in four phases, each building on the previous:

| Phase | Scope | Tests |
|-------|-------|-------|
| CKG-1 | Dependency persistence + extract command | 42 |
| CKG-2 | Module profiles + health signals | 39 |
| CKG-3 | Quality gap detection + completeness scoring | 28 |
| CKG-4 | DAG visualization + layer analysis | 29 |
| **Total** | | **138** |

---

## 2. Architecture

The CKG system is organized as a four-layer pipeline:

```
Source Files (.java, .sql, .yaml)
        |
        v
  CodeGraphExtractor          (CKG-1)  Parse + persist dependency edges
        |
        v
  ModuleProfileComputer       (CKG-2)  Aggregate into package-level profiles
  CodeHealthAnalyzer           (CKG-2)  Detect structural health signals
        |
        v
  QualityGapDetector          (CKG-3)  Cross-reference profiles + filesystem
  CompletenessScorer           (CKG-3)  Weighted gap penalty scoring
        |
        v
  DagRenderer                 (CKG-4)  ASCII + Mermaid DAG visualization
```

All data flows through four SQLite tables (V13 migration), scoped by `workspace_path` for multi-workspace isolation. The `CodeGraphCommand` CLI exposes the pipeline as subcommands (`extract`, `describe`, `health`, `gaps`) and top-level flags (`--cycles`, `--hotspots`, `--layers`, etc.).

The `MaintainOrchestrator` integrates CKG as Phase 10 of the maintenance pipeline. When the code graph is already populated, `maintain` runs incremental extraction, profile computation, gap detection, and completeness scoring automatically.

---

## 3. CKG-1: Dependency Persistence

**Commit:** `7ceb628` -- 2,264 lines added, 42 tests
**Issue scope:** CKG-1.01 through CKG-1.06

### What was built

**CodeGraphExtractor** parses Java source files using regex patterns to extract four dependency types:
- `import` -- standard and static import statements
- `extends` -- class inheritance edges
- `implements` -- interface implementation edges
- `annotation` -- annotation usage (inferred from import)

Each edge records source file, source class, source package, target class, target package, dependency type, and whether the target is external (third-party). External dependencies are identified by checking whether the target package exists within the workspace.

**CodeGraphRepository** provides the persistence layer with `INSERT OR REPLACE` semantics for idempotent writes. Key operations:
- `deleteAllForWorkspace()` -- clear before full re-extraction
- `deleteBySourceFile()` -- surgical removal for incremental updates
- `isPopulated()` -- check if the graph has data
- `countDependencies()` / `countCrossFormatLinks()` -- statistics

**Cross-format links** are detected by the existing `CrossFormatLinker` and persisted to `cross_format_links`. These connect SQL migration files to Java entity classes, YAML configuration to Java config classes, and similar cross-format relationships.

### How relate/impact got fast

Before CKG-1, `RelateCommand` and `ImpactCommand` read every Java file in the workspace on each invocation. After CKG-1:

1. Both commands check if the code graph is populated (`repository.isPopulated()`).
2. If populated, they query `code_dependencies` via SQL -- O(1) lookups via indexed columns.
3. If not populated, they fall back to the original live-extraction path.
4. The `--refresh` flag forces re-extraction before querying.

**ImpactCommand** performs BFS traversal on the persisted graph to compute transitive change impact (blast radius), which is now instant for populated workspaces.

### The extract command

```
synthesis code-graph extract                # full extraction (clears + rebuilds)
synthesis code-graph extract --incremental  # only changed files
synthesis code-graph extract --dry-run      # show file counts without writing
synthesis code-graph extract --stats        # show current graph statistics
```

Alias: `synthesis cg extract`

---

## 4. CKG-2: Module Profiles & Health Signals

**Commit:** `fe44a6a` -- 2,057 lines added, 39 tests
**Issue scope:** CKG-2.01 through CKG-2.05

### Module profiles (ModuleProfileComputer)

The `ModuleProfileComputer` aggregates raw `code_dependencies` rows into per-package profiles stored in `module_profiles`. For each discovered Java package, it computes:

- **Fan-in:** Count of distinct internal packages that import from this package. Measures how widely depended-upon the package is.
- **Fan-out:** Count of distinct internal packages this package imports. External (third-party) dependencies are excluded since they are outside the project's control.
- **Instability:** `fanOut / (fanIn + fanOut)` -- Robert C. Martin's instability metric. A value of 0.0 means maximally stable (many dependents, few dependencies); 1.0 means maximally unstable (few dependents, many dependencies). Orphan packages (fanIn=0, fanOut=0) default to 0.5.
- **Inferred purpose:** Heuristic label derived from the last segment of the package name. For example, `cli` maps to "CLI command implementations", `core` maps to "Core domain model", `graph` maps to "Graph analysis and visualization". Packages with no recognized segment receive "General purpose".
- **Total files:** Count of distinct source files in the package.
- **Confidence:** 0.8 for packages with at least one edge, 0.3 for isolated packages.

Profiles are persisted with `INSERT OR REPLACE` for idempotency.

### Health signals (CodeHealthAnalyzer)

The `CodeHealthAnalyzer` queries module profiles and dependency edges to detect seven health signals, sorted by severity:

| Signal ID | Severity | Condition | Description |
|-----------|----------|-----------|-------------|
| C001 | HIGH | Mutual import edges A->B and B->A | Circular dependency between packages |
| C013 | HIGH | core/model/domain package with instability > 0.5 | Unstable core -- core packages should be stable |
| C020 | HIGH | instability > 0.8 AND fan-in > 3 | Hotspot -- unstable package with many dependents |
| C010 | MEDIUM | fan-in > 5 AND no test package detected | High fan-in package lacking test coverage |
| C012 | MEDIUM | total_files > 15 | God package -- too many files in one package |
| C014 | LOW | fan-in = 0 AND fan-out = 0 (excluding cli/main/test) | Orphan code -- isolated, potentially dead |
| C021 | LOW | fan-in > 5 AND inferred_purpose = "General purpose" | Documentation gap -- important package with unclear intent |

Each signal includes the affected module path, a human-readable description, and an actionable fix suggestion (e.g., "Extract shared types to a common module" for C001).

### Commands

```
synthesis code-graph describe                          # all module profiles
synthesis code-graph describe --module graph           # filter by substring
synthesis code-graph describe --instability            # sort by instability desc
synthesis code-graph describe --format json            # JSON output
synthesis code-graph describe --refresh                # re-extract + recompute first

synthesis code-graph health                            # all health signals
synthesis code-graph health --errors-only              # HIGH severity only
synthesis code-graph health --format json              # JSON output
synthesis code-graph health --refresh                  # re-extract + recompute first
```

---

## 5. CKG-3: Quality Gap Detection

**Commit:** `adc8212` -- 1,693 lines added, 28 tests
**Issue scope:** CKG-3.01 through CKG-3.05

### Gap types (QualityGapDetector)

The `QualityGapDetector` cross-references module profiles, dependency edges, and the filesystem to detect five structural quality gaps:

| Gap Type | Severity | Condition | Description |
|----------|----------|-----------|-------------|
| MISSING_TESTS | HIGH | Module has source files but no corresponding test package | No test coverage for production code |
| MISSING_INTERFACE | MEDIUM | fan-in > 5 and no `implements` edges in the package | High-coupling module without interface abstraction |
| UNDOCUMENTED_HIGH_VALUE | MEDIUM | fan-in > 8 and inferred_purpose = "General purpose" | Critical module with unclear responsibility |
| MISSING_README | LOW | total_files > 5 and no README.md in the module directory | Large module lacking documentation |
| MISSING_PACKAGE_INFO | LOW | fan-in > 3 and no package-info.java | Important module missing Javadoc entry point |

Test package detection uses two strategies: checking if any package name contains "test" and checking if source files reside under `src/test/`. Filesystem checks verify the actual presence of `package-info.java` and `README.md` files.

### Completeness scoring (CompletenessScorer)

Each module receives a completeness score between 0.0 and 1.0, computed as:

```
score = max(0.0, 1.0 - sum(penalties))
```

Penalty weights by severity:

| Severity | Penalty |
|----------|---------|
| HIGH | -0.30 |
| MEDIUM | -0.15 |
| LOW | -0.05 |

A module with one HIGH gap and one MEDIUM gap scores `1.0 - 0.30 - 0.15 = 0.55`. A module with no gaps scores 1.0. Scores cannot go below 0.0.

The `completeness_score` column is added to `module_profiles` lazily via `ALTER TABLE` (idempotent -- the column addition is caught and ignored if it already exists).

### Commands

```
synthesis code-graph gaps                              # all gaps
synthesis code-graph gaps --type MISSING_TESTS         # filter by gap type
synthesis code-graph gaps --severity HIGH              # filter by severity
synthesis code-graph gaps --module cli                 # filter by module substring
synthesis code-graph gaps --score                      # show completeness scores
synthesis code-graph gaps --format json                # JSON output
synthesis code-graph gaps --refresh                    # re-detect gaps first
```

---

## 6. CKG-4: DAG Visualization

**Commit:** `a25d788` -- 1,332 lines added, 29 tests
**Issue scope:** CKG-4.01 through CKG-4.03

### Architectural layers (DagRenderer)

The `DagRenderer` infers a 4-tier architectural layer system from the instability metric:

| Layer | Name | Instability Range | Role |
|-------|------|-------------------|------|
| 1 | Foundation | 0.00 -- 0.25 | Stable base packages (models, core domain) |
| 2 | Core Services | 0.26 -- 0.50 | Service layer, business logic |
| 3 | Application | 0.51 -- 0.75 | Application-level coordination |
| 4 | Entry/CLI | 0.76 -- 1.00 | Entry points, command-line handlers |

This layering is derived from Martin's observation that stable packages (low instability, many dependents) naturally form foundations, while unstable packages (high instability, few dependents) form entry points.

### ASCII rendering

The default DAG output groups packages by layer, showing fan-in, fan-out, and instability for each. Packages are annotated with contextual markers:
- Check mark for stable packages (instability < 0.6)
- Warning indicator for unexpectedly unstable packages (instability > 0.6, not a CLI package)
- "(expected)" for CLI/main packages with high instability

The output also summarizes circular dependencies and layer violations.

### Mermaid output

With `--format mermaid`, the renderer produces a `graph TD` Mermaid diagram with labeled nodes showing stability values and directed edges for dependencies. Output is capped at 30 packages for readability.

### Stable Dependencies Principle violations

Layer violations occur when a **more stable** package (lower instability) depends on a **less stable** package (higher instability). This violates Robert C. Martin's Stable Dependencies Principle: dependencies should flow in the direction of stability. The `--layers` flag and the default DAG output both report these violations.

### Hotspot detection

Hotspot packages are those with instability > 0.7 AND fan-in > 2. These packages are structurally risky: they are depended upon by multiple other packages but are themselves highly volatile. Changes in hotspot packages cascade widely.

### Commands and flags

```
synthesis code-graph                                   # full DAG (ASCII, layered)
synthesis code-graph --format mermaid                  # Mermaid graph output
synthesis code-graph --cycles                          # circular dependency pairs
synthesis code-graph --hotspots                        # unstable high-coupling packages
synthesis code-graph --instability                     # all packages sorted by instability
synthesis code-graph --layers                          # layer diagram with violations
synthesis code-graph --cross-format                    # SQL/YAML->Java links
```

---

## 7. CLI Reference

The `synthesis code-graph` command family (alias: `synthesis cg`):

| Command | Description |
|---------|-------------|
| `code-graph` | Show package dependency DAG (default: ASCII) |
| `code-graph extract` | Extract dependencies from source files to SQLite |
| `code-graph describe` | Show module profiles (fan-in/out, instability, purpose) |
| `code-graph health` | Detect code health signals |
| `code-graph gaps` | Show quality gaps and completeness scores |

### Global options (top-level `code-graph`)

| Flag | Description |
|------|-------------|
| `--cycles` | Show circular dependency pairs |
| `--hotspots` | Show packages with instability > 0.7 and fan-in > 2 |
| `--instability` | List all packages sorted by instability descending |
| `--layers` | Show layer diagram with SDP violations |
| `--cross-format` | Show SQL/YAML to Java cross-format links |
| `--format mermaid` | Output Mermaid graph instead of ASCII |

### extract options

| Flag | Description |
|------|-------------|
| `--incremental` | Only re-extract changed files |
| `--stats` | Show current graph statistics without extracting |
| `--dry-run` | Show file counts without writing to database |

### describe options

| Flag | Description |
|------|-------------|
| `--module <name>` | Filter by module name substring |
| `--instability` | Sort by instability descending |
| `--format json` | JSON output |
| `--refresh` | Re-extract and recompute profiles before display |

### health options

| Flag | Description |
|------|-------------|
| `--errors-only` | Show only HIGH severity signals |
| `--format json` | JSON output |
| `--refresh` | Re-extract and recompute before analysis |

### gaps options

| Flag | Description |
|------|-------------|
| `--type <TYPE>` | Filter by gap type (e.g., MISSING_TESTS) |
| `--severity <LEVEL>` | Filter by severity (HIGH, MEDIUM, LOW) |
| `--module <name>` | Filter by module name substring |
| `--score` | Show completeness score per module |
| `--format json` | JSON output |
| `--refresh` | Re-detect gaps before display |

---

## 8. Database Schema

**Migration:** `V13__code_knowledge_graph.sql` (81 lines)

### code_dependencies

Stores class-level dependency edges.

| Column | Type | Description |
|--------|------|-------------|
| workspace_path | TEXT | Workspace root path |
| source_file | TEXT | Source file relative path |
| source_class | TEXT | Fully qualified source class name |
| source_package | TEXT | Source package name |
| target_file | TEXT | Target file (nullable, may be external) |
| target_class | TEXT | Target class name |
| target_package | TEXT | Target package name |
| dependency_type | TEXT | import, extends, implements, annotation |
| is_external | INTEGER | 1 if target is outside the workspace |
| last_computed | INTEGER | Unix epoch timestamp |

**UNIQUE:** (workspace_path, source_file, target_class, target_package)
**Indexes:** workspace, source_file, target_class+package, source_package, target_package

### module_profiles

Package-level aggregated profiles.

| Column | Type | Description |
|--------|------|-------------|
| workspace_path | TEXT | Workspace root path |
| module_path | TEXT | Slash-separated module path |
| package_name | TEXT | Dot-separated Java package name |
| inferred_purpose | TEXT | Heuristic purpose label |
| fan_in | INTEGER | Count of packages importing from this one |
| fan_out | INTEGER | Count of packages this one imports |
| instability | REAL | fanOut / (fanIn + fanOut), 0.0-1.0 |
| total_files | INTEGER | Source files in this package |
| confidence | REAL | Profile confidence (0.3 or 0.8) |
| completeness_score | REAL | Gap-weighted score (added lazily via ALTER TABLE) |
| last_computed | INTEGER | Unix epoch timestamp |

**UNIQUE:** (workspace_path, module_path)
**Indexes:** workspace, instability

### cross_format_links

Cross-format entity relationships.

| Column | Type | Description |
|--------|------|-------------|
| workspace_path | TEXT | Workspace root path |
| source_file | TEXT | Source file (e.g., V13__code_knowledge_graph.sql) |
| target_file | TEXT | Target file (e.g., CodeGraphRepository.java) |
| link_type | TEXT | Link type (e.g., sql_to_java) |
| entity_name | TEXT | Entity name bridging the formats |
| last_computed | INTEGER | Unix epoch timestamp |

**UNIQUE:** (workspace_path, source_file, target_file, entity_name)
**Indexes:** workspace, source_file, target_file

### code_quality_gaps

Detected structural quality gaps.

| Column | Type | Description |
|--------|------|-------------|
| workspace_path | TEXT | Workspace root path |
| module_path | TEXT | Affected module path |
| gap_type | TEXT | Gap type identifier |
| description | TEXT | Human-readable description |
| severity | TEXT | HIGH, MEDIUM, or LOW |
| file_path | TEXT | Related file path (nullable) |
| suggestion | TEXT | Actionable fix suggestion |
| last_computed | INTEGER | Unix epoch timestamp |

**UNIQUE:** (workspace_path, module_path, gap_type, file_path)
**Indexes:** workspace, gap_type, severity

---

## 9. Test Coverage

| Phase | Test Class | Count |
|-------|-----------|-------|
| CKG-1 | CodeGraphCommandTest | 14 |
| CKG-1 | CodeGraphExtractorTest | 12 |
| CKG-1 | CodeGraphRepositoryTest | 10 |
| CKG-1 | CodeKnowledgeGraphMigrationTest | 6 |
| CKG-2 | CodeGraphDescribeCommandTest | 10 |
| CKG-2 | CodeGraphHealthCommandTest | 10 |
| CKG-2 | CodeHealthAnalyzerTest | 12 |
| CKG-2 | ModuleProfileComputerTest | 7 |
| CKG-3 | CodeGraphGapsCommandTest | 10 |
| CKG-3 | CompletenessScoreTest | 8 |
| CKG-3 | QualityGapDetectorTest | 10 |
| CKG-4 | CodeGraphDagCommandTest | 12 |
| CKG-4 | DagRendererTest | 17 |
| **Total** | | **138** |

All tests pass with 0 failures and 0 errors.

---

## 10. Usage Examples

### Full workflow: extract, profile, detect, visualize

```bash
# Step 1: Extract dependency graph from source files
synthesis code-graph extract -d /path/to/project
#   Files processed:    87
#   Dependencies found: 423
#   Cross-format links: 12
#   Packages found:     11
#   External deps:      156
#   Elapsed:            340 ms

# Step 2: View module profiles
synthesis code-graph describe -d /path/to/project
#   Module Profiles (11 packages)
#
#     io/exoreaction/synthesis/cli
#       Purpose:     CLI command implementations
#       Fan-in:      0   Fan-out: 7   Instability: 1.00 (expected for CLI)
#       Files:       18
#
#     io/exoreaction/synthesis/core
#       Purpose:     Core domain model
#       Fan-in:      6   Fan-out: 1   Instability: 0.14 (checkmark)
#       Files:       5

# Step 3: Check code health
synthesis code-graph health -d /path/to/project
#   Code Health Signals (3 issues)
#
#     [HIGH] C001_CIRCULAR_DEPENDENCY -- io/exoreaction/synthesis/graph
#       graph <-> cli mutual imports detected (4 import edges each way)
#       Suggestion: Extract shared types to a common module

# Step 4: Detect quality gaps with scores
synthesis code-graph gaps --score -d /path/to/project
#   Quality Gaps (5 gaps across 3 modules)
#
#     io/exoreaction/synthesis/staging  [score: 0.70]
#       [HIGH] MISSING_TESTS
#         Module has 8 source file(s) but no corresponding test files
#         -> Add test classes in src/test/ for this module's classes

# Step 5: Visualize the dependency DAG
synthesis code-graph -d /path/to/project
#   Package dependency graph (11 packages, 34 edges)
#
#     Layer 1 -- Foundation (instability 0.00-0.25)
#       io/exoreaction/synthesis/core  fan-in: 6  fan-out: 1  instability: 0.14 (checkmark)
#
#     Layer 4 -- Entry/CLI (instability 0.76-1.00)
#       io/exoreaction/synthesis/cli   fan-in: 0  fan-out: 7  instability: 1.00 (expected)
#
#     Cycles detected: 1
#     Layer violations: 2

# Step 6: Export Mermaid for documentation
synthesis code-graph --format mermaid -d /path/to/project > architecture.mmd
```

### Targeted queries

```bash
# Find circular dependencies
synthesis code-graph --cycles -d /path/to/project

# Identify risky hotspot packages
synthesis code-graph --hotspots -d /path/to/project

# Find all modules missing tests
synthesis code-graph gaps --type MISSING_TESTS -d /path/to/project

# Show only high-severity health issues
synthesis code-graph health --errors-only -d /path/to/project

# JSON output for CI integration
synthesis code-graph health --format json -d /path/to/project

# View cross-format links (SQL->Java, YAML->Java)
synthesis code-graph --cross-format -d /path/to/project
```

### Maintenance integration

The code graph is automatically updated during `synthesis maintain` runs (Phase 10). For populated workspaces, the pipeline runs:

1. Incremental dependency extraction (changed files only)
2. Module profile computation
3. Quality gap detection
4. Completeness scoring

This ensures the code graph stays current without manual re-extraction.

---

## Source Files

### Production code (10 files)

| File | Lines | Purpose |
|------|-------|---------|
| `graph/CodeGraphExtractor.java` | 353 | Parse source files, extract dependencies |
| `graph/CodeGraphRepository.java` | 467 | SQLite persistence layer |
| `graph/CodeGraphStats.java` | 29 | Extraction statistics record |
| `graph/ModuleProfileComputer.java` | 264 | Package-level profile aggregation |
| `graph/CodeHealthAnalyzer.java` | 378 | 7 health signal detectors |
| `graph/CodeHealthSignal.java` | 24 | Health signal record |
| `graph/QualityGapDetector.java` | 397 | 5 quality gap detectors |
| `graph/QualityGap.java` | 25 | Quality gap record |
| `graph/CompletenessScorer.java` | 117 | Weighted completeness scoring |
| `graph/DagRenderer.java` | 413 | ASCII + Mermaid DAG rendering |
| `cli/CodeGraphCommand.java` | 931 | CLI command with 4 subcommands |
| `db/migration/V13__code_knowledge_graph.sql` | 81 | Database migration |

### Test code (13 test classes, 138 tests)

All test classes reside under `src/test/java/io/exoreaction/synthesis/`.
