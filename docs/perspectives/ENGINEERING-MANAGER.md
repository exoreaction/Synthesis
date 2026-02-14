# Synthesis for Engineering Managers

**Your team adopted AI. Output tripled. But are you shipping faster?**

**📊 Visual Summary:** [Chaos to Clarity Infographic](../visuals/chaos-to-clarity-infographic.png) | [Full Presentation](../visuals/closing-ai-comprehension-gap.pdf)

---

## The Comprehension Bottleneck

Your developers have AI coding assistants. They generate code 10-50x faster than before. But your sprint velocity increased maybe 1.5x. Where did the other 8.5x go?

**It went to searching.**

| Activity | Before AI | After AI (no infrastructure) |
|----------|-----------|------------------------------|
| Finding the right file | 5-15 min | 5-15 min (unchanged) |
| Understanding dependencies | 10-30 min | 10-30 min (unchanged) |
| Verifying completeness | 20-60 min | 20-60 min (unchanged) |
| **Total overhead per task** | **35-105 min** | **35-105 min** |

AI accelerated creation. It did nothing for comprehension. Your team now generates 100-500 files per week per person, but still navigates them with the same tools they used when generating 10-20.

**The math:**
- 40-60% of developer time is spent searching for context, tracing dependencies, and switching between tools
- On a team of 10, that is **4-6 full-time salaries** spent on retrieval, not creation
- AI made the gap worse: more output to search through, same human search speed

```
 Creation Speed        Comprehension Speed
 ┌─────────────┐       ┌─────────────┐
 │ ██████████  │ 10x   │ ██          │ 1x
 │ ██████████  │       │             │
 │ ██████████  │       │  (unchanged)│
 └─────────────┘       └─────────────┘
      THE GAP = WASTED AI INVESTMENT
```

---

## What Synthesis Does for Your Team

Synthesis is knowledge infrastructure for AI-augmented teams. It indexes everything your team creates -- code, docs, PDFs, videos, configs -- and makes it instantly searchable with relationship tracking and visual architecture maps.

### Four Outcomes That Matter to You

| Outcome | Before Synthesis | After Synthesis | Impact |
|---------|-----------------|-----------------|--------|
| **Onboarding time** | 3-4 weeks to productivity | 3-5 days | 80-90% reduction |
| **Refactoring safety** | "What breaks if I change this?" = hours of grep | Instant impact map (28 dependencies shown in 2 sec) | Near-zero surprise bugs |
| **Technical debt visibility** | Manual audit: 1-2 weeks, 60-70% accurate | Automated inventory: 20 min, 95%+ accurate | Data-driven prioritization |
| **Cross-repo awareness** | IDE sees 1 project; team works across 10+ | All repos indexed, searched, graphed together | No more "which repo was that in?" |

### Real Numbers (Validated February 14, 2026)

- **8,934 files** indexed across 3 workspaces in under 31 seconds
- **Sub-second search** across all files, all formats
- **2.7% storage overhead** (11.6 MB index for 434 MB of content)
- **58 repositories** mapped with 429 cross-dependencies in one graph

---

## Adoption Playbook: 4 Weeks to Full Team Adoption

### Week 1: Foundation (1 engineer, 30 minutes)

**Assign one developer** to install Synthesis and index your primary codebase. This is a 30-minute task, not a project.

**What happens:** Your codebase becomes searchable across all file types. Developer validates that search works and results are relevant.

**Success signal:** Developer says "I found X in 10 seconds that used to take me 5 minutes."

### Week 2: Team Adoption (all developers, 15 min/day)

**Introduce the morning scan habit.** Before starting work, run a scan (1-5 seconds). Throughout the day, search before building.

**New team norm:** "Search before you build." Before writing new code, search for existing patterns. Before refactoring, check dependencies.

**Success signal:** Team members start sharing search results in code reviews and Slack.

### Week 3: Integrate into Workflow (team lead, 2 hours)

**Embed Synthesis into existing processes:**
- **Code review:** "Did you check `relate` before changing this shared service?"
- **Sprint planning:** Generate module graph to identify coupling risks
- **Architecture decisions:** Show dependency graph for proposed changes

**Success signal:** First refactoring where zero surprise bugs occurred because dependencies were mapped in advance.

### Week 4: Measure and Report (you, 1 hour)

**Collect baseline metrics and compare.** Use the Success Dashboard below.

**Success signal:** You can report concrete improvements to your VP.

---

## Success Dashboard: Metrics That Matter

Track these 6 metrics weekly. Baselines are industry averages for AI-augmented teams without knowledge infrastructure.

| Metric | Baseline | Week 1 | Week 2 | Week 3 | Week 4 | Target |
|--------|----------|--------|--------|--------|--------|--------|
| **Searches/person/day** | 0 | 8 | 18 | 22 | 25 | 15-25 |
| **Time to find (seconds)** | 600 | 120 | 45 | 25 | 15 | 10-30 |
| **"Can't find it" events/week** | 8 | 5 | 2 | 1 | 0 | 0-1 |
| **Bugs from missed dependencies** | 4/sprint | 3 | 2 | 1 | 0 | 0-1 |
| **AI realization rate** | 18% | 35% | 52% | 68% | 75% | 70-80% |
| **Cycle time (idea to shipped)** | 21 days | 12 | 7 | 4 | 2 | 1-3 days |

**AI realization rate** = (features shipped / AI-generated code) x 100%. This is the single most important metric. It measures how much of your AI investment translates into shipped product. Without knowledge infrastructure, teams realize only 15-20% of AI-generated output. With it: 70-80%.

**How to measure:**
- Searches/day: Check with developers (or enable audit logging)
- Time to find: Weekly 1-question survey ("How long to find your last file?")
- "Can't find it": Count in Slack/Teams messages containing "where is", "can't find", "looking for"
- Missed dependency bugs: Tag in issue tracker with root cause
- AI realization rate: Git commits merged / total AI-generated files
- Cycle time: Issue tracker (created to closed)

---

## Justifying Synthesis to Your Leadership

**Cost:** Free (open source, MIT license). No procurement process needed.

**Risk:** Zero. Local-only processing (no code leaves your machines). 30-minute reversible pilot.

**ROI calculation for a 10-person team:**

| Line Item | Value |
|-----------|-------|
| Developer cost (fully loaded) | 1,200,000 NOK/year per developer |
| Time spent searching (40%) | 4,800,000 NOK/year (4 FTE equivalent) |
| Reduction with Synthesis (75%) | 3,600,000 NOK/year saved |
| Bugs from missed dependencies (4/sprint x 26 sprints x 8h fix) | 832 hours/year = ~500,000 NOK |
| **Total annual value** | **~4,100,000 NOK** |
| **Investment** | **30 minutes to install** |

---

## Your Next Step

**Hand this to your most senior developer and say: "Install Synthesis on our main codebase. Report back in 30 minutes."**

That is the entire pilot. No procurement. No meetings. No budget approval. 30 minutes, one developer, zero risk.

If they come back saying "I found things in 10 seconds that used to take 5 minutes," you have your answer.

---

**Related documentation:**
- **For your developers:** [Developer Guide](./DEVELOPER.md) | [Quick Start](../guides/QUICK-START.md) (5 min) | [Full User Guide](../guides/USER-GUIDE.md)
- **For your architect:** [Architecture Intelligence Guide](./ARCHITECT.md)
- **For your VP/CTO:** [Executive Brief](./EXECUTIVE-BRIEF.md) -- hand this up when you need budget for team training
- **Technical details:** [Project README](../../README.md)
