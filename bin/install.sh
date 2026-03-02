#!/usr/bin/env bash
#
# Synthesis Installer
# One-command installation for Synthesis - AI operations partner for knowledge infrastructure.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash
#   ./bin/install.sh
#   ./bin/install.sh --source /path/to/synthesis-repo
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
readonly MIN_JAVA_VERSION=21

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
    NC='\033[0m'
else
    RED='' GREEN='' YELLOW='' BLUE='' CYAN='' BOLD='' NC=''
fi

info()    { printf "${GREEN}[INFO]${NC}  %s\n" "$*"; }
warn()    { printf "${YELLOW}[WARN]${NC}  %s\n" "$*"; }
error()   { printf "${RED}[ERROR]${NC} %s\n" "$*" >&2; }
step()    { printf "${BLUE}==>${NC} ${BOLD}%s${NC}\n" "$*"; }
detail()  { printf "    %s\n" "$*"; }

# ---------------------------------------------------------------------------
# Cleanup on failure
# ---------------------------------------------------------------------------
CLEANUP_ON_FAIL=true
cleanup() {
    local exit_code=$?
    if [ $exit_code -ne 0 ] && [ "$CLEANUP_ON_FAIL" = true ]; then
        # Only remove if we created it this run
        if [ "${CREATED_SYNTHESIS_HOME:-false}" = true ]; then
            warn "Installation failed. Cleaning up..."
            rm -rf "$SYNTHESIS_HOME"
        fi
    fi
    exit $exit_code
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Helper Functions
# ---------------------------------------------------------------------------
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

get_java_version() {
    local version_output
    version_output=$(java -version 2>&1 | head -1)
    # Extract version number, handling both "1.8.0" and "17.0.1" formats
    local version
    version=$(echo "$version_output" | sed -n 's/.*version "\([0-9]*\).*/\1/p')
    if [ -z "$version" ]; then
        version=$(echo "$version_output" | sed -n 's/.*version "\(1\.\)\?\([0-9]*\).*/\2/p')
    fi
    echo "${version:-0}"
}

detect_shell() {
    local shell_name
    shell_name=$(basename "${SHELL:-/bin/bash}")
    case "$shell_name" in
        zsh)  echo "zsh" ;;
        bash) echo "bash" ;;
        *)    echo "bash" ;;
    esac
}

get_shell_rc() {
    local shell_type
    shell_type=$(detect_shell)
    case "$shell_type" in
        zsh)
            if [ -f "$HOME/.zshrc" ]; then
                echo "$HOME/.zshrc"
            else
                echo "$HOME/.zprofile"
            fi
            ;;
        bash)
            if [ -f "$HOME/.bashrc" ]; then
                echo "$HOME/.bashrc"
            elif [ -f "$HOME/.bash_profile" ]; then
                echo "$HOME/.bash_profile"
            else
                echo "$HOME/.profile"
            fi
            ;;
    esac
}

detect_os() {
    local uname_out
    uname_out=$(uname -s)
    case "$uname_out" in
        Linux*)  echo "linux" ;;
        Darwin*) echo "macos" ;;
        *)       echo "unknown" ;;
    esac
}

download_file() {
    local url="$1"
    local output="$2"
    if command_exists curl; then
        curl -fsSL -o "$output" "$url"
    elif command_exists wget; then
        wget -q -O "$output" "$url"
    else
        error "Neither curl nor wget available. Cannot download files."
        return 1
    fi
}

# Check if a URL exists (HTTP 200)
url_exists() {
    local url="$1"
    if command_exists curl; then
        curl -fsSL -o /dev/null -w "%{http_code}" "$url" 2>/dev/null | grep -q "200"
    elif command_exists wget; then
        wget -q --spider "$url" 2>/dev/null
    else
        return 1
    fi
}

# ---------------------------------------------------------------------------
# Parse Arguments
# ---------------------------------------------------------------------------
SOURCE_DIR=""
FORCE=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --source)
            SOURCE_DIR="$2"
            shift 2
            ;;
        --force)
            FORCE=true
            shift
            ;;
        --help|-h)
            echo "Synthesis Installer"
            echo ""
            echo "Usage:"
            echo "  install.sh [options]"
            echo ""
            echo "Options:"
            echo "  --source DIR   Use local source directory instead of cloning"
            echo "  --force        Overwrite existing installation"
            echo "  -h, --help     Show this help"
            echo ""
            echo "Environment:"
            echo "  SYNTHESIS_HOME  Installation directory (default: ~/.synthesis)"
            exit 0
            ;;
        *)
            error "Unknown option: $1"
            exit 1
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------
printf "\n"
printf "${CYAN}${BOLD}"
printf "  ____              _   _               _     \n"
printf " / ___| _   _ _ __ | |_| |__   ___  ___(_)___ \n"
printf " \\___ \\| | | | '_ \\| __| '_ \\ / _ \\/ __| / __|\n"
printf "  ___) | |_| | | | | |_| | | |  __/\\__ \\ \\__ \\\\\n"
printf " |____/ \\__, |_| |_|\\__|_| |_|\\___||___/_|___/\n"
printf "        |___/                                  \n"
printf "${NC}\n"
printf "  ${BOLD}AI operations partner for knowledge infrastructure${NC}\n"
printf "  ${CYAN}https://github.com/exoreaction/Synthesis${NC}\n"
printf "\n"

