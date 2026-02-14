# Synthesis Sales Enablement Guide

**You're selling Skill-Driven Development (SDD) services. Synthesis is your proof of the methodology. This guide shows you how to position it.**

---

## The Sales Context

**What you're selling:** SDD methodology (AI-augmented development services, workshops, consulting)

**What Synthesis is:**
- Proof point (we built this using SDD in 11 days)
- Enabler (helps clients adopt SDD themselves)
- Differentiator (we have infrastructure, competitors don't)

**What Synthesis is NOT:**
- A standalone product (it's open source, free)
- The main revenue driver (services are)
- A replacement for SDD expertise (it's a tool)

**The positioning:** *"We teach you how to build 10-30x faster with AI. Synthesis is the knowledge infrastructure that makes it sustainable."*

---

## Discovery: Qualifying the Opportunity

### Discovery Questions (Use 3-5 per conversation)

**Category 1: AI Adoption**
- *"Has your team adopted AI coding tools like Copilot or Claude Code?"*
  - Yes → They have the AI output explosion problem
  - No → They're 6-12 months behind, educate them on the wave coming

- *"How much faster are your developers generating code compared to a year ago?"*
  - Good answer: "3-10x faster"
  - Great answer: "We're drowning in code"
  - Red flag: "About the same" (they're not using AI effectively)

**Category 2: Comprehension Bottleneck**
- *"How much time do your developers spend searching for files, tracing dependencies, or asking 'where is the code for X?'"*
  - Good answer: "Too much" or "30-40%"
  - Great answer: "It's our biggest productivity drain"
  - Red flag: "Not much" (they don't see the problem yet)

- *"How long does it take to onboard a new developer to productivity?"*
  - Good answer: "3-4 weeks"
  - Great answer: "6-8 weeks, sometimes longer"
  - Excellent answer: "We've had trouble retaining new hires because onboarding is so painful"

**Category 3: Scale & Complexity**
- *"How many repositories do you manage?"*
  - <5 → Small, low complexity
  - 5-20 → Medium, moderate complexity
  - 20+ → Large, high complexity (good fit)

- *"Do your developers work across multiple repositories?"*
  - Yes → Cross-repo search is valuable
  - No → Single-repo focus, still valuable but less pain

**Category 4: Technical Debt & Refactoring**
- *"Have you had production bugs from refactoring that broke unexpected dependencies?"*
  - Yes → Impact analysis is a pain point
  - Multiple times → Acute pain, high value

- *"How often do you defer refactoring because you're not sure what will break?"*
  - Often → They need relationship mapping
  - Always → Acute pain

**Category 5: Architecture Governance**
- *"Are your architecture diagrams up to date?"*
  - No → Auto-generated graphs are valuable
  - "We don't have any" → High value
  - "Last updated 6 months ago" → Stale, high value

### Qualifying Criteria (Score 0-2 per item)

| Criterion | 0 points | 1 point | 2 points |
|-----------|----------|---------|----------|
| **AI adoption** | No AI tools | Some developers use | Org-wide Copilot/Claude |
| **Team size** | 1-5 developers | 6-20 developers | 20+ developers |
| **Repositories** | 1-2 repos | 3-10 repos | 10+ repos |
| **Comprehension pain** | "Not a problem" | "Some friction" | "Major bottleneck" |
| **Refactoring risk** | Rare bugs | Occasional bugs | Frequent bugs |
| **Architecture drift** | Docs current | Somewhat stale | No docs / very stale |

**Scoring:**
- 0-3 points: Weak fit (educate and nurture)
- 4-6 points: Moderate fit (qualified lead)
- 7-9 points: Strong fit (hot lead)
- 10-12 points: Perfect fit (close now)

---

## Demo Flow: 15-Minute Synthesis Demo

**Goal:** Show the comprehension bottleneck → solution → proof in 15 minutes

### Act 1: The Problem (3 minutes)

**Hook:**
*"Quick question: Your team adopted AI tools. You're generating code 10x faster. But are you shipping 10x faster?"*

[Let them answer - usually "no" or "maybe 2x"]

*"That gap is what we call the comprehension bottleneck. Let me show you what I mean."*

**The AI Paradox (visual):**

Show slide or draw on whiteboard:
```
Before AI:
  Creation: ██        10 files/week
  Search:   ██        5 min to find a file
  Shipping: ██        2 weeks feature-to-production

After AI (no infrastructure):
  Creation: ████████████████████  100 files/week (10x!)
  Search:   ██                    5 min to find (unchanged)
  Shipping: ███                   10 days (only 1.4x faster)

THE GAP: You're creating at AI speed but navigating at human speed.
```

*"40-60% of developer time is now spent searching for context across repos, docs, and dependencies. AI made the problem worse, not better."*

### Act 2: The Solution (7 minutes)

**Introduce Synthesis:**

*"This is Synthesis. It's knowledge infrastructure we built using our SDD methodology. It indexes everything you create—code, docs, videos, PDFs—and makes it searchable in seconds."*

**Live Demo Part 1: Universal Search (2 minutes)**

```bash
# Open terminal, navigate to a demo codebase
cd ~/demo-project

# Show status
synthesis status
# Output: 7,990 files indexed, 202 MB content, 9.7 MB index (2.7% overhead)
```

*"This codebase has 8,000 files. Traditional tools: grep, IDE search, Confluence. They're fragmented. Synthesis sees everything."*

```bash
synthesis search "authentication"
```

*"In 0.4 seconds, it found 23 files across code, docs, config, and PDFs. Relevance ranked. Try that with grep."*

**Live Demo Part 2: Impact Analysis (3 minutes)**

*"But here's the real power. Before refactoring, you need to know: What breaks if I change this file?"*

```bash
synthesis relate "AuthService.java"
```

**Output shown:**
```
Imports/References (outgoing): 5 files
  → UserRepository.java
  → TokenService.java
  ...

Referenced by (incoming): 8 files
  ← AuthController.java
  ← LoginService.java
  ← AuthServiceTest.java
  ...

Total connections: 13
```

*"The 'incoming' section is your blast radius. These 8 files break if you change AuthService. Now you know exactly what to test."*

*"How many times have you had a production bug because you didn't know some obscure file depended on what you changed?"*

[They nod, they've experienced this]

**Live Demo Part 3: Architecture Graphs (2 minutes)**

```bash
synthesis graph --modules --format mermaid
```

*"This generates your architecture diagram automatically from the code. Not a stale Confluence page. The actual dependency graph."*

Paste into mermaid.live, show the visual graph.

*"58 repositories, 429 dependencies. Auto-generated in 2.3 seconds. Updates every time you scan."*

### Act 3: The Proof (3 minutes)

**Credibility builder:**

*"We built Synthesis in 11 days using Skill-Driven Development. It has:"*
- 197,831 lines of Java code
- 7,461 tests (99.8% pass rate)
- Handles 8,000+ files, sub-second search
- Indexes code, docs, videos, PDFs, everything

*"Industry baseline for a tool like this: 10-18 months with a team of 3-5. We did it in 11 days with 1 developer + AI."*

*"That's not a claim. That's proof. And we can teach your team to work at this speed."*

### Act 4: The Bridge (2 minutes)

**Connect Synthesis to SDD services:**

*"Synthesis solves the comprehension bottleneck. But it's not just a tool you install. It's a methodology."*

*"What we offer:"*
1. **Workshop (2-4 hours):** Teach your team to use Synthesis + SDD principles
2. **Consulting (3-6 months):** Embed with your team, apply SDD to your hardest problems
3. **Mentoring (ongoing):** On-call expertise as you scale SDD org-wide

*"Synthesis is open source and free. What you're buying is the methodology to go 10-30x faster—proven, not theoretical."*

---

## Objection Handling

### Objection 1: "We already have grep / IDE search / Confluence"

**Response:**
*"Great question. Those tools are designed for different problems."*

**Comparison table (show or draw):**

| Tool | Scope | Formats | Relationships | Speed |
|------|-------|---------|---------------|-------|
| grep | Single repo | Text only | None | Fast (single repo) |
| IDE | Single project | Code only | Some (within project) | Fast (single project) |
| Confluence | Docs only | Docs only | Manual links | Slow, often stale |
| **Synthesis** | **All repos** | **All formats** | **Bi-directional** | **Sub-second (all repos)** |

*"If your team works across 10+ repos, grep and IDE don't help. If you need to find a demo video or architecture PDF, Confluence doesn't help. Synthesis is the missing layer."*

### Objection 2: "Why not just build this ourselves?"

**Response:**
*"You could. Industry baseline is 10-18 months with a team of 3-5."*

*"Or, you could use Synthesis (open source, free) and spend those 18 months building your actual product."*

*"We're not selling Synthesis. We're selling the methodology that built Synthesis in 11 days. What could your team build in 11 days if they worked at that speed?"*

### Objection 3: "Our developers won't adopt another tool"

**Response:**
*"That's a real concern. Adoption is the hard part."*

*"Three things make Synthesis stick:"*
1. **Immediate value:** First search takes 10 seconds vs 5 minutes. They feel the difference.
2. **Zero friction:** One command (`synthesis scan`) once a day. Not a new workflow.
3. **Team norm:** When one developer finds something in 10 seconds, others ask "how did you do that?" Network effect.

*"In our pilot programs, 70%+ of developers are still using it after 1 month. That's higher than most tools."*

### Objection 4: "We're not sure we need SDD services, maybe just the tool"

**Response:**
*"Totally fair. Synthesis is open source—you can install it right now for free."*

*"But here's what you'll hit in 2-4 weeks:"*
- *"How do I integrate this into CI/CD?"*
- *"How do I get my team to actually use it?"*
- *"How do I apply the same methodology to my product development?"*

*"That's where the workshop and mentoring come in. Synthesis is the proof. SDD is the process."*

*"Try Synthesis for free. When you want to scale it org-wide or apply the methodology to your hardest problems, that's when we work together."*

### Objection 5: "What about security? Are you uploading our code?"

**Response:**
*"No. Synthesis is 100% local by default."*

*"All indexing happens on your machines. Nothing leaves your network. The index is stored in a local `.synthesis/` directory."*

*"AI features are opt-in and require you to provide your own Anthropic API key. Even then, only files you explicitly select are sent."*

*"For regulated industries (finance, healthcare), this is a feature: zero data residency risk."*

---

## Pricing & Packaging

### Option 1: Workshop Only (Entry Point)

**What:** 2-4 hour workshop for 8-30 developers
**Price:** 35-80K NOK (35K for 2hr, 55K for 4hr, 75-80K for full-day)
**Deliverables:**
- Pre-workshop setup guide
- Live workshop with exercises
- Command reference handout
- Post-workshop follow-up email with resources

**When to offer:** Small teams (5-20 developers), budget-conscious, pilot/trial

**Upsell path:** Workshop → Mentoring package (3-month on-call)

### Option 2: Workshop + Mentoring (Partnership Model)

**What:** Workshop + 3-month "on-call" mentoring
**Price:** 190-230K NOK (60-80K workshop + 130-150K mentoring)
**Deliverables:**
- Workshop (as above)
- Monthly check-in calls (3 total)
- Slack/email support for Q&A
- Custom Synthesis configuration for their codebase
- SDD methodology coaching (applying to their product)

**When to offer:** Mid-size teams (20-50 developers), committed to SDD adoption, budget for partnership

**Upsell path:** Mentoring → Full consulting engagement (embed with team)

### Option 3: Consulting Engagement (Full Transformation)

**What:** 3-6 month embedded consulting
**Price:** 200-300K NOK/month (600K-1.8M total)
**Deliverables:**
- SDD methodology training
- Synthesis deployment and integration (CI/CD, team workflows)
- Apply SDD to client's hardest problem (build something together)
- Knowledge transfer (leave team self-sufficient)

**When to offer:** Large enterprises (50+ developers), strategic transformation, high-value problem to solve

### Option 4: Retainer (Ongoing Partnership)

**What:** Ongoing SDD expertise on-call
**Price:** 540-900K NOK/year (45-75K/month for 3-5 days/month)
**Deliverables:**
- Monthly strategy sessions
- On-call technical expertise (Slack/email)
- Quarterly workshops (new hires, new topics)
- Early access to new Synthesis features
- Custom development (Synthesis extensions, integrations)

**When to offer:** Long-term strategic accounts, high-tech companies (AI, security, compliance)

---

## Deal Stages & Actions

### Stage 1: Discovery Call (30-45 min)

**Goal:** Qualify the opportunity (use scoring criteria above)

**Agenda:**
1. Introduction (5 min)
2. Discovery questions (15 min)
3. Quick Synthesis demo (10 min) - if qualified
4. Discuss fit and next steps (5 min)

**Outcome:**
- Qualified → Schedule demo call
- Unqualified → Educate and nurture (send resources, follow up in 3 months)

### Stage 2: Demo Call (45-60 min)

**Goal:** Show value, build credibility, create urgency

**Agenda:**
1. Recap problem (5 min)
2. Full Synthesis demo (20 min) - use 15-min flow above
3. Discuss their specific use cases (10 min)
4. Present options (10 min) - Workshop, Workshop+Mentoring, Consulting
5. Next steps (5 min)

**Outcome:**
- Hot → Send proposal, schedule decision call
- Warm → Schedule follow-up in 1-2 weeks
- Cold → Nurture (send case study, follow up quarterly)

### Stage 3: Proposal & Negotiation (1-2 weeks)

**Deliverable:** Written proposal (use template below)

**Proposal structure:**
1. **Executive Summary** (1 page)
   - Problem: Comprehension bottleneck costs X hours/week
   - Solution: SDD methodology + Synthesis infrastructure
   - Outcome: 10-30x faster development, proven
2. **Approach** (2 pages)
   - Workshop curriculum or consulting plan
   - Timeline and milestones
   - Deliverables
3. **Investment** (1 page)
   - Option 1: Workshop (35-80K)
   - Option 2: Workshop + Mentoring (190-230K)
   - Option 3: Consulting (600K-1.8M)
4. **Proof Points** (1 page)
   - Synthesis: 11 days, 197K LOC, 7,461 tests
   - Jon Petter / Tvimenning: 40K closed, 190K potential
   - SpareBank 1: Enterprise validation (in progress)
5. **Next Steps** (1 paragraph)
   - Decision timeline
   - Contract terms (50% deposit, 50% on completion)
   - Start date

**Follow-up:** Call 2-3 days after sending to answer questions

### Stage 4: Close & Onboard (1 week)

**Goal:** Sign contract, schedule workshop/engagement

**Checklist:**
- [ ] Contract signed
- [ ] 50% deposit received
- [ ] Workshop/engagement scheduled
- [ ] Pre-work sent (if workshop)
- [ ] Kickoff meeting scheduled

---

## Sales Materials Checklist

**Required:**
- [ ] **1-page Synthesis overview** (feature summary, proof points)
- [ ] **15-min demo script** (use flow above)
- [ ] **Proposal template** (structure above)
- [ ] **Case studies** (Jon Petter, SpareBank 1, Item Consulting once complete)
- [ ] **Pricing sheet** (4 options with deliverables)

**Nice to have:**
- [ ] **Video testimonial** (from Jon Petter or SpareBank 1)
- [ ] **Recorded demo** (send to prospects who can't attend live)
- [ ] **ROI calculator** (input team size, output hours saved)
- [ ] **Competitive comparison** (Synthesis vs grep/IDE/Confluence)

---

## Success Metrics

**Track these per deal:**
- Discovery call → Demo call conversion (target: 60%+)
- Demo call → Proposal conversion (target: 40%+)
- Proposal → Close conversion (target: 30%+)
- Overall discovery → close (target: 7-12%)

**Track these per delivery:**
- Workshop NPS (target: 8+/10)
- 1-month adoption rate (target: 70%+)
- Upsell rate (workshop → mentoring: 30%+)
- Referrals per client (target: 1-2)

---

## Your Next Step

**Prepare for your next sales call:**

1. **Print this guide** (reference during calls)
2. **Practice the 15-min demo** (you should be able to deliver it smoothly)
3. **Prepare your demo environment** (sample codebase with 1,000+ files indexed)
4. **Set up proposal template** (customize for your client)

**During the call:**
- Use discovery questions to qualify
- Demo only if qualified (don't waste time on poor fits)
- Always end with a clear next step ("I'll send the proposal by Friday")

**After the call:**
- Send follow-up email same day
- Proposal within 48 hours
- Follow up on proposal within 3 days

---

**Related documentation:**
- **For technical proof:** [Integration Test Results](/tmp/synthesis-test-results.md)
- **For workshop delivery:** [Workshop Facilitator Guide](./WORKSHOP-FACILITATOR.md)
- **For client success:** [Engineering Manager Guide](./ENGINEERING-MANAGER.md)
- **For technical credibility:** [Architecture Guide](./ARCHITECT.md)
