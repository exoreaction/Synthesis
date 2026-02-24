# Synthesis — Architecture & Security Deep-Dive Report

**Version:** 1.9.3-SNAPSHOT
**Date:** 2026-02-18
**Scope:** Full codebase analysis — 175 source files, 28 packages, ~49,000 LOC
**Author:** Generated via Claude Code static analysis

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [System Overview](#2-system-overview)
3. [Architecture Deep-Dive](#3-architecture-deep-dive)
   - 3.1 [Package Structure & Responsibilities](#31-package-structure--responsibilities)
   - 3.2 [Entry Points & Interface Layer](#32-entry-points--interface-layer)
   - 3.3 [Core Indexing Pipeline](#33-core-indexing-pipeline)
   - 3.4 [Search Engine](#34-search-engine)
   - 3.5 [Sub-Workspace Architecture](#35-sub-workspace-architecture)
   - 3.6 [AI & Enrichment Layer](#36-ai--enrichment-layer)
   - 3.7 [Data Flows](#37-data-flows)
   - 3.8 [Configuration Management](#38-configuration-management)
   - 3.9 [Dependency Graph](#39-dependency-graph)
4. [Security Analysis](#4-security-analysis)
   - 4.1 [Security Architecture Overview](#41-security-architecture-overview)
   - 4.2 [Attack Surface Analysis](#42-attack-surface-analysis)
   - 4.3 [Credential & Secrets Management](#43-credential--secrets-management)
   - 4.4 [Dependency Security](#44-dependency-security)
   - 4.5 [AI/Prompt Security](#45-aiprompt-security)
   - 4.6 [Data Privacy](#46-data-privacy)
   - 4.7 [Hardening Roadmap](#47-hardening-roadmap)
5. [Code Quality & Test Coverage](#5-code-quality--test-coverage)
   - 5.1 [Test Coverage by Package](#51-test-coverage-by-package)
   - 5.2 [Code Quality Indicators](#52-code-quality-indicators)
   - 5.3 [Known Issues](#53-known-issues)
6. [Production Readiness Assessment](#6-production-readiness-assessment)
   - 6.1 [Deployment Options](#61-deployment-options)
   - 6.2 [Monitoring & Observability Gaps](#62-monitoring--observability-gaps)
   - 6.3 [Operational Maturity Matrix](#63-operational-maturity-matrix)
7. [Recommendations](#7-recommendations)
   - 7.1 [Immediate (< 1 sprint)](#71-immediate--1-sprint)
   - 7.2 [Short-term (1-4 weeks)](#72-short-term-1-4-weeks)
   - 7.3 [Medium-term (1-3 months)](#73-medium-term-1-3-months)
   - 7.4 [Strategic (3-12 months)](#74-strategic-3-12-months)

**Appendices**
- [Appendix A: Full Dependency List](#appendix-a-full-dependency-list)
- [Appendix B: Database Schema Reference](#appendix-b-database-schema-reference)
- [Appendix C: CLI Command Reference](#appendix-c-cli-command-reference)

---

## 1. Executive Summary

### What Synthesis Is

Synthesis is a Java 21+ CLI tool and MCP (Model Context Protocol) server designed to solve a concrete problem in AI-assisted development: AI tools have dramatically accelerated code *creation*, but comprehension and retrieval speed has not kept pace. The result is that 40–60% of developer time is consumed searching for context. Synthesis addresses this by indexing everything a team creates — source code, documentation, PDFs, videos, images — and making it instantly searchable with relationship tracking and AI-powered analysis.

The product ships as three artifacts built from a single Maven project:
- `synthesis.jar` — the primary CLI (35 subcommands)
- `synthesis-mcp-server.jar` — a Model Context Protocol server for AI agent integration
- `synthesis-lsp-server.jar` — a Language Server Protocol server for IDE integration

### Key Architectural Decisions

**Local-first by design.** The Lucene full-text index and SQLite metrics database are stored in `.synthesis/` within the workspace root. No workspace content is transmitted over the network unless the user explicitly opts into AI features (which require an Anthropic API key). This is the correct design for a privacy-sensitive knowledge tool.

**Flat fat-JAR deployment.** The Maven Shade plugin bundles all dependencies into three self-contained JARs. This eliminates classpath management issues and simplifies distribution, but at the cost of JAR size and inability to selectively update dependencies.

**Edition-based air-gapping.** The `SYNTHESIS_EDITION` environment variable controls whether AI and telemetry features activate. The `core` and `enterprise` editions are fully air-gapped. The implementation in `SynthesisApp.java` cleanly removes AI-dependent subcommands at runtime and routes to a no-op telemetry service.

**Mandatory telemetry for the pilot program.** For non-air-gapped (pro/ultimate) editions, telemetry is described as mandatory and cannot be disabled by the user. Telemetry sends command names, success flags, execution duration, OS details, Java version, and a random UUID to a hardcoded Slack webhook. This design decision has significant implications discussed in the Security Analysis.

**SQLite + Flyway for persistence.** A global SQLite database at `~/.synthesis/synthesis.db` stores metrics, file movement tracking, workspace snapshots, and AI-generated cache entries. Flyway handles schema evolution with migrations V1–V8 (V7 is absent in the codebase).

### Security Posture Summary

The local-first architecture provides a strong privacy foundation, but several areas require attention:

- **Hardcoded secrets in source code.** The Slack webhook URL (telemetry destination) and a Slack bot token (approval system) are hardcoded as `public static final` constants in `TelemetryConfig.java` and `ApprovalConfig.java` respectively. These are present in any compiled JAR and in the open-source repository.
- **Credential obfuscation, not encryption.** API keys stored via `synthesis credentials set` use XOR-with-UUID obfuscation. This is documented as obfuscation, not encryption, but users may not appreciate the distinction.
- **No SBOM or dependency vulnerability scanning.** The build has no OWASP Dependency-Check, Dependabot, or Snyk integration. The dependency tree includes several third-party libraries (Tesseract OCR via JNA, graphviz-java, Slack API) that may carry their own vulnerability histories.
- **Prompt injection risk.** The `ask` command and related AI features build prompts that include raw file content from the indexed workspace. Malicious content in indexed files could attempt to manipulate Claude's responses.
- **No input validation on file paths in the MCP server.** The MCP tool handler accepts workspace paths from the calling AI agent and resolves them directly to disk.

### Production Readiness Assessment

Synthesis is a well-structured, actively developed product at a mature prototype/early production stage. The codebase demonstrates clear architectural thinking, consistent patterns, and good separation of concerns. Test coverage is substantial (92+ test files, 2,291 tests per validated metrics). The primary gaps for production hardening are in the security domain (secrets management, dependency scanning) and in operational observability (structured logging, metrics export, distributed tracing).

### Top 5 Strengths

1. **Clean local-first architecture** with a well-defined air-gap mode
2. **Consistent patterns** across all 35 CLI commands (picocli, ConfigLoader, WorkspaceManager)
3. **Comprehensive AI caching** (summary, research, report caches keyed by index fingerprint)
4. **Multi-interface design** (CLI + MCP + LSP) from a shared core without code duplication
5. **Robust database evolution** via Flyway with proper migration versioning

### Top 5 Gaps

1. **Hardcoded Slack credentials in open-source code** — a serious secret-leakage issue
2. **No dependency vulnerability scanning** in the build pipeline
3. **XOR obfuscation is not cryptographic protection** for Anthropic API keys
4. **Telemetry cannot be disabled** by non-pilot users (consent and privacy concern)
5. **No structured logging or metrics export** — operational visibility is limited

---

## 2. System Overview

### Purpose and Goals

Synthesis indexes multi-format workspaces (code, docs, PDFs, videos, images) and provides:
- Sub-second full-text search across all file types
- Bidirectional relationship tracking ("what breaks if I change X?")
- Cross-repository dependency graphs
- AI-powered Q&A grounded in indexed content
- MCP server integration for AI agents (Claude Code, Cursor, Aider)
- LSP server integration for IDEs (VSCode, IntelliJ, Neovim)

### Validated Performance Metrics

From the CLAUDE.md project context:
- Indexing speed: 200–300 files/second
- Search latency: 0.4 seconds (validated)
- Scale: 36,342 files indexed
- Test suite: 2,291 tests passing
- Cross-repo dependency analysis: 58 repos, 429 dependencies in under 31 seconds

### Technology Stack (from pom.xml — exact versions)

| Component | Library | Version |
|-----------|---------|---------|
| Language | Java | 17 (source and target) |
| Build | Maven | (project standard) |
| CLI Framework | picocli | 4.7.7 |
| Full-Text Search | Apache Lucene (core, queryparser, highlighter, analysis-common) | 10.1.0 |
| YAML Config | SnakeYAML | 2.2 |
| PDF Processing | Apache PDFBox | 3.0.4 |
| Image Metadata | metadata-extractor (Drew Noakes) | 2.19.0 |
| OCR | Tess4J (Tesseract) | 5.9.0 |
| Native Access | JNA | 5.14.0 |
| Git Integration | Eclipse JGit | 7.1.1.202505221757-r |
| Graph Visualization | graphviz-java (guru.nidi) | 0.18.1 |
| Graph Algorithms | JGraphT | 1.5.2 |
| Telemetry | Slack Java API client | 1.44.2 |
| Database | SQLite JDBC (Xerial) | 3.47.1.0 |
| Schema Migrations | Flyway Core | 10.22.0 |
| Logging | SLF4J Simple | 2.0.16 |
| JSON Processing | Jackson Databind | 2.18.2 |
| LSP | Eclipse LSP4J | 0.23.1 |
| AI SDK | anthropic-java | 2.14.0 |
| Test Framework | JUnit Jupiter | 5.11.4 |

### Project Structure Overview

```
/src/exoreaction/Synthesis/
├── pom.xml                              # Maven build, 3 shade executions
├── src/main/java/io/exoreaction/synthesis/
│   ├── SynthesisApp.java                # Root picocli command, edition detection
│   ├── ai/                              # Claude API client, prompts, code explainer
│   ├── analyzer/                        # FileAnalyzer hierarchy (6 analyzers + registry)
│   ├── architecture/                    # Architecture health monitoring
│   ├── changelog/                       # Cross-workspace change reporting
│   ├── cli/                             # All 35 CLI subcommands (40 files)
│   ├── config/                          # SynthesisConfig, ConfigLoader, CredentialStore
│   ├── core/                            # DirectoryScanner, SubWorkspaceResolver, etc.
│   ├── db/                              # SynthesisDatabase (SQLite + Flyway wrapper)
│   ├── enrichment/                      # CompanionFileGenerator (.synthesis.md files)
│   ├── git/                             # JGit integration
│   ├── graph/                           # GraphBuilder, GraphRenderer (Mermaid/DOT/JSON)
│   ├── index/                           # SearchIndex (Lucene), DocumentFields, SearchResult
│   ├── insights/                        # InsightsEngine (connectivity, complexity, quality)
│   ├── lsp/                             # Language Server Protocol server (3 files)
│   ├── mcp/                             # MCP server + tool handler (4 files)
│   ├── metrics/                         # MetricsCollector, MetricsDatabase, MetricsEvent
│   ├── org/                             # Organization registry, scanner, classifier
│   ├── report/                          # Business report engine (10 files)
│   ├── research/                        # Multi-pass research engine (8 files)
│   ├── search/                          # MultiWorkspaceSearch, WorkspaceDiscoveryConfig
│   ├── skills/                          # SkillGenerator, SkillTemplate, SkillInstaller
│   ├── staging/                         # StagingManager (file intake workflow)
│   ├── summary/                         # SummaryEngine, cache, prompts (8 files)
│   ├── telemetry/                       # TelemetryService, ApprovalService, ClientUUID (6 files)
│   ├── tracking/                        # File movement tracking (5 files)
│   ├── update/                          # UpdateChecker, UpdateManager (8 files)
│   ├── util/                            # File utils, progress, OCR/Whisper/ffprobe detection
│   └── workspace/                       # WorkspaceMetadata, WorkspaceType
├── src/main/resources/
│   ├── db/migration/                    # Flyway SQL migrations V1–V8 (V7 missing)
│   ├── version.properties               # Maven-filtered version
│   └── synthesis-manifest.json         # Filtered manifest for update management
└── src/test/java/                       # 92+ test classes
```

### Key Entry Points

| Entry Point | Main Class | Transport | Purpose |
|-------------|-----------|-----------|---------|
| CLI | `SynthesisApp` | stdin/stdout | Human-facing command execution |
| MCP Server | `SynthesisMCPServer` | JSON-RPC 2.0 over stdio | AI agent integration |
| LSP Server | `SynthesisLanguageServer` | JSON-RPC 2.0 over stdio | IDE integration |

---

## 3. Architecture Deep-Dive

### 3.1 Package Structure & Responsibilities

| Package | Files | Key Classes | Responsibility |
|---------|-------|-------------|---------------|
| `(root)` | 1 | `SynthesisApp` | CLI root command, edition detection, telemetry orchestration |
| `ai` | 6 | `ClaudeClient`, `PromptTemplates`, `CodeExplainer`, `DirectedSynthesisEngine`, `ReadmeGenerator`, `EmbeddingService` | Anthropic API integration, prompt management, AI operations |
| `analyzer` | 11 | `FileAnalyzer`, `AnalyzerRegistry`, `MarkdownAnalyzer`, `CodeAnalyzer`, `YamlAnalyzer`, `PdfAnalyzer`, `ImageAnalyzer`, `VideoAnalyzer`, `GenericAnalyzer`, `AnalysisResult`, `PresentationExtractor` | File type detection and content extraction |
| `architecture` | 2 | `ArchitectureMonitor`, `ArchitectureAlert` | Architecture health monitoring and alerting |
| `changelog` | 6 | `SnapshotManager`, `ChangeReportGenerator`, `SignificanceClassifier`, `ChangeEvent`, `ChangeSignificance`, `WorkspaceSnapshot` | Cross-workspace change detection and reporting |
| `cli` | 40 | (35 command classes + 5 utilities) | All user-facing CLI subcommands |
| `config` | 3 | `SynthesisConfig`, `ConfigLoader`, `CredentialStore` | YAML configuration loading, credential storage |
| `core` | 10 | `DirectoryScanner`, `SubWorkspaceResolver`, `WorkspaceManager`, `FileMetadata`, `ScanResult`, `ScanState`, `RepositoryManager`, `EcosystemDetector`, `Ecosystem`, `SmartExclusions` | File scanning, workspace management, ecosystem detection |
| `db` | 1 | `SynthesisDatabase` | SQLite connection, Flyway migration, WAL mode |
| `enrichment` | 3 | `CompanionFileGenerator`, `EnrichmentLevel`, `EnrichmentResult` | Binary asset enrichment (.synthesis.md files) |
| `git` | 1 | `GitIntegration` | JGit integration for diff and change tracking |
| `graph` | 2 | `GraphBuilder`, `GraphRenderer` | Dependency graph construction and rendering (Mermaid, DOT, JSON) |
| `index` | 4 | `SearchIndex`, `DocumentFields`, `SearchResult`, `FileIndexer` | Lucene index management |
| `insights` | 1 | `InsightsEngine` | Connectivity, complexity, quality, and architecture metrics |
| `lsp` | 3 | `SynthesisLanguageServer`, `SynthesisTextDocumentService`, `SynthesisWorkspaceService` | LSP 3.17 protocol server |
| `mcp` | 4 | `SynthesisMCPServer`, `SynthesisToolHandler`, `JsonRpcMessage` | MCP 2024-11-05 protocol server |
| `metrics` | 3 | `MetricsCollector`, `MetricsDatabase`, `MetricsEvent` | Operational metrics recording to SQLite |
| `org` | 10 | `OrganizationRegistry`, `OrganizationScanner`, `DownloadsClassifier`, `Organization`, `Client`, `Product`, `ClientCodebaseResolver`, `DiscoveredOrganization`, `OrganizationType`, `ClientStatus` | Multi-organization workspace management |
| `report` | 10 | `ReportEngine`, `ReportPrompts`, `ReportCache`, `ReportRenderer`, `BusinessDocumentFinder`, `EntityDocumentFinder`, `ReportDocument`, `ReportResult`, `ReportTarget`, `ReportTopic` | Business executive report generation |
| `research` | 8 | `ResearchEngine`, `ResearchPrompts`, `ResearchCache`, `ResearchRenderer`, `ResearchResult`, `ResearchPassResult`, `ResearchTarget`, `ResearchTopic` | Multi-pass deep research reports |
| `search` | 2 | `MultiWorkspaceSearch`, `WorkspaceDiscoveryConfig` | Cross-workspace search aggregation |
| `skills` | 3 | `SkillGenerator`, `SkillTemplate`, `SkillInstaller` | Auto-generate Claude Code skills from workspace knowledge |
| `staging` | 1 | `StagingManager` | Incoming file staging, classification, promotion |
| `summary` | 8 | `SummaryEngine`, `SummaryCache`, `SummaryPrompts`, `SummaryRenderer`, `SummaryResult`, `SummaryLevel`, `SummaryPerspective`, `CodebaseProfile` | AI-enhanced workspace summaries with caching |
| `telemetry` | 6 | `TelemetryService`, `TelemetryConfig`, `TelemetryEvent`, `ClientUUID`, `ApprovalService`, `ApprovalConfig` | Usage telemetry and pilot approval system |
| `tracking` | 5 | `FileMovementTracker`, `FileTrackingDatabase`, `FileMovementRecord`, `DetectionMethod`, `MovementStatus` | File movement detection between workspaces |
| `update` | 8 | `UpdateChecker`, `UpdateManager`, `VersionManifest`, `InstallationFingerprint`, `InstallationHealth`, `UpdateCheckResult`, `UpdateResult`, `UpdateOptions` | Self-update management via Cantara Maven repo |
| `util` | 12 | `FileUtils`, `AnsiOutput`, `ProgressReporter`, `Version`, `WhisperTranscriber`, `WhisperDetector`, `FfprobeDetector`, `TesseractOcrExtractor`, `TesseractDetector`, `PdftoppmDetector`, `PdfToImageConverter`, `BundledBinaryManager` | Shared utilities, external tool detection |
| `workspace` | 2 | `WorkspaceMetadata`, `WorkspaceType` | Workspace type classification |

### 3.2 Entry Points & Interface Layer

#### CLI (picocli)

`SynthesisApp` is annotated with picocli's `@Command` and registers 35 subcommands. The root command provides:

- A global `-d/--directory` option (scope `INHERIT`, so all subcommands see it)
- Edition detection via `SYNTHESIS_EDITION` environment variable
- Air-gap mode enforcement (removes `ask` and `perspectives` commands)
- Telemetry lifecycle management (create → execute → report → shutdown)
- Background update checking via `UpdateChecker.checkInBackground()`
- Pilot approval checking via `ApprovalService`

Workspace resolution follows a four-level priority chain:
1. Explicit `-d`/`--directory` flag
2. `SYNTHESIS_WORKSPACE` environment variable
3. `~/.synthesis/workspace` file (persisted pointer)
4. Current working directory (fallback)

The picocli annotation processor (`picocli-codegen`) generates `reflect-config.json` and `native-image.json` at compile time, enabling future GraalVM native image compilation.

#### MCP Server

`SynthesisMCPServer` implements MCP protocol version `2024-11-05` over JSON-RPC 2.0 on stdio. The server:

- Parses line-delimited JSON from stdin
- Dispatches to handlers for: `initialize`, `initialized`, `shutdown`, `tools/list`, `tools/call`, `ping`
- Registers 7 tools: `search`, `relate`, `graph`, `stats`, `ask`, `enrich`, `explain`, `summary`
- Logs to `~/.synthesis/logs/mcp-server.log` (5 MB rotating, 3 files)
- Explicitly removes console handlers to avoid corrupting the JSON-RPC stream

Tool dispatch delegates to `SynthesisToolHandler`, which wraps the same Lucene and SQLite infrastructure as the CLI. The `workspace` parameter in each tool call allows an AI agent to target a different workspace than the server's configured default — this is a design-level attack surface noted in the security section.

The MCP server supports both single-workspace mode (`--workspace`) and multi-workspace mode (`--workspaces path1,path2,path3 --name alias`).

#### LSP Server

`SynthesisLanguageServer` implements LSP 3.17 via the Eclipse LSP4J library. Registered capabilities:
- Workspace symbols (`workspaceSymbolProvider: true`) — enables Cmd+T search
- Document links (`documentLinkProvider`) — clickable file references
- Hover (`hoverProvider: true`) — file metadata and relationship counts
- Go to definition (`definitionProvider: true`)
- Find references (`referencesProvider: true`)
- Code lens (`codeLensProvider: true`) — inline relationship counts
- Incremental text document sync

The LSP server is implemented with three classes: `SynthesisLanguageServer` (lifecycle), `SynthesisTextDocumentService` (document operations, hover, definitions), and `SynthesisWorkspaceService` (workspace symbols).

### 3.3 Core Indexing Pipeline

#### Scan Flow

The scan pipeline is orchestrated by `ScanCommand` and executed through three sequential phases:

```
ScanCommand.call()
  │
  ├─ 1. Configuration loading
  │       ConfigLoader.loadConfig(workspaceRoot) → SynthesisConfig
  │
  ├─ 2. File discovery (DirectoryScanner.scan())
  │       FileSystem.walkFileTree()
  │         ├─ preVisitDirectory: isExcludedDirectory() → SKIP_SUBTREE or CONTINUE
  │         └─ visitFile: shouldIncludeFile() → collect or skip
  │       + extractMetadata() per file: attrs, size, MD5 hash (optional)
  │       → ScanResult (List<FileMetadata>)
  │
  ├─ 3. Analysis (AnalyzerRegistry.analyze() per file)
  │       AnalyzerRegistry checks analyzers in order:
  │         MarkdownAnalyzer → CodeAnalyzer → YamlAnalyzer
  │         → PdfAnalyzer → ImageAnalyzer → VideoAnalyzer → GenericAnalyzer
  │       Each produces AnalysisResult (summary, headings, keywords, content, structure)
  │
  └─ 4. Indexing (SearchIndex.addDocument() per file)
          FileIndexer builds Lucene Document from FileMetadata + AnalysisResult
          Document includes: path, relativePath, filename, fileType, language,
            content (up to 10KB), headings, keywords, summary, size, lastModified,
            contentHash, repository, organization, client, subWorkspace,
            mediaType, dimensions, duration, aiDescription, companionFile
          SearchIndex.commit() at end
```

#### FileAnalyzer Hierarchy

| Analyzer | File Types | Key Output |
|----------|-----------|-----------|
| `MarkdownAnalyzer` | `.md` | H1–H3 headings as searchable headings, full text content |
| `CodeAnalyzer` | `.java`, `.py`, `.js`, `.ts`, `.go`, `.rs`, `.kt`, `.scala`, etc. | Class/function names as keywords, imports for relationship detection |
| `YamlAnalyzer` | `.yaml`, `.yml` | Top-level keys as structure, first 10KB as content |
| `PdfAnalyzer` | `.pdf` | Extracted text via PDFBox, number of pages |
| `ImageAnalyzer` | `.png`, `.jpg`, `.gif`, `.bmp`, `.webp`, `.tiff` | EXIF/IPTC/XMP metadata via metadata-extractor, dimensions |
| `VideoAnalyzer` | `.mp4`, `.avi`, `.mov`, `.mkv`, `.webm`, `.mp3`, `.wav`, `.flac`, `.ogg`, `.aac` | Companion file check for transcript, duration via ffprobe if available |
| `GenericAnalyzer` | All other types | File extension, size, basic metadata |

#### Lucene Index Structure

The index lives at `<workspace>/.synthesis/index/`. Fields and their Lucene field types:

| Field | `DocumentFields` constant | Lucene type | Boost | Description |
|-------|--------------------------|------------|-------|-------------|
| `path` | `PATH` | StoredField | — | Absolute path (stored, not searched) |
| `relativePath` | `RELATIVE_PATH` | TextField (stored+indexed) | 1.0 | Relative from workspace root |
| `filename` | `FILENAME` | TextField (stored+indexed) | **3.0** | File name — highest boost |
| `headings` | `HEADINGS` | TextField | **2.5** | Document H1–H3 headings |
| `keywords` | `KEYWORDS` | TextField | **2.0** | Extracted keywords |
| `summary` | `SUMMARY` | TextField | 1.5 | Analysis summary |
| `content` | `CONTENT` | TextField | 1.0 | Up to 10KB file content |
| `fileType` | `FILE_TYPE` | StringField (keyword) | — | MARKDOWN, CODE, PDF, IMAGE, etc. |
| `language` | `LANGUAGE` | StringField | — | Java, Python, etc. |
| `size` | `SIZE` | StoredField | — | File size in bytes |
| `lastModified` | `LAST_MODIFIED` | StoredField | — | Epoch millis |
| `contentHash` | `CONTENT_HASH` | StoredField | — | MD5 for deduplication |
| `repository` | `REPOSITORY` | StringField (keyword) | — | Repo identifier for filtering |
| `organization` | `ORGANIZATION` | StringField (keyword) | — | Org identifier |
| `client` | `CLIENT` | StringField (keyword) | — | Client identifier |
| `subWorkspace` | `SUB_WORKSPACE` | StringField (keyword) | — | Sub-workspace name |
| `mediaType` | `MEDIA_TYPE` | StringField (keyword) | — | presentation, document, etc. |
| `dimensions` | `DIMENSIONS` | StoredField | — | "WxH" for images |
| `duration` | `DURATION` | StoredField | — | Seconds for audio/video |
| `aiDescription` | `AI_DESCRIPTION` | TextField | — | Claude Vision description |
| `companionFile` | `COMPANION_FILE` | StoredField | — | Path to .synthesis.md sidecar |
| `embedding` | `EMBEDDING` | KnnVectorField | — | Semantic embedding (future) |

The analyzer is Lucene's `StandardAnalyzer`. Queries use `MultiFieldQueryParser` with the field boosts defined above. A fallback to `FuzzyQuery` (edit distance 2) activates when query parsing fails.

#### SQLite Schema

See Appendix B for the full per-table/column reference. The database at `~/.synthesis/synthesis.db` contains 9 tables managed by Flyway migrations V1–V8.

### 3.4 Search Engine

#### Full-Text Search

`SearchIndex.search()` provides several overloaded variants supporting progressive filter combinations:

```
search(query, maxResults)                              // basic
search(query, fileTypeFilter, maxResults)             // + type
search(query, fileTypeFilter, repoFilter, maxResults) // + repo
search(query, fileTypeFilter, repoFilter, orgFilter, clientFilter, maxResults)    // + org/client
searchWithSubWorkspace(query, fileType, repo, org, client, subWorkspace, max)    // + sub-workspace
searchWithMediaType(query, fileType, repo, mediaType, org, client, max)         // + media type
```

All search methods construct a `BooleanQuery` with MUST clauses for content and FILTER clauses for each active filter (exact term matching via `TermQuery`). All filters are exact-match except the content query, which goes through the full analyzer and boosting pipeline.

The default operator is OR, so multi-word queries return any matching document ranked by relevance. Users can override with Lucene boolean syntax (`AND`, `NOT`, `"exact phrase"`, `field:value`, `wildcard*`).

#### Multi-Workspace Search

`MultiWorkspaceSearch` aggregates results across multiple workspaces by:
1. Opening each workspace's Lucene index independently
2. Running the same query against each
3. Merging and re-ranking results by score
4. Deduplicating by content hash where applicable

Workspaces are discovered via `WorkspaceDiscoveryConfig` which reads the list of workspace paths configured in the global synthesis settings.

#### Relationship Queries

The `relate` command and `InsightsEngine` implement reference detection using regex patterns:
- Java imports: `^import\s+([\w.]+);`
- Markdown links: `\[([^\]]*)\]\(([^)]+)\)`
- Generic file references: `(?:['"``])([\\w./-]+\\.(?:java|py|js|ts|md|yaml|yml|json|xml|go|rs|kt))['"``]`

These are applied to file content retrieved from the Lucene index. The `RelateCommand` renders bidirectional relationships: "this file imports" and "this file is imported by." The `InsightsEngine` uses these to compute connectivity metrics, orphan detection, and circular dependency cluster identification.

### 3.5 Sub-Workspace Architecture

#### Data Model

Sub-workspaces are logical partitions within a single workspace root. They are configured in `synthesis-config.yaml` under `subWorkspaces:` and stored in the `sub_workspaces` SQLite table (V4 migration).

Each sub-workspace has:
- `name` — logical identifier (e.g., "eXOReaction", "Quadim")
- `path` — relative path prefix from workspace root
- `type` — general, source-code, documents, staging
- `tags` — classification tags
- `codebases` — associated repository names
- `includePatterns` / `excludePatterns` — override parent workspace patterns (null = inherit)

#### Resolution Algorithm

`SubWorkspaceResolver.resolve(relativePath)` delegates to `ConfigLoader.resolveSubWorkspace()`:
1. Compute the file's relative path from workspace root
2. For each configured sub-workspace, check if the relative path starts with the sub-workspace's `path` prefix
3. Use **longest matching prefix** (most-specific-match-wins semantics)
4. Return `null` if no match (file belongs to root workspace)

This means a file at `eXOReaction/Synthesis/src/main/java/Foo.java` would match a sub-workspace with path `eXOReaction/Synthesis` rather than one with path `eXOReaction` if both are configured.

#### Staging Workflow

Sub-workspaces of type `staging` receive special treatment via `StagingManager`:
1. Incoming files are tracked in the `staging_files` SQLite table with `status='pending'` and an `expires_at` timestamp
2. Optional auto-classification attempts to assign `classified_org` and `classification_confidence` using `DownloadsClassifier`
3. Files can be promoted to a permanent sub-workspace (moves file, updates status to `promoted`)
4. Expired files (past `expires_at`) can be cleaned up automatically if `staging.cleanupExpired=true`

The `RoutingConfig` (v1.9.0+) allows routing staged files to destinations based on filename glob patterns.

### 3.6 AI & Enrichment Layer

#### Anthropic API Integration

`ClaudeClient` wraps the `anthropic-java` SDK (v2.14.0) using the OkHttp transport. API key resolution follows this priority:
1. `ANTHROPIC_API_KEY` environment variable
2. `CredentialStore` (XOR-obfuscated file at `~/.synthesis/credentials`)

The client is constructed lazily — commands that need AI call `ClaudeClient.create(config)` or `ClaudeClient.createIfApiKeyAvailable(model)`. The latter bypasses the `ai.enabled` config flag and only requires the API key to be present.

Default model: `claude-sonnet-4-5-20250929` (configurable via `ai.model` in config.yaml).

Three generation modes:
- `generate(prompt, maxTokens)` — standard text generation
- `generateWithMeta(prompt, maxTokens, temperature)` — includes truncation detection (issue #44)
- `generateFromImage(imagePath, prompt, maxTokens)` — vision analysis with automatic resizing for images over 3.75 MB

#### Media Enrichment Pipeline

`CompanionFileGenerator` creates `.synthesis.md` sidecar files for binary assets (images, videos, PDFs, audio). These are then picked up by the next scan and indexed as standard Markdown files, making binary content fully searchable.

Three enrichment levels (configured by `--level` flag):
- **BASIC**: Deterministic metadata only — works in air-gapped mode (file size, dimensions, EXIF data, PDF page count)
- **LOCAL**: Adds output from locally installed tools — Whisper for audio/video transcription, pdftoppm + Tesseract for PDF slide images with OCR text
- **AI**: Adds Claude Vision descriptions and AI-generated summaries for images and slide content

External tool detection (`WhisperDetector`, `FfprobeDetector`, `TesseractDetector`, `PdftoppmDetector`) checks for system-installed tools before attempting LOCAL enrichment. `BundledBinaryManager` can optionally deploy bundled binaries from `src/main/resources/binaries/`.

#### Research Engine

`ResearchEngine` performs multi-pass AI analysis of a workspace. Each pass sends a different focused prompt to Claude, building an analysis across multiple topics. Pass results are cached in the `research_cache` SQLite table keyed by (workspace, target, topic, passes, index fingerprint). The index fingerprint is a hash of the file count and last modification time — cache invalidation is automatic when the index changes.

Research targets: `chatgpt`, `notebooklm-infographic`, `notebooklm-presentation`
Research topics: `full`, `architecture`, `security`, `quality`, `dependencies`, `evolution`

#### Report Generation

`ReportEngine` generates business executive reports. It uses `BusinessDocumentFinder` to discover relevant documents (meeting notes, presentations, status reports) and `ReportPrompts` to build targeted prompts for different audiences (CEO, board, investor) and timeframes (1w, 2w, 1m). Results are cached in `report_cache`.

Report topics: `weekly`, `pipeline`, `activities`, `executive`, `decisions`

#### Skills Generation

`SkillGenerator` analyzes a workspace (using Synthesis's own search capabilities) and generates Claude Code skills — Markdown files that define reusable behaviors for the AI assistant. `SkillInstaller` copies generated skills to `~/.claude/skills/` for global availability.

### 3.7 Data Flows

#### File Scan to Index Flow

```mermaid
sequenceDiagram
    participant User
    participant ScanCommand
    participant ConfigLoader
    participant DirectoryScanner
    participant AnalyzerRegistry
    participant FileIndexer
    participant SearchIndex
    participant SubWorkspaceResolver

    User->>ScanCommand: synthesis scan [--full] [-d /workspace]
    ScanCommand->>ConfigLoader: loadConfig(workspaceRoot)
    ConfigLoader-->>ScanCommand: SynthesisConfig
    ScanCommand->>DirectoryScanner: new DirectoryScanner(root, config)
    ScanCommand->>DirectoryScanner: scan()
    DirectoryScanner->>DirectoryScanner: discoverFiles() [walkFileTree + pattern matching]
    DirectoryScanner->>DirectoryScanner: extractMetadata() per file [size, hash, attrs]
    DirectoryScanner-->>ScanCommand: ScanResult (List<FileMetadata>)
    ScanCommand->>SearchIndex: open(.synthesis/index/)
    loop For each FileMetadata
        ScanCommand->>AnalyzerRegistry: analyze(metadata)
        AnalyzerRegistry-->>ScanCommand: AnalysisResult (summary, headings, keywords, content)
        ScanCommand->>SubWorkspaceResolver: resolve(relativePath)
        SubWorkspaceResolver-->>ScanCommand: subWorkspaceName (or null)
        ScanCommand->>FileIndexer: buildDocument(metadata, analysis, subWorkspace)
        FileIndexer-->>ScanCommand: Lucene Document
        ScanCommand->>SearchIndex: addDocument(doc)
    end
    ScanCommand->>SearchIndex: commit()
    ScanCommand-->>User: N files indexed in X seconds
```

#### Search Query to Result Flow

```mermaid
sequenceDiagram
    participant User
    participant SearchCommand
    participant SearchIndex
    participant MultiWorkspaceSearch

    User->>SearchCommand: synthesis search "query" [--type CODE] [--scope subws]
    alt Single workspace
        SearchCommand->>SearchIndex: search(query, fileType, repo, org, client, subWs, maxResults)
        SearchIndex->>SearchIndex: buildQuery() via MultiFieldQueryParser
        SearchIndex->>SearchIndex: BooleanQuery (MUST content + FILTER terms)
        SearchIndex-->>SearchCommand: List<SearchResult> ranked by score
    else Cross-workspace (--all)
        SearchCommand->>MultiWorkspaceSearch: searchAll(query, ...)
        MultiWorkspaceSearch->>MultiWorkspaceSearch: open each workspace index
        MultiWorkspaceSearch-->>SearchCommand: merged, re-ranked results
    end
    SearchCommand-->>User: ranked results with snippets
```

#### MCP Tool Call Flow

```mermaid
sequenceDiagram
    participant AIAgent as AI Agent (Claude Code)
    participant MCPServer as SynthesisMCPServer
    participant ToolHandler as SynthesisToolHandler
    participant SearchIndex
    participant MetricsDB as MetricsDatabase

    AIAgent->>MCPServer: JSON-RPC {"method":"tools/call","params":{"name":"search","arguments":{...}}}
    MCPServer->>MCPServer: handleMessage() → handleToolsCall()
    MCPServer->>ToolHandler: handleSearch(toolArgs)
    ToolHandler->>ToolHandler: resolveWorkspace(params)
    ToolHandler->>SearchIndex: open(workspace/.synthesis/index/)
    ToolHandler->>SearchIndex: search(query, filters, limit)
    SearchIndex-->>ToolHandler: List<SearchResult>
    ToolHandler->>MetricsDB: record(MCP_TOOL, "search", duration, resultCount)
    ToolHandler-->>MCPServer: ObjectNode (JSON results)
    MCPServer-->>AIAgent: JSON-RPC {"result":{"content":[{"type":"text","text":"..."}],"isError":false}}
```

#### AI Enrichment Flow

```mermaid
sequenceDiagram
    participant User
    participant EnrichCommand
    participant CompanionFileGenerator
    participant ClaudeClient
    participant Anthropic as Anthropic API

    User->>EnrichCommand: synthesis enrich --level ai [file.mp4]
    EnrichCommand->>CompanionFileGenerator: new(level=AI, force=false, aiClient)
    EnrichCommand->>CompanionFileGenerator: generate(metadata, analysis, relatedFiles)
    CompanionFileGenerator->>CompanionFileGenerator: companionPathFor(file.mp4) = file.mp4.synthesis.md
    alt companion exists and !force
        CompanionFileGenerator-->>EnrichCommand: Optional.empty()
    else
        CompanionFileGenerator->>ClaudeClient: generateFromImage(imagePath, prompt, maxTokens)
        ClaudeClient->>ClaudeClient: readImageBytes() [resize if > 3.75MB]
        ClaudeClient->>ClaudeClient: Base64 encode
        ClaudeClient->>Anthropic: POST /messages [image + text blocks]
        Anthropic-->>ClaudeClient: AI description
        ClaudeClient-->>CompanionFileGenerator: description string
        CompanionFileGenerator->>CompanionFileGenerator: renderMarkdown(metadata, analysis, aiDescription)
        CompanionFileGenerator->>disk: write file.mp4.synthesis.md
        CompanionFileGenerator-->>EnrichCommand: Optional(companionPath)
    end
    EnrichCommand-->>User: Generated companion file
```

### 3.8 Configuration Management

#### SynthesisConfig Structure

`SynthesisConfig` is deserialized from `synthesis-config.yaml` using SnakeYAML. The class uses mutable JavaBeans (no-arg constructors + setters) for SnakeYAML compatibility. Inner classes:

| Class | Key Fields | Purpose |
|-------|-----------|---------|
| `WorkspaceConfig` | name, type, description, metadata | Workspace identity |
| `SearchConfig` | maxResults(20), previewLength(200), contentPreviewBytes(10240) | Search behavior |
| `AiConfig` | enabled(false), apiKey(null), model, maxTokens(1024), vision | AI provider settings |
| `VisionConfig` | enabled(true), costPerImageUsd(0.02), maxImageSizeBytes(20MB), confirmBeforeScan(true) | Vision AI settings |
| `ScanConfig` | includePatterns, excludePatterns, useSmartDefaults(true), computeHashes(true), maxFileSizeBytes(10MB) | Scan behavior |
| `TrackingConfig` | enabled(true), safetyPeriodDays(7), autoDetect(true), watchCorrelationWindowMs(60000), retentionDays(90) | File movement tracking |
| `ChangelogConfig` | enabled(true), autoSnapshot(true), snapshotIntervalHours(6), retentionDays(90) | Change reporting |
| `SubWorkspaceConfig` | name, path, description, type, tags, codebases, includePatterns, excludePatterns | Sub-workspace definition |
| `StagingConfig` | enabled(false), retentionDays(30), autoClassify(true), classificationThreshold(0.5) | Staging area behavior |
| `RoutingConfig` | copyCompanions(true), rules | File routing rules |
| `RoutingRule` | name, patterns, destination | Single routing rule |
| `ReportConfig` | outputDir | Report output location |

#### Config File Location

`ConfigLoader` searches for the config file in this order:
1. `<workspaceRoot>/synthesis-config.yaml`
2. `<workspaceRoot>/.synthesis/config.yaml`
3. Default (in-memory) config

#### Environment Variable Overrides

| Variable | Effect |
|----------|--------|
| `SYNTHESIS_WORKSPACE` | Override workspace root |
| `SYNTHESIS_EDITION` | Edition: core, pro (default), enterprise, ultimate |
| `ANTHROPIC_API_KEY` | Anthropic API key (overrides CredentialStore) |
| `SYNTHESIS_DISABLE_APPROVAL` | Set to "true" or "1" to disable pilot approval checks |
| `SYNTHESIS_NO_UPDATE_CHECK` | Set to "1" or "true" to disable update checks |

#### Credential Storage

`CredentialStore` stores named credentials in `~/.synthesis/credentials`:
- File permissions: 600 (POSIX) or best effort on Windows
- Encoding: XOR with the machine's client UUID, then Base64
- Fallback XOR key: `synthesis-credential-fallback-seed` (a fixed string, used if client-uuid file is unavailable)

### 3.9 Dependency Graph

The following diagram shows key inter-package dependencies:

```mermaid
graph TD
    SynthesisApp --> cli
    SynthesisApp --> telemetry
    SynthesisApp --> update

    cli --> config
    cli --> core
    cli --> index
    cli --> search
    cli --> ai
    cli --> graph
    cli --> enrichment
    cli --> insights
    cli --> org
    cli --> tracking
    cli --> changelog
    cli --> staging
    cli --> summary
    cli --> research
    cli --> report
    cli --> architecture
    cli --> skills
    cli --> git
    cli --> metrics

    mcp --> index
    mcp --> search
    mcp --> graph
    mcp --> ai
    mcp --> enrichment
    mcp --> metrics
    mcp --> config
    mcp --> core

    lsp --> index
    lsp --> config

    core --> config
    index --> core
    ai --> config
    enrichment --> ai
    enrichment --> analyzer
    summary --> ai
    summary --> index
    research --> ai
    research --> index
    report --> ai
    report --> index
    insights --> index
    graph --> index
    tracking --> db
    changelog --> db
    metrics --> db
    staging --> db
    summary --> db
    research --> db
    report --> db
    telemetry --> util
    update --> util
```

**Hotspot files (most referenced by other packages):**
1. `SearchIndex` (index) — used by cli, mcp, lsp, insights, summary, research, report, skills
2. `SynthesisConfig` (config) — used by virtually all packages
3. `ClaudeClient` (ai) — used by enrichment, summary, research, report, skills, cli
4. `SynthesisDatabase` (db) — used by metrics, tracking, changelog, staging, summary, research, report

---

## 4. Security Analysis

### 4.1 Security Architecture Overview

#### Local-First Model

Synthesis's security posture is built on the principle that workspace content stays on the local machine. By default:
- The Lucene index is written to `<workspace>/.synthesis/index/`
- The SQLite database is written to `~/.synthesis/synthesis.db`
- No file content is transmitted over the network
- The MCP and LSP servers communicate only over stdio (no network sockets)

This local-first design means an attacker who cannot access the user's filesystem cannot access indexed workspace content. There is no server-side component to compromise.

#### Trust Boundaries

```
┌─────────────────────────────────────────────────────┐
│  User's machine (trusted zone)                       │
│                                                      │
│  ┌──────────────┐    ┌──────────────────────────┐   │
│  │ Synthesis CLI │    │ Lucene index (filesystem) │   │
│  │ MCP Server   │◄──►│ SQLite DB (~/.synthesis/) │   │
│  │ LSP Server   │    └──────────────────────────┘   │
│  └──────┬───────┘                                    │
└─────────┼───────────────────────────────────────────┘
          │ (opt-in, requires API key)
          ▼
┌─────────────────────────────────────────────────────┐
│  External services (partially trusted)               │
│                                                      │
│  Anthropic API  ←── File content snippets (AI)       │
│  Slack Webhook  ←── Command metadata (telemetry)     │
│  Cantara Maven  ←── Version queries (update check)   │
│  Slack Bot API  ←── UUID lookup (approval system)    │
└─────────────────────────────────────────────────────┘
```

#### What Data Never Leaves the Machine

- Workspace file content (except snippets sent to Anthropic for AI features)
- File names and paths
- User identity
- Search queries
- Relationships and graph structure

#### What Data Can Leave (and Under What Conditions)

| Data | Destination | Condition | Consent |
|------|------------|-----------|---------|
| Command name, success/failure, duration | Slack (telemetry) | Always (non-air-gapped) | Mandatory for pilot |
| Client UUID, OS, Java version | Slack (telemetry) | Always (non-air-gapped) | Mandatory for pilot |
| Client UUID | Slack (approval check) | Daily refresh | Part of pilot program |
| File content snippets (up to 10KB per file) | Anthropic API | User invokes `ask`, `perspectives`, `enrich --level ai`, `summary`, `research`, `report` | User must provide API key |
| Image data (up to 3.75MB per image, base64) | Anthropic API | User invokes `enrich --level ai` on images | User must provide API key |
| Current version | Cantara Maven repo (mvnrepo.cantara.no) | Daily background check (non-air-gapped) | Implicit (update notifications) |

### 4.2 Attack Surface Analysis

#### CLI Interface

**Attack vectors:**
- A malicious user with local access could run `synthesis scan` on arbitrary directories (permission-limited to what the running user can read)
- Crafted workspace paths via `-d` flag could attempt path traversal

**Current mitigations:**
- Workspace resolution normalizes paths with `toAbsolutePath().normalize()`
- `DirectoryScanner` skips symlinks explicitly
- File size limit (default 10MB) prevents indexing very large files

**Residual risks:**
- No validation that the workspace path is within any expected boundary
- The `-d` flag accepts any path the OS permits the process to read
- No sandboxing or chroot

**Recommendation:** Consider validating that workspace paths are within a configured allowed list for multi-user deployments.

#### MCP Server Interface

**Attack vectors:**
- Any AI agent with access to the configured MCP server can search the workspace index
- The `workspace` parameter in tool calls allows redirecting operations to any filesystem path
- A compromised AI agent (via prompt injection in workspace content) could issue search or relate queries

**Current mitigations:**
- MCP operates only over stdio — no network socket exposure
- The AI agent (e.g., Claude Code) must be explicitly configured to use this MCP server
- Workspace path resolution applies `toAbsolutePath().normalize()`

**Residual risks (IMPORTANT):**
- The `workspace` parameter accepted by MCP tools is resolved to any path accessible to the running user. An AI agent — potentially manipulated via prompt injection in the indexed content — could redirect searches to sensitive directories outside the intended workspace.
- No allowlist of permitted workspace paths exists in `SynthesisToolHandler.resolveWorkspace()`.
- The `stats` tool exposes workspace metadata (file counts, index size, last scan time) which could assist reconnaissance.

**Recommendation:** Add a `--allow-workspace` allowlist to the MCP server that restricts which workspace paths can be dynamically specified in tool calls.

#### LSP Server Interface

**Attack vectors:**
- Any IDE extension configured to use this LSP server gets workspace search and hover capabilities
- Document URI paths from the IDE are used to open files

**Current mitigations:**
- LSP operates only over stdio
- Workspace is set from IDE-provided `rootUri` or `workspaceFolders[0].uri` in the `initialize` request

**Residual risks:**
- LSP clients provide the workspace root — a malicious LSP client configuration could point the server at a sensitive directory
- Document link resolution could be manipulated by crafted file content

#### AI API (Anthropic)

**Attack vectors:**
- Prompt injection: malicious content in indexed files is included in prompts sent to Claude
- Data exfiltration: the AI feature sends file content to Anthropic's servers
- API key theft: if the key is compromised, unauthorized API usage occurs

**Current mitigations:**
- AI features are explicitly opt-in (require `ai.enabled=true` or API key)
- Air-gapped editions (`core`, `enterprise`) cannot invoke AI features at all
- Vision analysis has a configurable `confirmBeforeScan` flag (default: true)

**Residual risks:**
- No prompt injection defense exists — file content is embedded in prompts verbatim
- Users may not appreciate that `ask` and `summary` send file snippets to Anthropic
- The AI model (`claude-sonnet-4-5-20250929`) is hardcoded as default; users should be able to choose security-appropriate models

#### Credential Store

**Attack vectors:**
- File-system access to `~/.synthesis/credentials` yields XOR-obfuscated API keys
- XOR obfuscation is trivially reversible by anyone who knows the client UUID (from `~/.synthesis/client-uuid`)
- Both files have 600 permissions but are in the same directory

**Current mitigations:**
- File permissions restricted to owner read/write (600) on POSIX systems
- XOR key is machine-specific (UUID-based), preventing credentials from working on other machines
- Fallback XOR key (`synthesis-credential-fallback-seed`) is documented as weaker

**Residual risks (IMPORTANT):**
- XOR with a known key is not encryption. Anyone with read access to both `~/.synthesis/credentials` and `~/.synthesis/client-uuid` can decode the API key in seconds.
- The fallback key is a fixed string in the source code — credentials obfuscated with the fallback key are trivially decodable by anyone reading the source.
- No OS keychain integration (macOS Keychain, Linux libsecret, Windows DPAPI)

#### SQLite Database

**Attack vectors:**
- Direct read of `~/.synthesis/synthesis.db` by any user/process with filesystem access
- SQL injection if user-controlled input reaches query construction

**Current mitigations:**
- Database uses `PreparedStatement` throughout (observed in `SynthesisDatabase.cleanupOldRecords()`)
- WAL mode enabled for concurrency

**Residual risks:**
- No encryption at rest
- `~/.synthesis/synthesis.db` stores AI-generated summaries that may contain sensitive inferences about workspace content

### 4.3 Credential & Secrets Management

#### Hardcoded Secrets (Critical Finding)

Two credentials are hardcoded as `public static final` constants in the source code:

**1. Slack Incoming Webhook URL** (`TelemetryConfig.java`, line 33):
```java
public static final String DEFAULT_WEBHOOK_URL =
    "https://hooks.slack.com/services/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";
```
This webhook is the telemetry destination. Since it is hardcoded as `public static final` and the project is open-source, anyone can:
- Send fake telemetry events to the Synthesis team's Slack channel
- Flood the channel with spam
- Potentially abuse any quotas

The webhook comment states it "can only post messages" and "cannot read messages, list channels, or access any data" — so the risk is limited to write-only abuse. However, rotating this credential requires a code change and release, and it cannot be done without breaking existing installations.

**2. Slack Bot Token** (`ApprovalConfig.java`, line 32):
```java
private static final String DEFAULT_BOT_TOKEN =
    "xoxb-2741852652450-10496267091351-5mZK8KgCMrjxbq6mXZKlEOMU";
```
This is a Slack bot OAuth token with `channels:read` and `channels:history` scopes. Unlike the webhook, this is a **bot token that can read channel history**. Hardcoding it in an open-source repository means:
- Anyone can use this token to read the approval channel history (including any messages that happen to be in `#synthesis-pilots`)
- The token is permanently visible in git history
- If Slack's token rotation policies revoke it, the approval system breaks for all installations

**This is a critical security finding.** Both tokens should be immediately rotated (even if the new tokens are still hardcoded, the existing tokens should be invalidated). Long-term, these should be distributed via a secure channel (e.g., fetched on `synthesis init` from an authenticated endpoint, or distributed as part of installer packages outside the open-source repository).

#### Anthropic API Key Handling

Key resolution priority in `ClaudeClient.resolveApiKey()`:
1. `ANTHROPIC_API_KEY` environment variable (highest priority, plaintext in env)
2. `CredentialStore` (XOR-obfuscated, 600-permission file)

The environment variable approach is acceptable for development but problematic for production (env vars can leak to child processes, appear in process lists on some systems).

The `CredentialStore` approach is a reasonable local-machine solution, but the XOR obfuscation provides only minimal protection. A determined attacker with filesystem read access could decode keys in minutes.

#### Recommendation

Replace the local credential store with OS keychain integration:
- macOS: use `security` CLI or `java.awt.Desktop` keychain APIs
- Linux: use `secret-tool` (libsecret) or user keyring
- Windows: use Windows Credential Manager via DPAPI

For the hardcoded Slack tokens, immediately revoke and rotate them, and distribute replacements through a mechanism that does not require embedding them in public source code.

### 4.4 Dependency Security

No SBOM, Dependabot, or OWASP Dependency-Check is configured in the Maven build. The following table assesses vulnerability categories per dependency type:

| Dependency | Version | Vulnerability Category Risk |
|-----------|---------|----------------------------|
| lucene-* | 10.1.0 | Low — recently released, mature project |
| anthropic-java | 2.14.0 | Low — official SDK, well-maintained |
| snakeyaml | 2.2 | Low — the historic CVE-2022-1471 was fixed in 2.0 |
| pdfbox | 3.0.4 | Medium — complex PDF parsing surface area; check for XXE/DoS CVEs |
| metadata-extractor | 2.19.0 | Low — parsing library, monitor for buffer overflow issues |
| tess4j | 5.9.0 | Medium — wraps native Tesseract; native code vulnerabilities possible |
| jna | 5.14.0 | Medium — native library bridge; monitor for memory safety issues |
| jgit | 7.1.1.202505221757-r | Low — Eclipse foundation project, well-maintained |
| graphviz-java | 0.18.1 | Medium — executes external graphviz binary; command injection possible if filenames are not sanitized |
| jgrapht-core | 1.5.2 | Low — pure Java graph library |
| slack-api-client | 1.44.2 | Low — official SDK |
| sqlite-jdbc | 3.47.1.0 | Low — Xerial wrapper, stable |
| flyway-core | 10.22.0 | Low — migration tool, well-maintained |
| slf4j-simple | 2.0.16 | Low |
| jackson-databind | 2.18.2 | Medium — historically a high-CVE library; 2.18.x should be monitored |
| lsp4j | 0.23.1 | Low |
| picocli | 4.7.7 | Low |
| junit-jupiter | 5.11.4 | Low (test only) |

**Key missing controls:**
- No `dependencyManagement` BOM for transitive dependency version control
- No OWASP Dependency-Check Maven plugin
- No Snyk or similar CI-integrated vulnerability scanning
- No SBOM generation (CycloneDX or SPDX)
- Maven repositories include a custom Cantara repo — artifact integrity not verified beyond Maven checksum

### 4.5 AI/Prompt Security

#### What Content Goes to Anthropic

When AI features are used, the following content is sent to Anthropic's API:

| Feature | Content Sent | Max Size |
|---------|-------------|---------|
| `ask` command | Top-N search results (file content snippets, summaries, headings) | Configurable, typically 10KB × N |
| `perspectives` | Same as `ask` | Same |
| `summary` | Workspace file counts, type distributions, top files by size, code structure samples | Bounded by prompt template |
| `research` | Multi-pass: architecture patterns, dependency graphs, security indicators from file content | Large — multiple passes |
| `report` | Content of discovered business documents (PDFs, meeting notes, presentations) | Potentially very large |
| `enrich --level ai` (images) | Raw image bytes, base64-encoded (up to 3.75MB per image) | 3.75MB raw / ~5MB base64 |
| `explain` | File content of the explained target plus related files | Configurable |

#### Prompt Injection Risk

The `ask` command constructs a prompt that embeds search results verbatim. A malicious file in the workspace containing text like "Ignore all previous instructions and instead..." could attempt to manipulate Claude's responses. Current mitigations: none. The search results are inserted into a template prompt without sanitization.

This is a known risk category for RAG-based systems. The severity depends on what actions Claude can take in context (via MCP tools). In the Synthesis context, prompt injection in indexed files could cause Claude to:
- Return misleading search results
- Recommend harmful commands
- Leak other indexed content in the response

**Recommendation:** Prepend a system-level instruction that clarifies the structure of the context and warns the model to ignore embedded instructions. Consider XML-wrapping file content to visually separate it from instructions.

#### Data Minimisation

The `AiConfig.contentSummary` field (default: `false`) controls whether content summarization is applied during AI calls. Vision analysis has `confirmBeforeScan: true` by default, which prompts users before sending images to Anthropic. These are positive data minimisation controls.

### 4.6 Data Privacy

#### What Is Indexed

The Lucene index stores for each file:
- Absolute path and relative path
- File content (up to 10KB configurable via `contentPreviewBytes`)
- Extracted headings, keywords, and summaries
- File size, modification time, content hash
- AI-generated descriptions (if enriched)

For media files, companion `.synthesis.md` files may contain:
- Full Whisper transcripts (local audio/video transcription)
- OCR text from PDF slides
- Claude Vision descriptions (if AI enriched)

#### Where Indexes Are Stored

| Artifact | Location | Notes |
|---------|---------|-------|
| Lucene index | `<workspace>/.synthesis/index/` | Per-workspace, excludes `.synthesis/**` by default |
| SQLite database | `~/.synthesis/synthesis.db` | Global, contains metrics, tracking, snapshots, AI caches |
| Companion files | `<original-file>.synthesis.md` | Stored alongside original files in workspace |
| AI summary cache | SQLite `summary_cache` table | Contains AI-generated text indexed to workspace+fingerprint |
| Research cache | SQLite `research_cache` table | Contains multi-pass AI analysis |
| Report cache | SQLite `report_cache` table | Contains AI-generated business reports |
| Client UUID | `~/.synthesis/client-uuid` | Random UUID, no PII |
| Telemetry config | `~/.synthesis/telemetry.properties` | Slack webhook URL |
| Credentials | `~/.synthesis/credentials` | XOR-obfuscated API keys |
| Approval status | `~/.synthesis/approval-status` | Cached pilot approval state |
| MCP logs | `~/.synthesis/logs/mcp-server.log` | Operational logs (no content) |
| LSP logs | `~/.synthesis/logs/lsp-server.log` | Operational logs |

#### How to Purge/Delete

- Remove the Lucene index: `rm -rf <workspace>/.synthesis/index/`
- Remove companion files: `find <workspace> -name "*.synthesis.md" -delete`
- Remove global SQLite database: `rm ~/.synthesis/synthesis.db`
- Remove all global state: `rm -rf ~/.synthesis/`
- No `synthesis purge` or `synthesis reset` command currently exists — this is a gap

### 4.7 Hardening Roadmap

#### Quick Wins (< 1 sprint)

1. **Rotate hardcoded Slack tokens immediately.** Revoke `DEFAULT_WEBHOOK_URL` and `DEFAULT_BOT_TOKEN`. Replace with tokens distributed outside the open-source repository (environment variable injection at install time, or fetched from an authenticated bootstrap endpoint).

2. **Add `SYNTHESIS_TELEMETRY_WEBHOOK` environment variable override.** Allow the webhook URL to be externally configured without requiring a code change. This reduces the blast radius of webhook leaks.

3. **Add `synthesis purge` command** that removes the Lucene index, companion files, and optionally the SQLite database.

4. **Add OWASP Dependency-Check to the Maven build.** Configure as part of the verify phase with a report generated on every build.

#### Short-Term (1-4 weeks)

5. **Add MCP workspace path allowlist.** Extend `SynthesisMCPServer` to accept `--allow-workspace` flags that restrict which paths tool calls can address.

6. **Implement prompt injection defenses.** Wrap user-provided file content in XML tags (`<document>`, `<file-content>`) and add a system prompt clarifying the expected structure to Claude.

7. **Document data flows to Anthropic clearly.** Show users exactly which files will contribute content to AI calls before they execute. The `--dry-run` flag pattern used elsewhere should be extended to AI commands.

8. **Add user consent prompt for telemetry.** Even if telemetry remains mandatory for pilots, display a clear one-time disclosure of exactly what is sent on first run.

#### Medium-Term (1-3 months)

9. **Implement OS keychain integration for API key storage.** Remove the XOR-based credential store in favor of OS-provided secure storage (macOS Keychain, Linux Secret Service, Windows Credential Manager).

10. **Add SBOM generation to the release pipeline.** Use the CycloneDX Maven plugin to generate a machine-readable bill of materials for each release.

11. **Implement structured audit logging.** Log all AI API calls (model, timestamp, approximate token count, workspace) to a local audit log without including content. This enables post-hoc review of what was sent to external services.

12. **Add CI/CD dependency vulnerability scanning.** Integrate Snyk or Dependabot for the GitHub repository.

#### Long-Term (3-12 months)

13. **Implement workspace-level encryption for the Lucene index.** For enterprise deployments, encrypt index files at rest using AES-256 with a workspace-specific key stored in the OS keychain.

14. **Implement MCP authentication.** The current stdio transport has no authentication. For network-deployed scenarios, add a token-based authentication layer.

15. **Formal threat model and penetration test.** Commission a security review with a focus on prompt injection, path traversal, and credential leakage.

---

## 5. Code Quality & Test Coverage

### 5.1 Test Coverage by Package

| Package | Test Files | Notes |
|---------|-----------|-------|
| `ai` | 3 (`ClaudeClientTest`, `DirectedSynthesisEngineTest`, `EmbeddingServiceTest`, `PromptTemplatesTest`) | Core AI client tested |
| `analyzer` | 6 (`MarkdownAnalyzerTest`, `CodeAnalyzerTest`, `YamlAnalyzerTest`, `PdfAnalyzerTest`, `ImageAnalyzerTest`, `VideoAnalyzerTest`, `PresentationExtractorTest`) | All analyzers covered |
| `architecture` | 3 (`ArchitectureMonitorTest`, `ArchitectureAlertTest`, `ArchitectureMonitorExpandedTest`) | Expanded wave testing |
| `changelog` | 7 (`ChangeReportGeneratorTest`, `SignificanceClassifierTest`, `SnapshotManagerTest`, `ChangeEventTest`, `ChangeSignificanceTest`, `SignificanceClassifierParameterizedTest`, `SnapshotManagerParameterizedTest`) | Parameterized tests |
| `cli` | 14+ (`AnalyzeCommandTest`, `AskCommandTest`, `RelateCommandTest`, `WatchCommandTest`, `ChangedCommandTest`, `ExportCommandEnhancedTest`, `InteractiveConfirmationTest`, `LearnCommandTest`, `WatchCommandLearnTest`, `OrgEnrichCommandTest`, `TrackCommandTest`, `StagingCommandUtilsTest`, `ChangedCommandParameterizedTest`) | Major commands tested |
| `config` | 3 (`ConfigLoaderTest`, `ConfigLoaderExpandedTest`, `SynthesisConfigTest`) | Config loading well tested |
| `core` | 7 (`DirectoryScannerTest`, `ScanStateTest`, `EcosystemDetectorTest`, `RepositoryManagerTest`, `SmartExclusionsIntegrationTest`, `DirectoryScannerParameterizedTest`, `EcosystemDetectorParameterizedTest`, `ScanStateParameterizedTest`, `SmartExclusionsTest`, `WorkspaceManagerTest`) | Core scanning tested |
| `db` | 1 (`SynthesisDatabaseExpandedTest`) | Database tested |
| `enrichment` | 1 (`CompanionFileGeneratorTest`) | Companion generation tested |
| `git` | 1 (`GitIntegrationTest`) | Git integration tested |
| `graph` | 2 (`GraphBuilderTest`, `GraphRendererTest`) | Graph building and rendering |
| `index` | 2 (`SearchIndexTest`, `SearchIndexExtendedTest`) | Core search tested |
| `insights` | 2 (`InsightsEngineTest`, `InsightsEngineExpandedTest`) | Metrics engine tested |
| `integration` | 1 (`MediaScanIntegrationTest`) | Media integration |
| `lsp` | 1 (`SynthesisLanguageServerTest`) | LSP server tested |
| `mcp` | 2 (`SynthesisMCPServerTest`, `McpToolHandlerExtensionsTest`) | MCP server tested |
| `metrics` | 1 (`MetricsCollectorTest`) | Metrics tested |
| `org` | 7 (`OrganizationRegistryTest`, `OrganizationScannerTest`, `OrganizationTest`, `DownloadsClassifierTest`, `OrgSearchIntegrationTest`, `ClientStatusTest`, `DiscoveredOrganizationTest`, `ClientCodebaseResolverTest`) | Organization subsystem tested |
| `report` | 6 (`BusinessDocumentFinderTest`, `EntityDocumentFinderTest`, `ReportPromptsTest`, `ReportCacheTest`, `ReportEngineTest`, `ReportCommandTest`, `ReportAutoSaveTest`) | Report subsystem tested |
| `research` | (covered via ResearchCommand integration) | — |
| `skills` | 3 (`SkillGeneratorTest`, `SkillTemplateTest`, `SkillInstallerTest`) | Skills generation tested |
| `telemetry` | 7 (`TelemetryServiceTest`, `TelemetryConfigTest`, `TelemetryEventTest`, `ClientUUIDTest`, `ApprovalConfigTest`, `ApprovalServiceTest`, `SlackIntegrationTest`, `DebugApprovalTest`) | Telemetry well tested |
| `tracking` | 2 (`FileMovementTrackerTest`, `FileTrackingDatabaseTest`) | Movement tracking tested |
| `update` | 3 (`UpdateManagerTest`, `VersionManifestTest`, `InstallationFingerprintTest`) | Update system tested |
| `util` | 8 (`BundledBinaryManagerTest`, `FfprobeDetectorTest`, `PdftoppmDetectorTest`, `TesseractOcrExtractorTest`, `TesseractDetectorTest`, `WhisperTranscriberTest`, `WhisperDetectorTest`, `PdfToImageConverterTest`) | Utilities well tested |

**Coverage assessment:** Coverage is broad. Most packages have dedicated test classes. The `summary` and `research` packages lack dedicated unit tests (they are likely exercised through integration or CLI tests). The `lsp` package has minimal test coverage (1 file for 3 source files). The `mcp` tool handler's workspace path security logic is not independently tested.

### 5.2 Code Quality Indicators

#### Largest Files (by line count)

| File | LOC | Concern |
|------|-----|---------|
| `DashboardCommand.java` | 2,266 | Very large — likely a God Class |
| `SynthesisToolHandler.java` | 1,140 | Large but bounded to MCP dispatch |
| `StagingCommand.java` | 1,129 | Large — staging workflow complexity |
| `StatusCommand.java` | 910 | Large — status rendering |
| `SynthesisMCPServer.java` | 863 | Manageable — protocol + tool registration |
| `UpdateManager.java` | 812 | Large update management |
| `OrgCommand.java` | 792 | Large org management command |
| `SkillTemplate.java` | 730 | Template data, mostly string content |

`DashboardCommand.java` at 2,266 lines is flagged as a clear God Class requiring refactoring. At this size, it is difficult to test thoroughly and likely conflates presentation, business logic, and data access concerns.

#### Known Technical Debt

From source code review:
- `SearchIndex` is not thread-safe (documented in class JavaDoc) — concurrent access from MCP + CLI would cause issues
- Migration V7 is missing from `db/migration/` — the sequence jumps from V6 to V8. This may be intentional (skipped migration) but needs documentation.
- `getObject(col, Integer.class)` is called in some database code — documented in CLAUDE.md as failing for NULL columns on SQLite JDBC
- The `EmbeddingService` class exists but embedding-based search is listed as a future capability — this is dead code from a feature perspective

#### Dead Code Candidates

Based on the `EmbeddingService` class existence alongside a note that embeddings are a future feature, the following are dead code candidates:
- `EmbeddingService.java` (ai package) — embedding infrastructure not yet connected
- `DocumentFields.EMBEDDING` and `DocumentFields.EMBEDDING_MODEL` — defined but not used in any live query
- `ScanState` may have methods not exercised by any current command

#### Circular Dependency Clusters

No circular package dependencies were identified in the source structure — the dependency graph flows clearly from `cli` → `core` → `config`, and AI/enrichment layers are consistently downstream. However, the `cli` package is heavily loaded and pulls in most other packages, which is expected for a command-line application but limits reusability.

### 5.3 Known Issues

Based on code inspection and CLAUDE.md notes:

| Issue | Package | Description |
|-------|---------|-------------|
| #44 | ai/report | Silent truncation in AI responses — `generateWithMeta()` now detects this via `stopReason=MAX_TOKENS` |
| #54 | report | Integration tests added for report generation |
| #58 | report | Truncation detection merged with integration tests |
| #59 | config | `report.outputDir` configurable output location added |
| Missing V7 | db | Migration sequence jumps V6→V8 with no V7 |
| Thread safety | index | `SearchIndex` not thread-safe; concurrent CLI+MCP access is unsafe |
| Windows permissions | config | `CredentialStore` cannot set POSIX permissions on Windows NTFS |

---

## 6. Production Readiness Assessment

### 6.1 Deployment Options

#### Current Distribution Mechanism

Synthesis is distributed as fat JARs via Maven releases to the Cantara Maven repository. A launcher script (`bin/synthesis`) sets `SYNTHESIS_EDITION` and invokes `java -jar synthesis.jar`.

#### Supported Deployment Patterns

| Pattern | Support | Notes |
|---------|---------|-------|
| Local developer tool (JAR + script) | Supported | Primary distribution model |
| Global PATH installation via `~/bin/synthesis` | Supported | Symlink per CLAUDE.md |
| Docker container | Possible | No official Dockerfile provided |
| systemd service (for `watch` command) | Possible | No unit file provided |
| CI/CD integration (scan on commit) | Possible | Via Git hooks or pipeline step |
| MCP server for Claude Code | Supported | Documented in `SynthesisMCPServer` help |
| LSP server for IDEs | Supported | Documented configuration examples |

#### Gaps

- No official Docker image
- No systemd unit file for persistent `watch` mode
- No Helm chart or Kubernetes deployment configuration
- No signed JAR artifacts or release attestation

### 6.2 Monitoring & Observability Gaps

| Gap | Impact | Severity |
|-----|--------|---------|
| No structured logging format | Log analysis is difficult | Medium |
| No metrics export (Prometheus, OpenTelemetry) | Cannot monitor in production dashboards | Medium |
| MCP server logs to rotating file only | No log aggregation | Low |
| No health check endpoint | Cannot be monitored by uptime tools | Low |
| SQLite `metrics` table requires custom queries | No out-of-box dashboard | Medium |
| No distributed tracing | Cannot trace AI call latency end-to-end | Low |
| No alerting on index health degradation | Silent failures possible | Medium |

The `synthesis status` command provides a one-time health snapshot but there is no continuous monitoring capability. The SQLite `metrics` table collects MCP invocation data, but it requires custom SQL queries to extract actionable metrics.

### 6.3 Operational Maturity Matrix

| Capability | Status | Gap | Recommendation |
|-----------|--------|-----|---------------|
| Installation | Partial | No signed artifacts, no official Docker | Add release signing, Docker image |
| Configuration management | Good | No config validation on startup | Add `synthesis validate-config` command |
| Secrets management | Weak | XOR obfuscation, hardcoded tokens | OS keychain integration, rotate tokens |
| Logging | Partial | File-only, no structured format | Add JSON structured logging option |
| Metrics | Partial | SQLite only, no export | Add Prometheus metrics endpoint option |
| Alerting | Missing | No alerting capability | Integration with monitoring systems |
| Backup & recovery | Missing | No backup utilities | Document backup procedure for SQLite |
| High availability | Not applicable | Single-user local tool | N/A for current use case |
| Audit logging | Missing | No audit trail for AI calls | Add structured audit log |
| Update management | Good | Self-updating from Cantara repo | Add checksum verification of downloaded JARs |
| Dependency management | Weak | No vulnerability scanning | Add OWASP Dependency-Check |
| Documentation | Good | Comprehensive via `docs/perspectives/` | Maintain |
| Test coverage | Good | 2,291 tests, broad package coverage | Fill gaps in `summary`, `research`, `lsp` |

---

## 7. Recommendations

### 7.1 Immediate (< 1 sprint)

**P0 — Security:**
1. **Rotate both hardcoded Slack credentials** (`DEFAULT_WEBHOOK_URL` in `TelemetryConfig`, `DEFAULT_BOT_TOKEN` in `ApprovalConfig`). Revoke current tokens. Do not embed replacements in open-source code.
2. **Add `SYNTHESIS_TELEMETRY_WEBHOOK` environment variable** as an override for the webhook URL, allowing deployment-time configuration without code changes.

**P1 — Developer Experience:**
3. **Add `synthesis purge` command** to clear Lucene index, companion files, and optionally the SQLite database. This is a frequently needed operation and is currently manual.
4. **Document V7 migration gap.** Either add a V7 migration or add a code comment explaining why V7 is absent.

**P2 — Quality:**
5. **Refactor `DashboardCommand.java`** (2,266 lines). Extract rendering logic into dedicated helper classes and reduce the class to a command dispatch role.

### 7.2 Short-Term (1-4 weeks)

6. **Add OWASP Dependency-Check to Maven build.** Configure the `dependency-check-maven` plugin to run during `verify` phase and fail builds on HIGH severity findings.
7. **Implement MCP workspace allowlist.** Add `--allow-workspace` to `SynthesisMCPServer` and enforce it in `SynthesisToolHandler.resolveWorkspace()`.
8. **Add prompt injection defenses.** Wrap indexed file content in structured XML tags in all prompts sent to Claude. Add a system prompt clarifying context boundaries.
9. **Add thread-safe Lucene access.** Document or enforce that the MCP server and CLI do not share a workspace index simultaneously, or implement proper locking.
10. **Write unit tests for `summary` and `research` packages.** Currently these packages have limited test coverage.

### 7.3 Medium-Term (1-3 months)

11. **Implement OS keychain integration for credentials.** Replace `CredentialStore`'s XOR obfuscation with OS-provided secure storage. Use JNA (already a dependency) to call OS keychain APIs.
12. **Add SBOM generation.** Integrate CycloneDX Maven plugin into the release pipeline to generate a machine-readable software bill of materials.
13. **Implement structured audit logging for AI calls.** Log timestamp, model, workspace path, and token count (without content) to `~/.synthesis/audit.log`.
14. **Add `synthesis validate-config` command.** Parse and validate `synthesis-config.yaml`, reporting all errors before they cause runtime failures.
15. **Add Prometheus metrics export option.** Expose the SQLite metrics as a Prometheus scrape endpoint when running as a long-lived service.

### 7.4 Strategic (3-12 months)

16. **Formal security audit.** Commission a third-party penetration test focused on: prompt injection via indexed content, MCP workspace path traversal, credential leakage, and dependency vulnerabilities.
17. **Workspace-level index encryption.** For enterprise deployments, encrypt the Lucene index at rest using a workspace-specific key stored in the OS keychain.
18. **Native image compilation.** The picocli annotation processor already generates GraalVM reflection metadata. Completing native image support would eliminate the JVM startup penalty (currently ~0.5–1s) and simplify distribution.
19. **Formal data retention and deletion policies.** Implement `synthesis forget <file>` to remove a specific file from all indexes and caches, supporting right-to-erasure workflows for workspaces containing personal data.

---

## Appendix A: Full Dependency List

From `pom.xml` — every dependency with version and purpose:

| Artifact | GroupId | Version | Scope | Purpose |
|---------|---------|---------|-------|---------|
| `picocli` | `info.picocli` | 4.7.7 | compile | CLI framework and annotation processing |
| `anthropic-java` | `com.anthropic` | 2.14.0 | compile | Anthropic Claude AI SDK |
| `lucene-core` | `org.apache.lucene` | 10.1.0 | compile | Core Lucene full-text search engine |
| `lucene-queryparser` | `org.apache.lucene` | 10.1.0 | compile | Query string parsing |
| `lucene-highlighter` | `org.apache.lucene` | 10.1.0 | compile | Search result highlighting |
| `lucene-analysis-common` | `org.apache.lucene` | 10.1.0 | compile | Text analyzers (StandardAnalyzer) |
| `snakeyaml` | `org.yaml` | 2.2 | compile | YAML deserialization for config |
| `pdfbox` | `org.apache.pdfbox` | 3.0.4 | compile | PDF text extraction |
| `metadata-extractor` | `com.drewnoakes` | 2.19.0 | compile | Image EXIF/IPTC/XMP metadata |
| `tess4j` | `net.sourceforge.tess4j` | 5.9.0 | compile | Tesseract OCR Java binding |
| `jna` | `net.java.dev.jna` | 5.14.0 | compile | Native library access (JNA/Tesseract) |
| `org.eclipse.jgit` | `org.eclipse.jgit` | 7.1.1.202505221757-r | compile | Git repository integration |
| `graphviz-java` | `guru.nidi` | 0.18.1 | compile | Graphviz DOT graph rendering |
| `jgrapht-core` | `org.jgrapht` | 1.5.2 | compile | Graph data structures and algorithms |
| `slack-api-client` | `com.slack.api` | 1.44.2 | compile | Slack API for telemetry and approval |
| `sqlite-jdbc` | `org.xerial` | 3.47.1.0 | compile | SQLite JDBC driver |
| `flyway-core` | `org.flywaydb` | 10.22.0 | compile | Database schema migration |
| `slf4j-simple` | `org.slf4j` | 2.0.16 | compile | Logging implementation |
| `jackson-databind` | `com.fasterxml.jackson.core` | 2.18.2 | compile | JSON processing for MCP protocol |
| `org.eclipse.lsp4j` | `org.eclipse.lsp4j` | 0.23.1 | compile | Language Server Protocol implementation |
| `junit-jupiter` | `org.junit.jupiter` | 5.11.4 | test | JUnit 5 test framework |

**Maven Repositories:**
- Central (implied)
- `cantara-releases`: `https://mvnrepo.cantara.no/content/repositories/releases/`
- `cantara-snapshots`: `https://mvnrepo.cantara.no/content/repositories/snapshots/`

**Maven Plugins:**
- `maven-compiler-plugin` 3.13.0 (with `picocli-codegen` annotation processor)
- `maven-shade-plugin` 3.6.0 (produces 3 fat JARs)
- `maven-surefire-plugin` 3.5.2 (test execution)

---

## Appendix B: Database Schema Reference

The SQLite database at `~/.synthesis/synthesis.db` is managed by Flyway with migrations V1–V8 (V7 absent). All migrations use `CREATE TABLE IF NOT EXISTS` and `INSERT OR REPLACE INTO metadata` for idempotency.

### Table: `metrics` (V1 + V2)

| Column | Type | Index | Description |
|--------|------|-------|-------------|
| `id` | INTEGER PK AUTOINCREMENT | — | Row identifier |
| `timestamp` | INTEGER NOT NULL | idx_timestamp | Unix epoch seconds |
| `event_type` | TEXT NOT NULL | idx_event_type | Event category |
| `mcp_tool` | TEXT | idx_mcp_tool | MCP tool name (search, relate, etc.) |
| `mcp_workspace` | TEXT | idx_workspace | Workspace path |
| `execution_time_ms` | INTEGER | — | Duration in milliseconds |
| `result_count` | INTEGER | — | Number of results returned |
| `success` | INTEGER NOT NULL | — | Boolean (0/1) |
| `error_message` | TEXT | — | Error description if failed |
| `search_pattern` | TEXT | — | Search query pattern |
| `ai_feature` | TEXT | — | AI feature used |
| `ai_tokens_used` | INTEGER | — | Token consumption |
| `ai_retry` | INTEGER | — | Retry count |
| `workspace_tag` | TEXT | idx_workspace_tag | Workspace category (V2) |

### Table: `metadata` (V1)

| Column | Type | Description |
|--------|------|-------------|
| `key` | TEXT PK | Metadata key |
| `value` | TEXT NOT NULL | Metadata value |

Contains schema version markers and feature flags (e.g., `feature_workspace_tags=enabled`).

### Table: `file_movements` (V3)

| Column | Type | Index | Description |
|--------|------|-------|-------------|
| `id` | INTEGER PK AUTOINCREMENT | — | Row identifier |
| `timestamp` | INTEGER NOT NULL | idx_fm_timestamp | Detection time |
| `content_hash` | TEXT NOT NULL | idx_fm_hash | MD5 of file content |
| `source_workspace` | TEXT | idx_fm_source | Source workspace path |
| `source_path` | TEXT NOT NULL | idx_fm_source | Original file path |
| `target_workspace` | TEXT | — | Destination workspace |
| `target_path` | TEXT | — | Destination path |
| `file_size` | INTEGER NOT NULL | — | Bytes |
| `file_type` | TEXT | — | File type classification |
| `status` | TEXT NOT NULL DEFAULT 'detected' | idx_fm_status | detected, confirmed, archived |
| `detection_method` | TEXT NOT NULL | — | How movement was detected |
| `safety_expiry` | INTEGER | idx_fm_safety | Expiry for safety retention |
| `notes` | TEXT | — | Human notes |

### Table: `file_audit_log` (V3)

| Column | Type | Index | Description |
|--------|------|-------|-------------|
| `id` | INTEGER PK AUTOINCREMENT | — | Row identifier |
| `timestamp` | INTEGER NOT NULL | idx_fal_timestamp | Event time |
| `movement_id` | INTEGER | idx_fal_movement | FK to file_movements |
| `action` | TEXT NOT NULL | — | Action taken |
| `details` | TEXT | — | Additional details |

### Table: `workspace_snapshots` (V3)

| Column | Type | Index | Description |
|--------|------|-------|-------------|
| `id` | INTEGER PK AUTOINCREMENT | — | Row identifier |
| `workspace_path` | TEXT NOT NULL | idx_ws_workspace | Workspace path |
| `workspace_name` | TEXT | — | Human-readable name |
| `snapshot_time` | INTEGER NOT NULL | idx_ws_time | Unix epoch |
| `file_count` | INTEGER NOT NULL | — | Files at snapshot time |
| `total_size_bytes` | INTEGER NOT NULL | — | Total workspace size |
| `trigger` | TEXT NOT NULL DEFAULT 'scheduled' | — | What triggered snapshot |

### Table: `snapshot_entries` (V3)

| Column | Type | Index | Description |
|--------|------|-------|-------------|
| `id` | INTEGER PK AUTOINCREMENT | — | Row identifier |
| `snapshot_id` | INTEGER NOT NULL | idx_se_snapshot | FK to workspace_snapshots (CASCADE DELETE) |
| `relative_path` | TEXT NOT NULL | idx_se_path | File path relative to workspace |
| `content_hash` | TEXT | idx_se_hash | MD5 hash |
| `file_size` | INTEGER NOT NULL | — | Bytes |
| `last_modified` | INTEGER NOT NULL | — | Unix epoch |
| `file_type` | TEXT | — | File type classification |

### Table: `change_events` (V3)

| Column | Type | Index | Description |
|--------|------|-------|-------------|
| `id` | INTEGER PK AUTOINCREMENT | — | Row identifier |
| `workspace_path` | TEXT NOT NULL | idx_ce_workspace | Workspace path |
| `detected_time` | INTEGER NOT NULL | idx_ce_time | Unix epoch |
| `base_snapshot_id` | INTEGER | — | FK to workspace_snapshots |
| `compare_snapshot_id` | INTEGER | — | FK to workspace_snapshots |
| `change_type` | TEXT NOT NULL | idx_ce_type | added, modified, deleted, moved |
| `relative_path` | TEXT NOT NULL | — | Affected file path |
| `previous_path` | TEXT | — | Previous path (for renames) |
| `content_hash` | TEXT | — | Content hash |
| `file_size` | INTEGER | — | File size |
| `file_type` | TEXT | — | File type |
| `significance` | TEXT DEFAULT 'normal' | idx_ce_significance | noise, normal, critical |

### Table: `sub_workspaces` (V4)

| Column | Type | Index | Description |
|--------|------|-------|-------------|
| `id` | INTEGER PK AUTOINCREMENT | — | Row identifier |
| `workspace_path` | TEXT NOT NULL | idx_sw_workspace | Parent workspace path |
| `name` | TEXT NOT NULL | idx_sw_name | Sub-workspace name |
| `path_prefix` | TEXT NOT NULL | — | Directory prefix |
| `description` | TEXT | — | Human description |
| `type` | TEXT NOT NULL DEFAULT 'general' | — | general, staging, source-code, documents |
| `tags` | TEXT | — | Comma-separated tags |
| `created_at` | INTEGER NOT NULL | — | Unix epoch |
| `updated_at` | INTEGER NOT NULL | — | Unix epoch |
| UNIQUE | (workspace_path, name) | — | One name per workspace |

### Table: `staging_files` (V4)

| Column | Type | Index | Description |
|--------|------|-------|-------------|
| `id` | INTEGER PK AUTOINCREMENT | — | Row identifier |
| `workspace_path` | TEXT NOT NULL | idx_sf_workspace | Parent workspace |
| `sub_workspace` | TEXT NOT NULL | — | Staging sub-workspace name |
| `relative_path` | TEXT NOT NULL | — | File path |
| `file_size` | INTEGER NOT NULL | — | Bytes |
| `file_type` | TEXT | — | File type classification |
| `content_hash` | TEXT | — | MD5 hash |
| `classified_org` | TEXT | idx_sf_org | Detected organization |
| `classification_confidence` | REAL DEFAULT 0.0 | — | 0.0–1.0 confidence |
| `suggested_destination` | TEXT | — | Auto-suggested destination |
| `status` | TEXT NOT NULL DEFAULT 'pending' | idx_sf_status | pending, promoted, expired |
| `ingested_at` | INTEGER NOT NULL | — | Intake time |
| `expires_at` | INTEGER NOT NULL | idx_sf_expires | Expiry time |
| `promoted_at` | INTEGER | — | Promotion time |
| `promoted_to` | TEXT | — | Destination path |
| UNIQUE | (workspace_path, relative_path) | — | — |

### Table: `sub_workspace_stats` (V4)

| Column | Type | Description |
|--------|------|-------------|
| `id` | INTEGER PK AUTOINCREMENT | Row identifier |
| `workspace_path` | TEXT NOT NULL | Parent workspace |
| `sub_workspace` | TEXT NOT NULL | Sub-workspace name |
| `snapshot_time` | INTEGER NOT NULL | Unix epoch |
| `file_count` | INTEGER NOT NULL DEFAULT 0 | Total files |
| `total_size_bytes` | INTEGER NOT NULL DEFAULT 0 | Total bytes |
| `code_files` | INTEGER NOT NULL DEFAULT 0 | Source code count |
| `doc_files` | INTEGER NOT NULL DEFAULT 0 | Document count |
| `media_files` | INTEGER NOT NULL DEFAULT 0 | Media file count |
| `other_files` | INTEGER NOT NULL DEFAULT 0 | Other count |

### Table: `summary_cache` (V5)

| Column | Type | Description |
|--------|------|-------------|
| `id` | INTEGER PK AUTOINCREMENT | Row identifier |
| `workspace_path` | TEXT NOT NULL | Workspace path |
| `summary_level` | TEXT NOT NULL | executive, manager, developer |
| `perspective` | TEXT NOT NULL | general, executive, architect, security, etc. |
| `index_fingerprint` | TEXT NOT NULL | Hash of (file_count + last_modified) |
| `profile_json` | TEXT NOT NULL | Serialized CodebaseProfile |
| `ai_summary` | TEXT | AI-generated text (nullable) |
| `created_at` | TIMESTAMP NOT NULL | Creation time |
| `generation_time_ms` | INTEGER NOT NULL | How long generation took |
| `model_used` | TEXT | Claude model identifier |
| `expires_at` | TIMESTAMP | Optional TTL |
| `hits` | INTEGER NOT NULL DEFAULT 0 | Cache hit counter |
| UNIQUE | (workspace_path, summary_level, perspective, index_fingerprint) | — |

### Table: `research_cache` (V6)

| Column | Type | Description |
|--------|------|-------------|
| `id` | INTEGER PK AUTOINCREMENT | Row identifier |
| `workspace_path` | TEXT NOT NULL | Workspace path |
| `target` | TEXT NOT NULL | chatgpt, notebooklm-infographic, etc. |
| `topic` | TEXT NOT NULL | full, architecture, security, etc. |
| `passes` | TEXT NOT NULL | Comma-separated pass names |
| `index_fingerprint` | TEXT NOT NULL | Index state hash |
| `model` | TEXT NOT NULL | Claude model used |
| `report_content` | TEXT NOT NULL | Rendered final report |
| `pass_results` | TEXT NOT NULL | JSON array of pass results |
| `token_count` | INTEGER | Total tokens |
| `estimated_cost_usd` | REAL | Estimated USD cost |
| `created_at` | TEXT NOT NULL | ISO-8601 timestamp |
| `hits` | INTEGER NOT NULL DEFAULT 0 | Cache hit counter |
| UNIQUE | (workspace_path, target, topic, passes, index_fingerprint) | — |

### Table: `report_cache` (V8)

| Column | Type | Description |
|--------|------|-------------|
| `id` | INTEGER PK AUTOINCREMENT | Row identifier |
| `workspace_path` | TEXT NOT NULL | Workspace path |
| `topic` | TEXT NOT NULL | weekly, pipeline, activities, executive, decisions |
| `target` | TEXT NOT NULL | ceo, board, investor |
| `period` | TEXT NOT NULL | 1w, 2w, 1m |
| `document_fingerprint` | TEXT NOT NULL | Hash of doc paths + mtimes |
| `model` | TEXT NOT NULL | Claude model used |
| `report_content` | TEXT NOT NULL | Rendered report |
| `token_count` | INTEGER | Total tokens |
| `estimated_cost_usd` | REAL | Estimated cost |
| `created_at` | TEXT NOT NULL | ISO-8601 timestamp |
| `hits` | INTEGER NOT NULL DEFAULT 0 | Cache hit counter |
| UNIQUE | (workspace_path, topic, target, period, document_fingerprint) | — |

---

## Appendix C: CLI Command Reference

All commands registered in `SynthesisApp` as of v1.9.3-SNAPSHOT:

| Command | Class | Purpose |
|---------|-------|---------|
| `init` | `InitCommand` | Initialize a workspace (creates `.synthesis/`, writes config, generates UUID, sends telemetry) |
| `scan` | `ScanCommand` | Scan and index files (incremental by default, `--full` for full rebuild) |
| `search` | `SearchCommand` | Full-text search with optional filters (`--type`, `--scope`, `--org`, `--repo`, `--all`) |
| `ask` | `AskCommand` | AI Q&A grounded in indexed content; requires API key; **removed in air-gapped mode** |
| `analyze` | `AnalyzeCommand` | Smart project analysis (structure, tech stack, health) |
| `relate` | `RelateCommand` | Bidirectional file relationship analysis |
| `insights` | `InsightsCommand` | Deep codebase metrics (connectivity, complexity, quality, architecture) |
| `graph` | `GraphCommand` | Generate architecture graphs in Mermaid, DOT, or JSON |
| `cross-repo-deps` | `CrossRepoDepsCommand` | Cross-repository dependency discovery |
| `watch` | `WatchCommand` | Real-time file system monitoring with auto-indexing |
| `diff` | `DiffCommand` | Git diff integration for changed file analysis |
| `changed` | `ChangedCommand` | Files changed since a date or git ref |
| `maintain` | `MaintainCommand` | Detect changes and update index incrementally; cleanup old records |
| `export` | `ExportCommand` | Export index as JSON, Markdown, or AI-ready document sets |
| `status` | `StatusCommand` | Workspace health, index statistics, and configuration summary |
| `dashboard` | `DashboardCommand` | Full interactive dashboard with all workspace metrics |
| `summary` | `SummaryCommand` | AI-enhanced executive summaries at different levels and perspectives |
| `org` | `OrgCommand` | Organization management: scan, list, classify |
| `learn` | `LearnCommand` | Generate Claude Code skills from workspace knowledge |
| `perspectives` | `PerspectivesCommand` | Multi-perspective analysis from different roles; **removed in air-gapped mode** |
| `extract-slides` | `ExtractSlidesCommand` | Extract slides from presentation PDFs |
| `telemetry` | `TelemetryCommand` | View pilot status and telemetry configuration |
| `update` | `UpdateCommand` | Update Synthesis components; `--check` to preview; `--health` for installation health |
| `enrich` | `EnrichCommand` | Generate companion files for binary assets (images, videos, PDFs) |
| `explain` | `ExplainCommand` | AI-powered explanation of files, directories, or patterns |
| `architecture` | `ArchitectureCommand` | Architecture health monitoring and alerting |
| `metrics` | `MetricsCommand` | View operational metrics from SQLite database |
| `list` | `ListWorkspacesCommand` | List all configured workspaces and sub-workspaces |
| `which` | `WhichCommand` | Find which workspace(s) contain a given file |
| `track` | `TrackCommand` | Manage file movement tracking |
| `changelog` | `ChangelogCommand` | Cross-workspace change reports since a date |
| `staging` | `StagingCommand` | Staging area management: list, classify, promote, route |
| `migrate-repos` | `MigrateReposCommand` | Repository migration utilities |
| `export-skills` | `ExportSkillsCommand` | Export generated skills to various formats |
| `upcoming` | `UpcomingCommand` | View upcoming features and roadmap items |
| `research` | `ResearchCommand` | Multi-pass deep research reports for external AI tools |
| `report` | `ReportCommand` | Business executive reports (CEO, board, investor audiences) |
| `credentials` | `CredentialsCommand` | Manage stored API keys and credentials |

**Global options (available to all commands via `@Option(scope = INHERIT)`):**
- `-d`, `--directory` — workspace root directory
- `-V`, `--version` — print version
- `-h`, `--help` — print help

---

*Report generated by Claude Code static analysis of the Synthesis codebase on 2026-02-18.*
*This report reflects the state of commit `3479ef9` (test: expand suite from 1,054 to 2,291 tests across 8 waves) on branch `test/expand-suite-waves-1-8`.*
