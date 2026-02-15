#!/usr/bin/env bash
#
# Synthesis Uninstaller
# Cleanly removes Synthesis installation.
#
# Usage:
#   ~/.synthesis/bin/uninstall.sh
#   ./bin/uninstall.sh
#   ./bin/uninstall.sh --yes         # Skip confirmation prompt
#   ./bin/uninstall.sh --keep-data   # Keep workspace .synthesis/ directories
#
# Copyright (c) 2026 eXOReaction AS. All rights reserved.

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
readonly SYNTHESIS_HOME="${SYNTHESIS_HOME:-$HOME/.synthesis}"

# ---------------------------------------------------------------------------
# Color Output
# ---------------------------------------------------------------------------
if [ -t 1 ] && [ -t 2 ]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BLUE='\033[0;34m'
    BOLD='\033[1m'
    DIM='\033[2m'
    NC='\033[0m'
else
    RED='' GREEN='' YELLOW='' BLUE='' BOLD='' DIM='' NC=''
fi

info()    { printf "${GREEN}[INFO]${NC}  %s\n" "$*"; }
warn()    { printf "${YELLOW}[WARN]${NC}  %s\n" "$*"; }
error()   { printf "${RED}[ERROR]${NC} %s\n" "$*" >&2; }
step()    { printf "${BLUE}==>${NC} ${BOLD}%s${NC}\n" "$*"; }
detail()  { printf "    %s\n" "$*"; }

# ---------------------------------------------------------------------------
# Parse Arguments
# ---------------------------------------------------------------------------
SKIP_CONFIRM=false
KEEP_DATA=false
REMOVE_SKILLS=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --yes|-y)
            SKIP_CONFIRM=true
            shift
            ;;
        --keep-data)
            KEEP_DATA=true
            shift
            ;;
        --remove-skills)
            REMOVE_SKILLS=true
            shift
            ;;
        --help|-h)
            echo "Synthesis Uninstaller"
            echo ""
            echo "Usage:"
            echo "  uninstall.sh [options]"
            echo ""
            echo "Options:"
            echo "  --yes, -y         Skip confirmation prompt"
            echo "  --keep-data       Keep workspace .synthesis/ directories"
            echo "  --remove-skills   Also remove Claude Code skills for Synthesis"
            echo "  -h, --help        Show this help"
            echo ""
            echo "Environment:"
            echo "  SYNTHESIS_HOME    Installation directory (default: ~/.synthesis)"
            exit 0
            ;;
        *)
            error "Unknown option: $1"
            exit 1
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Check Installation
# ---------------------------------------------------------------------------
if [ ! -d "$SYNTHESIS_HOME" ]; then
    error "Synthesis is not installed at $SYNTHESIS_HOME"
    detail "Nothing to uninstall."
    exit 0
fi

# ---------------------------------------------------------------------------
# Survey What Will Be Removed
# ---------------------------------------------------------------------------
printf "\n"
printf "${BOLD}Synthesis Uninstaller${NC}\n"
printf "\n"

step "The following will be removed:"

# Installation directory
if [ -d "$SYNTHESIS_HOME" ]; then
    DIR_SIZE=$(du -sh "$SYNTHESIS_HOME" 2>/dev/null | awk '{print $1}' || echo "?")
    JAR_COUNT=$(ls -1 "$SYNTHESIS_HOME/lib"/synthesis-*.jar 2>/dev/null | wc -l || echo 0)
    detail "${BOLD}$SYNTHESIS_HOME/${NC} ($DIR_SIZE)"
    detail "  bin/synthesis (launcher)"
    detail "  bin/synthesis-mcp-server (MCP server launcher)"
    detail "  bin/synthesis-lsp-server (LSP server launcher)"
    detail "  bin/update.sh (updater)"
    detail "  lib/ ($JAR_COUNT JAR files)"
    detail "  .metadata/ (installation data)"
    detail "  .installation.json (installation fingerprint)"
    if [ -d "$SYNTHESIS_HOME/docs" ]; then
        DOC_COUNT=$(find "$SYNTHESIS_HOME/docs" -name "*.md" 2>/dev/null | wc -l || echo 0)
        detail "  docs/ ($DOC_COUNT documentation files)"
    fi
fi

# Shell integration
SHELL_FILES_TO_CLEAN=()
MARKER="# Synthesis - AI operations partner"
for rc_file in "$HOME/.bashrc" "$HOME/.bash_profile" "$HOME/.zshrc" "$HOME/.zprofile" "$HOME/.profile"; do
    if [ -f "$rc_file" ] && grep -q "$MARKER" "$rc_file" 2>/dev/null; then
        SHELL_FILES_TO_CLEAN+=("$rc_file")
    fi
