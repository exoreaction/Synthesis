# Synthesis for Product Managers

**Your product spans 8,000+ files across code, docs, demos, and sales materials. Find any of them in under a second -- and let the workspace organize itself.**

*Updated for Synthesis v1.11.1 (~2,500 tests passing) -- February 2026*

---

## What This Gives You

Product managers spend 2-4 hours per week searching for product knowledge: demo videos, sales decks, feature docs, architecture diagrams, customer case studies. Synthesis indexes all of it and makes it instantly searchable.

But Synthesis v1.11.1 goes much further:

- **Temporal intelligence:** `synthesis summary --since 7d` generates AI summaries grounded in what actually changed -- not generic overviews
- **Cross-workspace visibility:** `synthesis changelog` shows what changed across all repositories in a single report
- **Self-organizing workspaces:** Files route themselves to the right location, stale content expires, activity logs write themselves
- **Automated reporting:** Activity logs and sprint summaries generated from real change data, not someone's recollection
- **Content-intelligent routing:** Even PDFs, images, and binary files get classified and filed automatically

**Validated performance:** 36,342 files indexed across 8 workspaces. Sub-second search (0.4s typical). 92-95% reduction in retrieval time. Cross-repo dependency graphs covering 58 repos and 429 dependencies in under 31 seconds.

---

## Sprint Summaries with Real Data

The most impactful new capability for product teams: AI summaries that reflect what actually happened.

### AI Summary with Temporal Context

```bash
synthesis summary --since 7d
synthesis summary --since 2w --level manager --perspective product_manager
synthesis summary --since 2026-02-01 --format markdown --output sprint-summary.md
```

**How this differs from a generic summary:** The `--since` flag loads actual change events from the changelog database -- files added, modified, deleted -- and injects them directly into the AI prompt. The AI analysis is grounded in real activity, not a static snapshot.

**Use cases:**
- Sprint retrospective data: what actually shipped in the last 2 weeks
- Stakeholder updates: what changed since the last board meeting
- Release notes input: what was added since a specific date

**Supported time formats:** `24h`, `7d`, `2w`, `3m`, or an ISO date like `2026-02-01`.

**Prerequisite:** Run `synthesis maintain` periodically to capture snapshots.

### Perspectives for Different Audiences

The summary command supports 8 perspectives. The ones most useful for product managers:

| Perspective | Focus | When to Use |
|-------------|-------|-------------|
| `product_manager` | Feature velocity, user-facing risk, release planning | Sprint reviews, roadmap updates |
| `executive` | Business risk, ROI, competitive positioning | Board updates, investor reports |
| `engineering_manager` | Team velocity, technical debt, sprint planning | Capacity planning, tech debt reviews |
| `general` | Balanced overview across all dimensions | Default weekly check-in |

```bash
synthesis summary --since 7d --perspective product_manager --level manager
synthesis summary --since 1m --perspective executive --level executive --output board-update.md
```

---

## Cross-Team Visibility with Changelog

```bash
synthesis changelog --since 7d
synthesis changelog --weekly
synthesis changelog --since 2w --format markdown --output sprint-changes.md
```

The changelog shows file-level changes across all indexed repositories. Unlike `summary` (which gives an AI narrative), `changelog` gives you the specifics:

```
Synthesis - Change Report

Period: 2026-02-13 to now
Summary: 18 added, 5 modified, 2 deleted, 1 moved

+ eXOReaction/clients/ItemConsulting/proposal.md
+ eXOReaction/marketing/linkedin/post-2026-02-15.md
~ CLAUDE.md
~ eXOReaction/business/PIPELINE-STATUS.md
> eXOReaction/business/templates/contract.md [CRITICAL]

12 noise events filtered. Use --significance noise to see all.
```

**Significance filtering** separates signal from noise:
- `critical` -- security files, production configs, mass deletions
- `notable` -- READMEs, build files, major refactors
- `normal` -- standard development changes (default filter)
- `noise` -- temp files, logs, caches

**Weekly workflow:** Run both `synthesis summary --since 7d` (the narrative) and `synthesis changelog --weekly` (the specifics) for a complete sprint picture.

---

