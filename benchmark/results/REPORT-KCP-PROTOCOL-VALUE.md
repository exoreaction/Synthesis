# Structured Knowledge Manifests vs Runtime Tool Discovery: The KCP--MCP Composability Model

*Analytical Report -- February 2026*
*Based on Phase 5 benchmark data (6 conditions, 9 tasks, 54 agent sessions)*

---

## Abstract

This report examines the tradeoff between pre-loaded structured context and runtime tool invocation for AI agent performance, using benchmark data from the Synthesis knowledge infrastructure project. It draws on results from six experimental conditions -- Baseline, Knowledge (CLAUDE.md + skills), MCP (41 runtime tools), MCP + Hint, MCP + Improved Descriptions, and CLI -- across nine tasks probing factual recall, code navigation, architecture analysis, and relationship discovery.

The central finding: pre-loaded context and runtime tools are not competing strategies. They solve different problems. The Knowledge Context Protocol (KCP) formalizes the pre-loaded context layer with structured metadata that addresses the specific failure modes observed in the Knowledge condition -- most notably, hallucinated dates caused by stale narrative text. MCP provides the runtime discovery layer that pre-loaded context cannot replace. The two protocols compose naturally: KCP defines *what to load before the session starts*; MCP defines *what to discover during the session*.

---

## 1. The Core Tradeoff: Efficiency vs Accuracy

The benchmark measured two orthogonal dimensions: **tool call efficiency** (fewer calls = less latency, less cost) and **correctness** (scored 0--12 per task across structural, currency, depth, and semantic rubrics).

### Efficiency Rankings

| Condition | Avg tool calls | Delta vs Baseline |
|-----------|---------------|-------------------|
| MCP + Hint (Cond5) | 5.3 | -40% |
| MCP (Cond4) | 5.8 | -35% |
| Knowledge | 7.6 | -15% |
| MCP + Descriptions (Cond6) | 7.6 | -15% |
| Baseline | 8.9 | -- |
| CLI | 9.9 | +11% |

### The Tension

The Knowledge condition achieved 7.6 average tool calls -- a 15% improvement over Baseline -- with **zero MCP calls**. The agent answered entirely from pre-loaded CLAUDE.md context. This is the most efficient possible outcome for tasks where the answer is already in context.

But the Knowledge condition also produced the benchmark's most instructive failure: on task E1 (ROI metrics), the agent answered with 0 tool calls, got every metric correct, and **invented a date** -- reporting "Feb 19" instead of the ground truth "Feb 17". The response was fluent, confident, and wrong on exactly one dimension: temporal precision.

The MCP condition, by contrast, used 0 tool calls on the same task (also answering from context) and produced the same hallucination. But the MCP + Hint condition (Cond5) used 1 Read call to verify -- and got the correct date. The MCP + Descriptions condition used 4 calls (2 search + 2 Read) -- over-invoked, but correct.

This is the core tradeoff distilled to a single data point:

- **Pre-loaded context:** 0 calls, 10/12 correctness (hallucinated date)
- **Hint-guided verification:** 1 call, correct
- **Over-eager tool use:** 4 calls, correct but wasteful

The optimal agent uses pre-loaded context as its first answer and runtime tools as its verification mechanism. Neither alone is sufficient.

---

## 2. Where Pre-Loaded Context Wins

Two tasks demonstrate clear wins for the Knowledge condition.

### E1: ROI Metrics (Factual Recall)

The Knowledge condition: 0 tool calls. The agent extracted indexing throughput (200-300 files/sec), search latency (0.4s), file count (36,342), retrieval time reduction (92-95%), and test count (3,933) directly from CLAUDE.md. All correct. The only error was temporal: it cited "February 19, 2026" as the validation date when the actual date was February 17.

For the MCP (Cond4) condition, the result was identical: 0 calls, same metrics, same hallucinated date. The agent's pre-loaded context was sufficient for the factual payload, and no condition prompted it to verify the date -- except Cond5 (MCP + Hint), where the agent chose to read one file.

**Lesson:** When the answer domain is stable (product metrics that change quarterly, not daily), pre-loaded context eliminates all tool overhead. The failure mode is temporal precision, not factual accuracy.

### P4-B1: Flyway Migration Numbering

The Knowledge condition: 1 Glob call. CLAUDE.md stated that V7 was "intentionally reserved." The agent needed only to confirm the file naming pattern (`V*.sql`) with a single Glob call to list migration files. Score: 12/12.

