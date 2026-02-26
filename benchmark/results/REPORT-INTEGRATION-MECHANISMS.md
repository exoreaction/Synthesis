# Integration Mechanism Comparison: MCP Tools vs CLI Guide Skills for AI-Assisted Codebase Navigation

**Date:** February 26, 2026
**Authors:** Thor Henning Hetland, eXOReaction
**Benchmark codebase:** Synthesis v1.18.x (48,739 LOC Java, 2,325 tests, 25 skill files)
**Model:** claude-sonnet-4-6 (all conditions)
**Tasks:** 9 codebase navigation questions with verified ground truths

---

## Abstract

When integrating a knowledge infrastructure tool into an AI coding agent's workflow, there are two fundamentally different mechanisms: (1) exposing tool capabilities via the Model Context Protocol (MCP), which grants the agent runtime-invocable tools, and (2) providing CLI guide skills, which inject static documentation about available commands into the agent's context. We ran 9 identical codebase navigation tasks across 6 experimental conditions on the Synthesis codebase, measuring total tool calls as a proxy for agent efficiency. MCP tools reduced tool calls by 35-40% versus baseline. CLI guide skills increased tool calls by 11% -- worse than having no Synthesis integration at all. A one-line system prompt hint proved more effective than rewriting all 41 MCP tool descriptions. This report presents the per-task analysis, explains the failure modes of each mechanism, and provides practical recommendations for tooling teams.

---

## 1. Definitions: Two Integration Mechanisms

### MCP Tools (Runtime Invocation)

The Model Context Protocol exposes tools as callable functions. The agent receives a `tools/list` manifest describing each tool's name, description, and JSON schema. At runtime, the agent can invoke any tool and receive structured results. In our benchmark, Synthesis exposed 41 MCP tools (`search`, `relate`, `code-graph`, `trace`, `impact`, etc.) via an HTTP server. The agent sees these tools alongside its built-in tools (Grep, Glob, Read, Bash) and chooses which to call.

The key property: MCP tools are **actionable**. The agent can use them without intermediate steps.

### CLI Guide Skills (Static Context)

CLI guide skills are markdown files loaded into the agent's system prompt. Each skill describes a CLI command -- its syntax, options, typical use cases, and expected outputs. The agent reads this documentation and must then compose the correct CLI invocation via a Bash tool call. In our benchmark, 15 CLI guide skills documented Synthesis commands like `synthesis search`, `synthesis relate`, `synthesis graph`, etc.

The key property: CLI guide skills are **informational**. The agent must translate documentation into action through a separate tool (Bash), adding an indirection layer.

---

## 2. Experimental Conditions

Six conditions were tested against the same 9 tasks, each in an isolated session with no cross-contamination:

| # | Condition | What the agent receives | Avg tool calls | vs Baseline |
|---|-----------|------------------------|:--------------:|:-----------:|
| 1 | **Baseline** | Standard Claude Code, no Synthesis | **8.9** | -- |
| 2 | **Knowledge** | CLAUDE.md + 14 knowledge skills (no tools, no CLI guides) | **7.6** | -15% |
| 3 | **CLI** | Knowledge + 15 CLI guide skills (commands via Bash) | **9.9** | **+11%** |
| 4 | **MCP** | Knowledge + 41 MCP tools (original descriptions) | **5.8** | **-35%** |
| 5 | **MCP + Hint** | MCP + one-line system prompt: "Prefer `search` over Grep..." | **5.3** | **-40%** |
| 6 | **MCP + Descriptions** | MCP + rewritten descriptions ("Use INSTEAD OF Grep") | **7.6** | -15% |

The ordering is unambiguous: **MCP + Hint > MCP > Knowledge = MCP + Descriptions > Baseline > CLI**.

The CLI condition is the worst-performing condition in the entire benchmark, performing 11% worse than an agent with no Synthesis integration at all.

---

## 3. Per-Task Results

