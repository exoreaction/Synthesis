# Synthesis for Executives

**Your weekly briefing, pipeline status, and client health -- generated in 30 seconds, not 3 hours. Now with real temporal insights and a self-organizing workspace.**

*Updated for Synthesis v1.15.0 (3,933 tests passing) -- February 2026*

---

## What This Solves for You

You need to know the state of your business: pipeline status, client health, product progress, decisions that need your attention. Today that information lives across dozens of documents, and assembling it into a coherent picture requires either a skilled assistant or hours of your own time.

Synthesis reads your business documents -- pipeline files, activity logs, opportunity directories, product status files -- and generates executive reports on demand. The `exo` command is your single entry point.

**New in v1.11.1:** Synthesis now goes beyond search and reporting. Your workspace can organize itself -- files route to the right location automatically, stale documents expire, and activity logs write themselves. The result is less overhead on your team and more reliable data for your decisions.

---

## One-Time Setup (5 Minutes)

You only need to do this once. After setup, everything works from the `exo` command.

### Step 1: Store Your API Key

Synthesis uses Claude for report generation. Store the key once; it persists across sessions.

```
synthesis credentials set ANTHROPIC_API_KEY sk-ant-api03-your-key-here
```

Verify it is stored:

```
synthesis credentials status
```

You should see: `ANTHROPIC_API_KEY  -- active (no env var override)`

### Step 2: Verify Your Workspace

The `exo` command defaults to `~/Documents` as the workspace. If your business documents live elsewhere, set the environment variable:

```
export SYNTHESIS_WORKSPACE=~/Documents
```

Add that line to your `~/.bashrc` or `~/.zshrc` to make it permanent.

### Step 3: Initialize and Index

```
synthesis init -d ~/Documents
synthesis scan -d ~/Documents
```

This indexes all your documents. Takes 10-60 seconds depending on volume.

---

## Daily Use: The `exo` Command

The `exo` command is your interface. No flags to remember, no technical knowledge needed.

### Weekly CEO Briefing

```
exo report
```

Generates a comprehensive weekly report covering:
- Business highlights from the past week
- Pipeline status and movement
- Client activity and health indicators
- Decisions or risks that need attention
- Upcoming events and deadlines

**Time to generate:** 15-30 seconds (cached results return instantly).
**Cost:** Approximately $0.02-0.08 per report (Claude API usage).

### What Needs a Decision Today?

```
exo decisions
```

Surfaces items from your business documents that require executive action -- contract approvals, strategic choices, deadline-driven decisions.

### Pipeline Status

```
exo pipeline
```

Summarizes your sales pipeline: closed deals, hot leads, warm prospects, total pipeline value.

### Recent Activities

```
exo activities
```

What happened this week across clients, products, and internal projects.

### Client Health Check

```
exo client Mynder
```

Generates a report for a specific client relationship. Synthesis finds the client's opportunity directory, README files, activity logs, and contracts, then summarizes the current state.

Other examples:

```
exo client SpareBank1
exo client Elprint
```

### Product Status

```
exo product Synthesis
exo product lib-pcb
```

Generates a product status report: development activity, recent changes, open issues, roadmap items.

### Smart Mode

If you type a name without specifying `client` or `product`, `exo` tries to find a matching client first:

```
exo Mynder
```

This is equivalent to `exo client Mynder`.

### Interactive Dashboard

```
exo
```

Opens the interactive dashboard with context-aware navigation.

---

## Temporal Intelligence: Know What Actually Changed

Traditional reports summarize a static snapshot. Synthesis v1.11.1 gives you **temporal context** -- reports grounded in what actually happened during a specific period.

### AI Summaries with Real Change Data

```
synthesis summary --since 7d
synthesis summary --since 2w --level executive
synthesis summary --since 2026-02-01
```

This is not a generic summary. The `--since` flag loads actual change events from the changelog database and injects them directly into the AI analysis. The result reflects what really happened -- new files added, documents modified, items removed -- during the period you specify.

**Supported time formats:** `24h`, `7d`, `2w`, `3m`, or an ISO date like `2026-02-01`.

**Prerequisite:** Run `synthesis maintain` periodically (or on a schedule) to capture snapshots.

### Cross-Workspace Change Reports

```
synthesis changelog --since 7d
synthesis changelog --weekly
```

