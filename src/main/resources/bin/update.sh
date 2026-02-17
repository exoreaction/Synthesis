#!/usr/bin/env bash
#
# Synthesis Updater
# Self-updating distribution script for Synthesis.
#
# Usage:
#   synthesis-update                   # Update to latest version
#   synthesis-update --check           # Check for updates only
#   synthesis-update --version 1.0.*   # Update to specific version pattern
#   synthesis-update --force           # Force update even if current
#   synthesis-update --rollback        # Rollback to previous version
#   synthesis-update --self-update     # Update this script itself
#   synthesis-update --full            # Comprehensive update (JARs + scripts + docs)
#   synthesis-update --health          # Check installation health
#   synthesis-update --install-component NAME  # Install specific component
#
# Copyright (c) 2026 eXOReaction AS. All rights reserved.

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
readonly SYNTHESIS_HOME="${SYNTHESIS_HOME:-$HOME/.synthesis}"
readonly GITHUB_REPO="exoreaction/Synthesis"
readonly GITHUB_URL="https://github.com/${GITHUB_REPO}"
readonly GITHUB_RAW="https://raw.githubusercontent.com/${GITHUB_REPO}/main"
readonly CANTARA_SNAPSHOTS="https://mvnrepo.cantara.no/content/repositories/snapshots"
readonly CANTARA_RELEASES="https://mvnrepo.cantara.no/content/repositories/releases"
readonly GROUP_PATH="io/exoreaction"
readonly ARTIFACT_ID="synthesis"
readonly LIB_DIR="$SYNTHESIS_HOME/lib"
readonly META_DIR="$SYNTHESIS_HOME/.metadata"

# ---------------------------------------------------------------------------
# Color Output
# ---------------------------------------------------------------------------
if [ -t 1 ] && [ -t 2 ]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BLUE='\033[0;34m'
    CYAN='\033[0;36m'
    BOLD='\033[1m'
    DIM='\033[2m'
    NC='\033[0m'
else
    RED='' GREEN='' YELLOW='' BLUE='' CYAN='' BOLD='' DIM='' NC=''
fi

info()    { [ "${QUIET:-false}" = true ] || printf "${GREEN}[INFO]${NC}  %s\n" "$*"; }
warn()    { [ "${QUIET:-false}" = true ] || printf "${YELLOW}[WARN]${NC}  %s\n" "$*"; }
error()   { printf "${RED}[ERROR]${NC} %s\n" "$*" >&2; }
step()    { [ "${QUIET:-false}" = true ] || printf "${BLUE}==>${NC} ${BOLD}%s${NC}\n" "$*"; }
detail()  { [ "${QUIET:-false}" = true ] || printf "    %s\n" "$*"; }

# ---------------------------------------------------------------------------
# Helper Functions
# ---------------------------------------------------------------------------
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

download_file() {
    local url="$1"
    local output="$2"
    if command_exists curl; then
        curl -fsSL -o "$output" "$url"
    elif command_exists wget; then
        wget -q -O "$output" "$url"
    else
        error "Neither curl nor wget available."
        return 1
    fi
}

