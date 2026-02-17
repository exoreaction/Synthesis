# Synthesis for Executives

**Your weekly briefing, pipeline status, and client health -- generated in 30 seconds, not 3 hours.**

---

## What This Solves for You

You need to know the state of your business: pipeline status, client health, product progress, decisions that need your attention. Today that information lives across dozens of documents, and assembling it into a coherent picture requires either a skilled assistant or hours of your own time.

Synthesis reads your business documents -- pipeline files, activity logs, opportunity directories, product status files -- and generates executive reports on demand. The `exo` command is your single entry point.

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

---

## Quick Reference

| What you want | Command |
|--------------|---------|
| Weekly briefing | `exo report` |
| Decisions needed | `exo decisions` |
| Pipeline status | `exo pipeline` |
| Recent activity | `exo activities` |
| Client health | `exo client <name>` |
| Product status | `exo product <name>` |
| Interactive dashboard | `exo` |
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

---

**Related guides:**
- [Engineering Manager Guide](./ENGINEERING-MANAGER.md) -- for your technical leads
- [Product Manager Guide](./PRODUCT-MANAGER.md) -- for product owners
- [Full User Guide](../USER-GUIDE-V2.md) -- complete command reference
