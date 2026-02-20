# Synthesis for Engineering Managers

**Your team adopted AI coding tools. Output tripled. But are you shipping faster? Synthesis gives you the visibility to answer that question.**

---

## The Problem You Are Solving

Your developers have Copilot, Claude Code, or Cursor. They generate code 10-50x faster than before. But your sprint velocity increased maybe 1.5x. The gap is lost to searching -- finding the right file, tracing dependencies, understanding what already exists before building something new.

**The cost on a 10-person team:**
- 40-60% of developer time spent on retrieval (measured industry average)
- 4-6 FTE-equivalent salaries spent on searching, not creating
- AI made it worse: more output to search through, same search speed

Synthesis closes this gap by indexing everything and making it searchable in under a second with relationship tracking.

---

## What Synthesis Gives You as a Manager

### 1. Codebase Health Visibility

```bash
synthesis insights
```

Generates a health report including:
- File count by type, language, and directory
- Largest files (potential god classes)
- Most-connected files (highest coupling, highest risk)
- Dead code candidates (files with zero incoming references)
- Test coverage gaps (source files without test counterparts)

### 2. Workspace Health Score

```bash
synthesis health
```

New in v1.11.1 -- a quantitative workspace hygiene metric. Returns a score (0-100) with letter grade (A through F). Detects phantom config paths, build artifacts at root, empty directories, and loose files. Useful as an engineering excellence metric across teams.

```bash
synthesis health --format json    # Machine-readable for dashboards
synthesis health --fix-config     # Auto-repair detected config issues
```

### 3. Architecture Quality Metrics

```bash
synthesis architecture
```

Detects anti-patterns automatically:

| Issue | What it means | Why you care |
|-------|--------------|-------------|
| God classes (>1,000 lines) | Files doing too much | Maintenance risk, hard to test |
| Circular dependencies | A depends on B depends on A | Deployment nightmares |
| Dead code | Files nothing references | Wasted maintenance effort |
| Missing documentation | Directories without README | Onboarding friction |
| Test coverage gaps | Source without test | Quality risk |
| High coupling | Files with 20+ incoming refs | Change = widespread breakage |

### 4. Quantitative Metrics

```bash
synthesis metrics
```

Performance and usage statistics for your Synthesis installation, useful for tracking adoption across the team.

### 5. Cross-Repository Visibility

```bash
synthesis cross-repo-deps
```

Maps dependencies between repositories. Shows which repos depend on which others, with edge counts. Essential for microservice architectures where changes in one repo can break another.

Real example: 58 repositories, 429 cross-dependencies mapped in under 31 seconds.

### 6. AI-Powered Temporal Summaries

```bash
synthesis summary --since 7d       # What happened this week (AI-generated)
synthesis summary --since 24h      # What happened today
synthesis summary --since 2w       # Last two weeks (sprint summary)
synthesis summary --since 3m       # Quarterly review context
```

New in v1.9.5 -- temporal summaries use actual change data from `maintain` snapshots. The `--since` flag loads `ChangeEvent` data and injects it into the AI prompt, so the summary is grounded in what actually changed, not just the current state. Supports durations (`7d`, `24h`, `2w`, `3m`) and ISO dates (`2026-02-01`).

Requires `synthesis maintain` to have run at least once to populate snapshots.

### 7. Change Tracking and Velocity

```bash
synthesis changelog                           # Cross-workspace change summary
synthesis changelog --since 7d                # Last 7 days
synthesis changelog --weekly                  # Executive weekly report
synthesis changelog --significance critical   # Only critical changes
synthesis changed --since 2026-02-01          # What changed since a date
```

Track what is changing across your codebase over time. Useful for sprint reviews, release notes, and understanding team activity. Significance filtering separates noise from signal -- mass deletions (>10 files) are flagged as CRITICAL automatically.

### 8. Change Impact Analysis

```bash
synthesis impact src/core/AuthService.java    # Co-change analysis
```

Before approving changes, understand the blast radius. Shows which files tend to change together, helping you assess risk before merging.

---