# ---------------------------------------------------------------------------
# Step 1: Check Prerequisites
# ---------------------------------------------------------------------------
step "Checking prerequisites..."

# Check: Java 17+
if command_exists java; then
    JAVA_VER=$(get_java_version)
    if [ "$JAVA_VER" -ge "$MIN_JAVA_VERSION" ]; then
        info "Java $JAVA_VER found (>= $MIN_JAVA_VERSION required)"
    else
        error "Java $JAVA_VER found, but Java $MIN_JAVA_VERSION+ is required."
        detail "Install Java 21+ (Azul Zulu recommended):"
        detail "  Ubuntu/Debian: sudo apt install zulu21-jdk  (after adding Azul repo)"
        detail "  macOS:         brew install --cask zulu21"
        detail "  All platforms: https://www.azul.com/downloads/?version=java-21&package=jdk"
        exit 1
    fi
else
    error "Java not found. Java $MIN_JAVA_VERSION+ is required."
    detail "Install Java 21+ (Azul Zulu recommended):"
    detail "  Ubuntu/Debian: sudo apt install zulu21-jdk  (after adding Azul repo)"
    detail "  macOS:         brew install --cask zulu21"
    detail "  All platforms: https://www.azul.com/downloads/?version=java-21&package=jdk"
    exit 1
fi

# Check: curl or wget
if command_exists curl || command_exists wget; then
    info "Download tool available ($(command_exists curl && echo "curl" || echo "wget"))"
else
    error "Neither curl nor wget found. At least one is required."
    exit 1
fi

# Check: git (needed for source builds)
if command_exists git; then
    info "Git found ($(git --version | head -1))"
else
    warn "Git not found. Source builds will not be available."
    detail "Install git for update support:"
    detail "  Ubuntu/Debian: sudo apt install git"
    detail "  macOS:         brew install git"
fi

# Check: maven (optional, for source builds)
if command_exists mvn; then
    info "Maven found (source builds enabled)"
    HAS_MAVEN=true
else
    HAS_MAVEN=false
    detail "Maven not found (optional, needed for source builds)"
    detail "  Ubuntu/Debian: sudo apt install maven"
    detail "  macOS:         brew install maven"
fi

# ---------------------------------------------------------------------------
# Step 2: Check Existing Installation
# ---------------------------------------------------------------------------
step "Checking for existing installation..."

if [ -d "$SYNTHESIS_HOME" ]; then
    if [ "$FORCE" = true ]; then
        warn "Existing installation found at $SYNTHESIS_HOME"
        warn "Removing (--force specified)..."
        rm -rf "$SYNTHESIS_HOME"
    else
        error "Synthesis is already installed at $SYNTHESIS_HOME"
        detail "To reinstall, use:  install.sh --force"
        detail "To update, use:     synthesis-update"
        detail "To uninstall, use:  ~/.synthesis/bin/update.sh --uninstall"
        exit 1
    fi
fi

# ---------------------------------------------------------------------------
# Step 3: Create Directory Structure
# ---------------------------------------------------------------------------
step "Creating directory structure..."

mkdir -p "$SYNTHESIS_HOME"/{bin,lib,.metadata}
CREATED_SYNTHESIS_HOME=true

detail "Created $SYNTHESIS_HOME/"
detail "Created $SYNTHESIS_HOME/bin/"
detail "Created $SYNTHESIS_HOME/lib/"
detail "Created $SYNTHESIS_HOME/.metadata/"

# ---------------------------------------------------------------------------
# Step 4: Obtain Synthesis JAR
# ---------------------------------------------------------------------------
step "Obtaining Synthesis..."

JAR_OBTAINED=false
INSTALLED_VERSION=""

# Strategy 1: Check GitHub releases
if [ "$JAR_OBTAINED" = false ]; then
    detail "Checking GitHub releases..."
    RELEASE_URL=""
    if command_exists curl; then
        RELEASE_URL=$(curl -fsSL "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" 2>/dev/null \
            | grep -o '"browser_download_url": *"[^"]*synthesis[^"]*\.jar"' \
            | head -1 \
            | sed 's/"browser_download_url": *"//;s/"$//' || true)
    fi

    if [ -n "$RELEASE_URL" ]; then
        detail "Downloading from GitHub release..."
        RELEASE_TAG=$(curl -fsSL "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" 2>/dev/null \
            | grep -o '"tag_name": *"[^"]*"' | head -1 | sed 's/"tag_name": *"//;s/"$//' || true)
        INSTALLED_VERSION="${RELEASE_TAG:-unknown}"
        JAR_NAME="synthesis-${INSTALLED_VERSION}.jar"

        if download_file "$RELEASE_URL" "$SYNTHESIS_HOME/lib/$JAR_NAME"; then
            JAR_OBTAINED=true
            info "Downloaded $JAR_NAME from GitHub release"
        else
            warn "GitHub release download failed, trying next method..."
        fi
    else
        detail "No GitHub releases found, trying next method..."
    fi
fi

