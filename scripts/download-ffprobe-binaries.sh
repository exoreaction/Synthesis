#!/bin/bash
# Downloads ffprobe static binaries for all supported platforms.
#
# Usage:
#   ./scripts/download-ffprobe-binaries.sh
#
# Binaries are placed in src/main/resources/binaries/<platform>/
# and will be bundled into the JAR at build time.
#
# Sources:
#   Linux:   https://johnvansickle.com/ffmpeg/ (static builds)
#   macOS:   https://evermeet.cx/ffmpeg/ (universal builds)
#   Windows: https://www.gyan.dev/ffmpeg/builds/ (release essentials)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BINARIES_DIR="$PROJECT_ROOT/src/main/resources/binaries"
TMP_DIR="$(mktemp -d)"

cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

echo "=== Downloading ffprobe binaries ==="
echo "Target: $BINARIES_DIR"
echo ""

# Create target directories
mkdir -p "$BINARIES_DIR"/{linux-x64,darwin-universal,windows-x64}

# ---- Linux x64 (static build from johnvansickle.com) ----
echo "[1/3] Downloading Linux x64 ffprobe..."
LINUX_URL="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz"
LINUX_ARCHIVE="$TMP_DIR/ffmpeg-linux.tar.xz"

if curl -L --fail --progress-bar -o "$LINUX_ARCHIVE" "$LINUX_URL"; then
    echo "  Extracting ffprobe from archive..."
    # The archive contains a directory like ffmpeg-7.1-amd64-static/
    tar xf "$LINUX_ARCHIVE" -C "$TMP_DIR"
    LINUX_FFPROBE=$(find "$TMP_DIR" -name "ffprobe" -type f | head -1)
    if [ -n "$LINUX_FFPROBE" ]; then
        cp "$LINUX_FFPROBE" "$BINARIES_DIR/linux-x64/ffprobe"
        chmod +x "$BINARIES_DIR/linux-x64/ffprobe"
        SIZE=$(du -sh "$BINARIES_DIR/linux-x64/ffprobe" | cut -f1)
        echo "  OK: linux-x64/ffprobe ($SIZE)"
    else
        echo "  ERROR: ffprobe not found in Linux archive"
    fi
else
    echo "  ERROR: Failed to download Linux binary"
    echo "  Manual download: $LINUX_URL"
fi

echo ""

# ---- macOS (universal binary from evermeet.cx) ----
echo "[2/3] Downloading macOS universal ffprobe..."
echo "  NOTE: macOS binaries from evermeet.cx may require manual download."
echo "  Visit: https://evermeet.cx/ffmpeg/"
echo ""
echo "  Alternative: Build from Homebrew on macOS:"
echo "    brew install ffmpeg"
echo "    cp \$(which ffprobe) $BINARIES_DIR/darwin-universal/ffprobe"
echo ""

# Try evermeet.cx (may not always work due to download restrictions)
MACOS_URL="https://evermeet.cx/ffmpeg/ffprobe-7.1.1.zip"
MACOS_ARCHIVE="$TMP_DIR/ffprobe-macos.zip"

if curl -L --fail --progress-bar -o "$MACOS_ARCHIVE" "$MACOS_URL" 2>/dev/null; then
    echo "  Extracting ffprobe from archive..."
    unzip -o -q "$MACOS_ARCHIVE" -d "$TMP_DIR/macos"
    MACOS_FFPROBE=$(find "$TMP_DIR/macos" -name "ffprobe" -type f | head -1)
    if [ -n "$MACOS_FFPROBE" ]; then
        cp "$MACOS_FFPROBE" "$BINARIES_DIR/darwin-universal/ffprobe"
        chmod +x "$BINARIES_DIR/darwin-universal/ffprobe"
        SIZE=$(du -sh "$BINARIES_DIR/darwin-universal/ffprobe" | cut -f1)
        echo "  OK: darwin-universal/ffprobe ($SIZE)"
    else
        echo "  WARNING: ffprobe not found in macOS archive"
    fi
else
    echo "  WARNING: Automatic download failed. Please download manually."
fi

echo ""

# ---- Windows x64 (from gyan.dev) ----
echo "[3/3] Downloading Windows x64 ffprobe..."
WINDOWS_URL="https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
WINDOWS_ARCHIVE="$TMP_DIR/ffmpeg-windows.zip"

if curl -L --fail --progress-bar -o "$WINDOWS_ARCHIVE" "$WINDOWS_URL"; then
    echo "  Extracting ffprobe.exe from archive..."
    # Extract only ffprobe.exe from the archive
    unzip -j -o -q "$WINDOWS_ARCHIVE" "*/bin/ffprobe.exe" -d "$BINARIES_DIR/windows-x64/" 2>/dev/null || {
        # Try alternative extraction
        unzip -o -q "$WINDOWS_ARCHIVE" -d "$TMP_DIR/windows"
        WINDOWS_FFPROBE=$(find "$TMP_DIR/windows" -name "ffprobe.exe" -type f | head -1)
        if [ -n "$WINDOWS_FFPROBE" ]; then
            cp "$WINDOWS_FFPROBE" "$BINARIES_DIR/windows-x64/ffprobe.exe"
        fi
    }
    if [ -f "$BINARIES_DIR/windows-x64/ffprobe.exe" ]; then
        SIZE=$(du -sh "$BINARIES_DIR/windows-x64/ffprobe.exe" | cut -f1)
        echo "  OK: windows-x64/ffprobe.exe ($SIZE)"
    else
        echo "  ERROR: ffprobe.exe not found in Windows archive"
    fi
else
    echo "  ERROR: Failed to download Windows binary"
    echo "  Manual download: $WINDOWS_URL"
fi

echo ""
echo "=== Summary ==="
echo ""

# Show results
for platform in linux-x64 darwin-universal windows-x64; do
    if [ "$platform" = "windows-x64" ]; then
        binary="ffprobe.exe"
    else
        binary="ffprobe"
    fi

    if [ -f "$BINARIES_DIR/$platform/$binary" ]; then
        SIZE=$(du -sh "$BINARIES_DIR/$platform/$binary" | cut -f1)
        echo "  $platform/$binary: $SIZE"
    else
        echo "  $platform/$binary: MISSING"
    fi
done

echo ""
echo "Next steps:"
echo "  1. Verify binaries work: $BINARIES_DIR/linux-x64/ffprobe -version"
echo "  2. Build: mvn package"
echo "  3. The JAR will include all downloaded binaries."
echo ""
echo "Note: Binaries are NOT committed to Git. Run this script after each fresh clone."
