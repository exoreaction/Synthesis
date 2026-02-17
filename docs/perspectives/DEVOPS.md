# Synthesis for DevOps & Platform Engineering

**Your CI/CD pipeline validates code. Synthesis validates knowledge. Automate both.**

---

## What This Adds to Your Stack

Synthesis is a CLI tool that can be integrated into CI/CD pipelines, git hooks, scheduled jobs, and monitoring workflows. It indexes code and documentation, tracks changes, and provides real-time search and dependency analysis.

For DevOps, the key capabilities are:
- **Automated index maintenance** in CI/CD
- **Change tracking** across workspaces
- **File staging** for incoming content
- **MCP and LSP server modes** for IDE and AI agent integration
- **Credentials management** without environment variable sprawl
- **Edition-based feature gating** (core/air-gapped vs pro/cloud)

---

## Credentials Management

Store API keys locally without environment variables:

```bash
synthesis credentials set ANTHROPIC_API_KEY sk-ant-api03-...
synthesis credentials status
synthesis credentials clear ANTHROPIC_API_KEY
```

**How it works:**
- Stored in `~/.synthesis/credentials` with XOR obfuscation (keyed to machine UUID)
- File permissions set to 600 automatically
- Prevents accidental exposure in logs, screen sharing, or casual browsing
- No master password required

**Resolution order:**
1. Environment variable (highest priority, overrides credential store)
2. Credential store (`~/.synthesis/credentials`)
3. Not found (AI features disabled)

This means you can use environment variables in CI/CD and credential store on developer machines.

---

## CI/CD Integration

### GitHub Actions: Knowledge Validation

```yaml
# .github/workflows/knowledge-check.yml
name: Knowledge Infrastructure

on: [push, pull_request]

jobs:
  knowledge-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Java
        uses: actions/setup-java@v4
        with:
          java-version: '17'

      - name: Install Synthesis
        run: |
          curl -L https://github.com/exoreaction/Synthesis/releases/latest/download/synthesis.jar \
            -o /usr/local/bin/synthesis.jar
          echo '#!/bin/bash' > /usr/local/bin/synthesis
          echo 'exec java -jar /usr/local/bin/synthesis.jar "$@"' >> /usr/local/bin/synthesis
          chmod +x /usr/local/bin/synthesis

      - name: Index codebase
        run: synthesis init && synthesis scan

      - name: Architecture check
        run: |
          synthesis architecture --format json > architecture-report.json
          # Fail if errors found
          errors=$(cat architecture-report.json | python3 -c "import json,sys; print(json.load(sys.stdin).get('errorCount',0))")
          if [ "$errors" -gt 0 ]; then
            echo "Architecture errors detected"
            synthesis architecture --severity error
            exit 1
          fi

      - name: Upload reports
        uses: actions/upload-artifact@v4
        with:
          name: knowledge-reports
          path: architecture-report.json
```

### Jenkins Pipeline

```groovy
pipeline {
    agent any
    stages {
        stage('Knowledge Check') {
            steps {
                sh 'synthesis init && synthesis scan'
                sh 'synthesis architecture --format json > arch-report.json'
                sh 'synthesis insights > codebase-health.txt'
                archiveArtifacts 'arch-report.json,codebase-health.txt'
            }
        }
    }
}
```

---

## Automated Index Maintenance

### Watch Mode (Development)

```bash
synthesis watch
```

Monitors the workspace for file changes and automatically updates the index. Runs in the foreground. Suitable for development machines or dedicated index servers.

### Scheduled Refresh (Production)

**With cron:**

```bash
# Re-index every 4 hours during work hours
0 8,12,16,20 * * 1-5 cd /opt/codebase && synthesis scan >> /var/log/synthesis.log 2>&1
```

**With systemd timer:**

```ini
# /etc/systemd/system/synthesis-scan.timer
[Unit]
Description=Synthesis index refresh

[Timer]
OnCalendar=*-*-* 8,12,16,20:00:00
Persistent=true

[Install]
WantedBy=timers.target
```

