# Synthesis Workspace Management

## Context

Synthesis organizes indexed content into typed workspaces. Each workspace has a `.synthesis/`
directory containing configuration, indexes, and scan state. The unified workspace system
supports cross-workspace search, type-based filtering, and multi-workspace MCP serving.

Use this skill when you need to:
- Configure workspace types and metadata
- Perform cross-workspace operations
- Add new workspace features
- Understand the multi-workspace architecture

## Key Patterns

- Workspace types: `source-code`, `documents`, `mixed` (defined in `WorkspaceType` enum)
- Configuration lives in `.synthesis/config.yaml` per workspace
- `WorkspaceMetadata` provides category, language, repo count, company, tags
- `MultiWorkspaceSearch` enables parallel search across all discovered workspaces
- `ListWorkspacesCommand` discovers workspaces by scanning known paths
- `WhichCommand` locates files across all workspaces
- Auto-discovery searches: `~/Documents`, `~/Downloads`, `/src`, `~/src`, plus one level deep

## Workspace Types

```java
public enum WorkspaceType {
    SOURCE_CODE("source-code", "Source code repositories"),
    DOCUMENTS("documents", "Document and knowledge workspaces"),
    MIXED("mixed", "Mixed code and document workspaces");
}
```

Type resolution priority:
1. `metadata.category` field (if set and not "mixed")
2. `workspace.type` field (legacy, includes backward-compatible values)
3. Default: `MIXED`

Legacy type values that map to MIXED: `general`, `plugin-ecosystem`, `monorepo`, `multi-project`

## Code Examples

### Config.yaml Structure

```yaml
workspace:
  name: "exoreaction"
  type: "general"                    # Legacy field
  description: "eXOReaction source code"
  metadata:
    category: "source-code"          # Preferred: source-code, documents, mixed
    primaryLanguage: "java"          # For source-code workspaces
    repoCount: 26                    # Number of repositories
    company: "eXOReaction"           # Owning organization
    tags: "open-source,enterprise"   # Additional classification

scan:
  includePatterns:
    - "**/*.md"
    - "**/*.java"
    # ...
  excludePatterns:
    - "**/node_modules/**"
    - "**/.git/**"
    # ...

search:
  maxResults: 20
  previewLength: 200
  contentPreviewBytes: 10240

ai:
  enabled: false
  model: "claude-sonnet-4-5-20250929"
```

### WorkspaceMetadata Class

```java
// Plain Java class (not record) for SnakeYAML compatibility
public class WorkspaceMetadata {
    private String category = "mixed";
    private String primaryLanguage;      // null for document workspaces
    private int repoCount;
    private String description = "";
    private String company;
    private String tags = "";

    public WorkspaceType getWorkspaceType() {
        return WorkspaceType.fromConfigValue(category);
    }
}
```

### Cross-Workspace Search

```java
// Discover all workspaces
List<Path> allWorkspaces = MultiWorkspaceSearch.discoverAllWorkspaces();

// Filter by type
List<Path> sourceWorkspaces = MultiWorkspaceSearch.discoverWorkspacesByType(WorkspaceType.SOURCE_CODE);

// Search across all workspaces in parallel
MultiWorkspaceSearch search = new MultiWorkspaceSearch(allWorkspaces);
MultiSearchResult result = search.search("authentication", "CODE", 20);

// Results grouped by workspace
for (GroupedResults group : result.groups()) {
    System.out.println(group.workspace().name() + ": " + group.results().size() + " results");
}
```

### Finding Files Across Workspaces (which)

```java
MultiWorkspaceSearch search = new MultiWorkspaceSearch(allWorkspaces);
Map<WorkspaceEntry, List<String>> results = search.which("MetricsCollector.java", false);

// Results: workspace -> list of matching relative paths
for (var entry : results.entrySet()) {
    System.out.println(entry.getKey().name() + " contains " + entry.getValue().size() + " matches");
}
```

## Common Tasks

### Initialize a New Workspace

```bash
# Initialize with defaults
synthesis -d /path/to/workspace init

# Then edit .synthesis/config.yaml to set metadata:
#   metadata.category: source-code
#   metadata.primaryLanguage: java
#   metadata.company: MyCompany
```

### List All Workspaces with Filtering

