# KCP (Knowledge Context Protocol) v0.5 — Release Notes

**Version:** v1.19.0
**Date:** March 1, 2026
**PRs:** #284 (Phase 2: Detection) · #285 (Phase 3: Persistence) · #286 (Phase 4: Export) · #287 (Phase 5: Knowledge Graph)
**Tests:** 4,153 total (JUnit 5) — 46 new KCP-specific tests

---

## 1. Overview

KCP (Knowledge Context Protocol) is a structured YAML manifest format (`knowledge.yaml`) that
gives AI agents a curated reading list for a repository: which files matter, what each one is
for, and the recommended read order. Without a manifest, an agent must scan all files to
understand a codebase. With one, it reads the manifest first and immediately knows where to focus.

Synthesis now provides full-stack KCP v0.5 support across four capabilities:

| Capability | Command | What it does |
|-----------|---------|-------------|
| Detection & Parsing | `synthesis scan` / `synthesis maintain` | Detects `knowledge.yaml`, extracts `KcpUnit` + `KcpRelationship` records |
| SQLite Persistence | (automatic) | Stores KCP data in V17 tables across scan/maintain cycles |
| Manifest Export | `synthesis export --format kcp` | Generates conformant `knowledge.yaml` from the Lucene index |
| Knowledge Graph | `synthesis kg` | Surfaces KCP units as first-class nodes (ASCII, Mermaid, JSON) |

**Why this matters for agents:**

An agent consuming a Synthesis-indexed workspace can now:
1. Check `synthesis kg --format json | jq '.kcpUnits'` to discover all KCP manifests.
2. Use `synthesis export --format kcp` to generate a manifest for any repo that does not have one.
3. Use the manifest's `triggers` and `intent` fields to route agent queries to the right files.
4. Query KCP units via MCP (the `knowledge-graph` tool returns `kcpUnits` + `kcpRelationships`).

---

## 2. Implementation Phases

| Phase | PR | Scope |
|-------|-----|-------|
| Phase 2 | #284 | Detection + parsing (`YamlAnalyzer`, `KcpUnit`, `KcpRelationship`) |
| Phase 3 | #285 | SQLite persistence (V17 migration, `KcpRepository`, scan/maintain hooks) |
| Phase 4 | #286 | Manifest export (`ExportCommand.exportAsKcp()`) |
| Phase 5 | #287 | Knowledge graph integration (`KnowledgeGraphCommand` enhancements) |

---

## 3. Phase 2: Detection & Parsing (PR #284)

### Detection heuristic

`YamlAnalyzer.extractKcpManifestInfo()` identifies a YAML file as a KCP manifest when ALL THREE
conditions hold:

1. `filename == "knowledge.yaml"` — only the canonical filename is recognized
2. Top-level key `units` is a YAML sequence (list)
3. Top-level key `project` OR `id` exists

Files failing any condition are indexed as generic YAML. This three-condition approach avoids
false positives on unrelated `knowledge.yaml` files while matching all valid KCP v0.5 manifests.

When detection fires, the file receives `yamlType = "kcp-manifest"` in `AnalysisResult.metrics()`.

### Extracted data

**KcpUnit fields extracted per unit:**

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | Slug, unique within manifest |
| `path` | String | File path relative to manifest |
| `intent` | String | One-sentence description |
| `scope` | String | `global` / `module` / `focused` / `comprehensive` |
| `audience` | List<String> | `developer`, `agent`, or both |
| `format` | String | `markdown`, `pdf`, `openapi`, etc. |
| `kind` | String | `policy`, `schema`, or null (default knowledge) |
| `triggers` | List<String> | Up to 8 keywords |
| `validated` | String | Quoted ISO date |
| `updated` | String | Quoted ISO date |
| `hints` | Map<String,Object> | Additional hints from the hints block |

**KcpRelationship fields:**

| Field | Type | Notes |
|-------|------|-------|
| `fromUnit` | String | Source unit `id` |
| `toUnit` | String | Target unit `id` |
| `type` | String | `context`, `extends`, `summary_of` |

### Example: detection input and output

