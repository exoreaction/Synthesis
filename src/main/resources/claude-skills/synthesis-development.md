# Synthesis Development Guide

## Context

Synthesis is an open-source (MIT) Java 17+ CLI tool and MCP server for knowledge infrastructure.
It indexes code, docs, videos, PDFs, and media files across workspaces, providing sub-second
search, relationship tracking, cross-repo dependency graphs, and AI-powered Q&A.

Use this skill as the primary reference for developing Synthesis features, understanding
the codebase architecture, and following established patterns.

## Project Overview

- **Repository:** https://github.com/exoreaction/Synthesis
- **Local Path:** `/src/exoreaction/Synthesis`
- **Language:** Java 17+
- **Build:** Maven (`mvn clean package -DskipTests`)
- **Version:** 1.9.9-SNAPSHOT (as of Feb 19, 2026)
- **Tests:** 2540+ (JUnit 5)
- **License:** MIT

## Environment Setup (Critical for Agents and Subprocesses)

When synthesis is invoked from a spawned agent, Bash subshell, or CI environment, the PATH may
not include the synthesis binary even though it is installed. Always set PATH explicitly.

```bash
# Add to start of any agent prompt or script that uses synthesis:
export PATH="$HOME/bin:/home/totto/bin:$PATH"

# Verify availability:
which synthesis && synthesis --version 2>/dev/null | head -1
```

### Workspace Flag is Mandatory

Without `-d`, synthesis defaults to `~/Documents` (business docs) — not the source workspace.
This caused 10/10 search failures in the Feb 19 benchmark before the fix was discovered.

```bash
# Source code tasks — ALWAYS specify workspace:
synthesis search -d /src/exoreaction "SearchCommand" 2>/dev/null
synthesis search -d /src/exoreaction "isAnchorDoc" 2>/dev/null

# Business docs — default is fine:
synthesis search "pipeline status" 2>/dev/null

# Everything — use --all:
synthesis search --all "anchor document" 2>/dev/null
```

### Confirming a Search Will Work

Before relying on synthesis search in a script or agent:
```bash
export PATH="$HOME/bin:/home/totto/bin:$PATH"
synthesis search -d /src/exoreaction "test" 2>/dev/null | head -3
# Should show "X results for: test" — if not, check PATH and workspace
```

---

## Architecture

### Package Structure

