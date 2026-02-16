# Sub-Workspace Architecture Design Document

**Version:** 1.4.0
**Date:** 2026-02-16
**Author:** Thor Henning Hetland / eXOReaction
**Status:** Implemented

---

## 1. Problem Statement

Large Synthesis workspaces (like `~/Documents` with 8,934+ files) contain multiple
logical partitions: company directories, client projects, staging areas, and
cross-cutting concerns. The existing repository-based partitioning (`repos.json`)
was too rigid -- it required files to live in separate Git repositories and could
not represent organizational hierarchies, staging workflows, or mixed-content
workspaces.

## 2. Solution: Sub-Workspaces

Sub-workspaces introduce a path-prefix-based logical partitioning layer within
a single Synthesis workspace. Each sub-workspace:

- Has a **name** and **path prefix** (e.g., `eXOReaction` maps to `eXOReaction/`)
- Is tagged in the Lucene index via a `subWorkspace` field
- Supports **scoped search** (`--scope eXOReaction`)
- Supports **aggregated views** (`--aggregate`)
- Can be of type `staging` for time-limited incoming files
- Inherits scan configuration from the parent, with optional overrides

### Key Design Decisions

1. **Path-prefix matching** with longest-match-wins (not glob/regex) for deterministic resolution
2. **Backward compatible** -- workspaces without sub-workspace config continue working unchanged
3. **Index-level tagging** -- sub-workspace is a stored StringField in Lucene, enabling filter queries
4. **Database-level tracking** -- staging files, stats, and registry stored in SQLite
5. **Non-destructive migration** -- `synthesis migrate-repos` creates backups before changes

## 3. Architecture

### 3.1 Data Model

```
SynthesisConfig
  +-- subWorkspaces: List<SubWorkspaceConfig>
  |     +-- name: String       (e.g., "eXOReaction")
  |     +-- path: String       (e.g., "eXOReaction")
  |     +-- description: String
  |     +-- type: String       ("general", "staging", "source-code", "documents")
  |     +-- tags: List<String>
  |     +-- includePatterns: List<String>  (override parent)
  |     +-- excludePatterns: List<String>  (merge with parent)
  |
  +-- staging: StagingConfig
        +-- enabled: boolean
        +-- retentionDays: int
        +-- autoClassify: boolean
        +-- classificationThreshold: double
        +-- cleanupExpired: boolean
```

### 3.2 Resolution Algorithm

```java
SubWorkspaceResolver.resolve("eXOReaction/clients/SpareBank1/README.md")
  1. Check sub-workspace paths: ["eXOReaction", "Quadim", "Cantara"]
  2. Match: "eXOReaction/clients/SpareBank1/README.md".startsWith("eXOReaction/") -> YES
  3. Longest match wins (if multiple match)
  4. Return: "eXOReaction"
```

### 3.3 Index Integration

```
Lucene Document fields:
  path          = /home/totto/Documents/eXOReaction/clients/SpareBank1/README.md
  relativePath  = eXOReaction/clients/SpareBank1/README.md
  subWorkspace  = eXOReaction    <-- NEW FIELD
  fileType      = MARKDOWN
  ...
```

Search with sub-workspace filter uses `BooleanQuery.Builder`:
```java
booleanQuery.add(contentQuery, BooleanClause.Occur.MUST);
booleanQuery.add(new TermQuery(new Term("subWorkspace", "eXOReaction")),
    BooleanClause.Occur.FILTER);
```

### 3.4 Database Schema (V4 Migration)

```sql
-- Sub-workspace registry
CREATE TABLE sub_workspaces (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    name TEXT NOT NULL,
    path_prefix TEXT NOT NULL,
    description TEXT,
    type TEXT NOT NULL DEFAULT 'general',
    tags TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE(workspace_path, name)
);

-- Staging file tracking
CREATE TABLE staging_files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workspace_path TEXT NOT NULL,
    sub_workspace TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    file_type TEXT,
    content_hash TEXT,
    classified_org TEXT,
    classification_confidence REAL DEFAULT 0.0,
    suggested_destination TEXT,
    status TEXT NOT NULL DEFAULT 'pending',
    ingested_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    promoted_at INTEGER,
    promoted_to TEXT,
    UNIQUE(workspace_path, relative_path)
);

-- Per-sub-workspace statistics
CREATE TABLE sub_workspace_stats (...);
```

## 4. CLI Interface

### 4.1 Search

```bash
# Scoped search
synthesis search "testing strategy" --scope eXOReaction

# Aggregated view
synthesis search "config" --aggregate

# Combined with other filters
synthesis search "api" --scope Quadim --type CODE

# Multi-workspace + scope
synthesis search "deployment" --all --scope eXOReaction
```

### 4.2 Staging

```bash
# List staged files
synthesis staging list
synthesis staging list --status pending

# Promote a file
synthesis staging promote staging/incoming/report.pdf --to eXOReaction

# Ingest new files
synthesis staging ingest

# Process expired files
synthesis staging expire --dry-run

# Statistics
synthesis staging stats
```

### 4.3 Migration

```bash
# Migrate repos and orgs to sub-workspaces
synthesis migrate-repos
synthesis migrate-repos --dry-run
synthesis migrate-repos --repos-only
synthesis migrate-repos --orgs-only
```

### 4.4 List (Tree View)