The MCP (Cond4) condition was identical: 1 Glob call. The Baseline needed 8 calls. This task illustrates a category where pre-loaded context directly names the answer (a specific convention or design decision) and the agent needs only minimal verification.

**Lesson:** Convention documentation and design decisions are ideal candidates for pre-loading. The answer is deterministic and changes only when a human makes a new decision.

### The Pattern

Pre-loaded context wins when:

1. **The answer is stable.** Metrics, architectural decisions, naming conventions, configuration choices.
2. **The answer is explicitly stated.** Not inferred from code, but declared in documentation.
3. **Temporal precision is not critical.** The agent needs to know *what* was decided, not *when exactly* it was last validated.

---

## 3. Where Runtime Tools Win

Three tasks demonstrate clear wins for MCP runtime tools.

### P5-R2: Module Dependency Graph

The Baseline needed 32 Bash calls to reconstruct a module dependency graph by manual inspection. MCP (Cond4) used `code-graph` twice + 8 Bash verification calls = 10 total (-69%). MCP + Hint (Cond5) used `code-graph` five times + 0 other calls = 5 total (-84%). MCP + Descriptions (Cond6) used `code-graph` three times = 3 total (-91%).

No amount of pre-loaded documentation can replace a tool that computes dependency graphs from live code. The `code-graph` tool operates on the indexed state of the codebase, which changes with every commit. Static context would be stale within hours.

### P5-R1: SearchIndex Callers

The Baseline used 5 calls. MCP (Cond4) used 2 Grep calls. MCP + Descriptions (Cond6) used `relate` + `which` = 2 calls with zero Grep. The `relate` tool replaced manual symbol search with pre-indexed bidirectional relationship data.

Pre-loaded context cannot enumerate every caller of every method. This is inherently a runtime discovery task.

### B3: Cross-Repository Dependencies

The Baseline needed 9 calls. MCP (Cond4) used `cross-repo-deps` + 2 standard calls = 3 total. Cross-repository dependency graphs span multiple repositories and change as versions are bumped. No static manifest captures this.

### The Pattern

Runtime tools win when:

1. **The answer is computed, not stated.** Dependency graphs, caller analysis, impact analysis.
2. **The answer changes with code.** Any query whose result depends on the current state of the codebase.
3. **The search space is large.** Finding all usages of a symbol across 8,934 files is not a documentation problem.

---

## 4. The Hallucination Problem: Why Text Context Invents Dates

The E1 hallucination is the benchmark's most important finding for protocol design. Here is what happened:

CLAUDE.md contained validated metrics (all correct) embedded in narrative prose. It said the metrics were "Validated (Feb 14, 2026)" but also referenced benchmark sessions, phases, and dates in surrounding paragraphs. The agent, processing this narrative text, synthesized a date -- "February 19, 2026" -- that appeared nowhere in the source material. The actual validation date was February 17.

This is not a retrieval error. The agent did not retrieve the wrong date from a wrong file. It **confabulated** a date by interpolating from temporal cues in surrounding context. This is a well-documented failure mode of large language models when processing narrative temporal references: the model generates a plausible-sounding date rather than extracting the exact one.

### Why CLAUDE.md is Vulnerable

CLAUDE.md embeds facts in prose. Dates appear as natural language: "Validated Feb 14, 2026" in one section, "Phase 5 benchmark session" in another, "v1.3.0-SNAPSHOT, PR #18 merged Feb 16, 2026" elsewhere. The agent must parse temporal references from surrounding narrative, which introduces ambiguity.

The critical weakness is that **CLAUDE.md has no machine-readable freshness signal**. There is no field the agent can parse deterministically. The date is embedded in prose alongside other dates, creating a disambiguation problem that LLMs solve probabilistically -- and sometimes incorrectly.

### How KCP's `validated` Field Prevents This

A KCP manifest represents the same information differently:

```yaml
units:
  - id: synthesis-metrics
    path: docs/METRICS.md
    intent: "What are Synthesis's validated performance metrics?"
    scope: global
    audience: [agent, developer, architect]
    validated: 2026-02-17
    triggers: [performance, metrics, ROI, indexing-speed, search-latency]
```

The `validated` field is an ISO 8601 date -- a machine-readable scalar, not a phrase embedded in prose. An agent processing this manifest does not need to interpret temporal language. It reads a field value. There is no interpolation, no disambiguation, and no ambiguity about which date refers to which fact.