```ini
# /etc/systemd/system/synthesis-scan.service
[Unit]
Description=Synthesis scan

[Service]
Type=oneshot
WorkingDirectory=/opt/codebase
ExecStart=/usr/local/bin/synthesis maintain
User=synthesis
Group=synthesis
```

### Git Hooks

```bash
# .git/hooks/post-commit
#!/bin/bash
synthesis maintain --quiet &
```

```bash
# .git/hooks/pre-push
#!/bin/bash
synthesis scan
```

### Maintain vs Scan

| Command | What it does | When to use |
|---------|-------------|-------------|
| `synthesis scan` | Full or incremental index rebuild | After install, after major changes |
| `synthesis maintain` | Detect changes, update index, track file movements | Automation, scheduled jobs, hooks |

`maintain` also triggers file movement tracking (hash-based detection with 7-day safety period).

---

## Change Tracking

### Cross-Workspace Change Reporting

```bash
synthesis changelog
```

Generates a cross-workspace change report using daily snapshots. Shows what changed across all tracked workspaces since the last snapshot.

### File Movement Tracking

```bash
synthesis track                     # Show tracked file movements
synthesis track --status pending    # Show movements pending confirmation
```

File movement tracking uses content hashing to detect when files are moved or renamed. Movements have a 7-day safety period before being automatically confirmed.

### Changed Files

```bash
synthesis changed --since 2026-02-01    # Files changed since a date
synthesis diff HEAD~5                     # Changes in last 5 commits
synthesis diff main..feature-branch       # Changes between branches
```

---

## Staging Workflow

For managing incoming files (downloads, imports, external content):

```bash
synthesis staging                    # Show staging status
synthesis staging ingest <path>      # Move files into staging area
synthesis staging promote <path>     # Promote from staging to workspace
synthesis staging expire             # Remove expired staging files
```

Useful for automating content intake pipelines.

---

## MCP Server Integration

Synthesis can run as an MCP (Model Context Protocol) server, enabling Claude Desktop, Claude Code, and other MCP-compatible clients to use Synthesis tools directly.

**Configuration for Claude Desktop (`claude_desktop_config.json`):**

```json
{
  "mcpServers": {
    "synthesis": {
      "command": "synthesis",
      "args": ["mcp", "server"],
      "env": {
        "SYNTHESIS_WORKSPACE": "/path/to/workspace"
      }
    }
  }
}
```

**Available MCP tools:**
- `synthesis_search` -- Search across indexed files
- `synthesis_relate` -- Show file relationships
- `synthesis_graph` -- Generate dependency graphs
- `synthesis_status` -- Workspace health
- `synthesis_ask` -- AI-powered Q&A

See [MCP Quick Start](../guides/MCP-QUICKSTART.md) and [MCP Comprehensive Guide](../guides/MCP-COMPREHENSIVE-GUIDE.md) for full details.

---

## LSP Server Integration

Synthesis provides an LSP (Language Server Protocol) server for IDE integration.

**Features:**
- Architecture alerts as diagnostics (warnings/errors in Problems panel)
- Hover information with dependency context
- Code actions for relationship analysis

See [LSP Quick Start](../guides/LSP-QUICKSTART.md) and [LSP IDE Integration Guides](../guides/LSP-IDE-INTEGRATION-GUIDES.md) for per-IDE setup.

---

## Docker Deployment

```dockerfile
FROM eclipse-temurin:17-jre

RUN curl -L https://github.com/exoreaction/Synthesis/releases/latest/download/synthesis.jar \
    -o /usr/local/bin/synthesis.jar && \
    echo '#!/bin/bash\nexec java -jar /usr/local/bin/synthesis.jar "$@"' > /usr/local/bin/synthesis && \
    chmod +x /usr/local/bin/synthesis

WORKDIR /workspace
VOLUME ["/workspace"]

ENTRYPOINT ["synthesis"]
CMD ["status"]
```

```bash
docker build -t synthesis:latest .
docker run -v /path/to/codebase:/workspace synthesis:latest scan
docker run -v /path/to/codebase:/workspace synthesis:latest search "authentication"
```

---

## Editions and Feature Gating

