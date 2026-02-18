# The Tool That Manages the Tool That Makes Too Much

*A builder's story about shipping 8 versions in 4 days — and why that's exactly the problem.*

---

## Day Zero: You Broke Your Own Workflow

Here's a thing nobody warns you about when you start building with AI.

It works.

Not "kinda works." Not "useful sometimes." It actually works — and once it does, you don't slow down. You accelerate. The codebase grows. The docs multiply. PDFs, diagrams, analysis reports, companion files, slide decks — all of it accumulates at a speed that no human process was designed to manage. You've hired a team of tireless collaborators who never sleep, never forget to commit, and never run out of ideas. And they produce *so much output* that you can no longer find your own stuff.

That is the problem Synthesis was built to solve.

Not a theoretical problem. A real one — happening in a real workspace, in real time, to a developer who was moving fast enough to drown in their own momentum.

---

## February 14, 2026. First Commit.

The git log doesn't lie. `first commit` lands at the start of a Friday morning. There's no prototype phase. No spike branch. No "let me think about the architecture." Just: a Maven project, a name, and a problem that needs solving.

By end of day, five pull requests had merged:

- Interactive workspace initialization
- Skill generation for Claude Code integration
- Distribution and packaging
- Media support — images, video, audio
- AI vision analysis

That's not a day's work in the traditional sense. That's a week. Maybe two.

But this is the meta-story: **Synthesis was built using the same AI toolchain that created the problem it solves.** Claude Code wrote Synthesis. Synthesis tames the output of Claude Code. If that loop makes your head spin a little, good — it should.

---

## The Problem at Scale (and Why "Scale" Matters Here)

Let's be precise about what "excess data" means in an AI-assisted development workflow.

When you build with Claude Code seriously — across multiple clients, multiple products, multiple codebases — you generate:

- Code files, obviously
- Synthesis documents: AI analysis of PDFs, presentations, architecture diagrams
- Companion files: `.synthesis.md` enrichments attached to every image, video, and doc
- Research reports, multi-pass analysis, executive summaries
- Skill files, prompt libraries, context bundles

Traditional tools don't care. `grep` doesn't know the difference between a stale temp file and a critical architecture decision. `find` can't rank results by relevance. Search-as-grep breaks at the exact moment you need it most — when you have *too much* to search through.

The technical answer is Apache Lucene with BM25 ranking, multi-field indexing, and per-filetype analyzers that understand the difference between a Java import and a Markdown heading. But the *human* answer is simpler:

**Your filesystem becomes a knowledge graph. Synthesis is the graph engine.**

---

## Four Days. Eight Versions. One Direction.

Here's what the git history actually shows:

| Day | Versions shipped | Headline feature |
|-----|-----------------|------------------|
| Feb 14 | v1.0.0 → v1.0.3 | Search, vision, MCP server, media support |
| Feb 15 | v1.1.0 → v1.2.2 | LSP server, local enrichment (Whisper, Tesseract), metrics |
| Feb 16 | v1.4.0 → v1.6.1 | Sub-workspaces, org intelligence, executive summaries |
| Feb 17 | v1.7.0 → v1.8.1 | Research commands, AI rename, PDF vision, staging |

145 commits. 91 meaningful feature changes. From zero to a production-grade knowledge infrastructure tool with 35 CLI commands in 96 hours.

This isn't a flex about velocity for its own sake. It's the point. **The rate at which you can build with AI is the rate at which your knowledge infrastructure breaks down without something like Synthesis.** The two curves rise together.

---

## The Numbers Don't Lie

Someone will read the section above and think: marketing. Fair. Here's the raw data. All of this is in the git history.

159 commits across 5 days, February 14–18, 2026. 29 releases shipped — v1.0.0 through v1.8.4, then v1.9.0, v1.9.1, v1.9.2, v1.9.3. That's roughly 6 releases per day. Not tags-for-fun releases. Each one pushed to Maven Central with a fat JAR that boots.

The codebase as it stands: 175 source files. Approximately 49,000 lines of production Java across 28 packages in `io.exoreaction.synthesis.*`. 86 test files containing 2,291 tests — all passing. That test count grew from 802 on day three to 1,054 on day four to 2,291 by day five. The test suite expanded faster than the production code because every feature shipped with coverage, and then the coverage got expanded again when edge cases showed up in real use.

