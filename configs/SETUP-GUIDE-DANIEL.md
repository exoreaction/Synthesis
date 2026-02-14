# Synthesis Setup Guide for Synapti Marketplace

## Quick Setup (5 minutes)

### 1. Install Synthesis

```bash
# Copy the JAR to a permanent location
cp synthesis-1.0.0-SNAPSHOT.jar ~/tools/synthesis.jar

# Create an alias (add to ~/.bashrc or ~/.zshrc)
alias synthesis='java -jar ~/tools/synthesis.jar'
```

### 2. Initialize Your Workspace

```bash
cd ~/path/to/synapti-marketplace

# Initialize with the plugin-ecosystem preset
synthesis init . --name "Synapti Marketplace" --type "plugin-ecosystem"

# Copy the optimized config
cp /path/to/configs/synapti-marketplace.yaml .synthesis/config.yaml
```

### 3. First Scan

```bash
# Scan and index all files
synthesis scan

# Verify
synthesis status
```

### 4. Start Searching

```bash
# Find files about authentication
synthesis search "authentication"

# Find all Java code related to plugins
synthesis search "plugin" --type CODE

# Find YAML configuration files
synthesis search "database" --type YAML

# Find documentation about APIs
synthesis search "API" --type MARKDOWN
```

## Daily Workflow

### Quick Update (10 seconds)

After making changes to the codebase:

```bash
# Detect and apply changes incrementally
synthesis maintain
```

### Search Examples

```bash
# Find where a class is used
synthesis search "PluginRegistry"

# Find test files
synthesis search "test" --type CODE

# Find deployment configs
synthesis search "deployment" --type YAML

# Find documentation about a specific feature
synthesis search "marketplace" --type MARKDOWN

# Cross-plugin search: find all files mentioning a dependency
synthesis search "spring-boot-starter"
```

### Export for Team

```bash
# Generate a workspace overview
synthesis export --format markdown --output workspace-overview.md

# Export as JSON for tooling integration
synthesis export --format json --output workspace-index.json

# Export only code files
synthesis export --type CODE --format json
```

## Plugin-Specific Searches

For a plugin marketplace with multiple plugins as submodules:

```bash
# Search within a specific plugin (by path)
synthesis search "authentication" | grep "plugin-a"

# Find cross-plugin dependencies
synthesis search "import.*plugin" --type CODE

# Find all README files
synthesis search "README" --type MARKDOWN

# Find configuration differences
synthesis search "database.url" --type YAML
```

## Keeping the Index Fresh

### Option 1: Manual (Recommended to Start)

```bash
# After significant changes
synthesis maintain
```

### Option 2: Git Hook

Add to `.git/hooks/post-checkout`:

```bash
#!/bin/bash
synthesis maintain 2>/dev/null
```

### Option 3: Cron Job

```bash
# Every 30 minutes
*/30 * * * * cd ~/synapti && java -jar ~/tools/synthesis.jar maintain 2>/dev/null
```

## Troubleshooting

### "Not a Synthesis workspace" Error

```bash
synthesis init .  # Re-initialize
synthesis scan    # Rebuild index
```

### Stale Search Results

```bash
synthesis scan --full  # Full rebuild of index
```

### Large Workspace (1000+ files)

Synthesis is optimized for speed. If scan takes too long:
1. Check `.synthesis/config.yaml` exclude patterns
2. Reduce `maxFileSizeBytes` for faster scanning
3. Use `synthesis maintain` instead of `synthesis scan` for updates

## Support

Questions or issues? Contact Thor Henning (Totto) at eXOReaction.