| Edition | Set via | AI | Telemetry | Update checks |
|---------|---------|-----|-----------|--------------|
| `core` | `SYNTHESIS_EDITION=core` | Disabled | Disabled | Disabled |
| `pro` | Default | Enabled | Enabled | Enabled |
| `enterprise` | `SYNTHESIS_EDITION=enterprise` | Disabled | Disabled | Disabled |
| `ultimate` | `SYNTHESIS_EDITION=ultimate` | Enabled | Enabled | Enabled |

Air-gapped editions (`core`, `enterprise`) disable the `ask` and `perspectives` commands entirely. Use `core` for environments with no internet access.

---

## Environment Variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `SYNTHESIS_WORKSPACE` | Default workspace root | Current directory |
| `SYNTHESIS_EDITION` | Feature edition | `pro` |
| `ANTHROPIC_API_KEY` | AI API key (overrides credential store) | Not set |
| `NO_COLOR` | Disable terminal colors | Not set |

---

## Security Model

### Local-First Architecture

- All indexing and search happens locally
- No network calls for core commands (scan, search, relate, graph, architecture, insights)
- Index stored in `.synthesis/` (local filesystem)
- Credentials stored in `~/.synthesis/credentials` (chmod 600, obfuscated)

### AI Features (Opt-In)

- Requires explicit `ai.enabled: true` in config
- Requires API key (credential store or environment variable)
- Only content needed for the specific request is sent
- No background data collection

### Recommendations

- Add `.synthesis/` to `.gitignore`
- Use `SYNTHESIS_EDITION=core` for air-gapped environments
- Protect credential store file permissions (automatically set to 600)
- Use environment variables for API keys in CI/CD (not credential store)

---

## Performance Tuning

### Large Codebases (10,000+ files)

```yaml
# .synthesis/config.yaml
scan:
  excludePatterns:
    - "**/node_modules/**"
    - "**/target/**"
    - "**/build/**"
    - "**/.venv/**"
    - "**/dist/**"
  maxFileSizeBytes: 10485760    # 10 MB default
```

### JVM Tuning

```bash
java -Xmx1g -jar synthesis.jar scan    # Limit heap to 1 GB
```

### Watch Mode (Linux)

If `watch` misses changes, increase the inotify limit:

```bash
echo 'fs.inotify.max_user_watches=524288' | sudo tee -a /etc/sysctl.conf
sudo sysctl -p
```

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Search finds nothing | Files not in includePatterns | Update `.synthesis/config.yaml` |
| Scan is slow | Including build artifacts | Add to excludePatterns |
| Watch misses changes | inotify limit (Linux) | Increase `fs.inotify.max_user_watches` |
| Out of memory | Very large files | Reduce `maxFileSizeBytes` or increase JVM heap |
| "Not a Synthesis workspace" | Missing `.synthesis/` | Run `synthesis init` |
| AI features not working | No API key | `synthesis credentials set ANTHROPIC_API_KEY sk-ant-...` |

---

## Quick Reference

```
synthesis maintain                          # Incremental update + movement tracking
synthesis watch                             # Real-time monitoring
synthesis scan                              # Full/incremental index build
synthesis changelog                         # Cross-workspace change report
synthesis changed --since 2026-02-01        # Files changed since date
synthesis track                             # File movement tracking
synthesis staging                           # Staging area management
synthesis credentials set KEY value         # Store credential
synthesis credentials status                # Check credentials
synthesis credentials clear KEY             # Remove credential
synthesis architecture --format json        # Machine-readable quality check
synthesis update                            # Update Synthesis
synthesis update --check                    # Check for updates
synthesis update --health                   # Installation health check
synthesis status                            # Workspace health
synthesis list                              # All workspaces
```

---

**Related guides:**
- [MCP Quick Start](../guides/MCP-QUICKSTART.md) -- AI agent integration
- [LSP Quick Start](../guides/LSP-QUICKSTART.md) -- IDE integration
- [Developer Guide](./DEVELOPER.md) -- for your development team
- [Architect Guide](./ARCHITECT.md) -- architecture analysis
- [Full User Guide](../USER-GUIDE-V2.md) -- complete command reference
