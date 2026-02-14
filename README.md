# Synthesis

**AI operations partner for knowledge infrastructure.**

Synthesis is a CLI tool that scans, indexes, and searches workspace file systems, providing rapid discovery of documents, code, and knowledge artifacts. It creates a local Lucene search index enriched with file analysis (headings, keywords, code structure, PDF content), enabling instant search across your entire workspace.

## Quick Start

**Linux / macOS:**
```bash
# Install (one command)
curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash
```

**Windows (PowerShell):**
```powershell
# Install (one command, requires RemoteSigned execution policy)
Set-ExecutionPolicy RemoteSigned -Scope CurrentUser   # one-time setup
iex (iwr -useb https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.ps1).Content
```

**Then use Synthesis:**
```bash
# Initialize a workspace
synthesis init ~/projects/my-project --name "My Project"

# Scan and index all files
synthesis -d ~/projects/my-project scan

# Search for content
synthesis -d ~/projects/my-project search "authentication pipeline"

# Check workspace health
synthesis -d ~/projects/my-project status
```

## Installation

**Requirements:** Java 17 or later.

### Linux / macOS

#### One-Command Install

```bash
curl -fsSL https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.sh | bash
```

This will:
- Check prerequisites (Java 17+, git, curl)
- Download the latest Synthesis JAR (from GitHub releases or Cantara Maven repository)
- Install to `~/.synthesis/` with launcher script, updater, and symlink management
- Add `~/.synthesis/bin` to your PATH

After installation, open a new terminal and run `synthesis --version` to verify.

#### Install from Source

If you have the source code locally:

```bash
git clone https://github.com/exoreaction/Synthesis.git
cd Synthesis
mvn clean package -DskipTests
./bin/install.sh --source .
```

#### Updating

```bash
synthesis-update                   # Update to latest version
synthesis-update --check           # Check for updates without installing
synthesis-update --force           # Force re-download
synthesis-update --version '1.0.*' # Update to a specific version pattern
synthesis-update --rollback        # Rollback to previous version
```

#### Uninstalling

```bash
~/.synthesis/bin/uninstall.sh
# Or from the source directory:
./bin/uninstall.sh
```

This removes `~/.synthesis/`, cleans PATH entries from your shell config, and optionally removes Claude Code skills (`--remove-skills`). Workspace `.synthesis/` directories inside your projects are preserved.

### Windows

For detailed Windows instructions, see [INSTALL-WINDOWS.md](docs/INSTALL-WINDOWS.md).

#### One-Command Install (PowerShell)

```powershell
# First time: allow PowerShell scripts (run once)
Set-ExecutionPolicy RemoteSigned -Scope CurrentUser

# Install Synthesis
iex (iwr -useb https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.ps1).Content
```

Or download and run:

```powershell
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.ps1" -OutFile install.ps1
.\install.ps1
```

This will:
- Check prerequisites (Java 17+, PowerShell 5.1+)
- Download the latest Synthesis JAR (same sources as Linux/macOS)
- Install to `%USERPROFILE%\.synthesis\` with launcher (`synthesis.bat`), updater, and hard link management
- Add `%USERPROFILE%\.synthesis\bin` to User PATH
- Configure PowerShell profile with aliases

#### Install from Source (Windows)

```powershell
git clone https://github.com/exoreaction/Synthesis.git
cd Synthesis
mvn clean package -DskipTests
.\bin\install.ps1 -Source .
```

#### Updating (Windows)

```powershell
synthesis-update                      # Update to latest version
synthesis-update -Check               # Check for updates without installing
synthesis-update -Force               # Force re-download
synthesis-update -Version "1.0.*"     # Update to a specific version pattern
synthesis-update -Rollback            # Rollback to previous version
```

#### Uninstalling (Windows)

```powershell
& "$env:USERPROFILE\.synthesis\bin\uninstall.ps1"
# Or from the source directory:
.\bin\uninstall.ps1
```

This removes `%USERPROFILE%\.synthesis\`, cleans User PATH entries, cleans PowerShell profile, and optionally removes Claude Code skills (`-RemoveSkills`). Workspace `.synthesis\` directories inside your projects are preserved.

### Manual Install (Any Platform)

```bash
# Build the JAR
git clone https://github.com/exoreaction/Synthesis.git
cd Synthesis
mvn clean package -DskipTests

