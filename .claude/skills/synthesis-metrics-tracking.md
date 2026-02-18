# Synthesis Metrics Tracking

## Context

Synthesis collects privacy-safe operational metrics about MCP tool invocations, search
performance, and AI feature usage. Metrics are stored in a local SQLite database managed
by Flyway migrations and are queryable via CLI or programmatically.

Use this skill when you need to:
- Record new types of metrics events
- Query and display metrics data
- Add new metrics reports or dashboards
- Understand the privacy-safe metric design

## Key Patterns

- **Privacy-first**: No file content, user data, or credentials stored; only operational patterns
- **Async collection**: `MetricsCollector` uses a single daemon thread; never blocks command execution
- **Centralized storage**: `~/.synthesis/metrics.db` (shared across all workspaces)
- **90-day retention**: Automatic cleanup on database initialization
- **Builder pattern**: `MetricsEvent.builder()` for fluent event construction
- **Graceful degradation**: If metrics fail, commands still work normally
- **Toggle**: `SYNTHESIS_METRICS_ENABLED=false` environment variable disables collection

## Event Types

| Event Type | Description | Key Fields |
|------------|-------------|------------|
| `mcp_tool_invocation` | Any MCP tool call | mcpTool, mcpWorkspace, executionTimeMs, resultCount, success |
| `search` | Search query execution | mcpWorkspace, executionTimeMs, resultCount, searchPattern |
| `ai_feature` | AI feature usage (ask, explain, enrich) | aiFeature, mcpWorkspace, aiTokensUsed, aiRetry |

## Code Examples

### Recording Metrics

```java
// Create a collector (typically once at application startup)
MetricsCollector collector = MetricsCollector.create();

// Record an MCP tool invocation
collector.recordMcpInvocation(
    "search",                // tool name
    workspacePath,           // workspace path
    123,                     // execution time (ms)
    42,                      // result count
    true,                    // success
    null                     // error message (null if success)
);

// Record a search with pattern metadata
collector.recordSearch(
    workspacePath,
    95,                      // execution time (ms)
    15,                      // result count
    "terms:3 operators:AND", // pattern metadata (NOT the actual query)
    true
);

// Record AI feature usage
collector.recordAiFeature(
    "ask",                   // feature name
    workspacePath,
    2500,                    // execution time (ms)
    450,                     // tokens used
    true,                    // success
    false                    // retry
);

// Shutdown (in app shutdown hook)
collector.shutdown();
```

### MetricsEvent Builder

```java
MetricsEvent event = MetricsEvent.builder()
    .eventType("mcp_tool_invocation")
    .mcpTool("search")
    .mcpWorkspace("/src/exoreaction")
    .executionTimeMs(150)
    .resultCount(25)
    .success(true)
    .searchPattern("terms:2 type:CODE")
    .build();
```

### Querying Metrics

```java
try (MetricsDatabase db = new MetricsDatabase(MetricsDatabase.getDefaultPath())) {
    // Query events for last 7 days
    List<MetricsEvent> events = db.queryEvents(7);

    // Get tool statistics (aggregated)
    Map<String, ToolStats> toolStats = db.getToolStats(7);
    for (var entry : toolStats.entrySet()) {
        ToolStats stats = entry.getValue();
        System.out.printf("%s: %d calls, avg %.2fms, %.1f%% success%n",
            stats.toolName(),
            stats.invocationCount(),
            stats.avgExecutionTimeMs(),
            stats.successRate());
    }

    // Filter by workspace and time
    long since = Instant.now().minusSeconds(24 * 60 * 60).getEpochSecond();
    Map<String, ToolStats> wsStats = db.getToolStats("/src/exoreaction", since);

    // Get database info
    int totalRecords = db.getRecordCount();
    long dbSize = db.getDatabaseSize();
}
```

### Metrics in Status Command

```java
// From StatusCommand.showMetricsSummary()
private void showMetricsSummary(MetricsDatabase db, Path workspaceRoot) {
    long since = Instant.now().minusSeconds(24 * 60 * 60).getEpochSecond();
    String workspacePath = workspaceRoot.toAbsolutePath().normalize().toString();

    var stats = db.getToolStats(workspacePath, since);

    if (!stats.isEmpty()) {
        System.out.println("  " + AnsiOutput.bold("MCP Activity (Last 24h):"));
        for (var entry : stats.entrySet()) {
            var toolStats = entry.getValue();
            System.out.printf("    %-15s %d calls (avg %.2fs)%n",
                entry.getKey() + ":",
                toolStats.invocationCount(),
                toolStats.avgExecutionTimeMs() / 1000.0);
        }
    }
}
```

## Common Tasks

### Add a New Metric Type

