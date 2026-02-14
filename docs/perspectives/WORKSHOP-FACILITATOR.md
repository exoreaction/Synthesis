# Synthesis Workshop Facilitator Guide

**You're leading a workshop to teach developers Synthesis in 2-4 hours. This guide ensures they leave with working knowledge.**

**📊 Visual Summary:** [Workshop Mastering AI Output Infographic](../visuals/workshop-mastering-ai-output.png)

---

## Workshop Overview

**Target audience:** Developers, technical leads, architects
**Duration options:** 2 hours (half-day), 4 hours (full-day), or 6-8 hours (deep-dive)
**Group size:** 8-30 participants (ideal: 12-16)
**Prerequisites:** Laptop with Java 17+, access to a codebase

**Learning outcomes:**
1. Understand the AI output explosion problem
2. Install and configure Synthesis
3. Index a real codebase (their own or a sample)
4. Search, relate, and graph effectively
5. Leave with Synthesis running on their primary project

---

## Pre-Workshop Setup (1 Week Before)

### Send to Attendees

**Email subject:** "Synthesis Workshop Prep - 15 Minutes Required"

**Email body:**
```
Hi [Name],

Looking forward to the Synthesis workshop on [Date]!

Please complete these 3 steps BEFORE the workshop (15 minutes):

1. VERIFY JAVA 17+
   Run: java -version
   If not installed: https://adoptium.net/

2. INSTALL SYNTHESIS
   Linux/Mac: curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash
   Windows: See attachment install-windows.txt

3. VERIFY INSTALLATION
   Run: synthesis --version
   You should see: "Synthesis 1.0.3-SNAPSHOT" (or later)

If you hit any issues, reply to this email or arrive 15 minutes early.

Bring:
- Your laptop
- Access to a codebase you work on (optional but recommended)

See you on [Date]!
[Your name]
```

### Prepare Materials

**Required:**
- [ ] Projector/screen for live demos
- [ ] Sample codebase (for attendees without their own)
- [ ] USB drives with Synthesis JAR (for offline install if needed)
- [ ] Handout: Quick reference card (synthesis commands cheat sheet)
- [ ] Workshop feedback form (Google Form or paper)

**Nice to have:**
- [ ] Video recording setup (workshop can become training material)
- [ ] Slack/Discord channel for Q&A during exercises
- [ ] Mermaid rendering tool (mermaid.live or VSCode plugin)

---

## Workshop Structure: 2-Hour Version

**Recommended for:** Corporate lunch-and-learn, team training

### Module 1: The Problem (15 minutes)

**Opening hook (5 minutes):**

*"Quick poll: How many of you have spent more than 5 minutes in the last week searching for a file, trying to remember where something is, or asking a teammate 'where is the code for X?'"*

[Most hands go up]

*"That's the comprehension bottleneck. AI made you 10x faster at generating code. Did it make you 10x faster at finding code?"*

**The AI paradox (5 minutes):**

Show slide/diagram:
```
Creation Speed: ████████████████████ (10x)
Search Speed:   ██                   (1x, unchanged)
Shipping Speed: ███                  (1.5x realized)

THE GAP = WASTED AI INVESTMENT
```

**Key points:**
- AI output explosion: 100-500 files/week/person (vs 10-20 before)
- 40-60% of time spent searching (measured)
- Your IDE only sees one project. You work across 10+.

**The solution preview (5 minutes):**

*"Synthesis is knowledge infrastructure. It indexes everything you create—code, docs, videos, PDFs—and makes it searchable in seconds with relationship tracking."*

Show live:
```bash
synthesis search "authentication"
# Returns: code, docs, configs, all formats in <1 second
```

**Transition:** *"Let's install it and try it on your code."*

---

### Module 2: Hands-On Installation (20 minutes)

**Verify pre-work (5 minutes):**

*"Who has Synthesis installed and verified?"*
- If 80%+ → proceed
- If <80% → quick troubleshooting session (have TAs help stragglers)

**Initialize workspace (5 minutes):**