The `validated: 2026-02-17` field would have provided exactly the date the agent hallucinated. The E1 failure is directly attributable to the absence of structured temporal metadata -- precisely the gap KCP fills.

### The Deeper Point

Hallucination in this context is not a model deficiency. It is a **representation deficiency**. The model behaves correctly given the input format: it processes narrative text and generates a plausible continuation. The fix is not a better model; it is a better input format that separates machine-readable metadata from human-readable prose.

---

## 5. The Composability Argument: KCP + MCP as Complementary Protocols

### What Each Protocol Provides

| Concern | KCP | MCP |
|---------|-----|-----|
| When is it used? | Before session starts (context loading) | During session (runtime invocation) |
| What does it provide? | Structured metadata about what knowledge exists | Tool invocation for discovery and computation |
| Freshness signal | `validated` field (ISO date per unit) | Live computation from indexed state |
| Dependency ordering | `depends_on` field (load prerequisites first) | Not applicable (tools are independent) |
| Audience routing | `audience` field (agent, developer, architect) | Not applicable (all tools available to all agents) |
| Staleness detection | `validated` date vs current date = staleness | Implicit (always returns current state) |
| Scale model | Static file, kilobytes | Running server, 41 tools, sub-second queries |
| Failure mode | Stale context (E1 date hallucination) | Over-invocation (Cond6 P4-B1: 18 calls) |

### The Composability Model

The benchmark data supports a three-layer context architecture:

**Layer 1: KCP Manifest (pre-session)**
Load `knowledge.yaml` before the agent session begins. The manifest tells the agent:
- What knowledge units exist (`id`, `path`, `intent`)
- Which units are relevant to this task (`triggers`, `audience`)
- What order to load them in (`depends_on`)
- How fresh each unit is (`validated`)
- What has been superseded (`supersedes`)

The agent loads the relevant units into its context window. For tasks like E1 (ROI metrics) and P4-B1 (Flyway conventions), the answer is now in context with structured metadata. The `validated` field prevents temporal hallucination.

**Layer 2: Decision Heuristic (session configuration)**
A lightweight system prompt signal -- equivalent to the Cond5 one-line hint -- tells the agent when to trust pre-loaded context and when to invoke runtime tools:

> If the answer is in pre-loaded context and the `validated` date is within 7 days, answer directly. If the answer requires computation (dependency graphs, caller analysis, impact scope) or the `validated` date exceeds the freshness threshold, use MCP tools.

The Cond5 result (-40% vs Baseline) demonstrates that a single sentence of decision guidance produces the best efficiency result across all conditions.

**Layer 3: MCP Tools (runtime)**
For tasks that require discovery (`P5-R1`, `P5-R2`, `B3`) or verification when pre-loaded context is stale, invoke MCP tools. The `code-graph`, `relate`, `impact`, and `search` tools handle the computational queries that no static manifest can answer.

### Why This Composability Works

The benchmark reveals that the three layers address distinct failure modes:

| Failure mode | Observed in | Fixed by |
|-------------|-------------|----------|
| Temporal hallucination | Knowledge condition, E1 | KCP `validated` field (Layer 1) |
| Over-invocation | Cond6, P4-B1 (18 calls) | Decision heuristic (Layer 2) |
| Unable to answer computed queries | Knowledge condition, P5-R2 | MCP runtime tools (Layer 3) |
| Agent ignores available MCP tools | Cond4, 5/9 tasks no MCP | Decision heuristic (Layer 2) |
| Agent trusts stale context | E1 date, all pre-loaded conditions | KCP freshness threshold (Layer 1+2) |

No single layer addresses all five failure modes. The composability model does.

---

## 6. Practical Guidance

### When to Use KCP Manifest Injection Alone

- **Factual recall tasks** where the answer is documented and stable (ROI metrics, product specifications, architectural decisions, configuration conventions)
- **Convention queries** ("What naming pattern do we use for X?", "Why is V7 reserved?")
- **Onboarding context** where the agent needs to understand project structure before doing work
- **Low-frequency-change domains** where `validated` dates remain within the freshness threshold for weeks or months

**Expected efficiency:** 0-2 tool calls per task. **Risk:** Stale data if `validated` dates are not maintained.

### When to Use MCP Runtime Tools Alone

