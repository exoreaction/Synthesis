#!/usr/bin/env bash
# Emit one JSON line per task: the Synthesis KCP plan (units + token estimate) for
# an estate arm. Deterministic — no agent, no API key. See KCP-MANIFEST-BENCHMARK.md.
#
# Usage: run-kcp-plan-arm.sh <workspace-with-indexed-manifests> <tasks-file>
#
# The workspace must already be indexed (synthesis scan) so its knowledge.yaml
# manifests are persisted; this script only reads the plan.
set -euo pipefail

WORKSPACE="${1:?usage: run-kcp-plan-arm.sh <workspace> <tasks-file>}"
TASKS="${2:?usage: run-kcp-plan-arm.sh <workspace> <tasks-file>}"

while IFS= read -r task; do
  [ -z "$task" ] && continue
  [[ "$task" =~ ^# ]] && continue   # skip comment lines
  # --format json emits {task, units:[...], skipped:[...], totalTokenEstimate}
  synthesis -d "$WORKSPACE" kcp plan "$task" --format json 2>/dev/null \
    | tr -d '\n' | sed 's/  */ /g'
  echo
done < "$TASKS"
