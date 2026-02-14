# Synthesis for DevOps & Platform Engineering

**Your CI/CD pipeline validates code. But does it validate knowledge?**

**📊 Visual Summary:** [Full Presentation](../visuals/knowledge-infrastructure-health.pdf)

---

## The Knowledge Drift Problem

Your deployment pipeline is automated. Your infrastructure is code. Your tests run on every commit. But your knowledge infrastructure -- how developers find files, understand dependencies, and navigate the codebase -- is manual and fragile.

**What breaks between deployments:**

| Knowledge Asset | How It Breaks | Impact |
|----------------|---------------|--------|
| Developer onboarding docs | Stale within 2-4 weeks | New hires lost, productivity delayed |
| Architecture diagrams | Manual update, often forgotten | Decisions based on outdated mental models |
| Dependency maps | Only in developers' heads | Refactoring breaks production |
| "Where is X?" knowledge | Person leaves, knowledge lost | Bus factor = 1 for critical systems |

**The core problem:** You automate code deployment but leave knowledge management to humans and hope.

---

## Synthesis as Knowledge Infrastructure

Synthesis is a CLI tool that can be integrated into your CI/CD pipeline, development workflow, and operations processes. It provides continuous knowledge indexing just like you have continuous integration.

### Capability 1: CI/CD Integration

**Add knowledge validation to your pipeline:**

```yaml
# .github/workflows/knowledge-check.yml
name: Knowledge Infrastructure

on: [push, pull_request]

jobs:
  knowledge-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Set up Java
        uses: actions/setup-java@v3
        with:
          java-version: '17'

      - name: Install Synthesis
        run: |
          curl -L https://github.com/exoreaction/Synthesis/releases/latest/download/synthesis.jar -o synthesis.jar
          chmod +x synthesis.jar

      - name: Index codebase
        run: java -jar synthesis.jar init && java -jar synthesis.jar scan

      - name: Find TODOs
        run: java -jar synthesis.jar search "TODO" > todos.txt

      - name: Find deprecated APIs
        run: java -jar synthesis.jar search "@Deprecated" > deprecated.txt

      - name: Check for orphaned files (0 incoming references)
        run: |
          for file in $(find . -name "*.java"); do
            refs=$(java -jar synthesis.jar relate "$file" | grep "Referenced by" | wc -l)
            if [ "$refs" -eq 0 ]; then
              echo "WARNING: $file has no references (dead code?)"
            fi
          done

      - name: Upload knowledge artifacts
        uses: actions/upload-artifact@v3
        with:
          name: knowledge-reports
          path: |
            todos.txt
            deprecated.txt
```

**What this enables:**
- Automated technical debt tracking (TODOs, deprecated APIs)
- Dead code detection (files with 0 incoming references)
- Knowledge artifacts as build outputs (just like test reports)

### Capability 2: Watch Mode for Development

**Continuous indexing during development:**

```bash
# Terminal 1: Watch mode (re-indexes on file changes)
synthesis watch

# Terminal 2: Developer works normally
# Files are indexed automatically as they're created/modified

# Terminal 3: Search is always current
synthesis search "new feature"
# Finds files created 10 seconds ago
```

**Benefits:**
- Zero developer overhead (automatic)
- Index never stale (immediate update)
- Search always finds latest work

### Capability 3: Deployment Health Checks

**Pre-deployment knowledge validation:**

```bash
# Before deploying to production
synthesis scan --full
synthesis graph --modules --format mermaid > architecture-$(date +%Y-%m-%d).md

# Check for unexpected dependency changes
diff architecture-last-deploy.md architecture-$(date +%Y-%m-%d).md

# If diff shows new cross-module dependencies → investigate before deploying
# If diff is clean → deploy with confidence
```

**Use case:** Detect architecture drift before it reaches production. A new cross-module dependency might indicate a coupling issue that needs review.

### Capability 4: Telemetry and Monitoring

**Track knowledge infrastructure health:**

```bash
# Enable telemetry (optional, anonymous usage stats)
synthesis config telemetry.enabled true

# Export metrics
synthesis export --format prometheus > /var/lib/prometheus/synthesis-metrics.txt
```