```
synthesis list
  [docs]   Totto's Knowledge Base
  Path:        /home/totto/Documents
  Sub-spaces:  3
               +-- eXOReaction [general]
               +-- Quadim [general]
               +-- Cantara [general]
  Indexed:     +
  ...
```

### 4.5 Which (Sub-Workspace Tags)

```
synthesis which SynthesisConfig --verbose
  [source] Synthesis (/src/exoreaction/Synthesis)
      src/main/java/.../SynthesisConfig.java [core]
```

## 5. Configuration Examples

### 5.1 Multi-Company Knowledge Base

```yaml
workspace:
  name: "Totto's Knowledge Base"
  type: documents

subWorkspaces:
  - name: "eXOReaction"
    path: "eXOReaction"
    description: "eXOReaction company files"
    tags: ["company", "core"]
  - name: "Quadim"
    path: "Quadim"
    description: "Quadim SaaS platform"
    tags: ["company", "product"]
  - name: "Cantara"
    path: "Cantara"
    description: "Open source foundations"
    tags: ["foundation", "open-source"]
```

### 5.2 With Staging Area

```yaml
workspace:
  name: "Totto's Knowledge Base"

subWorkspaces:
  - name: "eXOReaction"
    path: "eXOReaction"
  - name: "incoming"
    path: "staging/incoming"
    type: staging
    description: "Temporary holding for new files"
    tags: ["staging", "temp"]

staging:
  enabled: true
  retentionDays: 14
  autoClassify: true
  classificationThreshold: 0.6
  cleanupExpired: false
```

### 5.3 Source Code Workspace

```yaml
workspace:
  name: "eXOReaction Source"
  type: source-code

subWorkspaces:
  - name: "Synthesis"
    path: "exoreaction/Synthesis"
    description: "Knowledge infrastructure tool"
    type: source-code
    tags: ["product", "java"]
    excludePatterns:
      - "**/target/**"
  - name: "lib-pcb"
    path: "exoreaction/lib-pcb"
    description: "PCB design library"
    type: source-code
    tags: ["product", "java"]
```

## 6. Migration Guide

### From repos.json

The `synthesis migrate-repos` command automatically converts repository
entries to sub-workspace configurations:

1. Run `synthesis migrate-repos --dry-run` to preview changes
2. Run `synthesis migrate-repos` to apply
3. Run `synthesis scan` to re-index with sub-workspace tags
4. Verify with `synthesis search --aggregate` or `synthesis list`

The original `repos.json` is backed up as `repos.json.pre-migration`.

### From organizations.json

Organizations discovered by `synthesis org scan` are also migrated:

1. Run `synthesis migrate-repos` (includes organizations by default)
2. Or run `synthesis migrate-repos --orgs-only` for just organizations

### Manual Migration

Add sub-workspace entries directly to `.synthesis/config.yaml`:

```yaml
subWorkspaces:
  - name: "MyProject"
    path: "path/to/project"
    description: "Description"
    type: general
    tags: ["project"]
```

## 7. Implementation Files

### Phase 1: Foundation
- `SynthesisConfig.java` -- SubWorkspaceConfig and StagingConfig inner classes
- `DocumentFields.java` -- SUB_WORKSPACE constant
- `FileIndexer.java` -- createDocument overload with subWorkspace parameter
- `SearchResult.java` -- subWorkspace field added to record
- `SearchIndex.java` -- searchWithSubWorkspace() and listAllWithSubWorkspace() methods
- `ConfigLoader.java` -- validation, resolveSubWorkspaceScanConfig(), resolveSubWorkspace()
- `V4__sub_workspaces.sql` -- database migration

### Phase 2: Scanning and Tagging
- `SubWorkspaceResolver.java` -- central resolution logic
- `ScanCommand.java` -- sub-workspace tagging during indexing
- `WatchCommand.java` -- sub-workspace resolution in processChanges()
- `MaintainCommand.java` -- sub-workspace tagging in applyChanges()
- `InitCommand.java` -- --auto-discover flag for sub-workspace discovery

### Phase 3: Search and Navigation
- `SearchCommand.java` -- --scope and --aggregate options, printAggregatedResults()
- `MultiWorkspaceSearch.java` -- searchWithSubWorkspace() method
- `SynthesisToolHandler.java` -- subWorkspace parameter in MCP search
- `ListWorkspacesCommand.java` -- tree view of sub-workspaces
- `WhichCommand.java` -- sub-workspace tags in results

### Phase 4: Staging
- `StagingManager.java` -- staging lifecycle (ingest, classify, promote, expire)
- `StagingCommand.java` -- CLI with list, promote, ingest, expire, stats subcommands
- `SynthesisApp.java` -- StagingCommand registration

### Phase 5: Migration
- `RepositoryManager.java` -- @Deprecated annotation with migration guidance
- `OrganizationScanner.java` -- toSubWorkspaceConfigs() conversion method
- `MigrateReposCommand.java` -- migration CLI with dry-run, backup, dedup
- `SynthesisApp.java` -- MigrateReposCommand registration

## 8. Testing

All 835 existing tests pass with 0 failures after the full implementation.
The changes are backward compatible: workspaces without sub-workspace
configuration continue to function identically.

---

*This document describes the sub-workspace architecture as implemented in
Synthesis v1.4.0. For questions or issues, refer to the source code or
the project README.*
