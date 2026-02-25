# Synthesis Workshop Facilitator Guide

**You are teaching developers how to manage AI-generated output. This guide ensures they leave with working knowledge and Synthesis running on their own projects.**

---

## Workshop Overview

| Element | Details |
|---------|---------|
| **Audience** | Developers, technical leads, architects |
| **Duration** | 2 hours (compact), 4 hours (standard), 6-8 hours (deep-dive) |
| **Group size** | 8-30 participants (ideal: 12-16) |
| **Prerequisites** | Laptop with Java 21+, access to a codebase |
| **Version** | Synthesis 1.11.1 |

**Learning outcomes:**
1. Understand the comprehension bottleneck (AI output explosion)
2. Install and configure Synthesis (including credentials)
3. Index a real codebase and search it
4. Map dependencies with `relate` and `graph`
5. Analyze change impact with `impact` and `summary --since`
6. Use AI features: `ask`, `explain`, `exo ask`
7. Understand the self-organizing workspace cycle
8. Leave with Synthesis running on their primary project

---

## Pre-Workshop Setup (Send 1 Week Before)

### Attendee Email

Subject: "Workshop Prep -- 15 Minutes Required"

```
Hi [Name],

Looking forward to the Synthesis workshop on [Date].

Please complete these steps BEFORE the workshop (15 minutes):

1. VERIFY JAVA 17+
   Run: java -version
   If not installed: https://adoptium.net/

2. INSTALL SYNTHESIS
   Linux/Mac: curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash
   Verify: synthesis --version (should show 1.11.1 or later)

3. OPTIONAL: GET AN API KEY
   If you have an Anthropic API key, bring it. We will use AI features during the workshop.
   No key? No problem -- core features work without one.

4. BRING A CODEBASE
   Any project you work on (Git repo preferred). If you do not have one,
   we will provide a sample project.

If you hit any issues, reply to this email or arrive 15 minutes early.

See you on [Date]!
```

### Facilitator Checklist

**One week before:**
- [ ] Send attendee email
- [ ] Prepare sample codebase (for attendees without their own)
- [ ] Test all demos on your laptop
- [ ] Prepare USB drives with synthesis.jar (offline backup)
- [ ] Print command reference cards

**Day before:**
- [ ] Test projector/screen
- [ ] Verify internet access (for API features and mermaid.live)
- [ ] Charge laptop, bring power adapter

**Day of (arrive 30 minutes early):**
- [ ] Test projector
- [ ] Help early arrivals with installation
- [ ] Open mermaid.live in browser tab (for graph demos)

---

## 5-Minute Demo Script (For Presentations)

Use this script when you have 5 minutes to show Synthesis. Works for conference talks, sales meetings, or workshop introductions.

### Setup (Before the Demo)

Have a workspace already initialized and scanned. Use a codebase with at least 500 files.

### Script

**[0:00] The problem (30 seconds)**

"Quick question: How many of you spent more than 5 minutes this week searching for a file? Looking for where something is implemented?"

[Hands go up]

"AI tools made you 10x faster at writing code. Did they make you 10x faster at finding code? That gap is what we are solving."

**[0:30] Search (60 seconds)**

```bash
synthesis search "authentication"
```

"One command. Sub-second. Finds code, docs, config, even videos. Across every file type."

Show the results. Point out the variety: Java files, markdown docs, YAML config, PDFs.

**[1:30] Dependencies (60 seconds)**

```bash
synthesis relate src/auth/AuthService.java
```

"Before you change anything, see what depends on it. These 8 incoming references are your blast radius. Change AuthService without updating these and something breaks."

**[2:30] Architecture (60 seconds)**

```bash
synthesis graph --modules --format mermaid
```

Paste into mermaid.live or show pre-rendered image.

"Auto-generated from your actual code. Not from a Confluence page someone drew 6 months ago. This updates every time you scan."

**[3:30] AI explanation (60 seconds)**

```bash
synthesis ask "how does authentication work in this project?"
```

"Natural language question. Synthesis reads your indexed files and gives you a grounded answer, referencing specific files."

**[4:30] Wrap (30 seconds)**

"That was search, dependencies, architecture, and AI explanation. All in under 5 minutes. Synthesis is open source, runs locally, and indexes 200-300 files per second. Install it, scan your project, and search. You will find things in 10 seconds that used to take 10 minutes."

---

## 2-Hour Workshop Structure