url_exists() {
    local url="$1"
    if command_exists curl; then
        local code
        code=$(curl -fsSL -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")
        [ "$code" = "200" ]
    elif command_exists wget; then
        wget -q --spider "$url" 2>/dev/null
    else
        return 1
    fi
}

# Compare version strings. Returns 0 if $1 > $2, 1 if equal, 2 if $1 < $2.
# Handles SNAPSHOT versions: treats them as pre-release (lower than release).
version_compare() {
    local v1="$1"
    local v2="$2"

    # Strip SNAPSHOT suffix for numeric comparison
    local v1_base="${v1%-SNAPSHOT}"
    local v2_base="${v2%-SNAPSHOT}"
    local v1_snap=false
    local v2_snap=false
    [[ "$v1" == *-SNAPSHOT ]] && v1_snap=true
    [[ "$v2" == *-SNAPSHOT ]] && v2_snap=true

    # Split by dots
    IFS='.' read -ra V1_PARTS <<< "$v1_base"
    IFS='.' read -ra V2_PARTS <<< "$v2_base"

    local max_len=${#V1_PARTS[@]}
    [ ${#V2_PARTS[@]} -gt $max_len ] && max_len=${#V2_PARTS[@]}

    for ((i = 0; i < max_len; i++)); do
        local p1="${V1_PARTS[$i]:-0}"
        local p2="${V2_PARTS[$i]:-0}"

        if [ "$p1" -gt "$p2" ] 2>/dev/null; then
            return 0  # v1 > v2
        elif [ "$p1" -lt "$p2" ] 2>/dev/null; then
            return 2  # v1 < v2
        fi
    done

    # Same base version - SNAPSHOT is lower than release
    if [ "$v1_snap" = true ] && [ "$v2_snap" = false ]; then
        return 2  # v1 < v2 (snapshot < release)
    elif [ "$v1_snap" = false ] && [ "$v2_snap" = true ]; then
        return 0  # v1 > v2 (release > snapshot)
    fi

    return 1  # equal
}

# Check if version matches pattern (e.g., 1.0.* matches 1.0.0, 1.0.1)
version_matches() {
    local version="$1"
    local pattern="$2"

    # Convert glob pattern to regex
    local regex
    regex=$(echo "$pattern" | sed 's/\./\\./g; s/\*/[0-9A-Za-z._-]*/g')
    echo "$version" | grep -qE "^${regex}$"
}

# Get current installed version
get_current_version() {
    if [ -f "$META_DIR/version" ]; then
        cat "$META_DIR/version"
    elif [ -L "$LIB_DIR/current.jar" ]; then
        local target
        target=$(readlink "$LIB_DIR/current.jar" 2>/dev/null || true)
        echo "$target" | sed 's/^synthesis-//;s/\.jar$//'
    else
        echo "unknown"
    fi
}

# Get list of installed versions (from lib/ directory)
get_installed_versions() {
    ls -1 "$LIB_DIR"/synthesis-*.jar 2>/dev/null \
        | sed 's|.*/synthesis-||;s|\.jar$||' \
        | grep -v "^$" \
        | sort -V
}

# Get the previous version (for rollback)
get_previous_version() {
    local current
    current=$(get_current_version)
    get_installed_versions | grep -v "^${current}$" | tail -1 || true
}

# Check if Synthesis process is running
check_running() {
    if pgrep -f "synthesis.*\.jar" >/dev/null 2>&1; then
        return 0  # running
    fi
    return 1  # not running
}

# Compute SHA256 checksum
compute_sha() {
    local file="$1"
    if command_exists sha256sum; then
        sha256sum "$file" | awk '{print $1}'
    elif command_exists shasum; then
        shasum -a 256 "$file" | awk '{print $1}'
    else
        echo "no-sha-tool"
    fi
}

# ---------------------------------------------------------------------------
# Update Strategies
# ---------------------------------------------------------------------------

# Strategy 1: Check GitHub releases for latest version
check_github_releases() {
    local latest_tag=""
    local latest_url=""

    if command_exists curl; then
        local api_response
        api_response=$(curl -fsSL "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" 2>/dev/null || echo "{}")

        latest_tag=$(echo "$api_response" | grep -o '"tag_name": *"[^"]*"' | head -1 | sed 's/"tag_name": *"//;s/"$//' || true)
        latest_url=$(echo "$api_response" | grep -o '"browser_download_url": *"[^"]*synthesis[^"]*\.jar"' | head -1 | sed 's/"browser_download_url": *"//;s/"$//' || true)
    fi

    if [ -n "$latest_tag" ]; then
        echo "release:${latest_tag}:${latest_url}"
    fi
}

# Strategy 2: Check Cantara Maven repository
check_cantara_repo() {
    # Check releases
    local meta_url="${CANTARA_RELEASES}/${GROUP_PATH}/${ARTIFACT_ID}/maven-metadata.xml"
    if url_exists "$meta_url" 2>/dev/null; then
        local latest
        latest=$(curl -fsSL "$meta_url" 2>/dev/null \
            | grep -o '<release>[^<]*</release>' | sed 's/<[^>]*>//g' || true)
        if [ -n "$latest" ]; then
            local jar_url="${CANTARA_RELEASES}/${GROUP_PATH}/${ARTIFACT_ID}/${latest}/synthesis-${latest}.jar"
            echo "cantara-release:${latest}:${jar_url}"
            return
        fi
    fi

    # Check snapshots
    meta_url="${CANTARA_SNAPSHOTS}/${GROUP_PATH}/${ARTIFACT_ID}/maven-metadata.xml"
    if url_exists "$meta_url" 2>/dev/null; then
        local latest
        latest=$(curl -fsSL "$meta_url" 2>/dev/null \
            | grep -o '<version>[^<]*</version>' | tail -1 | sed 's/<[^>]*>//g' || true)
        if [ -n "$latest" ]; then
            # For snapshots, resolve timestamped filename
            local snap_meta_url="${CANTARA_SNAPSHOTS}/${GROUP_PATH}/${ARTIFACT_ID}/${latest}/maven-metadata.xml"
            local jar_name="synthesis-${latest}.jar"

            if url_exists "$snap_meta_url" 2>/dev/null; then
                local ts bn base_ver
                ts=$(curl -fsSL "$snap_meta_url" 2>/dev/null | grep -o '<timestamp>[^<]*</timestamp>' | sed 's/<[^>]*>//g' || true)
                bn=$(curl -fsSL "$snap_meta_url" 2>/dev/null | grep -o '<buildNumber>[^<]*</buildNumber>' | sed 's/<[^>]*>//g' || true)
                if [ -n "$ts" ] && [ -n "$bn" ]; then
                    base_ver=$(echo "$latest" | sed 's/-SNAPSHOT//')
                    jar_name="synthesis-${base_ver}-${ts}-${bn}.jar"
                fi
            fi

            local jar_url="${CANTARA_SNAPSHOTS}/${GROUP_PATH}/${ARTIFACT_ID}/${latest}/${jar_name}"
            echo "cantara-snapshot:${latest}:${jar_url}"
        fi
    fi
}

# Strategy 3: Source build (git pull + mvn package)
update_from_source() {
    local source_dir=""

    # Check saved source directory
    if [ -f "$META_DIR/source-dir" ]; then
        source_dir=$(cat "$META_DIR/source-dir")
    fi

    # Auto-detect common locations
    if [ -z "$source_dir" ] || [ ! -d "$source_dir" ]; then
        for candidate in "$HOME/src/synthesis" "$HOME/src/exoreaction/synthesis" "$HOME/projects/synthesis"; do
            if [ -f "$candidate/pom.xml" ] && grep -q "synthesis" "$candidate/pom.xml" 2>/dev/null; then
                source_dir="$candidate"
                break
            fi
        done
    fi

    if [ -z "$source_dir" ] || [ ! -f "$source_dir/pom.xml" ]; then
        return 1
    fi

    detail "Source directory: $source_dir"

    # Pull latest changes
    if [ -d "$source_dir/.git" ] && command_exists git; then
        detail "Pulling latest changes..."
        (cd "$source_dir" && git pull --ff-only 2>&1) || {
            warn "Git pull failed (local changes?). Building current state..."
        }
    fi

    # Build
    if ! command_exists mvn; then
        error "Maven not found. Cannot build from source."
        return 1
    fi

    detail "Building from source (mvn package -DskipTests)..."
    if ! (cd "$source_dir" && mvn package -DskipTests -q 2>&1); then
        error "Maven build failed"
        return 1
    fi

    # Find built JAR
    local version
    version=$(grep -m1 '<version>' "$source_dir/pom.xml" | sed 's/.*<version>//;s/<\/version>.*//' | tr -d '[:space:]')
    local jar_name="synthesis-${version}.jar"

    if [ ! -f "$source_dir/target/$jar_name" ]; then
        error "Expected JAR not found: $source_dir/target/$jar_name"
        return 1
    fi

    # Install
    cp "$source_dir/target/$jar_name" "$LIB_DIR/$jar_name"
    (cd "$LIB_DIR" && ln -sf "$jar_name" current.jar)
    echo "$version" > "$META_DIR/version"
    echo "$source_dir" > "$META_DIR/source-dir"

    echo "source:${version}:${source_dir}"
}

# ---------------------------------------------------------------------------
# Parse Arguments
# ---------------------------------------------------------------------------
ACTION="update"
VERSION_PATTERN=""
FORCE=false
QUIET=false
FULL_UPDATE=false
SKIP_DOCS=false
SKIP_VISUALS=false
INSTALL_COMPONENT=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --check)
            ACTION="check"
            shift
            ;;
        --rollback)
            ACTION="rollback"
            shift
            ;;
        --version)
            VERSION_PATTERN="$2"
            shift 2
            ;;
        --force)
            FORCE=true
            shift
            ;;
        --quiet|-q)
            QUIET=true
            shift
            ;;
        --self-update)
            ACTION="self-update"
            shift
            ;;
        --full)
            FULL_UPDATE=true
            shift
            ;;
        --health)
            ACTION="health"
            shift
            ;;
        --install-component)
            ACTION="install-component"
            INSTALL_COMPONENT="$2"
            shift 2
            ;;
        --skip-docs)
            SKIP_DOCS=true
            shift
            ;;
        --skip-visuals)
            SKIP_VISUALS=true
            shift
            ;;
        --help|-h)
            echo "Synthesis Updater"
            echo ""
            echo "Usage:"
            echo "  update.sh [options]"
            echo ""
            echo "Actions:"
            echo "  (default)          Update JARs to latest version"
            echo "  --full             Comprehensive update (JARs + scripts + docs + assets)"
            echo "  --check            Check for updates without installing"
            echo "  --health           Check installation health"
            echo "  --install-component NAME  Install a specific component"
            echo "  --rollback         Rollback to previous version"
            echo "  --self-update      Update the update script itself"
            echo ""
            echo "Components:"
            echo "  synthesis-mcp-server    MCP server for AI agent integration"
            echo "  synthesis-lsp-server    LSP server for IDE integration"
            echo "  launcher-scripts        All launcher scripts"
            echo "  update-script           Update management script"
            echo "  documentation           User guides and docs"
            echo ""
            echo "Options:"
            echo "  --version PATTERN  Specific version or pattern (e.g., 1.0.*, 1.1.0)"
            echo "  --force            Force update even if current version matches"
            echo "  --skip-docs        Skip documentation (--full mode)"
            echo "  --skip-visuals     Skip visual assets (--full mode, saves ~270MB)"
            echo "  --quiet, -q        Minimal output (for background checks)"
            echo "  -h, --help         Show this help"
            echo ""
            echo "Environment:"
            echo "  SYNTHESIS_HOME     Installation directory (default: ~/.synthesis)"
            echo ""
            echo "Examples:"
            echo "  synthesis-update                    # Update JARs to latest"
            echo "  synthesis-update --full             # Update everything"
            echo "  synthesis-update --check            # Check only"
            echo "  synthesis-update --health           # Check installation"
            echo "  synthesis-update --install-component synthesis-mcp-server"
            echo "  synthesis-update --version '1.0.*'  # Specific pattern"
            echo "  synthesis-update --rollback         # Rollback"
            exit 0
            ;;
        *)
            error "Unknown option: $1"
            error "Run: update.sh --help"
            exit 1
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Sanity Checks
# ---------------------------------------------------------------------------
if [ ! -d "$SYNTHESIS_HOME" ]; then
    error "Synthesis not installed at $SYNTHESIS_HOME"
    detail "Run the installer first:"
    detail "  curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash"
    exit 1