```bash
# List all workspaces
synthesis list

# Filter by type
synthesis list --type source-code
synthesis list --type documents

# Filter by language
synthesis list --language java

# Filter by company
synthesis list --company eXOReaction

# JSON output (for scripting)
synthesis list --format json
```

### Find a File Across Workspaces

```bash
# Find which workspace contains a file
synthesis which MetricsCollector.java

# With verbose output (show all matching paths)
synthesis which MetricsCollector.java --verbose

# Use glob pattern
synthesis which --pattern "*.sql" README

# Filter by workspace type
synthesis which --type source-code MetricsCollector.java

# JSON output
synthesis which --format json MetricsCollector.java
```

### Search Across All Workspaces

```bash
# Search all workspaces
synthesis search --all "authentication"

# Search specific workspace types
synthesis search --all --type source-code "authentication"
```

### Add a New Workspace Type

1. Add the enum value in `WorkspaceType.java`:
   ```java
   INFRASTRUCTURE("infrastructure", "Infrastructure and DevOps configurations")
   ```

2. Update `fromConfigValue()` to handle legacy mappings if needed.

3. Update display logic in:
   - `StatusCommand.java` (type badge)
   - `ListWorkspacesCommand.java` (type badge)
   - `WhichCommand.java` (type label)

4. Update tests.

### Configure Workspace for MCP Server

The MCP server supports multi-workspace mode:

```json
{
  "mcpServers": {
    "synthesis": {
      "command": "java",
      "args": ["-jar", "synthesis.jar", "mcp",
               "--workspaces", "/src/exoreaction,/home/user/Documents"]
    }
  }
}
```

The `SynthesisToolHandler` constructor accepts multiple workspace paths and delegates
to `MultiWorkspaceSearch` for cross-workspace operations.

## Discovery Algorithm

`MultiWorkspaceSearch.discoverAllWorkspaces()` and `ListWorkspacesCommand.discoverWorkspaces()` use this algorithm:

1. Check known paths: `~/Documents`, `~/Downloads`, `/src`, `~/src`, current directory
2. For each path, check if `.synthesis/` directory exists (direct workspace)
3. For each path, list subdirectories one level deep and check for `.synthesis/`
4. Deduplicate by absolute normalized path
5. Sort by path

## Architecture

```
WorkspaceType (enum)           - Type classification
WorkspaceMetadata (POJO)       - Extended metadata per workspace
SynthesisConfig.WorkspaceConfig - Config section in YAML
ConfigLoader                   - Loads and validates YAML configs
MultiWorkspaceSearch           - Cross-workspace search engine
  - WorkspaceEntry (record)    - Workspace identity: path, name, type, language, company
  - GroupedResults (record)    - Per-workspace search results
  - MultiSearchResult (record) - Aggregate results
ListWorkspacesCommand          - CLI: list workspaces with filtering
WhichCommand                   - CLI: find files across workspaces
SearchCommand (--all flag)     - CLI: search across all workspaces
SynthesisToolHandler           - MCP: multi-workspace tool handling
```

## Related Files

- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/workspace/WorkspaceType.java` - Type enum
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/workspace/WorkspaceMetadata.java` - Metadata POJO
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/config/SynthesisConfig.java` - Full config structure
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/config/ConfigLoader.java` - YAML loading
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/search/MultiWorkspaceSearch.java` - Cross-workspace search
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/cli/ListWorkspacesCommand.java` - List command
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/cli/WhichCommand.java` - Which command
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/cli/StatusCommand.java` - Status display with type badges
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/mcp/SynthesisToolHandler.java` - MCP multi-workspace

## Testing

```bash
# Run workspace-related tests
cd /src/exoreaction/Synthesis
mvn test -Dtest="ListWorkspacesCommand*,WhichCommand*,MultiWorkspaceSearch*,ConfigLoader*"

# Verify workspace discovery
synthesis list --format json

# Verify type filtering
synthesis list --type source-code
synthesis list --type documents

# Test which command
synthesis which SynthesisApp.java --verbose
```

## See Also

- `synthesis-database-migrations.md` - Schema management for metrics database
- `synthesis-metrics-tracking.md` - Metrics that use workspace paths
- `synthesis-interactive-cli.md` - Interactive features within workspaces
- `synthesis-development.md` - General development patterns
