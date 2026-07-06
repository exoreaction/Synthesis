# Feature: KCP (Knowledge Context Protocol)

**Available since:** v1.19.0 (March 2026); v0.25-conformant export since July 2026
**Spec:** https://github.com/cantara/knowledge-context-protocol

---

## 1. What is KCP?

KCP (Knowledge Context Protocol) is a structured YAML manifest format. A `knowledge.yaml` file
sits in a repository and tells AI agents which files matter, what each one is for, and in what
order to read them. Without a manifest, an agent must scan the entire codebase to understand it.
With one, the agent reads the manifest first and immediately knows where to focus.

Synthesis provides full-stack support: detection and parsing during indexing (through v0.21
fields), SQLite persistence, v0.25-conformant manifest generation from the index (validated
against `kcp-agent validate` in CI), and first-class knowledge graph visualisation.

---

## 2. Quickstart

```bash
# Step 1: Generate a knowledge.yaml for your repo
synthesis -d /path/to/your-repo export --format kcp -o knowledge.yaml

# Step 2: Review and commit the manifest
git add knowledge.yaml
git commit -m "docs: add KCP manifest"

# Step 3: Let Synthesis index it (automatic during scan/maintain)
synthesis -d /path/to/your-repo scan

# Step 4: Visualise in the knowledge graph
synthesis -d /path/to/workspace kg
synthesis -d /path/to/workspace kg --format json | jq '.kcpUnits'
```

After step 3, the manifest data is stored in SQLite and available to all `synthesis kg` queries
and the `knowledge-graph` MCP tool — no extra steps needed.

---

## 3. Format Reference

The v0.5 baseline fields below are still the core of every manifest. Since July 2026 the
exporter emits `kcp_version: "0.25"` and additionally infers `content_structure`,
`content_hash` (sha256), `temporal.recorded_at` (last git commit), and
`discovery.verification_status: declared` per unit — see the `synthesis-kcp` skill for
the full field reference.

```yaml
# Knowledge Context Protocol (KCP)
kcp_version: "0.25"
project: my-service          # OR id: my-service
language: en
indexing: open

hints:
  unit_count: 3

units:
  - id: overview
    path: README.md
    intent: "What is this project and why does it exist?"
    scope: global
    audience: [developer, agent]
    format: markdown
    triggers: [overview, introduction, getting-started]
    validated: "2026-03-01"
    updated: "2026-03-01"

  - id: api-ref
    path: docs/api.md
    intent: "What endpoints does the API expose?"
    scope: module
    audience: [developer]
    format: markdown
    kind: knowledge

relationships:
  - from: api-ref
    to: overview
    type: context
```

### Field table

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `kcp_version` | Yes | String | Always `"0.5"` (quoted) |
| `project` / `id` | Yes (one of) | String | Manifest identifier |
| `language` | Recommended | String | ISO 639-1 code (`en`) |
| `indexing` | Recommended | String | `open` = agent may index freely |
| `hints.unit_count` | Recommended | Integer | Count of non-skipped units |
| `units[].id` | Yes | String | Slug, unique within manifest |
| `units[].path` | Yes | String | File path relative to manifest |
| `units[].intent` | Yes | String | One sentence: "what will I learn from this?" |
| `units[].scope` | Recommended | String | `global` / `module` / `focused` / `comprehensive` |
| `units[].audience` | Recommended | List | `developer`, `agent`, or both |
| `units[].format` | Optional | String | `markdown`, `pdf`, `openapi`, `yaml`, `json-schema` |
| `units[].kind` | Optional | String | `policy`, `schema`, or omit for default knowledge |
| `units[].triggers` | Optional | List | Up to 8 keywords that should activate this unit |
| `units[].validated` | Optional | String | Quoted ISO date (`"YYYY-MM-DD"`) |
| `units[].updated` | Optional | String | Quoted ISO date |
| `relationships[].from` | Yes (if present) | String | Source unit `id` |
| `relationships[].to` | Yes (if present) | String | Target unit `id` |
| `relationships[].type` | Yes (if present) | String | `context`, `extends`, `summary_of` |

---

## 4. Automatic Inference

`synthesis export --format kcp` infers all optional fields from the Lucene index. You do not
need to write these manually — generate first, then hand-edit only what needs customisation.

| Unit field | Inference rule |
|-----------|----------------|
| `id` | Filename slug (lowercase, hyphens, no extension) |
| `intent` | First non-empty sentence of the indexed summary |
| `scope` | Depth 0 → `global`; depth 1-2 → `module`; depth 3+ → `focused` |
| `audience` | All files → `[developer]` by default |
| `format` | Inferred from extension (see section 5 below) |
| `kind` | Inferred from filename (see section 6 below) |
| `triggers` | Top-8 heading words, slugified, stop-words removed |
| `validated` | Last-modified timestamp as quoted ISO date |
| `updated` | Same as `validated` |

---

## 5. `format` Reference