### Module 1: The Problem (15 min)

**Opening poll (5 min):**
"How many of you have spent more than 5 minutes in the last week searching for a file?"

**The AI paradox (5 min):**
- Creation speed: 10x (AI tools)
- Search speed: 1x (unchanged)
- Shipping speed: 1.5x (realized)
- The gap = wasted AI investment

**Solution preview (5 min):**
Live demo: `synthesis search "authentication"` -- show multi-format results in under 1 second.

### Module 2: Installation and First Scan (20 min)

**Verify installs (5 min):**
"Who has Synthesis installed? Show of hands."
- 80%+: proceed
- <80%: quick troubleshooting (have TAs help)

**Initialize and scan (10 min):**

```bash
cd ~/your-project
synthesis init
synthesis scan
```

Walk around. Check that scans complete. Collect numbers: "How many files? How long?"

**Optional: Store API key (5 min):**

```bash
synthesis credentials set ANTHROPIC_API_KEY sk-ant-...
synthesis credentials status
```

For attendees who have keys. Others skip this and use non-AI features.

### Module 3: Search (25 min)

**Basic search (10 min):**

Everyone searches their own codebase:

```bash
synthesis search "your keyword here"
```

Facilitator demo on projector: show results across multiple file types.

**Guided exercises (10 min):**

1. Search for a feature you work on
2. Search for "TODO" (find technical debt)
3. Search for a config setting
4. Search for "@Deprecated"

**Debrief (5 min):**
"What did you find that surprised you?"

### Module 4: Relationships and Impact (30 min)

**The refactoring question (5 min):**
"You need to change AuthService.java. What breaks?"

**Live demo (10 min):**

```bash
synthesis relate "AuthService.java"
```

Walk through outgoing (what it uses) and incoming (what uses it). The incoming list = blast radius.

**Exercise (15 min):**

"Pick an important file in your codebase. Run `relate` on it."

Questions to answer:
1. How many incoming references?
2. Were you surprised by any connections?
3. Would you have known about all of these before today?

### Module 5: Architecture Graphs (20 min)

**The whiteboard challenge (5 min):**
"Draw your system architecture. You have 60 seconds."

[Attendees struggle, realize they do not fully know]

**Auto-generated graph (10 min):**

```bash
synthesis graph --modules --format mermaid
```

Paste into mermaid.live. Show the result. Compare to the whiteboard attempts.

**Exercise (5 min):**

```bash
synthesis graph --modules --format mermaid > architecture.md
```

"Open the file. What did you learn about your architecture?"

### Module 6: AI Features (Optional, 10 min)

For attendees with API keys:

```bash
synthesis ask "how does authentication work?"
synthesis explain src/auth/AuthService.java
```

For all attendees:

```bash
synthesis insights                    # No AI required
synthesis architecture                # No AI required
```

### Module 7: Wrap-Up (10 min)

**Recap:**
1. Search across all file types in under a second
2. Map dependencies before changing anything
3. Auto-generate architecture diagrams
4. AI-powered explanations and analysis

**Daily workflow:**
- Morning: `synthesis maintain` (5 seconds, also tracks changes)
- Throughout day: `synthesis search` before building
- Before refactoring: `synthesis relate` + `synthesis impact`
- Weekly: `synthesis summary --since 7d` + `synthesis insights`

**Homework:**
- [ ] Keep Synthesis installed
- [ ] Scan your codebase daily this week
- [ ] Use `search` instead of grep
- [ ] Share one finding with your team

---

## 4-Hour Workshop: Additional Modules

### Module 8: Co-Change Analysis (30 min)

**The hidden coupling problem (5 min):**
"Static analysis shows imports and references. But what about files that always change *together*, even though they do not directly reference each other? Config files, test fixtures, migration scripts -- these are invisible couplings that break releases."

**Live demo (10 min):**

```bash
# First, ensure change tracking is populated
synthesis maintain

# Then analyze co-change patterns
synthesis impact src/auth/AuthService.java
```

Walk through the output:
- Files that historically change together
- Change frequency and coupling strength
- Unexpected couplings (e.g., a config file that always changes with a service)

**Exercise (15 min):**

"Pick a file you recently refactored. Run `impact` on it."

Questions to answer:
1. Did any co-change partners surprise you?
2. Would you have included those files in your PR?
3. Compare `relate` (static deps) vs `impact` (dynamic coupling) -- what does one show that the other misses?

**Example output to discuss:**