*"Everyone, pick a codebase on your laptop. Navigate to it and run:"*

```bash
cd ~/your-project
synthesis init
```

**Expected output:**
```
✓ Workspace initialized: /home/you/your-project
ℹ Config: .synthesis/config.yaml
ℹ Index:  .synthesis/index
```

*"Raise your hand when you see the checkmark."*

**First scan (10 minutes):**

*"Now index your codebase:"*

```bash
synthesis scan
```

**Walk around the room:**
- Check that scans are running
- Help anyone stuck on errors
- Note scan times and file counts (ask 2-3 people to share)

**Debrief (2 minutes):**
- *"How many files did you index?"* (collect numbers: 200, 1,500, 8,000)
- *"How long did it take?"* (collect times: 2s, 8s, 31s)
- *"That's 200-300 files/second. Your entire codebase is now searchable."*

---

### Module 3: Search Everything (25 minutes)

**Basic search (10 minutes):**

*"Try this: Search for something you know exists in your codebase."*

```bash
synthesis search "your keyword"
```

**Live demo on projector:**
```bash
synthesis search "authentication"
# Show results across multiple file types
```

**Key points:**
- Works across all file types (code, docs, config, media)
- Relevance ranked (most relevant first)
- Sub-second even on large codebases

**Exercise (10 minutes):**

*"Try these searches on your own code:"*

1. Search for a feature you work on
2. Search for "TODO" (find all technical debt)
3. Search for "@Deprecated" (find all deprecated code)
4. Search for a config setting

*"What did you find that surprised you?"*

[Collect 2-3 stories from attendees]

**Multi-word search (5 minutes):**

*"Synthesis handles multi-word queries:"*

```bash
synthesis search "user authentication service"
# Finds files containing all three words
```

**Exercise:**
*"Search for a two or three-word phrase specific to your domain."*

---

### Module 4: Relationships & Impact (30 minutes)

**The refactoring problem (5 minutes):**

*"Quiz: You need to refactor AuthService.java. What breaks if you change it?"*

[Attendees guess: "The login flow?" "The API?" "Everything?"]

*"Let's find out exactly:"*

```bash
synthesis relate "AuthService.java"
```

**Live demo (10 minutes):**

Show `relate` output on projector:
```
Relationships for: src/main/java/com/example/AuthService.java

Imports/References (outgoing): 5 files
  → UserRepository.java
  → TokenService.java
  → PasswordEncoder.java
  → Configuration.java
  → Logger.java

Referenced by (incoming): 8 files
  ← AuthController.java
  ← LoginService.java
  ← RegistrationService.java
  ← AuthServiceTest.java
  ← IntegrationTest.java
  ← SecurityConfig.java
  ← AdminService.java
  ← AuditService.java

Total connections: 13
```

**Key insight:**
*"The 'Referenced by' section is your blast radius. These 8 files break if you change AuthService without updating them. Now you know exactly what to test."*

**Exercise (15 minutes):**

*"Pick a file in your codebase that you think is important. Run relate on it:"*

```bash
synthesis relate "YourFile.java"
```

*"Questions to answer:"*
1. How many files import/reference this file? (incoming)
2. How many files does this file depend on? (outgoing)
3. Were you surprised by any of the connections?

**Debrief:**
- *"Who found a file with 20+ incoming references?"* → *"That's a critical file. Extra care when changing it."*
- *"Who found a file with 0 incoming references?"* → *"That might be dead code."*

---

### Module 5: Visual Knowledge Graphs (20 minutes)

**The architecture question (5 minutes):**

*"Pop quiz: Draw your system architecture on the whiteboard. You have 60 seconds."*