| Task | Baseline | Knowledge | CLI | MCP (C4) | MCP+Hint (C5) | MCP+Desc (C6) |
|------|:--------:|:---------:|:---:|:--------:|:--------------:|:--------------:|
| P5-R1 (SearchIndex callers) | 5 | 2 | 3 | 2 | 6 | 2 |
| P5-R2 (Module dep graph) | 32 | 10 | 14 | 10 | 5 | 3 |
| E1 (ROI metrics) | 6 | 1 | 3 | 0 | 1 | 4 |
| C2 (isAnchorDoc) | 4 | 3 | 1 | 3 | 2 | 3 |
| P4-C1 (--since flow) | 6 | 11 | 18 | 16 | 19 | 16 |
| P4-F2 (Pilot approval) | 5 | 9 | 10 | 7 | 6 | 8 |
| P4-B1 (Flyway migrations) | 8 | 3 | 5 | 1 | 2 | 18 |
| B3 (Cross-repo deps) | 9 | 8 | 8 | 3 | 4 | 10 |
| P5-A1 (Lucene boost fields) | 5 | 22 | 27 | 10 | 3 | 4 |
| **Average** | **8.9** | **7.6** | **9.9** | **5.8** | **5.3** | **7.6** |

---

## 4. Deep Dive: Five Revealing Tasks

### P5-R1: SearchIndex Callers -- When Grep Is Already Optimal

**Task:** List all production classes calling `SearchIndex.search()` or `SearchIndex.openReadOnly()`.

| Condition | Calls | Method |
|-----------|:-----:|--------|
| Baseline | 5 | Grep x5 (broad search, refinement) |
| MCP (C4) | 2 | Grep x2 (exact symbol match) |
| MCP+Hint (C5) | 6 | `search` x1 + Grep x4 + Read x1 |
| MCP+Desc (C6) | 2 | `relate` x1 + `which` x1 |

This task reveals a critical pattern: **the system prompt hint made things worse** (+200% over C4). The hint told the agent to "Prefer `search` over Grep for discovery," so it dutifully invoked `search` -- then ran Grep anyway to verify the result, adding calls rather than replacing them. The MCP tool was used additively, not substitutively.

Meanwhile, the improved description in C6 ("Use INSTEAD OF Grep when finding callers") correctly redirected the agent from Grep to `relate`, achieving the same 2-call efficiency as C4 but through the intended MCP path.

**Lesson:** "Prefer X" causes additive behavior. "Use X INSTEAD OF Y" causes substitutive behavior. The phrasing of the heuristic determines whether MCP calls replace or duplicate standard tool calls.

### P5-R2: Module Dependency Graph -- MCP's Structural Advantage

**Task:** Describe the complete module dependency structure and identify circular dependencies.

| Condition | Calls | Method |
|-----------|:-----:|--------|
| Baseline | 32 | Bash x32 (manual `find`, `grep`, `awk` across packages) |
| MCP (C4) | 10 | `code-graph` x2 + Bash x8 (MCP discovery + verification) |
| MCP+Hint (C5) | 5 | `code-graph` x5 (pure MCP, no Bash) |
| MCP+Desc (C6) | 3 | `code-graph` x3 (pure MCP, optimal) |

This is the strongest MCP case in the benchmark. Baseline required 32 shell commands to manually reconstruct the dependency graph. The tool name `code-graph` directly aligned with the task concept "dependency graph," enabling correct tool selection even without prompting.

The progression across MCP conditions is instructive: C4 used `code-graph` for discovery but fell back to Bash for verification (10 calls). C5's hint gave the agent confidence to use `code-graph` exclusively (5 calls). C6's description -- "replaces dozens of Grep/Read calls with a single pre-computed analysis" -- achieved the optimum at 3 calls.

**Lesson:** When a tool name lexically matches the task concept, even minimal MCP descriptions suffice. When task language and tool name diverge, descriptions or hints are needed to bridge the gap.

### P4-C1: --since Flow Tracing -- The Task That Defeats MCP

**Task:** Trace the complete execution flow of `synthesis changelog --since 7d` from CLI to database.

| Condition | Calls | Method |
|-----------|:-----:|--------|
| Baseline | 6 | Grep + Read (direct code reading) |
| MCP (C4) | 16 | Glob x5 + Read x7 + Grep x4 (no MCP tools used) |
| MCP+Hint (C5) | 19 | `trace` + `which` + Glob x11 + Read x6 |
| MCP+Desc (C6) | 16 | `search` + Read x13 + Grep + Glob |

Every MCP condition performed worse than baseline on this task. The MCP condition (C4) was 167% worse; MCP + Hint (C5) was 217% worse. This is the single most important negative result in the benchmark.

The root cause: multi-hop execution tracing is inherently sequential. The agent must read file A to discover it calls file B, read file B to discover it calls file C, and so on. MCP tools like `trace` can show the high-level chain, but the agent still needs to read each file to understand parameter threading, type conversions, and control flow branching at every hop. The `trace` tool returned a graph; the agent needed the flow.

