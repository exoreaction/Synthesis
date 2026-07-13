# Synthesis Release Notes

**From first commit to v1.42.0 -- the full story.**

This document covers the complete development history of Synthesis, from its first commit on February 14, 2026 through the current release. Synthesis grew from a simple file indexer into a comprehensive knowledge infrastructure platform with 76 CLI subcommands, 4,700+ tests, 23 Flyway migrations (V1-V24, V7 reserved), and three fat JARs (CLI, MCP server, LSP server).

---

## Table of Contents

- [v1.0.0 -- Genesis (February 14, 2026)](#v100----genesis-february-14-2026)
- [v1.0.1 -- Distribution and Skills (February 14, 2026)](#v101----distribution-and-skills-february-14-2026)
- [v1.0.2 -- Media and Directed Synthesis (February 14, 2026)](#v102----media-and-directed-synthesis-february-14-2026)
- [v1.0.3 -- Bundled Video Support (February 14, 2026)](#v103----bundled-video-support-february-14-2026)
- [v1.1.0 -- Protocol Servers (February 15, 2026)](#v110----protocol-servers-february-15-2026)
- [v1.2.0 -- AI Features (February 15, 2026)](#v120----ai-features-february-15-2026)
- [v1.2.1 through v1.2.3 -- UX and Workspace Polish (February 15, 2026)](#v121-through-v123----ux-and-workspace-polish-february-15-2026)
- [v1.4.0 -- File Tracking and Change Reporting (February 16, 2026)](#v140----file-tracking-and-change-reporting-february-16-2026)
- [v1.5.x -- Sub-Workspaces and Smart Exclusions (February 16, 2026)](#v15x----sub-workspaces-and-smart-exclusions-february-16-2026)
- [v1.6.x -- Executive Summaries and Organization Discovery (February 16, 2026)](#v16x----executive-summaries-and-organization-discovery-february-16-2026)
- [v1.7.x -- Dashboard, Research, and Reports (February 16-17, 2026)](#v17x----dashboard-research-and-reports-february-16-17-2026)
- [v1.8.x -- Staging Pipeline (February 17-18, 2026)](#v18x----staging-pipeline-february-17-18-2026)
- [v1.9.x -- Test Expansion and Operational Maturity (February 18-19, 2026)](#v19x----test-expansion-and-operational-maturity-february-18-19-2026)
- [v1.10.x -- Knowledge Integrity and Code Intelligence (February 19-20, 2026)](#v110x----knowledge-integrity-and-code-intelligence-february-19-20-2026)
- [v1.11.x -- Self-Organizing Workspace (February 20, 2026)](#v111x----self-organizing-workspace-february-20-2026)
- [v1.12.x -- Knowledge Graph (February 21-22, 2026)](#v112x----knowledge-graph-february-21-22-2026)
- [v1.13.0 -- Bugfixes: Rebalance + Health (February 22, 2026)](#v1130----bugfixes-rebalance--health-february-22-2026)
- [v1.13.1 -- CKG Dogfooding Fixes (February 22, 2026)](#v1131----ckg-dogfooding-fixes-february-22-2026)
- [v1.18.2 -- Session Lifecycle Integration (February 28, 2026)](#v1182--session-lifecycle-integration-february-28-2026)
- [v1.21.0 -- Episodic Memory: Claude Sessions (March 3, 2026)](#v1210--episodic-memory-claude-sessions-march-3-2026)
- [v1.22.0 -- Skills Match + Team Context](#v1220--skills-match--team-context-march-2026)
- [v1.23.0 -- Agent Dispatch Planner](#v1230--agent-dispatch-planner-march-2026)
- [v1.24.0 -- Reflect: Self-Maintaining Skill Library](#v1240--reflect-self-maintaining-skill-library-march-2026)
- [v1.26.0 -- Interactive Skills Graph + Subagent Session Linking](#v1260--interactive-skills-graph--subagent-session-linking-marchapril-2026)
- [v1.27.1 -- Topic Health/Triage + 3 New Bundled Skills](#v1271--topic-healthtriage--3-new-bundled-skills-april-10-2026)
- [v1.28.0 -- Explicit API Key Guidance](#v1280--explicit-api-key-guidance-april-11-2026)
- [v1.29.0 -- Git Signals + Notion Foundation (April 21, 2026)](#v1290--git-signals--notion-foundation-april-21-2026)
- [v1.30.0 -- Notion OAuth (April 21, 2026)](#v1300--notion-oauth-april-21-2026)
- [v1.32.x -- Maintenance Releases (April 22-27, 2026)](#v132x--maintenance-releases-april-22-27-2026)
- [v1.33.0 -- MCP Multi-Workspace Resolution (May 17, 2026)](#v1330--mcp-multi-workspace-resolution-may-17-2026)
- [v1.34.0 -- Bootstrap Context + Pilot Hardening (May 25, 2026)](#v1340--bootstrap-context--pilot-hardening-may-25-2026)
- [v1.34.1 through v1.35.0 -- Dependency Maintenance (May 28 - June 14, 2026)](#v1341-through-v1350--dependency-maintenance-may-28---june-14-2026)
- [v1.36.0 -- KCP v0.21 + OpenAI-Compatible Providers (June 14, 2026)](#v1360--kcp-v021--openai-compatible-providers-june-14-2026)
- [v1.37.x -- KCP Fixes and Agent-Harness Polish (June 14 - July 6, 2026)](#v137x--kcp-fixes-and-agent-harness-polish-june-14---july-6-2026)
- [v1.38.0 -- KCP v0.25 Full Stack (July 6, 2026)](#v1380--kcp-v025-full-stack-july-6-2026)
- [v1.40.0 -- Semantic Search Hardening (July 8, 2026)](#v1400--semantic-search-hardening-july-8-2026)
- [v1.41.0 -- Grounded Ask + Episodic Memory Tools (July 9, 2026)](#v1410--grounded-ask--episodic-memory-tools-july-9-2026)
- [v1.42.0 -- KCP Retrieval Benchmarks (July 9, 2026)](#v1420--kcp-retrieval-benchmarks-july-9-2026)
- [Current State](#current-state)

---

## v1.0.0 -- Genesis (February 14, 2026)

**Date:** 2026-02-14
**Commits:** 13ff7ae through 3c33a47
**PRs merged:** #1 through #4

Synthesis began as a response to a concrete problem: the lib-pcb project had generated 8,934 files in 11 days of AI-assisted development, and no existing tool could make that output navigable. The first commit landed on a Friday morning. By end of day, the core platform was functional.

### What was built

**Core indexing engine** -- The foundational loop that still drives Synthesis today:
- `DirectoryScanner` walks file trees with configurable include/exclude patterns
- `FileMetadata` records capture size, type, language, content hash, and modification time
- `ScanState` enables incremental updates by comparing filesystem state against saved snapshots
- `SearchIndex` wraps Apache Lucene 10.1.0 for full-text search with field-level boosting

**File analyzers** -- Specialized content extractors for each file type:
- `MarkdownAnalyzer` -- headings, links, keywords, word count, front matter
- `CodeAnalyzer` -- LOC, imports, declarations, language detection, framework identification
- `YamlAnalyzer` -- type detection (Docker Compose, GitHub Actions, Kubernetes, Claude skills)
- `PdfAnalyzer` -- text extraction via PDFBox, page count, title, author
- `GenericAnalyzer` -- fallback for unrecognized formats

**CLI framework** -- Built on picocli with subcommands:
- `synthesis init` -- workspace initialization with config generation
- `synthesis scan` -- full index build (200-300 files/second)
- `synthesis search` -- Lucene query syntax with type and scope filters
- `synthesis maintain` -- incremental change detection and re-indexing
- `synthesis export` -- JSON, Markdown, architecture doc, onboarding guide output
- `synthesis status` -- index health and statistics

**Organizational intelligence** -- Multi-company workspace support:
- `Organization`, `Client`, `Product` entity model
- `OrganizationScanner` auto-discovers structure from directory conventions
- `DownloadsClassifier` classifies files by organization signals
- Client status detection: `ACTIVE`, `PAST`, `OPPORTUNITY`, `SIGNED` from directory names

**Relationship mapping** -- Bi-directional dependency tracking:
- `GraphBuilder` extracts import/reference relationships from source files
- `GraphRenderer` produces Mermaid diagrams of module dependencies
- `synthesis relate` shows what breaks if you change a file
- Cross-repo dependency analysis across multiple Git repositories

**AI integration** -- Optional Claude-powered features:
- `ClaudeClient` wraps the Anthropic Java SDK
- `ReadmeGenerator` for AI-powered README creation
- `PromptTemplates` centralizes all AI prompts
- `synthesis ask` for natural language Q&A over the workspace
- `synthesis analyze` for AI-powered project analysis

**Architecture intelligence:**
- `InsightsEngine` detects anti-patterns, god classes, circular dependencies, dead code, test gaps
- `synthesis insights` provides workspace health metrics

**Git integration** via JGit:
- `synthesis watch` monitors file changes for real-time re-indexing
- `synthesis diff` integrates with Git refs
- `synthesis changed --since` queries Git history

**Test suite:** 53 files, 282 tests covering all analyzers, commands, and core components.

### Technical decisions

- **Java 21+** -- records, switch expressions, text blocks for clean domain modeling
- **Apache Lucene 10.1.0** -- production-grade full-text search, not a toy prototype
- **picocli** -- annotation-driven CLI with completion, help generation, and nested subcommands
- **Fat JAR via maven-shade** -- single deployable artifact, no classpath complexity
- **Local-first** -- zero cloud dependency for core features; AI features degrade gracefully

### Why it matters

This first release established the pattern that would define Synthesis: solve a real problem, build it fast, test it thoroughly, and ship it. The 9,854-line initial PR (#2) was the largest single change in the project's history, and it worked on first deploy.

---

## v1.0.1 -- Distribution and Skills (February 14, 2026)

**Date:** 2026-02-14
**PR:** #5
**Changes:** +3,072 lines across 18 files

### What was added

**Interactive init** -- Guided workspace setup with prompts for name, type, and configuration:
- `InteractiveConfirmation` utility for yes/no prompts
- Workspace type detection (general, plugin-ecosystem, monorepo, multi-project)

**Claude Code skill generation:**
- `SkillGenerator` analyzes a codebase and produces Claude Code skill YAML files
- `SkillInstaller` deploys skills to `~/.claude/skills/`
- `SkillTemplate` provides 17 skill templates covering search, relate, graph, insights, and more
- `synthesis learn` auto-generates skills; `synthesis export-skills --overwrite` installs them

**Distribution system:**
- Installation scripts (`install.sh`, `install.ps1`) for Linux/macOS and Windows
- Uninstall scripts with cleanup of PATH and shell config
- Launcher script with daily auto-update checks

**Pilot licensing and telemetry:**
- `ApprovalService` for pilot program management
- `ClientUUID` for anonymous instance identification
- `TelemetryEvent` and `TelemetryConfig` for usage tracking
- Slack integration for telemetry reporting

### Why it matters

This release transformed Synthesis from a developer tool into a distributable product. The skill generation system was a strategic choice: by teaching Claude Code how to use Synthesis, every AI-assisted session becomes more effective. The install script made adoption a single `curl` command.

---

## v1.0.2 -- Media and Directed Synthesis (February 14, 2026)

**Date:** 2026-02-14
**PR:** #6
**Changes:** +3,985 lines across 29 files

### What was added

**Image analysis:**
- `ImageAnalyzer` extracts EXIF metadata, dimensions, camera info, GPS coordinates, IPTC keywords
- Supports JPEG, PNG, GIF, SVG, WebP, TIFF, BMP
- Metadata-extractor library integration for deep EXIF/IPTC/XMP parsing

**Video analysis:**
- `VideoAnalyzer` extracts duration, resolution, codec information
- ffprobe integration for accurate media metadata
- Companion transcript support for searchability
- Supports MP4, MOV, AVI, MKV, WebM, FLV, WMV

**PDF enhancements:**
- `PresentationExtractor` for slide deck processing
- `synthesis extract-slides` command for presentation PDFs

**Directed synthesis:**
- `DirectedSynthesisEngine` generates analytical perspectives to find knowledge gaps
- Not summarization of existing content but generation of new analytical angles

### Why it matters

With media support, Synthesis could index everything a team produces -- not just code and documents but screenshots, diagrams, video recordings, and presentations. The directed synthesis engine established a pattern of AI doing analysis, not just retrieval.

---

## v1.0.3 -- Bundled Video Support (February 14, 2026)

**Date:** 2026-02-14
**PR:** #7

### What was added

**Bundled ffprobe binaries** for Linux x64, macOS, and Windows x64:
- `BundledBinaryManager` auto-extracts the appropriate binary from the JAR on first use
- Cached to `~/.synthesis/bin/` for subsequent runs
- Fallback to system-installed ffprobe if bundled version unavailable

### Technical note

This increased JAR size to approximately 136 MB (compressed), a conscious trade-off for zero-dependency video support. The `synthesis status` command reports ffprobe status: `Bundled (FFmpeg 7.0.2)` or system-installed path.

---

## Multi-Perspective Documentation (February 14-15, 2026)

**PRs:** #8, #9
**Not a version release** -- documentation-only changes (~64,000 words)

Nine role-specific guides were added to `docs/perspectives/`:

1. **Engineering Manager** -- team adoption, metrics, rollout planning
2. **Architect** -- architecture intelligence, dependency analysis
3. **Executive** -- ROI brief, strategic implications
4. **DevOps** -- CI/CD integration, monitoring
5. **Product Manager** -- feature discovery, roadmap alignment
6. **Workshop Facilitator** -- 2hr, 4hr, 8hr workshop formats
7. **Sales** -- client demonstrations, proof points
8. **Security** -- privacy model, air-gapped operation
9. **Developer** -- quick start and user guide

Additionally, 10 perspective-specific visual assets (8 infographics + 2 PDFs) and a 153 MB visual asset library were added. This documentation set was designed for the workshop and enterprise sales pipeline.

---

## v1.1.0 -- Protocol Servers (February 15, 2026)

**Date:** 2026-02-15
**PRs:** #10, #11, #12
**Changes:** +3,058 lines across 16 files (MCP/LSP alone)

### What was added

**MCP server** (Model Context Protocol):
- `SynthesisMCPServer` implements JSON-RPC 2.0 over stdio
- `SynthesisToolHandler` provides 7 tools: `search`, `relate`, `graph`, `stats`, `ask`, `enrich`, `explain`
- Compatible with Claude Code, Cursor, Aider, and other MCP-capable agents
- Separate fat JAR: `synthesis-mcp-server.jar`

**LSP server** (Language Server Protocol):
- `SynthesisLanguageServer` implements LSP 3.17
- `SynthesisTextDocumentService` provides document links, hover metadata, diagnostics, go-to-definition
- `SynthesisWorkspaceService` provides workspace symbol search
- Architecture alerts as diagnostics (circular deps, god classes)
- Separate fat JAR: `synthesis-lsp-server.jar`

**Update infrastructure:**
- `UpdateCommand` with version checking, rollback, and force-update
- `InstallationFingerprint` for environment tracking
- `VersionManifest` for version management
- Daily background update checks via the launcher

**Air-gapped architecture:**
- Edition detection (Community, Professional, Enterprise)
- Feature gating based on detected edition
- Works fully offline with graceful degradation of AI features

**Daemon mode:**
- PID management for background execution
- `synthesis watch` for real-time file monitoring

### Why it matters

The MCP server was the strategic inflection point. By exposing Synthesis through the Model Context Protocol, every AI agent in the ecosystem could use Synthesis as its knowledge backbone. The LSP server extended this to IDEs. Together, they made Synthesis the bridge between human developers, AI agents, and IDE tools.

---

## v1.2.0 -- AI Features (February 15, 2026)

**Date:** 2026-02-15
**PRs:** #13, #14, #15
**Database:** V1 (initial schema)

### What was added

**AI-powered commands:**
- `synthesis enrich` -- generate `.synthesis.md` companion files for binary assets (images, videos, PDFs)
- `synthesis explain --file` -- natural language code explanations with relationship context
- `synthesis perspectives` -- multi-perspective analysis (examines a question from 8 angles)
- Semantic code search with vector embeddings

**Continuous architecture intelligence:**
- `ArchitectureAlert` system for real-time monitoring
- Anti-pattern detection during `watch` mode

**Local media enrichment (Phase 2):**
- **Whisper integration** -- speech-to-text transcription for audio/video (99 languages)
- **Tesseract integration** -- OCR text extraction from images and screenshots
- **Poppler integration** -- scanned PDF processing via `pdftoppm`
- `EnrichmentLevel` system: `NONE` (offline), `LOCAL` (local tools), `CLOUD` (API-powered)
- Zero-cloud enrichment for air-gapped environments

**Metrics system:**
- `MetricsCollector` and `MetricsDatabase` for usage tracking
- `synthesis metrics` command for metrics display
- SQLite-backed persistence (V1 migration)

### Why it matters

The enrichment pipeline made binary files searchable. A screenshot of an architecture diagram could now be OCR'd and indexed. A meeting recording could be transcribed and made findable by content. The local enrichment tier was critical for enterprise adoption -- organizations with strict data policies could still use the full feature set.

---

## v1.2.1 through v1.2.3 -- UX and Workspace Polish (February 15, 2026)

**Date:** 2026-02-15
**PRs:** #16, #17
**Database:** V2 (workspace tags)

### What was added

**Unified workspace system:**
- Multi-workspace management with workspace discovery
- `synthesis status --all` for cross-workspace overview
- Aggregate statistics across workspaces
- `WorkspaceMetadata` for workspace identity and type

**Enhanced status command:**
- Default status shows aggregates and workspace summary
- File type distribution, language breakdown
- External tool detection (ffprobe, Whisper, Tesseract)
- Storage overhead reporting

**UX improvements:**
- Suppressed Java native access and logging warnings for clean output
- Better error messages and progress reporting
- Workspace type detection and display

---

## v1.4.0 -- File Tracking and Change Reporting (February 16, 2026)

**Date:** 2026-02-16
**PR:** #18
**Changes:** +2,946 lines across 23 files
**Database:** V3 (file tracking and changelog)

*Note: v1.3.0 was an internal version number; the public release jumped from v1.2.3 to v1.4.0.*

### What was added

**File movement tracking:**
- `FileMovementTracker` detects file renames and moves via content hash comparison
- Hash-based detection with a 7-day safety period before confirming movements
- `DetectionMethod` enum: `HASH_MATCH`, `NAME_SIMILARITY`, `CONTENT_ANALYSIS`
- `MovementStatus`: `DETECTED`, `CONFIRMED`, `REJECTED`
- Audit trail for all movement events

**Cross-workspace change reporting:**
- `SnapshotManager` takes daily snapshots of workspace state
- `ChangeReportGenerator` computes deltas between snapshots
- `SignificanceClassifier` classifies changes by impact level
- `synthesis changelog --since 7d` for cross-workspace change reports
- Smart filtering to reduce noise

**New commands:**
- `synthesis track` -- view and manage file movement tracking
- `synthesis changelog` -- generate change reports across workspaces

**Database schema:**
- V3 migration adds 6 tables for tracking and changelog data
- Movement records, snapshot state, change events

### Why it matters

With thousands of files being generated and reorganized daily, knowing what moved where -- and why -- became essential. The 7-day safety period prevented premature confirmations, and the cross-workspace change report gave executives a single view of what changed across the entire organization.

---

## v1.5.x -- Sub-Workspaces and Smart Exclusions (February 16, 2026)

**Date:** 2026-02-16
**PRs:** #19, #20, #21
**Database:** V4 (sub-workspaces)

### What was added

**Sub-workspace architecture (v1.5.0):**
- `SubWorkspaceResolver` for nested workspace detection
- Hierarchical workspace organization (parent/child relationships)
- Configurable discovery paths for workspace scanning
- `STAGING` workspace type for intermediate processing areas

**Smart exclusion defaults (v1.5.1):**
- `EcosystemDetector` identifies project types (Maven, Node, Python, Rust, Go, etc.)
- `SmartExclusions` auto-configures exclude patterns based on detected ecosystem
- `Ecosystem` enum covers 10+ build systems
- No more manually adding `node_modules/` or `target/` to exclude lists

**Bundled skills distribution:**
- Skills packaged inside the JAR for zero-config deployment
- `synthesis export-skills --overwrite` installs to `~/.claude/skills/`

**Visual workspace tree (v1.5.3):**
- ASCII tree rendering in `status --all` view
- Watch daemon detection and status display
- Configurable workspace discovery paths

### Why it matters

The sub-workspace architecture solved a real problem: a document workspace (`~/Documents`) containing references to source workspaces (`/src/exoreaction/`, `/src/cantara/`) needed to know about all of them without flattening them into a single index. Smart exclusions removed the most common source of indexing complaints.

---

## v1.6.x -- Executive Summaries and Organization Discovery (February 16, 2026)

**Date:** 2026-02-16
**PRs:** #23, #25
**Database:** V5 (summary cache)

### What was added

**Executive summary system (v1.6.0):**
- `SummaryEngine` generates AI-powered summaries from 8 perspectives
- `SummaryCache` stores results in SQLite with TTL-based expiration
- `SummaryRenderer` formats output for terminal and export
- `CodebaseProfile` analyzes workspace characteristics for context
- MCP integration: `summary` tool available to AI agents
- `synthesis summary` command with `--perspective` filter

**Eight summary perspectives:**
1. Executive -- strategic value and ROI
2. Technical -- architecture and implementation quality
3. Operations -- deployment and monitoring status
4. Security -- threat model and vulnerability posture
5. Product -- feature completeness and roadmap
6. Team -- productivity and collaboration patterns
7. Risk -- technical debt and compliance gaps
8. Innovation -- emerging patterns and opportunities

**Client-to-codebase auto-discovery (v1.6.1):**
- `ClientCodebaseResolver` parses `CODEBASE-INDEX.md` files
- Maps client names to repository paths automatically
- Enables cross-referencing between business documents and source code

### Why it matters

The executive summary system made Synthesis useful to non-developers. A VP of Engineering could run one command and get a security-focused or operations-focused view of the entire codebase. The 8-perspective model ensured every stakeholder role had a relevant entry point.

---

## v1.7.x -- Dashboard, Research, and Reports (February 16-17, 2026)

**Date:** 2026-02-16 through 2026-02-17
**PRs:** #26, #27, #28, #30, #32, #34, #35
**Database:** V6 (research cache)

This was a dense release cycle -- 8 minor versions in 2 days -- that transformed Synthesis from an indexing tool into a business intelligence platform.

### v1.7.0 -- Testing and Stability

- Comprehensive JUnit tests for `ClientCodebaseResolver`
- Foundation for the features that followed

### v1.7.1 -- Dashboard

- `synthesis dashboard` as alias for `synthesis status`
- Friendlier entry point for non-technical users

### v1.7.2 -- Update Mechanism Fix

- Switched update mechanism from GitHub releases to Cantara Maven repository
- More reliable version resolution and download

### v1.7.3 -- WBS Navigation and Upcoming Events

- Work Breakdown Structure navigation in dashboard
- `synthesis upcoming` command for scheduled events and deadlines
- Organization hierarchy browsing

### v1.7.4 -- Rich Client Summaries

- Client activity summaries with git fetch integration
- Repository freshness detection
- Dashboard enrichment with client-level detail

### v1.7.5 -- Organization Enrichment

- `synthesis org enrich` command for AI-powered organization analysis
- Fixed `ClientCodebaseResolver` path resolution

### v1.7.6 -- Research and Reports

**Research engine:**
- `synthesis research` -- multi-pass AI deep research report generation
- `ResearchEngine` performs iterative analysis across indexed files
- `ResearchPrompts` with specialized prompts for different research topics
- `ResearchCache` with SQLite persistence (V6 migration)
- Generates prompts for external AI tools (ChatGPT, NotebookLM)

**Report engine:**
- `synthesis report` -- AI-verified business document generation
- Report generation with source verification
- Entity-scoped reports (`--product`, `--client`)

**Credential store:**
- `synthesis credentials` -- persist API keys with XOR obfuscation
- Avoids repeated `ANTHROPIC_API_KEY` entry

### v1.7.7 through v1.7.8 -- Report Refinements

- Excluded README.md noise from report document discovery
- Entity reports: `synthesis report --product` and `synthesis report --client`
- Scoped report generation for specific products or clients

### Why it matters

The v1.7.x series marked the transition from "developer tool" to "knowledge platform." Research reports, business documents, and client summaries meant that Synthesis served sales, product, and executive functions alongside engineering. The credential store removed the last friction point for AI-powered features.

---

## v1.8.x -- Staging Pipeline (February 17-18, 2026)

**Date:** 2026-02-17 through 2026-02-18
**PRs:** #37, #38, #39, #55, #56, #57, #58, #59
**Database:** V8 (report cache)

### What was added

**Staging pipeline (v1.8.0-v1.8.2):**
- `synthesis staging ingest` -- ingest files from a staging area (e.g., `~/Downloads`)
- `synthesis staging route` -- route staged files to target workspaces using configurable rules
- `synthesis staging rename` -- AI-powered file renaming for meaningful filenames
- Config-driven routing rules with glob patterns and destination mappings
- Exclusion of `.synthesis/` internals and excludePatterns from staging operations

**Vision enhancements:**
- Image resizing for files exceeding the 5 MB API base64 limit
- AI text-based description for visual PDFs and PNG scans
- Routing-focused image enrichment prompts

**Distribution improvements:**
- Embedded `exo` shell wrapper in `install.sh` (no separate GitHub download)
- Updated multi-perspective guides

**Report module fixes (v1.8.3-v1.8.4):**
- Date anchoring for report temporal context
- `--no-cache` flag to bypass and avoid writing cache
- Co-located auto-save: reports saved alongside source documents
- Resolved 12 report module issues (#42-#53) across 6 fix waves
- Truncation detection for reports hitting token limits
- Integration tests for report generation
- Configurable `report.outputDir` setting

### Why it matters

The staging pipeline solved the "Downloads problem." Files downloaded from the web, received via email, or generated by AI tools could be automatically ingested, classified, and routed to their correct workspace location. Combined with AI-powered renaming, files with meaningless names like `Screenshot 2026-02-17.png` became `architecture-diagram-authentication-flow.png`.

---

## v1.9.x -- Test Expansion and Operational Maturity (February 18-19, 2026)

**Date:** 2026-02-18 through 2026-02-19
**PRs:** #58 through #84
**Tests:** 1,054 to 2,325 (120% increase)

This series focused on hardening, testing, and operational reliability.

### v1.9.0 -- Report Configuration

- `report.outputDir` for configurable report save location
- Truncation detection for AI-generated reports

### v1.9.1 -- Test Expansion

**Massive test suite growth:**
- 8 waves of test additions: +1,237 tests (1,054 to 2,291)
- Coverage across all major subsystems:
  - Architecture and insights analysis
  - Staging pipeline
  - Changelog and tracking
  - Configuration and workspace management
  - Search and indexing
  - Utilities and file handling

### v1.9.2-v1.9.3 -- Skills and Staging Polish

- Project-level Claude Code skills and CLAUDE.md added to the repository
- Bundled skill sync to v1.8.4
- `_processed` suffix: routed staging files renamed with `*_processed.*` instead of deleted
- Preserves originals for audit and recovery

### v1.9.4 -- Architecture Documentation

- Architecture and security deep-dive report (1,561 lines)
- 143 additional tests for architecture and insights subsystems
- Origin story documentation with metrics and SDD methodology context

### v1.9.5 -- Staging Integration Tests

- `StagingManager` integration tests for end-to-end staging validation
- Test count reached 2,325

### v1.9.6 -- Content-Intelligence Routing

- `DownloadsClassifier.classifyWithCompanion()` -- reads companion `.synthesis.md` files for routing hints
- Fallback classification when explicit rules do not match
- `autoClassify: true` with configurable `classificationThreshold` (default 0.5)
- Above-threshold matches auto-route; below-threshold become suggestions

### v1.9.7 -- Explain Enhancements

- `synthesis explain --file` now resolves bare filenames via the search index
- Real relationship context from `RelateCommand` integrated into explanations
- Exact filename match preferred over score-based matching

### v1.9.8 -- Temporal Summaries

- `synthesis summary --since` for time-bounded context
- Parses `7d`, `24h`, `2w`, `3m`, ISO dates (`2026-01-15`)
- Loads `ChangeEvent` data from `SnapshotManager` and injects change context into AI prompts
- Not just temporal filtering of output, but temporal enrichment of input

### v1.9.10-v1.9.11 -- MCP Schema Fixes

- Added missing `since` parameter to MCP summary tool schema
- Added missing `subWorkspace` parameter to MCP search tool schema
- Both fixes critical for AI agent interoperability

*Note: v1.9.9 was skipped due to a release process issue.*

### v1.9.12 -- Operational Features

- `synthesis enrich --path` and `--exclude` for targeted enrichment
- CLI metrics tracking for command usage analysis
- Report staleness warning when cached reports are outdated
- `synthesis maintain --update-activity-log` for automatic ACTIVITY-LOG.md updates

### v1.10.0 -- Concurrent Search

- Fixed concurrent read-only search to eliminate `write.lock` contention
- Multiple agents or users can now search simultaneously without blocking
- Critical for MCP server scenarios with parallel tool invocations

**`exo ask` -- Conversational RAG:**
- Shell wrapper for conversational Q&A over the knowledge base
- Search, source, stream answer, follow-up loop
- Executive-friendly interface for non-technical users

### Why it matters

The v1.9.x/v1.10.0 series transformed Synthesis from a working prototype into a production system. The test suite more than doubled. The `_processed` suffix preserved audit trails. The concurrent search fix made multi-agent scenarios reliable. And `exo ask` gave non-developers a zero-learning-curve entry point.

---

## v1.10.x -- Knowledge Integrity and Code Intelligence (February 19-20, 2026)

**Date:** 2026-02-19 through 2026-02-20
**PRs:** #85 through #144
**Issues filed:** #93-#113 (20 issues from benchmark session)

This was the most architecturally significant release cycle, driven by findings from a 90-session benchmark study.

### The Benchmark Discovery

A 6-phase benchmark (90 sessions) measured the impact of different Synthesis access modes on AI agent task completion. Key findings:

- All sessions scored 3/3 on correctness, but with different efficiency profiles
- Skills-based agents were faster but sometimes less thorough
- CLI-based agents (using `synthesis search` directly) scored +11% vs baseline
- Knowledge documents scored -15% vs baseline (stale context problem)
- The product insight: **"The market for faster search is crowded. The market for trustworthy AI context is empty."**

### v1.10.1 -- Validation and Discovery

**Workspace discovery:**
- `synthesis discover` finds unindexed Git repositories in configured search paths
- Suggests `synthesis init` for each discovered repository
- Workspace ancestor suggestion when `-d` points to non-workspace directory

**Documentation drift detection:**
- `synthesis validate` detects when skills and docs diverge from source code
- Flags stale claims, outdated version numbers, missing documentation

### v1.10.2 -- Knowledge Integrity

**Gap detection:**
- `synthesis validate --gaps` finds source files with no skill coverage
- Identifies undocumented areas of the codebase

**Confidence metadata:**
- Search results now include confidence scores and freshness timestamps
- MCP responses enriched with trust signals for AI agents

**Knowledge integrity checking:**
- `synthesis validate --integrity` verifies factual claims in skill files against source code
- Detects three failure modes: stale data, silent gaps, ambiguous claims

**Architectural improvements:**
- `RelationService` extracted from `RelateCommand` for reuse
- Tiered skill loading: separate core, command, and reference skills
- Import-graph path tracing between two classes
- `--violations` flag for layering violations and circular dependency detection
- Transitive change impact analysis (full blast radius)
- Co-change graph from Git commit history (implicit coupling detection)

### v1.10.3 -- Unified Knowledge Graph Foundation

**Graph intelligence (10 features):**
- Unified knowledge graph indexing doc-to-code entity relationships
- `synthesis watch` -- file watcher with debounce and auto-reindex
- Test coverage overlay mapping source files to test classes
- Cross-format entity linking: SQL to Java, YAML to Java
- Knowledge graph reconciliation during `maintain` runs
- Unified response enrichment with documentation graph + confidence in single traversal

**Benchmark documentation:**
- 4-axis correctness rubric for evaluation
- MCP session guide for agents
- Subagent research patterns

### v1.10.5-v1.10.6 -- Staging Intelligence

- Excluded `.synthesis.md` companion files from staging ingest/route
- `staging route --enrich-first` generates companions before classification
- Enhanced vision prompt for routing-focused image enrichment
- Keyword-based routing rules for staging route
- Fixed staging ingest for re-downloaded files with same name

### Why it matters

The v1.10.x series was a pivot from "fast retrieval" to "trustworthy context." The benchmark proved that speed without accuracy is counterproductive. Knowledge integrity checking, confidence metadata, and documentation drift detection addressed the root cause: AI agents need to know how much to trust their context, not just how fast they can get it.

---

## v1.11.x -- Self-Organizing Workspace (February 20, 2026)

**Date:** 2026-02-20
**PRs:** #157 through #210
**Issues resolved:** #148-#191

### v1.11.0 -- Workspace Hygiene

**Health command:**
- `synthesis health` -- workspace health audit with 0-100 score
- `synthesis health --fix-config` -- interactive auto-fix for common issues
- E001: phantom sub-workspace path detection and removal
- E002: build artifact detection (independently of `.synthesisignore`)

**Cleanup commands:**
- `synthesis prune --yes` -- remove empty directories
- `synthesis sweep --dry-run` / `--yes` -- identify and archive stale root-level files
- Route files to meaningful destinations before falling back to archive

### v1.11.1 -- File Organization

**Naming and TTL:**
- `synthesis naming` -- file naming consistency analysis
- `synthesis ttl set "*.tmp" --days 7` -- time-to-live management
- `synthesis consolidate "Entity"` -- gather scattered files into canonical locations
- `synthesis archive audit` -- archive space and duplicate audit

**Directory identity system:**
- Per-directory `.synthesis.md` files declare what each directory accepts
- `DirectoryIdentity`, `DirectoryIdentityParser`, `DirectoryNameVocabulary`
- `DirectorySignalExtractor` analyzes directory contents for type signals
- `DirectoryScorer` scores file-to-directory matches
- `DirectoryIdentityRouter` routes files using identity-based matching
- `ScopeChecker` and `ScopeResolver` for organizational scoping

**Self-organizing workspace:**
- `synthesis sync` discovers directories and writes/updates identity files
- `SweepCommand` uses `DirectoryIdentityRouter` before falling back to archive
- `MaintainCommand --rebalance` moves files from archive back to active directories (score >= 0.7)
- Frozen subtrees excluded from rebalance (`old-*`, `snapshot-*`, `frozen-*`)

### v1.11.2 -- Documentation

- Comprehensive documentation update for v1.11.1 features
- 4,331 lines of new test code for the identity system

### v1.11.3 -- Foundation Wave

**TDD infrastructure:**
- Unified identity model across all workspace types
- Grouped help display for CLI commands
- `downloads` alias for staging commands
- Wave 1 foundation for the maintain orchestrator

### v1.12.0 -- Maintain Orchestrator

**9-phase maintenance pipeline:**
1. **Ingest** -- pull files from staging area
2. **Route** -- classify and route staged files
3. **Sync** -- update directory identity files
4. **Sweep** -- archive stale root-level files
5. **Rebalance** -- move archive files back to active directories
6. **Expire** -- TTL-based file expiry
7. **Index** -- re-scan and update search index
8. **Track** -- file movement tracking
9. **Prune** -- remove empty directories

**Orchestrator options:**
- `--dry-run` -- preview all phases without making changes
- `--quiet` -- one summary line (for cron jobs)
- `--json` -- machine-readable output (for monitoring)
- `--skip-downloads` -- skip Ingest and Route phases
- `--skip-git` -- skip Git fetch for client codebases

**Guided first-run setup:**
- 5-phase `synthesis init` wizard
- Interactive configuration with sensible defaults
- Workspace type detection and smart defaults

### Why it matters

The self-organizing workspace was the most ambitious feature in Synthesis. Instead of manually filing documents, directories declared what they wanted, and the system routed files to matching locations. The 9-phase maintain pipeline made this automatic: a single `synthesis maintain` command runs the entire lifecycle. Combined with cron scheduling, workspaces became self-managing.

---

## v1.12.x -- Knowledge Graph (February 21-22, 2026)

**Date:** 2026-02-21 through 2026-02-22
**Commits:** 50+ commits across Phases P1-P4 and CKG-1 through CKG-4
**Database:** V10 (directory centroids), V11 (virtual membership/routing feedback), V12 (directory classification), V13 (code knowledge graph)
**Tests added:** 138 new tests (CKG) + knowledge graph tests

This was the culmination of the Synthesis architecture: a full knowledge graph spanning both document workspaces and source code repositories.

### Phase 1 -- Routing Infrastructure (P1-01 through P1-08)

**Routing unification:**
- Retired `SubjectBasedRouter` in favor of unified `DirectoryIdentityRouter`
- `RoutingContext`, `RoutingDecision`, and `RoutingConfidence` introduced
- `synthesis route-explain` command for debugging routing decisions
- Normalized `DirectoryScorer` output to 0.0-1.0 range
- Confidence-weighted transient merge with depth guard
- `MediaTypes` shared constants extracted

### Phase 2 -- Semantic Centroids and Wants (P2-01 through P2-09)

**Directory centroids** (what a directory IS):
- `DirectoryCentroid` computed from file enrichment signatures
- `EnrichmentSignatureExtractor` aggregates topics, named entities, document types
- `CentroidComputer` ranks topics and entities by frequency
- V10 migration: `directory_centroids` and `file_enrichment_signatures` tables

**Directory wants** (what a directory WANTS TO BECOME):
- `DirectoryWants` inferred from README, directory name, parent centroid, and overrides
- `WantsBootstrapper` with 4-tier cold-start strategy
- Want satisfaction = topicCoverage * 0.5 + entityCoverage * 0.3 + gapsFilled * 0.2

**Centroid-based scoring:**
- `DirectoryScorer` enhanced with centroid similarity
- `DirectoryIdentityParser` extended for centroid and wants blocks
- `synthesis describe` command for directory knowledge profiles

### Phase 3 -- Bidding and Health (P3-01 through P3-09)

**Directory bidding pull model:**
- `DirectoryBidder` uses Jaccard similarity for enrichment-based file matching
- Weights: topics 40%, entities 45%, type 10%, timeframe 5%
- Directories compete to "win" files based on want-alignment

**Routing cascade:**
1. RoutingHints (learned from feedback)
2. ConfigRules (glob/keyword patterns)
3. DirectoryBidder (enrichment-based bidding)
4. DirectoryScorer (identity-based fallback)

**Virtual membership:**
- Files can belong to multiple directories virtually
- `VirtualMembershipManager` tracks membership
- V11 migration: `virtual_memberships` and `routing_feedback` tables

**Health signals:**
- W020: Want starvation (satisfaction < 0.1)
- W021: Want drift (satisfaction < 0.4 with confident centroid)
- I020: Want fulfillment (informational)
- I021: Want conflict (competing wants)
- Directory health composite with health block in `.synthesis.md`

**Feedback system:**
- `synthesis feedback accept/reject` for routing quality improvement
- `RoutingLearner` for long-term learning from feedback

### Phase 4 -- Archetypes and Intelligence (P4-01 through P4-09)

**Directory archetypes:**
- 6 built-in patterns: `client-opportunity`, `project`, `methodology`, `marketing-campaign`, `product`, `archive`
- `ArchetypeRegistry` matches centroid+wants against patterns
- `GapAnalyzer` detects aspirational gaps (what is vs what could be)

**Intelligence commands:**
- `synthesis knowledge-graph` (alias: `kg`) -- full knowledge graph visualization
- `synthesis structure` -- structural analysis of workspace
- `synthesis evolution` (alias: `evo`) -- long-term evolution reports
- `synthesis describe` enhanced with health status and natural language

### DirectoryClassifier -- Code vs. Document Gating

A critical architectural addition that prevents knowledge graph features from polluting source code directories:

| Classification | Centroid | Wants | Health | Routing |
|---------------|----------|-------|--------|---------|
| DOCUMENT | Yes | Yes | Yes | Yes |
| CODE | No | No | No | No |
| MEDIA | Yes | No | Yes | Yes |
| GENERATED | No | No | No | No |

Detection uses a 4-tier heuristic: ancestor build files, path patterns, content signals, and a `docs/` carve-out for documentation inside code repos. V12 migration adds `classification` column to `directory_centroids`.

### Code Knowledge Graph -- CKG-1 through CKG-4

The Code Knowledge Graph is a parallel system for source code repositories, storing all metadata in SQLite only (nothing written inside source trees).

**CKG-1: Dependency Persistence** (commit `7ceb628`, +2,264 lines, 42 tests)
- `CodeGraphExtractor` parses Java source files for import, extends, implements, and annotation edges
- `CodeGraphRepository` with `INSERT OR REPLACE` semantics
- V13 migration: 4 tables (`code_dependencies`, `module_profiles`, `cross_format_links`, `code_quality_gaps`)
- `synthesis code-graph extract` with `--incremental`, `--dry-run`, `--stats`
- `relate` and `impact` commands now query SQLite first (instant) with fallback to live extraction
- Phase 10 in the maintenance pipeline for automatic incremental updates

**CKG-2: Module Profiles and Health Signals** (commit `fe44a6a`, +2,057 lines, 39 tests)
- `ModuleProfileComputer` aggregates dependencies into per-package profiles
- Fan-in, fan-out, instability (Robert C. Martin's metric), inferred purpose
- 7 health signals (C001-C021): circular dependencies, unstable core, hotspots, god packages, orphan code
- `synthesis code-graph describe` and `synthesis code-graph health` commands

**CKG-3: Quality Gap Detection** (commit `adc8212`, +1,693 lines, 28 tests)
- `QualityGapDetector` cross-references profiles, dependencies, and filesystem
- 5 gap types: missing tests, missing interfaces, undocumented high-value, missing README, missing package-info
- `CompletenessScorer` with severity-weighted penalties (HIGH: -0.30, MEDIUM: -0.15, LOW: -0.05)
- `synthesis code-graph gaps` with `--type`, `--severity`, `--score`, `--module` filters

**CKG-4: DAG Visualization** (commit `a25d788`, +1,332 lines, 29 tests)
- `DagRenderer` infers 4-tier architectural layers from instability metric
- ASCII and Mermaid output formats
- Stable Dependencies Principle violation detection
- `synthesis code-graph --cycles`, `--hotspots`, `--instability`, `--layers`, `--cross-format`
- Mermaid graph output capped at 30 packages for readability

### Why it matters

The knowledge graph was the intellectual core of Synthesis. Directories that know what they contain (centroids), what they want (wants), and what they should become (archetypes) form a self-describing organizational system. Combined with the code knowledge graph, Synthesis now understands both the content layer (documents, media, business artifacts) and the structural layer (code dependencies, module health, architectural violations). The routing cascade -- learned hints, config rules, enrichment-based bidding, identity-based scoring -- enables fully automatic file organization.

---

---

## v1.13.0 -- Bugfixes: Rebalance + Health (February 22, 2026)

**Version:** v1.13.0
**Tests:** 3,842 → 3,893 (51 new tests)
**Fixes:** #209, #212

Two significant bugfixes that were blocking real-world use of `synthesis maintain --rebalance` and `synthesis health --fix-config`.

### fix(rebalance): eliminate 275 false positives + strengthen scoring (#209)

**Problem:** `synthesis maintain --rebalance` reported 275 rebalance candidates on a real workspace — almost all false positives. Made the feature unusable in practice.

**Root causes and fixes:**

| Root cause | Fix |
|------------|-----|
| Threshold 0.5 too low — generic files matched almost anywhere | Raised to **0.7** |
| `.git` internals walked (FETCH_HEAD, packed-refs, etc.) | `.git` directories now excluded |
| `old-*` / `snapshot-*` archives incorrectly flagged | Frozen subtrees excluded at archive top level |
| Generic types (documentation, media) over-scored | Generic types: **+0.15** (was +0.30) |
| No reward for filename → directory name alignment | New filename-token scoring: up to **+0.25** |

**Result:** 275 → 6 real rebalance candidates on the same workspace.

**New scoring in `DirectoryScorer`:**
- `GENERIC_TYPES` constant: documentation, data, media, visual, report, document, config, archive, artifact
- `computeFilenameTokenScore()` — tokenizes filename, compares against dir path tokens, overlap ratio × 0.25
- Example: `synthesis-demo.mp4` now scores strongly for `products/Synthesis/media/` but weakly for generic `media/`

**Tests:** 17 new test cases in `DirectoryScorerTest`; 34 new cases in `MaintainCommandRebalanceTest`.

### fix(health): E002 `.synthesisignore` integration (#212)

**Problem:** Build-artifact directories (node_modules/, target/, etc.) were either always indexed (polluting search results) or required manual exclusion with no tooling support.

**Solution:** Full `.synthesisignore` integration with a clear design invariant:

| Layer | Behaviour |
|-------|-----------|
| `HealthCommand.findBuildArtifacts()` | Always scans — **blind to `.synthesisignore`**. Health reports disk reality. |
| `DirectoryScanner` | Respects `.synthesisignore` — excluded dirs are not indexed. |
| `health --fix-config` (E002) | Prompts `y/N` per artifact before appending to `.synthesisignore`. Never auto-applies. |
| `synthesis init` | Proposes default `.synthesisignore` (node_modules/, target/, .gradle/, etc.) with confirmation. |

**New API:**
- `DirectoryScanner.parseSynthesisIgnore(Path)` — strips comments/blanks, component-based matching at any depth
- `HealthCommand.appendToSynthesisIgnore(Path, String)` — creates/appends, idempotent (no duplicates)
- `InitCommand.proposeSynthesisIgnore(Path)` — skips if exists; auto-creates in `--yes` mode

**Tests:** 12 new tests in `HealthE002Test` covering all invariants.

---

## v1.13.1 -- CKG Dogfooding Fixes (February 22, 2026)

**Version:** v1.13.1-SNAPSHOT
**Tests:** 3,893 → 3,865 (net; see note below)
**PR:** #222
**Fixes:** #215, #216, #217, #218, #219, #220, #221

Seven issues discovered during a dogfooding session — running Synthesis against itself using `synthesis code-graph` on the Synthesis source tree. All 7 are in the Code Knowledge Graph subsystem.

> **Note on test count:** The PR added 30+ new tests but also updated existing threshold tests (C012 god-package threshold raised, see #220), resulting in a net count of 3,865 passing tests.

### Bugs fixed

**#215 — C001 circular dependency edge count corrected**

`C001_CIRCULAR_DEPENDENCY` was reporting inflated counts (e.g. "30 edges each way") because the health analyser aggregated class-level import rows instead of package-level edges. Rewrote `detectCircularDependencies()` to `GROUP BY source_package, target_package` first, then detect mutual pairs — matching the logic already used by `DagRenderer.detectCycles()`. Now correctly reports directional counts (e.g. "3 edges config→core, 10 edges core→config").

**#216 — C010 false positive: packages with tests were flagged as untested**

`C010_HIGH_FAN_IN_NO_TESTS` fired for packages that had full test coverage (e.g. `util` with 12 test files, `core` with 11). The detector was looking for packages with "test" in the package name rather than the standard Maven layout (`src/test/java/<same-package>/`). Replaced with a filesystem check: `hasTestFilesOnDisk()` mirrors the package path under `src/test/java/` and looks for `*Test.java` or `*Tests.java` files.

**#217 — Cross-format links doubled by scanning `target/`**

`synthesis code-graph --cross-format` reported ~280 links but roughly half were duplicates from both `src/main/resources/` and `target/classes/` being scanned. Added `isBuildArtifact(workspaceRoot, path)` utility and applied it as a filter in `findJavaFiles()`, `findSqlFiles()`, and the cross-format YAML/SQL walk. Covers `target/`, `build/`, and `out/` at any depth.

**#218 — `code-graph describe` required `--refresh` after every extract**

After `synthesis code-graph extract`, running `describe` without `--refresh` showed "No module profiles found." Fixed by: (a) auto-running `ModuleProfileComputer` at the end of `ExtractSub.runFull()` and `runIncremental()`, and (b) auto-computing in `DescribeSub` when `module_profiles` is empty but `code_dependencies` is populated.

### Improvements

**#219 — `inferPurpose()` heuristics: 28/31 packages showed "General purpose"**

Extended `matchSegment()` in `ModuleProfileComputer` with 13 new segment mappings:

| Segments | Purpose label |
|----------|--------------|
| changelog, tracking, track | Change tracking |
| enrichment, enrich | Media enrichment |
| summary, report | Reporting / summarization |
| research | Research engine |
| staging, stage | Staging pipeline |
| metrics, telemetry | Operational metrics |
| validate, validation | Validation |
| workspace | Workspace management |
| update | Update management |
| utils, utility | Shared utilities |
| configuration | Configuration management |

17 new test cases added to `ModuleProfileComputerTest`.

**#220 — C012 god-package threshold raised from 15 to 30**

The default of 15 was generating noise — `util` (20 files) triggered alongside `cli` (128 files). Threshold raised to 30 and extracted to a named constant `GOD_PACKAGE_THRESHOLD`. At threshold 30: `cli` (128), `org` (72), and `graph` (36) correctly fire; `report` (21), `core` (21), `util` (20), and `analyzer` (18) no longer fire.

**#221 — Helpful error when wrong `-d` path given**

`synthesis code-graph extract -d /src/exoreaction` (parent of the actual workspace) was silently exiting with code 2. Extended `WorkspaceManager.validate()` to walk down 1-2 levels and suggest child workspaces, alongside existing ancestor detection:

```
Error: '/src/exoreaction' is not a Synthesis workspace (no .synthesis/ directory found).

Did you mean one of these?
  /src/exoreaction/Synthesis    (synthesis workspace)

Run 'synthesis init' to initialize a new workspace.
```

---

## v1.18.2 — Session Lifecycle Integration (February 28, 2026)

**Commits:** 7682737, 417d2f3, 706d9fc, 5e8c4d7
**Tests:** 4,107 (all passing, 22 new)

Three new commands bridging the session lifecycle gap identified in the Ars Contexta PKM ecosystem. Together, `synthesis hooks generate` + `synthesis session-context` give every Claude Code session automatic codebase context injection on startup — without any manual steps.

### New Commands

**`synthesis session-context`** — compact codebase freshness snapshot:
- Multi-line summary or `--compact` single-line output (no newlines, designed for hook injection)
- Reports: file count, index size, recent changes, security posture, active packages
- `--since <duration>` controls lookback (default: 24h); `--no-security` for air-gapped use
- No AI dependency — fast, deterministic, <2 seconds

**`synthesis hooks generate`** — generate/merge Claude Code hook config:
- Writes `UserPromptSubmit` hook to `~/.claude/settings.json`
- Injects `synthesis session-context --compact` as the hook command
- Idempotent, merge-safe (never overwrites unrelated keys), aborts on malformed JSON
- `--dry-run` · `--type PreToolUse` · `-o <output>` options

**`synthesis claude-md refresh`** — maintain managed section in CLAUDE.md:
- Uses `<!-- synthesis-stats:start/end -->` markers — only managed section is touched
- Appends if no markers; replaces if markers exist; creates file if missing
- `--dry-run` · `-f <file>` · `--section-title` options

### MCP Tools Added (41 → 43)

- `session_context` — compact freshness snapshot via MCP (default: compact=true, since=24h)
- `hooks_generate` — hook config JSON via MCP (always dry-run — returns JSON, no disk writes)

### Documentation

- `CLAUDE-CODE-INTEGRATION.md` — new **Workflow 5: Session Lifecycle Integration**
- `CLAUDE.md` — session lifecycle commands in CLI reference
- Skills: `synthesis-development`, `synthesis-product-context` updated (version, test count, commands)
- 33 skills re-exported to `~/.claude/skills/`

---

## v1.21.0 -- Episodic Memory: Claude Sessions (March 3, 2026)

**Date:** 2026-03-03
**Migration:** V18 (`claude_sessions` + FTS5 virtual table + 3 sync triggers)
**Tests:** 4,170 (all passing, 20 new: 8 scanner + 12 store)

Synthesis has always been about making AI-generated output navigable. Versions 1.0 through 1.18 focused on workspace artifacts -- files, dependencies, knowledge graphs. But there is a second category of knowledge that accumulates during AI-augmented development: the conversations themselves. Every Claude Code session produces a JSONL transcript in `~/.claude/projects/`, and those transcripts contain decisions, rejected approaches, design rationale, and context that never makes it into committed code. Until now, that knowledge was effectively write-only.

v1.21.0 introduces the **sessions module** -- a new `io.exoreaction.synthesis.sessions` package that indexes Claude Code session history as episodic memory. This completes Layer 2 of a three-layer AI memory model:

- **Layer 1: Context window** -- working memory, present in every conversation, ephemeral
- **Layer 2: Session transcripts** -- episodic memory, indexed by `synthesis sessions` (new)
- **Layer 3: Workspace knowledge graph** -- semantic memory, indexed by `synthesis search`, `relate`, `impact`

### What was built

**`ClaudeSession`** -- an immutable Java record capturing the essential shape of a session: session ID, project directory, start/end timestamps, turn count, tool call count, tool names used, first user message (intent signal), and aggregated user text (searchable content).

**`ClaudeSessionScanner`** -- a streaming JSONL parser that walks `~/.claude/projects/**/*.jsonl` and extracts session records. Scanning is incremental: files whose `lastModified` timestamp has not changed since the last scan are skipped entirely. On first scan, 2,971 sessions were indexed in 109 seconds. Subsequent scans process only new or modified files and complete near-instantly.

**`SessionStore`** -- a synchronized SQLite DAO providing upsert, FTS5 search, filtered listing, and single-session retrieval. All public methods are `synchronized` to prevent concurrent write conflicts from MCP and CLI access. The FTS5 virtual table indexes `first_message` and `all_user_text` columns, enabling full-text search across the entire session corpus with SQLite's built-in ranking.

**V18 Flyway migration** -- creates `claude_sessions` (10 columns), `claude_sessions_fts` (FTS5 virtual table), and three triggers (`INSERT`, `UPDATE`, `DELETE`) that keep the FTS index synchronized automatically.

### CLI commands

```bash
synthesis sessions scan                        # Index ~/.claude/projects/ (incremental)
synthesis sessions search "authentication"     # FTS5 search across all sessions
synthesis sessions list                        # List recent sessions (default: 10)
synthesis sessions list --project myproject    # Filter by project directory
synthesis sessions list --since 7d             # Sessions from the last 7 days
synthesis sessions get <session-id>            # Full detail for a single session
```

### MCP tool

The `sessions` tool was registered in `SynthesisMCPServer` with two actions: `search` (requires `query` parameter) and `list` (accepts optional `project`, `since`, `limit` filters). This extends Synthesis MCP from 7 to 8 tools, and critically, it means Claude Code can search its own conversation history without requiring a second MCP server -- the same Synthesis process that serves workspace knowledge also serves episodic memory.

### Design decisions

The sessions module was deliberately built as a standalone package (`io.exoreaction.synthesis.sessions`) rather than routing through the existing Lucene indexing pipeline. Session transcripts are not workspace artifacts -- they live in a global location (`~/.claude/`), they are not associated with any single workspace, and their search semantics differ (temporal filtering, project scoping). SQLite + FTS5 was chosen over Lucene for this reason: simpler schema, no analyzer configuration, and the data naturally fits a relational model with a full-text overlay.

---

## v1.22.0 -- Skills Match + Team Context (March 2026)

**Highlights:** Two new productivity commands for AI-augmented teams.

- **`synthesis skills match "query"`** — Find top-5 relevant Claude Code skills from `~/.claude/skills/` by relevance score. Enables agents to auto-select the right skill before starting a task.
- **`synthesis team-context`** — Codebase-aware briefing for active Claude Code agent teams. `--compact` for single-line injection into Agent prompts; `--list` shows all defined teams.
- MCP tool: `team_context` added.

---

## v1.23.0 -- Agent Dispatch Planner (March 2026)

**Highlights:** `synthesis dispatch "task"` — generates an agent dispatch plan with skill recommendations, related files, team conflict check, and token estimate. `--compact` for single-line output; `--json` for machine-readable format; `--no-team` to skip conflict check.

---

## v1.24.0 -- Reflect: Self-Maintaining Skill Library (March 2026)

**Highlights:** `synthesis reflect` — scans Claude Code session history and auto-creates/updates skill YAML files in `~/.claude/skills/`. `--dry-run --compact` previews changes without writing. `--since 7d --max-new 5` tunes scan window and bloat cap.

The reflect loop closes the session→skill lifecycle: sessions are indexed by `synthesis sessions`, analyzed by `synthesis reflect`, and surfaced back to future sessions as skills.

---

## v1.26.0 -- Interactive Skills Graph + Subagent Session Linking (March/April 2026)

**Commit:** `6a84bf2` (V19), `5e8e39d` (skills-graph)
**Migration:** V19 (`session_subagent_links` table for parent-child session relationships)

- **Interactive skills-graph visualization** (`5e8e39d`) — visual graph of skill relationships and usage patterns.
- **Parent-child subagent session linking** (`6a84bf2`) — V19 Flyway migration; `session_subagent_links` table records when a session spawns subagents, enabling full agent-tree visibility in `synthesis sessions`.
- `knowledge.yaml` KCP manifest added to Synthesis repo for self-indexing (`ae292df`).
- Reflect improvements: noise filter, version batching, scan TTY detection, session freshness scoring (`c54c43e`).

---

## v1.27.1 -- Topic Health/Triage + 3 New Bundled Skills (April 10, 2026)

**PR:** #316 · **Date:** 2026-04-10

Two new commands for maintaining the Claude Code skills/memory ecosystem over time:

**`synthesis topic-health`** — HOT/WARM/COLD classification table for memory topic files. Scores each topic file by FTS hits + file age: hot files are actively referenced and recently updated; cold files are stale candidates for archival or pruning.

**`synthesis topic-triage`** — Scored triage: surfaces the top-5 topic files most urgently needing attention with a recommended action (ARCHIVE / PRUNE / UPDATE / KEEP). Options:
- `--auto` — skips if dual-threshold not met (24h + 5 sessions) — safe for cron use
- `--since 14d` — custom lookback window (default 30d)

**3 new bundled Claude Code skills** added to the JAR resources, available via `synthesis export-skills --overwrite`.

---

## v1.28.0 -- Explicit API Key Guidance (April 11, 2026)

**PR:** #317 · **Date:** 2026-04-11

When AI-powered features (`synthesis ask`, `synthesis enrich --level AI`, etc.) are invoked without a configured API key, Synthesis now shows an explicit, actionable error message with the exact command to fix it:

```
AI features require an Anthropic API key.
Run: synthesis credentials set ANTHROPIC_API_KEY <your-key>
Or set environment variable: ANTHROPIC_API_KEY=<your-key>
```

Previously, these scenarios failed with a generic HTTP error or silent skip. This change applies to all commands that call `ClaudeClient` — the key check runs before any API call is attempted.

---

## v1.29.0 -- Git Signals + Notion Foundation (April 21, 2026)

**PRs:** #319, #321, #327 · **Date:** 2026-04-21

**Git signal analysis (#321)** — Temporal intelligence mined from git history, persisted in the V20 migration (`git_file_metrics`, `git_cochange`):

- `synthesis hotspots` — files ranked by temporal hotspot score (commit churn with a 180-day decay half-life). `--refresh` recomputes from git history via `GitMetricsComputer`; `--path` filters to a prefix.
- `synthesis archaeology` — surfaces architectural decisions from commit messages (migration/inline/fix signals), with `--since` and `--min-confidence` filters.
- `synthesis impact` gains git co-change partners: files that historically change together, even without a static dependency edge.
- Bus factor analysis: contributor concentration per file.

Both V20 tables are reconstructible caches — losing them loses no information.

**Notion workspace source, Phase 1 (#327)** — Foundation for indexing Notion workspaces as a content source (V21 migration). This is the first non-filesystem source in Synthesis.

**Pilot distribution fix (#319)** — the QUICKSTART generator now pins the canonical MCP config snippet.

---

## v1.30.0 -- Notion OAuth (April 21, 2026)

**PR:** #330 (implements #328) · **Date:** 2026-04-21

`synthesis notion auth` — OAuth flow for connecting a Notion workspace, completing the authentication half of the Notion source introduced in v1.29.0.

---

## v1.32.x -- Maintenance Releases (April 22-27, 2026)

**Dates:** 2026-04-22 (v1.32.0), 2026-04-27 (v1.32.2)

v1.31.0 and v1.32.1 were never released — the version numbers were skipped during release-process iteration. v1.32.0 carried dependency updates only. v1.32.2 fixed one MCP protocol issue (#335): `notifications/*` messages from MCP clients are now silently ignored per spec instead of returning an error, which had caused noise with strict clients.

---

## v1.33.0 -- MCP Multi-Workspace Resolution (May 17, 2026)

**PR:** #342 · **Date:** 2026-05-17

In multi-workspace mode, MCP tools now resolve workspaces by name or directory basename — previously agents had to pass exact full paths, which regularly failed when the model abbreviated or guessed. This closed the most common MCP-agent friction reported by pilots.

---

## v1.34.0 -- Bootstrap Context + Pilot Hardening (May 25, 2026)

**PRs:** #337, #339, #343 · **Date:** 2026-05-25

**`bootstrap_context` (#337)** — a harness-neutral startup surface: one call that gives any AI harness (Claude Code, or others) the workspace context it needs at session start, without depending on harness-specific hook mechanisms.

**`.synthesisignore` glob patterns (#339)** — the ignore file now supports gitignore-style glob patterns, not just directory-name matching.

**Prune safety (#343)** — `synthesis prune` never deletes symlinks, plus a batch of secondary pilot UX fixes from field feedback (Pål, 2026-05-22).

---

## v1.34.1 through v1.35.0 -- Dependency Maintenance (May 28 - June 14, 2026)

**Dates:** 2026-05-28 (v1.34.1), 2026-05-30 (v1.34.2), 2026-06-14 (v1.35.0)

Three releases containing only dependency updates and release plumbing — no source changes. Kept in the timeline for completeness.

---

## v1.36.0 -- KCP v0.21 + OpenAI-Compatible Providers (June 14, 2026)

**PRs:** #344, #345 · **Date:** 2026-06-14

**KCP v0.5 → v0.21 (#345)** — Knowledge Context Protocol support upgraded with temporal filtering: units carry validity windows, and expired/superseded units are filtered from agent-facing surfaces. V22 migration adds the v0.21 fields.

**OpenAI-compatible AI provider (#344)** — AI features can now target any OpenAI-compatible endpoint (DeepSeek validated), breaking the hard dependency on the Anthropic API for `ask`, `enrich`, and friends.

---

## v1.37.x -- KCP Fixes and Agent-Harness Polish (June 14 - July 6, 2026)

**Dates:** 2026-06-14 (v1.37.0), 2026-06-19 (v1.37.1), 2026-07-06 (v1.37.2)

**v1.37.0 (#346)** — KCP temporal filtering now actually excludes inactive results (the v1.36.0 implementation computed but didn't apply the filter). **v1.37.1** was dependency maintenance.

**v1.37.2** collected agent-harness and KCP polish:
- `synthesis init` default-excludes agent-harness worktree directories in `.synthesisignore` (#351)
- Warning when subdirectory `SKILL.md` skills are invisible to `skills match`/`list` (#350)
- Warning when `knowledge.yaml` is gitignored but locally indexed (#352) — the K003 health signal
- MCP server: stderr is surfaced instead of dropped on nonzero exit; nonzero exit is treated as failure only when stdout is blank
- The repo's own `knowledge.yaml` upgraded to KCP v0.25 with per-unit intent/audience

---

## v1.38.0 -- KCP v0.25 Full Stack (July 6, 2026)

**PRs:** #353-#360 (epic #361, 7 phases) · **Date:** 2026-07-06

The largest feature release since the knowledge graph: full-stack KCP (Knowledge Context Protocol) v0.25 support, validated against the reference `kcp-agent` implementation in CI (`kcp-conformance.yml`). V23 (federation + lossless extensions) and V24 (verification results) migrations.

**The command surface:**

- `synthesis export --format kcp` — v0.25-conformant manifest generation from the Lucene index (#354)
- `synthesis kcp init` — scaffold `knowledge.yaml` from repo structure; `--batch` scaffolds a whole repo estate (#357, implements #310)
- `synthesis kcp refresh` — refresh volatile fields of generated manifests, with hand-edit protection (#357)
- `synthesis kcp verify` + `kcp gaps` — evidence engine checking manifest declarations against reality (V001-V006 + K-signals); hot files with no KCP coverage (#356)
- `synthesis kcp catalog` + `kcp federate` — externalize the cross-repo graph; root manifests federating every repo manifest, sharded above 50 repos (#358)
- `synthesis kcp plan` — ordered read plan over indexed units (RFC-0007 scoring), also exposed as the `plan_context` MCP tool, with CI plan/replay (#359)
- `synthesis kcp sign` — Ed25519 manifest signing with detached `.sig` envelope, trust tiers (TRUSTED/KNOWN/UNSIGNED/FAILED), and the G-series governance cross-check (#360)

**Trust interop proven, not claimed:** `kcp-agent plan --require-signature` verifies a Synthesis-signed manifest and rejects a tampered one — both asserted in CI against the conformance pin (0.9.0).

**Ingestion (#355):** v0.25 manifests persist losslessly — unmapped blocks land in `extensions_json` columns, federation entries in `kcp_federation`, and `synthesis kg` badges expired/superseded units with K-series health signals.

Also in this release: 29 tool/command descriptions aligned with actual behavior (#353), and the JAR-bundled CLAUDE.md refreshed to the 1.38.0 feature set.

---

## v1.40.0 -- Semantic Search Hardening (July 8, 2026)

**Date:** 2026-07-08 · *(v1.39 was never released — version number skipped)*

A fix wave focused on the semantic search / embeddings path:

- Embeddings are persisted for O(log N) HNSW semantic search instead of being recomputed (#376)
- Semantic search embeds file content, not just the summary (#375)
- `EmbeddingService` respects `ai.endpoint` config (#374)
- MCP `explain` mirrors CLI filename resolution and guards zero-match generation (#373)
- `runSynthesisCli` gets a timeout + concurrent stderr drain (#325)
- `prune` skips dot-ancestor paths in candidates (#329)

---

## v1.41.0 -- Grounded Ask + Episodic Memory Tools (July 9, 2026)

**Issue:** #371 · **Date:** 2026-07-09

Benchmark-driven improvements to agent-facing retrieval, all measured before merging:

- **Flag-gated KCP routing hints** for `search`/`ask` — manifest knowledge steers retrieval, behind a flag until benchmarks justified default-on
- **Fail-closed grounding for `ask`** — answers that can't cite indexed sources fail explicitly instead of hallucinating
- **KCP trigger-match boosting** — search/ask results matching a unit's declared `triggers` rank higher
- **`remember`/`recall` MCP tools** — episodic memory: agents can persist and retrieve session facts
- **`plan_context` session dedup** — a `known` parameter lets agents exclude units they've already read

---

## v1.42.0 -- KCP Retrieval Benchmarks (July 9, 2026)

**Date:** 2026-07-09

Adds the KCP manifest retrieval benchmark results (#371 item 3) — the measured evidence behind the v1.41.0 routing changes. Current release as of this writing; `main` carries post-release work including Kotlin code-graph extraction (#406).

---

## Current State

**Version:** v1.42.0
**Date:** July 9, 2026
**Days since first commit:** 145
**Tests:** 4,700+ (all passing)

### Commands (76 subcommands)

**Workspace lifecycle:**
`init`, `scan`, `maintain`, `status`, `health`, `dashboard`, `watch`, `discover`

**Search and discovery:**
`search`, `relate`, `impact`, `which`, `ask`, `hotspots`, `archaeology`, `discover`

**KCP (Knowledge Context Protocol):**
`kcp init`, `kcp refresh`, `kcp verify`, `kcp gaps`, `kcp catalog`, `kcp federate`, `kcp plan`, `kcp sign`, `export --format kcp`

**AI-powered analysis:**
`explain`, `perspectives`, `summary`, `research`, `enrich`

**Agent productivity:**
`skills match`, `team-context`, `dispatch`, `reflect`, `topic-health`, `topic-triage`

**Graphs and architecture:**
`graph`, `cross-repo-deps`, `architecture`, `code-graph` (with `extract`, `describe`, `health`, `gaps` subcommands)

**Change tracking:**
`track`, `changelog`, `changed`, `diff`, `sessions`

**Knowledge graph:**
`route-explain`, `describe`, `feedback`, `knowledge-graph`, `structure`, `evolution`

**Workspace hygiene:**
`sync`, `sweep`, `prune`, `consolidate`, `scatter`, `naming`, `ttl`, `archive`

**Organization and enrichment:**
`org scan`, `org list`, `org classify`, `org enrich`, `enrich`, `extract-slides`

**Staging pipeline:**
`staging ingest`, `staging route`, `staging rename`

**Export and reporting:**
`export`, `report`

**System:**
`release`, `update`, `learn`, `export-skills`, `list`, `telemetry`, `validate`, `credentials`, `metrics`

### Database Migrations

| Migration | Purpose |
|-----------|---------|
| V1 | Initial schema (metrics, workspace metadata) |
| V2 | Workspace tags |
| V3 | File tracking and changelog (6 tables) |
| V4 | Sub-workspaces |
| V5 | Summary cache |
| V6 | Research cache |
| V7 | *(reserved -- migration deleted, version permanently skipped)* |
| V8 | Report cache |
| V9 | Knowledge edges |
| V10 | Directory centroids and file enrichment signatures |
| V11 | Virtual membership and routing feedback |
| V12 | Directory classification |
| V13 | Code knowledge graph (4 tables) |
| V14 | Repo isolation |
| V15 | Security analysis (CKG-5) |
| V16 | Report history |
| V17 | KCP tables (`kcp_manifests`, `kcp_units`, `kcp_relationships`) |
| V18 | Claude sessions + FTS5 virtual table + sync triggers |
| V19 | Session subagent links (parent-child agent tree) |
| V20 | Git file metrics (`git_file_metrics`, `git_cochange`) |
| V21 | Notion workspace source |
| V22 | KCP v0.21 fields (temporal filtering) |
| V23 | KCP v0.25 federation + lossless extensions |
| V24 | KCP verification results |

### Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Language | Java | 21+ |
| Build | Maven | -- |
| CLI | picocli | 4.7.7 |
| Search | Apache Lucene | 10.1.0 |
| Database | SQLite via JDBC | 3.47.1.0 |
| Migrations | Flyway | 10.22.0 |
| AI | Anthropic Java SDK | 2.14.0 |
| PDF | Apache PDFBox | 3.0.4 |
| Git | JGit | 7.1.1 |
| Graph | JGraphT | 1.5.2 |
| Graph Viz | Graphviz-java | 0.18.1 |
| LSP | LSP4J | 0.23.1 |
| OCR | Tess4J | 5.9.0 |
| Tests | JUnit 5 | 5.11.4 |

### Deployable Artifacts

| JAR | Entry Point | Purpose |
|-----|-------------|---------|
| `synthesis-<version>.jar` | `SynthesisApp` | CLI tool |
| `synthesis-mcp-server.jar` | `SynthesisMCPServer` | MCP server for AI agents |
| `synthesis-lsp-server.jar` | `SynthesisLanguageServer` | LSP server for IDEs |

### Key Metrics

| Metric | Value |
|--------|-------|
| Files indexed (validated) | 36,342 |
| Indexing speed | 200-300 files/second |
| Search latency | <1 second (0.4s validated) |
| Retrieval time reduction | 92-95% |
| Storage overhead | 2.7% (11.6 MB index for 434 MB content) |
| Cross-repo deps | 58 repos, 429 dependencies in <31 seconds |
| Packages | 34 Java packages |
| Skills | 38 Claude Code skills |

### Project Structure

```
io.exoreaction.synthesis/
  SynthesisApp.java              # Entry point (55 subcommands)
  ai/                            # Claude API integration
  analyzer/                      # File analysis pipeline (6 analyzers)
  architecture/                  # Architecture alerts
  changelog/                     # Change tracking engine
  cli/                           # CLI commands
  config/                        # Configuration management
  core/                          # Core domain (scanner, metadata, state)
  db/                            # Database access + Flyway migrations
  enrichment/                    # AI enrichment pipeline
  git/                           # JGit integration
  graph/                         # Dependency graph + Code Knowledge Graph
  index/                         # Lucene search index
  insights/                      # Architecture analysis engine
  lsp/                           # LSP server
  mcp/                           # MCP server
  metrics/                       # Usage metrics
  org/                           # Organization registry + directory identity
  report/                        # Report generation
  research/                      # Research engine
  search/                        # Search configuration
  sessions/                      # Session history (episodic memory)
  skills/                        # Skill generation + topic-health/triage
  staging/                       # Staging pipeline
  summary/                       # Executive summaries
  telemetry/                     # Pilot telemetry
  tracking/                      # File movement tracking
  update/                        # Self-update mechanism
  util/                          # Shared utilities
  validate/                      # Workspace validation
  workspace/                     # Workspace metadata
```

---

## Appendix: Version Timeline

| Version | Date | Highlights |
|---------|------|-----------|
| v1.0.0 | Feb 14 | Core indexing, search, CLI, org intelligence, 282 tests |
| v1.0.1 | Feb 14 | Distribution, skill generation, install scripts |
| v1.0.2 | Feb 14 | Media support (image, video, PDF), directed synthesis |
| v1.0.3 | Feb 14 | Bundled ffprobe binaries |
| v1.1.0 | Feb 15 | MCP server, LSP server, update system, air-gapped mode |
| v1.2.0 | Feb 15 | AI features (enrich, explain, perspectives), local media enrichment |
| v1.2.1 | Feb 15 | Unified workspace system, global status |
| v1.2.2 | Feb 15 | Workspace polish |
| v1.2.3 | Feb 15 | Enhanced status with aggregates |
| v1.4.0 | Feb 16 | File tracking, change reporting, V3 migration |
| v1.4.1 | Feb 16 | Tracking config fix |
| v1.5.0 | Feb 16 | Sub-workspaces, bundled skills, V4 migration |
| v1.5.1 | Feb 16 | Smart exclusion defaults |
| v1.5.2 | Feb 16 | Configurable discovery, version reporting |
| v1.5.3 | Feb 16 | Visual workspace tree |
| v1.6.0 | Feb 16 | Executive summaries (8 perspectives), V5 migration |
| v1.6.1 | Feb 16 | Client-to-codebase auto-discovery |
| v1.7.0 | Feb 16 | ClientCodebaseResolver tests |
| v1.7.1 | Feb 17 | Dashboard alias |
| v1.7.2 | Feb 17 | Update mechanism fix (Cantara Maven) |
| v1.7.3 | Feb 17 | WBS navigation, upcoming command |
| v1.7.4 | Feb 17 | Rich client summaries, git fetch integration |
| v1.7.5 | Feb 17 | Org enrichment command |
| v1.7.6 | Feb 17 | Research engine, report engine, credential store, V6 migration |
| v1.7.7 | Feb 17 | Report noise fix |
| v1.7.8 | Feb 17 | Entity reports (--product, --client) |
| v1.8.0 | Feb 17 | Staging pipeline, V8 migration |
| v1.8.1 | Feb 17 | Embedded exo wrapper |
| v1.8.2 | Feb 18 | Staging exclusions, vision resize |
| v1.8.3 | Feb 18 | Report date anchoring, --no-cache |
| v1.8.4 | Feb 18 | Report fixes (12 issues), truncation detection |
| v1.9.0 | Feb 18 | Report output configuration |
| v1.9.1 | Feb 18 | Test expansion: 1,054 to 2,291 tests |
| v1.9.2 | Feb 18 | Project-level skills |
| v1.9.3 | Feb 18 | Staging _processed suffix |
| v1.9.4 | Feb 18 | Architecture security report, 143 more tests |
| v1.9.5 | Feb 18 | Staging integration tests (2,325 tests) |
| v1.9.6 | Feb 19 | Content-intelligence routing |
| v1.9.7 | Feb 19 | Explain filename resolution |
| v1.9.8 | Feb 19 | Summary --since temporal context |
| v1.9.10 | Feb 19 | MCP schema fixes |
| v1.9.11 | Feb 19 | MCP subWorkspace parameter |
| v1.9.12 | Feb 19 | Enrich targeting, CLI metrics, maintain activity log |
| v1.10.0 | Feb 19 | Concurrent search fix, exo ask |
| v1.10.1 | Feb 19 | Discover command, validate drift detection |
| v1.10.2 | Feb 19 | Knowledge integrity, gap detection, confidence metadata |
| v1.10.3 | Feb 20 | Unified knowledge graph, watch, test overlay, cross-format links |
| v1.10.5 | Feb 20 | Staging enrich-first, companion filters |
| v1.10.6 | Feb 20 | Keyword routing rules, staging ingest fix |
| v1.11.0 | Feb 20 | Health command, prune, sweep |
| v1.11.1 | Feb 20 | Directory identity system, self-organizing workspace |
| v1.11.2 | Feb 20 | Documentation update |
| v1.11.3 | Feb 20 | TDD foundation, unified identity, grouped help |
| v1.12.0 | Feb 21 | 9-phase maintain orchestrator, guided init |
| v1.12.1 | Feb 21 | Health fix-config phantom removal |
| v1.12.2 | Feb 22 | Knowledge graph (P1-P4), Code Knowledge Graph (CKG-1 through CKG-4), 3,842 tests |
| v1.13.0 | Feb 22 | Rebalance false-positive fix (#209), .synthesisignore health integration (#212), 3,893 tests |
| v1.13.1 | Feb 22 | CKG dogfooding: 4 bugs + 3 improvements (PR #222), 3,865 tests |
| v1.18.2 | Feb 28 | Session lifecycle integration, hooks generate, session-context, claude-md refresh, 4,107 tests |
| v1.21.0 | Mar 3 | Episodic memory: Claude sessions module, V18 migration, FTS5 search, 4,170 tests |
| v1.22.0 | Mar | Skills match + team-context commands, team_context MCP tool |
| v1.23.0 | Mar | Dispatch command — agent dispatch planner with skill/file/conflict/token analysis |
| v1.24.0 | Mar | Reflect — self-maintaining skill library from session history |
| v1.26.0 | Apr | Interactive skills-graph, parent-child subagent linking (V19), reflect improvements |
| v1.27.1 | Apr 10 | topic-health, topic-triage commands + 3 new bundled Claude skills |
| v1.28.0 | Apr 11 | Explicit API key guidance for AI features |
| v1.29.0 | Apr 21 | Git signals (hotspots, co-change, bus factor, archaeology, V20), Notion source Phase 1 (V21) |
| v1.30.0 | Apr 21 | Notion OAuth (`synthesis notion auth`) |
| v1.32.0 | Apr 22 | Maintenance (v1.31 skipped) |
| v1.32.2 | Apr 27 | MCP: silently ignore `notifications/*` |
| v1.33.0 | May 17 | MCP workspace resolution by name/basename |
| v1.34.0 | May 25 | bootstrap_context, .synthesisignore globs, prune symlink safety |
| v1.34.1-v1.35.0 | May 28 - Jun 14 | Dependency maintenance |
| v1.36.0 | Jun 14 | KCP v0.21 + temporal filtering (V22), OpenAI-compatible providers (DeepSeek) |
| v1.37.0 | Jun 14 | KCP temporal filtering fix |
| v1.37.2 | Jul 6 | Agent-harness polish: worktree ignores, skills warnings, K003, MCP stderr |
| v1.38.0 | Jul 6 | KCP v0.25 full stack: init/refresh/verify/gaps/catalog/federate/plan/sign (epic #361, V23-V24) |
| v1.40.0 | Jul 8 | Semantic search hardening: persisted HNSW embeddings, content embedding (v1.39 skipped) |
| v1.41.0 | Jul 9 | Grounded ask, KCP routing hints, remember/recall MCP tools |
| v1.42.0 | Jul 9 | KCP retrieval benchmark results |

---

*Built with [Skill-Driven Development (SDD)](https://exoreaction.com) by eXOReaction AS.*
*Repository: https://github.com/exoreaction/Synthesis*
*License: Apache 2.0*