Input `knowledge.yaml`:
```yaml
kcp_version: "0.5"
project: my-service
language: en
indexing: open
hints:
  unit_count: 2
units:
  - id: overview
    path: README.md
    intent: "What is this service and why does it exist?"
    scope: global
    audience: [developer, agent]
    triggers: [overview, getting-started]
    validated: "2026-03-01"
  - id: api-ref
    path: docs/api.md
    intent: "API endpoint reference."
    scope: module
    audience: [developer]
    format: markdown
relationships:
  - from: api-ref
    to: overview
    type: context
```

Extracted:
- 1 `KcpManifestInfo` with project=`my-service`, unitCount=2, relationshipCount=1
- 2 `KcpUnit` records
- 1 `KcpRelationship` record

---

## 4. Phase 3: SQLite Persistence (PR #285)

### V17 migration

Migration `V17__kcp_tables.sql` adds three tables:

#### kcp_manifests

Stores one row per detected `knowledge.yaml` file.

| Column | Type | Description |
|--------|------|-------------|
| `workspace_path` | TEXT | Workspace root path |
| `file_path` | TEXT | Relative path to `knowledge.yaml` |
| `project_id` | TEXT | Value of `project` or `id` key |
| `kcp_version` | TEXT | KCP version string (e.g., `"0.5"`) |
| `unit_count` | INTEGER | Number of units in manifest |
| `relationship_count` | INTEGER | Number of relationships |
| `language` | TEXT | ISO language code |
| `indexing` | TEXT | `open`, `restricted`, etc. |
| `last_indexed` | INTEGER | Unix epoch timestamp |

**UNIQUE:** `(workspace_path, file_path)`

#### kcp_units

Stores one row per unit within a manifest.

| Column | Type | Description |
|--------|------|-------------|
| `workspace_path` | TEXT | Workspace root path |
| `manifest_file` | TEXT | Relative path to parent `knowledge.yaml` |
| `unit_id` | TEXT | Unit slug (unique within manifest) |
| `unit_path` | TEXT | File path relative to manifest |
| `intent` | TEXT | One-sentence description |
| `scope` | TEXT | `global`, `module`, `focused`, `comprehensive` |
| `audience_json` | TEXT | JSON array of audience strings |
| `format` | TEXT | File format hint |
| `kind` | TEXT | `policy`, `schema`, or NULL |
| `triggers_json` | TEXT | JSON array of trigger keywords |
| `hints_json` | TEXT | JSON object of additional hints |
| `validated` | TEXT | Quoted ISO date string |
| `updated` | TEXT | Quoted ISO date string |
| `last_indexed` | INTEGER | Unix epoch timestamp |

**UNIQUE:** `(workspace_path, manifest_file, unit_id)`

#### kcp_relationships

Stores one row per relationship between units.

| Column | Type | Description |
|--------|------|-------------|
| `workspace_path` | TEXT | Workspace root path |
| `manifest_file` | TEXT | Relative path to parent `knowledge.yaml` |
| `from_unit` | TEXT | Source unit `id` |
| `to_unit` | TEXT | Target unit `id` |
| `type` | TEXT | Relationship type (`context`, `extends`, `summary_of`) |
| `last_indexed` | INTEGER | Unix epoch timestamp |

**UNIQUE:** `(workspace_path, manifest_file, from_unit, to_unit)`

### KcpRepository API

`KcpRepository` (`io.exoreaction.synthesis.kcp`) provides the persistence layer:

| Method | Description |
|--------|-------------|
| `upsertFromAnalysis(conn, workspacePath, metadata, analysis)` | Idempotent upsert for manifest + units + relationships |
| `deleteForManifest(conn, workspacePath, filePath)` | Remove manifest + all its units + relationships |
| `getManifests(conn, workspacePath)` | List all manifests for a workspace |
| `getUnitsForManifest(conn, workspacePath, filePath)` | List units for a specific manifest |
| `getRelationshipsForManifest(conn, workspacePath, filePath)` | List relationships for a manifest |
| `getAllUnits(conn, workspacePath)` | All units across all manifests (used by kg command) |
| `getAllRelationships(conn, workspacePath)` | All relationships (used by kg command) |