Shows the raw file-level changes across all indexed repositories. Useful when you want specifics rather than an AI narrative -- which files were added, modified, or deleted, with significance filtering (noise, normal, notable, critical).

### Weekly Briefing Workflow (Recommended)

Pair both for a complete picture:

1. `synthesis summary --since 7d` -- the AI-generated narrative of the week
2. `synthesis changelog --weekly` -- the specific file-by-file changes

Save either to a file for sharing:

```
synthesis summary --since 7d --format markdown --output weekly-summary.md
synthesis changelog --weekly --format markdown --output weekly-changes.md
```

---

## Self-Organizing Workspace: Less Overhead, Better Data

The biggest advance in v1.11.1 is that workspaces can now keep themselves organized without manual effort.

### The Problem

Documents pile up. Files land in the wrong place. Stale content clutters search results. Your team spends time on housekeeping instead of productive work. And when data is disorganized, the reports you generate from it are less reliable.

### The Solution

Synthesis now includes automated workspace hygiene:

| Capability | What It Does | Business Outcome |
|-----------|-------------|-----------------|
| **Self-routing files** | Files automatically move to the right folder based on content | Less time deciding "where does this go?" |
| **Stale file cleanup** | `synthesis sweep` identifies and archives old root-level files | Workspace stays current and relevant |
| **Expiring documents** | `synthesis ttl` sets time-to-live on temporary files | Drafts and temp files clean themselves up |
| **Health scoring** | `synthesis health` scores workspace hygiene 0-100 | Know when your knowledge base needs attention |
| **Fragmentation detection** | `synthesis scatter` finds documents spread across wrong locations | Consolidate before it becomes a problem |

### Automated Activity Logging

```
synthesis maintain --update-activity-log
```

This automatically generates a dated entry in your `ACTIVITY-LOG.md` listing what was added, modified, or removed -- with an optional AI narrative. No more manual status updates.

This feeds directly into `exo activities` and `exo report`, so your weekly briefings are based on real tracked activity, not someone's memory of what happened.

### Staging Pipeline: Incoming Documents Route Themselves

When new files arrive (downloads, email attachments, shared documents), the staging pipeline handles them:

```
synthesis staging ingest && synthesis staging route && synthesis maintain
```

The routing engine now uses AI-powered content classification. Even binary files -- PDFs, images, invoices -- get analyzed and routed to the correct organizational folder. A downloaded invoice from Client X automatically ends up in the Client X directory.

**Result:** Less time on file organization. More reliable data for reports.

---

## Security Posture (CKG-5)

Synthesis v1.15.0 includes automated security analysis across your entire codebase portfolio. This is relevant for executives because:

**Risk visibility without hiring a security consultant.** A single command scans all repositories for known vulnerabilities (CVEs), hardcoded credentials, and architectural security issues. In a Feb 22, 2026 scan of 5 workspaces (~12,000 files), Synthesis found 3 real CVEs in production dependencies -- including a critical remote code execution vulnerability (Text4Shell). All findings were triaged within hours.

**AI-specific security.** As your team adopts AI tools (Claude Code, Copilot, Cursor), new attack surfaces emerge: prompt injection, RAG poisoning, unconfirmed AI-initiated actions. Synthesis is the only tool that detects these. When Synthesis scanned itself, it found 23 prompt injection vectors and 12 missing security boundaries -- and fixed them the same day.

**Compliance and audit trail.** Security findings are persisted in the database and trackable over time. Run weekly scans to show trending improvement in your security posture.

```
synthesis code-graph security --severity HIGH --format json
```

---

## Impact Assessment

Before making a major change to a document or process, understand what else it affects:

```
synthesis impact PIPELINE-STATUS.md
```

Shows which other files and processes depend on or reference this document. Useful before restructuring client directories or changing standard templates.

---

## Saving Reports to File

Any report can be saved to a file for sharing or archiving:

```
exo report --output weekly-briefing.md
exo pipeline --output pipeline-status.md
```

The report is written to the specified file. Summary statistics (token count, cost, generation time) are printed to the terminal.

---

## Understanding Report Output

### Report Targets

Reports can be tailored for different audiences:

| Target | Format | Use when |
|--------|--------|----------|
| `ceo` (default) | Concise, action-oriented | Weekly briefing for yourself |
| `board` | Structured, formal | Board meeting preparation |
| `investor` | Metrics-focused, growth-oriented | Investor updates |

