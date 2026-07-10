# Synthesis Product Variants & Operating Modes: Strategic Roadmap

**Version:** 1.1
**Date:** 2026-02-15
**Author:** Thor Henning Hetland / eXOReaction (with Claude analysis)
**Status:** Decisions made -- implementation in progress
**Companion:** [AI-SCOPE-ANALYSIS.md](./AI-SCOPE-ANALYSIS.md) (AI capabilities deep-dive)

---

## Executive Summary

Synthesis v1.1.1-SNAPSHOT currently ships as a single monolithic JAR (137 MB) containing all capabilities: search, graph, AI-powered Q&A, MCP server, and LSP server. This document analyzes how to split Synthesis along two strategic axes:

1. **Product Variants** -- Air-gapped (no AI) vs. Self-learning SDD (AI-enabled)
2. **Operating Modes** -- Run-once CLI vs. Daemon (always-on, file-watching)

The 2x2 matrix creates four distinct product configurations, each targeting different market segments with different willingness to pay. Combined addressable market: $6.5B across enterprise security, startups, large corporations, and elite dev teams.

**Key finding from codebase analysis:** The current architecture already has clean separation points. AI dependencies are isolated in 3 core classes (`ClaudeClient`, `DirectedSynthesisEngine`, `ReadmeGenerator`) plus 6 CLI commands (`ask`, `perspectives`, `analyze`, `scan`, `export`, `extract-slides`). The existing `WatchCommand` already implements 80% of daemon mode using Java's `WatchService`. This makes the modular build approach (Option A) both technically sound and low-risk.

**Recommended build order:**
1. **Q2 2026 (now):** Ship air-gapped run-once variant (2-3 weeks effort, unlocks enterprise security segment)
2. **Q3 2026:** Ship daemon mode with WatchService (6-8 weeks, differentiation play)
3. **Q4 2026:** Combine into air-gapped daemon (1-2 weeks, largest deal sizes)