# Strategy 2: Check Cantara Maven repository (releases first, then snapshots)
if [ "$JAR_OBTAINED" = false ]; then
    detail "Checking Cantara Maven repository..."

    # Try releases
    MAVEN_METADATA_URL="${CANTARA_RELEASES}/${GROUP_PATH}/${ARTIFACT_ID}/maven-metadata.xml"
    if url_exists "$MAVEN_METADATA_URL" 2>/dev/null; then
        LATEST_RELEASE=$(curl -fsSL "$MAVEN_METADATA_URL" 2>/dev/null \
            | grep -o '<release>[^<]*</release>' | sed 's/<[^>]*>//g' || true)

        if [ -n "$LATEST_RELEASE" ]; then
            INSTALLED_VERSION="$LATEST_RELEASE"
            JAR_NAME="synthesis-${INSTALLED_VERSION}.jar"
            JAR_URL="${CANTARA_RELEASES}/${GROUP_PATH}/${ARTIFACT_ID}/${INSTALLED_VERSION}/${JAR_NAME}"

            if download_file "$JAR_URL" "$SYNTHESIS_HOME/lib/$JAR_NAME" 2>/dev/null; then
                JAR_OBTAINED=true
                info "Downloaded $JAR_NAME from Cantara releases"
            fi
        fi
    fi

    # Try snapshots
    if [ "$JAR_OBTAINED" = false ]; then
        MAVEN_METADATA_URL="${CANTARA_SNAPSHOTS}/${GROUP_PATH}/${ARTIFACT_ID}/maven-metadata.xml"
        if url_exists "$MAVEN_METADATA_URL" 2>/dev/null; then
            LATEST_SNAPSHOT=$(curl -fsSL "$MAVEN_METADATA_URL" 2>/dev/null \
                | grep -o '<version>[^<]*</version>' | tail -1 | sed 's/<[^>]*>//g' || true)

            if [ -n "$LATEST_SNAPSHOT" ]; then
                # For snapshots, get the actual timestamped JAR filename
                SNAPSHOT_META_URL="${CANTARA_SNAPSHOTS}/${GROUP_PATH}/${ARTIFACT_ID}/${LATEST_SNAPSHOT}/maven-metadata.xml"
                SNAPSHOT_JAR_NAME=""
                if url_exists "$SNAPSHOT_META_URL" 2>/dev/null; then
                    SNAPSHOT_TS=$(curl -fsSL "$SNAPSHOT_META_URL" 2>/dev/null \
                        | grep -o '<timestamp>[^<]*</timestamp>' | sed 's/<[^>]*>//g' || true)
                    SNAPSHOT_BN=$(curl -fsSL "$SNAPSHOT_META_URL" 2>/dev/null \
                        | grep -o '<buildNumber>[^<]*</buildNumber>' | sed 's/<[^>]*>//g' || true)
                    if [ -n "$SNAPSHOT_TS" ] && [ -n "$SNAPSHOT_BN" ]; then
                        BASE_VER=$(echo "$LATEST_SNAPSHOT" | sed 's/-SNAPSHOT//')
                        SNAPSHOT_JAR_NAME="synthesis-${BASE_VER}-${SNAPSHOT_TS}-${SNAPSHOT_BN}.jar"
                    fi
                fi

                # Fallback to standard SNAPSHOT name
                if [ -z "$SNAPSHOT_JAR_NAME" ]; then
                    SNAPSHOT_JAR_NAME="synthesis-${LATEST_SNAPSHOT}.jar"
                fi

                INSTALLED_VERSION="$LATEST_SNAPSHOT"
                JAR_URL="${CANTARA_SNAPSHOTS}/${GROUP_PATH}/${ARTIFACT_ID}/${LATEST_SNAPSHOT}/${SNAPSHOT_JAR_NAME}"

                if download_file "$JAR_URL" "$SYNTHESIS_HOME/lib/synthesis-${INSTALLED_VERSION}.jar" 2>/dev/null; then
                    JAR_OBTAINED=true
                    info "Downloaded synthesis-${INSTALLED_VERSION}.jar from Cantara snapshots"
                fi
            fi
        fi
    fi
fi

# Strategy 3: Use local source directory (--source flag or auto-detect)
if [ "$JAR_OBTAINED" = false ] && [ -n "$SOURCE_DIR" ]; then
    detail "Using local source directory: $SOURCE_DIR"
    if [ -f "$SOURCE_DIR/pom.xml" ]; then
        # Extract version from pom.xml
        INSTALLED_VERSION=$(grep -m1 '<version>' "$SOURCE_DIR/pom.xml" | sed 's/.*<version>//;s/<\/version>.*//' | tr -d '[:space:]')
        JAR_NAME="synthesis-${INSTALLED_VERSION}.jar"

        # Check for pre-built JAR
        if [ -f "$SOURCE_DIR/target/$JAR_NAME" ]; then
            cp "$SOURCE_DIR/target/$JAR_NAME" "$SYNTHESIS_HOME/lib/$JAR_NAME"
            JAR_OBTAINED=true
            info "Copied pre-built $JAR_NAME from source directory"
        elif [ "$HAS_MAVEN" = true ]; then
            detail "Building from source (mvn package -DskipTests)..."
            if (cd "$SOURCE_DIR" && mvn package -DskipTests -q 2>&1); then
                if [ -f "$SOURCE_DIR/target/$JAR_NAME" ]; then
                    cp "$SOURCE_DIR/target/$JAR_NAME" "$SYNTHESIS_HOME/lib/$JAR_NAME"
                    JAR_OBTAINED=true
                    info "Built and installed $JAR_NAME from source"
                fi
            else
                warn "Maven build failed"
            fi
        else
            error "Maven not found. Cannot build from source."
            detail "Install Maven: sudo apt install maven  (or)  brew install maven"
        fi
    else
        error "No pom.xml found in $SOURCE_DIR"
    fi