- **Computed queries** (dependency graphs, caller analysis, impact analysis, cross-repo relationships)
- **Discovery tasks** in unfamiliar codebases where the agent does not know which files to read
- **Currency-critical queries** ("What changed in the last 7 days?", "What are the current security findings?")
- **Tasks requiring transitive analysis** (change blast radius, architecture layers, circular dependency detection)

**Expected efficiency:** 3-10 tool calls per task. **Risk:** Over-invocation if tool descriptions are too directive (the Cond6 `search` regression).

### When to Use Both (Recommended Default)

- **Any production agent deployment** where both accuracy and efficiency matter
- **Mixed-domain sessions** that combine factual questions with code investigation
- **Enterprise contexts** where knowledge freshness is a compliance or quality requirement

**Implementation pattern:**

1. Generate `knowledge.yaml` from the Synthesis index: `synthesis export --format kcp`
2. Inject KCP-selected units into the agent's system prompt at session start
3. Configure MCP tools for runtime invocation
4. Add a one-line decision heuristic (the Cond5 pattern) to the system prompt

**Expected efficiency:** 2-6 tool calls per task (the Cond5 result). **Risk:** Minimal -- the decision heuristic prevents both over-reliance on stale context and over-invocation of tools.

---

## 7. The Description Quality Problem

An important secondary finding: MCP tool description wording has non-obvious effects on agent behavior.

The Cond6 experiment rewrote five tool descriptions with "Use this FIRST" and "Use INSTEAD OF Grep" language. The result was counterintuitive: Cond6 averaged 7.6 tool calls -- **identical to Knowledge-only and 31% worse than baseline MCP (Cond4)**.

The root cause was differential: `relate` and `code-graph` descriptions worked perfectly (P5-R1: 2 calls, P5-R2: 3 calls -- both improvements). But the `search` description ("Use this FIRST for finding relevant files") caused the agent to search even when CLAUDE.md context was sufficient, producing regressions on E1 (0 -> 4 calls) and P4-B1 (1 -> 18 calls).

The lesson for KCP: **description language in both manifests and tool schemas must be conditional, not imperative.** "Use this FIRST" is an absolute instruction that overrides the agent's cost model. "Use this when you need to discover files in an unfamiliar area of the codebase" is a conditional instruction that preserves the agent's ability to choose the cheapest correct path.

KCP's `triggers` field is inherently conditional -- it signals relevance without commanding action. This is the right design pattern for agent-facing metadata.

---

## 8. Conclusion

The benchmark data demonstrates that KCP and MCP address fundamentally different problems in the AI agent knowledge stack.

**KCP** solves the **context loading problem**: what should the agent know before it starts working? Its structured fields -- `validated`, `depends_on`, `triggers`, `audience`, `supersedes` -- provide machine-readable metadata that narrative text cannot express without ambiguity. The E1 hallucination is direct evidence that unstructured temporal references in prose context lead to confabulation, and that a structured `validated` field would have prevented it.

**MCP** solves the **runtime discovery problem**: what should the agent compute or look up during the session? Its 41 tools provide capabilities that no static manifest can replicate -- dependency graphs, impact analysis, caller enumeration, change tracking.

**Together**, they form a composability model that outperforms either alone:

```
KCP Manifest (pre-session)     MCP Tools (runtime)
  knowledge.yaml                 41 tools via JSON-RPC
  |                              |
  | Load relevant units          | Invoke for discovery/computation
  | Check validated dates        | Verify stale context
  | Respect depends_on order     | Answer computed queries
  |                              |
  +--- Decision Heuristic ------+
       (1-line system prompt)
       "Trust pre-loaded if fresh;
        invoke tools if stale or computed"
```

The benchmark evidence for this model is concrete:

- Pre-loaded context alone: -15% efficiency, hallucination risk on temporal data
- MCP tools alone: -35% efficiency, but 5/9 tasks underutilized MCP
- MCP + decision hint: **-40% efficiency**, best overall, correct on E1
- KCP structured metadata: would have prevented the E1 hallucination entirely

KCP is not a competitor to MCP. It is the missing complement -- the structured knowledge layer that MCP's tool layer needs to serve context correctly. The protocols are independently useful and jointly optimal.

---

*Data sources: Phase 5 benchmark (Conditions 1-6), 54 agent sessions, 9 tasks, model: claude-sonnet-4-6, workspace: 8,934 files.*
*KCP Specification: v0.1 draft -- github.com/cantara/knowledge-context-protocol*
*Synthesis: v1.18.0 -- github.com/exoreaction/Synthesis*