Change the target:

```
exo report --target board
exo report --target investor
```

### Coverage Period

By default, reports cover the last week. Adjust with `--period`:

```
exo report --period 2w     # Last 2 weeks
exo report --period 1m     # Last month
```

---

## Cost Estimation

Before generating a report, you can preview what documents will be analyzed and the estimated cost:

```
synthesis report -d ~/Documents --estimate
```

Output shows:
- Documents that will be analyzed (with file sizes)
- Estimated token count
- Estimated cost in USD

This does not call the AI and costs nothing.

---

## Report Caching

Reports are cached automatically. If you run the same report type and your documents have not changed, the cached version is returned instantly (no API cost).

```
synthesis report -d ~/Documents --cache-stats     # View cache statistics
synthesis report -d ~/Documents --no-cache        # Force a fresh report
synthesis report -d ~/Documents --cache-clear     # Clear all cached reports
```

Note: `synthesis summary --since` always bypasses the cache, since temporal queries should always reflect current data.

---

## Upcoming Events and Deadlines

```
synthesis upcoming -d ~/Documents
```

Reads `UPCOMING.md` from your workspace root and shows:
- Confirmed events with dates
- Overdue items (past-due, not marked done)
- Pipeline actions
- Content calendar items

Options:

```
synthesis upcoming -d ~/Documents --days 14     # Next 14 days only
synthesis upcoming -d ~/Documents --all         # All items
synthesis upcoming -d ~/Documents --overdue     # Past-due items
synthesis upcoming -d ~/Documents --actions     # Include actions from indexed docs
```

---

## How It Works (Without the Technical Details)

Synthesis scans your `~/Documents` directory and builds a searchable index of all your files. When you request a report, it:

1. Finds relevant business documents (pipeline files, activity logs, client directories)
2. Reads their content
3. Sends the content to Claude with a structured prompt for the report type you requested
4. Returns the formatted report

All your files stay on your machine. Only the content needed for a specific report is sent to the Claude API, and only when you explicitly request a report.

**New in v1.11.1:** The workspace also maintains itself -- routing incoming files, archiving stale content, and logging activity -- so the data powering your reports stays current and well-organized without manual effort.

---

## Quick Reference

| What you want | Command |
|--------------|---------|
| Weekly briefing | `exo report` |
| AI summary with real change data | `synthesis summary --since 7d` |
| Cross-workspace change report | `synthesis changelog --weekly` |
| Decisions needed | `exo decisions` |
| Pipeline status | `exo pipeline` |
| Recent activity | `exo activities` |
| Client health | `exo client <name>` |
| Product status | `exo product <name>` |
| Interactive dashboard | `exo` |
| Impact assessment | `synthesis impact <file>` |
| Workspace health score | `synthesis health` |
| Archive stale files | `synthesis sweep` |
| Auto-generate activity log | `synthesis maintain --update-activity-log` |
| Upcoming events | `synthesis upcoming -d ~/Documents` |
| Cost preview | `synthesis report -d ~/Documents --estimate` |
| Save to file | `exo report --output report.md` |
| Board-format report | `exo report --target board` |
| Last 2 weeks | `exo report --period 2w` |

---

## Troubleshooting

### "AI not configured"

Run: `synthesis credentials set ANTHROPIC_API_KEY sk-ant-your-key`

### Reports seem outdated

Re-scan the workspace: `synthesis scan -d ~/Documents`
Or force a fresh report: `exo report --no-cache`

### "No documents found"

Ensure your business documents (pipeline status, activity logs, client directories) are inside the workspace directory. Synthesis looks for files matching common business document patterns (PIPELINE-STATUS.md, ACTIVITY-LOG.md, opportunity directories, etc.).

### Report takes too long

First-time reports for large workspaces may take 30-60 seconds. Subsequent reports for the same topic are cached and return instantly unless documents have changed.

### summary --since shows "changelog not available"

Run `synthesis maintain` first. The `maintain` command captures snapshots that the temporal analysis depends on. For ongoing use, schedule `synthesis maintain` to run periodically (e.g., daily cron).

---

**Related guides:**
- [Engineering Manager Guide](./ENGINEERING-MANAGER.md) -- for your technical leads
- [Product Manager Guide](./PRODUCT-MANAGER.md) -- for product owners
- [Full User Guide](../guides/USER-GUIDE.md) -- complete command reference