```
Co-change analysis for: src/auth/AuthService.java

  src/auth/AuthConfig.java          (85% co-change, 12 shared commits)
  src/test/auth/AuthServiceTest.java (92% co-change, 15 shared commits)
  db/migrations/V5__auth_tables.sql  (40% co-change, 3 shared commits)
  docs/auth/README.md               (25% co-change, 2 shared commits)
```

### Module 9: Temporal Summaries (20 min)

**The stale report problem (5 min):**
"Most reports describe what the codebase looks like. But what changed *this week*? Generic AI summaries guess. Synthesis injects real changelog data into the AI prompt."

**Live demo (10 min):**

```bash
# Generate a temporally-grounded summary
synthesis summary --since 7d
```

Show how the output references specific recent changes, not generic observations.

```bash
# Different perspectives on the same changes
synthesis summary --since 7d --perspective architect
synthesis summary --since 7d --perspective security
```

**Exercise (5 min):**

"Run `synthesis summary --since 7d` on your workspace. Does it correctly identify what changed?"

**Requirement:** `synthesis maintain` must have run at least once to populate snapshots.

### Module 10: Directory Identity Workshop (30 min)

**The messy workspace problem (5 min):**
"Your team has 5,000 files. New hires put things in the wrong directories. AI agents generate files with no idea where they belong. How does a workspace stay organized?"

**Live demo: Sync and inspect (10 min):**

```bash
# Preview what directory identities would be created
synthesis sync --dry-run
```

Walk through the output. Each directory gets a `.synthesis.md` with YAML front matter declaring what it accepts:

```yaml
---
synthesis:
  accepts:
    types: [CODE, MARKDOWN]
    formats: [java, md]
    patterns: ["*Service.java"]
  scope: "Authentication subsystem"
---
```

```bash
# Actually populate the identities
synthesis sync

# Now preview what automated cleanup would do
synthesis sweep --dry-run
```

Show the sweep recommendations: misplaced files, suggested destinations.

**Exercise (15 min):**

"Run `synthesis sync --dry-run` on your workspace. Then `synthesis sweep --dry-run`."

Questions to answer:
1. How many directories got identities?
2. Did `sweep` find misplaced files?
3. Were the suggested destinations correct?
4. Would you trust an AI agent to use these identities for file placement?

### Module 11: Activity Log and Team Reports (15 min)

**The team standup problem (5 min):**
"What did the team actually do this week? Not what they planned -- what they changed."

**Live demo (10 min):**

```bash
# Auto-generate activity log from change tracking
synthesis maintain --update-activity-log

# Cross-workspace change report
synthesis changelog --since 7d
```

Show how the changelog captures additions, modifications, and deletions across all indexed workspaces.

### Module 12: Advanced Features (30 min)

**Multi-workspace search:**

```bash
synthesis search --all "authentication"    # Search all workspaces
synthesis which EventStoreService.java     # Find which workspace has a file
```

**Binary file enrichment:**

```bash
synthesis enrich                           # Generate companions
synthesis enrich --level ai                # AI descriptions
synthesis scan                             # Re-index
```

**The `exo ask` conversational loop:**

```bash
exo ask "what is the status of our authentication implementation?"
```

Show the RAG loop: sources displayed, answer streamed, follow-up available. Compare to `synthesis ask` (single-shot).

### Module 13: Group Exercises (45 min)

Break into groups of 3-4. Each group picks one:

1. **Technical debt audit:** Find all TODOs, FIXMEs, deprecated code. Report the top 5 debt items.
2. **Refactoring plan:** Pick a shared service. Map full impact with `relate` + `impact`. Create a refactoring checklist that includes co-change partners.
3. **Self-organizing workspace:** Run the full `sync` -> `sweep --dry-run` -> review cycle. Propose directory identity rules for your project.
4. **Architecture documentation:** Generate module graph. Annotate it. Create an architecture README.
5. **Cross-repo exploration:** If multiple repos are available, run `cross-repo-deps`. Report findings.
6. **Temporal analysis:** Run `summary --since 7d` with three different perspectives (architect, security, developer). Compare what each highlights.

Groups work 30 minutes, then present (3 min each).

### Module 14: Team Workflow Integration (15 min)

- Add `relate` + `impact` checks to PR template
- Add `synthesis maintain` to git hooks (replaces `synthesis scan`)
- Set up daily `synthesis changelog --since 24h` for team reports
- Configure `synthesis architecture` in CI/CD
- Add `synthesis summary --since 7d` to weekly standup prep