```
io.exoreaction.synthesis
  |-- SynthesisApp.java              # Main entry point, picocli @Command root
  |
  |-- cli/                            # CLI commands (picocli subcommands)
  |   |-- InitCommand                 # synthesis init
  |   |-- ScanCommand                 # synthesis scan
  |   |-- SearchCommand               # synthesis search (+ --all cross-workspace)
  |   |-- AskCommand                  # synthesis ask (+ --interactive)
  |   |-- AnalyzeCommand              # synthesis analyze
  |   |-- RelateCommand               # synthesis relate
  |   |-- InsightsCommand             # synthesis insights
  |   |-- GraphCommand                # synthesis graph
  |   |-- CrossRepoDepsCommand        # synthesis cross-repo-deps
  |   |-- WatchCommand                # synthesis watch (real-time monitoring)
  |   |-- DiffCommand                 # synthesis diff
  |   |-- ChangedCommand              # synthesis changed --since
  |   |-- MaintainCommand             # synthesis maintain
  |   |-- ExportCommand               # synthesis export
  |   |-- StatusCommand               # synthesis status (enhanced: types, metrics, watch)
  |   |-- OrgCommand                  # synthesis org scan|list|classify
  |   |-- LearnCommand                # synthesis learn (skill generation)
  |   |-- PerspectivesCommand         # synthesis perspectives
  |   |-- ExtractSlidesCommand        # synthesis extract-slides
  |   |-- TelemetryCommand            # synthesis telemetry
  |   |-- UpdateCommand               # synthesis update
  |   |-- EnrichCommand               # synthesis enrich (local media enrichment)
  |   |-- ExplainCommand              # synthesis explain
  |   |-- ArchitectureCommand         # synthesis architecture
  |   |-- MetricsCommand              # synthesis metrics (view MCP metrics)
  |   |-- ListWorkspacesCommand       # synthesis list (workspace discovery + filtering)
  |   |-- WhichCommand                # synthesis which (cross-workspace file finder)
  |   +-- InteractiveConfirmation     # Y/N prompts
  |
  |-- config/                          # Configuration
  |   |-- SynthesisConfig             # Root YAML config (workspace, search, ai, scan)
  |   +-- ConfigLoader                # SnakeYAML loader with validation
  |
  |-- workspace/                       # Workspace type system
  |   |-- WorkspaceType               # Enum: SOURCE_CODE, DOCUMENTS, MIXED
  |   +-- WorkspaceMetadata           # Category, language, company, tags
  |
  |-- core/                            # Core scanning/indexing
  |   |-- DirectoryScanner            # File system walker
  |   |-- FileMetadata                # Per-file metadata
  |   |-- ScanResult                  # Scan output
  |   |-- ScanState                   # Persisted scan state
  |   |-- RepositoryManager           # Multi-repo tracking
  |   +-- WorkspaceManager            # Workspace validation and paths
  |
  |-- index/                           # Apache Lucene index
  |   |-- SearchIndex                 # Lucene read/write wrapper
  |   |-- FileIndexer                 # Document -> Lucene doc conversion
  |   |-- SearchResult                # Query result record
  |   +-- DocumentFields              # Lucene field name constants
  |
  |-- search/                          # Cross-workspace search
  |   +-- MultiWorkspaceSearch        # Parallel search across workspaces
  |
  |-- analyzer/                        # File type analyzers
  |   |-- AnalyzerRegistry            # Maps file extensions -> analyzers
  |   |-- CodeAnalyzer                # Source code (Java, JS, Python, etc.)
  |   |-- MarkdownAnalyzer            # Markdown documents
  |   |-- PdfAnalyzer                 # PDF extraction (Apache PDFBox)
  |   |-- ImageAnalyzer               # Image metadata
  |   |-- VideoAnalyzer               # Video metadata (ffprobe)
  |   |-- YamlAnalyzer                # YAML/config files
  |   +-- GenericAnalyzer             # Fallback for unknown types
  |
  |-- graph/                           # Dependency visualization
  |   |-- GraphBuilder                # Builds relationship graphs
  |   +-- GraphRenderer               # Renders Mermaid, DOT, PNG, SVG
  |
  |-- ai/                             # AI integration
  |   |-- ClaudeClient                # Anthropic API wrapper
  |   |-- PromptTemplates             # Prompt construction
  |   |-- DirectedSynthesisEngine     # Multi-perspective analysis
  |   |-- CodeExplainer               # AI code explanation
  |   |-- ReadmeGenerator             # AI README generation
  |   +-- EmbeddingService            # (optional) embedding support
  |
  |-- changelog/                       # Snapshot-based change tracking
  |   |-- SnapshotManager             # Take/compare workspace snapshots; getChangesForWorkspace()
  |   |-- ChangeEvent                 # Record: path, changeType (ADDED/MODIFIED/DELETED/MOVED), significance
  |   |-- ChangeReportGenerator       # Human-readable cross-workspace change reports
  |   |-- ActivityLogUpdater          # Auto-appends draft ACTIVITY-LOG.md entries from ChangeEvents
  |   |-- WorkspaceSnapshot           # Snapshot metadata record
  |   |-- ChangeSignificance          # Enum: NOISE, NORMAL, NOTABLE, CRITICAL
  |   +-- SignificanceClassifier      # Classifies events by file type and path patterns
  |
  |-- metrics/                         # Operational metrics
  |   |-- MetricsDatabase             # SQLite + Flyway storage
  |   |-- MetricsCollector            # Async collection service
  |   +-- MetricsEvent                # Event record with builder
  |
  |-- mcp/                            # MCP server
  |   |-- SynthesisMCPServer          # JSON-RPC MCP server (stdio)
  |   |-- SynthesisToolHandler        # Tool implementations (search, relate, graph, stats)
  |   +-- JsonRpcMessage              # JSON-RPC message types
  |
  |-- enrichment/                      # Local media enrichment
  |   |-- CompanionFileGenerator      # Creates .companion files
  |   |-- EnrichmentLevel             # BASIC, LOCAL, AI
  |   +-- EnrichmentResult            # Enrichment output
  |
  |-- skills/                          # Claude Code skill generation
  |   |-- SkillGenerator              # Generates skills from workspace
  |   |-- SkillInstaller              # Installs to ~/.claude/skills/
  |   +-- SkillTemplate               # Skill file templates
  |
  |-- org/                             # Organization discovery
  |   |-- OrganizationRegistry        # Manages discovered orgs
  |   |-- OrganizationScanner         # Auto-discovers companies
  |   |-- DownloadsClassifier         # Classifies files by org
  |   +-- Organization, Client, Product, ClientStatus, OrganizationType
  |
  |-- telemetry/                       # Pilot telemetry
  |   |-- TelemetryService            # Async event reporting
  |   |-- ClientUUID                  # Installation identity
  |   |-- ApprovalService             # Pilot approval check
  |   +-- TelemetryConfig             # Configuration
  |
  |-- update/                          # Self-update system
  |   |-- UpdateManager               # Orchestrates updates
  |   |-- UpdateChecker               # Background version checks
  |   |-- VersionManifest             # Remote version info
  |   +-- InstallationFingerprint     # Installation health
  |
  |-- lsp/                            # Language Server Protocol
  |   |-- SynthesisLanguageServer     # LSP server
  |   |-- SynthesisTextDocumentService
  |   +-- SynthesisWorkspaceService
  |
  |-- git/                            # Git integration
  |   +-- GitIntegration              # Git operations
  |
  |-- insights/                        # Deep analysis
  |   +-- InsightsEngine              # Codebase insights
  |
  |-- architecture/                    # Architecture monitoring
  |   |-- ArchitectureMonitor         # Drift detection
  |   +-- ArchitectureAlert           # Alert types
  |
  +-- util/                            # Utilities
      |-- AnsiOutput                  # Terminal colors and formatting
      |-- FileUtils                   # File operations
      |-- ProgressReporter            # Progress bars
      |-- Version                     # Version utility
      +-- BundledBinaryManager        # Native binary management
```