# Run directly
java -jar target/synthesis-1.0.0-SNAPSHOT.jar --help
```

### Environment Variables

| Variable | Description |
|----------|-------------|
| `SYNTHESIS_HOME` | Installation directory (default: `~/.synthesis` or `%USERPROFILE%\.synthesis`) |
| `SYNTHESIS_NO_UPDATE_CHECK` | Set to `1` to disable daily auto-update checks |
| `SYNTHESIS_JAVA_OPTS` | Extra JVM options passed to Java (e.g., `-Xmx2g`) |

The launcher performs a daily background update check and will notify you when a new version is available. Disable with `SYNTHESIS_NO_UPDATE_CHECK=1`.

### Video Support (Batteries Included)

Synthesis bundles ffprobe for all major platforms (Linux x64, macOS, Windows x64), providing full video metadata support out of the box. No external installation needed.

On first use, the appropriate ffprobe binary is automatically extracted from the JAR to `~/.synthesis/bin/` and cached for subsequent runs. Supported video formats include MP4, MOV, AVI, MKV, WebM, FLV, WMV, and more.

The `synthesis status` command shows ffprobe status:
```
External Tools:
  ffprobe: Bundled (FFmpeg 7.0.2)
```

If you prefer to use your own system-installed ffprobe, it will be detected as a fallback. See [docs/FFMPEG-BINARIES.md](docs/FFMPEG-BINARIES.md) for details on the bundled binary approach.

**Note:** The bundled binaries increase JAR size to approximately 136 MB (compressed). Uncompressed binary sizes: Linux ~76 MB, macOS ~76 MB, Windows ~95 MB.

## Commands

### Core Commands

| Command | Description |
|---------|-------------|
| `init [dir]` | Initialize a new Synthesis workspace |
| `scan` | Scan workspace, analyze files, and build search index |
| `search <query>` | Full-text search across the workspace |
| `maintain` | Detect changes and update index incrementally |
| `export` | Export index as JSON, Markdown, architecture doc, or onboarding guide |
| `status` | Show workspace health and index statistics |

### AI-Powered Commands (Tier 1)

| Command | Description |
|---------|-------------|
| `ask <question>` | AI-powered Q&A about your workspace -- searches relevant files and uses Claude to answer with file citations |
| `analyze` | Smart project analysis -- detects patterns, issues, missing docs, test coverage gaps |
| `relate <file>` | Relationship mapping -- shows imports, references, dependencies, optional Mermaid diagram |

### Organizational Intelligence Commands

| Command | Description |
|---------|-------------|
| `org scan` | Auto-discover organizations, clients, products from workspace structure |
| `org list` | Show organizational hierarchy (companies, clients, products) |
| `org classify [dir]` | Classify files in Downloads directory by organization |

### Developer Experience Commands (Tier 2)

| Command | Description |
|---------|-------------|
| `watch` | Watch mode -- monitors file changes and auto-updates index in real-time |
| `diff <ref>` | Git diff integration -- shows changed files between refs, optionally search only changed files |
| `changed --since <d>` | Git history -- files changed since date (supports `7d`, `24h`, `2w`, `YYYY-MM-DD`) |

### Global Options

| Option | Description |
|--------|-------------|
| `-d, --directory` | Workspace root directory (default: current directory) |
| `-h, --help` | Show help for any command |
| `-V, --version` | Print version information |

### Search Filters

| Option | Description |
|--------|-------------|
| `--type <TYPE>` | Filter by file type (CODE, MARKDOWN, YAML, etc.) |
| `--repo <name>` | Filter to a specific repository (multi-repo workspaces) |
| `--company <name>` | Filter to a specific organization/company |
| `--client <name>` | Filter to a specific client |

## How It Works

1. **`init`** creates a `.synthesis/` directory with configuration, Lucene index, and reports
2. **`scan`** walks the directory tree, applies include/exclude patterns, and for each file:
   - Extracts metadata (size, type, language, hash)
   - Analyzes content with specialized analyzers (Markdown, Code, YAML, PDF)
   - Indexes the enriched metadata in a local Lucene search index
3. **`search`** queries the Lucene index with relevance ranking across filename, headings, keywords, and content
4. **`maintain`** compares the current filesystem against saved scan state to apply incremental updates
5. **`export`** dumps the index as structured JSON or navigable Markdown for sharing or AI context

## File Types Supported

| Type | Extensions | Analysis |
|------|-----------|----------|
| Markdown | `.md` | Headings, links, keywords, word count, front matter |
| Code | `.java`, `.py`, `.js`, `.ts`, `.go`, `.rs`, `.rb`, `.kt`, `.sh`, etc. | LOC, imports, declarations, language, frameworks |
| YAML | `.yaml`, `.yml` | Type detection (Docker Compose, GitHub Actions, Kubernetes, Claude skills), key extraction |
| PDF | `.pdf` | Text extraction, page count, title, author |
| Image | `.jpg`, `.png`, `.gif`, `.svg`, `.webp`, etc. | EXIF metadata, dimensions, camera info, GPS, IPTC keywords |
| Video | `.mp4`, `.mov`, `.avi`, `.mkv`, `.webm`, etc. | Duration, resolution (metadata-extractor + ffprobe fallback), companion transcripts |
| Audio | `.mp3`, `.wav`, `.flac`, `.ogg`, `.aac`, etc. | Duration, companion transcripts |
| JSON | `.json` | Content indexing |
| Config | `.toml`, `.ini`, `.properties`, `.xml` | Content indexing |

## Configuration

Configuration lives in `.synthesis/config.yaml` (auto-generated by `init`):

```yaml
workspace:
  name: "my-project"
  type: "general"      # general, plugin-ecosystem, monorepo, multi-project