---

## 6-8 Hour Deep-Dive: Additional Modules

### Module 15: Business Intelligence Features (45 min)

- `synthesis report` for executive reports
- `synthesis upcoming` for event tracking
- `synthesis org scan` for organizational discovery
- Workshop exercise: Generate a product status report

### Module 16: Custom Integration Projects (2-3 hours)

Each attendee or pair builds one of:
- CI/CD pipeline with Synthesis architecture checks
- Custom search dashboard using `synthesis export`
- Onboarding guide generated from `synthesis graph` + `synthesis explain`
- Weekly automated report using `synthesis changelog --since 7d` + `synthesis summary --since 7d`
- Self-organizing workspace rules for their team (directory identities + sweep config)

Present projects at end.

---

## Proof of Methodology

When using Synthesis as proof that AI-assisted development works at scale:

### Key Numbers to Reference

- **lib-pcb:** 197,831 lines of Java code generated in 11 days (industry standard: 10-18 months)
- **~2,500 tests**, 99.8% pass rate
- **36,342 files** indexed across production deployment
- **Sub-second search** (<1 second, typically 0.4 seconds)
- **58 repositories**, 429 cross-dependencies mapped in under 31 seconds
- **48% reduction** in AI agent API calls with Synthesis + Skills (Condition C benchmark)

### Live Demonstration Flow

```bash
# Show the scale
synthesis status                              # Files indexed, last scan

# Show the speed
synthesis search "validation"                 # Sub-second results

# Show the intelligence
synthesis relate src/core/Validator.java      # Dependency mapping
synthesis impact src/core/Validator.java      # Co-change analysis

# Show the architecture
synthesis graph --modules --format mermaid     # Visual overview

# Show temporal awareness
synthesis summary --since 7d                   # What changed this week?

# Show the AI analysis
synthesis insights                            # Codebase health
```

### Skills Export

After the workshop, attendees can export skills for their Claude Code setup:

```bash
synthesis learn                               # Generate skills from workspace
synthesis learn --install                     # Install to ~/.claude/skills/
synthesis export-skills                       # Export bundled Synthesis skills
```

---

## Troubleshooting (For TAs)

| Issue | Fix |
|-------|-----|
| "Not a Synthesis workspace" | `synthesis init` |
| No search results | Check `includePatterns` in `.synthesis/config.yaml` |
| Scan is slow | Exclude `node_modules`, `target`, `build`, `.venv` |
| Java not found | Install Java 21+ from adoptium.net |
| AI features not working | `synthesis credentials set ANTHROPIC_API_KEY sk-ant-...` |
| Graph won't render | Use `--format mermaid` and paste into mermaid.live |
| `summary --since` shows no changes | Run `synthesis maintain` first to populate snapshots |
| `synthesis learn` fails | Run `synthesis org scan` first |

---

## Post-Workshop Follow-Up

**Send within 24 hours:**

```
Thanks for attending the Synthesis workshop!

Quick links:
- Quick Start: https://github.com/exoreaction/Synthesis/blob/main/docs/guides/QUICK-START.md
- Full User Guide: https://github.com/exoreaction/Synthesis/blob/main/docs/USER-GUIDE-V2.md
- GitHub: https://github.com/exoreaction/Synthesis

Your 5-day challenge:
1. Day 1: Run `synthesis maintain` on your main project
2. Day 2: Use `synthesis search` instead of grep
3. Day 3: Run `relate` + `impact` before refactoring a file
4. Day 4: Generate your architecture graph and run `synthesis summary --since 7d`
5. Day 5: Share one finding with your team

Reply with:
- One thing you learned
- One thing you tried
- One question you still have
```

**Follow up after 1 week:**
- "Are you still using Synthesis?"
- Yes: "What has been most valuable?"
- No: "What is blocking you?"

**Success metrics:**
- 70%+ using Synthesis 1 week later
- 50%+ using Synthesis 1 month later
- 3+ attendees become internal champions

---

**Related guides:**
- [Developer Guide](./DEVELOPER.md) -- hand this to attendees
- [Engineering Manager Guide](./ENGINEERING-MANAGER.md) -- for attendees' managers
- [DevOps Guide](./DEVOPS.md) -- CI/CD integration
- [Full User Guide](../USER-GUIDE-V2.md) -- complete command reference
