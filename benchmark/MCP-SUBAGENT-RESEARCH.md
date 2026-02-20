# Research: MCP Tool Availability in Claude Code Task Subagents

**Issue:** #113
**Date:** February 2026
**Conclusion:** MCP tools from `~/.claude/config.json` are NOT available in Task subagents. CLI workaround is the recommended approach for automated benchmarks.

---

## Research Questions Answered

### Q1: Is this a fundamental limitation or a configuration gap?

**Fundamental limitation.**

Claude Code's `Task` tool spawns subagents that call the Anthropic API directly. The subagents do not inherit the parent session's `~/.claude/config.json`. MCP server configurations (including the synthesis servers) are local stdio-based processes — they run as child processes of the interactive Claude Code session and are not network-accessible.

There is no mechanism to pass stdio-based MCP server definitions to the API at the session level.

### Q2: Is there an API/SDK path?

**Partial — HTTP only, not applicable to Synthesis MCP.**

The Anthropic API has a beta `mcp_servers` parameter that allows injecting MCP server definitions at the API call level. However, this only works for **HTTP/SSE-accessible MCP servers** (e.g., a server exposing `https://example.com/mcp`).

Synthesis MCP uses **stdio transport** (local process). To use the API-level `mcp_servers` parameter, Synthesis would need to expose an HTTP endpoint — a significant architectural change.

### Q3: Can Synthesis MCP tools be wrapped as regular tools?

**Yes — the CLI workaround works today.**

Subagents have full access to the `Bash` tool. All Synthesis MCP tools have CLI equivalents:

| MCP tool | CLI equivalent (works in subagents) |
|---|---|
| `search` | `export PATH="$HOME/bin:$PATH" && synthesis search -d /src/exoreaction "query"` |
| `relate` | `synthesis relate SearchIndex.java` |
| `graph` | `synthesis graph --modules` |
| `stats` | `synthesis status` |
| `ask` | `synthesis ask "question"` |
| `explain` | `synthesis explain SearchIndex.java` |
| `summary` | `synthesis summary --level executive` |
| `enrich` | `synthesis enrich <file>` |

**Critical:** Always `export PATH="$HOME/bin:$PATH"` before using synthesis in subagents. Without this, subagents report "synthesis CLI not available" even though it is installed.

### Q4: What does Claude's multi-agent documentation say?

The Anthropic multi-agent / agent SDK documentation confirms that MCP tools are session-scoped in interactive Claude Code. When using the API directly (as Task subagents do), MCP servers must be provided at the API level — which requires HTTP transport. Stdio-based servers cannot be used in non-interactive API contexts.

---

## Impact on Benchmark Automation

| Benchmark condition | Automated? | Reason |
|---|---|---|
| Baseline | ✓ | Standard tools only |
| Knowledge | ✓ | Context injection via prompt |
| CLI | ✓ | `synthesis` CLI available via Bash tool |
| MCP | ✗ | Requires interactive session |

**Phase 5 MCP condition** (9 tasks) must be run as 9 separate interactive Claude Code sessions.
See `benchmark/PHASE5-MCP-SESSION-GUIDE.md` for the exact prompts and scoring template.

**Phase 6+ options:**
1. Continue running MCP condition manually (9 sessions per phase)
2. Convert Synthesis MCP to HTTP mode (nginx/tunneling) to enable API-level injection — significant engineering effort, low priority
3. Accept MCP condition as manually-validated benchmark arm and automate only Baseline/Knowledge/CLI

---

## Recommendation

**Use CLI workaround for automated benchmarks. Run MCP condition manually per phase.**

The CLI condition already demonstrates that Synthesis tools reduce tool calls significantly for the right task types (P5-R1: -40% vs Baseline). MCP is expected to improve further on structural tasks (graph, relate) but the manual overhead of interactive sessions is justified only for research phases, not continuous monitoring.

For production agentic workflows that spawn subagents: use CLI tools (`synthesis search`, `synthesis relate`, etc.) rather than MCP tools. The output is equivalent; the CLI has no session dependency.

---

## Future Path: HTTP MCP Mode

If MCP-in-subagents becomes a priority, the path is:

1. Add an HTTP transport mode to `SynthesisMcpServer.java` (SSE or streamable-HTTP per MCP spec)
2. Run `synthesis mcp-server --http --port 8765` as a background daemon
3. Register in Anthropic API calls: `"mcp_servers": [{"url": "http://localhost:8765/mcp"}]`
4. Task subagents can then use MCP tools directly

Effort estimate: 2-3 days for the HTTP transport layer. Not currently planned.