fi

if [ ! -d "$LIB_DIR" ]; then
    error "Library directory missing: $LIB_DIR"
    exit 1
fi

mkdir -p "$META_DIR"

# ---------------------------------------------------------------------------
# Action: Self-Update
# ---------------------------------------------------------------------------
if [ "$ACTION" = "self-update" ]; then
    step "Updating update script..."
    TEMP_SCRIPT=$(mktemp)
    if download_file "${GITHUB_RAW}/bin/update.sh" "$TEMP_SCRIPT"; then
        cp "$TEMP_SCRIPT" "$SYNTHESIS_HOME/bin/update.sh"
        chmod +x "$SYNTHESIS_HOME/bin/update.sh"
        rm -f "$TEMP_SCRIPT"
        info "Update script updated successfully"
    else
        rm -f "$TEMP_SCRIPT"
        error "Failed to download update script"
        exit 1
    fi
    exit 0
fi

# ---------------------------------------------------------------------------
# Helper: Find source directory
# ---------------------------------------------------------------------------
find_source_dir() {
    local src_dir=""
    if [ -f "$META_DIR/source-dir" ]; then
        src_dir=$(cat "$META_DIR/source-dir")
    fi
    if [ -z "$src_dir" ] || [ ! -d "$src_dir" ]; then
        for candidate in "$HOME/src/synthesis" "$HOME/src/exoreaction/synthesis" "$HOME/projects/synthesis"; do
            if [ -f "$candidate/pom.xml" ] && grep -q "synthesis" "$candidate/pom.xml" 2>/dev/null; then
                src_dir="$candidate"
                break
            fi
        done
    fi
    echo "$src_dir"
}

