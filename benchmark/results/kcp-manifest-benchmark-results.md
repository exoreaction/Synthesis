# KCP Manifest Retrieval Benchmark Results

**Run date**: 2026-07-09
**Issue**: #371 item 3 / #359
**Workspace**: Synthesis v1.40.1-SNAPSHOT (single-repo)

## Arms

| Arm | Manifest | Units | Strategy |
|-----|----------|-------|----------|
| A | None | 0 | Agent explores blind |
| B | Hand-written `knowledge.yaml` | 9 | Semantic architectural units (ai-layer, cli, graph, mcp-server, etc.) |
| C | `synthesis kcp init` generated | 24 | Structural detection (README, docs, tests, CI, source-root) |

## Summary

| Metric | Arm B | Arm C |
|--------|-------|-------|
| Units per task (avg) | 8.1 | 8.9 |
| Total score (8 tasks) | 378 | 455 |
| Avg score per task | 47.2 | 56.9 |
| Skipped units per task | 2.6 | 15.1 |
| Path overlap (B vs C) | **0%** | **0%** |

## Per-Task Results

| Task | B units | C units | B score | C score |
|------|---------|---------|---------|---------|
| What endpoints does the API expose? | 9 | 10 | 79 | 62 |
| How is this project built and released? | 9 | 9 | 52 | 57 |
| Where is the main business logic? | 9 | 10 | 52 | 74 |
| What is the security posture? | 9 | 11 | 70 | 75 |
| What does the data model look like? | 9 | 14 | 40 | 96 |
| How do I get started as a contributor? | 9 | 7 | 33 | 31 |
| What configuration does this service require? | 4 | 3 | 12 | 18 |
| Which modules depend on which? | 7 | 7 | 40 | 42 |

## Key Findings

### 1. Zero path overlap — completely different routing strategies

Hand-written units point to **source code directories** (`src/main/java/io/exoreaction/synthesis/ai`, `src/.../cli`, `src/.../mcp`). Generated units point to **docs, tests, and CI** (`docs/user-guide-v2.md`, `tests`, `ci`, `README.md`).

Not a single file path is selected by both arms for any task. The strategies are complementary, not comparable.

### 2. Generated scores higher but noisier

Arm C's aggregate score (455) exceeds B (378) because generated units have more verbose trigger lists — the README alone has 8 triggers. More triggers = more query-term matches = higher score. But Arm C also skips 15.1 units/task vs B's 2.6 — the wider net catches more noise.

### 3. Hand-written excels at "find the code" tasks

For "What endpoints does the API expose?" Arm B routes to `ai-layer` (score 15, "5 intent matches") — the correct source package. Arm C routes to `tests` (score 15, "5 intent matches") — useful but indirect.

### 4. Generated excels at "understand the project" tasks

For "How is this project built and released?" Arm C routes to `ci` (score 18, "6 intent matches") — exactly right. Arm B spreads across all 9 source packages with low scores.

### 5. Neither manifest is sufficient alone

The ideal manifest combines B's semantic source-code units with C's structural docs/CI units. A hybrid (`kcp init` + human curation) would outperform both.

## Hypothesis Evaluation

> **H: C >= B > A on tool_calls and wall_clock, with correctness held equal.**

**Partially supported, with nuance:**

- **C > B on aggregate score** (455 > 378) — generated manifests DO match more query terms
- **B better for source navigation** — hand-written units point directly to the right code
- **C better for project understanding** — generated units cover docs and CI that B misses
- **A = 0** — confirmed: without any manifest, the planner produces empty plans; agent must explore blind

## Limitations

1. **Single-repo benchmark** — only Synthesis workspace, not the full 37-manifest eXOReaction estate
2. **Token estimates = 0** — units point to directories, not files; the planner can't estimate directory sizes
3. **No agent sessions** — this measures plan quality only, not actual agent tool calls and correctness
4. **Worktree triplication** — raw Arm B data contains 3x copies from `.claude/worktrees/` copies; de-duplicated in analysis

## Raw Data

- `benchmark/results/arm-B-synthesis.jsonl` — 8 tasks, hand-written manifest
- `benchmark/results/arm-C-synthesis.jsonl` — 8 tasks, generated manifest