fi

# Strategy 4: Clone from GitHub and build
if [ "$JAR_OBTAINED" = false ] && command_exists git && [ "$HAS_MAVEN" = true ]; then
    detail "Cloning from GitHub and building..."
    CLONE_DIR=$(mktemp -d)
    trap "rm -rf '$CLONE_DIR'; cleanup" EXIT

    if git clone --depth 1 "${GITHUB_URL}.git" "$CLONE_DIR/synthesis" 2>/dev/null; then
        INSTALLED_VERSION=$(grep -m1 '<version>' "$CLONE_DIR/synthesis/pom.xml" | sed 's/.*<version>//;s/<\/version>.*//' | tr -d '[:space:]')
        JAR_NAME="synthesis-${INSTALLED_VERSION}.jar"

        detail "Building from source (mvn package -DskipTests)..."
        if (cd "$CLONE_DIR/synthesis" && mvn package -DskipTests -q 2>&1); then
            if [ -f "$CLONE_DIR/synthesis/target/$JAR_NAME" ]; then
                cp "$CLONE_DIR/synthesis/target/$JAR_NAME" "$SYNTHESIS_HOME/lib/$JAR_NAME"
                JAR_OBTAINED=true
                info "Built and installed $JAR_NAME from GitHub source"
            fi
        else
            warn "Maven build failed"
        fi
    else
        warn "Git clone failed"
    fi

    rm -rf "$CLONE_DIR"
    # Restore original trap
    trap cleanup EXIT
fi

# Strategy 5: Auto-detect source in common locations
if [ "$JAR_OBTAINED" = false ]; then
    for candidate in "$HOME/src/synthesis" "$HOME/src/exoreaction/synthesis" "$HOME/projects/synthesis" "$(pwd)"; do
        if [ -f "$candidate/pom.xml" ] && grep -q "synthesis" "$candidate/pom.xml" 2>/dev/null; then
            INSTALLED_VERSION=$(grep -m1 '<version>' "$candidate/pom.xml" | sed 's/.*<version>//;s/<\/version>.*//' | tr -d '[:space:]')
            JAR_NAME="synthesis-${INSTALLED_VERSION}.jar"

            if [ -f "$candidate/target/$JAR_NAME" ]; then
                cp "$candidate/target/$JAR_NAME" "$SYNTHESIS_HOME/lib/$JAR_NAME"
                JAR_OBTAINED=true
                info "Found and copied $JAR_NAME from $candidate"

                # Save source location for future updates
                echo "$candidate" > "$SYNTHESIS_HOME/.metadata/source-dir"
                break
            fi
        fi
    done
fi

if [ "$JAR_OBTAINED" = false ]; then
    error "Could not obtain Synthesis JAR."
    echo ""
    detail "Options:"
    detail "  1. Build from source first:"
    detail "     cd ~/src/synthesis && mvn package -DskipTests"
    detail "     ./bin/install.sh --source ~/src/synthesis"
    detail ""
    detail "  2. Install Maven for automatic builds:"
    detail "     sudo apt install maven  (or)  brew install maven"
    detail ""
    detail "  3. Download a release JAR manually:"
    detail "     Place it at: $SYNTHESIS_HOME/lib/synthesis-VERSION.jar"
    exit 1
fi

# ---------------------------------------------------------------------------
# Step 5: Create Symlinks
# ---------------------------------------------------------------------------
step "Setting up symlinks..."

JAR_NAME="synthesis-${INSTALLED_VERSION}.jar"
(cd "$SYNTHESIS_HOME/lib" && ln -sf "$JAR_NAME" current.jar)
info "Linked current.jar -> $JAR_NAME"

# ---------------------------------------------------------------------------
# Step 6: Install Launcher Script
# ---------------------------------------------------------------------------
step "Installing launcher script..."

cat > "$SYNTHESIS_HOME/bin/synthesis" << 'LAUNCHER_EOF'
#!/usr/bin/env bash
#
# Synthesis Launcher
# Launches the Synthesis CLI with auto-update notifications.
#
# Copyright (c) 2026 eXOReaction AS. All rights reserved.

set -euo pipefail

readonly SYNTHESIS_HOME="${SYNTHESIS_HOME:-$HOME/.synthesis}"
readonly JAR_PATH="$SYNTHESIS_HOME/lib/current.jar"
readonly MIN_JAVA_VERSION=21

# ---------------------------------------------------------------------------
# Color Output
# ---------------------------------------------------------------------------
if [ -t 1 ] && [ -t 2 ]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    NC='\033[0m'
else
    RED='' GREEN='' YELLOW='' NC=''
fi

