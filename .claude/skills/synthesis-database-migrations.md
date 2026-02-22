# Synthesis Database Migrations (Flyway)

## Context

Synthesis uses Flyway for versioned database schema evolution of its SQLite metrics database.
The metrics database stores operational data about MCP tool invocations, search performance,
and AI feature usage. Flyway ensures schema changes are applied consistently across all
installations and are forward-compatible with future updates.

Use this skill when you need to:
- Add new columns or tables to the metrics database
- Understand how schema versioning works in Synthesis
- Debug migration issues
- Create new migration scripts

## Key Patterns

- Flyway migrations live in `src/main/resources/db/migration/`
- Naming convention: `V{number}__{description}.sql` (double underscore between version and description)
- Versions are strictly sequential: V1, V2, V3, etc.
- Flyway runs on database initialization in `MetricsDatabase.initialize()`
- SQLite-specific: Use `ALTER TABLE` for adding columns, `CREATE TABLE IF NOT EXISTS` for new tables
- Baseline version is "0" (allows migrating from pre-Flyway databases)
- `baselineOnMigrate(true)` handles existing databases that predate Flyway

## Code Examples

### Migration File Naming

```
src/main/resources/db/migration/
  V1__initial_schema.sql       # Creates metrics + metadata tables
  V2__add_workspace_tags.sql   # Adds workspace_tag column
  V3__add_session_tracking.sql # (example: next migration)
```

### Sample Migration (V1 - Initial Schema)

```sql
-- Synthesis Metrics Database - Initial Schema
-- Version: 1.0
-- Created: 2026-02-15

CREATE TABLE IF NOT EXISTS metrics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    mcp_tool TEXT,
    mcp_workspace TEXT,
    execution_time_ms INTEGER,
    result_count INTEGER,
    success INTEGER NOT NULL,
    error_message TEXT,
    search_pattern TEXT,
    ai_feature TEXT,
    ai_tokens_used INTEGER,
    ai_retry INTEGER
);

-- Indexes for efficient queries
CREATE INDEX IF NOT EXISTS idx_timestamp ON metrics(timestamp);
CREATE INDEX IF NOT EXISTS idx_mcp_tool ON metrics(mcp_tool);
CREATE INDEX IF NOT EXISTS idx_workspace ON metrics(mcp_workspace);
CREATE INDEX IF NOT EXISTS idx_event_type ON metrics(event_type);

-- Metadata table for storing configuration
CREATE TABLE IF NOT EXISTS metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
```

### Sample Migration (V2 - ALTER TABLE)

```sql
-- Add workspace tags for better categorization
ALTER TABLE metrics ADD COLUMN workspace_tag TEXT;

CREATE INDEX IF NOT EXISTS idx_workspace_tag ON metrics(workspace_tag);

INSERT OR REPLACE INTO metadata (key, value) VALUES ('feature_workspace_tags', 'enabled');
```

### Flyway Initialization in Java

```java
// From MetricsDatabase.java
String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
Flyway flyway = Flyway.configure()
        .dataSource(url, null, null)                 // SQLite: no user/password
        .locations("classpath:db/migration")          // Migration file location
        .baselineOnMigrate(true)                      // Handle pre-Flyway databases
        .baselineVersion("0")                         // Baseline before V1
        .load();

flyway.migrate();
```

## Current Migration State (Feb 2026)

```
V1__initial_schema.sql                              # Core metrics + metadata tables
V2__add_workspace_tags.sql                          # Workspace tag column
V3__file_tracking_and_changelog.sql                 # File tracking + changelog tables
V4__sub_workspaces.sql                              # Sub-workspace support
V5__summary_cache.sql                               # AI summary caching
V6__research_cache.sql                              # Research cache
V7 — INTENTIONALLY RESERVED                         # Migration deleted; version permanently reserved
V8__report_cache.sql                                # Report cache
V9__knowledge_edges.sql                             # Skill/doc→source edge tracking
V10__directory_centroids.sql                        # KG: directory_centroids + file_enrichment_signatures
V11__virtual_membership_and_routing_feedback.sql    # KG: virtual_memberships + routing_feedback
V12__directory_classification.sql                   # KG: adds classification column to directory_centroids
V13__code_knowledge_graph.sql                       # CKG: code_dependencies, module_profiles,
                                                    #      cross_format_links, code_quality_gaps
```

**Next migration will be V14.** Always run `ls src/main/resources/db/migration/` before creating a new one.

### Key Tables by System