One Maven build produces 3 fat JARs: the CLI, the MCP server, and the LSP server. 21 external dependencies — Lucene 10.1.0 for search, Anthropic SDK 2.14.0 for AI, PDFBox 3.0.4 for documents, JGit for repository analysis, Tesseract for OCR, Slack API for notifications, LSP4J for editor integration, JGraphT for dependency graphs. 11 SQLite tables managed by 7 Flyway migrations (V1 through V8, V7 intentionally absent — long story, short answer: a migration got deleted and the version number stayed reserved).

In the development environment right now: 36,342 files indexed across 8 workspaces. Total index size: 58.6 MB. Validated search time: 0.4 seconds. The deep-dive architecture report that analysed this entire codebase — every package, every pattern, every dependency — runs 1,561 lines. It was generated in a single Claude Code session by reading actual source files. Not documentation. Source.

The observation that matters: none of these numbers are impressive individually. A senior engineer could write 49,000 lines of Java in a few months. What's unusual is the *density* — 29 releases in 5 days means every feature was shipped, tested in production, and refined before the next one started. The git log doesn't show a prototype that got cleaned up. It shows a product that was production-ready on commit one and stayed that way through 158 more.

---

## Show-off Moment #1: Your AI Agent Can Now Navigate Your Codebase

On day two, Synthesis grew a **Model Context Protocol server** — the standard that lets Claude Code, Cursor, and Aider talk to external tools in real time.

What that means in practice:

```
synthesis --mcp-server
```

That's it. Now Claude Code has tools: `search`, `relate`, `graph`, `ask`, `enrich`, `explain`. It can query your indexed workspace mid-conversation. It doesn't have to re-read 47 files from scratch every session. It asks Synthesis. Synthesis answers.

For a startup running AI agents across a multi-repo workspace, this is the difference between an agent that's confused and one that has context. It's the difference between `grep` and *understanding*.

---

## Show-off Moment #2: Drop a PDF. Get Knowledge.

Here's the workflow that gets people's attention:

You receive a client presentation. You save it to `~/clients/acme/Q1-Strategy.pdf`. You run:

```bash
synthesis enrich
```

Synthesis looks at the PDF with Claude's vision API. It reads the slides — not just extracts text, but *understands* the visual layout. It generates a companion file: `Q1-Strategy.pdf.synthesis.md`. Every slide is summarized. Key topics are extracted. The document is now searchable.

Next time you run `synthesis ask "what is Acme's Q1 priority?"` — it knows.

This works on images, video files, audio recordings, and scanned documents with no extractable text. The vision tier kicks in wherever text extraction fails. The Whisper integration transcribes your meeting recordings locally, without sending audio to any API. The Tesseract OCR reads your whiteboard photos.

Your filesystem stops being a graveyard of unsearchable files. Everything becomes findable.

---

## Show-off Moment #3: It Knows Your Clients

The org intelligence feature is the one that surprises people most.

Synthesis can infer your organizational structure from directory layout. Point it at a workspace with folders like `~/clients/acme/`, `~/clients/beta-corp/`, `~/products/platform/` — and it builds a live org map. Companies, clients, products, codebases, relationship status (ACTIVE, PAST, OPPORTUNITY, SIGNED).

Then:

```bash
synthesis report --client acme
```

It generates a business document: what code exists for this client, recent changes, outstanding items, AI-generated summary. Without any manual tagging. Without any configuration file you had to remember to maintain.

For a startup juggling five clients and three products simultaneously, this is not a nice-to-have. It's the difference between knowing what you shipped last week and having to reconstruct it from memory.

---

## Show-off Moment #4: AI Rename

This one is small and extremely satisfying.

```bash
synthesis staging rename ~/Downloads/
```

Synthesis reads the content of every file in the folder — using vision for PDFs and images, text extraction for docs — and suggests a meaningful filename based on what the file *actually contains*. `scan-0042.pdf` becomes `acme-q1-architecture-review-2026.pdf`. `IMG_3847.png` becomes `whiteboard-auth-flow-diagram.png`.

You approve. It renames.

The Downloads folder is no longer a black hole.