| Extension(s) | Inferred `format` |
|-------------|-------------------|
| `.md`, `.mdx`, `.markdown` | `markdown` |
| `.pdf` | `pdf` |
| `openapi.yaml`, `swagger.*`, `asyncapi.*` | `openapi` |
| `*-schema.json`, `*-schema.yaml` | `json-schema` |
| `.yaml`, `.yml` | `yaml` |
| `.json` | `json` |
| Code files (`.java`, `.py`, etc.) | Omitted |

---

## 6. `kind` Reference

Synthesis auto-classifies these filenames as `policy`:
- `SECURITY.md`
- `LICENSE.md` / `LICENSE` / `LICENSE.txt`
- `CONTRIBUTING.md`
- `PRIVACY.md`
- `TERMS.md`
- `NOTICE.md`
- Any filename containing `POLICY` (case-insensitive)

Synthesis auto-classifies these as `schema`:
- `openapi.yaml` / `swagger.yaml` / `swagger.json`
- `asyncapi.yaml`
- Files matching `*-schema.json` or `*-schema.yaml`

Everything else defaults to `knowledge` (the field is omitted — omit = knowledge per the spec).

---

## 7. Knowledge Graph Usage

After scanning, KCP units appear in all `synthesis kg` output formats.

### ASCII (default)

```bash
synthesis kg -d /path/to/workspace
```

```
KCP Knowledge Units:
----------------------------------------
  [my-service]
    • overview: What is this project and why does it exist?
      → README.md  [scope: global]
      triggers: overview, introduction, getting-started
    • api-ref: What endpoints does the API expose?
      → docs/api.md  [scope: module]

  Relationships:
    api-ref --[context]--> overview
```

### Mermaid

```bash
synthesis kg -d /path/to/workspace --format mermaid
```

KCP units appear as pill-shaped nodes linked to their source directory:

```
graph TD
  docs("docs/")
  kcp_my-service_overview("my-service/overview\nWhat is this project?")
  kcp_my-service_api-ref("my-service/api-ref\nAPI endpoint reference.")

  docs --> kcp_my-service_overview:::kcp-unit
  docs --> kcp_my-service_api-ref:::kcp-unit
  kcp_my-service_api-ref -->|context| kcp_my-service_overview
```

### JSON

```bash
synthesis kg -d /path/to/workspace --format json
```

```json
{
  "directories": [...],
  "kcpUnits": [
    {
      "unitId": "overview",
      "project": "my-service",
      "manifestFile": "my-service/knowledge.yaml",
      "path": "README.md",
      "intent": "What is this project and why does it exist?",
      "scope": "global",
      "triggers": ["overview", "introduction", "getting-started"]
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

### Scope filtering

```bash
# Only show KCP units from manifests inside docs/
synthesis kg -d /path/to/workspace --scope docs/
```

---

## 8. Using with MCP

The `synthesis knowledge-graph` MCP tool (used by Claude Desktop and Claude Code via MCP server)
returns `kcpUnits` and `kcpRelationships` in its JSON response. Agents can use this to:

- Discover which repos have KCP manifests
- Find the `intent` for each unit to route queries correctly
- Use `triggers` to match agent queries to relevant files
- Follow `relationships` to understand reading order

```bash
# Example: find all KCP units across all repos in the workspace
mcp__synthesis__knowledge-graph --format json | jq '.kcpUnits | group_by(.project)'

# Find units with "security" in triggers
mcp__synthesis__knowledge-graph --format json | jq '[.kcpUnits[] | select(.triggers[]? | contains("security"))]'
```

---

## 9. Troubleshooting

### Detection didn't fire

The manifest must meet all three conditions:
1. Filename is exactly `knowledge.yaml` (case-sensitive)
2. Top-level key `units` is a YAML list
3. Top-level key `project` or `id` exists

Check with: `synthesis search -d /path/to/workspace "kcp-manifest" --type YAML`

If no results, run `synthesis scan -d /path/to/workspace` and check for parse errors.

### Wrong `kind` assigned

Synthesis auto-assigns `kind: policy` for `SECURITY.md`, `LICENSE.md`, etc. If a file is being
misclassified, either rename it or edit the generated `knowledge.yaml` manually after generation.

### No units in `synthesis kg`

KCP units are stored in SQLite during `scan` or `maintain`. If you added a `knowledge.yaml` after
the last scan, run:

```bash
synthesis scan -d /path/to/workspace
# or
synthesis maintain -d /path/to/workspace
```

Then check: `synthesis kg -d /path/to/workspace --format json | jq '.kcpUnits | length'`

### Empty triggers

Triggers are extracted from indexed headings. If the file has no headings (or only headings
that are all stop-words), `triggers` will be empty or have fewer than 8 entries. Add explicit
headings to the source file, re-scan, and re-export.

---

**Related:** [KCP-RELEASE-NOTES.md](../releases/KCP-RELEASE-NOTES.md) · [synthesis-kcp.yaml](../../.claude/skills/synthesis-kcp.yaml)
