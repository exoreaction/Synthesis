# Synthesis for DevOps & Platform Engineering

**Your CI/CD pipeline validates code. Synthesis validates knowledge. Automate both.**

---

## What This Adds to Your Stack

Synthesis is a CLI tool that can be integrated into CI/CD pipelines, git hooks, scheduled jobs, and monitoring workflows. It indexes code and documentation, tracks changes, and provides real-time search and dependency analysis.

For DevOps, the key capabilities are:
- **Automated index maintenance** in CI/CD
- **Self-organizing workspace management** (sync, sweep, rebalance)
- **Workspace health diagnostics** with automated config repair
- **Change tracking** across workspaces
- **File staging** with content-intelligence routing
- **TTL-based file lifecycle management**
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

      - name: Health check
        run: |
          synthesis health --format json > health-report.json
          # Fail if health score below threshold
          score=$(cat health-report.json | python3 -c "import json,sys; print(json.load(sys.stdin).get('score',0))")
          if [ "$score" -lt 60 ]; then
            echo "Workspace health below threshold (score: $score)"
            synthesis health
            exit 1
          fi

      - name: Upload reports
        uses: actions/upload-artifact@v4
        with:
          name: knowledge-reports
          path: |
            architecture-report.json
            health-report.json
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
                sh 'synthesis health --format json > health-report.json'
                sh 'synthesis insights > codebase-health.txt'
                archiveArtifacts 'arch-report.json,health-report.json,codebase-health.txt'
            }
        }
    }
}
```

---

## Workspace Health Diagnostics

### The `health` Command

```bash
synthesis health                # Audit workspace, get a score (0-100)
synthesis health --fix-config   # Auto-fix phantom sub-workspace paths
synthesis health --format json  # Machine-readable output for CI/CD
```

**Checks performed:**

| Code | Severity | Description |
|------|----------|-------------|
| E001 | ERROR    | Phantom sub-workspaces (configured paths that don't exist) |
| E002 | ERROR    | Build artifacts at root (target/, node_modules/, __pycache__/) |
| W001 | WARNING  | Empty directories (no files anywhere in subtree) |
| W002 | WARNING  | 5+ loose root-level files (not dirs, not hidden) |
| I001 | INFO     | Archive percentage (large archive/ dirs noted) |

**Score formula:** `100 - 15 x errors - 5 x warnings`
**Grades:** A (90+), B (75+), C (60+), D (40+), F (<40)

The `--fix-config` flag fuzzy-matches phantom paths against actual directories using Levenshtein distance and updates `.synthesis/config.yaml` in-place.

---

## Automated Index Maintenance

### Watch Mode (Development)

```bash
synthesis watch
```

Monitors the workspace for file changes and automatically updates the index. Runs in the foreground. Suitable for development machines or dedicated index servers.

### Scheduled Refresh (Production)

The recommended automation sequence has evolved significantly since v1.8.0. The full pipeline now includes directory sync, cleanup, and activity logging.

**Recommended cron sequence:**

```bash
# Full maintenance cycle
synthesis maintain --sync --update-activity-log   # Index + sync + activity log
synthesis sweep --yes                              # Archive stale root-level files
```

Or as separate steps for granular control:

```bash
synthesis maintain              # Incremental index update + tracking
synthesis maintain --sync       # Run directory sync after maintenance
synthesis sweep --yes           # Automated cleanup of root-level stale files
synthesis ttl check --archive   # Archive TTL-expired files
```

**With cron:**

```bash
# Full maintenance cycle every 4 hours during work hours
0 8,12,16,20 * * 1-5 cd /opt/codebase && synthesis maintain --sync --update-activity-log >> /var/log/synthesis.log 2>&1

# Daily cleanup at midnight
0 0 * * * cd /opt/codebase && synthesis sweep --yes && synthesis ttl check --archive >> /var/log/synthesis-cleanup.log 2>&1
```

**With systemd timer:**

```ini
# /etc/systemd/system/synthesis-maintain.timer
[Unit]
Description=Synthesis maintenance cycle

[Timer]
OnCalendar=*-*-* 8,12,16,20:00:00
Persistent=true

[Install]
WantedBy=timers.target
```

```ini
# /etc/systemd/system/synthesis-maintain.service
[Unit]
Description=Synthesis maintain + sync + cleanup

[Service]
Type=oneshot
WorkingDirectory=/opt/codebase
ExecStart=/bin/bash -c 'synthesis maintain --sync --update-activity-log && synthesis sweep --yes && synthesis ttl check --archive'
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

