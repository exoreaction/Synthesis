# Synthesis

**Local-first knowledge infrastructure for AI-augmented development.**

Synthesis indexes everything your team creates — code, documentation, PDFs, media, Notion — into
searchable knowledge graphs, and exposes all of it to you and your AI agents through a CLI, an
MCP server, and an LSP server. Everything runs on your machine. Nothing is sent to a cloud
service.

AI made *creating* code 10× faster; *understanding* it stayed at 1×. Synthesis closes that gap —
and goes one step further: it doesn't just retrieve knowledge, it lets you **verify** it.

---

## Get started in three commands

Install (Linux/macOS — [Windows guide](INSTALL-WINDOWS.md)):

```bash
curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash
```

Then point it at a project:

```bash
synthesis init ~/projects/my-project --name "My Project"
synthesis scan                          # indexes 200–300 files/second
synthesis search "authentication pipeline"
```

Sub-second results from the first search. Continue with the **[Quick Start](guides/QUICK-START.md)**,
or connect your AI agent via the **[MCP Quick Start](guides/MCP-QUICKSTART.md)**.

---

## What Synthesis does that other tools don't

<div class="grid cards" markdown>

-   **Knowledge you can verify, not just retrieve**

    ---

    `kcp verify` checks knowledge declarations against evidence in the repo. Manifests are
    Ed25519-signed and tamper-evident — verified in CI against the reference KCP consumer.
    Governance cross-checks catch claims reality contradicts (e.g. "public" files with open
    security findings). Search is a crowded market; **trustworthy AI context is not**.

-   **A full Knowledge Context Protocol stack**

    ---

    Generate ([`kcp init`](features/FEATURE-KCP.md)), refresh, verify, federate across an entire
    repo estate, plan ordered read-lists under a token budget (`kcp plan`), and sign — the complete
    [KCP v0.25](https://github.com/Cantara/knowledge-context-protocol) lifecycle, conformant with
    the reference implementation.

-   **Episodic memory for AI agents**

    ---

    Your agent forgets everything when the session ends. Synthesis
    [indexes Claude Code sessions](features/FEATURE-CLAUDE-SESSIONS.md) into searchable memory,
    and exposes `remember` / `recall` MCP tools with hash-pinned, tamper-detected entries — so
    agents build on past work instead of rediscovering it.

-   **Two knowledge graphs, one workspace**

    ---

    A **document graph** learns what each directory *is* and *wants to become*, routing files
    intelligently. A **[code graph](architecture/CODE-KNOWLEDGE-GRAPH-DESIGN.md)** persists
    dependency edges for instant impact analysis: *what breaks if I change this?* — across
    repos, and across formats (SQL→Java, YAML→Java, docs→code).

-   **52 MCP tools, grounded answers**

    ---

    From `search` and `impact` to `ask --ground`, which cross-checks every claim in an AI answer
    against sha256-pinned sources and surfaces what it *couldn't* verify as explicit gaps.
    Fail-closed, not confidently wrong. [MCP guide →](guides/MCP-COMPREHENSIVE-GUIDE.md)

-   **Local-first, zero cloud**

    ---

    The index is Lucene on your disk; the database is SQLite. Core features need no API key at
    all. Works on air-gapped networks. Your code and documents never leave your machine —
    auditable line-by-line under Apache 2.0.

</div>

---

## Choose your path

| You are… | Start here | Time |
|---|---|---|
| **A developer** | [Quick Start](guides/QUICK-START.md) → [User Guide](guides/USER-GUIDE.md) | 5–15 min |
| **Connecting an AI agent** | [MCP Quick Start](guides/MCP-QUICKSTART.md) → [AI Agent guide](perspectives/AI-AGENT.md) | 5 min |
| **Setting up your IDE** | [LSP Quick Start](guides/LSP-QUICKSTART.md) → [IDE setup](guides/LSP-IDE-INTEGRATION-GUIDES.md) | 5 min/IDE |
| **An engineering manager** | [Team Adoption Guide](perspectives/ENGINEERING-MANAGER.md) | 10 min |
| **An architect** | [Architecture Intelligence](perspectives/ARCHITECT.md) | 12 min |
| **An executive** | [Executive Brief](perspectives/EXECUTIVE.md) | 5 min |

Not listed? The [role selector](perspectives/README.md) has nine role-specific guides.

---

## By the numbers

**v1.45.0** · 76 CLI commands · 52 MCP tools · 4,800+ tests · 200–300 files/sec indexing ·
sub-second search · Java 21 · Apache 2.0

---

## The wider stack

Synthesis is part of an open knowledge-infrastructure stack:
the [Knowledge Context Protocol](https://github.com/Cantara/knowledge-context-protocol) spec
(submitted to the Linux Foundation's Agentic AI Foundation),
[kcp-agent](https://github.com/Cantara/kcp-agent) (the deterministic reference agent), and
[kcp-memory](https://github.com/Cantara/kcp-memory) (episodic memory for Claude Code).
The story behind the tool — from a 197,831-line codebase nobody could navigate to the
comprehension bottleneck it revealed — lives on the
[Knowledge Infrastructure pages](https://wiki.totto.org/knowledge-infrastructure/).
