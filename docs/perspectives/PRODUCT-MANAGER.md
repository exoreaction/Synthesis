# Synthesis for Product Managers

**Your product spans 8,000 files across code, docs, demos, and sales materials. Find any of them in under a second.**

---

## What This Gives You

Product managers spend 2-4 hours per week searching for product knowledge: demo videos, sales decks, feature docs, architecture diagrams, customer case studies. Synthesis indexes all of it and makes it instantly searchable.

But Synthesis v1.8.0 goes further. The `report` command generates structured business reports from your workspace documents. The `upcoming` command tracks events and deadlines. The `org` command maps your organizational structure.

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

## Research Reports for Product Strategy

For deeper analysis:

```bash
synthesis research --topic quality           # Code quality analysis
synthesis research --topic evolution         # Technical evolution trends
synthesis research --output product-health.md
```

Useful for quarterly product reviews, technical debt prioritization, and roadmap planning.

---

## Quick Reference

```
synthesis report --product <name>           # Product status report
synthesis report --client <name>            # Client health report
synthesis report --topic pipeline           # Pipeline status
synthesis report --topic decisions          # Decisions needed
synthesis report --topic weekly             # Weekly executive update
synthesis report --estimate                 # Cost preview
synthesis upcoming                          # Events and deadlines
synthesis upcoming --actions                # Include scanned actions
synthesis search "query"                    # Multi-format search
synthesis org scan                          # Discover organization
synthesis org list                          # View organization
synthesis enrich                            # Make binary files searchable
synthesis export                            # Export index
synthesis extract-slides <pdf>              # Extract slides as PNG
synthesis relate <file>                     # Feature dependency map
synthesis research --topic quality          # Deep quality analysis
```

---

**Related guides:**
- [Executive Guide](./EXECUTIVE.md) -- for CEO-level reporting
- [Developer Guide](./DEVELOPER.md) -- for your engineering team
- [Workshop Facilitator Guide](./WORKSHOP-FACILITATOR.md) -- running product demos
- [Full User Guide](../USER-GUIDE-V2.md) -- complete command reference