### Maintain vs Scan vs Health

| Command | What it does | When to use |
|---------|-------------|-------------|
| `synthesis scan` | Full or incremental index rebuild | After install, after major changes |
| `synthesis maintain` | Detect changes, update index, track file movements | Automation, scheduled jobs, hooks |
| `synthesis maintain --sync` | Maintain + run directory identity sync | When using self-organizing workspaces |
| `synthesis maintain --update-activity-log` | Maintain + append to ACTIVITY-LOG.md | For automated reporting |
| `synthesis maintain --rebalance` | Maintain + recover misplaced archive files | When directory identities have been updated |
| `synthesis health` | Workspace diagnostics (score, issues, grade) | CI/CD gates, periodic audits |

`maintain` also triggers file movement tracking (hash-based detection with 7-day safety period) and runs the `KnowledgeEdgeScanner` to link docs to skills and source files. The `KnowledgeReconciler` detects drift when source changes break doc links, surfaced as warnings: "Knowledge edge degraded: ..."

---

## Directory Identity System (Self-Organizing Workspaces)

New in v1.11.1, the directory identity system enables workspaces to self-organize. This is the key automation primitive for DevOps.

### How It Works

1. **`synthesis sync`** writes per-directory `.synthesis.md` files declaring what each directory accepts (types, formats, scope)
2. **`synthesis sweep`** reads these identities to route stale files to the right directory instead of blindly archiving
3. **`synthesis maintain --rebalance`** periodically moves archive files back to active directories if a better destination is now known

### Setup

```bash
# One-time: establish directory identities
synthesis sync

# Verify: check what each directory declares
ls */.synthesis.md

# Ongoing: maintenance with sync
synthesis maintain --sync
```

### Automation Pattern

```bash
# The self-organizing workspace pipeline
synthesis maintain --sync           # 1. Update index + refresh directory identities
synthesis sweep --dry-run           # 2. Preview what sweep would do (shows ROUTABLE vs ARCHIVE)
synthesis sweep --yes               # 3. Execute: route files to correct dirs or archive
synthesis maintain --rebalance      # 4. Recover archive files that now have a known destination
```

The dry-run output shows two groups:

```
ROUTABLE (destination found):
  run-overnight.sh         -> knowledge-infrastructure/automation/
  2024-DISCOVERY-REPORT.md -> eXOReaction/business/reports/

ARCHIVE (no meaningful destination):
  TONIGHT-NOTES.md         -> archive/swept-2026-02-20/
```

---

## Workspace Cleanup Commands

### Sweep: Archive Stale Root-Level Files

```bash
synthesis sweep --dry-run          # Preview (shows ROUTABLE and ARCHIVE groups)
synthesis sweep --days 14          # Lower age threshold (default: 30 days)
synthesis sweep --yes              # Execute without prompting
synthesis sweep --archive-only     # Skip routing, send everything to archive/
```

**File categories swept:**
- **EPHEMERAL** -- session names: `TONIGHT-*`, `*-COMPLETE.md`, `*-PLAN.md`, `*-STATUS.md`
- **SCRIPTS** -- `.sh`/`.bash` files older than `--days`
- **ARTIFACTS** -- archives (`.zip`, `.tar.gz`, `.7z`, `.rar`, etc.) regardless of age
- **COMPLETED REPORTS** -- dated names (`2024-*`, `*-2025-COMPLETE`, etc.) older than `--days`

### Prune: Remove Orphaned/Empty Directories

```bash
synthesis prune                    # Scan only (dry-run by default)
synthesis prune --yes              # Remove empty directories
synthesis prune --path reports/    # Limit to a sub-path
```

Safety exclusions: dot-directories, directories containing README.md, paths referenced in config.

### TTL: Time-to-Live File Management

```bash
synthesis ttl set "TONIGHT-*.md" --days 3    # Register a rule
synthesis ttl set "*.tmp" --days 1           # Short-lived temp files
synthesis ttl list                            # Show active rules
synthesis ttl check                           # Dry-run: what has expired
synthesis ttl check --archive                 # Archive expired files
```

Rules stored in `.synthesis/ttl-rules.yaml`. Pattern is a glob. Expiry = `createdAt + days`.

### Consolidate: Merge Fragmented Directories

```bash
synthesis scatter --all                       # Find fragmented entities
synthesis consolidate "Entity Name"           # Dry-run preview
synthesis consolidate "Entity Name" --execute # Execute the merge
```