scan:
  includePatterns:
    - "**/*.md"
    - "**/*.java"
    - "**/*.py"
    - "**/*.js"
    # ... (full list in generated config)
  excludePatterns:
    - "**/node_modules/**"
    - "**/.git/**"
    - "**/target/**"
    - "**/.synthesis/**"
  computeHashes: true
  maxFileSizeBytes: 10485760    # 10 MB

search:
  maxResults: 20
  previewLength: 200

ai:
  enabled: false                # Set to true + ANTHROPIC_API_KEY for AI features
  model: "claude-sonnet-4-5-20250929"
  readmeGeneration: true
  maxTokens: 1024
```

## AI Features (Optional)

Synthesis optionally integrates with Claude to provide:

- **Q&A:** `synthesis ask "How does plugin loading work?"` -- answers questions about your workspace with file citations
- **Project Analysis:** `synthesis analyze` -- deep project analysis with AI-powered insights and recommendations
- **Architecture Docs:** `synthesis export --format architecture-doc` -- generates narrative architecture documentation
- **Onboarding Guide:** `synthesis export --format onboarding-guide` -- generates new developer onboarding guide
- **README Generation:** `synthesis scan --with-readme` generates README.md for directories that lack one
- **Content Summarization:** AI-powered summaries for improved search relevance

All AI features gracefully degrade -- they fall back to rule-based analysis when Claude is unavailable.

To enable:
1. Set `ai.enabled: true` in config
2. Set `ANTHROPIC_API_KEY` environment variable

## Search Syntax

Synthesis uses Apache Lucene query syntax:

```bash
# Simple terms
synthesis search "authentication"

# Multi-word (OR by default)
synthesis search "testing strategy"

# Exact phrases
synthesis search '"NCI Protocol"'

# Boolean
synthesis search "testing AND strategy"

# Wildcards
synthesis search "test*"

# Field-specific
synthesis search "language:Java"