# ---------------------------------------------------------------------------
# Pre-flight Checks
# ---------------------------------------------------------------------------

# Check JAR exists
if [ ! -f "$JAR_PATH" ]; then
    printf "${RED}Error:${NC} Synthesis JAR not found at %s\n" "$JAR_PATH" >&2
    printf "  Run the installer: curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash\n" >&2
    exit 1
fi

# Check Java
if ! command -v java >/dev/null 2>&1; then
    printf "${RED}Error:${NC} Java not found. Java %d+ is required.\n" "$MIN_JAVA_VERSION" >&2
    printf "  Install: sudo apt install openjdk-17-jdk\n" >&2
    exit 1
fi

# Check Java version
JAVA_VER=$(java -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')
JAVA_VER="${JAVA_VER:-0}"
if [ "$JAVA_VER" -lt "$MIN_JAVA_VERSION" ]; then
    printf "${RED}Error:${NC} Java %s found, but Java %d+ is required.\n" "$JAVA_VER" "$MIN_JAVA_VERSION" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Auto-Update Check (non-blocking, daily)
# ---------------------------------------------------------------------------
if [ "${SYNTHESIS_NO_UPDATE_CHECK:-0}" != "1" ]; then
    LAST_CHECK_FILE="$SYNTHESIS_HOME/.metadata/last-update-check"
    NOW=$(date +%s)
    LAST_CHECK=0

    if [ -f "$LAST_CHECK_FILE" ]; then
        LAST_CHECK=$(cat "$LAST_CHECK_FILE" 2>/dev/null || echo 0)
    fi

    SECONDS_IN_DAY=86400
    if [ $((NOW - LAST_CHECK)) -ge $SECONDS_IN_DAY ]; then
        # Run update check in background (non-blocking)
        (
            "$SYNTHESIS_HOME/bin/update.sh" --check --quiet > "$SYNTHESIS_HOME/.metadata/update-check-result" 2>/dev/null
            echo "$NOW" > "$LAST_CHECK_FILE"
        ) &
        disown 2>/dev/null || true
    fi

    # Show result from previous check
    if [ -f "$SYNTHESIS_HOME/.metadata/update-check-result" ]; then
        UPDATE_MSG=$(cat "$SYNTHESIS_HOME/.metadata/update-check-result" 2>/dev/null || true)
        if [ -n "$UPDATE_MSG" ] && echo "$UPDATE_MSG" | grep -qi "update available"; then
            printf "${YELLOW}%s${NC}\n" "$UPDATE_MSG"
            printf "  Run: synthesis-update\n\n"
            # Clear after showing once
            rm -f "$SYNTHESIS_HOME/.metadata/update-check-result"
        fi
    fi
fi

# ---------------------------------------------------------------------------
# Launch Synthesis
# ---------------------------------------------------------------------------
# Resolve the actual JAR (follow symlink)
REAL_JAR=$(readlink -f "$JAR_PATH" 2>/dev/null || readlink "$JAR_PATH" 2>/dev/null || echo "$JAR_PATH")

exec java -jar "$REAL_JAR" "$@"
LAUNCHER_EOF

chmod +x "$SYNTHESIS_HOME/bin/synthesis"
info "Installed launcher at $SYNTHESIS_HOME/bin/synthesis"

# ---------------------------------------------------------------------------
# Step 6b: Install MCP and LSP Server Scripts
# ---------------------------------------------------------------------------
step "Installing MCP and LSP server scripts..."

# Install MCP and LSP server JARs
# Classifiers published to Cantara Maven: synthesis-{version}-mcp-server.jar, synthesis-{version}-lsp-server.jar
MCP_JAR_NAME="synthesis-mcp-server.jar"
LSP_JAR_NAME="synthesis-lsp-server.jar"

for SERVER_JAR in "$MCP_JAR_NAME" "$LSP_JAR_NAME"; do
    SERVER_JAR_FOUND=false
    CLASSIFIER="${SERVER_JAR%.jar}"       # e.g. synthesis-mcp-server
    CLASSIFIER="${CLASSIFIER#synthesis-}" # e.g. mcp-server

    # Strategy 1: local build output (dev machine or -Source install)
    if [ -n "${SOURCE_DIR:-}" ] && [ -f "$SOURCE_DIR/target/$SERVER_JAR" ]; then
        cp "$SOURCE_DIR/target/$SERVER_JAR" "$SYNTHESIS_HOME/lib/$SERVER_JAR"
        SERVER_JAR_FOUND=true
    fi
    if [ "$SERVER_JAR_FOUND" = false ]; then
        for candidate in "$HOME/src/synthesis" "$HOME/src/exoreaction/synthesis" "$HOME/projects/synthesis" "$(pwd)"; do
            if [ -f "$candidate/target/$SERVER_JAR" ]; then
                cp "$candidate/target/$SERVER_JAR" "$SYNTHESIS_HOME/lib/$SERVER_JAR"
                SERVER_JAR_FOUND=true
                break
            fi
        done
    fi

    # Strategy 2: Cantara Maven (classifier artifact published by build-helper-maven-plugin)
    if [ "$SERVER_JAR_FOUND" = false ] && [ -n "${INSTALLED_VERSION:-}" ]; then
        CLASSIFIER_JAR="synthesis-${INSTALLED_VERSION}-${CLASSIFIER}.jar"
        MAVEN_URL="${CANTARA_RELEASES}/${GROUP_PATH}/${ARTIFACT_ID}/${INSTALLED_VERSION}/${CLASSIFIER_JAR}"
        if url_exists "$MAVEN_URL" 2>/dev/null; then
            if download_file "$MAVEN_URL" "$SYNTHESIS_HOME/lib/$SERVER_JAR" 2>/dev/null; then
                SERVER_JAR_FOUND=true
                info "Downloaded $SERVER_JAR from Cantara Maven"
            fi
        fi
    fi

    if [ "$SERVER_JAR_FOUND" = true ]; then
        info "Installed $SERVER_JAR"
    else
        warn "$SERVER_JAR not found — MCP/LSP features unavailable until manually installed"
    fi
done

# MCP server launcher
cat > "$SYNTHESIS_HOME/bin/synthesis-mcp-server" << 'MCP_LAUNCHER_EOF'
#!/usr/bin/env bash
set -euo pipefail
readonly SYNTHESIS_HOME="${SYNTHESIS_HOME:-$HOME/.synthesis}"
readonly JAR_PATH="$SYNTHESIS_HOME/lib/synthesis-mcp-server.jar"
if [ ! -f "$JAR_PATH" ]; then
    echo "Error: MCP Server JAR not found at $JAR_PATH" >&2
    echo "  Rebuild: cd ~/src/synthesis && mvn package -DskipTests" >&2
    exit 1
fi
if ! command -v java >/dev/null 2>&1; then
    echo "Error: Java not found." >&2; exit 1
fi
exec java ${SYNTHESIS_JAVA_OPTS:-} -jar "$JAR_PATH" "$@"
MCP_LAUNCHER_EOF
chmod +x "$SYNTHESIS_HOME/bin/synthesis-mcp-server"
info "Installed synthesis-mcp-server launcher"

# LSP server launcher
cat > "$SYNTHESIS_HOME/bin/synthesis-lsp-server" << 'LSP_LAUNCHER_EOF'
#!/usr/bin/env bash
set -euo pipefail
readonly SYNTHESIS_HOME="${SYNTHESIS_HOME:-$HOME/.synthesis}"
readonly JAR_PATH="$SYNTHESIS_HOME/lib/synthesis-lsp-server.jar"
if [ ! -f "$JAR_PATH" ]; then
    echo "Error: LSP Server JAR not found at $JAR_PATH" >&2
    echo "  Rebuild: cd ~/src/synthesis && mvn package -DskipTests" >&2
    exit 1
fi
if ! command -v java >/dev/null 2>&1; then
    echo "Error: Java not found." >&2; exit 1
fi
exec java ${SYNTHESIS_JAVA_OPTS:-} -jar "$JAR_PATH" "$@"
LSP_LAUNCHER_EOF
chmod +x "$SYNTHESIS_HOME/bin/synthesis-lsp-server"
info "Installed synthesis-lsp-server launcher"

# Symlink all launchers into ~/bin so they are accessible even if
# ~/.synthesis/bin is not yet on PATH in the current session (#267)
mkdir -p "$USER_BIN_DIR"
for launcher in synthesis synthesis-mcp-server synthesis-lsp-server synthesis-update; do
    if [ -f "$SYNTHESIS_HOME/bin/$launcher" ]; then
        ln -sf "$SYNTHESIS_HOME/bin/$launcher" "$USER_BIN_DIR/$launcher"
    fi
done
info "Symlinked launchers into $USER_BIN_DIR"

# ---------------------------------------------------------------------------
# Step 7: Install Update Script
# ---------------------------------------------------------------------------
step "Installing update script..."

# The update script is maintained as a separate file.
# If installing from source, copy it. Otherwise, download it.
UPDATE_SCRIPT_INSTALLED=false

# Try: copy from same directory as this install script
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
if [ -f "$SCRIPT_DIR/update.sh" ]; then
    cp "$SCRIPT_DIR/update.sh" "$SYNTHESIS_HOME/bin/update.sh"
    chmod +x "$SYNTHESIS_HOME/bin/update.sh"
    UPDATE_SCRIPT_INSTALLED=true
    info "Copied update.sh from source"
fi

# Try: copy from source dir
if [ "$UPDATE_SCRIPT_INSTALLED" = false ] && [ -n "${SOURCE_DIR:-}" ] && [ -f "$SOURCE_DIR/bin/update.sh" ]; then
    cp "$SOURCE_DIR/bin/update.sh" "$SYNTHESIS_HOME/bin/update.sh"
    chmod +x "$SYNTHESIS_HOME/bin/update.sh"
    UPDATE_SCRIPT_INSTALLED=true
    info "Copied update.sh from source directory"
fi

# Try: download from GitHub
if [ "$UPDATE_SCRIPT_INSTALLED" = false ]; then
    if download_file "${GITHUB_RAW}/bin/update.sh" "$SYNTHESIS_HOME/bin/update.sh" 2>/dev/null; then
        chmod +x "$SYNTHESIS_HOME/bin/update.sh"
        UPDATE_SCRIPT_INSTALLED=true
        info "Downloaded update.sh from GitHub"
    fi
fi

# Fallback: create a minimal update script that self-downloads
if [ "$UPDATE_SCRIPT_INSTALLED" = false ]; then
    cat > "$SYNTHESIS_HOME/bin/update.sh" << 'STUB_EOF'
#!/usr/bin/env bash
echo "Update script not yet available. Download it from:"
echo "  https://github.com/exoreaction/Synthesis/blob/main/bin/update.sh"
echo ""
echo "Or rebuild from source:"
echo "  cd ~/src/synthesis && git pull && mvn package -DskipTests"
echo "  cp target/synthesis-*.jar ~/.synthesis/lib/"
exit 1
STUB_EOF
    chmod +x "$SYNTHESIS_HOME/bin/update.sh"
    warn "Update script installed as stub (download full version from GitHub)"
fi

# ---------------------------------------------------------------------------
# Step 7b: Install exo Executive Script
# ---------------------------------------------------------------------------
step "Installing exo executive script..."

# exo is embedded inline here (same pattern as synthesis launcher above).
# Distribution is via Maven/Cantara for the JAR + install.sh for scripts.
# Scripts are never downloaded at runtime -- they are baked into this installer.

USER_BIN_DIR="${HOME}/bin"
mkdir -p "$USER_BIN_DIR"

cat > "$USER_BIN_DIR/exo" << 'EXO_EOF'
#!/usr/bin/env bash
#
# eXOReaction Executive Command
#
# Smart executive tool that wraps Synthesis dashboard and report commands.
#
# Usage:
#   exo                    → interactive dashboard
#   exo report             → weekly CEO briefing
#   exo decisions          → critical decisions needed
#   exo pipeline           → pipeline status
#   exo activities         → recent activities summary
#   exo client <name>      → client status report
#   exo product <name>     → product status report
#   exo <name>             → smart: tries client, then product
#
# Copyright (c) 2026 eXOReaction AS. All rights reserved.

WORKSPACE="${SYNTHESIS_WORKSPACE:-$HOME/Documents}"

case "${1:-}" in
  "")         exec synthesis dashboard ;;
  report)     exec synthesis report -d "$WORKSPACE" -v "${@:2}" ;;
  decisions)  exec synthesis report -d "$WORKSPACE" --topic decisions -v ;;
  pipeline)   exec synthesis report -d "$WORKSPACE" --topic pipeline -v ;;
  activities) exec synthesis report -d "$WORKSPACE" --topic activities -v ;;
  client)     exec synthesis report -d "$WORKSPACE" --client "$2" -v ;;
  product)    exec synthesis report -d "$WORKSPACE" --product "$2" -v ;;
  *)          exec synthesis report -d "$WORKSPACE" --client "$1" -v ;;