All upserts use `INSERT OR REPLACE` semantics — idempotent and safe to re-run.

### Scan and maintain hooks

- **`ScanCommand`**: After each YAML file is analyzed, calls `KcpRepository.upsertFromAnalysis()` if `yamlType == "kcp-manifest"`.
- **`MaintainCommand`**: On changed/added `knowledge.yaml` files, upserts updated KCP data. On deleted `knowledge.yaml` files, calls `deleteForManifest()` to remove stale rows.
- No manual action needed — KCP data stays current automatically.

### DTO records

The `kcp` package adds four Java records used across the persistence pipeline:

| Record | Fields | Purpose |
|--------|--------|---------|
| `KcpManifestInfo` | projectId, kcpVersion, unitCount, relCount, language, indexing | Manifest-level metadata |
| `KcpUnit` | unitId, path, intent, scope, audience, format, kind, triggers, hints, validated, updated | Per-unit data |
| `KcpRelationship` | fromUnit, toUnit, type | Unit relationship edge |
| `KcpManifestRecord` | All kcp_manifests columns as Java record | Read-back from DB |

---

## 5. Phase 4: Manifest Export (PR #286)

### Command syntax

```bash
# Generate manifest from all MARKDOWN files (default filter)
synthesis export --format kcp

# Write to file
synthesis export --format kcp -o knowledge.yaml

# Alias
synthesis export --format knowledge-context-protocol -o knowledge.yaml

# Include code files too
synthesis export --format kcp --type CODE
```

The exporter queries the Lucene index directly — no AI, no network, no database read.

### Generated header fields

| Field | Value |
|-------|-------|
| `kcp_version` | `"0.5"` |
| `language` | `en` (fixed for now) |
| `indexing` | `open` |
| `hints.unit_count` | Count of units written |

### Per-unit inference

Each indexed file becomes one unit. Fields are inferred automatically:

| Unit field | Inference rule |
|-----------|----------------|
| `id` | Filename slug (lowercase, hyphens, no extension) |
| `path` | Relative path from workspace root |
| `intent` | First non-empty sentence of the indexed summary |
| `scope` | Depth 0 → `global`; depth 1-2 → `module`; depth 3+ → `focused` |
| `audience` | MARKDOWN/PDF → `[developer]`; CODE/YAML/CONFIG → `[developer]` |
| `format` | `.md`/`.mdx`/`.markdown` → `markdown`; `.pdf` → `pdf`; `openapi.yaml` / `swagger.*` / `asyncapi.*` → `openapi`; `*-schema.json` → `json-schema`; `.yaml`/`.yml` → `yaml`; code → omitted |
| `kind` | `SECURITY.md`, `LICENSE.md`, `CONTRIBUTING.md`, `PRIVACY.md`, `TERMS.md`, `NOTICE.md`, `*POLICY*` → `policy`; `openapi.yaml`, `swagger.*`, `*-schema.json` → `schema`; all else → omitted |
| `triggers` | Top-8 heading words slugified (stop-words removed) — increased from 4 in earlier draft |
| `validated` | Last-modified timestamp as quoted ISO date (`"YYYY-MM-DD"`) |
| `updated` | Same as `validated` |

### Key methods

| Method | Class | Description |
|--------|-------|-------------|
| `exportAsKcp(results, outputPath)` | `ExportCommand` | Entry point — converts SearchResult list to KCP YAML |
| `toKcpFormat(fileType, fileName)` | `ExportCommand` | Infers `format` field from extension + filename |
| `toKcpKind(fileName)` | `ExportCommand` | Infers `kind` field (policy/schema/null) |
| `toKcpTriggers(headings, max)` | `ExportCommand` | Extracts top-N trigger keywords from headings |

### Example generated output