## Automated Housekeeping

New in v1.11.1, Synthesis provides a suite of commands to keep workspaces clean without manual effort.

### Sweep: Automated Stale File Cleanup

```bash
synthesis sweep --dry-run          # Preview what would be cleaned up
synthesis sweep --yes              # Execute cleanup
synthesis sweep --days 14          # Lower age threshold (default: 30 days)
synthesis sweep --archive-only     # Skip smart routing, archive everything
```

Identifies and handles stale root-level files: session scripts, ephemeral docs, dated reports, archives. With directory identities configured (`synthesis sync`), sweep routes files to the right directory instead of blindly archiving.

### Prune: Remove Orphaned Index Entries and Empty Directories

```bash
synthesis prune --yes              # Remove empty directories
synthesis prune --path reports/    # Limit to a sub-path
```

### TTL: Automatic File Expiry

```bash
synthesis ttl set "TONIGHT-*.md" --days 3     # Expires after 3 days
synthesis ttl set "*.tmp" --days 1            # Short-lived temp files
synthesis ttl list                             # Show active rules
synthesis ttl check --archive                  # Archive expired files
```

Register glob-pattern rules that declare when specific files should be cleaned up. Run `ttl check --archive` in a cron job for hands-off lifecycle management.

### Consolidate: Merge Fragmented Content

```bash
synthesis scatter --all                        # Find content spread across locations
synthesis consolidate "Entity Name"            # Preview merge
synthesis consolidate "Entity Name" --execute  # Execute merge
```

Detects when content about the same entity (client, project, topic) is spread across multiple directories. The consolidate command merges them and updates cross-references in markdown files.

### The Self-Organizing Workspace

The recommended automation sequence for clean workspaces:

```bash
synthesis maintain --sync --update-activity-log   # Index + sync + activity log
synthesis sweep --yes                              # Clean up stale files
synthesis ttl check --archive                      # Archive expired files
```

Set this up as a cron job or systemd timer and workspaces stay organized automatically.

---

## Activity Log and Automated Reporting

```bash
synthesis maintain --update-activity-log
```

Auto-generates dated entries in `ACTIVITY-LOG.md` using actual change data. This provides:
- Automated daily/weekly activity summaries without manual effort
- Audit trail of what happened in the workspace
- Input to `synthesis report --topic activities` for status reporting

Gracefully degrades to a structured diff when no API key is present. Skips if today's entry already exists.

Combined with temporal summaries:

```bash
synthesis summary --since 7d         # AI summary grounded in the week's actual changes
synthesis changelog --weekly         # Cross-workspace change report for stakeholders
```

This gives you two complementary views: the AI narrative (summary) and the factual change list (changelog).

---

## Onboarding New Developers

New developer onboarding typically takes 3-4 weeks. With Synthesis, aim for 3-5 days.

### Day 1: New Developer Setup

The new developer runs:

```bash
cd ~/company/main-repo
synthesis init
synthesis scan
```

### Day 1: Architecture Overview

```bash
synthesis graph --modules --format mermaid
```

Generates an architecture diagram from the actual code. Not from a stale Confluence page, but from what exists right now.

### Day 1-2: Guided Exploration

```bash
synthesis search "authentication"              # Find auth code
synthesis ask "how does authentication work?"   # AI explanation
synthesis explain src/auth/AuthService.java     # Deep dive on a file
synthesis relate src/auth/AuthService.java      # See all connections
```

### Day 3-5: Independent Navigation

The developer uses `search` and `relate` to navigate independently. When they need to make changes, `relate` shows the blast radius before they touch anything.

### Onboarding Checklist for Managers

- [ ] Developer installs Synthesis and scans main codebase
- [ ] Developer generates module graph and reviews architecture
- [ ] Developer uses `search` to find code for their first task
- [ ] Developer uses `relate` before their first refactoring
- [ ] Developer shares an interesting finding in standup

---

## Team Adoption Playbook: 4 Weeks

### Week 1: Pilot (1 developer, 30 minutes)

