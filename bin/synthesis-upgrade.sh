#!/usr/bin/env bash
# synthesis-upgrade.sh — build, install, restart services, export skills
# Works on local dev machine and EC2 (Mímir/Klaw) — auto-detects environment.
#
# Usage:
#   synthesis-upgrade.sh                        # build from current HEAD (local only)
#   synthesis-upgrade.sh synthesis-1.21.0       # checkout specific tag first (local only)
#   synthesis-upgrade.sh --skip-build           # install from existing target/ JARs
set -euo pipefail

SYNTH_SRC="/src/exoreaction/Synthesis"
SKIP_BUILD=false
TAG=""

for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=true ;;
    *)            TAG="$arg" ;;
  esac
done

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; BOLD='\033[1m'; NC='\033[0m'
step() { printf "\n${BOLD}==> %s${NC}\n" "$*"; }
ok()   { printf "  ${GREEN}✓${NC}  %s\n" "$*"; }
warn() { printf "  ${YELLOW}!${NC}  %s\n" "$*"; }
fail() { printf "  ${RED}✗${NC}  %s\n" "$*" >&2; exit 1; }

export PATH="$HOME/bin:$HOME/.synthesis/bin:$PATH"

# Detect environment: EC2 has no source tree, uses sudo systemctl (system units)
HAS_SOURCE=false
[ -d "$SYNTH_SRC/.git" ] && HAS_SOURCE=true

# Use sudo systemctl (system units) if synthesis services live in /etc/systemd/system/
# Otherwise use --user (local dev machine with user units in ~/.config/systemd/user/)
if ls /etc/systemd/system/synthesis-*.service >/dev/null 2>&1; then
  SYSTEMCTL="sudo systemctl"
else
  SYSTEMCTL="systemctl --user"
fi

# 1. Git (local only)
if [ "$HAS_SOURCE" = true ] && [ "$SKIP_BUILD" = false ]; then
  step "1/5  Git"
  cd "$SYNTH_SRC"
  git fetch --all --tags --quiet
  if [ -n "$TAG" ]; then
    git checkout "$TAG" || fail "Could not checkout $TAG"
    ok "Checked out $TAG"
  else
    git pull --ff-only --quiet && ok "Pulled latest ($(git describe --tags --always 2>/dev/null || git rev-parse --short HEAD))" \
      || ok "Already up to date"
  fi
else
  step "1/5  Git"
  warn "No source tree at $SYNTH_SRC — skipping git step"
  SKIP_BUILD=true
fi

# 2. Build + install
if [ "$SKIP_BUILD" = true ]; then
  step "2/5  Install (skip build)"
  # On EC2: just update the current.jar symlink to the newest JAR in lib/
  NEWEST=$(ls -1t ~/.synthesis/lib/synthesis-1.*.jar 2>/dev/null | head -1)
  if [ -n "$NEWEST" ]; then
    ln -sf "$NEWEST" ~/.synthesis/lib/current.jar
    ok "current.jar → $(basename $NEWEST)"
  else
    # Try synthesis update if available
    synthesis update --skip-build --skip-docs --skip-visuals 2>/dev/null \
      && ok "synthesis update done" \
      || warn "No JARs found and synthesis update unavailable — JAR must be pushed manually"
  fi
else
  step "2/5  Build + install"
  synthesis update --skip-docs --skip-visuals -d "$SYNTH_SRC" \
    || fail "synthesis update failed"
fi
ok "$(synthesis --version 2>/dev/null || echo 'version unknown')"

# 3. Restart MCP services (discover all synthesis-mcp-* units)
step "3/5  Restart MCP services"
MCP_UNITS=$($SYSTEMCTL list-unit-files 'synthesis-mcp*' -t service --no-pager --no-legend 2>/dev/null | awk '{print $1}' || true)
if [ -n "$MCP_UNITS" ]; then
  $SYSTEMCTL restart $MCP_UNITS
  sleep 2
  for unit in $MCP_UNITS; do
    $SYSTEMCTL is-active "$unit" >/dev/null 2>&1 \
      && ok "$unit" \
      || warn "$unit not running after restart"
  done
else
  warn "No synthesis-mcp-* units found — skipped"
fi

# 4. Restart watchers (local only — EC2 has no watch daemons)
step "4/5  Restart watchers"
WATCH_UNITS=$($SYSTEMCTL list-unit-files 'synthesis-watch-*' -t service --no-pager --no-legend 2>/dev/null | awk '{print $1}' || true)
if [ -n "$WATCH_UNITS" ]; then
  $SYSTEMCTL restart $WATCH_UNITS
  sleep 2
  for unit in $WATCH_UNITS; do
    $SYSTEMCTL is-active "$unit" >/dev/null 2>&1 \
      && ok "$unit" \
      || warn "$unit not running after restart"
  done
else
  warn "No synthesis-watch-* units found — skipped"
fi

# 5. Export skills (local only — EC2 gets skills via morning push rsync)
step "5/5  Export skills"
if [ "$HAS_SOURCE" = true ]; then
  synthesis export-skills --overwrite -d "$SYNTH_SRC" 2>&1 \
    | grep -E 'Copied|Error|error' | head -5
  ok "Skills exported to ~/.claude/skills/"
else
  warn "No source tree — skills come via morning push rsync (skipped)"
fi

# Summary
printf "\n${BOLD}Done.${NC}"
[ "$HAS_SOURCE" = true ] && printf " Restart Claude Code to pick up updated skills."
printf "\n\n"