**Metrics tracked:**
- Files indexed (total, by type, by language)
- Search queries (frequency, popular keywords)
- Relationship density (average incoming/outgoing connections per file)
- Index size and growth rate
- Scan duration and throughput

**Why this matters:** Knowledge infrastructure is infrastructure. It should be monitored like any other critical system.

---

## Integration Patterns for Operations

### Pattern 1: Docker Deployment

**Run Synthesis as a containerized service:**

```dockerfile
FROM eclipse-temurin:17-jre

# Install Synthesis
RUN curl -L https://github.com/exoreaction/Synthesis/releases/latest/download/synthesis.jar -o /usr/local/bin/synthesis.jar

# Set up workspace
WORKDIR /workspace
VOLUME ["/workspace"]

# Entrypoint
ENTRYPOINT ["java", "-jar", "/usr/local/bin/synthesis.jar"]
CMD ["status"]
```

```bash
# Build
docker build -t synthesis:latest .

# Run (mount your codebase)
docker run -v /path/to/codebase:/workspace synthesis:latest scan

# Search
docker run -v /path/to/codebase:/workspace synthesis:latest search "authentication"
```

**Benefits:**
- Consistent environment across team
- No local Java installation required
- Easy integration into container-based CI/CD

### Pattern 2: Scheduled Index Refresh

**Keep knowledge fresh with cron/systemd:**

```bash
# crontab -e
# Re-index every 4 hours during work hours
0 8,12,16,20 * * 1-5 cd /opt/codebase && synthesis scan >> /var/log/synthesis.log 2>&1
```

Or with systemd timer:

```ini
# /etc/systemd/system/synthesis-scan.timer
[Unit]
Description=Synthesis knowledge index refresh

[Timer]
OnCalendar=*-*-* 8,12,16,20:00:00
Persistent=true

[Install]
WantedBy=timers.target
```

```ini
# /etc/systemd/system/synthesis-scan.service
[Unit]
Description=Synthesis scan service

[Service]
Type=oneshot
WorkingDirectory=/opt/codebase
ExecStart=/usr/local/bin/synthesis scan
User=synthesis
Group=synthesis
```

**Benefits:**
- Index stays fresh without developer action
- Predictable resource usage (scheduled, not ad-hoc)
- Centralized logging

### Pattern 3: Git Hooks for Automatic Indexing

**Index on every commit/push:**

```bash
# .git/hooks/post-commit
#!/bin/bash
synthesis scan --quiet &
# Background scan, doesn't slow down commit
```

```bash
# .git/hooks/pre-push
#!/bin/bash
# Ensure index is current before pushing
synthesis scan

# Optional: Block push if deprecated APIs are added
if synthesis search "@Deprecated" | grep -q "$(git diff --name-only HEAD)"; then
  echo "ERROR: New deprecated API usage detected"
  echo "Please remove @Deprecated annotations before pushing"
  exit 1
fi
```

**Benefits:**
- Zero-friction developer experience
- Index always reflects committed code
- Optional quality gates (block deprecated APIs, TODOs, etc.)

### Pattern 4: Multi-Repository Indexing

**Index all repositories in your organization:**

```bash
# Index all repos under /opt/repos/
cd /opt/repos
synthesis init
synthesis scan

# Now search across all repos simultaneously
synthesis search "authentication service"
# Finds results in repo A, repo B, repo C

# Generate cross-repo dependency graph
synthesis graph --cross-repo --format mermaid > org-architecture.md
```

**Use case:** Platform teams managing 50+ microservices. One index, one search, all repos.

---

## Security Model

### Local-First Architecture

**Core features are 100% local:**
- All indexing happens on local machine/container
- No network calls for scan, search, relate, graph
- Index stored in `.synthesis/` directory (local filesystem)
- No telemetry by default (opt-in only)

**AI features require opt-in:**
- `--with-readme` and `--synthesize` flags require Anthropic API key
- Only selected files sent to API (never entire codebase)
- User explicitly chooses which files to send

### Data Storage