# ---------------------------------------------------------------------------
# Action: Health Check
# ---------------------------------------------------------------------------
if [ "$ACTION" = "health" ]; then
    step "Checking installation health..."

    CURRENT_VERSION=$(get_current_version)
    echo ""
    printf "  %-20s %s\n" "Version:" "$CURRENT_VERSION"
    if [ -f "$META_DIR/install-date" ]; then
        printf "  %-20s %s\n" "Install date:" "$(cat "$META_DIR/install-date")"
    fi
    if [ -f "$META_DIR/source-dir" ]; then
        printf "  %-20s %s\n" "Source:" "$(cat "$META_DIR/source-dir")"
    fi
    echo ""

    ISSUES=0

    # Check CLI JAR
    if [ -f "$LIB_DIR/current.jar" ]; then
        printf "  ${GREEN}✓${NC} %-35s %s\n" "synthesis-cli" "($(readlink "$LIB_DIR/current.jar" 2>/dev/null || echo "current.jar"))"
    else
        printf "  ${RED}✗${NC} %-35s %s\n" "synthesis-cli" "MISSING (critical)"
        ISSUES=$((ISSUES + 1))
    fi

    # Check MCP server JAR
    if [ -f "$LIB_DIR/synthesis-mcp-server.jar" ]; then
        printf "  ${GREEN}✓${NC} %-35s %s\n" "synthesis-mcp-server" "(installed)"
    else
        printf "  ${YELLOW}○${NC} %-35s %s\n" "synthesis-mcp-server" "(not installed, available since 1.0.4)"
    fi

    # Check LSP server JAR
    if [ -f "$LIB_DIR/synthesis-lsp-server.jar" ]; then
        printf "  ${GREEN}✓${NC} %-35s %s\n" "synthesis-lsp-server" "(installed)"
    else
        printf "  ${YELLOW}○${NC} %-35s %s\n" "synthesis-lsp-server" "(not installed, available since 1.0.4)"
    fi

    echo ""

    # Check launcher scripts
    for script in synthesis synthesis-mcp-server synthesis-lsp-server; do
        if [ -f "$SYNTHESIS_HOME/bin/$script" ]; then
            if [ -x "$SYNTHESIS_HOME/bin/$script" ]; then
                printf "  ${GREEN}✓${NC} %-35s %s\n" "bin/$script" "(executable)"
            else
                printf "  ${YELLOW}!${NC} %-35s %s\n" "bin/$script" "(not executable)"
                ISSUES=$((ISSUES + 1))
            fi
        else
            printf "  ${YELLOW}○${NC} %-35s %s\n" "bin/$script" "(not installed)"
        fi
    done

    # Check management scripts
    for script in update.sh install.sh uninstall.sh; do
        if [ -f "$SYNTHESIS_HOME/bin/$script" ]; then
            printf "  ${GREEN}✓${NC} %-35s %s\n" "bin/$script" "(present)"
        else
            printf "  ${DIM}○${NC} %-35s %s\n" "bin/$script" "(not installed)"
        fi
    done

    # Check documentation
    echo ""
    if [ -d "$SYNTHESIS_HOME/docs" ]; then
        DOC_COUNT=$(find "$SYNTHESIS_HOME/docs" -name "*.md" 2>/dev/null | wc -l || echo 0)
        printf "  ${GREEN}✓${NC} %-35s %s\n" "documentation" "($DOC_COUNT files)"
    else
        printf "  ${YELLOW}○${NC} %-35s %s\n" "documentation" "(not installed)"
    fi

    # Check fingerprint
    if [ -f "$SYNTHESIS_HOME/.installation.json" ]; then
        printf "  ${GREEN}✓${NC} %-35s %s\n" "installation fingerprint" "(present)"
    else
        printf "  ${YELLOW}○${NC} %-35s %s\n" "installation fingerprint" "(not created yet)"
    fi

    echo ""
    if [ $ISSUES -gt 0 ]; then
        warn "$ISSUES issue(s) detected."
        detail "Run 'synthesis-update --full' to fix."
    else
        info "Installation is functional."
        if [ ! -f "$LIB_DIR/synthesis-mcp-server.jar" ] || [ ! -f "$LIB_DIR/synthesis-lsp-server.jar" ]; then
            detail "Optional components available. Run 'synthesis-update --full' to install."
        fi
    fi
    exit 0
fi

