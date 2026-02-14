# Synthesis Setup Guide for Synapti Plugin Marketplace

**Prepared for:** Daniel Bentes, Synapti
**Version:** 1.0 | February 2026
**Time to setup:** ~5 minutes

---

## Quick Setup (5 minutes)

### Step 1: Get Synthesis (30 seconds)

```bash
# Create tools directory if it doesn't exist
mkdir -p ~/tools

# Copy the Synthesis JAR
cp synthesis-1.0.0-SNAPSHOT.jar ~/tools/synthesis.jar

# Create a shell alias (add to ~/.bashrc or ~/.zshrc)
echo 'alias synthesis="java -jar ~/tools/synthesis.jar"' >> ~/.bashrc
source ~/.bashrc
```

**Verify:** `synthesis --version` should print `Synthesis 1.0.0-SNAPSHOT`.

### Step 2: Initialize Your Workspace (60 seconds)

```bash
# Navigate to your Synapti marketplace root
cd ~/synapti-marketplace   # adjust to your actual path

# Initialize with interactive setup
synthesis init .

# When prompted:
#   Workspace name: Synapti Marketplace
#   Confirm: yes
```

**What happens:** Creates a `.synthesis/` directory with config and index.

### Step 3: Copy the Optimized Config (15 seconds)

```bash
# Copy the plugin-ecosystem optimized configuration
cp /path/to/configs/synapti-marketplace.yaml .synthesis/config.yaml
```

This config is pre-tuned for plugin marketplaces: includes all source code types, excludes build artifacts, and supports cross-plugin search patterns.

### Step 4: Scan Your Workspace (60 seconds)

```bash
# Full scan - indexes all files
synthesis scan

# Verify everything looks right
synthesis status
```

**Expected output:** File count, index size, categories breakdown.

### Step 5: Discover Organizational Structure (60 seconds)

```bash
# Auto-discover plugins, shared libraries, etc.
synthesis org scan

# View what was found
synthesis org list --show-clients
```

### Step 6: Generate Claude Code Skills (60 seconds)

```bash
# Generate workspace knowledge as Claude Code skills
synthesis learn

# Install globally (available in all Claude Code sessions)
synthesis learn --install
```

**What this does:** Creates YAML skill files that teach Claude Code about your plugin structure, so it knows where things are without you telling it.

---

## Daily Workflow

### Morning: Quick Index Update

```bash
# After pulling latest changes
synthesis maintain
```

This incrementally updates the index -- only processes files that changed. Takes seconds, not minutes.

### During Development: Search

```bash
# Find anything by keyword
synthesis search "authentication"

# Find code files only
synthesis search "PluginRegistry" --type CODE

# Find configuration
synthesis search "database" --type YAML

# Find documentation
synthesis search "API" --type MARKDOWN
```

### Background: Watch Mode (Optional)

```bash
# Auto-update index as files change
synthesis watch

# With skill regeneration (updates Claude Code knowledge too)
synthesis watch --learn
```

Leave this running in a terminal tab. Every file save updates the index automatically.

### End of Day: Export Overview

```bash
# Generate a workspace overview for the team
synthesis export --format markdown --output workspace-overview.md
```

---

## Plugin-Specific Searches

Your plugin marketplace has unique search needs. Here are the patterns that work best:

### Cross-Plugin Discovery

```bash
# Find where a shared interface is implemented across plugins
synthesis search "PluginInterface"

# Find all plugin manifests
synthesis search "plugin.yaml" --type YAML

# Find shared dependencies across plugins
synthesis search "spring-boot-starter" --type YAML

# Find all entry points
synthesis search "main" --type CODE
```

### Plugin-Specific Searches

```bash
# Search within a specific plugin (filter by path)
synthesis search "authentication" | grep "plugin-auth"

# Find all tests for a specific plugin
synthesis search "test" --type CODE | grep "plugin-payments"

# Compare configurations across plugins
synthesis search "database.url" --type YAML
```

### Cross-Plugin Dependencies

```bash
# Find import statements referencing other plugins
synthesis search "import.*plugin" --type CODE

# Find shared utility usage
synthesis search "shared-utils" --type CODE

# Discover cross-references in documentation
synthesis search "depends on" --type MARKDOWN
```

### Architecture Discovery

```bash
# Find all README files (plugin documentation)
synthesis search "README" --type MARKDOWN

# Find deployment configurations
synthesis search "deployment" --type YAML

# Find API definitions
synthesis search "openapi\|swagger" --type YAML

# Get project analysis
synthesis analyze
```

---

## Sharing with Your Team

### Export for Team Review

```bash
# Full workspace overview (Markdown)
synthesis export --format markdown --output workspace-overview.md

# Machine-readable format for tooling
synthesis export --format json --output workspace-index.json

# Code files only
synthesis export --type CODE --format json --output code-index.json
```

### Team Onboarding

When a new team member joins:

1. They clone the repo
2. Run `synthesis init . && synthesis scan`
3. Copy the shared config: `cp configs/synapti-marketplace.yaml .synthesis/config.yaml`
4. Run `synthesis learn --install` for Claude Code integration
5. They are immediately productive with search and navigation

### Git Integration

Add `.synthesis/` to `.gitignore` (indexes are local). Share the config file:

```bash
# In .gitignore
.synthesis/index/
.synthesis/skills/
.synthesis/organizations.json

# BUT share the config
!.synthesis/config.yaml
```

---

## Troubleshooting

### "Not a Synthesis workspace"

```bash
# Re-initialize
synthesis init .
synthesis scan
```

### Stale search results

```bash
# Full rebuild (not incremental)
synthesis scan
```

### Search returns too many results

```bash
# Use type filters
synthesis search "config" --type YAML          # Only YAML files
synthesis search "handler" --type CODE         # Only code files
synthesis search "setup" --type MARKDOWN       # Only docs
```

### Large workspace (1000+ files)

Synthesis handles this well. If scans feel slow:

1. Check exclude patterns in `.synthesis/config.yaml`
2. Ensure `node_modules/`, `target/`, `build/` are excluded
3. Use `synthesis maintain` for incremental updates (much faster than full scan)
4. Use `synthesis watch` for real-time updates

### Plugin submodules not indexed

Make sure submodules are checked out:

```bash
git submodule update --init --recursive
synthesis scan   # Re-scan to pick up submodule contents
```

### Claude Code skills not loading

```bash
# Verify installation
ls ~/.claude/skills/

# Reinstall
synthesis learn --install

# Check skill content
synthesis learn   # Shows what was generated
```

---

## Quick Command Reference

| Task | Command |
|------|---------|
| First setup | `synthesis init . && synthesis scan` |
| Update index | `synthesis maintain` |
| Search anything | `synthesis search "query"` |
| Code only | `synthesis search "query" --type CODE` |
| Watch mode | `synthesis watch --learn` |
| Workspace health | `synthesis status` |
| Organization view | `synthesis org list` |
| Export overview | `synthesis export --format markdown` |
| Generate skills | `synthesis learn --install` |

---

## Support

Questions, issues, or feature requests? Contact Thor Henning (Totto) at eXOReaction.

- **Email:** [totto@exoreaction.com]
- **LinkedIn:** Thor Henning Hetland
- **In person:** Rebel Makerspace, Oslo
