# Harness Support

Synthesis is primarily developed against **Claude Code** as its host LLM
harness, but the core value (indexing, search, MCP tools, LSP, CLI, AI
analysis) is harness-agnostic. This document classifies every Synthesis
integration point by portability so contributors building new integrations
(pi.dev, Cursor, Codex, Copilot CLI, Gemini CLI, etc.) know what they get
for free, what needs adaptation, and what is intentionally Claude Code-specific.

See [issue #332](https://github.com/exoreaction/Synthesis/issues/332) for the
discovery that motivated this document.

---

## Portability tiers

### Tier 1 — Harness-agnostic (works anywhere)

These surfaces make zero assumptions about the host harness. They are
first-class install targets for any environment.

| Surface | Notes |
|---|---|
| `synthesis` CLI | Plain Java 21+. Runs in any terminal. |
| `synthesis-mcp-server.jar` | Standards-conformant MCP server. Speaks stdio or HTTP. Works with any MCP-capable host, either natively (Claude Code, Cursor, Codex) or via an adapter (pi.dev). |
| `synthesis-lsp-server.jar` | Standards-conformant LSP server. Works with any LSP client. |
| `~/.synthesis/` install layout | Lib and state live here; independent of harness. |
| Anthropic API features (`ask`, `perspectives`, `summary`, `explain`, `enrich`) | Require `ANTHROPIC_API_KEY` only. The harness is not involved. |

### Tier 2 — Claude Code-specific (break silently or go dark elsewhere)

These surfaces write to Claude Code conventions. Running them inside another
harness is a no-op or produces files the harness does not read.

| Surface | CC-specific assumption |
|---|---|
| `synthesis export-skills` | Writes to `~/.claude/skills/`. Other harnesses use different skill mechanisms (or none). |
| `synthesis learn --install` | Same as above. |
| `synthesis hooks generate` | Writes to `~/.claude/settings.json`. Other harnesses have their own hook surfaces (or none). |
| SessionStart hook injection via `synthesis session-context --compact` | Depends on CC's SessionStart hook type. No equivalent in many harnesses. |
| `synthesis claude-md refresh` | Targets `CLAUDE.md`. Other harnesses use `AGENTS.md`, `GEMINI.md`, `.cursorrules`, etc. |

**Follow-up issues** will generalize some of these — see #332 for the list.

### Tier 3 — Harness-dependent (needs per-harness adapter)

These depend on what the host harness supports. Per-harness setup guides live
in [`docs/integrations/`](integrations/pi-dev.md).

| Harness | MCP support | Skills surface | Hooks surface | Instructions file |
|---|---|---|---|---|
| **Claude Code** | Native | `~/.claude/skills/` | `~/.claude/settings.json` | `CLAUDE.md` |
| **Cursor** | Native | Rules / commands | Limited | `.cursorrules` |
| **Codex (OpenAI)** | Native | — | — | `AGENTS.md` |
| **Gemini CLI** | Via `activate_skill` | Skills | — | `GEMINI.md` |
| **Copilot CLI (GitHub)** | Native | Plugin skills | — | `AGENTS.md` (proposed) |
| **pi.dev** | Via [`pi-mcp-adapter`](https://github.com/nicobailon/pi-mcp-adapter) (proxy pattern) | TS extensions | None | `AGENTS.md` or project-local |

---

## Harness-neutral startup surface

**`bootstrap_context`** is the recommended first call for any non-Claude harness.

It returns a single compact startup packet by composing:
- workspace freshness (from `session_context`)
- relevant skills (from `match_skills`, only when `task` is provided)
- key documents (from `knowledge.yaml`, `README.md`, `AGENTS.md`, etc.)
- workspace validation warnings

Call it at session start and inject the `compact` field into the initial prompt.
No hooks, no config files, no harness-specific setup required.

**MCP:**
```json
{ "name": "bootstrap_context", "arguments": { "task": "add OAuth login", "compact": true } }
```

**CLI:**
```bash
synthesis bootstrap-context --task "add OAuth login"
synthesis bootstrap-context --json   # full JSON output
```

Claude Code users keep their hook-driven `session_context` — `bootstrap_context`
is additive, not a replacement.

---

## Recommended minimum for any new harness

If you want Synthesis "working" inside a new harness with the least effort:

1. **MCP server over stdio** — point the harness at `synthesis-mcp-server`
   (the launcher script) or `java -jar ~/.synthesis/lib/current.jar` directly
   if the launcher is not on PATH.
2. **CLI on PATH** — ensures `synthesis search`, `synthesis ask`, etc. work
   from the harness's shell tool.
3. **`ANTHROPIC_API_KEY` in env** — unlocks all AI features.
4. **Call `bootstrap_context` at session start** — inject the `compact` output
   into the initial prompt. Replaces the manual composition of `session_context`
   + `match_skills` + KCP lookup that harnesses previously had to do themselves.

Everything else (skills distribution, hook-driven freshness, instructions-file
auto-refresh) is an enhancement layer that can be added per-harness.

---

## Specific integrations

- [pi.dev setup](integrations/pi-dev.md) — via `pi-mcp-adapter`, proxy pattern, recommended direct-tool promotions.

(More to come as integrations land. Contribute a guide by opening a PR against
`docs/integrations/`.)