# Type filter
synthesis search "pipeline" --type CODE
synthesis search "deployment" --type YAML
```

## Organizational Intelligence

Synthesis supports multi-company workspaces with auto-discovery of organizational structure.

### How It Works

1. **`org scan`** analyzes your workspace for organizational patterns:
   - Detects companies from directory structure (README.md, clients/, products/, business/)
   - Discovers client relationships with status detection (active, past, opportunity)
   - Finds products and codebase references
   - Saves to `.synthesis/organizations.json`

2. **Organization-scoped search** filters results by company or client:
   ```bash
   synthesis search "authentication" --company eXOReaction
   synthesis search "proposal" --client "SpareBank1"
   synthesis insights --company Quadim
   ```

3. **Downloads classification** analyzes files for organization signals:
   ```bash
   synthesis org classify ~/Downloads
   # Classifies files by filename keywords and content analysis
   ```

### Directory Naming Conventions

The scanner detects client status from directory names:
- `clients/Elprint/` -- Active client
- `clients/Entra-past/` -- Past/completed engagement
- `clients/opportunity-SpareBank1/` -- Pipeline opportunity

### Configuration

Organization data is persisted in `.synthesis/organizations.json` (auto-generated by `org scan`).

## Architecture

```
io.exoreaction.synthesis/
  SynthesisApp.java          # Picocli entry point
  ai/                        # Claude API integration
    ClaudeClient.java        # Anthropic SDK wrapper
    ReadmeGenerator.java     # AI-powered README generation
    PromptTemplates.java     # Centralized prompts
  analyzer/                  # File analysis pipeline
    FileAnalyzer.java        # Analyzer interface
    MarkdownAnalyzer.java    # Markdown-specific analysis
    CodeAnalyzer.java        # Source code analysis
    YamlAnalyzer.java        # YAML type detection
    PdfAnalyzer.java         # PDF text extraction
    GenericAnalyzer.java     # Fallback analyzer
    AnalyzerRegistry.java    # Analyzer dispatch
  cli/                       # CLI commands
    InitCommand.java
    ScanCommand.java
    SearchCommand.java
    AskCommand.java          # AI-powered Q&A
    AnalyzeCommand.java      # Smart project analysis
    RelateCommand.java       # Relationship mapping
    WatchCommand.java        # File change watcher
    DiffCommand.java         # Git diff integration
    ChangedCommand.java      # Git history search
    MaintainCommand.java
    ExportCommand.java       # JSON, MD, architecture-doc, onboarding-guide
    StatusCommand.java
    OrgCommand.java          # Organization management (scan, list, classify)
  config/                    # Configuration
    SynthesisConfig.java     # Config model
    ConfigLoader.java        # YAML loading
  core/                      # Core domain
    DirectoryScanner.java    # File tree walker
    FileMetadata.java        # File metadata record
    ScanResult.java          # Scan result aggregate
    ScanState.java           # Incremental scan state
    WorkspaceManager.java    # Workspace lifecycle
  git/                       # Git integration
    GitIntegration.java      # JGit wrapper
  index/                     # Lucene search index
    SearchIndex.java         # Index wrapper
    FileIndexer.java         # Document builder
    SearchResult.java        # Result record
    DocumentFields.java      # Field constants (incl. ORGANIZATION, CLIENT)
  org/                       # Organizational intelligence
    Organization.java        # Organization entity
    Client.java              # Client entity with status
    Product.java             # Product entity
    ClientStatus.java        # ACTIVE, PAST, OPPORTUNITY, SIGNED
    OrganizationType.java    # COMPANY, FOUNDATION, HOLDING, CONCEPT
    OrganizationRegistry.java # Registry with persistence
    OrganizationScanner.java # Auto-discovery from directory structure
    DownloadsClassifier.java # File classification for Downloads routing
  util/                      # Utilities
    AnsiOutput.java          # Terminal colors
    FfprobeDetector.java     # ffprobe detection & caching
    FileUtils.java           # File classification
    ProgressReporter.java    # Progress bars
```

## Development

```bash
# Compile
mvn compile

# Run tests (282 tests)
mvn test

# Build executable JAR
mvn package

# Run directly
java -jar target/synthesis-1.0.0-SNAPSHOT.jar --help
```

### Adding a New Analyzer

1. Create a class implementing `FileAnalyzer`
2. Implement `canAnalyze(FileMetadata)` and `analyze(FileMetadata)`
3. Register it in `AnalyzerRegistry`
4. Add corresponding `FileType` to `FileUtils` if needed

### Technology Stack

- **Java 17+** with records, switch expressions, text blocks
- **Apache Lucene 10.1.0** for full-text search with ranking
- **Picocli 4.7.7** for CLI framework
- **Apache PDFBox 3.0.4** for PDF processing
- **SnakeYAML 2.2** for configuration
- **Anthropic Java SDK 2.14.0** for AI features (optional)
- **JGit 7.1.0** for Git repository integration
- **JUnit 5** for testing
- **Maven** with shade plugin for uber-JAR

## License

Copyright (c) 2026 eXOReaction AS. All rights reserved.

---

*Built with [Skill-Driven Development (SDD)](https://exoreaction.com) methodology.*
