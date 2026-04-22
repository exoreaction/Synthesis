# Synthesis + pi.dev

How to use Synthesis's MCP server inside [pi.dev](https://pi.dev/) (Mario
Zechner's minimalist TypeScript coding agent).

**Status:** Draft — written from Synthesis's side without live pi.dev testing.
Please open a PR with corrections after trying it. Tracked in
[#332](https://github.com/exoreaction/Synthesis/issues/332).

---

## What works, what doesn't

| Feature | Status in pi.dev |
|---|---|
| `synthesis-mcp-server.jar` over stdio | ✓ Works via `pi-mcp-adapter` |
| `synthesis` CLI via bash tool | ✓ Works (pi's default bash tool) |
| Anthropic API features (`ask`, `perspectives`, `summary`, `enrich`) | ✓ Works (pi handles `ANTHROPIC_API_KEY`) |
| `synthesis export-skills` | ✗ Writes to `~/.claude/skills/` — ignored by pi |
| `synthesis hooks generate` | ✗ Writes to `~/.claude/settings.json` — ignored by pi |
| SessionStart context injection | ✗ No equivalent hook surface in pi |
| `synthesis claude-md refresh` | ✗ Pi uses `AGENTS.md`, not `CLAUDE.md` |

For the rationale behind each row see
[`docs/HARNESS-SUPPORT.md`](../HARNESS-SUPPORT.md).

---

## Prerequisites

- Pi installed and working (`pi --version`)
- Synthesis installed (`synthesis --version`) and on `PATH`
- `ANTHROPIC_API_KEY` exported in the shell that launches pi
- Java 21+ (`java -version`)

---

## Setup

### 1. Install `pi-mcp-adapter`

Pi has no native MCP. Install the community adapter:

```bash
pi install npm:pi-mcp-adapter
```

Restart pi, then run the guided config:

```
/mcp setup
```

See [`nicobailon/pi-mcp-adapter`](https://github.com/nicobailon/pi-mcp-adapter)
for full adapter documentation.

### 2. Configure the Synthesis MCP server

Add Synthesis to pi's MCP config (`.mcp.json` in the project root, or
`~/.config/mcp/mcp.json` for a global entry):

```json
{
  "mcpServers": {
    "synthesis": {
      "command": "synthesis-mcp-server",
      "args": ["--workspace", "/absolute/path/to/your-project"]
    }
  }
}
```

> **Note:** Use an absolute path — the `~` shorthand is not expanded inside
> JSON config files. If `synthesis-mcp-server` is not on the `PATH` pi sees,
> substitute the full path: `java -jar /Users/you/.synthesis/lib/current.jar`.

### 3. Index the workspace

From the project root:

```bash
cd /absolute/path/to/your-project
synthesis init
synthesis scan
```

---

## Recommended direct-tool promotions

`pi-mcp-adapter` uses a **proxy pattern**: one `mcp` meta-tool
(~200 tokens) instead of every tool definition in the system prompt. This is
great for context efficiency but means every Synthesis tool call is an indirect
proxy call.

For hot paths, promote these Synthesis tools to **direct registration** in the
adapter's config so they show up as top-level tools:

| Tool | Why promote |
|---|---|
| `search` | Highest-frequency call; should not go through the proxy. |
| `ask` | AI Q&A grounded in index — common high-value call. |
| `relate` | Dependency lookups during code reading. |
| `code-graph` | Fast impact analysis for code tasks. |
| `session_context` | Useful at session start to orient the model. |

Leave the long tail (`architecture`, `evolution`, `sessions`, `enrich`,
`perspectives`, `summary`, etc.) behind the proxy — they are called rarely
enough that the context savings win.

Follow the `pi-mcp-adapter` docs for the exact syntax to mark tools as direct
rather than proxied.

---

## Subagent extensions

If you use pi's subagent extension, you can grant subagents direct access to
specific Synthesis tools via frontmatter:

```yaml
mcp: synthesis.search, synthesis.ask, synthesis.relate
```

This matches how we use Claude Code subagents (Explore, Plan, general-purpose)
to research code — a subagent given `synthesis.search` can find relevant files
without burning the main-agent context.

---

## Known gaps

- **No session-start freshness injection.** Claude Code users get a
  one-line codebase freshness summary on every new session via a hook.
  Pi has no equivalent, so freshness must be fetched on-demand:
  `synthesis session-context --compact`.
- **Skills are not portable to pi.** The 33 Synthesis skills in
  `~/.claude/skills/` assume Claude Code's skill discovery. In pi you would
  need to re-author them as pi TS extensions, or treat them as reference
  docs (they are plain markdown).
- **`AGENTS.md` vs `CLAUDE.md`.** `synthesis claude-md refresh` currently
  hard-codes `CLAUDE.md`. Until we add a `--target` flag
  ([#332](https://github.com/exoreaction/Synthesis/issues/332) follow-up),
  copy-paste the generated section into `AGENTS.md` manually.
- **JVM warm-up on idle disconnect.** `pi-mcp-adapter` disconnects idle MCP
  servers after ~10 minutes. Reconnecting re-spawns the JVM (~1-2s
  cold-start). For active sessions this is fine; for brief one-off calls
  after a long pause it is noticeable.

---

## Verifying the setup

Once configured, in a pi session ask:

> Can you search the code for UserService using synthesis?

If pi invokes the `search` tool (or the proxied `mcp` tool with a `search`
subcommand) and returns results from the indexed workspace, the integration
is working.

Troubleshooting:

- **"synthesis CLI not available"** — pi's spawned shells may not inherit
  your full `PATH`. Export it explicitly or use the full jar path in the
  `command` field.
- **Zero search results** — verify the workspace is indexed:
  `synthesis status -d /absolute/path/to/your-project`.
- **MCP server won't start** — try running it manually:
  `synthesis-mcp-server --workspace /path/to/project` and check for Java errors.

---

## Contributing

If you try this and hit something not covered above — even a one-line
"works on my machine" confirmation — please open a PR against this file.
The more real-world pi.dev usage feedback we have, the better this guide gets.