**Decisions made (v1.1):**
- **Architecture:** Separate launcher scripts (`synthesis-core`, `synthesis`) + `SYNTHESIS_EDITION` env var. Implemented.
- **File watcher:** Java WatchService (not PathWatcher). Multi-dir support essential. See [AI-SCOPE-ANALYSIS.md Section 8](./AI-SCOPE-ANALYSIS.md#8-file-watcher-decision-watchservice-vs-pathwatcher).
- **Daemon mode:** `--daemon` flag on WatchCommand with PID file management. Implemented.
- **Telemetry:** No-op in air-gapped mode (`TelemetryService.createNoOp()`). Implemented.
- **AI scope:** Three-phase incremental roadmap. See [AI-SCOPE-ANALYSIS.md](./AI-SCOPE-ANALYSIS.md) for full analysis.
- **Licensing:** TBD (deferred).

---

## Table of Contents

1. [Current Architecture Analysis](#1-current-architecture-analysis)
2. [Product Matrix (2x2)](#2-product-matrix)
3. [Architecture Recommendation](#3-architecture-recommendation)
4. [PathWatcher Integration Design](#4-pathwatcher-integration-design)
5. [Go-to-Market Strategy](#5-go-to-market-strategy)
6. [Implementation Roadmap](#6-implementation-roadmap)
7. [Business Model Comparison](#7-business-model-comparison)
8. [Open Questions for Decision](#8-open-questions-for-decision)

---

## 1. Current Architecture Analysis

### 1.1 Codebase Profile (v1.1.1-SNAPSHOT)

| Metric | Value |
|--------|-------|
| Total Java source | 22,643 lines across 83 classes |
| Test classes | 54 test files |
| Fat JAR size | 137 MB (shaded with all dependencies) |
| Original JAR (no deps) | 88 MB |
| Java version | 17 |
| Build system | Maven with shade plugin |
| Server JARs | 3 (synthesis.jar, synthesis-mcp-server.jar, synthesis-lsp-server.jar) |

### 1.2 Package Structure

```
io.exoreaction.synthesis/
  SynthesisApp.java            # CLI entry point (picocli)
  ai/                          # AI features (3 classes)
    ClaudeClient.java          # Anthropic SDK wrapper
    DirectedSynthesisEngine.java  # Multi-perspective analysis
    ReadmeGenerator.java       # AI-generated README files
    PromptTemplates.java       # Prompt construction (no SDK dependency)
  analyzer/                    # File analysis (7 classes)
  cli/                         # CLI commands (20 classes)
  config/                      # Configuration (2 classes)
  core/                        # Core scanning (5 classes)
  git/                         # Git integration (1 class)
  graph/                       # Dependency graphs (2 classes)
  index/                       # Lucene indexing (4 classes)
  insights/                    # Code insights (1 class)
  lsp/                         # LSP server (3 classes)
  mcp/                         # MCP server (3 classes)
  org/                         # Organization model (7 classes)
  skills/                      # Skill generation (3 classes)
  telemetry/                   # Telemetry (6 classes)
  update/                      # Update system (7 classes)
  util/                        # Utilities (5 classes)
```

### 1.3 Dependency Map

**Core Dependencies (required for all variants):**
- Apache Lucene 10.1.0 (indexing, search)
- Picocli 4.7.7 (CLI framework)
- SnakeYAML 2.2 (configuration)
- JGit 7.1.1 (git metadata)
- JGraphT 1.5.2 (graph algorithms)
- Graphviz-Java 0.18.1 (graph visualization)
- Jackson 2.18.2 (JSON processing)
- PDFBox 3.0.4 (PDF analysis)
- Metadata-Extractor 2.19.0 (image EXIF)

**AI-Only Dependencies (removable for air-gapped):**
- Anthropic Java SDK 2.14.0 (`com.anthropic:anthropic-java`)
  - Includes OkHttp, Kotlin stdlib, and other transitive dependencies

**Server Dependencies:**
- LSP4J 0.23.1 (Language Server Protocol)

**Telemetry Dependencies (potentially removable for air-gapped):**
- Slack API Client 1.44.2 (webhook reporting)

### 1.4 AI Coupling Analysis

**Files with direct Anthropic SDK imports (hard coupling):**
- `ai/ClaudeClient.java` -- Only file importing `com.anthropic.*`

**Files with ClaudeClient usage (soft coupling via Optional):**
- `cli/AskCommand.java` -- Creates `Optional<ClaudeClient>`, exits gracefully if empty
- `cli/PerspectivesCommand.java` -- Same pattern
- `cli/AnalyzeCommand.java` -- AI-enhanced analysis, degrades without AI
- `cli/ScanCommand.java` -- Vision analysis for images (optional feature)
- `cli/ExportCommand.java` -- AI-generated summaries (optional)
- `cli/ExtractSlidesCommand.java` -- Slide AI descriptions (optional)
- `ai/DirectedSynthesisEngine.java` -- Requires ClaudeClient
- `ai/ReadmeGenerator.java` -- Requires ClaudeClient
- `analyzer/PresentationExtractor.java` -- Optional ClaudeClient parameter

**Files with zero AI dependency (the core):**
- All of `index/`, `core/`, `graph/`, `git/`, `org/`, `skills/`, `config/`
- CLI: `search`, `scan` (core scanning), `relate`, `graph`, `insights`, `watch`, `diff`, `changed`, `maintain`, `status`, `init`, `cross-repo-deps`, `learn`, `org`, `telemetry`, `update`
- All of `mcp/` and `lsp/` (no AI calls)

**Critical insight:** The AI surface area is cleanly isolated. `ClaudeClient.create()` already returns `Optional.empty()` when AI is disabled or the API key is missing. Most commands already degrade gracefully. The air-gapped variant is architecturally straightforward.

### 1.5 Existing Watch Mode Analysis

The current `WatchCommand` (lines 52-387) already implements:
- Java `WatchService` for file system event detection
- Recursive directory registration with exclusion patterns
- Debounced event batching (configurable, default 300ms)
- Incremental indexing via `SearchIndex.addDocument()` / `deleteByRelativePath()`
- CREATE, MODIFY, DELETE event handling
- Graceful shutdown via JVM shutdown hook
- New directory auto-registration on CREATE events
- Organizational file change detection and skill regeneration

**What it lacks for true daemon mode:**
- Background process management (PID files, detached execution)
- `daemon start` / `daemon stop` / `daemon status` subcommands
- Systemd/launchd service integration
- Shared index access (current implementation opens/closes index per batch)
- API server for external queries (HTTP/gRPC)
- Health monitoring and crash recovery

This means daemon mode is approximately 80% implemented. The remaining 20% is lifecycle management and service integration.

---

## 2. Product Matrix

### 2x2 Overview

|  | **Run-Once (CLI)** | **Daemon Mode (Always-On)** |
|---|---|---|
| **Air-gapped** (no AI) | **Synthesis Core** | **Synthesis Enterprise** |
| **Self-learning SDD** (AI-enabled) | **Synthesis Pro** | **Synthesis Ultimate** |

---

### 2.1 Cell A: Synthesis Core (Air-gapped + Run-Once)

**Positioning:** "Zero-dependency knowledge infrastructure. No cloud. No AI. No excuses."

**Who it is for:**
- Defense contractors prohibited from cloud connectivity
- Banking & financial institutions with strict data residency
- Government agencies (GDPR/SOC2/HIPAA/NIS2 environments)
- Air-gapped development environments (submarine software, nuclear facilities)
- Any team that cannot or will not use external APIs

**Top 3 use cases:**
1. **Secure codebase navigation** -- Developer searches 50K+ files across classified projects without any data leaving the machine. Sub-second search, relationship tracking, dependency graphs -- all local.
2. **Compliance-ready knowledge audit** -- Security team runs `synthesis scan` + `synthesis export` to generate a complete inventory of all code, documentation, and artifacts. Report goes to compliance officer. No data exfiltration risk.
3. **MCP server for Claude Code (offline)** -- Developer uses Claude Code with local MCP server providing workspace context. The MCP server queries the local Lucene index; Claude Code handles the AI. No Anthropic SDK in the tool itself.

**Technical requirements:**
- Remove Anthropic SDK dependency (Maven profile exclusion)
- Remove or disable `ask`, `perspectives` commands (compile-time exclusion)
- Optionally remove Slack telemetry dependency (enterprise sensitivity)
- Build: `mvn clean package -P air-gapped` producing `synthesis-core-1.2.0.jar`
- Estimated JAR size: ~110 MB (27 MB smaller without Anthropic SDK + transitive deps)

**Competitive advantage:**
- **Only** knowledge infrastructure tool that ships with zero cloud dependencies
- Sourcegraph requires server deployment; GitHub Copilot requires cloud; Cody requires API
- Can be audited line-by-line (Apache 2.0 license, single-module Java, no native code)
- Works on classified networks, air-gapped SCIFs, submarine systems
- MCP/LSP servers still function -- AI agents query the index, Synthesis itself never calls out

**Revenue model:**
- Enterprise site license: $50K-200K/year per organization
- Support contracts: $20K-50K/year (SLA-backed, on-site support for classified environments)
- Professional services: $5K-15K per deployment (installation, configuration, training)
- Volume licensing for government frameworks (GSA Schedule, G-Cloud, NATO COTS)

**Market size:**
- TAM: $500M (10,000 high-security organizations globally x $50K average)
- SAM: $50M (1,000 Nordic/European orgs accessible via IASA + defense networks)
- SOM: $5M (100 organizations in first 3 years via Thor's IASA chairman network)

---

### 2.2 Cell B: Synthesis Pro (Self-learning SDD + Run-Once)

**Positioning:** "AI-augmented knowledge infrastructure. Your codebase, understood."

**Who it is for:**
- Fast-moving startups using AI-assisted development (Claude Code, Copilot, Cursor)
- Mid-market SaaS companies (50-500 devs) drowning in AI-generated output
- SDD practitioners and teams trained in eXOReaction methodology
- Individual developers managing multiple large codebases

**This is the current product (v1.1.1-SNAPSHOT).**

**Top 3 use cases:**
1. **AI-powered Q&A** -- `synthesis ask "How does authentication work?"` searches the index, pulls relevant files, sends context to Claude, returns a cited answer. Developer gets from question to answer in seconds, not minutes.
2. **Multi-perspective analysis** -- `synthesis perspectives "Should we migrate from REST to GraphQL?"` generates 3-5 analytical viewpoints using workspace context. Reveals trade-offs a single-answer approach would miss.
3. **Intelligent scanning** -- `synthesis scan` with AI vision analyzes images (architecture diagrams, screenshots), generates README content, and builds semantic summaries for better search relevance.

**Technical requirements:**
- Current build (no changes needed)
- Requires `ANTHROPIC_API_KEY` environment variable for AI features
- AI features degrade gracefully without API key (falls back to Core functionality)
- Build: `mvn clean package` producing `synthesis-1.2.0.jar`
- JAR size: 137 MB (current)

**Competitive advantage:**
- **Hybrid approach:** Local indexing (fast, private) + cloud AI (intelligent, contextual)
- Unlike pure-cloud tools: index is always local, AI is optional and query-only
- Unlike pure-local tools: AI features add genuine intelligence (not just search)
- MCP + LSP integration means it works inside Claude Code AND IDEs simultaneously
- SDD methodology backing: not just a tool, but a proven development philosophy

**Revenue model:**
- Open source (MIT) for community adoption and developer love
- SaaS pricing for teams: $49/month (team of 5), $149/month (team of 20), $499/month (enterprise team)
- Workshop revenue: 35-75K NOK per workshop (current model, proven)
- Consulting: 15-20K NOK/day for SDD implementation (Mynder model)

**Market size:**
- TAM: $2B (1M AI-augmented dev teams x $2K/year average)
- SAM: $200M (100K teams using Claude Code/Copilot actively)
- SOM: $2M (1,000 teams in first 3 years via workshops + LinkedIn + conferences)

---

### 2.3 Cell C: Synthesis Enterprise (Air-gapped + Daemon)

**Positioning:** "Always-on knowledge infrastructure for the enterprise. Zero downtime, zero cloud."

**Who it is for:**
- Large corporations with 100K+ file codebases (Fortune 500, system integrators)
- Enterprise dev teams needing always-current indexes without manual re-scanning
- Organizations running CI/CD pipelines that need real-time change detection
- Consulting firms managing multiple large client codebases simultaneously

**Top 3 use cases:**
1. **Continuous codebase indexing** -- Daemon watches all workspace directories. When a developer commits, the index updates within seconds. Every search reflects the current state of the codebase. No `synthesis scan` needed after initial setup.
2. **CI/CD broken-link detection** -- Daemon detects when file moves/renames break Markdown references, import paths, or documentation links. Reports broken links immediately. PR check integration prevents merging docs with stale references.
3. **Multi-workspace monitoring** -- Enterprise installs daemon across 5-10 major codebases. Central monitoring via systemd status. Each workspace independently indexed, searchable via MCP from any developer's Claude Code instance.

**Technical requirements:**
- Combine air-gapped profile with daemon module
- PathWatcher integration (Cantara library) or enhanced Java WatchService
- Daemon lifecycle management (PID files, start/stop/status/restart)
- Systemd service file (Linux), launchd plist (macOS), Windows Service wrapper
- Shared Lucene index with proper locking (NRT reader pattern)
- Health monitoring endpoint (optional: HTTP API for monitoring systems)
- Build: `mvn clean package -P air-gapped,daemon` producing `synthesis-enterprise-1.3.0.jar`
- Estimated JAR size: ~115 MB

**Competitive advantage:**
- **Only** air-gapped tool with daemon mode (Sourcegraph server requires network)
- Real-time index freshness without any cloud dependency
- Enterprise deployment patterns (systemd, Docker, Kubernetes)
- Can run on isolated build servers inside corporate firewalls
- Monitoring integration (Prometheus metrics endpoint, Grafana dashboards)

**Revenue model:**
- Enterprise license: $100K-500K/year per organization (scales with dev team size)
- Support + SLA: $50K-100K/year (24/7 support, dedicated account manager)
- Professional services: $25K-75K per deployment (multi-workspace setup, CI/CD integration)
- Training: $15K-25K per cohort (admin training for daemon management)

**Market size:**
- TAM: $1B (5,000 large enterprises x $200K average)
- SAM: $100M (500 Nordic/European large enterprises)
- SOM: $10M (50 enterprises in first 3 years via IASA board network + SpareBank 1 reference)

---

### 2.4 Cell D: Synthesis Ultimate (Self-learning SDD + Daemon)

**Positioning:** "The AI that watches your codebase sleep. Real-time intelligence, always learning."

**Who it is for:**
- Elite dev teams practicing SDD at the highest level
- AI research labs building on top of large codebases
- Cutting-edge consultancies (like eXOReaction) managing 100+ repositories
- Teams that want proactive codebase intelligence, not just reactive search

**Top 3 use cases:**
1. **Real-time refactoring suggestions** -- Daemon detects that a file was moved but 7 other files still reference the old path. AI analyzes the impact and suggests specific fixes. Developer gets a notification with a fix plan before they even realize the breakage.
2. **Codebase learning** -- Daemon learns patterns over time: "This team always puts DTOs in `model/` packages, tests mirror `src/` structure, and README files follow a specific template." When a new file is created that breaks the pattern, Synthesis flags it.
3. **Continuous documentation quality** -- Daemon watches for documentation drift. When code changes but corresponding docs stay stale, AI detects the semantic gap and flags outdated documentation with specific suggestions for updates.

**Technical requirements:**
- Full build with all features: daemon + AI + MCP + LSP
- Real-time AI analysis pipeline (debounced, batched, cost-controlled)
- Learning model: pattern extraction from historical changes (local, no cloud storage)
- Cost management: AI calls are expensive; budget $0.01-0.10 per file change event
- Configuration: `synthesis.daemon.ai.budget_per_day: $5.00` (cost ceiling)
- WebSocket API for real-time notifications to IDE extensions
- Build: `mvn clean package -P daemon` producing `synthesis-ultimate-1.3.0.jar`
- Estimated JAR size: ~140 MB

**Competitive advantage:**
- **No competitor has this:** real-time AI-powered codebase monitoring with local indexing
- Sourcegraph Cody is cloud-only; GitHub Copilot has no file-watching; Cursor has no daemon
- Proactive intelligence vs. reactive search: Synthesis tells you what broke before you ask
- SDD methodology integration: learns your team's patterns, enforces consistency
- Cost-controlled AI: budget limits prevent runaway API costs

**Revenue model:**
- Premium SaaS: $499/month (team), $2,499/month (enterprise team)
- Enterprise license: $200K-1M/year (includes AI budget allocation)
- SDD certification: $5K per developer (bundled with training)
- Consulting retainer: $15-20K/month (ongoing AI strategy, like Mynder model)

**Market size:**
- TAM: $3B (500K advanced dev teams x $6K/year average)
- SAM: $300M (50K teams actively using AI-assisted development)
- SOM: $3M (500 teams in first 3 years via conference circuit + partnerships)

---

### Matrix Summary Table

| Variant | Mode | Name | Target | Price | TAM | Build Effort |
|---------|------|------|--------|-------|-----|-------------|
| Air-gapped | Run-once | **Core** | Security-conscious | $50K-200K/yr | $500M | 2-3 weeks |
| Self-learning | Run-once | **Pro** | Startups & SDD | $49-499/mo | $2B | Done (current) |
| Air-gapped | Daemon | **Enterprise** | Large corps | $100K-500K/yr | $1B | 1-2 weeks* |
| Self-learning | Daemon | **Ultimate** | Elite teams | $499-2,499/mo | $3B | 8-10 weeks |

*After daemon infrastructure is built for Ultimate/Enterprise

---

## 3. Architecture Recommendation

### 3.1 Options Evaluated

**Option A: Modular Build (Maven Profiles)**
```
synthesis/                    # Single repo, single module
  pom.xml                     # Profiles: air-gapped, daemon, full
  src/main/java/
    synthesis/
      ai/                     # Excluded by air-gapped profile
      daemon/                 # New: daemon mode classes
      ...                     # Shared core
```

Build commands:
```bash
mvn clean package                     # Pro (current, all features)
mvn clean package -P air-gapped       # Core (no AI deps)
mvn clean package -P daemon           # Ultimate (all + daemon)
mvn clean package -P air-gapped,daemon # Enterprise (daemon, no AI)
```

**Option B: Feature Flags (Single Build)**
```
synthesis.jar (unified, 140 MB)
  --no-ai flag         # Disable AI at runtime
  --daemon flag         # Run in daemon mode
  config.yaml:
    ai.enabled: false   # Air-gapped mode
    daemon.enabled: true # Daemon mode
```

**Option C: Separate Products (Multiple Repos)**
```
synthesis/           # Core (OSS, MIT)
synthesis-ai/        # AI plugin (commercial)
synthesis-daemon/    # Daemon plugin (enterprise)
```

### 3.2 Recommendation: Hybrid A+B (Maven Profiles + Runtime Config)

**Use Maven profiles for dependency exclusion, runtime config for feature toggling.**

**Rationale:**

1. **Air-gapped requires compile-time exclusion.** Security-conscious enterprises will audit the JAR. If the Anthropic SDK classes are present but "disabled," that fails security review. The SDK must not be in the classpath at all. This rules out pure Option B for the air-gapped variant.

2. **Daemon mode is a runtime concern.** The daemon classes (file watcher, lifecycle manager) should always be compiled in. Whether the user runs `synthesis scan` or `synthesis daemon start` is a runtime decision, not a build-time one. This rules out Option C's separation for daemon mode.

3. **Single repo is critical for maintenance.** With 22,643 lines of Java and 54 test files, maintaining multiple repos would be a 3x maintenance burden. Cross-repo version coordination is painful. A single Maven module with profiles keeps everything in one place.

4. **The current codebase already supports this.** `ClaudeClient.create()` returns `Optional.empty()` when AI is disabled. Most commands already handle the no-AI case gracefully. The air-gapped profile just needs to exclude the JAR dependency and remove the 3 AI-only CLI commands.

### 3.3 Detailed Architecture

```
synthesis/                              # Single repository
  pom.xml                               # Profiles: air-gapped, daemon
  src/main/java/
    io.exoreaction.synthesis/
      SynthesisApp.java                 # Entry point (register commands conditionally)
      ai/                               # AI package (excluded by air-gapped profile)
        ClaudeClient.java               # Only Anthropic SDK import
        DirectedSynthesisEngine.java    # Multi-perspective analysis
        ReadmeGenerator.java            # AI README generation
        PromptTemplates.java            # Keep: no SDK dependency, useful for MCP
      cli/
        AskCommand.java                 # Excluded by air-gapped profile
        PerspectivesCommand.java        # Excluded by air-gapped profile
        DaemonCommand.java              # NEW: daemon start/stop/status
        ...                             # All other commands: always included
      daemon/                           # NEW: daemon mode package
        DaemonService.java              # Lifecycle management
        FileWatcherService.java         # PathWatcher/WatchService abstraction
        IncrementalIndexer.java         # Single-file indexing
        DaemonConfig.java               # Daemon-specific configuration
        DaemonHealthMonitor.java        # Health checks, metrics
      ...                               # Unchanged packages
```

### 3.4 Maven Profile Implementation

```xml
<profiles>
  <!-- Air-gapped: exclude AI dependencies and commands -->
  <profile>
    <id>air-gapped</id>
    <properties>
      <synthesis.variant>air-gapped</synthesis.variant>
    </properties>
    <build>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <configuration>
            <excludes>
              <!-- Exclude AI-only source files -->
              <exclude>**/ai/ClaudeClient.java</exclude>
              <exclude>**/ai/DirectedSynthesisEngine.java</exclude>
              <exclude>**/ai/ReadmeGenerator.java</exclude>
              <exclude>**/cli/AskCommand.java</exclude>
              <exclude>**/cli/PerspectivesCommand.java</exclude>
            </excludes>
          </configuration>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-shade-plugin</artifactId>
          <configuration>
            <artifactSet>
              <excludes>
                <exclude>com.anthropic:*</exclude>
              </excludes>
            </artifactSet>
          </configuration>
        </plugin>
      </plugins>
    </build>
    <dependencies>
      <!-- Override Anthropic SDK to provided scope (not included in JAR) -->
      <dependency>
        <groupId>com.anthropic</groupId>
        <artifactId>anthropic-java</artifactId>
        <version>${anthropic.version}</version>
        <scope>provided</scope>
      </dependency>
    </dependencies>
  </profile>

  <!-- Optional: exclude telemetry for maximum air-gap -->
  <profile>
    <id>no-telemetry</id>
    <build>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-shade-plugin</artifactId>
          <configuration>
            <artifactSet>
              <excludes>
                <exclude>com.slack.api:*</exclude>
              </excludes>
            </artifactSet>
          </configuration>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

### 3.5 Conditional Command Registration

The `SynthesisApp.java` currently hard-codes all subcommands in the `@Command` annotation. For air-gapped builds where AI classes are excluded, we need conditional registration:

**Approach: Reflective command discovery with fallback**

```java
// In SynthesisApp.main(), after creating CommandLine:
CommandLine cmd = new CommandLine(new SynthesisApp());

// Try to register AI commands; skip if classes not present (air-gapped)
tryRegisterCommand(cmd, "io.exoreaction.synthesis.cli.AskCommand");
tryRegisterCommand(cmd, "io.exoreaction.synthesis.cli.PerspectivesCommand");

// Try to register daemon command
tryRegisterCommand(cmd, "io.exoreaction.synthesis.cli.DaemonCommand");

// Helper method:
private static void tryRegisterCommand(CommandLine cmd, String className) {
    try {
        Class<?> clazz = Class.forName(className);
        // Extract @Command name via annotation
        Command annotation = clazz.getAnnotation(Command.class);
        if (annotation != null) {
            cmd.addSubcommand(annotation.name(), clazz.getDeclaredConstructor().newInstance());
        }
    } catch (ClassNotFoundException e) {
        // Class excluded by build profile -- silently skip
    } catch (Exception e) {
        // Other error -- log and skip
    }
}
```

**Alternative (simpler): Compile-time exclusion with separate App classes**

Create `SynthesisAppCore.java` (air-gapped) and `SynthesisAppFull.java` (Pro/Ultimate), each with the correct `@Command(subcommands = {...})` list. The Maven profile selects which one to compile. Less elegant but more explicit and easier to audit.

### 3.6 Architecture Trade-off Summary

| Concern | Chosen Approach | Trade-off |
|---------|----------------|-----------|
| AI exclusion | Maven profile + source exclusion | Slightly complex build, but guaranteed no AI classes in air-gapped JAR |
| Daemon mode | Runtime flag (`synthesis daemon start`) | Always compiled in, daemon classes present in all builds (acceptable) |
| Telemetry exclusion | Optional `no-telemetry` profile | Separate concern from air-gapped; some air-gapped customers may want telemetry internally |
| Command registration | Reflective discovery with fallback | More complex but single codebase; no duplication |
| MCP/LSP | Always included (no AI dependency) | MCP/LSP servers work in all variants; AI agent provides the intelligence |
| Configuration | `config.yaml` with `variant` field | Runtime validation: air-gapped build rejects `ai.enabled: true` config |

---

## 4. PathWatcher Integration Design

### 4.1 Why PathWatcher over Java WatchService

The current `WatchCommand` uses Java's `WatchService`. It works, but has known limitations:

| Concern | Java WatchService | Cantara PathWatcher |
|---------|-------------------|---------------------|
| Recursive watching | Manual: walk tree, register each dir | Built-in recursive monitoring |
| File completion detection | No: fires on first write, not completion | Yes: `FileCompletelyCreatedHandler` |
| Polling vs. native | Platform-dependent (inotify on Linux, polling on macOS) | Configurable: scanner mode selection |
| New directory handling | Manual: register on CREATE event | Automatic: watches new subdirectories |
| Multiple watch dirs | Supported (register multiple) | **Single directory only** per instance |
| Debouncing | Manual implementation (current: 300ms) | Not built-in; manual implementation needed |
| Maturity | JDK standard (since Java 7) | 67 releases, Cantara ecosystem |

**Recommendation: Abstract the watcher interface, implement both backends.**

PathWatcher is a good fit for single-workspace monitoring with its file-completion detection. But its single-directory limitation means multi-workspace requires multiple PathWatcher instances. Java's WatchService is more flexible for multi-workspace scenarios. An abstraction layer lets us use both:

```java
public interface FileWatcherBackend {
    void watch(Path directory, FileChangeHandler handler) throws IOException;
    void stop();
    boolean isRunning();
}

// Two implementations:
class JavaWatchServiceBackend implements FileWatcherBackend { ... }
class PathWatcherBackend implements FileWatcherBackend { ... }
```

Default: PathWatcher (better file-completion handling). Fallback: Java WatchService (no additional dependency).

### 4.2 Daemon Architecture

```
synthesis daemon start [--workspace /path] [--port 8080]
       |
       v
  DaemonService
       |
       +-- FileWatcherService (PathWatcher or WatchService)
       |     |
       |     +-- Monitors workspace directories
       |     +-- Fires: CREATED, MODIFIED, DELETED events
       |     +-- Debounces events (configurable, default 1s for daemon)
       |     +-- Filters: ignore .git, node_modules, .synthesis, etc.
       |
       +-- IncrementalIndexer
       |     |
       |     +-- Receives batched file change events
       |     +-- Updates Lucene index (addDocument / deleteByRelativePath)
       |     +-- Uses NRT (Near Real-Time) reader for concurrent search
       |     +-- Updates relationship graph (incremental)
       |     +-- Performance target: <100ms per file, <500ms per batch
       |
       +-- DaemonHealthMonitor
       |     |
       |     +-- Tracks: uptime, files indexed, events processed, errors
       |     +-- Writes health file: ~/.synthesis/daemon-health.json
       |     +-- Optional: Prometheus metrics endpoint (/metrics)
       |
       +-- RealtimeAnalyzer (only if AI-enabled variant)
       |     |
       |     +-- Detects broken links (file moved/deleted)
       |     +-- Detects outdated references
       |     +-- Suggests refactorings (AI-powered, budget-controlled)
       |     +-- Publishes notifications
       |
       +-- SharedIndexManager
             |
             +-- Single IndexWriter, shared across daemon + MCP/LSP
             +-- NRT DirectoryReader for concurrent search
             +-- Handles lock coordination (only one writer allowed)
             +-- Graceful handoff: if CLI command needs write access, daemon yields
```

### 4.3 Key Implementation Classes

#### `DaemonService.java`

```java
package io.exoreaction.synthesis.daemon;

/**
 * Main daemon service. Manages lifecycle of file watcher, incremental indexer,
 * and optional real-time analyzer.
 *
 * Lifecycle:
 *   start() -> initialize watcher, indexer, write PID file
 *   stop()  -> graceful shutdown, flush index, remove PID file
 *   status() -> read PID file, check if process is alive
 */
public class DaemonService {

    private static final Path PID_FILE = Path.of(
        System.getProperty("user.home"), ".synthesis", "daemon.pid");
    private static final Path HEALTH_FILE = Path.of(
        System.getProperty("user.home"), ".synthesis", "daemon-health.json");

    private final Path workspaceRoot;
    private final SynthesisConfig config;
    private FileWatcherService watcher;
    private IncrementalIndexer indexer;
    private DaemonHealthMonitor healthMonitor;
    private volatile boolean running = false;

    // start(), stop(), status(), isRunning() methods
    // PID file management
    // Signal handling (SIGTERM, SIGINT)
}
```

#### `IncrementalIndexer.java`

```java
package io.exoreaction.synthesis.daemon;

/**
 * Handles incremental index updates for single files.
 *
 * Key design decisions:
 * 1. Uses Lucene's NRT (Near Real-Time) reader pattern:
 *    - Single IndexWriter kept open for daemon lifetime
 *    - DirectoryReader.openIfChanged() for search freshness
 *    - Commit every N seconds or M documents (configurable)
 *
 * 2. Reuses existing analysis pipeline:
 *    - AnalyzerRegistry for content extraction
 *    - FileIndexer for Lucene document creation
 *    - FileMetadata for metadata extraction
 *
 * 3. Performance: <100ms per file update
 *    - Current full scan: 52 seconds for 8,934 files (~5.8ms/file)
 *    - Incremental: single file analysis + index update
 *    - Batch commit: every 5 seconds or 50 documents
 */
public class IncrementalIndexer implements Closeable {

    private final IndexWriter writer;
    private final AnalyzerRegistry analyzers;
    private final FileIndexer fileIndexer;
    private DirectoryReader reader;  // NRT reader, refreshed periodically

    public void indexFile(Path file, Path workspaceRoot) { ... }
    public void removeFile(Path file, Path workspaceRoot) { ... }
    public void commit() { ... }
    public DirectoryReader getReader() { ... }  // For search queries
}
```

#### `FileWatcherService.java`

```java
package io.exoreaction.synthesis.daemon;

/**
 * Abstraction over file watching backends.
 * Supports PathWatcher (preferred) and Java WatchService (fallback).
 *
 * Event flow:
 *   File change detected
 *     -> Debounce buffer (1-2 seconds)
 *       -> Batch delivered to IncrementalIndexer
 *         -> Index updated
 *           -> Health monitor updated
 */
public class FileWatcherService {

    public interface FileChangeHandler {
        void onBatch(List<FileChangeEvent> events);
    }

    public record FileChangeEvent(
        Path path,
        ChangeType type,  // CREATED, MODIFIED, DELETED
        Instant timestamp
    ) {}

    // Debouncing: collect events, deliver as batch after quiet period
    private final ScheduledExecutorService debounceExecutor;
    private final Map<Path, FileChangeEvent> pendingEvents;
    private final long debounceMs;  // Default: 1000ms for daemon (vs 300ms for watch)
}
```

### 4.4 Shared Index Access Pattern

**Problem:** The daemon holds the Lucene IndexWriter open. If a user runs `synthesis search` from CLI simultaneously, it needs read access. If the user runs `synthesis scan` (full rebuild), it needs write access.

**Solution: NRT Reader + Lock File Protocol**

```
Daemon holds:
  - IndexWriter (exclusive write access)
  - NRT DirectoryReader (refreshed every 5 seconds)

CLI search:
  - Opens DirectoryReader directly (read-only, Lucene supports concurrent readers)
  - No conflict with daemon's writer

CLI scan (full rebuild):
  - Checks for daemon PID file
  - If daemon running: asks daemon to pause, rebuild, resume (via IPC)
  - If daemon not running: takes write lock normally

IPC mechanism:
  - Simple: Unix signal (SIGUSR1 = pause/rebuild, SIGUSR2 = resume)
  - Better: Lock file protocol (~/.synthesis/daemon.lock)
  - Best: Unix domain socket for bidirectional communication
```

**Recommended IPC for v1:** Lock file protocol (simplest, cross-platform).

```
~/.synthesis/daemon.lock     # Daemon writes PID; CLI checks before write ops
~/.synthesis/daemon.signal   # CLI writes "PAUSE" or "REBUILD"; daemon polls
```

### 4.5 Daemon Configuration

New section in `config.yaml`:

```yaml
daemon:
  # Whether to start in daemon mode
  enabled: false

  # File watcher backend: "pathwatcher" or "watchservice"
  watcherBackend: "pathwatcher"

  # Debounce interval for daemon mode (ms)
  # Higher than watch mode because daemon prioritizes efficiency over immediacy
  debounceMs: 1000

  # Commit interval (seconds) - how often to flush index to disk
  commitIntervalSeconds: 5

  # Maximum batch size before forced commit
  maxBatchSize: 50

  # Health check interval (seconds)
  healthCheckIntervalSeconds: 30

  # API server (optional)
  api:
    enabled: false
    port: 8080
    bindAddress: "127.0.0.1"  # localhost only by default

  # AI analysis in daemon mode (only if AI variant)
  ai:
    # Enable real-time AI analysis of changes
    realtimeAnalysis: false
    # Maximum AI spend per day (USD)
    budgetPerDayUsd: 5.00
    # Minimum file age before AI analysis (seconds)
    # Prevents analyzing files being actively edited
    cooldownSeconds: 30
```

### 4.6 Daemon CLI Commands

```
synthesis daemon start [OPTIONS]     Start the daemon
  --foreground                       Run in foreground (for systemd)
  --workspace <path>                 Workspace root (default: current)
  --port <port>                      API server port (default: 8080)
  --no-api                           Disable API server

synthesis daemon stop                Stop the running daemon
  --force                            Force stop (SIGKILL)

synthesis daemon restart             Restart the daemon

synthesis daemon status              Show daemon status
  Output:
    Status: Running
    PID: 12345
    Uptime: 3h 42m
    Workspace: /home/totto/Documents
    Files indexed: 8,934
    Events processed: 1,247
    Last event: 2m ago
    Index size: 11.6 MB
    Memory: 256 MB / 512 MB max

synthesis daemon logs                Tail daemon logs
  --lines <n>                        Number of lines (default: 50)
  --follow                           Follow mode (like tail -f)
```

### 4.7 Systemd Service File

```ini
[Unit]
Description=Synthesis Knowledge Infrastructure Daemon
Documentation=https://github.com/exoreaction/Synthesis
After=network.target

[Service]
Type=simple
User=totto
Group=totto
WorkingDirectory=/home/totto

# Run daemon in foreground mode (systemd manages the process)
ExecStart=/usr/local/bin/synthesis daemon start --foreground --workspace /home/totto/Documents
ExecStop=/usr/local/bin/synthesis daemon stop

# Graceful shutdown: 30 second timeout
TimeoutStopSec=30

# Restart on failure
Restart=on-failure
RestartSec=10

# Resource limits
MemoryMax=512M
CPUQuota=25%

# Logging to journal
StandardOutput=journal
StandardError=journal
SyslogIdentifier=synthesis

[Install]
WantedBy=multi-user.target
```

Installation:
```bash
# Generate and install service file
synthesis daemon install-service
# This creates /etc/systemd/system/synthesis.service
# and runs: systemctl daemon-reload && systemctl enable synthesis

# Start
sudo systemctl start synthesis

# Check status
systemctl status synthesis
# or
synthesis daemon status
```

---

## 5. Go-to-Market Strategy

### 5.1 Market Segmentation & Prioritization

```
                    High Willingness to Pay
                           |
    Segment 3              |              Segment 4
    Enterprise             |              Ultimate
    (Air-gapped Daemon)    |              (Self-learning Daemon)
    $100K-500K/yr          |              $200K-1M/yr
    Large corps, SIs       |              Elite teams, AI labs
    Build: Q4 2026         |              Build: Q3 2026
                           |
  ----Low Urgency----------+----------High Urgency----
                           |
    Segment 1              |              Segment 2
    Core                   |              Pro
    (Air-gapped CLI)       |              (Self-learning CLI)
    $50K-200K/yr           |              $49-499/mo
    Defense, banks, gov    |              Startups, SDD teams
    Build: Q2 2026         |              SHIPPED (current)
                           |
                    Low Willingness to Pay
```

### 5.2 Build Sequence & Business Justification

**Phase 1: Synthesis Core (Q2 2026) -- 2-3 weeks**

- **Why first:** Lowest engineering effort (subtract, don't add). Air-gapped is a subset of current product. Maven profile + source exclusion = done.
- **Revenue unlock:** Enterprise security segment has highest per-deal value ($50K-200K). One defense contractor contract pays for 6 months of development.
- **Strategic value:** "Zero cloud dependency" is a unique positioning that no competitor can match without a complete rewrite.
- **Sales channel:** Thor's IASA chairman network gives direct access to enterprise architects at defense and banking organizations.
- **Validation needed:** SpareBank 1 security team (Vidar already engaged, they rolled out Claude Code org-wide -- they have security review processes).

**Phase 2: Daemon Infrastructure (Q3 2026) -- 6-8 weeks**

- **Why second:** Daemon mode is the differentiation play. It transforms Synthesis from a CLI tool into a development infrastructure service.
- **Revenue unlock:** Both Enterprise and Ultimate segments require daemon mode. Building the daemon unlocks $1B + $3B = $4B total addressable market.
- **Strategic value:** "Always-on, always-fresh index" is a compelling pitch for enterprises tired of stale code search. Sourcegraph requires server deployment; Synthesis runs as a lightweight local daemon.
- **Engineering approach:** Build daemon for Ultimate first (all features), then subtract AI for Enterprise (trivial, already know how from Phase 1).

**Phase 3: Synthesis Enterprise (Q4 2026) -- 1-2 weeks**

- **Why third:** Combine Phase 1 (air-gapped) + Phase 2 (daemon). Almost zero additional engineering.
- **Revenue unlock:** Largest per-deal segment ($100K-500K/year). One Fortune 500 contract = $500K.
- **Sales channel:** IASA board network, SpareBank 1 reference case, Item Consulting referral network.

### 5.3 Pricing Strategy

| Product | OSS? | License | Price | Justification |
|---------|------|---------|-------|---------------|
| **Core** | MIT | Enterprise support contract | $50K-200K/yr | Air-gapped enterprises expect paid support; MIT means they can audit source |
| **Pro** | MIT | Free (workshops/consulting revenue) | $0 (OSS) | Community growth, workshop pipeline, consulting revenue |
| **Enterprise** | BSL 1.1 | Enterprise license | $100K-500K/yr | Daemon mode is enterprise value; BSL converts to MIT after 3 years |
| **Ultimate** | BSL 1.1 | Enterprise license + SaaS | $499-2,499/mo or $200K-1M/yr | Premium AI features justify premium pricing |

**BSL 1.1 (Business Source License):** Source available, free for non-production use, converts to MIT after 3 years. Used by MariaDB, HashiCorp, CockroachDB. Protects commercial value while maintaining transparency.

### 5.4 Competitive Positioning

```
                        Cloud Required
                            |
    GitHub Copilot          |          Sourcegraph + Cody
    (AI pair programmer)    |          (Code search + AI)
    No search/index         |          Requires server
    $19-39/user/mo          |          $49-500/user/mo
                            |
  ----No Daemon-------------+----------Daemon/Server----
                            |
    Synthesis Core/Pro      |          Synthesis Enterprise/Ultimate
    (Local CLI, MIT)        |          (Local daemon, BSL)
    Zero cloud dependency   |          Zero cloud + always-on
    $0-200K/yr              |          $100K-1M/yr
                            |
                        Local Only
```

**Unique positioning:** Synthesis is the only tool in the bottom-left quadrant (local, no cloud, no server). Enterprise/Ultimate is the only tool in the bottom-right quadrant (local daemon, no cloud). The entire bottom row is uncontested territory.

### 5.5 Sales Playbook by Segment

**Core (Defense/Banking):**
- Lead with security audit: "Here's our JAR. Run `jar tf synthesis-core.jar` -- no Anthropic, no OkHttp, no cloud SDKs."
- Reference: SpareBank 1 (if Vidar converts to customer)
- Channel: IASA board, Nordic defense conferences, banking technology forums
- Close timeline: 3-6 months (enterprise procurement cycles)

**Pro (Startups/SDD):**
- Lead with workshop: "We'll teach your team SDD in a day. Synthesis is included."
- Reference: Tvimenning (40K signed), Item Consulting (30 developers)
- Channel: LinkedIn (proven 43% conversion), JavaZone, NDC, conferences
- Close timeline: 1-4 weeks (startup speed)

**Enterprise (Large Corps):**
- Lead with ROI: "Your 200 developers spend 40-60% of time searching. Synthesis reduces that by 92%. Do the math."
- Reference: SpareBank 1 (200 developers, org-wide Claude Code)
- Channel: IASA board, consulting firm partnerships, CTO networks
- Close timeline: 3-9 months (enterprise procurement + security review)

**Ultimate (Elite Teams):**
- Lead with demo: "Watch this. I'm editing a file. Synthesis just told me I broke 7 references and suggested fixes."
- Reference: eXOReaction internal use (managing 100+ repos, lib-pcb 8,934 files)
- Channel: Conference circuit (JavaZone, Devoxx, GOTO), Anthropic partnership
- Close timeline: 1-3 months (technical decision-makers move fast)

---

## 6. Implementation Roadmap

### 6.1 Q2 2026: Synthesis Core (Air-gapped Variant)

**Duration:** 2-3 weeks
**Dependencies:** None (subtractive work on existing codebase)
**Version target:** 1.2.0

#### Week 1: Maven Profile + Build System

| Day | Task | Deliverable |
|-----|------|-------------|
| 1 | Create `air-gapped` Maven profile | `pom.xml` with profile definition |
| 1 | Exclude Anthropic SDK from shaded JAR | Build produces 110 MB JAR |
| 2 | Implement conditional command registration | `SynthesisApp.java` loads AI commands via reflection |
| 2 | Create `SynthesisAppCore` entry point (alternative) | Explicit command list without AI commands |
| 3 | Handle `ClaudeClient` references in non-AI commands | `ScanCommand`, `ExportCommand`, `AnalyzeCommand` compile without `ClaudeClient` |
| 3 | Strategy: wrap AI references in try-catch ClassNotFoundException | Or extract to interface with no-op implementation |
| 4 | Build and test air-gapped JAR | `mvn clean package -P air-gapped` succeeds |
| 5 | Verify all non-AI commands work | `search`, `relate`, `graph`, `watch`, `scan`, `export` all pass |

**Key technical challenge:** Commands like `ScanCommand` reference `ClaudeClient.isVisionSupported()` and `ClaudeClient.estimateVisionCost()`. These are static utility methods that don't actually call the API. Options:
1. Move static utilities to a `VisionUtils` class in `util/` package (cleanest)
2. Use reflection to check for class availability at runtime
3. Duplicate the 10-line utility methods in ScanCommand (quickest)

**Recommended:** Option 1. Create `util/VisionUtils.java` with the format-checking and cost-estimation methods. These have no dependency on the Anthropic SDK.

#### Week 2: Testing + Documentation

| Day | Task | Deliverable |
|-----|------|-------------|
| 1 | Write integration tests for air-gapped build | Verify no Anthropic classes in JAR |
| 1 | Test: `jar tf synthesis-core.jar | grep -i anthropic` returns nothing | Automated in CI |
| 2 | Update README.md with variant documentation | Installation guide for air-gapped |
| 2 | Create `docs/guides/AIR-GAPPED-DEPLOYMENT.md` | Security audit documentation |
| 3 | Create compliance statements | GDPR, SOC2, HIPAA applicability notes |
| 3 | Update MCP/LSP documentation | Confirm they work without AI |
| 4 | Create sales one-pager for air-gapped | PDF: "Synthesis Core: Zero-Dependency Knowledge Infrastructure" |
| 5 | Release `v1.2.0` with dual artifacts | `synthesis-1.2.0.jar` (Pro) + `synthesis-core-1.2.0.jar` (Core) |

#### Week 3 (Optional): Telemetry Exclusion Profile

| Day | Task | Deliverable |
|-----|------|-------------|
| 1 | Create `no-telemetry` Maven profile | Excludes Slack SDK |
| 1 | Make TelemetryService gracefully no-op when Slack classes missing | Runtime check |
| 2 | Test: air-gapped + no-telemetry combined profile | `mvn clean package -P air-gapped,no-telemetry` |
| 3 | Document telemetry exclusion for ultra-secure deployments | Compliance documentation |

**Milestone:** Synthesis Core v1.2.0 released. Two JARs published. Air-gapped customers can install and run with zero cloud dependencies.

---

### 6.2 Q3 2026: Daemon Mode (Self-learning + Air-gapped)

**Duration:** 8 weeks
**Dependencies:** Q2 air-gapped profile (for air-gapped daemon variant)
**Version target:** 1.3.0

#### Weeks 1-2: Daemon Infrastructure

| Week | Task | Deliverable |
|------|------|-------------|
| 1.1 | Create `daemon/` package with core classes | `DaemonService`, `DaemonConfig`, `DaemonHealthMonitor` |
| 1.2 | Implement PID file management | Write/read/validate PID, check process alive |
| 1.3 | Implement signal handling (SIGTERM, SIGINT) | Graceful shutdown on signal |
| 1.4 | Create `DaemonCommand` CLI subcommand | `start`, `stop`, `status`, `logs`, `restart` |
| 1.5 | Test daemon start/stop lifecycle | Unit + integration tests |
| 2.1 | Implement `--foreground` mode for systemd | DaemonService runs in calling thread |
| 2.2 | Create systemd service file template | Generated by `synthesis daemon install-service` |
| 2.3 | Create launchd plist template (macOS) | Generated by `synthesis daemon install-service` |
| 2.4 | Test systemd integration | Service starts, stops, restarts, survives reboot |
| 2.5 | Health file writing (`daemon-health.json`) | JSON with uptime, files, events, errors |

#### Weeks 3-4: FileWatcher Abstraction + PathWatcher Integration

| Week | Task | Deliverable |
|------|------|-------------|
| 3.1 | Define `FileWatcherBackend` interface | `watch()`, `stop()`, `isRunning()` |
| 3.2 | Implement `JavaWatchServiceBackend` | Port from existing `WatchCommand` |
| 3.3 | Add PathWatcher dependency to `pom.xml` | Cantara Maven repository already configured |
| 3.4 | Implement `PathWatcherBackend` | PathWatcher singleton, event mapping |
| 3.5 | Handle PathWatcher single-dir limitation | Multiple instances for multi-workspace |
| 4.1 | Implement `FileWatcherService` (abstraction) | Debouncing, batching, backend selection |
| 4.2 | Implement debounce buffer | Collect events for 1-2 seconds, deliver as batch |
| 4.3 | Test: rapid file creation (1000 files in 10 seconds) | No event loss, proper batching |
| 4.4 | Test: PathWatcher vs. WatchService comparison | Performance, reliability, resource usage |
| 4.5 | Configuration: `daemon.watcherBackend` setting | Switch between backends in config.yaml |

#### Weeks 5-6: Incremental Indexer + Shared Index

| Week | Task | Deliverable |
|------|------|-------------|
| 5.1 | Implement `IncrementalIndexer` | Single-file index, update, delete |
| 5.2 | Implement NRT reader pattern | `DirectoryReader.openIfChanged()` for concurrent access |
| 5.3 | Implement batch commit strategy | Every 5 seconds OR 50 documents |
| 5.4 | Test: incremental index correctness | File add/modify/delete reflected in search |
| 5.5 | Test: concurrent access (daemon writing, CLI reading) | No lock contention |
| 6.1 | Implement `SharedIndexManager` | Coordinates daemon writer + CLI readers |
| 6.2 | Lock file protocol for CLI write operations | `~/.synthesis/daemon.lock` |
| 6.3 | Handle: `synthesis scan` while daemon running | Daemon yields write lock, rebuilds, resumes |
| 6.4 | Performance benchmarking | Target: <100ms per file, <500ms per batch |
| 6.5 | Memory profiling | Target: <512 MB steady-state for 10K-file workspace |

#### Weeks 7-8: Integration, Polish, Release

| Week | Task | Deliverable |
|------|------|-------------|
| 7.1 | Integrate daemon with MCP server | MCP queries shared index (NRT reader) |
| 7.2 | Integrate daemon with LSP server | LSP queries shared index |
| 7.3 | RealtimeAnalyzer (AI variant only) | Broken link detection, outdated ref detection |
| 7.4 | AI budget management | `daemon.ai.budgetPerDayUsd` enforcement |
| 7.5 | End-to-end testing: daemon + MCP + Claude Code | Full workflow validation |
| 8.1 | Air-gapped daemon profile | Combine `air-gapped` + daemon (exclude AI) |
| 8.2 | Documentation: `docs/guides/DAEMON-MODE.md` | Complete setup guide |
| 8.3 | Documentation: `docs/guides/DAEMON-ENTERPRISE.md` | Enterprise deployment patterns |
| 8.4 | Performance report | Benchmarks, resource usage, scaling characteristics |
| 8.5 | Release v1.3.0 | Four JARs: Core, Pro, Enterprise, Ultimate |

**Milestone:** Synthesis v1.3.0 released. Four product variants shipping. Daemon mode operational with PathWatcher integration.

---

### 6.3 Q4 2026: Scale & Partnerships

**Duration:** 12 weeks
**Dependencies:** v1.3.0 shipped with all four variants
**Version target:** 1.4.0 - 2.0.0

#### Weeks 1-4: Enterprise Features

| Week | Task | Deliverable |
|------|------|-------------|
| 1 | HTTP API server for daemon | REST endpoints: search, relate, graph, stats, health |
| 2 | WebSocket notification channel | Real-time broken link / stale doc notifications |
| 3 | Prometheus metrics endpoint | `/metrics` for Grafana dashboards |
| 4 | Docker image + Kubernetes manifests | `docker pull exoreaction/synthesis:1.4.0` |

#### Weeks 5-8: Platform Partnerships

| Week | Task | Deliverable |
|------|------|-------------|
| 5-6 | Anthropic partnership pitch | MCP integration proven, SpareBank 1 validation, formal proposal |
| 7-8 | JetBrains IntelliJ plugin | LSP client + Synthesis branding on JetBrains Marketplace |

#### Weeks 9-12: Scale

| Week | Task | Deliverable |
|------|------|-------------|
| 9-10 | VSCode extension | LSP client + Synthesis branding on VS Marketplace |
| 11-12 | Enterprise pilot program | 5-10 large enterprises, air-gapped daemon deployments |

---

## 7. Business Model Comparison

### 7.1 Revenue Model by Variant

| Product | License | Pricing Tier | Year 1 Target | Year 3 Target |
|---------|---------|-------------|---------------|---------------|
| **Core** | MIT + support | $50K-200K/yr per org | $200K (2-4 enterprises) | $2M (20 enterprises) |
| **Pro** | MIT (free) | Workshops + consulting | $500K (10 workshops + 2 retainers) | $2M (scale workshops) |
| **Enterprise** | BSL 1.1 | $100K-500K/yr per org | $300K (2-3 enterprises) | $5M (30 enterprises) |
| **Ultimate** | BSL 1.1 | $499-2,499/mo per team | $200K (30-50 teams) | $3M (500 teams) |
| **Combined** | -- | -- | **$1.2M** | **$12M** |

### 7.2 Unit Economics

| Product | CAC | ACV | LTV (3yr) | LTV/CAC | Payback |
|---------|-----|-----|-----------|---------|---------|
| Core | $10K (enterprise sales) | $100K | $300K | 30x | 1 month |
| Pro | $500 (workshop funnel) | $2K | $6K | 12x | 3 months |
| Enterprise | $25K (enterprise sales) | $250K | $750K | 30x | 1 month |
| Ultimate | $2K (conference/content) | $12K | $36K | 18x | 2 months |

### 7.3 Revenue Trajectory (NOK)

| Quarter | Core | Pro | Enterprise | Ultimate | Total |
|---------|------|-----|-----------|----------|-------|
| Q2 2026 | -- | 675K-1,075K (pipeline) | -- | -- | 675K-1,075K |
| Q3 2026 | 500K-1M (first licenses) | 500K (workshops) | -- | 100K-200K (early adopters) | 1.1M-1.7M |
| Q4 2026 | 500K | 500K | 1M-2.5M (first Enterprise) | 200K-500K | 2.2M-4M |
| Q1 2027 | 1M | 750K | 2M-5M | 500K-1M | 4.25M-7.75M |

### 7.4 Cost Structure

| Cost Category | Monthly | Annual | Notes |
|--------------|---------|--------|-------|
| Thor (founder) | 120K NOK | 1,440K NOK | Full-time, all development + sales |
| AI costs (Claude API) | 5-15K NOK | 60-180K NOK | For Pro/Ultimate AI features |
| Infrastructure | 2K NOK | 24K NOK | GitHub, domains, CI/CD, Cantara Maven repo |
| Marketing | 5K NOK | 60K NOK | LinkedIn ads, conference travel |
| Legal (BSL licensing) | 10K NOK one-time | 10K NOK | Lawyer review of BSL 1.1 terms |
| **Total** | **132-142K NOK** | **1,594-1,714K NOK** |

**Breakeven:** ~1.6M NOK/year = 2 Enterprise licenses OR 4 Core licenses OR 40 workshops.
Current pipeline (675K-1,075K in Q2 alone) suggests breakeven in H1 2026.

---

## 8. Open Questions for Decision

### 8.1 Licensing Strategy (DECISION REQUIRED)

**Question:** Should daemon mode (Enterprise/Ultimate) use BSL 1.1 or remain MIT?

| Option | Pros | Cons |
|--------|------|------|
| All MIT | Maximum adoption, community goodwill, no legal complexity | Anyone can resell; no protection of enterprise features |
| BSL 1.1 for daemon | Protects commercial value, converts to MIT after 3 years | Reduces adoption, "source available not open source" criticism |
| Dual license (MIT + commercial) | MIT for non-commercial, commercial license for production | Complex, confusing, legal overhead |
| MIT + paid support only | Everything is free; monetize support/consulting | Hard to capture value from self-service enterprises |

**Recommendation:** MIT for Core/Pro (maximum adoption, workshop funnel). BSL 1.1 for Enterprise/Ultimate (protects $100K+ deal sizes). This matches the HashiCorp/CockroachDB/MariaDB model.

### 8.2 AI Features Scope (DECIDED -- see AI-SCOPE-ANALYSIS.md)

**Decision:** Three-phase incremental AI roadmap. See [AI-SCOPE-ANALYSIS.md](./AI-SCOPE-ANALYSIS.md) for the comprehensive analysis of 120+ Claude Code skills and the resulting roadmap.

**Phase 1 -- Deterministic Intelligence (Q2 2026, 4-6 weeks):**
- Companion file generation (`.synthesis.md` for all media files)
- Enhanced relationship detection (temporal, naming, structural)
- Content fingerprinting (Lucene MoreLikeThis)
- Bidirectional cross-references
- `synthesis enrich` command
- Works in ALL editions including air-gapped

**Phase 2 -- Local Media Enrichment (Q3 2026, 6-8 weeks):**
- Whisper transcription integration (16x realtime, validated)
- PDF slide extraction (pdftoppm)
- Campaign batch processing (5.5x speedup, validated)
- Works in Pro/Enterprise/Ultimate

**Phase 3 -- Semantic Intelligence (Q4 2026, 4-6 weeks):**
- Claude Vision for images ($0.005/image, 92% accuracy, validated)
- Claude Vision for PDFs (200-1500 line analysis per PDF)
- AI-generated summaries
- Works in Pro/Ultimate only

**Key insight:** The highest-value capabilities (companion files, relationship detection) are deterministic and require zero AI. This validates the air-gapped edition strategy.

### 8.3 PathWatcher vs. Alternatives (DECIDED -- WatchService)

**Decision:** Continue with Java WatchService. Do not add PathWatcher dependency.

**Rationale (detailed in [AI-SCOPE-ANALYSIS.md Section 8](./AI-SCOPE-ANALYSIS.md#8-file-watcher-decision-watchservice-vs-pathwatcher)):**
1. Multi-directory support is essential for Synthesis workspaces
2. Already implemented and tested (WatchCommand.java, 628 lines including daemon support)
3. 300ms debounce effectively handles file-completion problem
4. No additional dependency needed (critical for air-gapped edition)

**File-completion enhancement:** Added size-stability check for large files (compare file size at two time points, 200ms apart).

**Future consideration:** If PathWatcher adds multi-directory support, reconsider. The watch loop can be refactored to accept a `FileChangeSource` interface.

### 8.4 API Server Scope (DECISION REQUIRED)

**Question:** Should daemon mode include an HTTP API server?

| Scope | Effort | Value | When |
|-------|--------|-------|------|
| No API (daemon just updates index) | 0 | Sufficient for v1.3.0 | Q3 2026 |
| Read-only HTTP API (search, relate, graph, stats) | 2 weeks | Enables web UI and CI/CD | Q4 2026 |
| Full API + WebSocket | 4 weeks | Real-time notifications to IDE | Q4 2026 |

**Recommendation:** No API in v1.3.0. Daemon updates the index; MCP/LSP servers query it. Add HTTP API in v1.4.0 (Q4 2026) when enterprise customers request web UI or CI/CD integration.

### 8.5 Platform Partnership Timing (DECISION REQUIRED)

**Question:** When to approach Anthropic with a formal partnership proposal?

| Timing | Pros | Cons |
|--------|------|------|
| Now (Q2 2026) | MCP proven, SpareBank 1 validation, first-mover | No daemon mode yet, limited traction data |
| After daemon (Q3 2026) | Stronger differentiation, more customer data | Someone else might approach them first |
| After 10 enterprise customers (Q4 2026) | Strongest negotiating position, proven revenue | Late; Anthropic may have built or partnered already |

**Recommendation:** Informal conversation now (Q2) via existing Anthropic contacts. Formal partnership proposal after daemon ships (Q3). The MCP integration is already a strong signal; daemon mode with real-time index freshness makes it irresistible.

### 8.6 Telemetry in Enterprise (DECIDED -- no-op in air-gapped)

**Decision:** Air-gapped editions use `TelemetryService.createNoOp()` which silently discards all events. Implemented.

**Implementation:**
- `SynthesisApp.main()` checks `isAirGapped()` before creating telemetry service
- Air-gapped mode: `TelemetryService.createNoOp()` returns no-op instance (client UUID: "air-gapped")
- No outbound connections from air-gapped editions (no telemetry, no update checks, no pilot approval)
- Future: optional internal telemetry can be added via configurable webhook endpoint

---

## Appendix A: File-Level AI Dependency Map

Classes that must be handled for air-gapped build:

| File | AI Dependency Type | Air-gapped Strategy |
|------|-------------------|---------------------|
| `ai/ClaudeClient.java` | Hard (imports `com.anthropic.*`) | **Exclude from compilation** |
| `ai/DirectedSynthesisEngine.java` | Hard (uses `ClaudeClient`) | **Exclude from compilation** |
| `ai/ReadmeGenerator.java` | Hard (uses `ClaudeClient`) | **Exclude from compilation** |
| `ai/PromptTemplates.java` | None (pure string manipulation) | **Keep** (useful for MCP context building) |
| `cli/AskCommand.java` | Hard (imports `ClaudeClient`) | **Exclude from compilation** |
| `cli/PerspectivesCommand.java` | Hard (imports `DirectedSynthesisEngine`) | **Exclude from compilation** |
| `cli/ScanCommand.java` | Soft (`ClaudeClient.isVisionSupported()`) | **Refactor**: move static utils to `VisionUtils` |
| `cli/ExportCommand.java` | Soft (`ClaudeClient.create()` in Optional) | **Refactor**: guard with `try-catch ClassNotFoundException` |
| `cli/AnalyzeCommand.java` | Soft (`ClaudeClient.create()` in Optional) | **Refactor**: guard with `try-catch ClassNotFoundException` |
| `cli/ExtractSlidesCommand.java` | Soft (optional `ClaudeClient`) | **Refactor**: guard with `try-catch ClassNotFoundException` |
| `analyzer/PresentationExtractor.java` | Soft (optional `ClaudeClient` param) | **Keep**: parameter is already nullable |

**Total files to modify:** 4 (ScanCommand, ExportCommand, AnalyzeCommand, ExtractSlidesCommand)
**Total files to exclude:** 5 (ClaudeClient, DirectedSynthesisEngine, ReadmeGenerator, AskCommand, PerspectivesCommand)
**Total files unchanged:** 73 out of 83

---

## Appendix B: Estimated JAR Sizes

| Variant | Dependencies Included | Estimated Size |
|---------|----------------------|---------------|
| Core (air-gapped, no telemetry) | Lucene, Picocli, JGit, Jackson, PDFBox, LSP4J | ~100 MB |
| Core (air-gapped, with internal telemetry) | + Slack SDK | ~110 MB |
| Pro (current) | All dependencies | 137 MB |
| Enterprise (air-gapped + daemon) | Lucene, Picocli, JGit, Jackson, PDFBox, LSP4J, PathWatcher | ~105 MB |
| Ultimate (all features) | All dependencies + PathWatcher | ~140 MB |

---

## Appendix C: Competitive Landscape

| Tool | Local Search | AI Q&A | Daemon Mode | Air-gapped | MCP | LSP | Price |
|------|-------------|--------|-------------|-----------|-----|-----|-------|
| **Synthesis Core** | Yes | No | No | **Yes** | Yes | Yes | $50K-200K/yr |
| **Synthesis Pro** | Yes | Yes | No | No | Yes | Yes | Free (MIT) |
| **Synthesis Enterprise** | Yes | No | **Yes** | **Yes** | Yes | Yes | $100K-500K/yr |
| **Synthesis Ultimate** | Yes | Yes | **Yes** | No | Yes | Yes | $499-2,499/mo |
| Sourcegraph | Server | Via Cody | Server-based | Possible (self-hosted) | No | No | $49-500/user/mo |
| GitHub Copilot | No (cloud) | Yes (cloud) | No | No | No | No | $19-39/user/mo |
| Cursor | No (cloud) | Yes (cloud) | No | No | No | No | $20-40/user/mo |
| Codeium | No (cloud) | Yes (cloud) | No | No | No | No | Free-$40/user/mo |

**Synthesis advantage:** Only tool offering local-first search with optional AI, both CLI and daemon modes, both air-gapped and cloud-connected variants, and both MCP and LSP server protocols. No competitor covers more than 3 of these 6 dimensions.

---

*Document version 1.1. Last updated 2026-02-15.*
*Companion document: [AI-SCOPE-ANALYSIS.md](./AI-SCOPE-ANALYSIS.md) (AI capabilities deep-dive, 120+ skills analyzed)*
*Next review: After Q2 2026 air-gapped variant ships.*