```yaml
# Knowledge Context Protocol (KCP) v0.5
# Generated by Synthesis v1.19.0
kcp_version: "0.5"
language: en
indexing: open

hints:
  unit_count: 3

units:
  - id: readme
    path: README.md
    intent: "Synthesis indexes everything a team creates."
    scope: global
    audience:
      - developer
    format: markdown
    triggers:
      - synthesis
      - indexing
      - knowledge-graph
      - search
    validated: "2026-03-01"
    updated: "2026-03-01"

  - id: developer
    path: docs/perspectives/DEVELOPER.md
    intent: "Index your codebase in seconds."
    scope: module
    audience:
      - developer
    format: markdown
    triggers:
      - developer
      - workflow
      - search
      - relate
    validated: "2026-03-01"
    updated: "2026-03-01"

  - id: contributing
    path: CONTRIBUTING.md
    intent: "How to contribute to Synthesis."
    scope: module
    audience:
      - developer
    format: markdown
    kind: policy
    triggers:
      - contributing
      - pull-request
      - guidelines
    validated: "2026-02-15"
    updated: "2026-02-15"
```

---

## 6. Phase 5: Knowledge Graph Integration (PR #287)

### New commands and flags

No new top-level commands. Existing `synthesis kg` (alias: `synthesis knowledge-graph`) gains
KCP awareness in all three output formats.

```bash
# ASCII (default) — shows KCP units section at the top
synthesis kg -d /path/to/workspace

# Mermaid — KCP units as pill nodes with kcp-unit edges
synthesis kg -d /path/to/workspace --format mermaid

# JSON — includes kcpUnits and kcpRelationships arrays
synthesis kg -d /path/to/workspace --format json

# Scope filter — only units whose manifest_file starts with prefix
synthesis kg -d /path/to/workspace --scope docs/
```

### ASCII output

A `KCP Knowledge Units` section is prepended to the existing output, grouped by project:

```
KCP Knowledge Units:
----------------------------------------
  [my-service]
    • overview: What is this service and why does it exist?
      → README.md  [scope: global]
      triggers: overview, getting-started
    • api-ref: API endpoint reference.
      → docs/api.md  [scope: module]

  Relationships:
    api-ref --[context]--> overview

  [another-service]
    • intro: What does another-service do?
      → README.md  [scope: global]
```

### Mermaid output

KCP units appear as pill-shaped nodes `("project/unitId\nintent")` linked to their
source directory via `kcp-unit` labelled edges. Inter-unit relationships are shown as
typed directed edges.

```
graph TD
  ...existing directory nodes...

  %% KCP units
  kcp_my-service_overview("my-service/overview\nWhat is this service?")
  kcp_my-service_api-ref("my-service/api-ref\nAPI endpoint reference.")

  docs --> kcp_my-service_overview:::kcp-unit
  docs --> kcp_my-service_api-ref:::kcp-unit
  kcp_my-service_api-ref -->|context| kcp_my-service_overview
```

### JSON output

The JSON response gains two new top-level arrays:

```json
{
  "directories": [...],
  "kcpUnits": [
    {
      "unitId": "overview",
      "project": "my-service",
      "manifestFile": "my-service/knowledge.yaml",
      "path": "README.md",
      "intent": "What is this service and why does it exist?",
      "scope": "global",
      "audience": ["developer", "agent"],
      "format": "markdown",
      "triggers": ["overview", "getting-started"],
      "validated": "2026-03-01",
      "updated": "2026-03-01"
    }
  ],
  "kcpRelationships": [
    {
      "fromUnit": "api-ref",
      "toUnit": "overview",
      "type": "context",
      "manifestFile": "my-service/knowledge.yaml"
    }
  ]
}
```

### New methods in KnowledgeGraphCommand

| Method | Signature | Description |
|--------|-----------|-------------|
| `collectKcpUnits` | `(conn, workspacePath, scopeFilter) → List<KcpUnitNode>` | Queries kcp_units, applies optional scope prefix filter |
| `collectKcpRelEdges` | `(conn, workspacePath, scopeFilter) → List<KcpUnitEdge>` | Queries kcp_relationships, applies optional scope filter |

### New record types

| Record | Fields | Purpose |
|--------|--------|---------|
| `KcpUnitNode` | unitId, project, manifestFile, path, intent, scope, audience, format, triggers, validated, updated | In-memory node for kg rendering |
| `KcpUnitEdge` | fromUnit, toUnit, type, manifestFile | Directed relationship edge for rendering |

### Scope filtering