esac
EXO_EOF

chmod +x "$USER_BIN_DIR/exo"
info "Installed exo executive command to $USER_BIN_DIR/exo"
detail "Usage: exo               → interactive dashboard"
detail "       exo report        → weekly CEO briefing"
detail "       exo client NAME   → client status report"

# Ensure ~/bin is in PATH
PATH_LINE_BIN='export PATH="$HOME/bin:$PATH"'
if [ -f "$SHELL_RC" ] && grep -q "$USER_BIN_DIR" "$SHELL_RC" 2>/dev/null; then
    true  # Already in PATH
else
    {
        echo ""
        echo "# User local bin (added by Synthesis installer)"
        echo "$PATH_LINE_BIN"
    } >> "$SHELL_RC"
    export PATH="$USER_BIN_DIR:$PATH"
fi

# ---------------------------------------------------------------------------
# Step 8: Save Metadata
# ---------------------------------------------------------------------------
step "Saving installation metadata..."

echo "$INSTALLED_VERSION" > "$SYNTHESIS_HOME/.metadata/version"
echo "$(date -Iseconds)" > "$SYNTHESIS_HOME/.metadata/install-date"
echo "$(date +%s)" > "$SYNTHESIS_HOME/.metadata/last-update-check"
detect_os > "$SYNTHESIS_HOME/.metadata/os"