---

## Change Tracking

### Cross-Workspace Change Reporting

```bash
synthesis changelog                            # Changes since last snapshot (default: 7d)
synthesis changelog --since 24h                # Last 24 hours
synthesis changelog --since 2w                 # Last 2 weeks
synthesis changelog --weekly                   # Weekly executive report
synthesis changelog --significance critical    # Only critical changes
synthesis changelog --format markdown -o report.md  # Save report to file
synthesis changelog --snapshot                 # Take a snapshot now
```

Change types: `+` added, `~` modified, `-` deleted, `>` moved. Significance levels: noise, normal, notable, critical. Mass changes (>10 files deleted at once) are flagged as CRITICAL automatically.

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

### Impact Analysis

```bash
synthesis impact src/auth/AuthService.java    # Co-change analysis
```

Shows which files tend to change together, useful for change-risk assessment before deployments.

### Discover Unindexed Repos

```bash
synthesis discover                  # Find git repos in search paths not yet indexed
```

Useful for ensuring nothing is missed in a multi-repo environment.

---

## Staging Workflow

For managing incoming files (downloads, imports, external content):

```bash
synthesis staging                    # Show staging status
synthesis staging ingest <path>      # Move files into staging area
synthesis staging route              # Route files to workspaces using rules
synthesis staging promote <path>     # Promote from staging to workspace
synthesis staging expire             # Remove expired staging files
```

### Content-Intelligence Routing (v1.11.1)

The staging pipeline now supports content-based routing for files that do not match filename patterns:

1. `staging route` evaluates files against glob pattern rules first
2. For unmatched files, it reads the companion `.synthesis.md` file (if present) for keyword-based classification
3. Files above `classificationThreshold` (default 0.5) are auto-routed
4. Below-threshold matches become suggestions

**Recommended full pipeline:**

```bash
synthesis enrich --path "~/Downloads/*.pdf"   # Generate AI companions for binary files
synthesis staging ingest ~/Downloads/         # Move files into staging
synthesis staging route                       # Route with content intelligence
synthesis maintain                            # Update index with new files
```

Or use the shorthand:

```bash
synthesis staging route --enrich-first        # Auto-enrich before routing
```

The `~` prefix in output indicates content-routed files; `?` indicates suggestions (in verbose mode).

---

## Activity Log and Automated Reporting

```bash
synthesis maintain --update-activity-log      # Auto-append entry to ACTIVITY-LOG.md
```

This generates a dated entry in `ACTIVITY-LOG.md` using the same change-event data as `changelog`. Useful for:
- Automated daily/weekly activity summaries
- Audit trails of workspace changes
- Input to `synthesis report --topic activities` for status reporting

Gracefully degrades to a structured diff when no API key is present. Skips if today's entry already exists.

---

## Knowledge Integrity Monitoring

`maintain` now runs the `KnowledgeEdgeScanner` which links documentation to skills and source files. The `KnowledgeReconciler` detects drift when source changes break documentation links.

Surfaced as warnings during maintain:
```
Knowledge edge degraded: docs/ARCHITECTURE.md -> src/main/java/AuthService.java (file moved)
```

This is useful for detecting stale documentation after refactoring.

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
- `synthesis_summary` -- AI summaries with `--since` temporal context

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
docker run -v /path/to/codebase:/workspace synthesis:latest health
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
- No network calls for core commands (scan, search, relate, graph, architecture, insights, health, sweep, prune)
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

## Security Scanning in CI/CD (CKG-5)

CKG-5 security analysis integrates into CI pipelines alongside existing architecture and health checks.

### GitHub Actions: Security Gate

```yaml
      - name: Security analysis
        run: |
          synthesis code-graph extract
          synthesis code-graph security --severity HIGH --format json > security-report.json
          high_count=$(cat security-report.json | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('totalHigh',0))")
          if [ "$high_count" -gt 0 ]; then
            echo "HIGH severity security findings detected ($high_count)"
            synthesis code-graph security --severity HIGH
            exit 1
          fi

      - name: Dependency CVE check
        run: |
          synthesis code-graph security --type S010_DEPENDENCY_KNOWN_VULN --format json > cve-report.json
          # Fail on any known CVE in declared dependencies
```

### Scheduled Portfolio Scan

For multi-workspace environments, scan all workspaces on a schedule:

```bash
# Weekly security audit across all workspaces
for ws in /src/exoreaction /src/cantara /src/quadim; do
  echo "=== Scanning $ws ==="
  synthesis code-graph security -d "$ws" --severity HIGH --errors-only
done
```

### Security Commands Reference

```bash
synthesis code-graph security                           # All findings (text)
synthesis code-graph security --severity HIGH           # HIGH only
synthesis code-graph security --errors-only             # Alias for --severity HIGH
synthesis code-graph security --type S010_DEPENDENCY_KNOWN_VULN  # CVE scan
synthesis code-graph security --attack-surface          # Entry-to-sink BFS path map
synthesis code-graph security --scan-secrets            # Non-Java secret scanning
synthesis code-graph security --format json             # JSON for CI/automation
synthesis code-graph security --refresh                 # Force re-analysis
synthesis code-graph security --module <name>           # Scope to single module
```

The 21 signals cover traditional vulnerabilities (SQL injection, XXE, deserialization, hardcoded secrets, dependency CVEs) and agentic AI-specific surfaces (prompt injection, RAG poisoning, unconfirmed actions, missing prompt boundaries). Security findings are persisted in SQLite (`security_findings`, `declared_dependencies`, `attack_surface_edges` tables from V15 migration) and automatically updated during `synthesis maintain` Phase 11.

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
| Health score low | Config issues, stale entries | Run `synthesis health --fix-config` |
| Sweep routes incorrectly | Missing directory identities | Run `synthesis sync` first |
| Knowledge edge warnings | Docs referencing moved files | Update doc references or re-run `maintain` |

---

## Quick Reference

```
# Index Management
synthesis scan                                 # Full/incremental index build
synthesis maintain                             # Incremental update + movement tracking
synthesis maintain --sync                      # Maintain + directory identity sync
synthesis maintain --update-activity-log       # Maintain + append ACTIVITY-LOG.md
synthesis maintain --rebalance                 # Recover misplaced archive files
synthesis watch                                # Real-time file monitoring daemon
synthesis discover                             # Find unindexed git repos

# Health & Diagnostics
synthesis health                               # Workspace health audit (score 0-100)
synthesis health --fix-config                  # Auto-repair config issues
synthesis status                               # Workspace status overview

# Cleanup & Organization
synthesis sweep --dry-run                      # Preview stale file cleanup
synthesis sweep --yes                          # Execute cleanup
synthesis prune --yes                          # Remove empty directories
synthesis ttl set "*.tmp" --days 1             # Register TTL rule
synthesis ttl check --archive                  # Archive expired files
synthesis consolidate "Entity" --execute       # Merge fragmented directories
synthesis sync                                 # Establish directory identities

# Change Tracking
synthesis changelog                            # Cross-workspace change report
synthesis changelog --since 24h                # Changes in last 24 hours
synthesis changelog --weekly                   # Weekly executive report
synthesis changed --since 2026-02-01           # Files changed since date
synthesis track                                # File movement tracking
synthesis impact <file>                        # Co-change analysis

# Staging Pipeline
synthesis staging ingest <path>                # Ingest files
synthesis staging route                        # Route with content intelligence
synthesis staging route --enrich-first         # Enrich then route

# Credentials & Configuration
synthesis credentials set KEY value            # Store credential
synthesis credentials status                   # Check credentials
synthesis credentials clear KEY                # Remove credential

# Security
synthesis code-graph security --severity HIGH         # HIGH findings
synthesis code-graph security --format json           # CI/automation output
synthesis code-graph security --type S010_DEPENDENCY_KNOWN_VULN  # CVE scan
synthesis code-graph security --attack-surface        # Entry-to-sink paths

# Reports & Architecture
synthesis architecture --format json           # Machine-readable quality check
synthesis insights                             # Codebase health report

# Organization
synthesis org                                  # Organization registry
synthesis list                                 # All workspaces

# Updates
synthesis update                               # Update Synthesis
synthesis update --check                       # Check for updates
synthesis update --health                      # Installation health check
```

---

**Version:** v1.15.0 (Feb 2026) | 3,933 tests passing

**Related guides:**
- [MCP Quick Start](../guides/MCP-QUICKSTART.md) -- AI agent integration
- [LSP Quick Start](../guides/LSP-QUICKSTART.md) -- IDE integration
- [Developer Guide](./DEVELOPER.md) -- for your development team
- [Architect Guide](./ARCHITECT.md) -- architecture analysis
- [Full User Guide](../guides/USER-GUIDE.md) -- complete command reference