`--scope <prefix>` applies to KCP units by checking `manifestFile.startsWith(prefix)`. A unit
in `eXOReaction/Synthesis/knowledge.yaml` is included by `--scope eXOReaction/` but excluded
by `--scope Cantara/`.

### Backward compatibility

The three-argument `collectNodes()`, `collectEdges()`, `buildMermaid()` overloads introduced
in earlier phases are unchanged. KCP rendering is additive — existing ASCII/Mermaid/JSON output
is not modified.

---

## 7. Test Coverage

| Phase | Test Class | New Tests |
|-------|-----------|-----------|
| Phase 2 | `KcpDetectionTest` | 12 |
| Phase 2 | `KcpParsingTest` | 10 |
| Phase 3 | `KcpRepositoryTest` | 11 |
| Phase 3 | `KcpPersistenceMigrationTest` | 5 |
| Phase 4 | `KcpExportCommandTest` | 8 |
| Phase 5 | `KcpKnowledgeGraphCommandTest` | 10 |
| **Total** | | **56 new KCP tests** |

All tests pass with 0 failures and 0 errors. Total test count: 4,153.

---

## 8. Database Schema

### V17__kcp_tables.sql

Three new tables added in Flyway migration V17:

```sql
CREATE TABLE IF NOT EXISTS kcp_manifests (
    workspace_path TEXT NOT NULL,
    file_path      TEXT NOT NULL,
    project_id     TEXT,
    kcp_version    TEXT,
    unit_count     INTEGER DEFAULT 0,
    relationship_count INTEGER DEFAULT 0,
    language       TEXT,
    indexing       TEXT,
    last_indexed   INTEGER NOT NULL,
    PRIMARY KEY (workspace_path, file_path)
);

CREATE TABLE IF NOT EXISTS kcp_units (
    workspace_path TEXT NOT NULL,
    manifest_file  TEXT NOT NULL,
    unit_id        TEXT NOT NULL,
    unit_path      TEXT,
    intent         TEXT,
    scope          TEXT,
    audience_json  TEXT,
    format         TEXT,
    kind           TEXT,
    triggers_json  TEXT,
    hints_json     TEXT,
    validated      TEXT,
    updated        TEXT,
    last_indexed   INTEGER NOT NULL,
    PRIMARY KEY (workspace_path, manifest_file, unit_id)
);

CREATE TABLE IF NOT EXISTS kcp_relationships (
    workspace_path TEXT NOT NULL,
    manifest_file  TEXT NOT NULL,
    from_unit      TEXT NOT NULL,
    to_unit        TEXT NOT NULL,
    type           TEXT,
    last_indexed   INTEGER NOT NULL,
    PRIMARY KEY (workspace_path, manifest_file, from_unit, to_unit)
);
```

---

## 9. Known Limitations / Future Work

| Issue | Status |
|-------|--------|
| **Cross-manifest relationships** — Units in `repo-a/knowledge.yaml` can declare `context` relationships only to units in the same manifest. Cross-repo relationship declarations are ignored. | Planned |
| **Round-trip re-export** — `synthesis export --format kcp` always generates from the Lucene index. It does not read or preserve a pre-existing `knowledge.yaml` in the workspace. Manually authored intents and scopes are overwritten on re-export. | By design; opt-in preservation planned |
| **Trigger quality scoring** — Top-8 heading words are extracted by frequency. Low-quality triggers (dates, numbers, generic words) are filtered by stop-word list but semantic quality is not assessed. | Future |
| **`synthesis kcp validate`** — A dedicated command to validate a `knowledge.yaml` against the v0.5 spec (required fields, duplicate IDs, dangling relationship references) does not yet exist. Use the KCP Python reference parser: `python3 -m kcp knowledge.yaml`. | Planned as #300 |
| **Audience inference** — Currently all files default to `[developer]`. Agent-specific audience (`[agent]`) is not yet inferred automatically; add it manually. | Future |

---

**Related:** [FEATURE-KCP.md](../features/FEATURE-KCP.md) · [synthesis-kcp.yaml](../../.claude/skills/synthesis-kcp.yaml) · KCP spec: github.com/cantara/knowledge-context-protocol
