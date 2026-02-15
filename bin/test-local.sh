#!/bin/bash
#
# test-local.sh - Build and install for local testing
#
# Ensures you're always testing the latest code during development.
# Usage: ./bin/test-local.sh [--skip-tests]
#

set -e

cd "$(dirname "$0")/.."

echo "================================"
echo "  Synthesis Local Testing Build"
echo "================================"
echo

# Check if we should skip tests
SKIP_TESTS=""
if [[ "$1" == "--skip-tests" ]]; then
    SKIP_TESTS="-DskipTests"
    echo "⏭️  Skipping tests (--skip-tests specified)"
fi

# Show current branch
BRANCH=$(git branch --show-current)
echo "📍 Branch: $BRANCH"

# Show git status
if [[ -n $(git status --porcelain) ]]; then
    echo "⚠️  Uncommitted changes present"
    git status --short
else
    echo "✅ Working directory clean"
fi
echo

# Build
echo "🔨 Building..."
if mvn clean package $SKIP_TESTS -q; then
    VERSION=$(grep -m 1 '<version>' pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
    echo "✅ Build successful: $VERSION"
else
    echo "❌ Build failed"
    exit 1
fi
echo

# Install to ~/.synthesis
echo "📦 Installing to ~/.synthesis/lib/..."
cp target/synthesis-$VERSION.jar ~/.synthesis/lib/
cp target/synthesis-mcp-server.jar ~/.synthesis/lib/
cp target/synthesis-lsp-server.jar ~/.synthesis/lib/
cd ~/.synthesis/lib
ln -sf synthesis-$VERSION.jar current.jar
echo "✅ Installed: current.jar -> synthesis-$VERSION.jar"
echo

# Verify
echo "🔍 Verifying installation..."
INSTALLED_VERSION=$(synthesis --version 2>&1 | grep -oP 'Synthesis \K[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?')
echo "✅ Running version: $INSTALLED_VERSION"
echo

echo "================================"
echo "✅ Ready for testing!"
echo "================================"
echo
echo "Try:"
echo "  synthesis status"
echo "  synthesis status --all"
echo "  synthesis list"
echo