### Key Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Picocli | 4.7.7 | CLI framework (annotation-based) |
| Apache Lucene | 10.1.0 | Full-text search index |
| Anthropic Java SDK | 2.14.0 | Claude AI integration |
| SnakeYAML | 2.2 | YAML configuration loading |
| Apache PDFBox | 3.0.4 | PDF text extraction |
| Flyway | (bundled) | Database schema migrations |
| SQLite JDBC | (bundled) | Metrics database |
| Eclipse LSP4J | 0.23.1 | Language Server Protocol |
| Jackson | (bundled) | JSON processing (MCP) |
| JUnit 5 | 5.11.4 | Testing framework |

### Edition System

Synthesis supports multiple editions controlled by `SYNTHESIS_EDITION` environment variable:
- **pro** (default): Full features including AI, telemetry, updates
- **core**: Air-gapped, no AI, no telemetry, no cloud
- **enterprise**: Air-gapped with daemon support
- **ultimate**: Full features with daemon support

In air-gapped mode (`core`, `enterprise`), AI commands (`ask`, `perspectives`) are removed
from the command registry, and telemetry/update checks are skipped.

## Development Workflow

### Build

```bash
cd /src/exoreaction/Synthesis

# Full build
mvn clean package

# Skip tests (faster)
mvn clean package -DskipTests

# Run specific test class
mvn test -Dtest="AskCommandTest"

# Run all tests
mvn test
```

