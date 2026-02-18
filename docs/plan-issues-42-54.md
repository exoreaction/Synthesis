# Implementation Plan: Synthesis Issues #42–#54

> **Version:** 1.0
> **Date:** 2026-02-18
> **Scope:** 13 GitHub issues across the `report` module
> **Build:** `mvn test` from `/src/exoreaction/Synthesis`
> **Current version:** 1.8.3-SNAPSHOT

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Wave Structure Summary](#wave-structure-summary)
- [Wave 0: Test Infrastructure Foundation (#54 Phase 1)](#wave-0-test-infrastructure-foundation-54-phase-1)
- [Wave 1: Document Discovery Fixes (#43, #45, #50, #51)](#wave-1-document-discovery-fixes-43-45-50-51)
- [Wave 2: Entity Discovery Fixes (#47, #49, #52)](#wave-2-entity-discovery-fixes-47-49-52)
- [Wave 3: Period-Based Document Filtering (#46)](#wave-3-period-based-document-filtering-46)
- [Wave 4: AI Quality Improvements (#42, #48)](#wave-4-ai-quality-improvements-42-48)
- [Wave 5: Cost Estimation Fix (#53)](#wave-5-cost-estimation-fix-53)
- [Wave 6: Truncation Detection (#44)](#wave-6-truncation-detection-44)
- [Wave 7: Integration Tests (#54 Phases 2-3)](#wave-7-integration-tests-54-phases-2-3)
- [Dependency Graph](#dependency-graph)
- [Complexity Summary](#complexity-summary)
- [Shared Changes Map](#shared-changes-map)
- [Critical Files for Implementation](#critical-files-for-implementation)

---

## Architecture Overview

The report module has this flow:

```
ReportCommand (CLI, picocli)
  -> BusinessDocumentFinder.discover(workspace, topic)    [business topics]
  -> EntityDocumentFinder.discoverForProduct/Client(...)   [entity mode]
  -> ReportEngine.generate() / generateForEntity()
       -> ReportPrompts.xxxPass()     [prompt construction]
       -> ClaudeClient.generate()     [API call, no temperature set]
  -> ReportCache.put() / get()        [SQLite cache]
  -> ReportRenderer.render()          [markdown output]
```

### Key observations from code reading

1. **ClaudeClient.generate()** builds `MessageCreateParams` **without** calling `.temperature()`
   — so the API default temperature (1.0) is used. The Anthropic Java SDK v2.14
   `MessageCreateParams.Builder` exposes `.temperature(double)`.

2. **BusinessDocumentFinder.matchesPatterns()** uses `fileName.contains(pattern)` for
   pipeline/activity/executive patterns, and `fullPath.contains("/events/")` for events.
   No directory-level exclusion logic exists.

3. **EntityDocumentFinder.discoverForProduct()** has **no empty-result guard** — compare with
   `ReportEngine.generateForEntity()` which handles empty docs with a friendly message string
   (not a hard error with suggestions).

4. **`--period`** is passed to prompts as text but **never used for file filtering**. Documents
   from any date are included regardless of period.

5. **estimateCost()** uses hardcoded `avgCharsPerDoc = 4000` and `maxTokensPerPass` for output
   estimate, resulting in ~50% inaccuracy.

6. **No Mockito** in pom.xml — tests must use real objects, `@TempDir`, and in-memory SQLite.

7. **Existing test files** (4 files, ~960 tests total in project):
   - `ReportCacheTest.java` — 15 tests, in-memory SQLite pattern
   - `ReportPromptsTest.java` — 22 tests, prompt structure verification
   - `EntityDocumentFinderTest.java` — 6 tests, `@TempDir` filesystem pattern (new, PR #56)
   - `ReportAutoSaveTest.java` — 8 tests, `@TempDir` filesystem pattern (new, PR #56)

8. **ReportDocument** is a Java record — adding fields changes the constructor signature and
   breaks all callers. New methods can be added without breaking changes.

---

## Wave Structure Summary

| Wave | Issues | Theme | Complexity |
|------|--------|-------|------------|
| 0 | #54 (Phase 1) | Test infrastructure foundation | MEDIUM |
| 1 | #43, #45, #50, #51 | BusinessDocumentFinder discovery fixes | MEDIUM |
| 2 | #47, #49, #52 | EntityDocumentFinder discovery fixes | MEDIUM |
| 3 | #46 | Period-based document filtering | COMPLEX |
| 4 | #42, #48 | AI quality (temperature, prompts) | MEDIUM |
| 5 | #53 | Cost estimation accuracy | MEDIUM |
| 6 | #44 | Single-pass truncation detection | MEDIUM |
| 7 | #54 (Phases 2-3) | Integration and AI tests | MEDIUM |

---

## Wave 0: Test Infrastructure Foundation (#54 Phase 1)

**Rationale:** Every subsequent wave benefits from having tests written first (TDD). This wave
creates the test scaffolding that enables test-first development for Waves 1-6.

**Complexity:** MEDIUM (substantial file creation but patterns are established)

### 0A. Create `BusinessDocumentFinderTest.java`

**File:** `src/test/java/io/exoreaction/synthesis/report/BusinessDocumentFinderTest.java`

**Pattern to follow:** `EntityDocumentFinderTest.java` — uses `@TempDir`, creates directory
structures with `Files.createDirectories()` + `Files.writeString()`, asserts on discovered
documents. To control modification times, use `Files.setLastModifiedTime()`.

**Test cases (12 tests):**

```java
package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BusinessDocumentFinderTest {

    @TempDir
    Path tempDir;

    private final BusinessDocumentFinder finder = new BusinessDocumentFinder();

    @Test
    void discover_findsPipelineStatusFile() throws IOException {
        Path dir = tempDir.resolve("eXOReaction/business");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("PIPELINE-STATUS.md"), "# Pipeline\nActive deals...");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE);

        assertFalse(docs.isEmpty(), "Should find PIPELINE-STATUS.md");
        assertEquals("pipeline", docs.get(0).category());
    }

    @Test
    void discover_findsActivityLogFile() throws IOException {
        Files.writeString(tempDir.resolve("ACTIVITY-LOG.md"), "# Activity Log\nMeetings...");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.ACTIVITIES);

        assertTrue(docs.stream().anyMatch(d -> "activity".equals(d.category())),
                "Should find ACTIVITY-LOG.md as activity category");
    }

    @Test
    void discover_findsEventsDirectory() throws IOException {
        Path eventsDir = tempDir.resolve("eXOReaction/events");
        Files.createDirectories(eventsDir);
        Files.writeString(eventsDir.resolve("workshop-feb.md"), "# Workshop\nFeb 2026");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.ACTIVITIES);

        assertTrue(docs.stream().anyMatch(d -> "event".equals(d.category())),
                "Should find files in events/ directory");
    }

    @Test
    void discover_findsStrategyFiles() throws IOException {
        Path strategyDir = tempDir.resolve("eXOReaction/business/strategy");
        Files.createDirectories(strategyDir);
        Files.writeString(strategyDir.resolve("EXECUTIVE-SUMMARY.md"), "# Strategy");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.DECISIONS);

        assertTrue(docs.stream().anyMatch(d -> "strategy".equals(d.category())),
                "Should find strategy files in business/strategy/");
    }

    @Test
    void discover_findsExecutiveUpdateFiles() throws IOException {
        Files.writeString(tempDir.resolve("EXECUTIVE-UPDATE-2026-02.md"), "# Update");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.EXECUTIVE);

        assertTrue(docs.stream().anyMatch(d -> "executive".equals(d.category())),
                "Should find EXECUTIVE-UPDATE files");
    }

    @Test
    void discover_excludesReadmeFiles() throws IOException {
        Files.writeString(tempDir.resolve("README.md"), "# README with pipeline info");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.WEEKLY);

        assertTrue(docs.isEmpty(), "README.md should be excluded from all categories");
    }

    @Test
    void discover_excludesDotSynthesisDirectory() throws IOException {
        Path synthDir = tempDir.resolve(".synthesis");
        Files.createDirectories(synthDir);
        Files.writeString(synthDir.resolve("PIPELINE-STATUS.md"), "# Cached pipeline");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE);

        assertTrue(docs.isEmpty(), "Files under .synthesis/ should be excluded");
    }

    @Test
    void discover_excludesGitDirectory() throws IOException {
        Path gitDir = tempDir.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("ACTIVITY-LOG.md"), "# Git internal");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.ACTIVITIES);

        assertTrue(docs.isEmpty(), "Files under .git/ should be excluded");
    }

    @Test
    void discover_pipelineTopicOnlyFindsRelevantCategories() throws IOException {
        Files.writeString(tempDir.resolve("PIPELINE-STATUS.md"), "# Pipeline");
        Files.writeString(tempDir.resolve("ACTIVITY-LOG.md"), "# Activity");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE);

        assertTrue(docs.stream().allMatch(d -> "pipeline".equals(d.category())),
                "PIPELINE topic should only return pipeline-category documents");
    }

    @Test
    void discover_weeklyTopicFindsAllCategories() throws IOException {
        Files.writeString(tempDir.resolve("PIPELINE-STATUS.md"), "# Pipeline");
        Files.writeString(tempDir.resolve("ACTIVITY-LOG.md"), "# Activity");
        Path eventsDir = tempDir.resolve("eXOReaction/events");
        Files.createDirectories(eventsDir);
        Files.writeString(eventsDir.resolve("workshop.md"), "# Event");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.WEEKLY);

        assertTrue(docs.size() >= 3, "WEEKLY topic should find docs from multiple categories");
    }

    @Test
    void discover_sortsByLastModifiedMostRecentFirst() throws IOException {
        Path p1 = tempDir.resolve("PIPELINE-STATUS.md");
        Path p2 = tempDir.resolve("pipeline-old.md");
        Files.writeString(p1, "# Current");
        Files.writeString(p2, "# Old");
        Files.setLastModifiedTime(p1, FileTime.from(Instant.now()));
        Files.setLastModifiedTime(p2, FileTime.from(Instant.now().minus(30, ChronoUnit.DAYS)));

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE);

        assertTrue(docs.size() >= 2, "Should find both pipeline files");
        assertTrue(docs.get(0).lastModified().isAfter(docs.get(1).lastModified()),
                "Most recently modified document should be first");
    }

    @Test
    void discover_deduplicatesByPath() throws IOException {
        Files.writeString(tempDir.resolve("PIPELINE-STATUS.md"), "# Pipeline");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.WEEKLY);

        long pipelineCount = docs.stream()
                .filter(d -> d.path().getFileName().toString().equals("PIPELINE-STATUS.md"))
                .count();
        assertEquals(1, pipelineCount, "Same file should not appear twice after deduplication");
    }
}
```

### 0B. Create `BusinessDocumentFinderFalsePositiveTest.java`

**File:** `src/test/java/io/exoreaction/synthesis/report/BusinessDocumentFinderFalsePositiveTest.java`

These tests will **initially FAIL** (red phase) and pass after Wave 1 fixes are applied.

```java
package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * False-positive regression tests for BusinessDocumentFinder.
 * Each test documents a specific false-positive bug (issues #43, #45, #50).
 * Tests are expected to FAIL before their corresponding fixes are applied.
 */
class BusinessDocumentFinderFalsePositiveTest {

    @TempDir
    Path tempDir;

    private final BusinessDocumentFinder finder = new BusinessDocumentFinder();

    @Test
    void discover_excludesTechnicalPipelineFiles_issue43() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("multi-stage-pipeline-architecture.yaml"),
                "# Technical pipeline config");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE);

        assertTrue(docs.isEmpty(),
                "Technical pipeline files in /skills/ should NOT be discovered (#43)");
    }

    @Test
    void discover_excludesMethodologyPipelineFiles_issue43() throws IOException {
        Path methodDir = tempDir.resolve("methodology");
        Files.createDirectories(methodDir);
        Files.writeString(methodDir.resolve("pipeline-overview.md"),
                "# Methodology: Pipeline Overview");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE);

        assertTrue(docs.isEmpty(),
                "Methodology pipeline files should NOT be discovered (#43)");
    }

    @Test
    void discover_excludesArchitecturePipelineFiles_issue43() throws IOException {
        Path archDir = tempDir.resolve("docs/technical");
        Files.createDirectories(archDir);
        Files.writeString(archDir.resolve("pipeline-design.md"),
                "# Architecture: Pipeline Design");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE);

        assertTrue(docs.isEmpty(),
                "Technical architecture pipeline files should NOT be discovered (#43)");
    }

    @Test
    void discover_excludesPersonalEventsDirectory_issue45() throws IOException {
        Path personalEvents = tempDir.resolve("personal/events");
        Files.createDirectories(personalEvents);
        Files.writeString(personalEvents.resolve("sommerfest.md"), "# Sommerfest 2026");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.ACTIVITIES);

        boolean hasPersonalEvent = docs.stream()
                .anyMatch(d -> d.path().toString().contains("personal"));
        assertFalse(hasPersonalEvent,
                "Personal events in /personal/events/ should NOT be discovered (#45)");
    }

    @Test
    void discover_stillFindsBusinessEventsDirectory_issue45() throws IOException {
        Path businessEvents = tempDir.resolve("eXOReaction/events");
        Files.createDirectories(businessEvents);
        Files.writeString(businessEvents.resolve("workshop-feb.md"),
                "# Workshop February 2026");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.ACTIVITIES);

        assertTrue(docs.stream().anyMatch(d -> "event".equals(d.category())),
                "Business events must still be discovered after #45 fix (regression guard)");
    }

    @Test
    void discover_excludesPresentationNotesFromEvents_issue50() throws IOException {
        Path eventsDir = tempDir.resolve("eXOReaction/events");
        Files.createDirectories(eventsDir);
        Files.writeString(eventsDir.resolve("conference-presentation-notes.md"),
                "# Presentation: AI Development Workshop");
        Files.writeString(eventsDir.resolve("javazone-slides-summary.md"),
                "# Slides: JavaZone 2026 Talk");

        List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.ACTIVITIES);

        boolean hasPresentationMaterial = docs.stream()
                .anyMatch(d -> d.path().getFileName().toString().contains("presentation")
                        || d.path().getFileName().toString().contains("slides"));
        assertFalse(hasPresentationMaterial,
                "Presentation materials in events/ should NOT be categorized as events (#50)");
    }
}
```

### 0C. Extend `EntityDocumentFinderTest.java` with Wave 2 and future tests

Add these methods to the existing `EntityDocumentFinderTest.java`. Tests for #47, #49, and
#52 — some will initially fail and pass after Wave 2 fixes.

```java
// --- Product discovery: development history (#49) ---

@Test
void discoverForProduct_findsChangelogInProductDir_issue49() throws IOException {
    Path productDir = tempDir.resolve("eXOReaction/products/TestProduct");
    Files.createDirectories(productDir);
    Files.writeString(productDir.resolve("CHANGELOG.md"),
            "# Changelog\n## v1.0\n- Initial release");

    List<ReportDocument> docs = finder.discoverForProduct(tempDir, "TestProduct");

    assertTrue(docs.stream().anyMatch(d ->
            d.path().getFileName().toString().equalsIgnoreCase("CHANGELOG.md")),
            "Should find CHANGELOG.md in product directory (#49)");
}

@Test
void discoverForProduct_findsReleaseNotesInProductDir_issue49() throws IOException {
    Path productDir = tempDir.resolve("eXOReaction/products/TestProduct/docs");
    Files.createDirectories(productDir);
    Files.writeString(productDir.resolve("RELEASE-NOTES.md"), "# Release Notes\n## v1.1");

    List<ReportDocument> docs = finder.discoverForProduct(tempDir, "TestProduct");

    assertTrue(docs.stream().anyMatch(d ->
            d.path().getFileName().toString().contains("RELEASE-NOTES")),
            "Should find RELEASE-NOTES.md in product docs (#49)");
}

@Test
void discoverForProduct_findsRoadmapInProductDir_issue49() throws IOException {
    Path productDir = tempDir.resolve("eXOReaction/products/TestProduct");
    Files.createDirectories(productDir);
    Files.writeString(productDir.resolve("ROADMAP.md"), "# Roadmap\n## Q1 2026");

    List<ReportDocument> docs = finder.discoverForProduct(tempDir, "TestProduct");

    assertTrue(docs.stream().anyMatch(d ->
            d.path().getFileName().toString().contains("ROADMAP")),
            "Should find ROADMAP.md in product directory (#49)");
}

// --- Product discovery: cross-contamination (#52) ---

@Test
void discoverForProduct_doesNotIncludeUnrelatedGotchaFiles_issue52() throws IOException {
    Path productDir = tempDir.resolve("eXOReaction/products/lib-pcb");
    Files.createDirectories(productDir);
    Files.writeString(productDir.resolve("README.md"), "# lib-pcb");
    Path docsDir = tempDir.resolve("eXOReaction/products/lib-pcb/docs");
    Files.createDirectories(docsDir);
    Files.writeString(docsDir.resolve("jme3-gotchas.md"),
            "# JME3 Gotchas\nNothing to do with PCB");

    List<ReportDocument> docs = finder.discoverForProduct(tempDir, "lib-pcb");

    boolean hasGotchas = docs.stream()
            .anyMatch(d -> d.path().getFileName().toString().contains("gotchas"));
    assertFalse(hasGotchas,
            "Unrelated *-gotchas.md files should NOT appear in product reports (#52)");
}

@Test
void discoverForProduct_doesNotIncludeReferenceNotesFiles_issue52() throws IOException {
    Path productDir = tempDir.resolve("eXOReaction/products/TestProduct/docs");
    Files.createDirectories(productDir);
    Files.writeString(productDir.resolve("README.md"), "# TestProduct docs");
    Files.writeString(productDir.resolve("some-library.notes.md"),
            "# Random library notes\nNot product-related");

    List<ReportDocument> docs = finder.discoverForProduct(tempDir, "TestProduct");

    boolean hasNotes = docs.stream()
            .anyMatch(d -> d.path().getFileName().toString().contains(".notes."));
    assertFalse(hasNotes,
            "Reference *.notes.md files should NOT appear in product reports (#52)");
}

// --- Empty result guard (#47) ---

@Test
void discoverForProduct_returnsEmptyForNonExistentProduct_issue47() {
    List<ReportDocument> docs = finder.discoverForProduct(tempDir, "NonExistentProduct");

    assertTrue(docs.isEmpty(),
            "Non-existent product should return empty list (#47)");
}

@Test
void discoverForClient_returnsEmptyForNonExistentClient_issue47() throws IOException {
    Files.createDirectories(tempDir.resolve("eXOReaction/clients"));

    List<ReportDocument> docs = finder.discoverForClient(tempDir, "NonExistentClient");

    assertTrue(docs.isEmpty(),
            "Non-existent client should return empty list (#47)");
}
```

### 0D. Create `ReportEngineTest.java`

**File:** `src/test/java/io/exoreaction/synthesis/report/ReportEngineTest.java`

Tests only non-AI methods (cost estimation, model getter). AI-dependent tests are in
Wave 7 `@Tag("ai-integration")`.

```java
package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReportEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void getModel_returnsNoneWhenClientIsNull() {
        ReportEngine engine = new ReportEngine(null, 4000);
        assertEquals("none", engine.getModel());
    }

    @Test
    void estimateCost_pipelineTopicReturnsOnePass() throws IOException {
        createSampleDocs();
        ReportEngine engine = new ReportEngine(null, 4000);
        ReportEngine.CostEstimate estimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.PIPELINE, "1w");
        assertEquals(1, estimate.passCount(), "PIPELINE topic should use 1 pass");
    }

    @Test
    void estimateCost_activitiesTopicReturnsOnePass() throws IOException {
        createSampleDocs();
        ReportEngine engine = new ReportEngine(null, 4000);
        ReportEngine.CostEstimate estimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.ACTIVITIES, "1w");
        assertEquals(1, estimate.passCount(), "ACTIVITIES topic should use 1 pass");
    }

    @Test
    void estimateCost_executiveTopicReturnsFourPasses() throws IOException {
        createSampleDocs();
        ReportEngine engine = new ReportEngine(null, 4000);
        ReportEngine.CostEstimate estimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.EXECUTIVE, "1w");
        assertEquals(4, estimate.passCount(),
                "EXECUTIVE topic should use 4 passes (pipeline + activities + decisions + synthesis)");
    }

    @Test
    void estimateCost_emptyWorkspaceReturnsZeroDocs() {
        ReportEngine engine = new ReportEngine(null, 4000);
        ReportEngine.CostEstimate estimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.PIPELINE, "1w");
        assertEquals(0, estimate.documentCount(), "Empty workspace should have 0 documents");
    }

    @Test
    void estimateCost_formatProducesReadableOutput() throws IOException {
        createSampleDocs();
        ReportEngine engine = new ReportEngine(null, 4000);
        ReportEngine.CostEstimate estimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.PIPELINE, "1w");
        String formatted = estimate.format();
        assertTrue(formatted.contains("Business Report Cost Estimate"));
        assertTrue(formatted.contains("Passes:"));
        assertTrue(formatted.contains("Documents:"));
    }

    @Test
    void estimateCost_costIsNonNegative() throws IOException {
        createSampleDocs();
        ReportEngine engine = new ReportEngine(null, 4000);
        ReportEngine.CostEstimate estimate =
                engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.EXECUTIVE, "1w");
        assertTrue(estimate.totalCostUsd() >= 0);
        assertTrue(estimate.inputCostUsd() >= 0);
        assertTrue(estimate.outputCostUsd() >= 0);
    }

    private void createSampleDocs() throws IOException {
        Files.writeString(tempDir.resolve("PIPELINE-STATUS.md"),
                "# Pipeline\n\nActive deals: 3\nTotal: 500K NOK");
        Files.writeString(tempDir.resolve("ACTIVITY-LOG.md"),
                "# Activity Log\n\n## Feb 18\n- Meeting with client");
        Path strategyDir = tempDir.resolve("eXOReaction/business/strategy");
        Files.createDirectories(strategyDir);
        Files.writeString(strategyDir.resolve("EXECUTIVE-SUMMARY.md"),
                "# Executive Summary\n\nQ1 revenue on track");
    }
}
```

### Wave 0 test count summary

| Test file | New tests | Initial status |
|-----------|----------|----------------|
| BusinessDocumentFinderTest.java | 12 | All PASS |
| BusinessDocumentFinderFalsePositiveTest.java | 6 | Expected FAIL (red) |
| EntityDocumentFinderTest.java (additions) | 7 | Some FAIL (red for #49, #52) |
| ReportEngineTest.java | 6 | All PASS |
| **Total new** | **31** | |

---

## Wave 1: Document Discovery Fixes (#43, #45, #50, #51)

**Files modified:**
- `src/main/java/io/exoreaction/synthesis/report/BusinessDocumentFinder.java`
- `src/main/java/io/exoreaction/synthesis/report/ReportDocument.java` (for #51 — new method only)
- `src/main/java/io/exoreaction/synthesis/report/ReportPrompts.java` (for #51)

**Complexity:** MEDIUM

**After this wave:** All 6 tests in `BusinessDocumentFinderFalsePositiveTest` should PASS.

### 1A. Add `EXCLUDED_DIRS` constant (#43 + #45)

**Location:** `BusinessDocumentFinder.java`, after the last patterns constant.

```java
/**
 * Directories whose contents should NEVER match business document patterns.
 * Prevents false positives from technical/methodology/personal directories.
 *
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/43">#43</a>
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/45">#45</a>
 */
private static final List<String> EXCLUDED_DIRS = List.of(
        "/skills/",
        "/methodology/",
        "/docs/technical/",
        "/architecture/",
        "/personal/"
);
```

### 1B. Fix `matchesPatterns()` for pipeline false positives (#43)

**Location:** `BusinessDocumentFinder.java`, `matchesPatterns()` method.

In the final block where filename patterns are checked, add directory exclusion first:

```java
// Exclude technical/non-business directories (#43)
for (String excluded : EXCLUDED_DIRS) {
    if (fullPath.contains(excluded)) {
        return false;
    }
}

// Then check filename patterns
for (String pattern : patterns) {
    if (fileName.contains(pattern)) {
        return true;
    }
}
return false;
```

### 1C. Fix events false positive for personal directories (#45)

**Location:** `BusinessDocumentFinder.java`, `matchesPatterns()`, events category block.

Replace:
```java
if ("event".equals(category)) {
    return fullPath.contains("/events/") && fileName.endsWith(".md");
}
```

With:
```java
if ("event".equals(category)) {
    if (!fullPath.contains("/events/") || !fileName.endsWith(".md")) return false;
    // Exclude personal directories (#45)
    for (String excluded : EXCLUDED_DIRS) {
        if (fullPath.contains(excluded)) return false;
    }
    // Exclude presentation materials (#50)
    if (isPresentationFile(fileName)) return false;
    return true;
}
```

### 1D. Add `isPresentationFile()` helper (#50)

**Location:** `BusinessDocumentFinder.java`, new private static method.

```java
/**
 * Returns true if the filename indicates presentation material rather than an event record.
 * Presentation materials are created FOR events, not records OF events.
 *
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/50">#50</a>
 */
private static boolean isPresentationFile(String fileName) {
    String lower = fileName.toLowerCase();
    return lower.contains("presentation") || lower.contains("slides")
            || lower.contains("deck") || lower.contains("talk-");
}
```

### 1E. Add `isArchived()` method to `ReportDocument` (#51)

**File:** `src/main/java/io/exoreaction/synthesis/report/ReportDocument.java`

Add as a derived method on the record (no constructor change):

```java
/**
 * Returns true if this document appears to be from an archive or historical context.
 * Based on path heuristics — no field change needed.
 *
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/51">#51</a>
 */
public boolean isArchived() {
    String pathStr = path.toString().toLowerCase();
    return pathStr.contains("/archive/") || pathStr.contains("/archived/")
            || pathStr.contains("/legacy/") || pathStr.contains("/historical/")
            || pathStr.contains("/old/");
}
```

### 1F. Modify `ReportPrompts.formatDocuments()` for archive marker (#51)

**File:** `src/main/java/io/exoreaction/synthesis/report/ReportPrompts.java`

In `formatDocuments()`, add the archive marker for historical documents:

```java
return docs.stream()
        .map(doc -> {
            String header = "--- " + doc.category().toUpperCase() + ": "
                    + doc.relativePath() + " ---\n";
            if (doc.isArchived()) {
                header += "[HISTORICAL DOCUMENT -- may be outdated. "
                        + "Weight current documents more heavily.]\n";
            }
            return header + doc.content() + "\n";
        })
        .collect(Collectors.joining("\n"));
```

### 1G. Tests for #51

**Add to `BusinessDocumentFinderTest.java`:**

```java
@Test
void reportDocument_isArchived_trueForArchivePaths_issue51() {
    ReportDocument doc = new ReportDocument(
            Path.of("/workspace/archive/old-strategy.md"),
            "archive/old-strategy.md", "strategy", "Old content", Instant.now(), 100L);
    assertTrue(doc.isArchived(), "Documents in /archive/ should be archived (#51)");
}

@Test
void reportDocument_isArchived_trueForLegacyPaths_issue51() {
    ReportDocument doc = new ReportDocument(
            Path.of("/workspace/legacy/2024-plan.md"),
            "legacy/2024-plan.md", "strategy", "Legacy content", Instant.now(), 100L);
    assertTrue(doc.isArchived(), "Documents in /legacy/ should be archived (#51)");
}

@Test
void reportDocument_isArchived_falseForNormalPaths_issue51() {
    ReportDocument doc = new ReportDocument(
            Path.of("/workspace/eXOReaction/business/strategy/EXECUTIVE-SUMMARY.md"),
            "eXOReaction/business/strategy/EXECUTIVE-SUMMARY.md", "strategy",
            "Current content", Instant.now(), 100L);
    assertFalse(doc.isArchived(), "Normal business docs should NOT be flagged (#51)");
}
```

**Add to `ReportPromptsTest.java`:**

```java
@Test
void formatDocuments_prependsHistoricalMarkerForArchivedDocs_issue51() {
    ReportDocument archivedDoc = new ReportDocument(
            Path.of("/workspace/archive/old-pipeline.md"),
            "archive/old-pipeline.md", "pipeline", "Old pipeline data", Instant.now(), 50L);
    String prompt = ReportPrompts.pipelinePass(List.of(archivedDoc), ReportTarget.CEO, "Last 7 days");
    assertTrue(prompt.contains("HISTORICAL DOCUMENT"),
            "Archived documents should have [HISTORICAL DOCUMENT] marker (#51)");
}

@Test
void formatDocuments_noHistoricalMarkerForCurrentDocs_issue51() {
    ReportDocument currentDoc = new ReportDocument(
            Path.of("/workspace/PIPELINE-STATUS.md"),
            "PIPELINE-STATUS.md", "pipeline", "Current pipeline", Instant.now(), 50L);
    String prompt = ReportPrompts.pipelinePass(List.of(currentDoc), ReportTarget.CEO, "Last 7 days");
    assertFalse(prompt.contains("HISTORICAL DOCUMENT"),
            "Current documents should NOT have historical marker (#51)");
}
```

---

## Wave 2: Entity Discovery Fixes (#47, #49, #52)

**Files modified:**
- `src/main/java/io/exoreaction/synthesis/report/EntityDocumentFinder.java`
- `src/main/java/io/exoreaction/synthesis/cli/ReportCommand.java` (for #47 guard)

**Complexity:** MEDIUM

**After this wave:** All Wave 0C entity tests should PASS.

### 2A. Add empty-result guard with suggestions for `--product` (#47)

**File:** `ReportCommand.java`, after document discovery for fingerprinting.

```java
// Guard: no documents found for entity (#47)
if (entityName != null && documents.isEmpty()) {
    String entityType = reportTopic == ReportTopic.CLIENT ? "client" : "product";
    AnsiOutput.printError("No documents found for " + entityType
            + " \"" + entityName + "\".");
    List<String> suggestions = suggestSimilarEntities(workspaceRoot, entityName, reportTopic);
    if (!suggestions.isEmpty()) {
        System.err.println("  Did you mean one of these?");
        for (String s : suggestions) {
            System.err.println("    --" + entityType + " " + s);
        }
    } else {
        System.err.println("  Expected: eXOReaction/"
                + (reportTopic == ReportTopic.CLIENT ? "clients/" : "products/"));
    }
    return 1;
}
```

**New helper method:**

```java
private List<String> suggestSimilarEntities(Path workspaceRoot, String entityName,
                                              ReportTopic topic) {
    List<String> suggestions = new ArrayList<>();
    Path searchRoot = topic == ReportTopic.CLIENT
            ? workspaceRoot.resolve("eXOReaction/clients")
            : workspaceRoot.resolve("eXOReaction/products");
    if (!Files.isDirectory(searchRoot)) return suggestions;
    try (var stream = Files.list(searchRoot)) {
        stream.filter(Files::isDirectory)
                .map(p -> p.getFileName().toString())
                .filter(name -> !name.startsWith("."))
                .map(name -> name.startsWith("opportunity-")
                        ? name.substring("opportunity-".length()) : name)
                .distinct().sorted()
                .forEach(suggestions::add);
    } catch (IOException e) { /* ignore */ }
    return suggestions;
}
```

### 2B. Extend product discovery to find development history (#49)

**File:** `EntityDocumentFinder.java`, method `collectDocFiles()`.

Expand the `product-source` category filter to include changelog-style files:

```java
.filter(p -> {
    if ("product-source".equals(category)) {
        String s = p.toString();
        String name = p.getFileName().toString();
        String nameUpper = name.toUpperCase();
        return s.contains("/docs/")
                || name.equalsIgnoreCase("README.md")
                || name.equalsIgnoreCase("CLAUDE.md")
                || nameUpper.contains("CHANGELOG")
                || nameUpper.contains("RELEASE-NOTES")    // #49
                || nameUpper.contains("RELEASE_NOTES")    // #49
                || nameUpper.contains("ROADMAP")           // #49
                || (nameUpper.contains("ACTIVITY") && nameUpper.contains("LOG")); // #49
    }
    return true;
})
```

### 2C. Fix cross-contamination in product reports (#52)

**Step 1:** Add constant after `MAX_MENTION_CHARS`:

```java
/**
 * File name patterns to exclude from entity discovery.
 * Prevents reference/cheatsheet files from contaminating product reports.
 *
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/52">#52</a>
 */
private static final List<String> EXCLUDED_FILE_PATTERNS = List.of(
        "-gotchas.", ".notes.", "-notes.", "-cheatsheet.", "-reference."
);
```

**Step 2:** Add filter in `collectDocFiles()` walker chain (before the product-source filter):

```java
// Exclude reference/gotcha files that cause cross-contamination (#52)
.filter(p -> {
    String nameLower = p.getFileName().toString().toLowerCase();
    for (String pattern : EXCLUDED_FILE_PATTERNS) {
        if (nameLower.contains(pattern)) return false;
    }
    return true;
})
```

---

## Wave 3: Period-Based Document Filtering (#46)

**Files modified:**
- `src/main/java/io/exoreaction/synthesis/report/BusinessDocumentFinder.java`
- `src/main/java/io/exoreaction/synthesis/report/ReportEngine.java`
- `src/main/java/io/exoreaction/synthesis/cli/ReportCommand.java`

**Complexity:** COMPLEX (cross-cutting, needs careful anchor-doc handling and caller updates)

### 3A. Add period utilities to `BusinessDocumentFinder`

```java
/**
 * Parses a period string into an Instant cutoff.
 *
 * @param period "1w", "2w", or "1m"
 * @return cutoff instant (start of cutoff day)
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/46">#46</a>
 */
public static Instant parsePeriodCutoff(String period) {
    LocalDate today = LocalDate.now();
    LocalDate cutoff = switch (period) {
        case "2w" -> today.minusWeeks(2);
        case "1m" -> today.minusMonths(1);
        default -> today.minusWeeks(1);
    };
    return cutoff.atStartOfDay(ZoneId.systemDefault()).toInstant();
}

private static final List<String> ANCHOR_DOC_PATTERNS = List.of(
        "PIPELINE-STATUS", "PIPELINE_STATUS",
        "ACTIVITY-LOG", "ACTIVITY_LOG"
);

private static boolean isAnchorDoc(String fileName) {
    String upper = fileName.toUpperCase();
    return ANCHOR_DOC_PATTERNS.stream().anyMatch(upper::contains);
}
```

**New imports:** `java.time.LocalDate`, `java.time.ZoneId`

### 3B. Add period-aware `discover()` overload

Add `discover(Path, ReportTopic, String)` that applies period-based filtering:

```java
public List<ReportDocument> discover(Path workspaceRoot, ReportTopic topic, String period) {
    // Collect all docs via existing discover() logic
    List<ReportDocument> allDocs = discover(workspaceRoot, topic);

    // Apply period-based filtering — anchor docs always included (#46)
    Instant cutoff = parsePeriodCutoff(period);
    return allDocs.stream()
            .filter(doc -> isAnchorDoc(doc.path().getFileName().toString())
                    || doc.lastModified().isAfter(cutoff))
            .collect(Collectors.toList());
}
```

### 3C. Update callers to pass period

**`ReportEngine.java`** — two call sites:
- `generate()`: `finder.discover(workspaceRoot, topic)` → `finder.discover(workspaceRoot, topic, period)`
- `estimateCost()`: same change (period is already a parameter but was not passed through)

**`ReportCommand.java`** — two call sites:
- Fingerprinting: `new BusinessDocumentFinder().discover(workspaceRoot, reportTopic)` → add `, period`
- Estimate mode: same change

### 3D. Tests for #46

**Add to `BusinessDocumentFinderTest.java`:**

```java
@Test
void discover_withPeriod1w_excludesOldDocuments_issue46() throws IOException {
    Path strategyDir = tempDir.resolve("eXOReaction/business/strategy");
    Files.createDirectories(strategyDir);
    Path oldFile = strategyDir.resolve("old-strategy.md");
    Files.writeString(oldFile, "# Old Strategy");
    Files.setLastModifiedTime(oldFile,
            FileTime.from(Instant.now().minus(30, ChronoUnit.DAYS)));

    List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.DECISIONS, "1w");

    assertFalse(docs.stream().anyMatch(d ->
            d.path().getFileName().toString().equals("old-strategy.md")),
            "Documents older than 1 week should be excluded (#46)");
}

@Test
void discover_withPeriod_alwaysIncludesPipelineStatus_issue46() throws IOException {
    Path pipeline = tempDir.resolve("PIPELINE-STATUS.md");
    Files.writeString(pipeline, "# Pipeline");
    Files.setLastModifiedTime(pipeline,
            FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));

    List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.PIPELINE, "1w");

    assertTrue(docs.stream().anyMatch(d ->
            d.path().getFileName().toString().equals("PIPELINE-STATUS.md")),
            "PIPELINE-STATUS.md is anchor doc and must always be included (#46)");
}

@Test
void discover_withPeriod_alwaysIncludesActivityLog_issue46() throws IOException {
    Path activityLog = tempDir.resolve("ACTIVITY-LOG.md");
    Files.writeString(activityLog, "# Activity Log");
    Files.setLastModifiedTime(activityLog,
            FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));

    List<ReportDocument> docs = finder.discover(tempDir, ReportTopic.ACTIVITIES, "1w");

    assertTrue(docs.stream().anyMatch(d ->
            d.path().getFileName().toString().equals("ACTIVITY-LOG.md")),
            "ACTIVITY-LOG.md is anchor doc and must always be included (#46)");
}

@Test
void parsePeriodCutoff_1w_returnsApproximatelySevenDaysAgo_issue46() {
    Instant cutoff = BusinessDocumentFinder.parsePeriodCutoff("1w");
    Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
    assertTrue(Math.abs(cutoff.toEpochMilli() - sevenDaysAgo.toEpochMilli()) < 86400_000L,
            "1w cutoff should be approximately 7 days ago");
}

@Test
void parsePeriodCutoff_2w_returnsApproximatelyFourteenDaysAgo_issue46() {
    Instant cutoff = BusinessDocumentFinder.parsePeriodCutoff("2w");
    Instant fourteenDaysAgo = Instant.now().minus(14, ChronoUnit.DAYS);
    assertTrue(Math.abs(cutoff.toEpochMilli() - fourteenDaysAgo.toEpochMilli()) < 86400_000L,
            "2w cutoff should be approximately 14 days ago");
}
```

---

## Wave 4: AI Quality Improvements (#42, #48)

**Files modified:**
- `src/main/java/io/exoreaction/synthesis/ai/ClaudeClient.java`
- `src/main/java/io/exoreaction/synthesis/report/ReportEngine.java`
- `src/main/java/io/exoreaction/synthesis/report/ReportPrompts.java`

**Complexity:** MEDIUM

### 4A. Add temperature parameter to `ClaudeClient.generate()` (#42)

Add an overload that accepts explicit temperature:

```java
/**
 * Generates text with explicit temperature control.
 *
 * @param prompt      the prompt to send
 * @param maxTokens   maximum tokens in the response
 * @param temperature sampling temperature (0.0 = deterministic, 1.0 = default)
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/42">#42</a>
 */
public String generate(String prompt, int maxTokens, double temperature) {
    MessageCreateParams params = MessageCreateParams.builder()
            .model(model)
            .maxTokens((long) maxTokens)
            .temperature(temperature)
            .addUserMessage(prompt)
            .build();

    Message message = client.messages().create(params);
    return message.content().stream()
            .filter(ContentBlock::isText)
            .map(block -> block.asText().text())
            .findFirst()
            .orElse("");
}
```

Keep existing `generate(String, int)` unchanged for backward compatibility.

### 4B. Use temperature 0 in `ReportEngine` (#42)

Replace all `client.generate(prompt, maxTokens)` calls with `client.generate(prompt, maxTokens, 0.0)`.

**~9 call sites** in `generate()` and `generateForEntity()` — all single-pass and multi-pass
API calls for the report module. (Research commands keep their existing temperature.)

### 4C. Add confidence markers to prompts (#42)

**In `ReportPrompts.java`, add constant:**

```java
private static final String CONFIDENCE_MARKERS = """

        When presenting findings, classify each with a confidence marker:
        - **[Document-supported]** -- directly stated in source documents
        - **[Inferred]** -- reasonably deduced from available context
        - **[Ambiguous]** -- conflicting or insufficient evidence; list alternatives
        """;
```

**Append to:** `pipelinePass()`, `decisionsPass()`, `entityEvidencePass()` prompts.

### 4D. Add consistency rules to executive synthesis (#42)

In `executivePass()`, add before the format instructions:

```java
private static final String CONSISTENCY_RULES = """

        CONSISTENCY RULES:
        - If previous passes contain conflicting information about the same deal or metric,
          flag the conflict explicitly rather than silently choosing one interpretation.
        - Do not contradict findings from previous passes -- synthesize, do not overrule.
        - When recommendations differ between passes, present both with rationale.
        - Where data is ambiguous, say so.
        """;
```

Append `CONSISTENCY_RULES` to the `executivePass()` prompt template.

### 4E. Strengthen staleness flagging in `entityEvidencePass()` (#48)

Replace the existing staleness note with:

```
STALENESS DETECTION (today is %s):
1. Information in documents modified >14 days ago: mark as **[POTENTIALLY STALE]**
2. Specific dates mentioned in text that are before today: mark as **[DEADLINE PASSED]**
   and note how many days overdue -- do NOT present these as upcoming
3. Relative time phrases ("this week", "next Monday") in documents >7 days old:
   mark as **[TEMPORAL REFERENCE LIKELY OUTDATED]**
```

Add `todayForPrompt()` call to the formatted() arguments for this prompt.

### 4F. Tests for Wave 4

**Add to `ReportPromptsTest.java`:**

```java
@Test
void pipelinePass_containsConfidenceMarkers_issue42() {
    List<ReportDocument> docs = List.of(sampleDoc("pipeline"));
    String prompt = ReportPrompts.pipelinePass(docs, ReportTarget.CEO, "Last 7 days");
    assertTrue(prompt.contains("Document-supported"), "#42: confidence markers required");
    assertTrue(prompt.contains("Inferred"));
    assertTrue(prompt.contains("Ambiguous"));
}

@Test
void decisionsPass_containsConfidenceMarkers_issue42() {
    List<ReportDocument> docs = List.of(sampleDoc("strategy"));
    String prompt = ReportPrompts.decisionsPass(docs, ReportTarget.CEO, "Last 7 days");
    assertTrue(prompt.contains("Document-supported"), "#42: confidence markers required");
}

@Test
void executivePass_containsConsistencyRules_issue42() {
    List<ReportDocument> docs = List.of(sampleDoc("pipeline"));
    String prompt = ReportPrompts.executivePass(
            docs, ReportTarget.CEO, "p", "a", "d", "Last 7 days");
    assertTrue(prompt.contains("CONSISTENCY RULES")
            || prompt.contains("conflicting information"), "#42: consistency rules required");
}

@Test
void entityEvidencePass_containsDeadlinePassedInstruction_issue48() {
    List<ReportDocument> docs = List.of(sampleDoc("client"));
    String prompt = ReportPrompts.entityEvidencePass("TestCorp", "client", docs);
    assertTrue(prompt.contains("DEADLINE PASSED"), "#48: deadline passed instruction required");
}

@Test
void entityEvidencePass_containsPotentiallyStaleInstruction_issue48() {
    List<ReportDocument> docs = List.of(sampleDoc("client"));
    String prompt = ReportPrompts.entityEvidencePass("TestCorp", "client", docs);
    assertTrue(prompt.contains("POTENTIALLY STALE"), "#48: stale instruction required");
}

@Test
void entityEvidencePass_containsTemporalReferenceInstruction_issue48() {
    List<ReportDocument> docs = List.of(sampleDoc("client"));
    String prompt = ReportPrompts.entityEvidencePass("TestCorp", "client", docs);
    assertTrue(prompt.contains("TEMPORAL REFERENCE LIKELY OUTDATED"),
            "#48: temporal reference instruction required");
}
```

---

## Wave 5: Cost Estimation Fix (#53)

**File modified:** `src/main/java/io/exoreaction/synthesis/report/ReportEngine.java`

**Complexity:** MEDIUM

### 5A. Replace hardcoded token estimates with actual prompt construction

**Current approach:** `int estimatedInputTokens = (docCount * 4000) / 4` — hardcoded 4000 avg chars per doc.

**New approach:** Build the actual prompt strings (without calling AI) and use `chars / 4` heuristic:

```java
// For single-pass topics:
String prompt = ReportPrompts.pipelinePass(documents, target, periodDescription);
int estimatedInputTokens = prompt.length() / 4;
int passCount = 1;
int estimatedOutputTokens = maxTokensPerPass;

// For multi-pass (EXECUTIVE/WEEKLY):
// Build each pass prompt with actual documents, measure length
// For the synthesis pass, use a placeholder for previous pass outputs
```

The full `estimateCost()` rewrite should:
1. Accept `period` parameter (already in signature but unused for discovery)
2. Call `finder.discover(workspaceRoot, topic, period)` to get actual documents
3. Build actual prompt strings for each pass
4. Sum up `prompt.length() / 4` across all passes for input estimate
5. Use `maxTokensPerPass * passCount` for output estimate
6. Apply correct per-model pricing (sonnet vs opus)

### 5B. Tests for #53

**Add to `ReportEngineTest.java`:**

```java
@Test
void estimateCost_inputTokensScaleWithDocumentSize_issue53() throws IOException {
    Files.writeString(tempDir.resolve("PIPELINE-STATUS.md"),
            "# Pipeline\n" + "x".repeat(2000));
    ReportEngine engine = new ReportEngine(null, 4000);
    ReportEngine.CostEstimate smallEstimate =
            engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.PIPELINE, "1w");

    Files.writeString(tempDir.resolve("PIPELINE-STATUS.md"),
            "# Pipeline\n" + "x".repeat(7000));
    ReportEngine.CostEstimate largeEstimate =
            engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.PIPELINE, "1w");

    assertTrue(largeEstimate.estimatedInputTokens() > smallEstimate.estimatedInputTokens(),
            "Larger documents should produce higher input token estimates (#53)");
}

@Test
void estimateCost_multiPassHigherThanSinglePass_issue53() throws IOException {
    createSampleDocs();
    ReportEngine engine = new ReportEngine(null, 4000);

    ReportEngine.CostEstimate single =
            engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.PIPELINE, "1w");
    ReportEngine.CostEstimate multi =
            engine.estimateCost(tempDir, ReportTarget.CEO, ReportTopic.EXECUTIVE, "1w");

    assertTrue(multi.estimatedInputTokens() > single.estimatedInputTokens(),
            "Multi-pass (EXECUTIVE) should have higher input tokens than single-pass (#53)");
}
```

---

## Wave 6: Truncation Detection (#44)

**Files modified:**
- `src/main/java/io/exoreaction/synthesis/ai/ClaudeClient.java`
- `src/main/java/io/exoreaction/synthesis/report/ReportEngine.java`

**Complexity:** MEDIUM

### 6A. Add `GenerationResult` record and `generateWithMeta()` to `ClaudeClient`

```java
/**
 * Result of a generation call, including truncation status.
 *
 * @see <a href="https://github.com/exoreaction/Synthesis/issues/44">#44</a>
 */
public record GenerationResult(String content, boolean truncated) {}

/**
 * Generates text and returns metadata including whether output was truncated.
 * Use this for single-pass topics where silent truncation is a bug.
 */
public GenerationResult generateWithMeta(String prompt, int maxTokens, double temperature) {
    MessageCreateParams params = MessageCreateParams.builder()
            .model(model)
            .maxTokens((long) maxTokens)
            .temperature(temperature)
            .addUserMessage(prompt)
            .build();

    Message message = client.messages().create(params);
    String content = message.content().stream()
            .filter(ContentBlock::isText)
            .map(block -> block.asText().text())
            .findFirst()
            .orElse("");

    // Check if stop reason indicates truncation
    boolean truncated = Message.StopReason.MAX_TOKENS.equals(message.stopReason().orElse(null));

    return new GenerationResult(content, truncated);
}
```

**Note:** Verify the exact `StopReason` API in the version of `anthropic-java-sdk` in use.
Check `pom.xml` for version, then inspect the enum in the jar.

### 6B. Use `generateWithMeta()` for single-pass topics in `ReportEngine`

For the `PIPELINE`, `ACTIVITIES`, and `DECISIONS` single-pass cases:

```java
case PIPELINE: {
    if (verbose) System.err.print("  Running pipeline analysis...");
    var genResult = client.generateWithMeta(
            ReportPrompts.pipelinePass(documents, target, periodDescription),
            maxTokensPerPass, 0.0);
    if (genResult.truncated()) {
        System.err.println("\n  WARNING: Output truncated at " + maxTokensPerPass
                + " tokens. Use --max-tokens " + (maxTokensPerPass * 2)
                + " or --topic weekly for multi-pass synthesis.");
    }
    totalTokens += ResearchPassResult.estimateTokens(genResult.content());
    if (verbose) System.err.println(" done");
    reportContent = genResult.content();
    break;
}
```

Apply same pattern for `ACTIVITIES` and `DECISIONS` cases.

### 6C. Tests for #44

```java
// In a new or existing test file:

@Test
void generationResult_reportsContentCorrectly_issue44() {
    var result = new ClaudeClient.GenerationResult("Hello world", false);
    assertEquals("Hello world", result.content());
}

@Test
void generationResult_reportsTruncatedTrue_issue44() {
    var result = new ClaudeClient.GenerationResult("Partial...", true);
    assertTrue(result.truncated(), "Should report truncated=true (#44)");
}

@Test
void generationResult_reportsTruncatedFalse_issue44() {
    var result = new ClaudeClient.GenerationResult("Complete.", false);
    assertFalse(result.truncated(), "Should report truncated=false (#44)");
}
```

---

## Wave 7: Integration Tests (#54 Phases 2-3)

**Complexity:** MEDIUM

### 7A. Create `ReportCommandTest.java`

Since `ReportCommand` uses `@ParentCommand` and requires a running `SynthesisApp` for
full CLI testing, test the extracted public components (enums, renderer):

**File:** `src/test/java/io/exoreaction/synthesis/report/ReportCommandTest.java`

```java
package io.exoreaction.synthesis.report;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReportCommandTest {

    @Test
    void reportTopic_fromString_parsesAllValidTopics() {
        assertEquals(ReportTopic.PIPELINE, ReportTopic.fromString("pipeline"));
        assertEquals(ReportTopic.ACTIVITIES, ReportTopic.fromString("activities"));
        assertEquals(ReportTopic.DECISIONS, ReportTopic.fromString("decisions"));
        assertEquals(ReportTopic.EXECUTIVE, ReportTopic.fromString("executive"));
        assertEquals(ReportTopic.WEEKLY, ReportTopic.fromString("weekly"));
    }

    @Test
    void reportTopic_fromString_defaultsToWeeklyForUnknown() {
        assertEquals(ReportTopic.WEEKLY, ReportTopic.fromString(null));
        assertEquals(ReportTopic.WEEKLY, ReportTopic.fromString("unknown"));
    }

    @Test
    void reportTarget_fromStringStrict_throwsForInvalidInput() {
        assertThrows(IllegalArgumentException.class,
                () -> ReportTarget.fromStringStrict("unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> ReportTarget.fromStringStrict(null));
    }

    @Test
    void reportTarget_fromStringStrict_parsesAllValidTargets() {
        assertEquals(ReportTarget.CEO, ReportTarget.fromStringStrict("ceo"));
        assertEquals(ReportTarget.BOARD, ReportTarget.fromStringStrict("board"));
        assertEquals(ReportTarget.INVESTOR, ReportTarget.fromStringStrict("investor"));
    }

    @Test
    void reportTarget_fromStringStrict_isCaseInsensitive() {
        assertEquals(ReportTarget.CEO, ReportTarget.fromStringStrict("CEO"));
        assertEquals(ReportTarget.BOARD, ReportTarget.fromStringStrict("Board"));
    }

    @Test
    void reportRenderer_formatPeriod_formatsCorrectly() {
        assertEquals("Last 7 days", ReportRenderer.formatPeriod("1w"));
        assertEquals("Last 14 days", ReportRenderer.formatPeriod("2w"));
        assertEquals("Last 30 days", ReportRenderer.formatPeriod("1m"));
    }

    @Test
    void reportRenderer_formatPeriod_handlesUnknown() {
        assertEquals("3m", ReportRenderer.formatPeriod("3m"));
    }

    @Test
    void reportTopic_cliValues_areStable() {
        assertEquals("pipeline", ReportTopic.PIPELINE.cliValue());
        assertEquals("client", ReportTopic.CLIENT.cliValue());
        assertEquals("product", ReportTopic.PRODUCT.cliValue());
    }

    @Test
    void reportTarget_cliValues_areStable() {
        assertEquals("ceo", ReportTarget.CEO.cliValue());
        assertEquals("board", ReportTarget.BOARD.cliValue());
        assertEquals("investor", ReportTarget.INVESTOR.cliValue());
    }
}
```

### 7B. Add to `ReportCacheTest.java`

```java
@Test
void get_incrementsHitCounterOnEachAccess() throws Exception {
    ReportResult stored = sampleResult(ReportTopic.PIPELINE);
    cache.put(WORKSPACE, stored, FINGERPRINT);

    cache.get(WORKSPACE, ReportTopic.PIPELINE, ReportTarget.CEO, "1w", FINGERPRINT);
    cache.get(WORKSPACE, ReportTopic.PIPELINE, ReportTarget.CEO, "1w", FINGERPRINT);

    try (var stmt = connection.prepareStatement(
            "SELECT hits FROM report_cache WHERE workspace_path = ? AND topic = ?")) {
        stmt.setString(1, WORKSPACE.toString());
        stmt.setString(2, "pipeline");
        var rs = stmt.executeQuery();
        assertTrue(rs.next());
        assertEquals(2, rs.getInt("hits"), "Hit counter should be 2 after two get() calls");
    }
}

@Test
void putAndGet_preservesModel() {
    ReportResult stored = sampleResult(ReportTopic.PIPELINE);
    cache.put(WORKSPACE, stored, FINGERPRINT);
    Optional<ReportResult> retrieved = cache.get(
            WORKSPACE, ReportTopic.PIPELINE, ReportTarget.CEO, "1w", FINGERPRINT);
    assertTrue(retrieved.isPresent());
    assertEquals("claude-sonnet-test", retrieved.get().model());
}
```

### 7C. Optional AI integration tests

**File:** `src/test/java/io/exoreaction/synthesis/report/ReportAiIntegrationTest.java`

Gated by `@Tag("ai-integration")` and `@EnabledIfEnvironmentVariable`.
Run with: `mvn test -Dgroups=ai-integration`

```java
package io.exoreaction.synthesis.report;

import io.exoreaction.synthesis.ai.ClaudeClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Tag("ai-integration")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class ReportAiIntegrationTest {

    @TempDir Path tempDir;

    @Test
    void generate_producesNonEmptyReport() throws Exception {
        Files.writeString(tempDir.resolve("PIPELINE-STATUS.md"),
                "# Pipeline\nSpareBank 1: 100-200K NOK\nItem Consulting: 35-75K NOK");

        // Use claude-haiku for low cost in tests
        var client = ClaudeClient.create(null); // Will need real config setup
        // ... (implementation depends on ClaudeClient factory API)
    }
}
```

### 7D. Wave 7 test count summary

| Test file | New tests |
|-----------|----------|
| ReportCommandTest.java | 10 |
| ReportCacheTest.java (additions) | 2 |
| ReportAiIntegrationTest.java | 2 (optional) |
| **Total** | **14** |

---

## Dependency Graph

```
Wave 0 (Test Infrastructure)
  │
  ├──► Wave 1 (BusinessDocumentFinder: #43, #45, #50, #51)
  │        │
  │        └──► Wave 3 (Period filtering: #46)
  │                  │
  │                  └──► Wave 5 (Cost estimation: #53)
  │
  ├──► Wave 2 (EntityDocumentFinder: #47, #49, #52)
  │
  ├──► Wave 4 (AI Quality: #42, #48)
  │        │
  │        └──► Wave 6 (Truncation: #44)
  │
  └──► Wave 7 (Integration tests — runs last)
```

**Sequential constraints:**
- Wave 1 before Wave 3 (Wave 3 builds on BDF changes)
- Wave 3 before Wave 5 (cost estimation uses period-aware discover)
- Wave 4 before Wave 6 (truncation builds on temperature overload)
- Wave 7 last (validates full stack)

**Parallel opportunities:**
- Waves 1 and 2 can be developed simultaneously (different files)
- Wave 4 can be developed in parallel with Waves 1, 2, 3 (different files)

---

## Complexity Summary

| Issue | Title | Complexity | Wave |
|-------|-------|-----------|------|
| #54 | Test infrastructure (Phase 1) | MEDIUM | 0 |
| #43 | Pipeline keyword false positive | QUICK | 1 |
| #45 | Personal events false positive | QUICK | 1 |
| #50 | Presentation PDFs miscategorized | QUICK | 1 |
| #51 | Archive docs weighted equally | MEDIUM | 1 |
| #47 | Non-existent product hallucinates | QUICK | 2 |
| #49 | Product reports miss dev history | QUICK | 2 |
| #52 | Cross-contamination (jme3 in lib-pcb) | QUICK | 2 |
| #46 | `--period` flag has no effect | COMPLEX | 3 |
| #42 | Non-deterministic recommendations | MEDIUM | 4 |
| #48 | Stale urgency deadlines | QUICK | 4 |
| #53 | Hardcoded token estimates | MEDIUM | 5 |
| #44 | Single-pass truncation silent | MEDIUM | 6 |
| #54 | Test infrastructure (Phases 2-3) | MEDIUM | 7 |

---

## Shared Changes Map

### `BusinessDocumentFinder.java` (Waves 1, 3)

| Wave | Change | Location |
|------|--------|----------|
| 1 | Add `EXCLUDED_DIRS` constant | After patterns constants |
| 1 | Add exclusion check in `matchesPatterns()` | Final pattern loop |
| 1 | Fix events category block | `matchesPatterns()` events case |
| 1 | Add `isPresentationFile()` helper | New private method |
| 3 | Add `parsePeriodCutoff()` + `isAnchorDoc()` | New methods |
| 3 | Add `ANCHOR_DOC_PATTERNS` constant | New constant |
| 3 | Add period-aware `discover()` overload | New overload |

Wave 1 touches `matchesPatterns()`. Wave 3 adds new methods and a new `discover()` overload.
No overlap in code regions. Apply Wave 1 first, then Wave 3.

### `EntityDocumentFinder.java` (Wave 2)

| Wave | Change | Location |
|------|--------|----------|
| 2 | Add `EXCLUDED_FILE_PATTERNS` constant | After `MAX_MENTION_CHARS` |
| 2 | Add exclusion filter in `collectDocFiles()` | Walker filter chain |
| 2 | Expand product-source filter | `collectDocFiles()` filter |

All changes in one wave. The exclusion filter (#52) is added before the product-source
expansion filter (#49). No conflict.

### `ReportPrompts.java` (Waves 1, 4)

| Wave | Change | Location |
|------|--------|----------|
| 1 | Modify `formatDocuments()` for archive marker | Private method |
| 4 | Add `CONFIDENCE_MARKERS` constant | Class level |
| 4 | Append markers to 3 prompts | pipelinePass, decisionsPass, entityEvidencePass |
| 4 | Add consistency rules to executivePass | executivePass method |
| 4 | Strengthen staleness instructions | entityEvidencePass |

Wave 1 touches `formatDocuments()`. Wave 4 touches prompt template strings in different
methods. No overlap.

### `ClaudeClient.java` (Waves 4, 6)

| Wave | Change | Location |
|------|--------|----------|
| 4 | Add `generate(String, int, double)` overload | After existing generate() |
| 6 | Add `GenerationResult` record | New nested record |
| 6 | Add `generateWithMeta()` method | New method |

Wave 4 adds a temperature overload. Wave 6 adds a new record and method. Sequential, no
conflict.

### `ReportEngine.java` (Waves 3, 4, 5, 6)

| Wave | Change | Location |
|------|--------|----------|
| 3 | Pass `period` to `discover()` calls | Lines ~63, ~281 |
| 4 | Add `, 0.0` temperature to generate() calls | ~9 call sites |
| 5 | Rewrite `estimateCost()` body | `estimateCost()` method |
| 6 | Replace single-pass generate with generateWithMeta | 3 case blocks |

Apply in wave order: 3 → 4 → 5 → 6.

---

## Critical Files for Implementation

Five files are most critical — modified across multiple waves:

1. **`BusinessDocumentFinder.java`** — Core of Waves 1 and 3. Most-modified file in the plan.
   False-positive fixes (#43, #45, #50), archive detection (#51), period filtering (#46).

2. **`EntityDocumentFinder.java`** — Core of Wave 2. Cross-contamination fix (#52),
   dev history expansion (#49).

3. **`ClaudeClient.java`** — Core of Waves 4 and 6. Temperature control (#42),
   truncation detection (#44). Verify `MessageCreateParams.Builder.temperature()` API
   and `Message.StopReason.MAX_TOKENS` name in the SDK version in use.

4. **`ReportPrompts.java`** — Core of Wave 4. Confidence markers (#42), consistency rules
   (#42), staleness strengthening (#48), archive labeling (#51 via `formatDocuments()`).

5. **`ReportEngine.java`** — Touched by 4 waves. Temperature passthrough (#42), period
   passthrough (#46), cost estimation rewrite (#53), truncation handling (#44).

---

*Plan written by Claude Opus 4.6 on 2026-02-18.*
*Issues verified against GitHub exoreaction/Synthesis #42–#54.*