# Save source directory if we auto-detected it
if [ -n "${SOURCE_DIR:-}" ] && [ ! -f "$SYNTHESIS_HOME/.metadata/source-dir" ]; then
    echo "$SOURCE_DIR" > "$SYNTHESIS_HOME/.metadata/source-dir"
fi

info "Version: $INSTALLED_VERSION"
info "Installed: $(date)"

# Write installation fingerprint
step "Writing installation fingerprint..."

FINGERPRINT="$SYNTHESIS_HOME/.installation.json"
HAS_MCP=$([ -f "$SYNTHESIS_HOME/lib/synthesis-mcp-server.jar" ] && echo "true" || echo "false")
HAS_LSP=$([ -f "$SYNTHESIS_HOME/lib/synthesis-lsp-server.jar" ] && echo "true" || echo "false")
INSTALL_METHOD="installer"
INSTALL_SRC=""
if [ -n "${SOURCE_DIR:-}" ]; then
    INSTALL_METHOD="source"
    INSTALL_SRC="source-build"
else
    INSTALL_SRC="unknown"
fi

cat > "$FINGERPRINT" <<FPEOF
{
  "version": "$INSTALLED_VERSION",
  "installDate": "$(date -Iseconds)",
  "lastUpdateDate": null,
  "installMethod": "$INSTALL_METHOD",
  "installSource": "$INSTALL_SRC",
  "sourceDirectory": "${SOURCE_DIR:-}",
  "components": {
    "synthesis-cli": { "installed": true, "version": "$INSTALLED_VERSION", "installedDate": "$(date -Iseconds)" },
    "synthesis-mcp-server": { "installed": $HAS_MCP, "version": "$INSTALLED_VERSION", "installedDate": "$(date -Iseconds)" },
    "synthesis-lsp-server": { "installed": $HAS_LSP, "version": "$INSTALLED_VERSION", "installedDate": "$(date -Iseconds)" },
    "launcher-synthesis": { "installed": true, "version": "$INSTALLED_VERSION", "installedDate": "$(date -Iseconds)" },
    "launcher-mcp-server": { "installed": true, "version": "$INSTALLED_VERSION", "installedDate": "$(date -Iseconds)" },
    "launcher-lsp-server": { "installed": true, "version": "$INSTALLED_VERSION", "installedDate": "$(date -Iseconds)" },
    "update-script": { "installed": true, "version": "$INSTALLED_VERSION", "installedDate": "$(date -Iseconds)" }
  }
}
FPEOF
info "Installation fingerprint created"