1. Decide the event type name (e.g., `"workspace_scan"`).

2. Add a convenience method to `MetricsCollector`:
   ```java
   public void recordWorkspaceScan(String workspace, long executionTimeMs,
                                    int filesScanned, boolean success) {
       if (!enabled) return;
       MetricsEvent event = MetricsEvent.builder()
           .eventType("workspace_scan")
           .mcpWorkspace(workspace)
           .executionTimeMs(executionTimeMs)
           .resultCount(filesScanned)
           .success(success)
           .build();
       recordEvent(event);
   }
   ```

3. If the existing fields are insufficient, add a new column via Flyway migration
   (see `synthesis-database-migrations.md`).

4. Call the method from the appropriate command or handler.

### Add a New Metrics Report

1. Add options to `MetricsCommand` or create a new subcommand:
   ```java
   @Option(names = {"--top-searches"}, description = "Show top search patterns")
   private boolean topSearches;
   ```

2. Query the database for the relevant data:
   ```java
   if (topSearches) {
       // Custom query against MetricsDatabase
       List<MetricsEvent> searchEvents = events.stream()
           .filter(e -> "search".equals(e.mcpTool()))
           .toList();
       // ... aggregate and display
   }
   ```

### View Metrics via CLI

```bash
# Default: last 7 days
synthesis metrics

# Last 30 days
synthesis metrics --period 30

# All time
synthesis metrics --period 0

# Filter by workspace
synthesis metrics --workspace ~/Documents

# JSON output (for export/scripting)
synthesis metrics --format json
```

### Integrate Metrics into MCP Tool Handler

The `SynthesisToolHandler` creates a `MetricsCollector` in its constructor:

```java
public SynthesisToolHandler(ObjectMapper mapper, Path defaultWorkspace, List<Path> allWorkspaces) {
    // ...
    this.metrics = MetricsCollector.create();
}
```

Then records metrics after each tool invocation:

```java
long startTime = System.nanoTime();
// ... execute tool ...
long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

metrics.recordMcpInvocation("search", workspacePath, elapsedMs, results.size(), true, null);
```

## Privacy-Safe Design

The metrics system is designed to be privacy-safe by construction:

| Stored | NOT Stored |
|--------|------------|
| Tool name (e.g., "search") | Actual search queries |
| Execution time (ms) | File contents |
| Result count | File names or paths (only workspace root) |
| Success/failure | User identity |
| AI token count | AI prompts or responses |
| Pattern metadata (e.g., "terms:3") | Actual search terms |
| Workspace root path | Credentials or API keys |

The `searchPattern` field stores structural metadata about queries (e.g., "terms:3 operators:AND")
but never the actual search terms themselves.

## Architecture

```
MetricsCollector (service)
  |-- Async recording via daemon thread (ExecutorService)
  |-- MetricsDatabase (storage)
  |     |-- SQLite via JDBC
  |     |-- Flyway migrations (schema evolution)
  |     |-- 90-day retention policy
  |     +-- Thread-safe (synchronized methods)
  |-- MetricsEvent (record)
  |     |-- Builder pattern
  |     +-- fromResultSet() for query deserialization
  |
  +-- Consumers:
      |-- SynthesisToolHandler  (MCP server - primary data source)
      |-- MetricsCommand        (CLI - view metrics)
      |-- StatusCommand         (CLI - 24h summary in status)
```

## Related Files

- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/metrics/MetricsCollector.java` - Async collection service
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/metrics/MetricsDatabase.java` - SQLite storage with Flyway
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/metrics/MetricsEvent.java` - Event record with builder
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/cli/MetricsCommand.java` - CLI metrics viewer
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/cli/StatusCommand.java` - 24h metrics summary
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/mcp/SynthesisToolHandler.java` - MCP metrics recording
- `/src/exoreaction/Synthesis/src/main/resources/db/migration/` - Flyway SQL migrations

## Testing

```bash
# Run metrics-related tests
cd /src/exoreaction/Synthesis
mvn test -Dtest="MetricsDatabase*,MetricsCollector*,MetricsCommand*"

# Verify metrics database exists
ls -la ~/.synthesis/metrics.db

# View metrics from CLI
synthesis metrics --period 7
synthesis metrics --format json

# View 24h summary in status
synthesis status

# Test with metrics disabled
SYNTHESIS_METRICS_ENABLED=false synthesis search "test"
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SYNTHESIS_METRICS_ENABLED` | `true` | Enable/disable metrics collection |

## See Also

- `synthesis-database-migrations.md` - How the metrics schema evolves
- `synthesis-workspace-management.md` - Workspace paths used in metrics
- `synthesis-interactive-cli.md` - Interactive sessions that generate metrics
- `synthesis-development.md` - General development patterns