## Impact Assessment Before Changes

```bash
synthesis impact src/auth/AuthService.java
synthesis impact PIPELINE-STATUS.md
```

Before a major refactor, feature removal, or document restructuring, `impact` shows you what other files are likely affected based on historical co-change data. Useful for estimating the blast radius of product decisions.

---

## Business Reports

### Product Status Reports

```bash
synthesis report --product Synthesis
synthesis report --product lib-pcb
```

Generates a report on a specific product's status: recent development activity, open items, roadmap progress, and client-facing status.

### Client Health Reports

```bash
synthesis report --client Mynder
synthesis report --client Elprint
synthesis report --client SpareBank1
```

Generates a report on a specific client relationship. Synthesis discovers the client's opportunity directory, README files, activity logs, and contracts, then summarizes the current state.

### Pipeline Reports

```bash
synthesis report --topic pipeline
```

Summarizes your sales pipeline from indexed business documents.

### Weekly Executive Update

```bash
synthesis report --topic weekly
synthesis report --topic executive
```

Full executive update covering business highlights, pipeline, client activity, and decisions needed.

### Decisions Needed

```bash
synthesis report --topic decisions
```

Surfaces items requiring action: contract approvals, strategic choices, deadline-driven decisions.

### Report Options

| Option | Purpose | Values |
|--------|---------|--------|
| `--target` | Audience format | `ceo` (default), `board`, `investor` |
| `--period` | Coverage period | `1w` (default), `2w`, `1m` |
| `--output <file>` | Save to file | Any path |
| `--estimate` | Cost preview (no AI call) | Flag |
| `--no-cache` | Force fresh generation | Flag |

### Cost Awareness

Reports use AI and have a small cost (approximately $0.02-0.08 per report). Preview before generating:

```bash
synthesis report --topic pipeline --estimate
```

Shows which documents will be analyzed, estimated token count, and cost.

---

## Automated Activity Logging

Instead of manually updating status documents, let Synthesis generate them from real data:

```bash
synthesis maintain --update-activity-log
```

This auto-generates a dated entry in `ACTIVITY-LOG.md` with:
- Files added, modified, and removed during the period
- An optional AI narrative summarizing the activity
- Newest entries first (reverse chronological)

**How this helps product teams:**
- Sprint retrospective preparation -- the log is already written from real data
- Investor/client updates -- `exo activities` reads the activity log for on-demand summaries
- Audit trail -- every change is recorded without manual effort
- Status reports -- `synthesis report --topic activities` generates reports from the log

**Recommended setup:** Schedule `synthesis maintain --update-activity-log` as a daily cron job. Activity logs accumulate automatically; reports stay current.

---

## Self-Organizing Workspace

The biggest conceptual advance in v1.11.1: workspaces can now keep themselves organized.

### Directory Identity

```bash
synthesis sync
```

Establishes per-directory identity -- what each folder accepts. Once set up, files route automatically to the right place. The `sync` command reads existing content to infer what each directory is for, then records that identity so the routing engine can use it.

### Automated Housekeeping

| Command | What It Does | Product Team Benefit |
|---------|-------------|---------------------|
| `synthesis sweep [--dry-run]` | Archives stale root-level files | Workspace root stays clean |
| `synthesis ttl set "*.md" --days 30` | Expiring documents | Draft specs auto-clean after review period |
| `synthesis consolidate "Client"` | Merges scattered files to canonical location | No more client docs in 3 different places |
| `synthesis health` | Workspace hygiene score (0-100) | Know when your knowledge base needs attention |
| `synthesis prune` | Removes stale index entries | Search results stay relevant |
| `synthesis maintain --rebalance` | Recovers misplaced files periodically | Self-healing workspace |

**Result:** The "where do I put this?" friction that slows teams down is eliminated. Files go to the right place automatically, and the workspace stays organized without manual effort.

---

## Staging Pipeline: Managing Incoming Documents

When new files arrive -- downloads, email attachments, shared documents -- the staging pipeline handles classification and routing:

```bash
synthesis staging ingest && synthesis staging route && synthesis maintain
```

### Content-Intelligent Routing