Assign one developer to install Synthesis and index your primary codebase. Their report back should answer: "Did search find things faster than grep?"

**Success signal:** "I found X in 10 seconds that used to take 5 minutes."

### Week 2: Team Adoption (all developers, 15 min/day)

Introduce the morning scan habit. Before starting work: `synthesis scan` (1-5 seconds). Throughout the day: search before building.

**New team norm:** "Search before you build." Before writing new code, search for existing patterns. Before refactoring, check dependencies.

**Success signal:** Team members start sharing search results in code reviews.

### Week 3: Process Integration (team lead, 2 hours)

Embed Synthesis into existing workflows:
- **Code review:** "Did you check `relate` before changing this shared service?"
- **Sprint planning:** Generate module graph to identify coupling risks
- **Architecture decisions:** Show dependency graph for proposed changes
- **Change risk:** Run `synthesis impact <file>` before approving shared-service changes

**Success signal:** First refactoring where zero surprise bugs occurred because dependencies were mapped.

### Week 4: Measure and Report (you, 1 hour)

Collect metrics and compare to baselines using the dashboard below.

---

## Success Dashboard

Track these metrics weekly. Baselines are industry averages for AI-augmented teams without knowledge infrastructure.

| Metric | Baseline | Target | How to measure |
|--------|----------|--------|----------------|
| Searches/person/day | 0 | 15-25 | Ask developers or check shell history |
| Time to find a file (sec) | 300-900 | 10-30 | Weekly 1-question survey |
| "Can't find it" events/week | 8 | 0-1 | Count in Slack ("where is", "can't find") |
| Bugs from missed deps | 4/sprint | 0-1 | Tag in issue tracker |
| New dev time to productivity | 3-4 weeks | 3-5 days | Track onboarding duration |
| Cross-repo awareness | None | Complete | Can developers explain inter-service deps? |
| Workspace health score | Untracked | 80+ (B+) | `synthesis health --format json` |

---

## Generating Team Reports

### Temporal AI Summaries (Recommended)

For status reports grounded in actual recent changes:

```bash
synthesis summary --since 7d                   # Weekly AI summary
synthesis summary --since 2w                   # Sprint summary
synthesis summary --since 3m                   # Quarterly summary
synthesis summary --since 7d -o weekly.md      # Save to file
```

These use actual change data from snapshots, so the AI narrative reflects what really happened.

### Cross-Workspace Change Reports

```bash
synthesis changelog --weekly                   # Executive weekly report
synthesis changelog --format markdown -o changes.md   # Save for sharing
synthesis changelog --significance notable     # Filter to important changes only
```

### Codebase Research Reports

For deeper analysis (architecture, security, quality, dependencies, technical evolution):

```bash
synthesis research --topic architecture -o architecture-report.md
synthesis research --topic security -o security-report.md
synthesis research --topic quality -o quality-report.md
synthesis research                        # Full analysis (all topics)
```

Research reports use multi-pass AI analysis. Each pass examines the codebase from a different angle, then a synthesis pass combines findings. Useful for quarterly reviews, technical debt assessments, and architecture decision records.

**Cost estimation:**

```bash
synthesis research --estimate
```

Shows what files will be analyzed and the estimated API cost before running.

**Output targets:**

| Target | Purpose |
|--------|---------|
| `chatgpt` (default) | Markdown report for reading or further processing |
| `notebooklm-infographic` | Data dump optimized for NotebookLM infographic generation |
| `notebooklm-presentation` | Chapter-based narrative for NotebookLM presentation |

### Executive Summaries

```bash
synthesis summary
```

Generates a high-level summary of the workspace -- useful for status reports to leadership.

---

## Perspectives: Multi-Angle Analysis

When facing architectural or strategic decisions:

```bash
synthesis perspectives "should we split the monolith into microservices?"
synthesis perspectives "should we migrate from REST to gRPC?"
synthesis perspectives "is our test coverage adequate for production?"
```

Generates analysis from multiple viewpoints (pragmatist, purist, risk analyst, etc.), grounded in your actual codebase structure.

---

