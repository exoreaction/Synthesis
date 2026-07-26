# Synthesis + Pi

How to use Synthesis with the [Pi coding agent](https://pi.dev/)
(`@earendil-works/pi-coding-agent`). Since this guide was first drafted, the
KCP ecosystem grew a dedicated Pi extension —
[`@cantara/pi-kcp`](https://github.com/Cantara/pi-kcp) — and that changes the
recommended integration shape: Synthesis's most valuable contribution to a Pi
session is now the **manifest it generates and signs**, with the MCP server as
an optional second lane.

**Status:** Updated for pi-kcp 0.2.0. The KCP lane is exercised against the
same artifacts our CI validates (`kcp-agent` conformance gauntlet); the MCP
lane is written from Synthesis's side without long-running live Pi testing.
Corrections welcome — tracked in
[#332](https://github.com/exoreaction/Synthesis/issues/332).

---

## Two lanes

| Lane | What Synthesis provides | What Pi needs |
|---|---|---|
| **KCP (recommended)** | Generated, verified, Ed25519-signed `knowledge.yaml` | `pi-kcp` extension + `kcp-agent` CLI — works on **stock Pi**, no MCP client |
| **MCP (optional)** | `synthesis-mcp-server.jar` — 52 tools (search, ask, relate, bootstrap_context, remember/recall, plan_context, …) | An MCP client extension (e.g. `pi-mcp-adapter`) |

The lanes are independent. The KCP lane gives Pi deterministic knowledge plans
and runtime governance over a manifest Synthesis keeps honest. The MCP lane
gives the model live code-intelligence queries. Use either or both.

pi-kcp deliberately treats MCP as its compatibility boundary — it never
imports Synthesis and never assumes a specific provider. Synthesis slots in
as configuration, which is exactly the arms-length contract we want.

---

## Lane 1: KCP — Synthesis as the manifest producer

`pi-kcp` adds `/kcp` commands to Pi (`plan`, `validate`, `recall`, `health`,
`init`) and — since its runtime-depth milestone — enforces `action_scope`
governance on native tool calls. All of it is driven by the project's
`knowledge.yaml`. Synthesis's job is to make that manifest **exist, stay
fresh, and stay trustworthy**:

```bash
# One-time: scaffold or generate the manifest
synthesis kcp init                      # from repo structure (never overwrites), or
synthesis export --format kcp -o knowledge.yaml   # from the full index

# Keep it honest
synthesis kcp refresh                   # refresh volatile fields (hand-edits protected)
synthesis kcp verify                    # declarations vs evidence (exit 1 on HIGH)
synthesis kcp gaps                      # hot files with no unit coverage

# Make it tamper-evident
synthesis kcp sign knowledge.yaml       # Ed25519, kcp-agent-interoperable
```

Then, in a Pi session with pi-kcp loaded:

```text
/kcp validate            # kcp-agent validates the Synthesis-generated manifest
/kcp plan <intent>       # deterministic read plan, injected into the next turn
```

Version notes (from pi-kcp's own prerequisites): Pi >= 0.80.6, Node >= 20,
kcp-agent CLI for plan/validate/init, optional kcp-memory daemon for episodic
recall. Synthesis's export is validated against pinned `kcp-agent` in CI
(`.github/workflows/kcp-conformance.yml`), including the signature interop
both ways — verified when intact, rejected when tampered.

**Governed skills are a hand-authored layer.** pi-kcp's runtime governance
keys off units with `kind: skill` + `action_scope` (tools / paths /
capabilities allowlists). Synthesis does not generate those — add them by
hand to the generated manifest. `synthesis kcp refresh` protects hand-edited
units, so the generated and curated layers coexist in one file.

---

## Lane 2: MCP — Synthesis as the code-intelligence provider

Stock Pi ships without an MCP client. Install one (pi-kcp's docs point at
the community adapter):

```bash
pi install npm:pi-mcp-adapter
```

Then configure Synthesis following pi-kcp's recommended provider defaults —
**lazy lifecycle, no direct tool injection** (`.pi/mcp.json` in the project,
per pi-kcp's `docs/mcp-providers.md`):

```json
{
  "settings": {
    "toolPrefix": "server",
    "directTools": false
  },
  "mcpServers": {
    "synthesis": {
      "command": "bash",
      "args": ["-lc", "exec java -jar $HOME/.synthesis/lib/synthesis-mcp-server.jar --workspace \"$PWD\""],
      "lifecycle": "lazy"
    }
  }
}
```

> Use absolute paths if `$HOME` expansion is unavailable in your setup; `~`
> is not expanded inside JSON config files. If you have the dedicated
> `synthesis-mcp-server` launcher on `PATH`, use it directly as `command`.

Lazy startup means sessions that never ask for code intelligence never pay
the JVM + index cost; `directTools: false` keeps 52 tool definitions out of
the prompt until the model actually reaches for the server. Both defaults
come from pi-kcp's provider guidance and match our own context-economy
advice.

Index the workspace first:

```bash
cd /absolute/path/to/your-project
synthesis init
synthesis scan
```

### Session startup: call `bootstrap_context` first

Pi has no equivalent of Claude Code's `UserPromptSubmit` hook, so nothing
injects codebase freshness automatically. The `bootstrap_context` MCP tool is
the harness-neutral replacement — call it once at session start and put the
`compact` field into the initial prompt:

```json
{
  "name": "bootstrap_context",
  "arguments": { "task": "optional — what you are about to work on", "compact": true }
}
```

```
workspace:my-project | workspace:4823files·61MB | changed:3files(24h) | skills:synthesis-development | docs:README.md, AGENTS.md
```

The tool is read-only and harness-neutral — it never writes to `~/.claude/`,
`AGENTS.md`, or any config file.

(If you use pi-kcp's `/kcp recall` alongside this, note the division of
labor: recall injects *episodic memory* from kcp-memory; `bootstrap_context`
injects *workspace freshness* from the Synthesis index. They compose.)

---

## What works, what doesn't

| Feature | Status in Pi |
|---|---|
| `knowledge.yaml` generate/refresh/verify/sign → `/kcp plan`, `/kcp validate` | ✓ Works on stock Pi via pi-kcp + kcp-agent |
| `synthesis-mcp-server.jar` over stdio | ✓ Works via an MCP client extension (`pi-mcp-adapter`) |
| `synthesis` CLI via bash tool | ✓ Works (Pi's default bash tool) |
| Anthropic API features (`ask`, `perspectives`, `summary`, `enrich`) | ✓ Works (Pi handles `ANTHROPIC_API_KEY`) |
| `synthesis export-skills` | ✗ Writes to `~/.claude/skills/` — ignored by Pi (see below) |
| `synthesis hooks generate` | ✗ Writes to `~/.claude/settings.json` — ignored by Pi |
| SessionStart context injection | ✗ No hook surface; use `bootstrap_context` (MCP) or `/kcp recall` (pi-kcp) |
| `synthesis claude-md refresh` | ✗ Pi uses `AGENTS.md`, not `CLAUDE.md` — copy-paste until a `--target` flag exists |

For the rationale behind each row see
[`docs/HARNESS-SUPPORT.md`](../HARNESS-SUPPORT.md).

**On skills:** Synthesis's Claude Code skills don't port to Pi as-is, but the
KCP lane offers a better-than-porting path — pi-kcp reads *governed skills*
from `knowledge.yaml` (`kind: skill` + `action_scope`), which is a
harness-neutral, enforceable declaration rather than a Claude-specific
markdown convention. Declaring your critical workflows there makes them
available to any KCP-aware harness, not just Pi.

---

## Verifying the setup

**KCP lane:** run `/kcp health` in Pi — it reports config validity,
kcp-memory reachability, and where kcp-agent was found. Then `/kcp validate`
against the Synthesis-generated manifest; it should pass (our CI holds the
same bar).

**MCP lane:** ask Pi:

> Can you search the code for UserService using synthesis?

If Pi invokes the server (directly or through the adapter's proxy surface)
and returns results from the indexed workspace, the integration works.

Troubleshooting:

- **"synthesis CLI not available"** — Pi's spawned shells may not inherit
  your full `PATH`. Export it explicitly or use the full jar path.
- **Zero search results** — verify the workspace is indexed:
  `synthesis status -d /absolute/path/to/your-project`.
- **MCP server won't start** — run it manually and check for Java errors.
- **`/kcp plan` returns nothing** — pi-kcp rejects non-JSON planner output;
  check `kcp-agent --version` and that `knowledge.yaml` validates first.
- **JVM warm-up on idle disconnect** — adapters may disconnect idle MCP
  servers; reconnecting re-spawns the JVM (~1-2s). Lazy lifecycle makes this
  a feature, not a bug.

---

## Contributing

If you try this and hit something not covered above — even a one-line
"works on my machine" confirmation — please open a PR against this file.