The routing engine now uses AI-powered content classification. It reads companion `.synthesis.md` descriptions (generated by `synthesis enrich`) and classifies even binary files:

- A downloaded PDF invoice from Client X automatically routes to the Client X directory
- A product screenshot routes to the product's media folder
- An unrecognized file gets suggestions for where it might belong

**Setup for binary file routing:**

```bash
synthesis enrich                    # Generate AI descriptions for PDFs, images, videos
synthesis staging ingest            # Ingest new files from staging areas (Downloads, etc.)
synthesis staging route             # Route files to the right location
```

**Routing indicators:**
- `~` prefix = content-routed (high confidence, auto-moved)
- `?` prefix = suggestion (low confidence, requires manual decision)

### Expanding Coverage

```bash
synthesis discover
```

Finds new repositories and directories that could be indexed, without requiring manual configuration. Useful when your product footprint expands to new repos.

---

## Tracking Events and Deadlines

```bash
synthesis upcoming -d ~/Documents
```

Reads `UPCOMING.md` from your workspace and displays:
- **Confirmed events** with dates
- **Overdue items** (past-due, not marked done)
- **Pipeline actions** (assigned to owners)
- **Content calendar** items

### Options

```bash
synthesis upcoming --days 14          # Next 14 days only
synthesis upcoming --all              # Show all items
synthesis upcoming --overdue          # Show past-due items
synthesis upcoming --actions          # Include actions from indexed opportunity docs
synthesis upcoming --format markdown  # Output as markdown
```

### UPCOMING.md Format

Create `UPCOMING.md` in your workspace root:

```markdown
# Upcoming

## Events
- 2026-03-01  JavaZone CFP deadline  [confirmed]
- 2026-03-15  Client workshop (SpareBank 1)  [confirmed]
- 2026-04-01  NDC Oslo submission deadline

## Content
- 2026-02-20  LinkedIn post: Synthesis launch  [recommended]
- TBD  Conference talk proposal draft

## Actions
- [ ] Mynder: Schedule follow-up meeting
- [ ] SpareBank 1: Send proposal draft
- [ ] Internal: Update sales deck
```

Synthesis parses dated items, TBD items, action items (with owners), and done items (`[x]` or `[done]`).

---

## Multi-Format Product Search

Search across all product materials in one command:

```bash
synthesis search "real-time analytics"
```

Returns results across all file types:
- **Code:** AnalyticsEngine.java, RealtimeProcessor.java
- **Docs:** analytics-architecture.md, performance-benchmarks.md
- **Videos:** real-time-analytics-demo.mp4 (with duration, resolution)
- **PDFs:** Analytics Product Brief (with page count)
- **Config:** analytics-config.yaml

### Demo Preparation

Client call in 2 hours. Need to show your "authentication" feature:

```bash
synthesis search "authentication demo"
synthesis search "authentication"
synthesis relate src/auth/AuthService.java    # What connects to it
```

All materials found in seconds. Watch the demo video, review the docs, open the code. Prepared in 15 minutes instead of scrambling for an hour.

---

## Organizational Intelligence

### Discover Your Organization

```bash
synthesis org scan
```

Auto-discovers organizational structure from your workspace: companies, clients, products, status (active, past, prospect).

### View Structure

```bash
synthesis org list
```

Shows the discovered hierarchy: companies, their clients, products, and current status.

### Classify Content

```bash
synthesis org classify
```

Classifies files by organization -- useful for understanding where content belongs.

---

## Content Management

### Make Binary Files Searchable

PDFs, videos, and images are not text-searchable by default. Synthesis creates companion files:

```bash
synthesis enrich                  # Generate companions for all binary assets
synthesis enrich --type video     # Only for videos
synthesis enrich --level ai       # Rich AI descriptions (requires API key)
synthesis enrich --stats          # Coverage statistics
synthesis scan                    # Re-scan to index new companions
```

After enrichment, `synthesis search "product demo"` finds your demo video by its description, not just its filename.

### Export Index

```bash
synthesis export                            # Export as default format
synthesis export --format json              # JSON export
synthesis export --format markdown          # Markdown export
```

Useful for sharing an inventory of product materials with sales or marketing teams.