[Attendees struggle, draw conflicting diagrams, realize they don't know]

*"Let's generate it automatically:"*

```bash
synthesis graph --modules --format mermaid
```

**Live demo (10 minutes):**

Generate module graph on projector, paste into mermaid.live, show result.

**Key points:**
- Auto-generated from actual code (not stale Confluence)
- Updates whenever you re-scan
- Multiple formats (Mermaid, PNG, SVG, DOT)

**Exercise (5 minutes):**

*"Generate your architecture graph:"*

```bash
synthesis graph --modules --format mermaid > architecture.md
```

*"Open architecture.md in an editor. Paste the Mermaid code into mermaid.live or your IDE Mermaid plugin."*

*"What did you learn about your architecture?"*

[Collect insights: "I didn't know module X depended on Y", "We have a circular dependency!", etc.]

---

### Module 6: Daily Workflow & Next Steps (10 minutes)

**The morning habit (3 minutes):**

*"Make this a habit: Before you start work, run a scan."*

```bash
synthesis scan  # 1-5 seconds, keeps index fresh
```

*"Throughout the day: Search before building."*
- Before writing new code: `synthesis search "existing pattern"`
- Before refactoring: `synthesis relate "file I'm changing"`
- Before deploying: `synthesis graph --modules` (check for unexpected changes)

**Integration ideas (5 minutes):**

*"How to make this stick:"*

1. **Shell alias:** `alias syn='synthesis'` (type less)
2. **Git hook:** Auto-scan after every commit (see DevOps guide)
3. **Team norm:** Require `relate` output in PRs for shared services
4. **Morning standup:** "What did you find with Synthesis yesterday?"

**Resources (2 minutes):**

*"Where to learn more:"*
- Quick Start: [link]
- Full User Guide: [link]
- All commands: `synthesis --help`

---

### Closing & Feedback (10 minutes)

**Recap (5 minutes):**

*"What you learned today:"*
1. ✅ The comprehension bottleneck (AI paradox)
2. ✅ Install and configure Synthesis
3. ✅ Search across all file types
4. ✅ Map dependencies with relate
5. ✅ Generate architecture graphs

*"Your homework:"*
- [ ] Keep Synthesis installed
- [ ] Scan your codebase daily this week
- [ ] Search instead of grep
- [ ] Share one interesting finding with your team

**Feedback (5 minutes):**

*"Please fill out the feedback form: [link or paper form]"*

**Questions:**
1. What was most valuable? (1-5)
2. What was least clear?
3. Will you use Synthesis after today? (Yes/No/Maybe)
4. What feature do you want to learn more about?

---

## Workshop Structure: 4-Hour Version

**Recommended for:** Team training, conference workshop

**Additions to 2-hour version:**

### Module 7: Advanced Features (30 minutes)

**Multi-workspace workflows:**
- Managing multiple projects
- Cross-repo search

**AI-powered features (optional, requires API key):**
- README generation
- Code synthesis from natural language

**Media support:**
- Indexing videos (metadata extraction)
- PDF full-text search
- Image analysis

### Module 8: Real-World Use Cases (30 minutes)

**Break into groups (3-4 people per group):**

Each group picks a use case:
1. **Onboarding:** Use Synthesis to create a new hire guide
2. **Technical debt:** Find all TODOs, FIXMEs, deprecated APIs
3. **Refactoring safety:** Pick a shared service, map full impact
4. **Architecture documentation:** Generate module graph, document it

**Groups work for 20 minutes, then present (2 min each).**

### Module 9: Integration & Automation (30 minutes)

**CI/CD integration:**
- Adding Synthesis to GitHub Actions
- Automated technical debt reports

**Watch mode:**
- Continuous indexing during development

**Team workflows:**
- Code review checklists
- Architecture governance

### Extended Exercises (fill remaining time)

- Generate cross-repo dependency graphs
- Create custom search workflows
- Build a knowledge base for your team

---

## Workshop Structure: 6-8 Hour Deep-Dive

**Recommended for:** Paid training, multi-day workshop, university course

**Add to 4-hour version:**

### Module 10: Organizational Intelligence (1 hour)

- Company/client/product detection
- Organization-scoped search
- Business context mapping

### Module 11: Custom Integration Projects (2-3 hours)

**Each attendee builds something:**
- CI/CD pipeline with Synthesis
- Custom search dashboard
- Architecture documentation system
- Onboarding automation

**Present projects at end.**

### Module 12: Advanced Graph Analysis (1 hour)

- Cyclomatic complexity
- Coupling metrics
- Architecture health scoring

---

## Troubleshooting Guide (For TAs/Helpers)

### Issue 1: "Not a Synthesis workspace"

**Symptom:** `synthesis scan` fails with error
**Fix:** `synthesis init` first

### Issue 2: "No search results"

**Symptom:** Search returns 0 results after scan
**Diagnosis:** Files not in includePatterns
**Fix:** Edit `.synthesis/config.yaml`, add file types

### Issue 3: Scan is slow (>1 min for 1,000 files)

**Diagnosis:** Including node_modules, build artifacts
**Fix:** Add to excludePatterns in config

### Issue 4: Java not found

**Diagnosis:** Java not installed or not in PATH
**Fix:** Install Java 17+ from adoptium.net

### Issue 5: Permission denied

**Diagnosis:** Synthesis JAR not executable
**Fix:** `chmod +x synthesis.jar`

---

## Post-Workshop Follow-Up

**Send within 24 hours:**

**Email subject:** "Synthesis Workshop - Resources & Next Steps"

**Email body:**
```
Thanks for attending the Synthesis workshop!

Quick links:
- Workshop slides: [link]
- Quick Start Guide: [link]
- Full User Guide: [link]
- Slack/Discord for questions: [link]

Your homework (takes 5 min/day):
1. Day 1: Run `synthesis scan` on your main project
2. Day 2: Search for something you need (instead of grep)
3. Day 3: Run `relate` before refactoring a file
4. Day 4: Generate your architecture graph
5. Day 5: Share one interesting finding with your team

Reply to this email with:
- One thing you learned
- One thing you tried
- One question you still have

Keep building!
[Your name]
```

**Follow up after 1 week:**

*"Quick check-in: Are you still using Synthesis?"*
- Yes → *"What's been most valuable?"*
- No → *"What's blocking you?"*

**Success metrics:**
- 70%+ attendees using Synthesis 1 week later
- 50%+ attendees using Synthesis 1 month later
- 3+ attendees become internal champions

---

## Facilitator Checklist

**Week before:**
- [ ] Send pre-work email
- [ ] Prepare sample codebase (if needed)
- [ ] Test all demos on your laptop
- [ ] Print handouts (command reference)
- [ ] Set up feedback form

**Day before:**
- [ ] Test projector/screen
- [ ] Verify internet (for mermaid.live if using)
- [ ] Charge laptop
- [ ] Prepare USB drives with Synthesis JAR

**Day of (arrive 30 min early):**
- [ ] Test projector again
- [ ] Set up Slack/Discord channel
- [ ] Help stragglers with installation
- [ ] Start on time (respect attendees' schedules)

**During workshop:**
- [ ] Walk around during exercises
- [ ] Answer questions publicly (everyone benefits)
- [ ] Collect interesting findings/stories
- [ ] Stay on time (use timer)

**After workshop:**
- [ ] Send resources email within 24 hours
- [ ] Review feedback
- [ ] Follow up with non-responders after 1 week

---

## Your Next Step

**Pick your workshop format:**
- 2 hours → Use Module 1-6 (core workflow)
- 4 hours → Add Module 7-9 (advanced + use cases)
- 6-8 hours → Add Module 10-12 (deep-dive projects)

**Schedule it:**
- Internal team? → Friday afternoon lunch-and-learn
- Client workshop? → Tuesday-Thursday full-day
- Conference? → 2-hour session

**Prepare:**
- Send pre-work email 1 week before
- Test demos on your laptop
- Bring energy (you're teaching a superpower!)

---

**Related documentation:**
- **For your attendees:** [Quick Start](../guides/QUICK-START.md) | [User Guide](../guides/USER-GUIDE.md)
- **For workshop examples:** Sample codebases in `examples/` directory
- **For CI/CD integration:** [DevOps Guide](./DEVOPS.md)
- **For team adoption:** [Engineering Manager Guide](./ENGINEERING-MANAGER.md)