done

if [ ${#SHELL_FILES_TO_CLEAN[@]} -gt 0 ]; then
    printf "\n"
    detail "${BOLD}Shell integration:${NC}"
    for f in "${SHELL_FILES_TO_CLEAN[@]}"; do
        detail "  PATH entry in $f"
    done
fi

# Claude Code skills
SKILL_FILES=()
if [ -d "$HOME/.claude/skills" ]; then
    while IFS= read -r -d '' skill_file; do
        SKILL_FILES+=("$skill_file")
    done < <(find "$HOME/.claude/skills" -name "*synthesis*" -print0 2>/dev/null || true)
fi

if [ ${#SKILL_FILES[@]} -gt 0 ]; then
    printf "\n"
    if [ "$REMOVE_SKILLS" = true ]; then
        detail "${BOLD}Claude Code skills (will remove):${NC}"
    else
        detail "${BOLD}Claude Code skills (keeping, use --remove-skills to remove):${NC}"
    fi
    for f in "${SKILL_FILES[@]}"; do
        detail "  $f"
    done
fi

# Workspace data note
if [ "$KEEP_DATA" = true ]; then
    printf "\n"
    detail "${DIM}Workspace .synthesis/ directories will be kept (--keep-data)${NC}"
else
    printf "\n"
    detail "${YELLOW}Note:${NC} Workspace .synthesis/ directories (project indexes) are NOT removed."
    detail "  These live inside your project directories and contain search indexes."
    detail "  Remove them manually if desired: find ~ -name .synthesis -type d"
fi

# ---------------------------------------------------------------------------
# Confirmation
# ---------------------------------------------------------------------------
printf "\n"

if [ "$SKIP_CONFIRM" = false ]; then
    printf "${YELLOW}Are you sure you want to uninstall Synthesis? [y/N]${NC} "
    read -r response
    case "$response" in
        [yY]|[yY][eE][sS])
            ;;
        *)
            info "Uninstall cancelled."
            exit 0
            ;;
    esac
fi

printf "\n"

# ---------------------------------------------------------------------------
# Remove Installation Directory
# ---------------------------------------------------------------------------
step "Removing installation..."

if [ -d "$SYNTHESIS_HOME" ]; then
    rm -rf "$SYNTHESIS_HOME"
    info "Removed $SYNTHESIS_HOME"
fi

# ---------------------------------------------------------------------------
# Clean Shell Integration
# ---------------------------------------------------------------------------
step "Cleaning shell integration..."

for rc_file in "${SHELL_FILES_TO_CLEAN[@]}"; do
    if [ -f "$rc_file" ]; then
        # Remove the Synthesis block (marker line + PATH line)
        # Use a temp file to avoid issues with in-place sed differences (Linux vs macOS)
        TEMP_RC=$(mktemp)
        # Remove lines matching the marker and the PATH line that follows
        awk -v marker="$MARKER" '
            $0 == marker { skip=1; next }
            skip && /export PATH.*\.synthesis/ { skip=0; next }
            skip && /^$/ { skip=0; next }
            { skip=0; print }
        ' "$rc_file" > "$TEMP_RC"

        # Also remove any trailing blank lines we may have left
        # Only update if content actually changed
        if ! diff -q "$rc_file" "$TEMP_RC" >/dev/null 2>&1; then
            cp "$TEMP_RC" "$rc_file"
            info "Cleaned $rc_file"
        fi
        rm -f "$TEMP_RC"
    fi
done

if [ ${#SHELL_FILES_TO_CLEAN[@]} -eq 0 ]; then
    detail "No shell integration to clean"
fi

# ---------------------------------------------------------------------------
# Remove Claude Code Skills (if requested)
# ---------------------------------------------------------------------------
if [ "$REMOVE_SKILLS" = true ] && [ ${#SKILL_FILES[@]} -gt 0 ]; then
    step "Removing Claude Code skills..."
    for skill_file in "${SKILL_FILES[@]}"; do
        if [ -f "$skill_file" ]; then
            rm -f "$skill_file"
            info "Removed $skill_file"
        fi
    done
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
printf "\n"
printf "${GREEN}${BOLD}Synthesis has been uninstalled.${NC}\n"
printf "\n"
printf "  ${DIM}Open a new terminal for PATH changes to take effect.${NC}\n"

if [ ${#SKILL_FILES[@]} -gt 0 ] && [ "$REMOVE_SKILLS" = false ]; then
    printf "  ${DIM}Claude Code skills were kept. Use --remove-skills to remove them.${NC}\n"
fi

printf "\n"
printf "  To reinstall:\n"
printf "    curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash\n"
printf "\n"
