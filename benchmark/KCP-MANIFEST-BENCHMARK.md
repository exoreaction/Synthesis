# KCP Manifest Retrieval Benchmark (Phase 6, issue #359)

**Question:** does a `knowledge.yaml` manifest reduce the work an agent spends
finding the right files — and do *Synthesis-generated* manifests plan at least
as well as *hand-written* ones?

This mirrors kcp-agent's own headline result (**53–80% fewer agent tool calls vs
unguided exploration** across five frameworks) and Synthesis's earlier Fixed-CLI
benchmark (**−23.8%**). It is the marketing proof point for the epic: *generated
context beats artisanal context*.

> ⚠️ **Status: harness + methodology, awaiting a real run.** The arms below need the
> live eXOReaction estate (the ~110 hand-written manifests), a driving agent, and an
> API key — none of which run in CI or the dev sandbox. The deterministic planner
> that arms B and C share (`KcpPlanner`) is fully implemented, tested, and exercised
> by `kcp-conformance.yml` (kcp-agent `plan` + `replay` over a generated manifest).
> Fill in the numbers from a real session, the way Phases 5/6 were recorded under
> `benchmark/results/`.

## Arms

| Arm | Context provided to the agent | Manifest source |
|-----|-------------------------------|-----------------|
| **A** | none — unguided exploration (baseline) | — |
| **B** | `knowledge.yaml` in each repo | hand-written (the ~110 existing manifests) |
| **C** | `knowledge.yaml` in each repo | `synthesis kcp init --batch` generated |

All three answer the **same task set** over the **same repos**. Only the manifest
(and its absence in A) varies.

## Metrics (per task, then aggregated)

- **tool_calls** — file reads / searches the agent issues before answering (primary)
- **wall_clock_s** — end-to-end latency
- **correctness** — 0/1 against a rubric answer (must not regress)
- **completeness** — fraction of expected facts present (catches "fast but shallow")
- **plan_precision** — of the units the planner selected, fraction actually read to
  answer (measures manifest quality directly, arms B/C only)

**Hypothesis:** C ≥ B > A on tool_calls and wall_clock, with correctness held equal.
If C ≥ B on plan_precision, generated manifests match or beat hand-written ones.

## Running it

```bash
# 1. Generate arm C manifests over a scratch copy of the estate (never mutate originals)
cp -r /src/exoreaction /tmp/estate-C
synthesis kcp init --batch /tmp/estate-C

# 2. For each arm, plan the task set and record the load plan + agent trace.
#    The deterministic planner (arms B/C share it) is scriptable without an agent:
./benchmark/run-kcp-plan-arm.sh /tmp/estate-C benchmark/kcp-tasks.txt > results/arm-C.jsonl
./benchmark/run-kcp-plan-arm.sh /src/exoreaction  benchmark/kcp-tasks.txt > results/arm-B.jsonl
#    Arm A is the existing unguided-exploration harness (no manifest).

# 3. Diff plan_precision / tool_calls across arms; write RESULTS.md under
#    benchmark/results/kcp-manifest/ following the existing report format.
```

## Task set

`benchmark/kcp-tasks.txt` — one task per line, chosen to span the estate's units
(API surface, auth, build/release, data model, security posture, …). Keep it stable
across arms and across re-runs so numbers are comparable over time.

## Why the planner is the honest core

Arms B and C both route through Synthesis's `KcpPlanner` (RFC-0007 scoring: trigger 5,
intent 3, id/path 1; expired/superseded units skipped; token-budget aware). Because
it is deterministic and model-free, the *plan* half of the benchmark is reproducible
without an agent or API key — only the *answer* half needs the driving model. That
keeps the manifest-quality signal (plan_precision) cheap and stable, and isolates it
from model variance.