# ---------------------------------------------------------------------------
# Step 9: Shell Integration
# ---------------------------------------------------------------------------
step "Setting up shell integration..."

SHELL_RC=$(get_shell_rc)
MARKER="# Synthesis - AI operations partner"
PATH_LINE='export PATH="$HOME/.synthesis/bin:$PATH"'

if [ -f "$SHELL_RC" ] && grep -q "$MARKER" "$SHELL_RC" 2>/dev/null; then
    info "Shell integration already present in $SHELL_RC"
else
    {
        echo ""
        echo "$MARKER"
        echo "$PATH_LINE"
    } >> "$SHELL_RC"
    info "Added to PATH in $SHELL_RC"
fi

# Also create synthesis-update convenience symlink
(cd "$SYNTHESIS_HOME/bin" && ln -sf update.sh synthesis-update)

# Add to current session PATH
export PATH="$SYNTHESIS_HOME/bin:$PATH"

# ---------------------------------------------------------------------------
# Step 10: Verify Installation
# ---------------------------------------------------------------------------
step "Verifying installation..."

# Check JAR is valid
if java -jar "$SYNTHESIS_HOME/lib/current.jar" --version >/dev/null 2>&1; then
    VERIFIED_VERSION=$(java -jar "$SYNTHESIS_HOME/lib/current.jar" --version 2>&1 || true)
    info "Verification passed: $VERIFIED_VERSION"
else
    warn "JAR verification skipped (may need terminal restart)"
fi

# ---------------------------------------------------------------------------
# Done!
# ---------------------------------------------------------------------------
CLEANUP_ON_FAIL=false

# ---------------------------------------------------------------------------
# Step 11: Check Optional Dependencies
# ---------------------------------------------------------------------------
step "Checking optional dependencies..."

if command_exists ffprobe; then
    FFPROBE_VER=$(ffprobe -version 2>&1 | head -1 | sed 's/ffprobe version //' | cut -d' ' -f1 | cut -d'-' -f1)
    info "ffprobe detected (version $FFPROBE_VER) - full video metadata support"
else
    detail "ffprobe not found (optional, for video metadata)"
    detail "  Synthesis works without it for MP4, MOV, AVI (~90% of videos)"
    detail "  For MKV/WebM support, install ffmpeg:"
    OS_TYPE=$(detect_os)
    if [ "$OS_TYPE" = "macos" ]; then
        detail "    brew install ffmpeg"
    else
        detail "    sudo apt install ffmpeg  (or sudo dnf install ffmpeg)"
    fi
fi

printf "\n"
printf "${GREEN}${BOLD}Synthesis installed successfully!${NC}\n"
printf "\n"
printf "  ${BOLD}Installation:${NC}  %s\n" "$SYNTHESIS_HOME"
printf "  ${BOLD}Version:${NC}       %s\n" "$INSTALLED_VERSION"
printf "  ${BOLD}Launcher:${NC}      %s\n" "$SYNTHESIS_HOME/bin/synthesis"
printf "  ${BOLD}JAR:${NC}           %s\n" "$SYNTHESIS_HOME/lib/$JAR_NAME"
printf "\n"
printf "${YELLOW}To get started, open a new terminal (or run: source %s) and then:${NC}\n" "$SHELL_RC"
printf "\n"
printf "  ${CYAN}synthesis --help${NC}                  # Show all commands\n"
printf "  ${CYAN}synthesis init ~/my-project${NC}        # Initialize a workspace\n"
printf "  ${CYAN}synthesis scan${NC}                     # Scan and index files\n"
printf "  ${CYAN}synthesis search \"query\"${NC}            # Search your workspace\n"
printf "\n"
printf "  ${CYAN}synthesis-update${NC}                   # Update to latest version\n"
printf "  ${CYAN}synthesis-update --check${NC}            # Check for updates\n"
printf "\n"