| System | Tables |
|--------|--------|
| Metrics | `metrics`, `metadata` (V1-V2) |
| File tracking | `file_movements`, `file_states`, `changelog_snapshots` (V3) |
| Sub-workspaces | `sub_workspaces` (V4) |
| Caches | `summary_cache`, `research_cache`, `report_cache` (V5, V6, V8) |
| Knowledge edges | `knowledge_edges` (V9) |
| Document knowledge graph | `directory_centroids`, `file_enrichment_signatures` (V10); `virtual_memberships`, `routing_feedback` (V11); `classification` column (V12) |
| Code knowledge graph | `code_dependencies`, `module_profiles`, `cross_format_links`, `code_quality_gaps` (V13) |

---

## Common Tasks

### Add a New Column to the Metrics Table

1. Determine the next version number by checking existing migrations:
   ```
   ls src/main/resources/db/migration/
   ```
   If the latest is `V13__code_knowledge_graph.sql`, the next is V14.

2. Create the migration file:
   ```
   src/main/resources/db/migration/V3__add_session_id.sql
   ```

3. Write the SQL (SQLite-compatible):
   ```sql
   -- Add session ID for tracking multi-turn conversations
   ALTER TABLE metrics ADD COLUMN session_id TEXT;
   CREATE INDEX IF NOT EXISTS idx_session_id ON metrics(session_id);
   ```

4. Update the MetricsEvent record to include the new field:
   ```java
   // In MetricsEvent.java - add to the record parameters
   String sessionId
   ```

5. Update MetricsDatabase.recordEvent() INSERT statement to include the new column.

6. Update MetricsDatabase.queryEvents() SELECT statement.

7. Build and test:
   ```bash
   cd /src/exoreaction/Synthesis && mvn test -pl . -Dtest=MetricsDatabase*
   ```

### Add a New Table

1. Create migration file `V{N}__create_new_table.sql`
2. Use `CREATE TABLE IF NOT EXISTS` for safety
3. Add appropriate indexes
4. Create a new Java class for the table's data access

### Debug Migration Issues

1. Check the Flyway schema history table in the database:
   ```sql
   SELECT * FROM flyway_schema_history ORDER BY installed_rank;
   ```

2. If a migration failed midway (SQLite auto-commits DDL), you may need to:
   - Fix the SQL in the migration file
   - Delete the failed entry from `flyway_schema_history`
   - Re-run the application

3. For development, you can use `flyway.repair()` before `flyway.migrate()`.

## SQLite-Specific Considerations

- SQLite does NOT support `DROP COLUMN`, `ALTER COLUMN`, or `RENAME COLUMN` (prior to 3.35.0)
- For column changes, the pattern is: create new table, copy data, drop old, rename
- `ALTER TABLE` only supports `ADD COLUMN` and `RENAME TABLE` reliably
- Always use `IF NOT EXISTS` for CREATE TABLE and CREATE INDEX
- SQLite auto-commits DDL statements (no transactional DDL)
- `INSERT OR REPLACE` is used instead of `UPSERT` for metadata entries

## Related Files

- `/src/exoreaction/Synthesis/src/main/resources/db/migration/V1__initial_schema.sql` - Initial schema
- `/src/exoreaction/Synthesis/src/main/resources/db/migration/V2__add_workspace_tags.sql` - Tags migration
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/metrics/MetricsDatabase.java` - Database initialization and queries
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/metrics/MetricsEvent.java` - Event record with builder
- `/src/exoreaction/Synthesis/src/main/java/io/exoreaction/synthesis/metrics/MetricsCollector.java` - Async collection service
- `/src/exoreaction/Synthesis/pom.xml` - Flyway and SQLite dependencies

## Dependencies (pom.xml)

```xml
<!-- Flyway for database migrations -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
    <version>${flyway.version}</version>
</dependency>

<!-- SQLite JDBC driver -->
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>${sqlite.version}</version>
</dependency>
```

## Testing

1. **Unit test migrations**: The MetricsDatabase constructor runs Flyway automatically.
   Creating a MetricsDatabase with a temp file tests all migrations:
   ```java
   Path tempDb = Files.createTempFile("test-metrics", ".db");
   try (MetricsDatabase db = new MetricsDatabase(tempDb)) {
       // If we get here, all migrations ran successfully
       assertEquals(0, db.getRecordCount());
   }
   ```

2. **Verify migration ordering**: Ensure version numbers are sequential and no gaps exist.

3. **Test backward compatibility**: Create a database with an older schema, then verify
   Flyway upgrades it correctly.

4. **Run full test suite**:
   ```bash
   cd /src/exoreaction/Synthesis && mvn test
   ```

## Database Location

The metrics database is stored at `~/.synthesis/metrics.db` (shared across all workspaces).
This path is returned by `MetricsDatabase.getDefaultPath()`.

Retention policy: Records older than 90 days are automatically cleaned up on initialization.

## See Also

- `synthesis-metrics-tracking.md` - How metrics are collected and queried
- `synthesis-workspace-management.md` - Workspace configuration that feeds into metrics
- `synthesis-development.md` - General Synthesis development patterns