Moreover, having MCP tools available appears to have increased deliberation overhead. In baseline, the agent simply grepped for `--since` and followed the chain. With MCP tools present, the agent spent extra calls deciding which tool to use, trying MCP tools that returned incomplete results, and then falling back to file reads.

**Lesson:** MCP tools do not help with sequential code comprehension tasks. The integration mechanism cannot change the inherent structure of the problem. More concerning: the presence of unused MCP tools can increase decision overhead, degrading performance on tasks where standard tools are optimal.

### E1: ROI Metrics -- When Context Knowledge Eliminates Tools Entirely

**Task:** List Synthesis's validated performance metrics (files/sec, search latency, validation date).

| Condition | Calls | Method |
|-----------|:-----:|--------|
| Baseline | 6 | Grep + Read (search through docs) |
| MCP (C4) | 0 | Answered from CLAUDE.md context alone |
| MCP+Hint (C5) | 1 | Read x1 (verify one detail from context) |
| MCP+Desc (C6) | 4 | `search` x2 + Read x2 |

The MCP condition achieved 0 tool calls -- a result no MCP tool can beat. The answer was already in the agent's system prompt via CLAUDE.md. This is the most efficient possible outcome.

The improved descriptions in C6 *degraded* performance: the instruction "Use `search` FIRST for finding relevant files" overrode the agent's implicit knowledge from context, causing it to search for information it already had. The system prompt hint (C5) was more restrained, allowing the agent to trust its context with only one verification read.

**Lesson:** The best integration mechanism is pre-loaded context. Neither MCP tools nor CLI guide skills should override an agent's ability to answer from knowledge it already possesses. Directive description language ("Use FIRST") can suppress this capability.

### P4-B1: Flyway Migrations -- A Simple Task Destroyed by Overdirection

**Task:** List all Flyway migration files and explain any version gaps.

| Condition | Calls | Method |
|-----------|:-----:|--------|
| Baseline | 8 | Multiple Glob/Read/Grep calls |
| MCP (C4) | 1 | Glob for `V*.sql` (optimal) |
| MCP+Hint (C5) | 2 | Glob x2 |
| MCP+Desc (C6) | 18 | Read x15 + Glob + Bash + Task subagent |

Condition 6 (improved descriptions) caused an 18x regression from the 1-call optimum in C4. The agent, prompted by "Use `search` FIRST," became uncertain about whether to use MCP search or direct file listing. It escalated by spawning a Task subagent and reading 15 files individually. This is the starkest example of how description language can cause pathological behavior on simple tasks.

The MCP condition (C4) achieved the best result across all conditions with a single `Glob V*.sql` -- knowing from CLAUDE.md that V7 was "intentionally reserved." No MCP tool was needed because pattern-matched file discovery combined with context knowledge is the optimal strategy for this class of task.

**Lesson:** Not every task benefits from tool integration. Overly broad directives ("Use FIRST," "Use INSTEAD OF") can transform a 1-call task into an 18-call task. Tool descriptions must include scope guards.

---

## 5. Failure Mode Analysis

### Failure Mode 1: CLI Guide Skills Add Decision Overhead Without Backing Tools

The CLI condition (9.9 avg, +11% vs baseline) is the worst-performing condition. The mechanism: CLI guide skills inject detailed documentation about commands like `synthesis search --workspace /path --format json` into the agent's context. The agent reads this documentation, understands that a specialized search tool exists, but must compose a Bash command to invoke it. This introduces three costs:

1. **Cognitive overhead:** The agent must parse and evaluate 15 CLI guide skill documents to decide which command applies, competing with its existing knowledge of Grep/Glob/Read.
2. **Composition overhead:** Translating documentation into a correct Bash invocation requires constructing the right flags, paths, and argument ordering -- an error-prone process.
3. **No feedback loop:** Unlike MCP tools (which return structured JSON), CLI output must be parsed from stdout, often with formatting artifacts.

The result: the agent frequently *considered* using Synthesis CLI commands (incurring decision time) but *chose* Grep/Read anyway (achieving no benefit). The guide skills consumed context tokens without producing action.

### Failure Mode 2: MCP Description Quality Is a Double-Edged Sword

The three MCP conditions reveal a precise spectrum of description effectiveness:

| Approach | Avg calls | Effect |
|----------|:---------:|--------|
| Original descriptions (C4) | 5.8 | Agent selects MCP only when tool name matches task |
| One-line hint (C5) | 5.3 | Agent selects MCP more often; sometimes additive, sometimes substitutive |
| "Use FIRST / Use INSTEAD OF" descriptions (C6) | 7.6 | Agent over-invokes `search`; context knowledge suppressed |

The "Use INSTEAD OF Grep" language in C6 worked exactly as intended for `relate` and `code-graph`, which are structurally different from standard tools. But the same language applied to `search` -- a tool that overlaps significantly with Grep -- caused over-invocation. The agent invoked `search` even when CLAUDE.md already contained the answer (E1: 4 calls vs C4's 0 calls; P4-B1: 18 calls vs C4's 1 call).

The critical distinction: **comparative directives work for tools with structural advantages** (graph analysis, relationship mapping). They **backfire for tools that overlap with standard capabilities** (text search, file discovery).

### Failure Mode 3: The Additive MCP Pattern

Across all MCP conditions, a recurring pattern emerges: the agent invokes an MCP tool, receives a result, and then runs Grep/Read to verify or extend it. This is rational behavior -- MCP tools are unfamiliar and the agent minimizes risk by cross-checking with trusted tools. But it means MCP calls frequently *add to* rather than *replace* standard tool calls.

In Condition 5 (MCP + Hint):
- P5-R1: `search` + Grep x4 + Read = 6 calls (C4 used Grep x2 = 2 calls)
- P4-C1: `trace` + `which` + Glob x11 + Read x6 = 19 calls (C4: 16 calls)
- B3: `search` x2 + Read x2 = 4 calls (C4: 1 MCP + 2 other = 3 calls)

The hint increased MCP utilization from 0.56 to 1.44 calls per task (2.6x) but only decreased total calls from 5.8 to 5.3 (-8%). Most of the new MCP calls were additive rather than substitutive.

---

## 6. The Central Finding: Why a One-Line Hint Beats Both Alternatives

The one-line system prompt hint in Condition 5 achieved the best overall result (5.3 avg, -40% vs baseline) despite being the simplest intervention. The reason is architectural:

1. **It is additive, not overriding.** The hint suggests preferences ("Prefer `search` over Grep") without forbidding standard tools. The agent retains its cost model and can use context knowledge (E1: 1 call), direct file matching (P4-B1: 2 calls), or MCP tools (P5-R2: 5 calls) as appropriate.

2. **It is concise enough to avoid decision overhead.** One line introduces five tool-to-task mappings. By contrast, 15 CLI guide skills or 41 rewritten tool descriptions flood the agent's decision space, increasing deliberation without proportional benefit.

3. **It operates at the right abstraction level.** The hint maps task categories to tools (`"callers/dependents" -> relate`, `"architecture" -> code-graph`) rather than dictating tool usage for every possible query. This lets the agent interpolate for novel tasks.

The improved descriptions (C6) failed because they operated at the wrong level -- they told the agent what to do for every invocation of a specific tool, without accounting for task context. "Use `search` FIRST" is correct for unfamiliar codebase navigation but incorrect for factual queries answerable from context.

---

## 7. Correctness: MCP Does Not Sacrifice Quality

The MCP condition (C4) scored 10.8/12 on the multi-axis correctness rubric, the only condition with systematic correctness data in this phase. Key observations:

- **Structural accuracy was high** (2.8/3.0 avg): MCP tools returned correct data when invoked.
- **Currency was also high** (2.8/3.0 avg): The Synthesis index was current, so MCP results reflected the actual codebase state.
- **One hallucination** (E1: invented "Feb 19" date instead of the actual Feb 17) occurred in the 0-call context-only answer, not in any MCP-sourced answer.
- **One misinterpretation** (B3: Maven dependencies vs workspace cross-repo tracking) was caused by task prompt ambiguity, not tool failure.

The efficiency gains from MCP do not come at the cost of correctness. The agent produces answers of equal or higher quality in fewer steps.

---

## 8. Verdict

**MCP tools are the superior integration mechanism.** They reduce agent work by 35-40% compared to baseline and outperform every other condition tested. CLI guide skills are counterproductive -- they increase tool calls by 11% versus having no integration at all.