### Run

```bash
# Via installed binary
export PATH="$HOME/.synthesis/bin:$PATH"
synthesis --version
synthesis status

# Via Maven
cd /src/exoreaction/Synthesis
mvn exec:java -Dexec.mainClass="io.exoreaction.synthesis.SynthesisApp" -Dexec.args="status"

# Via JAR
java -jar target/synthesis-1.2.1-SNAPSHOT-jar-with-dependencies.jar status
```

### Adding a New CLI Command

1. Create the command class in `cli/` package:

```java
@Command(
    name = "mycommand",
    description = "Description of what it does",
    mixinStandardHelpOptions = true
)
public class MyCommand implements Callable<Integer> {

    @ParentCommand
    private SynthesisApp parent;

    @Parameters(index = "0", description = "Required parameter")
    private String param;

    @Option(names = {"-v", "--verbose"}, description = "Verbose output")
    private boolean verbose;

    @Override
    public Integer call() {
        Path workspaceRoot = parent.getWorkspaceRoot();
        // ... implementation ...
        return 0;  // exit code
    }
}
```

2. Register in `SynthesisApp.java`:
```java
@Command(subcommands = {
    // ...existing commands...
    MyCommand.class
})
```

3. Add tests in `test/java/io/exoreaction/synthesis/cli/MyCommandTest.java`

### Adding a New Flyway Migration

See `synthesis-database-migrations.md` for detailed instructions.

Quick reference:
1. Check latest version: `ls src/main/resources/db/migration/`
2. Create `V{N}__{description}.sql`
3. Write SQLite-compatible DDL
4. Update Java code to use new columns
5. Test: `mvn test`

### Adding a New MCP Tool

1. Add the tool definition in `SynthesisMCPServer.listTools()`:
```java
tools.add(createTool("synthesis_mytool", "Description",
    Map.of("param1", "string description")));
```

2. Add the handler method in `SynthesisToolHandler`:
```java
public ObjectNode handleMyTool(JsonNode params) {
    long start = System.nanoTime();
    // ... implementation ...
    long elapsed = (System.nanoTime() - start) / 1_000_000;
    metrics.recordMcpInvocation("mytool", workspacePath, elapsed, count, true, null);
    return result;
}
```

3. Add the routing in `SynthesisMCPServer.handleToolCall()`.

### Configuration (config.yaml)

Configuration is loaded by `ConfigLoader` using SnakeYAML. Key constraint:
- All config classes must use plain Java classes with no-arg constructors and setters
  (SnakeYAML requirement -- cannot use Java records for config)

Config sections:
- `workspace` - Name, type, description, metadata (category, language, company)
- `scan` - Include/exclude patterns, hash computation, max file size
- `search` - Max results, preview length, content preview bytes
- `ai` - Enabled, model, vision config

## Commands Reference (Updated Feb 19, 2026)

### Core Commands
| Command | Description |
|---------|-------------|
| `synthesis init [dir]` | Initialize a workspace |
| `synthesis scan` | Scan and index files |
| `synthesis search <query>` | Full-text search (Lucene) |
| `synthesis search --all <query>` | Search across all workspaces |
| `synthesis ask <question>` | AI-powered Q&A |
| `synthesis ask --interactive` | Multi-turn conversation mode |
| `synthesis relate <file>` | Bidirectional relationships |
| `synthesis graph` | Architecture visualization |

### Discovery Commands
| Command | Description |
|---------|-------------|
| `synthesis list` | List all workspaces |
| `synthesis list --type source-code` | Filter by workspace type |
| `synthesis list --language java` | Filter by language |
| `synthesis list --company X` | Filter by company |
| `synthesis which <file>` | Find file across workspaces |
| `synthesis which --pattern "*.sql"` | Glob pattern search |