## Justifying Synthesis to Leadership

**Cost:** Free (MIT open source). No procurement needed.

**Risk:** Zero. All processing is local. 30-minute reversible pilot.

**ROI calculation for a 10-person team:**

| Line item | Annual value |
|-----------|-------------|
| Developer time recovered (40% search time x 75% reduction) | ~3,600,000 NOK |
| Bugs from missed dependencies (4/sprint x 26 sprints x 8h fix) | ~500,000 NOK |
| Faster onboarding (3 weeks saved x 2 new hires/year) | ~200,000 NOK |
| **Total annual value** | **~4,100,000 NOK** |
| **Investment** | **30 minutes to install** |

---

## Integration with Team Processes

### Code Reviews

Add to your PR template:

```markdown
## Impact Analysis
- [ ] Ran `synthesis relate` on changed shared services
- [ ] Ran `synthesis impact` on high-risk files
- [ ] Verified all incoming references are updated
- [ ] No new circular dependencies introduced
```

### Sprint Planning

Before each sprint:

```bash
synthesis architecture                     # Check for new anti-patterns
synthesis graph --modules --format mermaid  # Updated architecture view
synthesis insights                          # Codebase health check
synthesis health                            # Workspace hygiene score
synthesis summary --since 2w               # AI summary of last sprint's changes
```

### Sprint Reviews

```bash
synthesis summary --since 2w               # AI narrative of the sprint
synthesis changelog --since 2w             # Detailed change list
synthesis changed --since 2026-02-01       # Files changed this sprint
```

### Retrospectives

```bash
synthesis changed --since 2026-02-01       # What changed this sprint
synthesis changelog --since 2w             # Cross-workspace changes
synthesis impact <most-changed-file>       # Was the riskiest change managed well?
```

### Weekly Status Reporting

Set up a Monday morning routine:

```bash
synthesis maintain --sync --update-activity-log   # Refresh everything
synthesis summary --since 7d -o weekly-summary.md  # AI summary for stakeholders
synthesis changelog --weekly                       # Factual change list
```

---

## Quick Reference

```
# Visibility & Health
synthesis insights                          # Codebase health report
synthesis architecture                      # Anti-pattern detection
synthesis health                            # Workspace health score (0-100)
synthesis metrics                           # Performance statistics
synthesis cross-repo-deps                   # Cross-repo dependency map
synthesis graph --modules                   # Architecture overview

# Temporal Intelligence
synthesis summary --since 7d               # AI summary of last week
synthesis summary --since 2w               # Sprint summary
synthesis changelog                         # Cross-workspace change report
synthesis changelog --weekly                # Executive weekly report
synthesis changed --since 2026-01-01        # Files changed since date
synthesis impact <file>                     # Co-change / blast radius analysis

# Housekeeping & Automation
synthesis maintain --sync --update-activity-log   # Full maintenance cycle
synthesis sweep --dry-run                   # Preview stale file cleanup
synthesis sweep --yes                       # Execute cleanup
synthesis prune --yes                       # Remove empty directories
synthesis ttl set "*.tmp" --days 1          # Register TTL rule
synthesis ttl check --archive               # Archive expired files
synthesis consolidate "Entity" --execute    # Merge fragmented content
synthesis discover                          # Find unindexed git repos

# Deep Analysis
synthesis research --topic architecture     # Deep architecture analysis
synthesis research --estimate               # Cost preview
synthesis summary                           # Executive summary
synthesis perspectives "question"           # Multi-angle analysis

# Team Tools
synthesis learn                             # Generate Claude Code skills
synthesis export                            # Export index for sharing
synthesis org                               # Organization registry
```

---

**Version:** v1.11.1 (Feb 2026) | ~2,500 tests passing

**Related guides:**
- [Developer Guide](./DEVELOPER.md) -- for your team members
- [Architect Guide](./ARCHITECT.md) -- for architectural analysis
- [Executive Guide](./EXECUTIVE.md) -- for justifying to leadership
- [Full User Guide](../USER-GUIDE-V2.md) -- complete command reference
