# ADR-0001 seam refactor — before/after extraction evidence (#463)

Proof that the per-language `LanguageExtractor` seam refactor leaves the two persisted
write channels (`code_dependencies`, `cross_format_links`) **byte-identical**.

## Method

| | |
|---|---|
| **Before** | released `synthesis-1.43.0.jar` (pre-refactor, `~/.synthesis/lib/`) |
| **After** | branch HEAD build `synthesis-1.43.1-SNAPSHOT.jar` |
| **Command** | `code-graph extract -d <workspace>` (full extraction) |
| **Isolation** | each jar run under its own `-Duser.home=<dir>` → separate SQLite DB |
| **Normalisation** | dump excludes the volatile `workspace_path` (absolute) and `last_computed` (timestamp) columns; rows ordered deterministically |

A single mixed-language corpus (Java + Kotlin + TypeScript + a SQL migration) exercises every
resolution path across the three extractors and the cross-format step. Corpus source is listed at
the end for reproducibility.

## Result — identical on every channel

Stats reported by both jars were identical: **Files processed 11, Dependencies 13,
Cross-format links 1, Packages 2, External deps 2.**

> Note (#469): the reported `13` counted upsert attempts, one more than the 12 persisted rows
> (`./bar` and `./bar.js` collapse onto the same row). Since #469 the counter reports persisted
> rows, so this corpus now prints `Dependencies 12`. The rows themselves are unchanged.

| Channel | before rows | after rows | Verdict |
|---|---|---|---|
| Java `code_dependencies` | 4 | 4 | **IDENTICAL** |
| Kotlin `code_dependencies` | 5 | 5 | **IDENTICAL** |
| TypeScript `code_dependencies` | 3 | 3 | **IDENTICAL** |
| `cross_format_links` | 1 | 1 | **IDENTICAL** |

`diff before.<channel>.csv after.<channel>.csv` is empty for all four.

## Rows (after; before is byte-for-byte the same)

### Java — `code_dependencies` (before == after)

```
source_file, source_class, source_package, target_file, target_class, target_package, dependency_type, is_external, repo_name
src/main/java/com/example/Dog.java,Dog,com.example,src/main/java/com/example/Animal.java,Animal,"",extends,0,""
src/main/java/com/example/Dog.java,Dog,com.example,src/main/java/com/example/util/Helper.java,Helper,com.example.util,import,0,""
src/main/java/com/example/Dog.java,Dog,com.example,src/main/java/com/example/Runnable.java,Runnable,"",implements,0,""
src/main/java/com/example/Dog.java,Dog,com.example,,Service,org.springframework.stereotype,import,1,""
```

Covers: internal `import`, `extends`, `implements`, and external classification (`is_external=1`,
Spring `Service`, no `target_file`).

### Kotlin — `code_dependencies` (before == after)

```
source_file, source_class, source_package, target_file, target_class, target_package, dependency_type, is_external, repo_name
src/main/kotlin/com/example/Foo.kt,Foo,com.example,src/main/java/com/example/Animal.java,Animal,com.example,import,0,""
src/main/kotlin/com/example/Foo.kt,Foo,com.example,src/main/kotlin/com/example/Base.kt,Base,"",supertype,0,""
src/main/kotlin/com/example/Foo.kt,Foo,com.example,src/main/kotlin/com/example/Utils.kt,doThing,com.example.util,import,0,""
src/main/kotlin/com/example/HelloController.kt,HelloController,com.example,src/main/kotlin/com/example/Base.kt,Base,"",supertype,0,""
src/main/kotlin/com/example/HelloController.kt,HelloController,com.example,src/main/java/com/example/util/Helper.java,Helper,com.example.util,import,0,""
```

Covers: `supertype` (internal → `Base.kt`), cross-language Java `import` (`Animal.java`,
`Helper.java`), the **top-level-function fallback** (`import com.example.util.doThing` → `Utils.kt`,
the function-only file — the `packageFallbackFiles` hook), and multi-declaration primary-class
attribution (all `HelloController.kt` edges attributed to `HelloController`, not the earlier-declared
`HelloResponse` — `choosePrimaryClass`).

### TypeScript — `code_dependencies` (before == after)

```
source_file, source_class, source_package, target_file, target_class, target_package, dependency_type, is_external, repo_name
src/main/ts/foo.ts,foo,"",src/main/ts/bar.ts,bar,"",import,0,""
src/main/ts/foo.ts,foo,"",,react,"",import,1,""
src/main/ts/foo.ts,foo,"",src/main/ts/widget/index.ts,widget,"",import,0,""
```

Covers: relative import resolved internal (`./bar` → `bar.ts`; the `./bar.js` rewrite and the
duplicate `./bar` specifier collapse onto the same row), bare-module external (`react`,
`is_external=1`), and directory/index resolution (`./widget` → `widget/index.ts`).

### `cross_format_links` (before == after)

```
source_file, target_file, link_type, entity_name
src/main/resources/db/migration/V1__init.sql,src/main/java/com/example/Dog.java,table-reference,animals
```
Cross-format (SQL `CREATE TABLE animals` → `Dog.java`, which references the `animals` table) stays
outside the seam (ADR sub-decision 4) and is unaffected.

## Corpus (reproducible)

```
pom.xml
src/main/java/com/example/Animal.java
src/main/java/com/example/Dog.java
src/main/java/com/example/Runnable.java
src/main/java/com/example/util/Helper.java
src/main/kotlin/com/example/Base.kt
src/main/kotlin/com/example/Foo.kt
src/main/kotlin/com/example/HelloController.kt
src/main/kotlin/com/example/Utils.kt
src/main/resources/db/migration/V1__init.sql
src/main/ts/bar.ts
src/main/ts/foo.ts
src/main/ts/widget/index.ts
```