### Analysis Commands
| Command | Description |
|---------|-------------|
| `synthesis analyze` | Smart project analysis |
| `synthesis insights` | Deep codebase metrics |
| `synthesis perspectives <q>` | Multi-perspective analysis |
| `synthesis cross-repo-deps` | Cross-repo dependency mapping |
| `synthesis architecture` | Architecture monitoring |

### Operations Commands
| Command | Description |
|---------|-------------|
| `synthesis status` | Workspace health (types, metrics, watch daemon) |
| `synthesis metrics` | MCP performance metrics |
| `synthesis metrics --period 30` | Metrics for last 30 days |
| `synthesis watch` | Real-time file monitoring |
| `synthesis maintain` | Incremental index update |
| `synthesis maintain --update-activity-log` | Auto-append today's draft entry to ACTIVITY-LOG.md |
| `synthesis diff <ref>` | Git-integrated diff |
| `synthesis changed --since <d>` | Changed files since date |

### AI & Enrichment Commands
| Command | Description |
|---------|-------------|
| `synthesis enrich [file]` | Local media enrichment (Whisper, Tesseract) |
| `synthesis explain <file>` | AI code explanation |
| `synthesis learn` | Generate Claude Code skills |
| `synthesis learn --install` | Install skills to ~/.claude/skills/ |
| `synthesis extract-slides <pdf>` | Extract presentation slides |

### Admin Commands
| Command | Description |
|---------|-------------|
| `synthesis export` | Export index (JSON, Markdown, AI docs) |
| `synthesis org scan` | Auto-discover organizations |
| `synthesis org list` | Show companies/clients/products |
| `synthesis telemetry` | View pilot status |
| `synthesis update` | Self-update |

## Testing Patterns

### Unit Tests

```java
@Test
void testMyFeature() {
    // Arrange
    Path tempDir = Files.createTempDirectory("synthesis-test");

    // Act
    MyCommand cmd = new MyCommand();
    int result = cmd.call();

    // Assert
    assertEquals(0, result);
}
```

### Integration Tests

For tests that need a full workspace:
```java
@BeforeEach
void setUp() throws Exception {
    testDir = Files.createTempDirectory("synthesis-integration-test");
    // Create .synthesis directory and config
    Files.createDirectory(testDir.resolve(".synthesis"));
    // Write test config.yaml
}
```

### Run Tests

```bash
# All tests
mvn test

# Specific test class
mvn test -Dtest="AskCommandTest"

# Specific test method
mvn test -Dtest="AskCommandTest#testInteractiveMode"

# Tests matching pattern
mvn test -Dtest="*Workspace*"
```

## Workspace Resolution

`SynthesisApp.getWorkspaceRoot()` resolves workspaces in this order:
1. `-d`/`--directory` flag (explicit)
2. `SYNTHESIS_WORKSPACE` environment variable
3. `~/.synthesis/workspace` file
4. Current directory (fallback)

## Key File Locations

| Path | Purpose |
|------|---------|
| `~/.synthesis/` | Global config directory |
| `~/.synthesis/metrics.db` | Metrics database (SQLite) |
| `~/.synthesis/bin/synthesis` | Launcher script |
| `~/.synthesis/workspace` | Default workspace path |
| `<workspace>/.synthesis/` | Per-workspace config |
| `<workspace>/.synthesis/config.yaml` | Workspace configuration |
| `<workspace>/.synthesis/index/` | Lucene search index |
| `<workspace>/.synthesis/scan-state.json` | Last scan metadata |

## Related Skills

- `synthesis-database-migrations.md` - Flyway migration patterns
- `synthesis-workspace-management.md` - Workspace types and cross-workspace operations
- `synthesis-interactive-cli.md` - Interactive conversation mode
- `synthesis-metrics-tracking.md` - MCP metrics collection and display
- `synthesis-graph-architecture.yaml` - Graph command usage
- `synthesis-search-workspace.yaml` - Search command usage
- `synthesis-relate-dependencies.yaml` - Relate command usage
- `synthesis-product-context.yaml` - Product context and business value
- `synthesis-release-manager.yaml` - Release management workflow