### Extract Slides

```bash
synthesis extract-slides presentation.pdf
```

Extracts individual slides from a PDF presentation as PNG images. Useful for creating thumbnails, social media posts, or product screenshots.

---

## Feature Relationship Mapping

Understand which features depend on each other:

```bash
synthesis relate src/analytics/AnalyticsEngine.java
```

Results show incoming references -- other features that depend on Analytics:
- Dashboard (DashboardService.java)
- Reporting (ReportGenerator.java)
- Alerts (AlertProcessor.java)

When a client asks "If we buy Analytics, what else does that enable?" you have a concrete answer: Dashboard, Reporting, and Alerts all build on Analytics.

---

## Knowledge Integrity Monitoring

Synthesis now tracks links between documentation, skills, and source code. When source files change, `synthesis maintain` warns about documentation that may be stale:

```
Warning: Knowledge edge degraded: [doc] -- update [skill]
```

This keeps your product documentation honest as the codebase evolves. No more shipping outdated feature descriptions because the code changed and nobody updated the docs.

---

## Content Audit Workflow

Find all product materials for a quarterly review:

```bash
synthesis search "demo"                     # All demo materials
synthesis search "presentation"              # All presentations
synthesis search "case study"                # Customer success stories
synthesis search "roadmap"                   # Roadmap documents
synthesis search "competitor"                # Competitive analysis
```

Complete content audit in 5 minutes instead of 2-4 hours manually searching folders and drives.

---

## Workspace Health Monitoring

```bash
synthesis health
```

Scores your workspace hygiene on a 0-100 scale, flagging:
- **E001:** Phantom paths (configured but non-existent directories)
- **E002:** Build artifacts that should not be indexed
- **W001:** Empty directories cluttering the structure
- **W002:** Loose files at the workspace root

Use `synthesis health --fix-config` to auto-fix configuration issues. Schedule `synthesis health` periodically to catch workspace degradation before it affects search quality and report accuracy.

---

## Quick Reference

```
# Temporal Intelligence (NEW)
synthesis summary --since 7d                  # AI summary grounded in real changes
synthesis summary --since 2w --perspective product_manager  # PM-focused summary
synthesis changelog --weekly                  # Cross-workspace change report
synthesis changelog --since 2w --format markdown  # Shareable change report
synthesis impact <file>                       # What else is affected by this change?

# Automated Workspace Management (NEW)
synthesis maintain --update-activity-log      # Auto-generate activity log entries
synthesis sweep [--dry-run]                   # Archive stale root files
synthesis ttl set "*.md" --days 30            # Set file expiration
synthesis health                              # Workspace hygiene score
synthesis sync                                # Establish directory identity
synthesis consolidate "Entity"                # Merge scattered files
synthesis discover                            # Find new repos to index

# Staging Pipeline (NEW)
synthesis staging ingest                      # Ingest new files
synthesis staging route                       # Content-intelligent routing
synthesis enrich                              # AI descriptions for binary files

# Business Reports
synthesis report --product <name>             # Product status report
synthesis report --client <name>              # Client health report
synthesis report --topic pipeline             # Pipeline status
synthesis report --topic decisions            # Decisions needed
synthesis report --topic weekly               # Weekly executive update
synthesis report --estimate                   # Cost preview

# Events & Deadlines
synthesis upcoming                            # Events and deadlines
synthesis upcoming --actions                  # Include scanned actions

# Search & Discovery
synthesis search "query"                      # Multi-format search
synthesis relate <file>                       # Feature dependency map
synthesis org scan                            # Discover organization
synthesis org list                            # View organization

# Content Management
synthesis enrich                              # Make binary files searchable
synthesis export                              # Export index
synthesis extract-slides <pdf>                # Extract slides as PNG
```

---

**Related guides:**
- [Executive Guide](./EXECUTIVE.md) -- for CEO-level reporting
- [Developer Guide](./DEVELOPER.md) -- for your engineering team
- [Workshop Facilitator Guide](./WORKSHOP-FACILITATOR.md) -- running product demos
- [Full User Guide](../USER-GUIDE-V2.md) -- complete command reference