# ---------------------------------------------------------------------------
# Action: Install Component
# ---------------------------------------------------------------------------
if [ "$ACTION" = "install-component" ]; then
    if [ -z "$INSTALL_COMPONENT" ]; then
        error "No component specified. Usage: --install-component NAME"
        detail "Available: synthesis-mcp-server, synthesis-lsp-server, launcher-scripts"
        exit 1
    fi

    SOURCE_DIR=$(find_source_dir)

    step "Installing component: $INSTALL_COMPONENT"

    case "$INSTALL_COMPONENT" in
        synthesis-mcp-server)
            if [ -n "$SOURCE_DIR" ] && [ -f "$SOURCE_DIR/target/synthesis-mcp-server.jar" ]; then
                cp "$SOURCE_DIR/target/synthesis-mcp-server.jar" "$LIB_DIR/synthesis-mcp-server.jar"
                info "Installed synthesis-mcp-server.jar from source"
            elif [ -n "$SOURCE_DIR" ] && [ -f "$SOURCE_DIR/bin/synthesis-mcp-server" ]; then
                # JAR not built yet
                error "MCP server JAR not found. Build first:"
                detail "cd $SOURCE_DIR && mvn package -DskipTests"
                exit 1
            else
                error "Source directory not found. Cannot install component."
                detail "Build from source first or use --full update."
                exit 1
            fi
            # Install launcher script
            if [ -n "$SOURCE_DIR" ] && [ -f "$SOURCE_DIR/bin/synthesis-mcp-server" ]; then
                cp "$SOURCE_DIR/bin/synthesis-mcp-server" "$SYNTHESIS_HOME/bin/synthesis-mcp-server"
                chmod +x "$SYNTHESIS_HOME/bin/synthesis-mcp-server"
                info "Installed bin/synthesis-mcp-server launcher"
            fi
            echo ""
            info "MCP server installed! Configure Claude Code:"
            detail "Add to ~/.claude/config.json:"
            detail '  { "mcpServers": { "synthesis": {'
            detail '      "command": "synthesis-mcp-server",'
            detail '      "args": ["--workspace", "/path/to/project"]'
            detail '  }}}'
            ;;
        synthesis-lsp-server)
            if [ -n "$SOURCE_DIR" ] && [ -f "$SOURCE_DIR/target/synthesis-lsp-server.jar" ]; then
                cp "$SOURCE_DIR/target/synthesis-lsp-server.jar" "$LIB_DIR/synthesis-lsp-server.jar"
                info "Installed synthesis-lsp-server.jar from source"
            else
                error "LSP server JAR not found. Build first:"
                detail "cd $SOURCE_DIR && mvn package -DskipTests"
                exit 1
            fi
            if [ -n "$SOURCE_DIR" ] && [ -f "$SOURCE_DIR/bin/synthesis-lsp-server" ]; then
                cp "$SOURCE_DIR/bin/synthesis-lsp-server" "$SYNTHESIS_HOME/bin/synthesis-lsp-server"
                chmod +x "$SYNTHESIS_HOME/bin/synthesis-lsp-server"
                info "Installed bin/synthesis-lsp-server launcher"
            fi
            ;;
        launcher-scripts)
            if [ -n "$SOURCE_DIR" ]; then
                for script in synthesis synthesis-mcp-server synthesis-lsp-server; do
                    if [ -f "$SOURCE_DIR/bin/$script" ]; then
                        cp "$SOURCE_DIR/bin/$script" "$SYNTHESIS_HOME/bin/$script"
                        chmod +x "$SYNTHESIS_HOME/bin/$script"
                        info "Installed bin/$script"
                    fi
                done
            else
                error "Source directory not found."
                exit 1
            fi
            ;;
        update-script)
            if [ -n "$SOURCE_DIR" ] && [ -f "$SOURCE_DIR/bin/update.sh" ]; then
                cp "$SOURCE_DIR/bin/update.sh" "$SYNTHESIS_HOME/bin/update.sh"
                chmod +x "$SYNTHESIS_HOME/bin/update.sh"
                (cd "$SYNTHESIS_HOME/bin" && ln -sf update.sh synthesis-update)
                info "Installed bin/update.sh"
            else
                # Try GitHub
                TEMP_SCRIPT=$(mktemp)
                if download_file "${GITHUB_RAW}/bin/update.sh" "$TEMP_SCRIPT"; then
                    cp "$TEMP_SCRIPT" "$SYNTHESIS_HOME/bin/update.sh"
                    chmod +x "$SYNTHESIS_HOME/bin/update.sh"
                    rm -f "$TEMP_SCRIPT"
                    info "Downloaded and installed update.sh from GitHub"
                else
                    rm -f "$TEMP_SCRIPT"
                    error "Failed to install update script"
                    exit 1
                fi
            fi
            ;;
        documentation)
            if [ -n "$SOURCE_DIR" ] && [ -d "$SOURCE_DIR/docs" ]; then
                mkdir -p "$SYNTHESIS_HOME/docs"
                cp -r "$SOURCE_DIR/docs"/* "$SYNTHESIS_HOME/docs/"
                DOC_COUNT=$(find "$SYNTHESIS_HOME/docs" -name "*.md" | wc -l)
                info "Installed documentation ($DOC_COUNT files)"
            else
                error "Source docs not found."
                exit 1
            fi
            ;;
        *)
            error "Unknown component: $INSTALL_COMPONENT"
            detail "Available: synthesis-mcp-server, synthesis-lsp-server, launcher-scripts, update-script, documentation"
            exit 1
            ;;
    esac
    exit 0
fi

# ---------------------------------------------------------------------------
# Action: Rollback
# ---------------------------------------------------------------------------
if [ "$ACTION" = "rollback" ]; then
    step "Rolling back to previous version..."

    CURRENT=$(get_current_version)
    PREVIOUS=$(get_previous_version)

    if [ -z "$PREVIOUS" ]; then
        error "No previous version available for rollback"
        detail "Installed versions:"
        get_installed_versions | while read -r v; do
            if [ "$v" = "$CURRENT" ]; then
                detail "  $v (current)"
            else
                detail "  $v"
            fi
        done
        exit 1
    fi

    info "Current version:  $CURRENT"
    info "Rollback target:  $PREVIOUS"

    local_jar="synthesis-${PREVIOUS}.jar"
    if [ ! -f "$LIB_DIR/$local_jar" ]; then
        error "Previous version JAR not found: $LIB_DIR/$local_jar"
        exit 1
    fi

    (cd "$LIB_DIR" && ln -sf "$local_jar" current.jar)
    echo "$PREVIOUS" > "$META_DIR/version"

    # Verify
    if java -jar "$LIB_DIR/current.jar" --version >/dev/null 2>&1; then
        info "Rollback successful: now running $PREVIOUS"
    else
        warn "Rollback completed but verification failed. The JAR may still work."
    fi
    exit 0
fi

# ---------------------------------------------------------------------------
# Get Current Version
# ---------------------------------------------------------------------------
CURRENT_VERSION=$(get_current_version)
[ "$QUIET" = false ] && step "Current version: $CURRENT_VERSION"

# ---------------------------------------------------------------------------
# Check for Running Processes
# ---------------------------------------------------------------------------
if check_running; then
    warn "Synthesis appears to be running. The update will take effect on next launch."
fi

# ---------------------------------------------------------------------------
# Find Latest Available Version
# ---------------------------------------------------------------------------
step "Checking for updates..."

BEST_SOURCE=""
BEST_VERSION=""
BEST_URL=""

# Check GitHub releases
detail "Checking GitHub releases..."
GH_RESULT=$(check_github_releases || true)
if [ -n "$GH_RESULT" ]; then
    GH_VERSION=$(echo "$GH_RESULT" | cut -d: -f2)
    GH_URL=$(echo "$GH_RESULT" | cut -d: -f3-)

    # Strip 'v' prefix if present
    GH_VERSION="${GH_VERSION#v}"

    if [ -n "$VERSION_PATTERN" ]; then
        if version_matches "$GH_VERSION" "$VERSION_PATTERN"; then
            BEST_SOURCE="github"
            BEST_VERSION="$GH_VERSION"
            BEST_URL="$GH_URL"
        fi
    else
        BEST_SOURCE="github"
        BEST_VERSION="$GH_VERSION"
        BEST_URL="$GH_URL"
    fi

    detail "GitHub: $GH_VERSION available"
fi

# Check Cantara Maven repository
detail "Checking Cantara Maven repository..."
CANTARA_RESULT=$(check_cantara_repo || true)
if [ -n "$CANTARA_RESULT" ]; then
    CANTARA_VERSION=$(echo "$CANTARA_RESULT" | cut -d: -f2)
    CANTARA_URL=$(echo "$CANTARA_RESULT" | cut -d: -f3-)

    if [ -n "$VERSION_PATTERN" ]; then
        if version_matches "$CANTARA_VERSION" "$VERSION_PATTERN"; then
            # Prefer Cantara if it's newer than GitHub
            if [ -z "$BEST_VERSION" ]; then
                BEST_SOURCE="cantara"
                BEST_VERSION="$CANTARA_VERSION"
                BEST_URL="$CANTARA_URL"
            else
                set +e
                version_compare "$CANTARA_VERSION" "$BEST_VERSION"
                cmp_result=$?
                set -e
                if [ $cmp_result -eq 0 ]; then
                    BEST_SOURCE="cantara"
                    BEST_VERSION="$CANTARA_VERSION"
                    BEST_URL="$CANTARA_URL"
                fi
            fi
        fi
    else
        if [ -z "$BEST_VERSION" ]; then
            BEST_SOURCE="cantara"
            BEST_VERSION="$CANTARA_VERSION"
            BEST_URL="$CANTARA_URL"
        else
            set +e
            version_compare "$CANTARA_VERSION" "$BEST_VERSION"
            cmp_result=$?
            set -e
            if [ $cmp_result -eq 0 ]; then
                BEST_SOURCE="cantara"
                BEST_VERSION="$CANTARA_VERSION"
                BEST_URL="$CANTARA_URL"
            fi
        fi
    fi

    detail "Cantara: $CANTARA_VERSION available"
fi

# ---------------------------------------------------------------------------
# Action: Check Only
# ---------------------------------------------------------------------------
if [ "$ACTION" = "check" ]; then
    if [ -z "$BEST_VERSION" ]; then
        # No remote versions found, check source
        if [ -f "$META_DIR/source-dir" ]; then
            SOURCE_DIR=$(cat "$META_DIR/source-dir")
            if [ -d "$SOURCE_DIR/.git" ]; then
                REMOTE_HEAD=$(cd "$SOURCE_DIR" && git ls-remote origin HEAD 2>/dev/null | awk '{print $1}' || true)
                LOCAL_HEAD=$(cd "$SOURCE_DIR" && git rev-parse HEAD 2>/dev/null || true)
                if [ -n "$REMOTE_HEAD" ] && [ "$REMOTE_HEAD" != "$LOCAL_HEAD" ]; then
                    echo "Update available (source): new commits in repository"
                    exit 0
                fi
            fi
        fi
        if [ "$QUIET" = false ]; then
            info "No updates found. Current version: $CURRENT_VERSION"
        fi
        exit 0
    fi

    set +e
    version_compare "$BEST_VERSION" "$CURRENT_VERSION"
    cmp_result=$?
    set -e

    if [ $cmp_result -eq 0 ]; then
        echo "Update available: $BEST_VERSION (current: $CURRENT_VERSION)"
        exit 0
    elif [ $cmp_result -eq 1 ]; then
        if [ "$QUIET" = false ]; then
            info "Already up to date: $CURRENT_VERSION"
        fi
        exit 0
    else
        if [ "$QUIET" = false ]; then
            info "Current version ($CURRENT_VERSION) is newer than latest ($BEST_VERSION)"
        fi
        exit 0
    fi
fi

# ---------------------------------------------------------------------------
# Action: Update
# ---------------------------------------------------------------------------
NEED_UPDATE=false

if [ -z "$BEST_VERSION" ]; then
    # No remote version found, try source build
    detail "No remote versions found. Attempting source build..."
    SOURCE_RESULT=$(update_from_source || true)
    if [ -n "$SOURCE_RESULT" ]; then
        NEW_VERSION=$(echo "$SOURCE_RESULT" | cut -d: -f2)
        info "Updated from source to version $NEW_VERSION"
        echo "$(date +%s)" > "$META_DIR/last-update-check"
        exit 0
    else
        error "No update sources available."
        detail "Ensure GitHub access or a local source directory."
        exit 1
    fi
fi

# Compare versions
if [ "$FORCE" = true ]; then
    NEED_UPDATE=true
    info "Forcing update (--force)"
else
    set +e
    version_compare "$BEST_VERSION" "$CURRENT_VERSION"
    cmp_result=$?
    set -e

    if [ $cmp_result -eq 0 ]; then
        NEED_UPDATE=true
    elif [ $cmp_result -eq 1 ]; then
        info "Already up to date: $CURRENT_VERSION"
        echo "$(date +%s)" > "$META_DIR/last-update-check"
        exit 0
    else
        info "Current version ($CURRENT_VERSION) is newer than latest ($BEST_VERSION)"
        info "Use --force to downgrade."
        echo "$(date +%s)" > "$META_DIR/last-update-check"
        exit 0
    fi
fi

if [ "$NEED_UPDATE" = false ]; then
    exit 0
fi

# ---------------------------------------------------------------------------
# Download and Install
# ---------------------------------------------------------------------------
step "Updating: $CURRENT_VERSION -> $BEST_VERSION"

JAR_NAME="synthesis-${BEST_VERSION}.jar"
TEMP_JAR=$(mktemp)

# Backup current JAR
if [ -f "$LIB_DIR/current.jar" ]; then
    CURRENT_JAR=$(readlink "$LIB_DIR/current.jar" 2>/dev/null || echo "current.jar")
    detail "Backing up current: $CURRENT_JAR"
fi

# Download
if [ -n "$BEST_URL" ]; then
    detail "Downloading from $BEST_SOURCE..."
    if ! download_file "$BEST_URL" "$TEMP_JAR"; then
        rm -f "$TEMP_JAR"
        error "Download failed from $BEST_URL"

        # Fallback to source build
        detail "Attempting source build as fallback..."
        SOURCE_RESULT=$(update_from_source || true)
        if [ -n "$SOURCE_RESULT" ]; then
            NEW_VERSION=$(echo "$SOURCE_RESULT" | cut -d: -f2)
            info "Updated from source to version $NEW_VERSION"
            echo "$(date +%s)" > "$META_DIR/last-update-check"
            exit 0
        fi

        exit 1
    fi
else
    # Source build
    rm -f "$TEMP_JAR"
    SOURCE_RESULT=$(update_from_source || true)
    if [ -n "$SOURCE_RESULT" ]; then
        NEW_VERSION=$(echo "$SOURCE_RESULT" | cut -d: -f2)
        info "Updated from source to version $NEW_VERSION"
        echo "$(date +%s)" > "$META_DIR/last-update-check"
        exit 0
    fi
    error "No download URL and source build failed."
    exit 1
fi

# Verify download (check it's a valid JAR - starts with PK zip header)
if [ -f "$TEMP_JAR" ]; then
    HEADER=$(xxd -l 2 -p "$TEMP_JAR" 2>/dev/null || true)
    if [ "$HEADER" != "504b" ]; then
        rm -f "$TEMP_JAR"
        error "Downloaded file is not a valid JAR (ZIP archive)"
        exit 1
    fi
fi

# SHA verification (if checksum file available)
SHA_URL="${BEST_URL}.sha256"
if url_exists "$SHA_URL" 2>/dev/null; then
    detail "Verifying SHA256 checksum..."
    EXPECTED_SHA=$(curl -fsSL "$SHA_URL" 2>/dev/null | awk '{print $1}' || true)
    ACTUAL_SHA=$(compute_sha "$TEMP_JAR")

    if [ -n "$EXPECTED_SHA" ] && [ "$EXPECTED_SHA" != "$ACTUAL_SHA" ]; then
        rm -f "$TEMP_JAR"
        error "SHA256 checksum mismatch!"
        detail "Expected: $EXPECTED_SHA"
        detail "Actual:   $ACTUAL_SHA"
        exit 1
    fi
    info "SHA256 checksum verified"
else
    detail "No SHA256 checksum available (skipping verification)"
fi

# Install new JAR
mv "$TEMP_JAR" "$LIB_DIR/$JAR_NAME"
detail "Installed $JAR_NAME"

# Update symlink
(cd "$LIB_DIR" && ln -sf "$JAR_NAME" current.jar)
detail "Updated current.jar -> $JAR_NAME"

# Update metadata
echo "$BEST_VERSION" > "$META_DIR/version"
echo "$(date +%s)" > "$META_DIR/last-update-check"
echo "$(date -Iseconds)" > "$META_DIR/last-update"

# ---------------------------------------------------------------------------
# Verify New Version
# ---------------------------------------------------------------------------
step "Verifying update..."

if java -jar "$LIB_DIR/current.jar" --version >/dev/null 2>&1; then
    VERIFIED=$(java -jar "$LIB_DIR/current.jar" --version 2>&1 || true)
    info "Verification passed: $VERIFIED"
else
    warn "Verification failed. Rolling back..."

    # Rollback
    PREVIOUS=$(get_previous_version)
    if [ -n "$PREVIOUS" ]; then
        (cd "$LIB_DIR" && ln -sf "synthesis-${PREVIOUS}.jar" current.jar)
        echo "$PREVIOUS" > "$META_DIR/version"
        warn "Rolled back to $PREVIOUS"
        warn "The new JAR is kept at $LIB_DIR/$JAR_NAME for inspection."
    fi
    exit 1
fi

# ---------------------------------------------------------------------------
# Cleanup Old Versions (keep last 3)
# ---------------------------------------------------------------------------
step "Cleaning up old versions..."

INSTALLED_COUNT=$(get_installed_versions | wc -l)
if [ "$INSTALLED_COUNT" -gt 3 ]; then
    REMOVE_COUNT=$((INSTALLED_COUNT - 3))
    get_installed_versions | head -n "$REMOVE_COUNT" | while read -r old_version; do
        OLD_JAR="$LIB_DIR/synthesis-${old_version}.jar"
        if [ -f "$OLD_JAR" ]; then
            detail "Removing old version: $old_version"
            rm -f "$OLD_JAR"
        fi
    done
    info "Kept last 3 versions"
else
    detail "No cleanup needed ($INSTALLED_COUNT versions installed)"
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
printf "\n"
printf "${GREEN}${BOLD}Update complete!${NC}\n"
printf "  ${BOLD}Previous:${NC}  %s\n" "$CURRENT_VERSION"
printf "  ${BOLD}Current:${NC}   %s\n" "$BEST_VERSION"
printf "  ${BOLD}Source:${NC}    %s\n" "$BEST_SOURCE"
printf "\n"

# Show installed versions
detail "Installed versions:"
get_installed_versions | while read -r v; do
    if [ "$v" = "$BEST_VERSION" ]; then
        detail "  ${GREEN}$v (current)${NC}"
    else
        detail "  ${DIM}$v${NC}"
    fi
done
printf "\n"

# ---------------------------------------------------------------------------
# Comprehensive Update (scripts, docs, assets) -- triggered by --full flag
# ---------------------------------------------------------------------------
if [ "$FULL_UPDATE" = true ]; then
    SOURCE_DIR=$(find_source_dir)

    step "Performing comprehensive update..."

    UPDATED_COMPONENTS=0

    # Update launcher scripts
    if [ -n "$SOURCE_DIR" ]; then
        for script in synthesis synthesis-mcp-server synthesis-lsp-server; do
            if [ -f "$SOURCE_DIR/bin/$script" ]; then
                cp "$SOURCE_DIR/bin/$script" "$SYNTHESIS_HOME/bin/$script"
                chmod +x "$SYNTHESIS_HOME/bin/$script"
                detail "Updated bin/$script"
                UPDATED_COMPONENTS=$((UPDATED_COMPONENTS + 1))
            fi
        done

        # Update management scripts
        for script in update.sh install.sh uninstall.sh; do
            if [ -f "$SOURCE_DIR/bin/$script" ]; then
                cp "$SOURCE_DIR/bin/$script" "$SYNTHESIS_HOME/bin/$script"
                chmod +x "$SYNTHESIS_HOME/bin/$script"
                detail "Updated bin/$script"
                UPDATED_COMPONENTS=$((UPDATED_COMPONENTS + 1))
            fi
        done

        # Recreate synthesis-update symlink
        (cd "$SYNTHESIS_HOME/bin" && ln -sf update.sh synthesis-update)

        # Install MCP server JAR (if built)
        if [ -f "$SOURCE_DIR/target/synthesis-mcp-server.jar" ]; then
            cp "$SOURCE_DIR/target/synthesis-mcp-server.jar" "$LIB_DIR/synthesis-mcp-server.jar"
            detail "Updated synthesis-mcp-server.jar"
            UPDATED_COMPONENTS=$((UPDATED_COMPONENTS + 1))
        fi

        # Install LSP server JAR (if built)
        if [ -f "$SOURCE_DIR/target/synthesis-lsp-server.jar" ]; then
            cp "$SOURCE_DIR/target/synthesis-lsp-server.jar" "$LIB_DIR/synthesis-lsp-server.jar"
            detail "Updated synthesis-lsp-server.jar"
            UPDATED_COMPONENTS=$((UPDATED_COMPONENTS + 1))
        fi

        # Install documentation
        if [ "$SKIP_DOCS" = false ] && [ -d "$SOURCE_DIR/docs" ]; then
            mkdir -p "$SYNTHESIS_HOME/docs"
            cp -r "$SOURCE_DIR/docs"/* "$SYNTHESIS_HOME/docs/" 2>/dev/null || true
            DOC_COUNT=$(find "$SYNTHESIS_HOME/docs" -name "*.md" 2>/dev/null | wc -l || echo 0)
            detail "Updated documentation ($DOC_COUNT files)"
            UPDATED_COMPONENTS=$((UPDATED_COMPONENTS + 1))
        fi

        # Install README
        if [ -f "$SOURCE_DIR/README.md" ]; then
            cp "$SOURCE_DIR/README.md" "$SYNTHESIS_HOME/README.md"
            detail "Updated README.md"
        fi

    else
        # No source directory -- try downloading scripts from GitHub
        detail "No source directory found. Downloading from GitHub..."
        for script in synthesis synthesis-mcp-server synthesis-lsp-server update.sh install.sh uninstall.sh; do
            TEMP_FILE=$(mktemp)
            if download_file "${GITHUB_RAW}/bin/$script" "$TEMP_FILE" 2>/dev/null; then
                cp "$TEMP_FILE" "$SYNTHESIS_HOME/bin/$script"
                chmod +x "$SYNTHESIS_HOME/bin/$script"
                detail "Downloaded bin/$script"
                UPDATED_COMPONENTS=$((UPDATED_COMPONENTS + 1))
            fi
            rm -f "$TEMP_FILE"
        done
        (cd "$SYNTHESIS_HOME/bin" && ln -sf update.sh synthesis-update)
    fi

    # Write installation fingerprint
    FINGERPRINT="$SYNTHESIS_HOME/.installation.json"
    HAS_MCP=$([ -f "$LIB_DIR/synthesis-mcp-server.jar" ] && echo "true" || echo "false")
    HAS_LSP=$([ -f "$LIB_DIR/synthesis-lsp-server.jar" ] && echo "true" || echo "false")
    HAS_DOCS=$([ -d "$SYNTHESIS_HOME/docs" ] && echo "true" || echo "false")
    cat > "$FINGERPRINT" <<FPEOF
{
  "version": "$BEST_VERSION",
  "installDate": "$(cat "$META_DIR/install-date" 2>/dev/null || date -Iseconds)",
  "lastUpdateDate": "$(date -Iseconds)",
  "installMethod": "$([ -n "$SOURCE_DIR" ] && echo "source" || echo "github")",
  "installSource": "$([ -n "$SOURCE_DIR" ] && echo "source-build" || echo "$BEST_SOURCE")",
  "sourceDirectory": "${SOURCE_DIR:-}",
  "components": {
    "synthesis-cli": { "installed": true, "version": "$BEST_VERSION" },
    "synthesis-mcp-server": { "installed": $HAS_MCP, "version": "$BEST_VERSION" },
    "synthesis-lsp-server": { "installed": $HAS_LSP, "version": "$BEST_VERSION" },
    "launcher-synthesis": { "installed": true, "version": "$BEST_VERSION" },
    "launcher-mcp-server": { "installed": $([ -f "$SYNTHESIS_HOME/bin/synthesis-mcp-server" ] && echo "true" || echo "false"), "version": "$BEST_VERSION" },
    "launcher-lsp-server": { "installed": $([ -f "$SYNTHESIS_HOME/bin/synthesis-lsp-server" ] && echo "true" || echo "false"), "version": "$BEST_VERSION" },
    "update-script": { "installed": true, "version": "$BEST_VERSION" },
    "documentation": { "installed": $HAS_DOCS, "version": "$BEST_VERSION" }
  }
}
FPEOF
    detail "Written installation fingerprint"

    printf "\n"
    printf "${GREEN}${BOLD}Comprehensive update complete!${NC}\n"
    printf "  ${BOLD}Components updated:${NC} %d\n" "$UPDATED_COMPONENTS"
    printf "\n"
fi