| Data Type | Location | Purpose | Sensitive? |
|-----------|----------|---------|------------|
| Lucene index | `.synthesis/index/` | Full-text search | Contains code snippets, file paths |
| Config | `.synthesis/config.yaml` | User preferences | May contain API key |
| Telemetry | Anonymous (if enabled) | Usage stats | No code, only metrics |
| Bundled binaries | `~/.synthesis/bin/` | ffprobe for video metadata | Trusted (FFmpeg official build) |

**Security recommendations:**
- Add `.synthesis/` to `.gitignore` (keep index local)
- Protect `config.yaml` if it contains API key (chmod 600)
- Review telemetry settings (`telemetry.enabled: false` to disable)
- Run in isolated containers for untrusted code

### Compliance Considerations

**For regulated industries:**
- **Data residency:** All data stays on your infrastructure (no cloud dependency)
- **Audit trail:** Optional telemetry can be exported for compliance review
- **Access control:** Use filesystem permissions to control who can run Synthesis
- **Encryption at rest:** Index files can be encrypted using standard filesystem encryption

---

## Performance Tuning

### For Large Codebases (10,000+ files)

**Optimize scan performance:**

```yaml
# .synthesis/config.yaml
scan:
  # Increase batch size for faster processing
  batchSize: 1000  # default: 500

  # Exclude large binary directories
  excludePatterns:
    - "**/node_modules/**"
    - "**/target/**"
    - "**/build/**"
    - "**/.venv/**"
    - "**/dist/**"

  # Limit file size to avoid huge binaries
  maxFileSizeBytes: 10485760  # 10 MB (default)
```

**Measured performance (7,990 files, validated):**
- Throughput: 258-300 files/sec
- Full scan: 31 seconds
- Incremental scan: 156-345ms (1,000 files)
- Index overhead: 2.7% (9.7 MB index for 202 MB content)

### For CI/CD Pipelines

**Fast scanning in pipelines:**

```bash
# Only index files changed in this commit
synthesis scan --incremental

# Skip media processing in CI (faster)
synthesis scan --no-media

# Verbose output for debugging
synthesis scan --verbose
```

---

## Maintenance

### Index Health Checks

**Weekly maintenance:**

```bash
# Check index health
synthesis status

# Expected output:
# ✓ Index is healthy
# ✓ 7,990 files indexed
# ✓ Last scan: 2 hours ago
# ✓ Index size: 9.7 MB (2.7% overhead)
```

### When to Rebuild Index

**Full rebuild needed if:**
- Changed include/exclude patterns in config
- Moved/renamed many files across repositories
- Index corruption (very rare, Lucene is robust)

```bash
# Full rebuild
synthesis scan --full

# Takes 31 seconds for 7,990 files (still fast)
```

### Troubleshooting

| Symptom | Diagnosis | Fix |
|---------|-----------|-----|
| Search finds no results | Files not in includePatterns | Update config.yaml includePatterns |
| Scan is slow (>1 min for 5,000 files) | Including node_modules or build artifacts | Add to excludePatterns |
| Index size is huge (>10% of content) | Large binary files indexed | Reduce maxFileSizeBytes |
| Watch mode misses changes | File system watcher limit (Linux) | Increase `fs.inotify.max_user_watches` |
| Out of memory during scan | Very large files | Reduce maxFileSizeBytes or increase JVM heap |

---

## Your Next Step

**Pick one integration pattern and implement it this week:**

**Option 1 (Easiest):** Add Synthesis to CI/CD pipeline (copy YAML above, 15 minutes)

**Option 2 (Most Impact):** Set up scheduled index refresh (cron or systemd, 20 minutes)

**Option 3 (Zero Friction):** Add git hooks for automatic indexing (5 minutes)

Start with one. Prove the value. Then expand.

---

**Related documentation:**
- **For your developers:** [Quick Start](../guides/QUICK-START.md) | [User Guide](../guides/USER-GUIDE.md)
- **For CI/CD examples:** [Project README](../../README.md) - Configuration section
- **For architecture patterns:** [Architecture Guide](./ARCHITECT.md)
- **For team adoption:** [Engineering Manager Guide](./ENGINEERING-MANAGER.md)
