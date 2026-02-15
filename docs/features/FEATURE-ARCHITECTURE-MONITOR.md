# Feature: Continuous Architecture Intelligence

**Status:** Implemented | **Priority:** 2 | **Version:** 1.0.4-SNAPSHOT

## Overview

Continuous Architecture Intelligence provides automated detection of anti-patterns, coupling issues, and quality gaps in your codebase. It runs as a CLI command, integrates with the daemon (watch) mode for real-time monitoring, and publishes diagnostics through the LSP server for in-IDE alerts.

## Problem

Architecture decay happens gradually:
- Classes grow beyond reasonable size ("god classes")
- Circular dependencies creep in between modules
- Test coverage erodes as new files are added without tests
- Documentation gaps grow as code outpaces docs
- Dead code accumulates, increasing maintenance burden

Manual architecture reviews catch these issues periodically, but continuous monitoring catches them immediately.

## Solution

The `ArchitectureMonitor` analyzes the Synthesis index to detect seven categories of architecture issues, with three levels of severity.

## Architecture

### Core Components

**ArchitectureMonitor** (`src/main/java/io/exoreaction/synthesis/architecture/ArchitectureMonitor.java`)
- Main analysis engine
- Methods: `analyze(SearchIndex)`, `analyzeFile(Path, SearchIndex)`, `detectGodClasses()`, `detectCircularDependencies()`, `detectDeadCode()`, `detectMissingDocumentation()`, `detectTestCoverageGaps()`
- Uses DFS cycle detection for circular dependency finding
- Integrates with `RelateCommand` for relationship analysis
- Configurable threshold: `GOD_CLASS_LINE_THRESHOLD = 1000`

**ArchitectureAlert** (`src/main/java/io/exoreaction/synthesis/architecture/ArchitectureAlert.java`)
- Record class representing a single architecture issue
- Fields: `severity`, `category`, `filePath`, `message`, `metadata`
- Methods: `toSummaryLine()`, `toMap()`

### Alert Categories

| Category | Description | Detection Method |
|----------|-------------|-----------------|
| `GOD_CLASS` | Files exceeding line count threshold | Line count analysis |
| `CIRCULAR_DEPENDENCY` | Cyclic import/reference chains | DFS cycle detection on relationship graph |
| `DEAD_CODE` | Unreferenced files (excluding entry points and tests) | Incoming reference analysis |
| `MISSING_DOCUMENTATION` | Directories with 3+ code files but no README | Directory content analysis |
| `TEST_COVERAGE_GAP` | Source files without corresponding test files | Naming convention matching |
| `HIGH_COUPLING` | Files with excessive incoming references | Reference count analysis |
| `FEATURE_ENVY` | Files referencing another module more than their own | Cross-module reference analysis |

### Severity Levels

| Severity | Meaning | Examples |
|----------|---------|---------|
| `ERROR` | Critical architecture issue requiring attention | God class >2000 lines, circular dependencies |
| `WARNING` | Significant issue that should be addressed | God class >1000 lines, missing docs, test gaps |
| `INFO` | Informational finding | Dead code, potential feature envy |

### Smart Filtering

The monitor applies intelligent filtering to reduce false positives:
- **Entry points** are excluded from dead code detection (Main.java, Application.java, etc.)
- **Test files** are excluded from dead code and coverage gap analysis
- **Configuration files** are excluded from dead code detection
- **Small directories** (<3 code files) are excluded from documentation checks

## Integration Points

### CLI Command

```bash
synthesis architecture analyze [--severity warning] [--category GOD_CLASS] [--format json] [--limit 50]
```

Located at: `src/main/java/io/exoreaction/synthesis/cli/ArchitectureCommand.java`

Features:
- Colored terminal output with severity prefixes
- Grouped output by category
- JSON output for CI/CD integration
- Severity and category filtering
- Configurable result limit

### Daemon Mode (Watch)

Located at: `src/main/java/io/exoreaction/synthesis/cli/WatchCommand.java`

When `synthesis watch` detects file changes:
1. Index is updated with the changed files
2. For code files, `ArchitectureMonitor.analyzeFile()` runs
3. Architecture alerts are logged with `[ARCH]` prefix in verbose mode
4. Provides real-time feedback as developers work

### LSP Server

Located at: `src/main/java/io/exoreaction/synthesis/lsp/SynthesisTextDocumentService.java`

The LSP integration:
1. On `didOpen` or `didSave` for code files, runs architecture analysis
2. Converts `ArchitectureAlert` severity to `DiagnosticSeverity`
3. Publishes diagnostics with source `"synthesis-architecture"`
4. Diagnostics appear in the IDE's Problems panel

Severity mapping:
- `ArchitectureAlert.Severity.ERROR` -> `DiagnosticSeverity.Error`
- `ArchitectureAlert.Severity.WARNING` -> `DiagnosticSeverity.Warning`
- `ArchitectureAlert.Severity.INFO` -> `DiagnosticSeverity.Information`

## Testing

Tests located at: `src/test/java/io/exoreaction/synthesis/architecture/ArchitectureMonitorTest.java`

15 tests covering:
- God class detection (small files, large files, extreme sizes)
- Error severity for extreme file sizes (>2x threshold)
- Missing documentation detection (with and without README)
- Test coverage gap detection (source with/without matching test)
- Dead code detection (unreferenced files)
- Entry point and test file exclusion from dead code
- Alert model (`toSummaryLine()`, `toMap()`)
- Severity ordering

## Configuration

Currently, thresholds are defined as constants in `ArchitectureMonitor`:

| Constant | Value | Description |
|----------|-------|-------------|
| `GOD_CLASS_LINE_THRESHOLD` | 1000 | Lines above which a file is flagged |
| `CODE_FILES_FOR_DOCS` | 3 | Minimum code files in directory before requiring README |

Future enhancement: Make thresholds configurable via `.synthesis/config.yaml`.

## Performance

| Operation | Typical Time | Notes |
|-----------|-------------|-------|
| Full analysis (1,000 files) | 0.5-2s | All detectors |
| Full analysis (10,000 files) | 2-8s | Scales with file count |
| Single file analysis | 0.1-0.5s | Used by watch/LSP |
| God class detection | O(n) | One pass through files |
| Circular dependency detection | O(V + E) | DFS on dependency graph |
| Dead code detection | O(n * m) | n = files, m = avg references |

## CI/CD Integration

Use JSON output in CI pipelines:

```bash
# Fail build on architecture errors
synthesis architecture analyze --severity error --format json > arch-report.json
errors=$(jq '.totalAlerts' arch-report.json)
if [ "$errors" -gt 0 ]; then
  echo "Architecture errors found!"
  jq '.alerts[]' arch-report.json
  exit 1
fi
```

## Future Enhancements

- Configurable thresholds via `.synthesis/config.yaml`
- Trend tracking (alert count over time)
- Architecture Decision Records (ADR) integration
- Custom rule definitions
- Team-specific threshold profiles
- Integration with code review tooling