However, the mechanism matters less than the meta-signal. The single most effective intervention was a one-line system prompt hint -- not the tools themselves, not the tool descriptions, and certainly not CLI documentation. The hint gave the agent a minimal decision framework: which MCP tool to prefer for which class of question. This nudge, combined with the agent's existing capability to fall back to standard tools when appropriate, produced the optimal balance.

**For tooling teams building MCP integrations:**

1. **Expose tools via MCP, not CLI documentation.** The indirection cost of CLI guide skills (read docs, compose command, parse stdout) outweighs their informational value. MCP tools are directly invocable and return structured data.

2. **Add one decision-heuristic line to the system prompt.** Map 3-5 tool names to task categories. This single line delivered more efficiency gain than rewriting all 41 tool descriptions.

3. **Do not use "Use FIRST" or "Use ALWAYS" in tool descriptions.** These suppress the agent's ability to answer from pre-loaded context, which is the cheapest possible path. Use "Use INSTEAD OF [specific tool] when [specific condition]" -- and only for tools with genuine structural advantages over standard alternatives.

4. **Accept that some tasks will not benefit from MCP.** Sequential code tracing, simple pattern matching, and factual recall from context are all resistant to MCP optimization. Overfitting tool descriptions to these cases causes regressions.

5. **Monitor the additive MCP pattern.** Agents frequently invoke MCP tools and then verify results with standard tools, negating the efficiency gain. If your tool returns comprehensive, trustworthy results, the description should explicitly state this: "Returns ALL usages -- no additional Grep verification needed."

---

## 9. Recommendations by Role

| Role | Action |
|------|--------|
| **AI Product Managers** | Invest in MCP tool integrations over documentation-based approaches. Budget for system prompt engineering -- one line of decision heuristic outperforms weeks of documentation work. |
| **Developer Tooling Teams** | Keep MCP tool descriptions factual (what the tool does), and place decision heuristics in the system prompt (when to use it). Resist the temptation to make descriptions directive -- "Use FIRST" caused the worst single-task regression in the benchmark (P4-B1: 18x). |
| **Software Architects** | MCP tools compress architectural queries (dependency graphs, impact analysis, relationship mapping) by 70-91%. They do not compress sequential code comprehension tasks. Design MCP tools for structural queries, not linear traces. |
| **Benchmark Designers** | Include tasks where MCP has no advantage (pattern matching, context recall) alongside tasks where MCP has structural advantage (graph queries, cross-file relationships). This prevents overfitting to the tool's strengths and reveals failure modes. |

---

## 10. Limitations

- **Sample size:** n=1 per condition per task. Results are directional, not statistically significant. Three replicates per condition are required for confidence intervals.
- **Single model:** All conditions used claude-sonnet-4-6. Behavior may differ across model families or versions.
- **Self-referential benchmark:** Synthesis was used to benchmark Synthesis. While this is valid dogfooding, external codebases would strengthen generalizability.
- **Tool call count as proxy:** We measured efficiency via total tool calls, not wall-clock time or token cost. These metrics correlate but are not identical.
- **Ground truth drift:** 3 of 9 ground truths had drifted by the time of the benchmark run, complicating correctness scoring.

---

## Appendix: Raw Data Summary

**Condition 4 (MCP):** 5.8 avg total calls, 0.56 MCP calls/task, 4/9 tasks used any MCP tool, 10.8/12 correctness.

**Condition 5 (MCP + Hint):** 5.3 avg total calls, 1.44 MCP calls/task, 6/9 tasks used any MCP tool. Hint text: *"Synthesis MCP tools are available. Prefer `search` over Grep for discovery, `relate` over Grep for callers/dependents, `code-graph` for architecture, `trace` for execution flow, `impact` for change analysis."*

**Condition 6 (MCP + Descriptions):** 7.6 avg total calls, 1.78 MCP calls/task, 8/9 tasks used any MCP tool. Description changes: `search` ("Use this FIRST"), `relate` ("Use INSTEAD OF Grep"), `code-graph` ("Use FIRST for architecture"), `trace` ("Use INSTEAD OF reading files"), `impact` ("Use INSTEAD OF manual Grep-for-usages").

**CLI condition:** 9.9 avg total calls, 0/9 tasks used MCP (none available), 15 CLI guide skill files in context.

---

*This report was generated from benchmark data collected February 25-26, 2026, as part of Synthesis issue #270. The benchmark design, session guide, and raw results are available in the Synthesis repository under `/benchmark/`.*