---

## The Only Tool That Works at This Scale

There's a specific claim embedded in Synthesis's design decisions that's worth naming directly.

Most developer tools are built for codebases. A few hundred files. Maybe a few thousand. They assume a human is the primary navigator — that someone will use the IDE, read the file tree, maintain the docs.

Synthesis assumes the opposite. It assumes a workspace with **tens of thousands of files** across code, docs, media, configs, presentations, and analysis artifacts — because that's what you actually have when you've been building with AI for a few months. It assumes multiple clients, multiple products, nested sub-workspaces with independent indexes. It assumes nobody will maintain the docs manually, because nobody has time.

That's why it indexes automatically on change. That's why the MCP server exists — so agents can navigate without human assistance. That's why enrichment generates companion files instead of modifying originals. That's why the three-tier enrichment model (BASIC → LOCAL → AI) degrades gracefully when you're offline or in an air-gapped environment.

It's infrastructure. Not a tool you use. Infrastructure that runs underneath the tools you use.

---

## Skill-Driven Development

Something happened during the build that wasn't planned. It's obvious in hindsight, but it wasn't obvious at the time.

Synthesis ships 27 Claude Code skills inside the JAR itself — bundled in `src/main/resources/claude-skills/`. When a user runs `synthesis export-skills`, they get structured instructions for every feature: how to search, how to use relate, how to build dependency graphs, how to run enrichment, how to manage staging areas, how to verify reports. These aren't documentation. They're not README fragments. They're skills — machine-readable context that Claude Code picks up automatically and uses during sessions.

That was the product feature. Here's what happened next.

The Synthesis repository itself grew a `.claude/skills/` directory with 25 development skills: architecture patterns, database migration conventions, CLI interaction patterns, release workflow, staging management, metrics tracking, workspace lifecycle. A `CLAUDE.md` project context file sits at the root — tech stack, key commands, known gotchas, skills navigation. When a new Claude Code session opens in the Synthesis repo, it already knows the architecture. It knows that SQLite JDBC's `getObject(col, Integer.class)` fails silently on NULL columns. It knows that JUnit 5's `assertDoesNotThrow()` needs an explicit cast to `(Executable)`. It knows that Flyway migration files must follow `V{n}__description.sql` naming exactly, and that V7 is reserved.

Zero warmup. Full context. Every session starts where the last one left off — not because of memory, but because the project teaches the agent what it needs to know.

This is a pattern. Not a feature. The pattern is: **you're not just shipping code anymore — you're shipping the context that lets AI agents work with your code.** The product teaches the agents. The agents build the product. The loop closes.

I didn't design this. It emerged from the fact that I was building Synthesis *with* Claude Code *for* Claude Code. The skills that help users use Synthesis are the same kind of artifact as the skills that help Claude Code develop Synthesis. Once you see it, you can't unsee it: every serious project in the AI-assisted era will ship its own skills, or it will be at a disadvantage against projects that do. The agents will prefer the codebase that explains itself.

Call it Skill-Driven Development. Or don't call it anything — just notice that the projects shipping context alongside code are the ones where AI agents are most effective. The rest are still doing the equivalent of handing a new hire a laptop and saying "good luck."

---

## What This Means for Builders

If you're building with AI — really building, not just using autocomplete — you're already generating more knowledge artifacts than you can manage manually. The question isn't whether you need this kind of infrastructure. The question is whether you build it yourself or use something that already exists.

Synthesis is five days old and already on version 1.9. That's what building on top of AI tooling looks like. That's what the feedback loop feels like when you're your own first user.

The real lesson from the git history isn't the velocity. It's the direction: **every single commit is a response to a real pain point encountered while using the previous version.** First commit: search works. Second commit: need vision. Third commit: need to talk to AI agents. Fourth commit: need to handle local files without the API. The product is the log of what broke while building the product.

That's the startup loop. That's the builder loop. And at AI-assisted development speeds, it runs four times faster than you expect.

Which means you need your knowledge infrastructure in place before you need it. Not after.

---

*Synthesis is an open infrastructure project by [eXOReaction AS](https://exoreaction.io). Built with Claude Code. Eats its own dog food. Currently on v1.9. Ships its own skills.*
