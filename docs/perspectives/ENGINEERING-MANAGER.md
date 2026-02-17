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

### 2. Architecture Quality Metrics

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

### 3. Quantitative Metrics

```bash
synthesis metrics
```

Performance and usage statistics for your Synthesis installation, useful for tracking adoption across the team.

### 4. Cross-Repository Visibility

```bash
synthesis cross-repo-deps
```

Maps dependencies between repositories. Shows which repos depend on which others, with edge counts. Essential for microservice architectures where changes in one repo can break another.

Real example: 58 repositories, 429 cross-dependencies mapped in under 31 seconds.

### 5. Change Tracking

```bash
synthesis changelog                           # Cross-workspace change summary
synthesis changed --since 2026-02-01          # What changed since a date
```

Track what is changing across your codebase over time. Useful for sprint reviews, release notes, and understanding team activity.

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

---

## Generating Team Reports

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
- [ ] Verified all incoming references are updated
- [ ] No new circular dependencies introduced
```

### Sprint Planning

Before each sprint:

```bash
synthesis architecture                     # Check for new anti-patterns
synthesis graph --modules --format mermaid  # Updated architecture view
synthesis insights                          # Codebase health check
```

### Retrospectives

```bash
synthesis changed --since 2026-02-01       # What changed this sprint
synthesis changelog                         # Cross-workspace changes
```

---

## Quick Reference

```
synthesis insights                          # Codebase health report
synthesis architecture                      # Anti-pattern detection
synthesis metrics                           # Performance statistics
synthesis cross-repo-deps                   # Cross-repo dependency map
synthesis graph --modules                   # Architecture overview
synthesis changelog                         # Cross-workspace change report
synthesis changed --since 2026-01-01        # Files changed since date
synthesis research --topic architecture     # Deep architecture analysis
synthesis research --estimate               # Cost preview
synthesis summary                           # Executive summary
synthesis perspectives "question"           # Multi-angle analysis
synthesis learn                             # Generate Claude Code skills
synthesis export                            # Export index for sharing
```

---

**Related guides:**
- [Developer Guide](./DEVELOPER.md) -- for your team members
- [Architect Guide](./ARCHITECT.md) -- for architectural analysis
- [Executive Guide](./EXECUTIVE.md) -- for justifying to leadership
- [Full User Guide](../USER-GUIDE-V2.md) -- complete command reference
