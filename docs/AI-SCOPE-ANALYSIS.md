# AI Scope Analysis: Self-Learning SDD Synthesis

**Version:** 1.0
**Date:** 2026-02-15
**Author:** Thor Henning Hetland / eXOReaction (with Claude analysis)
**Status:** Analysis complete -- ready for implementation planning
**Companion:** [PRODUCT-VARIANTS-ROADMAP.md](./PRODUCT-VARIANTS-ROADMAP.md) (architecture decisions)

---

## Executive Summary

This document analyzes the AI capabilities that should be built into Synthesis's "Self-Learning SDD" edition by studying **proven patterns from 120+ Claude Code skills** that have been developed and validated in production over the past 3 months. Rather than speculating about what AI features might be useful, this analysis extracts what **actually works** from the skill library and maps those patterns onto Synthesis's existing architecture.

**Key finding:** The most impactful AI capabilities are not the ones that require the most sophisticated models. The highest-value patterns are **deterministic enrichment** (companion file generation, metadata extraction, relationship inference) that require zero AI API calls. The second tier is **local model integration** (Whisper for transcription, vision for screenshots). Cloud AI (Claude API) is the third tier -- powerful but optional.

**Three-phase roadmap:**
1. **Phase 1: Deterministic Intelligence** (4-6 weeks) -- Companion file generation, enhanced relationship inference, content fingerprinting. Zero AI dependencies. Works in air-gapped mode.
2. **Phase 2: Local Media Enrichment** (6-8 weeks) -- Whisper transcription, ffprobe metadata, PDF slide extraction. Optional local binaries, no cloud.
3. **Phase 3: Semantic Intelligence** (4-6 weeks) -- Claude Vision for images/PDFs, AI-generated summaries, semantic search. Requires API key, cloud connectivity.

**Validated ROI from skills:** Campaign-batched media processing reduces per-asset time from 12 min to 2.2 min (5.5x). Parallel transcription processes at 16x realtime. Screenshot analysis costs $0.005/image at 92% accuracy. The patterns are proven -- the question is which to automate inside Synthesis first.

---

## Table of Contents

1. [Methodology](#1-methodology)
2. [Media File Handling Patterns](#2-media-file-handling-patterns)
3. [Text and Image Extraction Patterns](#3-text-and-image-extraction-patterns)
4. [Context-Oriented Linking Patterns](#4-context-oriented-linking-patterns)
5. [Current Synthesis Capabilities (Baseline)](#5-current-synthesis-capabilities-baseline)
6. [Gap Analysis: Skills vs Synthesis](#6-gap-analysis-skills-vs-synthesis)
7. [Incremental AI Roadmap](#7-incremental-ai-roadmap)
8. [File Watcher Decision: WatchService vs PathWatcher](#8-file-watcher-decision-watchservice-vs-pathwatcher)
9. [Integration Architecture](#9-integration-architecture)
10. [Appendix: Skills Inventory](#10-appendix-skills-inventory)

---

## 1. Methodology

### 1.1 What Was Analyzed

| Source | Count | Purpose |
|--------|-------|---------|
| Claude Code skills (~/.claude/skills/) | 120+ files | Proven workflow patterns, validated metrics |
| Synthesis Java source files | 83 classes | Current capabilities, extension points |
| Synthesis test files | 54 files | Test coverage, quality gates |
| Existing analyzer implementations | 5 analyzers | Media handling baseline |

### 1.2 Skills Studied in Depth

The following skills were analyzed in detail for their media handling, extraction, and linking patterns:

| Skill | Domain | Key Pattern |
|-------|--------|-------------|
| `video-integration-workflow.yaml` | Video processing | Campaign batching (5.5x speedup) |
| `batch-transcription-whisper.yaml` | Speech-to-text | Parallel transcription (16x realtime) |
| `pdf-vision-analysis-comprehensive.yaml` | PDF analysis | Vision-based metadata (200-1500 line docs) |
| `screenshot-integration-batch.yaml` | Image processing | Batch vision ($0.005/image, 92% accuracy) |
| `integrate-assets.yaml` | Asset management | MD5 dedup, multi-format analysis |
| `organize-media-by-concept.yaml` | Organization | Concept-based grouping, timestamp matching |
| `extract-presentation-slides.yaml` | PDF slides | pdftoppm extraction, semantic renaming |
| `knowledge-infrastructure-management.yaml` | Infrastructure | Multi-company knowledge graphs |
| `content-seed-capture.yaml` | Content | Seed recognition, cross-referencing |
| `repository-documentation-extraction.yaml` | Extraction | "Extract 0.2%, support 100% of business" |

### 1.3 Analysis Approach

The analysis follows the principle: **"What patterns have been proven in manual Claude Code sessions that could be automated inside Synthesis?"**

Each pattern was evaluated on:
- **Validation level:** How many times has this pattern been used successfully?
- **Automation potential:** Can this run without human judgment?
- **Air-gap compatibility:** Does it work without cloud/AI dependencies?
- **Integration complexity:** How much Synthesis code needs to change?

---

## 2. Media File Handling Patterns

### 2.1 Video Processing Pipeline (Proven)

**Source:** `video-integration-workflow.yaml`, `batch-transcription-whisper.yaml`

The validated video processing pipeline has four stages:

```
Stage 1: Metadata Extraction (deterministic, no AI)
  Input:  video.mp4
  Tool:   metadata-extractor (Java) or ffprobe (external)
  Output: duration, resolution, codec, framerate, creation date
  Speed:  Instantaneous per file

Stage 2: Transcription (local AI, optional)
  Input:  video.mp4
  Tool:   Whisper (local model, "base" recommended for batch)
  Output: video.txt (companion transcript)
  Speed:  16x realtime (1 hour video = ~3.75 minutes)
  Note:   Idempotent -- skips if transcript already exists

Stage 3: Categorization (deterministic, no AI)
  Input:  filename + metadata + transcript
  Tool:   Pattern matching, keyword extraction
  Output: category tags, duration classification, format tags
  Speed:  Instantaneous

Stage 4: Integration (deterministic, no AI)
  Input:  metadata + transcript + category
  Tool:   Template-based generation
  Output: video.synthesis.md (companion descriptive file)
  Speed:  Instantaneous
```

**Key metric from skills:** Campaign-batched processing (grouping related videos by series/campaign before processing) reduced per-video integration time from **12 minutes to 2.2 minutes** (5.5x improvement). This was validated on 106 videos (38 + 68 in two batches).

**Synthesis integration point:** `VideoAnalyzer.java` already implements Stage 1 with a two-tier strategy (metadata-extractor primary, ffprobe fallback). The companion transcript detection in `TRANSCRIPT_EXTENSIONS` already handles Stage 2 output. What is missing is **automated Stage 2 triggering** and **Stage 4 companion file generation**.

### 2.2 Image Processing Pipeline (Proven)

**Source:** `screenshot-integration-batch.yaml`, `integrate-assets.yaml`

```
Stage 1: Metadata Extraction (deterministic, no AI)
  Input:  image.png
  Tool:   metadata-extractor (Java, EXIF/IPTC)
  Output: dimensions, camera info, GPS, IPTC keywords, creation date
  Speed:  Instantaneous per file

Stage 2: Classification (deterministic, no AI)
  Input:  dimensions + file size + metadata
  Tool:   Heuristic rules
  Output: type classification (icon/thumbnail/screenshot/photo/banner/diagram)
  Speed:  Instantaneous

Stage 3: Vision Analysis (cloud AI, optional)
  Input:  image file (base64 encoded)
  Tool:   Claude Vision API
  Output: AI-generated description, detected text, content analysis
  Cost:   ~$0.005 per image (validated)
  Accuracy: 92% (validated on 589+ screenshots)

Stage 4: Integration (deterministic, no AI)
  Input:  metadata + classification + vision description
  Tool:   Template-based generation
  Output: image.synthesis.md (companion descriptive file)
  Speed:  Instantaneous
```

**Key insight from skills:** The screenshot batch processing skill uses a **checkpoint system** for resume capability and a **Phase 0 test sample** (5-10 images) to validate the pipeline before processing hundreds. This pattern should be replicated in Synthesis for any AI-powered batch operations.

**Synthesis integration point:** `ImageAnalyzer.java` already implements Stages 1-2. Line 33 in the source even notes: *"When combined with Claude Vision (Phase 5), images also get AI-generated descriptions."* The architecture was designed for this extension. What is missing is the Vision API integration and companion file generation.

### 2.3 PDF Processing Pipeline (Proven)

**Source:** `pdf-vision-analysis-comprehensive.yaml`, `extract-presentation-slides.yaml`

```
Stage 1: Text Extraction (deterministic, no AI)
  Input:  document.pdf
  Tool:   Apache PDFBox (Java, already in Synthesis)
  Output: text content (up to 50K chars from 100 pages)
  Speed:  Fast (< 1 second for most PDFs)

Stage 2: Media Type Detection (deterministic, no AI)
  Input:  page orientation + text density + creator tool + page count
  Tool:   Heuristic scoring (5 signals)
  Output: "presentation" | "document" | "spreadsheet"
  Speed:  Instantaneous

Stage 3: Slide Extraction (local tool, optional)
  Input:  presentation.pdf (detected as presentation)
  Tool:   pdftoppm (poppler-utils, 300 DPI PNG output)
  Output: slide-01.png through slide-N.png
  Speed:  ~2 seconds per slide
  Note:   Only for presentations, not for all PDFs

Stage 4: Vision Analysis (cloud AI, optional)
  Input:  extracted slides or PDF pages
  Tool:   Claude Vision API
  Output: Per-slide content descriptions, key points, speaker notes
  Cost:   ~$0.02 per page (validated)
  Result: 200-1500 line metadata documents (validated)

Stage 5: Integration (deterministic, no AI)
  Input:  text content + type detection + optional vision analysis
  Tool:   Template-based generation
  Output: document.synthesis.md (companion descriptive file)
  Speed:  Instantaneous
```

**Key insight from skills:** The `pdf-vision-analysis-comprehensive` skill produces **200-1500 line analysis documents** per PDF when Claude Vision is used. This level of enrichment transforms PDFs from opaque binary blobs into fully searchable, cross-referenceable knowledge assets. For the `extract-presentation-slides` skill, the validated efficiency is **35-40 minutes for a 15-slide presentation** (3x faster than manual), producing semantic file names and a comprehensive README.

**Synthesis integration point:** `PdfAnalyzer.java` already implements Stages 1-2. The `ExtractSlidesCommand.java` exists for manual slide extraction. What is missing is **automatic presentation detection triggering slide extraction** and **optional Vision-based enrichment**.

### 2.4 Audio Processing Pipeline (Partially Proven)

```
Stage 1: Metadata Extraction (deterministic, no AI)
  Input:  audio.mp3
  Tool:   metadata-extractor (Java) or ffprobe
  Output: duration, bitrate, sample rate, channels, codec
  Speed:  Instantaneous

Stage 2: Transcription (local AI, optional)
  Input:  audio.mp3
  Tool:   Whisper (same as video, but faster since no video decoding)
  Output: audio.txt (companion transcript)
  Speed:  16x realtime

Stage 3: Integration (deterministic, no AI)
  Input:  metadata + transcript
  Tool:   Template-based generation
  Output: audio.synthesis.md (companion descriptive file)
  Speed:  Instantaneous
```

**Synthesis integration point:** `VideoAnalyzer.java` already handles audio files (the `canAnalyze` method checks for both `VIDEO` and `AUDIO` file types). The companion transcript detection already works for audio files. The gap is the same as video: automated transcription triggering and companion file generation.

---

## 3. Text and Image Extraction Patterns

### 3.1 Companion File Pattern (Core Innovation)

**Source:** `integrate-assets.yaml`, `organize-media-by-concept.yaml`

The most impactful pattern discovered across all skills is the **companion descriptive file** pattern. For every non-text file (image, video, PDF, audio), a companion `.synthesis.md` file is generated alongside it:

```
project/
  assets/
    demo-video.mp4              # Binary file (not text-searchable)
    demo-video.synthesis.md     # Generated: metadata + transcript + description
    architecture.png            # Binary file
    architecture.synthesis.md   # Generated: dimensions + AI description + context
    slides.pdf                  # Binary file
    slides.synthesis.md         # Generated: text extract + slide descriptions
```

**Why this matters:** Synthesis's Lucene index can only search text content. Binary files (images, videos, PDFs) are currently indexed by metadata only (filename, size, format, duration). The companion file pattern makes **every binary file fully text-searchable** by generating a rich descriptive text file that gets indexed alongside it.

**Three companion file templates (from skills):**

**Template 1: Minimal (deterministic, no AI)**
```markdown
# demo-video.mp4

**Type:** Video (MP4)
**Duration:** 5m 23s
**Resolution:** 1920x1080
**Size:** 45.2 MB
**Created:** 2026-01-15

## Keywords
video, mp4, medium-video, 1080p

## Transcript
[Content of companion .txt/.srt file if exists]
```

**Template 2: Standard (local tools, optional AI)**
```markdown
# demo-video.mp4

**Type:** Video (MP4)
**Duration:** 5m 23s
**Resolution:** 1920x1080
**Size:** 45.2 MB
**Created:** 2026-01-15
**Transcription:** Whisper (base model)

## Summary
[First 200 chars of transcript]

## Keywords
video, mp4, medium-video, 1080p, has-transcript, [extracted-topic-keywords]

## Full Transcript
[Complete Whisper transcription]

## Related Files
- [slides.pdf](slides.pdf) -- Presented during this video
- [notes.md](../meeting-notes/2026-01-15.md) -- Meeting notes from same date
```

**Template 3: Rich (cloud AI)**
```markdown
# demo-video.mp4

**Type:** Video (MP4)
**Duration:** 5m 23s
**Resolution:** 1920x1080
**Size:** 45.2 MB
**Created:** 2026-01-15
**Analysis:** Claude Vision + Whisper

## AI Summary
[Claude-generated summary of video content]

## Key Topics
[AI-extracted topics with timestamps]

## Visual Description
[Description of key visual elements, diagrams shown, code displayed]

## Transcript
[Complete Whisper transcription with timestamps]

## Context
[AI-inferred relationships to other workspace files]

## Related Files
- [slides.pdf](slides.pdf) -- Slides shown at 2:15-4:30
- [code.java](../src/Main.java) -- Code displayed at 1:45
```

### 3.2 Text Extraction Strategies by Format

| Format | Current Synthesis | Skills-Proven Enhancement | AI Tier |
|--------|------------------|--------------------------|---------|
| **PDF** | PDFBox text (50K chars, 100 pages) | Slide extraction (pdftoppm) + per-slide Vision analysis | Phase 2-3 |
| **Images** | EXIF/IPTC metadata, dimension classification | Vision API descriptions ($0.005/img), OCR for text-in-images | Phase 3 |
| **Video** | metadata-extractor + ffprobe metadata | Whisper transcription (16x realtime), keyframe extraction | Phase 2 |
| **Audio** | Same as video (duration, codec) | Whisper transcription, speaker diarization | Phase 2 |
| **SVG** | XML text parsing, viewBox extraction | Already excellent -- SVG is text-searchable | No change |
| **Office** | Not yet supported | PDFBox (convert to PDF first) or Apache POI | Phase 1-2 |

### 3.3 Incremental Enrichment (Key Design Principle)

**Source:** `batch-transcription-whisper.yaml` (idempotent processing pattern)

A critical design principle from the skills: **enrichment is always incremental and idempotent.**

```
Rule 1: Never re-process a file that already has a companion file
  - Check: Does {basename}.synthesis.md exist?
  - If yes: Skip (unless --force flag)
  - If no: Generate

Rule 2: Enrichment tiers are independent
  - Deterministic enrichment can run without AI
  - Local tool enrichment can run without cloud
  - Cloud AI enrichment is always optional

Rule 3: Partial enrichment is valid
  - A companion file with only metadata is valid
  - Later enrichment passes add sections (not replace)
  - Version tracking: `## Enrichment History` section at bottom

Rule 4: Campaign batching for efficiency
  - Group related files before processing
  - Shared context reduces redundant work
  - 5.5x speedup validated on video integration
```

### 3.4 Duplicate Detection (Proven Pattern)

**Source:** `integrate-assets.yaml`

Before any enrichment, the skills run MD5 duplicate detection:

```
Step 1: Hash all files in the target scope
Step 2: Group by hash (identical content)
Step 3: For duplicates, keep one canonical copy
Step 4: Generate cross-reference entries for duplicates
```

Synthesis already has hash computation (`ScanConfig.computeHashes = true`). The gap is using hashes for **deduplication during enrichment** -- avoiding redundant companion file generation for identical content in different locations.

---

## 4. Context-Oriented Linking Patterns

### 4.1 Current Relationship Tracking in Synthesis

`GraphBuilder.java` and `InsightsEngine.java` currently detect three types of references:

| Pattern | Regex | Example |
|---------|-------|---------|
| Java imports | `^import\s+([\w.]+);` | `import com.example.Foo;` |
| Markdown links | `\[([^\]]*)\]\(([^)]+)\)` | `[link](file.md)` |
| Generic file refs | `(['"\x60])([\w./-]+\.ext)` | `"config.yaml"` |

These are **syntactic references** -- they detect explicit mentions of other files. The skills reveal several additional reference types that Synthesis should detect.

### 4.2 Enhanced Reference Detection (From Skills)

**Source:** `knowledge-infrastructure-management.yaml`, `organize-media-by-concept.yaml`

#### 4.2.1 Temporal Co-occurrence

Files created within the same time window are likely related:

```
Pattern: Files modified within +-24 hours of each other
Signal:  Weak relationship (weight: 0.3)
Example: meeting-notes-2026-01-15.md <-> recording-2026-01-15.mp4

Pattern: Files modified within +-1 hour of each other
Signal:  Strong relationship (weight: 0.7)
Example: screenshot-1234.png <-> bug-report.md (both at 14:32)
```

**Implementation:** During indexing, store `lastModified` timestamps. Post-index analysis groups files by time clusters. This is fully deterministic -- no AI required.

#### 4.2.2 Naming Convention Relationships

Files that share base names are related:

```
Pattern: Same base name, different extensions
Signal:  Very strong relationship (weight: 0.9)
Example: presentation.pdf <-> presentation.pptx <-> presentation.synthesis.md

Pattern: Same base name with suffixes
Signal:  Strong relationship (weight: 0.7)
Example: design.png <-> design-v2.png <-> design-final.png

Pattern: Same directory prefix
Signal:  Moderate relationship (weight: 0.5)
Example: feature-auth-login.java <-> feature-auth-logout.java
```

**Implementation:** String matching on `FileMetadata.fileName()`. This is already partially implemented in `VideoAnalyzer.getBaseName()` for companion transcript detection.

#### 4.2.3 Directory Structure Relationships

**Source:** `repository-documentation-extraction.yaml`

```
Pattern: Files in the same directory
Signal:  Moderate relationship (weight: 0.4)

Pattern: Files in parent-child directories
Signal:  Weak relationship (weight: 0.2)

Pattern: README.md → all files in same directory
Signal:  Documentation relationship (weight: 0.6)

Pattern: test/ → src/ mirror structure
Signal:  Test-implementation relationship (weight: 0.8)
Example: src/Foo.java <-> test/FooTest.java
```

**Implementation:** Path-based analysis during post-index phase. The `InsightsEngine.java` already computes directory-level metrics (`filesPerDirectory`). Extending this to track cross-directory relationships is straightforward.

#### 4.2.4 Content Similarity Relationships

**Source:** `content-seed-capture.yaml`

```
Pattern: Shared keywords (TF-IDF or exact match)
Signal:  Variable (weight based on keyword rarity)

Pattern: Shared headings (Markdown H1/H2)
Signal:  Strong topical relationship (weight: 0.6)

Pattern: Shared code symbols (class names, function names)
Signal:  Implementation relationship (weight: 0.7)
```

**Implementation:** Lucene already stores indexed content. Post-index analysis can use Lucene's `MoreLikeThis` query to find content-similar documents. This is deterministic (no AI) but requires Lucene API usage.

#### 4.2.5 Cross-Repository Dependency Relationships

Synthesis already has `CrossRepoDepsCommand.java` for cross-repo dependency mapping. The skills reveal additional cross-repo patterns:

```
Pattern: Shared Maven/npm/pip dependency
Signal:  Technical relationship (weight: 0.4)

Pattern: Same configuration key used
Signal:  Configuration relationship (weight: 0.5)

Pattern: API client → API server (URL matching)
Signal:  Integration relationship (weight: 0.8)
```

### 4.3 Bidirectional Cross-Reference Pattern (Key Innovation)

**Source:** `video-integration-workflow.yaml` (Phase 4: Cross-referencing)

The most valuable linking pattern from the skills is **bidirectional cross-referencing**. When a relationship is detected, **both files get updated**:

```
# In meeting-notes.synthesis.md:
## Related Files
- [recording.mp4](recording.mp4) -- Video recording of this meeting

# In recording.synthesis.md:
## Related Files
- [meeting-notes.md](meeting-notes.md) -- Written notes from this session
```

**Why bidirectional matters:** Unidirectional references (A mentions B) are already tracked by Synthesis. But when searching from B, you cannot find A. Bidirectional references mean that from ANY file in a relationship, you can discover ALL related files.

**Implementation in Synthesis:** The companion `.synthesis.md` files provide the vehicle for bidirectional references. When a relationship is detected between two files, both companion files get a `## Related Files` section update. This uses Synthesis's existing index to discover relationships, then writes back to companion files, which then get re-indexed -- creating a **self-reinforcing knowledge graph**.

### 4.4 Concept-Based Organization (Advanced Pattern)

**Source:** `organize-media-by-concept.yaml`

Beyond file-to-file relationships, the skills demonstrate **concept-based grouping**:

```
Traditional: Organize by file type
  images/ → all images
  videos/ → all videos
  docs/   → all documents

Concept-based: Organize by business purpose
  onboarding/
    welcome-video.mp4
    welcome-video.synthesis.md
    getting-started.md
    setup-screenshot.png
    setup-screenshot.synthesis.md
  architecture/
    system-diagram.png
    system-diagram.synthesis.md
    architecture-doc.md
    component-overview.pdf
    component-overview.synthesis.md
```

**Implementation:** Synthesis can generate concept clusters using:
1. Co-occurrence analysis (files frequently accessed together)
2. Shared keyword extraction (files about the same topic)
3. Temporal clustering (files created in the same work session)

This is a Phase 2-3 feature that builds on the basic relationship infrastructure from Phase 1.

---

## 5. Current Synthesis Capabilities (Baseline)

### 5.1 Analyzer Coverage

| Analyzer | File Types | Extraction Level | AI Required |
|----------|-----------|------------------|-------------|
| `VideoAnalyzer` | MP4, AVI, MOV, MKV, WebM, MP3, WAV, FLAC, OGG, AAC | Metadata (duration, resolution) + companion transcripts | No |
| `ImageAnalyzer` | PNG, JPG, JPEG, GIF, BMP, SVG, WebP, TIFF | EXIF/IPTC metadata, dimension classification | No |
| `PdfAnalyzer` | PDF | Text extraction (50K chars), media type detection | No |
| `CodeAnalyzer` | Java, Python, JS, TS, Go, Rust, etc. | Structure (classes, functions, imports) | No |
| `DefaultAnalyzer` | Markdown, YAML, JSON, XML, etc. | Text content, heading extraction | No |

### 5.2 Relationship Tracking

| Capability | Status | Location |
|------------|--------|----------|
| Java import graph | Working | `GraphBuilder.java` |
| Markdown link graph | Working | `GraphBuilder.java` |
| Generic file references | Working | `GraphBuilder.java` |
| Cross-repo dependencies | Working | `CrossRepoDepsCommand.java` |
| Temporal co-occurrence | Not implemented | -- |
| Naming convention relationships | Partial (companion transcripts) | `VideoAnalyzer.java` |
| Content similarity | Not implemented | -- |
| Bidirectional cross-references | Not implemented | -- |

### 5.3 AI Features (Current)

| Feature | Status | Class |
|---------|--------|-------|
| AI-powered Q&A | Working | `AskCommand.java` → `ClaudeClient.java` |
| Multi-perspective analysis | Working | `PerspectivesCommand.java` → `ClaudeClient.java` |
| AI code analysis | Working | `AnalyzeCommand.java` |
| AI-enhanced scan | Working | `ScanCommand.java` (optional) |
| Vision analysis | Not in Synthesis (in skills only) | -- |
| Whisper transcription | Not in Synthesis (in skills only) | -- |
| Companion file generation | Not in Synthesis (in skills only) | -- |

### 5.4 Watch Mode (Current)

| Capability | Status | Location |
|------------|--------|----------|
| File system monitoring | Working | `WatchCommand.java` → `WatchService` |
| Recursive directory registration | Working | `registerDirectories()` |
| Event debouncing | Working (300ms default) | `watchLoop()` |
| Incremental indexing | Working | `processChanges()` |
| Skill regeneration | Working (--learn flag) | `regenerateSkills()` |
| Background/daemon mode | Not implemented | -- |
| PID file management | Not implemented | -- |
| Shared index access (NRT) | Not implemented | -- |

---

## 6. Gap Analysis: Skills vs Synthesis

### 6.1 High-Impact Gaps (Address in Phase 1)

| Gap | Skills Evidence | Synthesis Impact | Effort |
|-----|----------------|------------------|--------|
| **Companion file generation** | All media skills use `.synthesis.md` pattern | Makes all binary files fully searchable | 2-3 weeks |
| **Temporal relationship detection** | `organize-media-by-concept` (timestamp matching) | 30-50% more relationships discovered | 1 week |
| **Naming convention relationships** | `integrate-assets` (base name matching) | 10-20% more relationships discovered | 3 days |
| **Bidirectional cross-references** | `video-integration-workflow` (Phase 4) | Transforms unidirectional graph to bidirectional | 1-2 weeks |
| **Incremental enrichment framework** | `batch-transcription-whisper` (idempotent) | Foundation for all future enrichment | 1 week |

### 6.2 Medium-Impact Gaps (Address in Phase 2)

| Gap | Skills Evidence | Synthesis Impact | Effort |
|-----|----------------|------------------|--------|
| **Whisper transcription integration** | `batch-transcription-whisper` (16x realtime) | Makes videos/audio fully searchable | 2-3 weeks |
| **PDF slide extraction** | `extract-presentation-slides` (pdftoppm) | Presentations become visual assets | 1-2 weeks |
| **Duplicate detection during enrichment** | `integrate-assets` (MD5 dedup) | Avoids redundant processing | 3 days |
| **Campaign batch processing** | `video-integration-workflow` (5.5x speedup) | Efficient bulk enrichment | 1 week |
| **Content similarity (MoreLikeThis)** | `content-seed-capture` (shared keywords) | Semantic relationships without AI | 1 week |

### 6.3 Lower-Priority Gaps (Address in Phase 3)

| Gap | Skills Evidence | Synthesis Impact | Effort |
|-----|----------------|------------------|--------|
| **Claude Vision for images** | `screenshot-integration-batch` (92% accuracy) | AI descriptions for images | 2-3 weeks |
| **Claude Vision for PDFs** | `pdf-vision-analysis-comprehensive` (200-1500 line docs) | Rich PDF analysis | 2-3 weeks |
| **AI-generated summaries** | `knowledge-infrastructure-management` | Auto-summarize any file | 1-2 weeks |
| **Concept clustering** | `organize-media-by-concept` (concept-based org) | Smart file grouping | 2-3 weeks |
| **Semantic search** | Not in current skills | Natural language queries | 4-6 weeks |

---

## 7. Incremental AI Roadmap

### 7.1 Phase 1: Deterministic Intelligence (4-6 weeks)

**Goal:** Make binary files fully searchable without any AI dependency.
**Works in:** Air-gapped mode (Core edition), all editions.
**Dependencies:** None beyond current Synthesis.

#### 7.1.1 Companion File Generator

A new `CompanionFileGenerator` class that creates `.synthesis.md` files for all indexed non-text files:

```java
// New class: io.exoreaction.synthesis.enrichment.CompanionFileGenerator
public class CompanionFileGenerator {

    /**
     * Generates a .synthesis.md companion file for a media file.
     * Uses only deterministic metadata -- no AI calls.
     *
     * @param metadata    The indexed file metadata
     * @param analysis    The analysis result from the appropriate analyzer
     * @param relatedFiles List of related files (from relationship detection)
     * @return Path to the generated companion file, or empty if not applicable
     */
    public Optional<Path> generate(FileMetadata metadata, AnalysisResult analysis,
                                    List<RelatedFile> relatedFiles) {
        // Only generate for non-text files
        if (isTextFile(metadata)) return Optional.empty();

        // Check if companion file already exists (idempotent)
        Path companionPath = companionPathFor(metadata.path());
        if (Files.exists(companionPath) && !forceRegenerate) {
            return Optional.empty();
        }

        // Generate from template based on file type
        String content = switch (metadata.fileType()) {
            case VIDEO, AUDIO -> generateMediaCompanion(metadata, analysis, relatedFiles);
            case IMAGE -> generateImageCompanion(metadata, analysis, relatedFiles);
            case PDF -> generatePdfCompanion(metadata, analysis, relatedFiles);
            default -> generateDefaultCompanion(metadata, analysis, relatedFiles);
        };

        Files.writeString(companionPath, content);
        return Optional.of(companionPath);
    }

    static Path companionPathFor(Path originalFile) {
        String baseName = originalFile.getFileName().toString();
        return originalFile.getParent().resolve(baseName + ".synthesis.md");
    }
}
```

**Impact:** Every image, video, PDF, and audio file becomes fully text-searchable through its companion file. The companion files are auto-generated, auto-indexed, and auto-maintained.

#### 7.1.2 Enhanced Relationship Detector

Extend `GraphBuilder.java` and `InsightsEngine.java` with additional relationship types:

```java
// New class: io.exoreaction.synthesis.graph.RelationshipDetector
public class RelationshipDetector {

    // Existing: syntactic references (imports, links, file refs)
    // NEW: temporal co-occurrence
    public List<Relationship> detectTemporalRelationships(List<SearchResult> files) {
        // Group files modified within +- 1 hour
        // Return weak relationships (weight 0.3-0.7)
    }

    // NEW: naming convention relationships
    public List<Relationship> detectNamingRelationships(List<SearchResult> files) {
        // Match base names across extensions
        // Match versioned files (v1, v2, final)
        // Match test <-> implementation pairs
    }

    // NEW: directory structure relationships
    public List<Relationship> detectStructuralRelationships(List<SearchResult> files) {
        // README -> all files in directory
        // test/ -> src/ mirror
        // config -> code that references it
    }
}
```

#### 7.1.3 Content Fingerprinting

Use Lucene's existing capabilities for content-based similarity:

```java
// New enrichment in post-index analysis
public class ContentFingerprinter {

    /**
     * Finds content-similar documents using Lucene's MoreLikeThis.
     * No AI required -- uses TF-IDF similarity from the existing index.
     */
    public List<SimilarDocument> findSimilar(String documentId, SearchIndex index) {
        MoreLikeThis mlt = new MoreLikeThis(index.getReader());
        mlt.setMinTermFreq(2);
        mlt.setMinDocFreq(3);
        mlt.setFieldNames(new String[]{"content", "keywords", "headings"});
        return mlt.like(documentId);
    }
}
```

#### 7.1.4 Enrichment Command

A new `synthesis enrich` command that runs companion file generation:

```
synthesis enrich                    # Generate companion files for all media files
synthesis enrich --force            # Regenerate even if companion files exist
synthesis enrich --type video       # Only for video files
synthesis enrich --dry-run          # Show what would be generated
synthesis enrich --stats            # Show enrichment coverage statistics
```

### 7.2 Phase 2: Local Media Enrichment (6-8 weeks)

**Goal:** Leverage local tools (Whisper, ffprobe, pdftoppm) for richer enrichment.
**Works in:** Pro edition and above. No cloud dependency.
**Dependencies:** Optional local binaries (Whisper, poppler-utils).

#### 7.2.1 Whisper Integration

```java
// New class: io.exoreaction.synthesis.enrichment.WhisperTranscriber
public class WhisperTranscriber {

    /**
     * Transcribes audio/video to text using local Whisper model.
     *
     * Design decisions (from skills analysis):
     * - Model: "base" recommended for batch (best speed/quality tradeoff)
     * - Output: .txt companion file (not .synthesis.md -- raw transcript)
     * - Idempotent: Skips if transcript already exists
     * - Background: Runs as a daemon task, non-blocking
     */
    public Optional<Path> transcribe(Path mediaFile) {
        Path transcriptPath = companionTranscriptPath(mediaFile);

        // Idempotent check
        if (Files.exists(transcriptPath)) return Optional.of(transcriptPath);

        // Detect Whisper availability
        if (!WhisperDetector.isAvailable()) return Optional.empty();

        // Run Whisper (process builder, timeout, output capture)
        ProcessBuilder pb = new ProcessBuilder(
            WhisperDetector.getWhisperPath(),
            mediaFile.toString(),
            "--model", "base",
            "--output_format", "txt",
            "--output_dir", mediaFile.getParent().toString()
        );
        // ... execution and error handling
    }
}
```

**Speed:** 16x realtime (validated). A 1-hour video transcribes in ~3.75 minutes. A full workspace with 100 videos (average 10 minutes each) would take ~62.5 minutes for complete transcription.

#### 7.2.2 PDF Slide Extractor

```java
// Enhanced: io.exoreaction.synthesis.enrichment.SlideExtractor
public class SlideExtractor {

    /**
     * Extracts slides from presentation PDFs using pdftoppm.
     * Only activates for PDFs detected as presentations by PdfAnalyzer.
     *
     * Design decisions (from skills analysis):
     * - Resolution: 300 DPI (optimal for readability without excessive size)
     * - Format: PNG (lossless, good for text-heavy slides)
     * - Naming: slide-01.png through slide-NN.png
     * - Selective: Only for presentations (score >= 4 in PdfAnalyzer)
     */
    public List<Path> extractSlides(Path pdfPath, AnalysisResult analysis) {
        // Check if this PDF was detected as a presentation
        if (!"presentation".equals(analysis.metrics().get("mediaType"))) {
            return Collections.emptyList();
        }

        // Check pdftoppm availability
        if (!PdftoppmDetector.isAvailable()) return Collections.emptyList();

        // Create output directory
        Path slideDir = pdfPath.getParent().resolve(
            getBaseName(pdfPath.getFileName().toString()) + "-slides");
        Files.createDirectories(slideDir);

        // Extract slides
        ProcessBuilder pb = new ProcessBuilder(
            "pdftoppm", "-png", "-r", "300",
            pdfPath.toString(),
            slideDir.resolve("slide").toString()
        );
        // ... execution
    }
}
```

#### 7.2.3 Campaign Batch Processing

```java
// New class: io.exoreaction.synthesis.enrichment.BatchProcessor
public class BatchProcessor {

    /**
     * Processes enrichment tasks in campaign batches for efficiency.
     * Groups related files before processing to leverage shared context.
     *
     * Design decisions (from skills analysis):
     * - Grouping: By directory, then by timestamp, then by naming convention
     * - Checkpoint: Write progress file for resume capability
     * - Phase 0: Test sample (5 files) before full batch
     * - Parallel: Use ForkJoinPool for CPU-bound tasks (Whisper, pdftoppm)
     */
    public BatchResult process(List<Path> files, EnrichmentLevel level) {
        // Group into campaigns
        List<Campaign> campaigns = groupIntoCampaigns(files);

        // Phase 0: Test sample
        if (campaigns.stream().mapToInt(c -> c.files().size()).sum() > 20) {
            Campaign testSample = campaigns.get(0).subset(5);
            BatchResult testResult = processCampaign(testSample, level);
            if (testResult.failureRate() > 0.2) {
                return BatchResult.aborted("Phase 0 test failed: " +
                    testResult.failureRate() * 100 + "% failure rate");
            }
        }

        // Process campaigns with checkpointing
        // ...
    }
}
```

### 7.3 Phase 3: Semantic Intelligence (4-6 weeks)

**Goal:** Add cloud AI capabilities for the richest possible enrichment.
**Works in:** Full (Pro/Ultimate) editions only. Requires API key.
**Dependencies:** Claude API access (ANTHROPIC_API_KEY).

#### 7.3.1 Vision Analysis for Images

```java
// New class: io.exoreaction.synthesis.enrichment.VisionAnalyzer
public class VisionAnalyzer {

    /**
     * Analyzes images using Claude Vision API.
     *
     * Design decisions (from skills analysis):
     * - Cost: ~$0.005 per image (validated on 589+ images)
     * - Accuracy: 92% (validated)
     * - Batch: Process in groups of 10-20 for efficiency
     * - Confirmation: Require user confirmation before batch (cost estimate)
     * - Max size: 20 MB per image (configurable)
     */
    public VisionResult analyze(Path imagePath) {
        // Check AI config
        if (!config.getAi().isEnabled() || !config.getAi().getVision().isEnabled()) {
            return VisionResult.skipped("Vision not enabled");
        }

        // Size check
        if (Files.size(imagePath) > config.getAi().getVision().getMaxImageSizeBytes()) {
            return VisionResult.skipped("Image too large");
        }

        // Encode and send to Claude Vision
        byte[] imageData = Files.readAllBytes(imagePath);
        String base64 = Base64.getEncoder().encodeToString(imageData);

        // Use existing ClaudeClient with vision-capable model
        String description = claudeClient.analyzeImage(base64,
            "Describe this image in detail. Include: visual elements, " +
            "any text visible, diagrams/charts, and the likely purpose.");

        return VisionResult.success(description);
    }
}
```

**SynthesisConfig extension for Vision:**

The existing `VisionConfig` class in `SynthesisConfig.java` already has the right structure:
- `enabled` (default: true when AI enabled)
- `costPerImageUsd` (default: $0.02 -- can lower to $0.005 based on skills validation)
- `maxImageSizeBytes` (default: 20 MB)
- `confirmBeforeScan` (default: true)

#### 7.3.2 Vision Analysis for PDFs

```java
// Enhancement to existing PdfAnalyzer or new VisionPdfAnalyzer
public class VisionPdfAnalyzer {

    /**
     * Analyzes PDF pages using Claude Vision for rich content understanding.
     * Particularly valuable for presentation slides (image-heavy, low text).
     *
     * Design decisions (from skills analysis):
     * - Cost: ~$0.02 per page (validated)
     * - Output: 200-1500 line analysis documents (validated)
     * - Selective: Only for presentations or PDFs with low text density
     * - Per-page: Analyze each page/slide independently
     * - Combined: Merge per-page analysis into single companion file
     */
    public VisionPdfResult analyze(Path pdfPath, List<Path> extractedSlides) {
        // Use extracted slides if available (better quality than page renders)
        // Fall back to PDFBox rendering if slides not extracted
        // Send each slide/page to Claude Vision
        // Combine results into structured companion file
    }
}
```

#### 7.3.3 AI-Generated Summaries

```java
// Enhancement for the enrich command
public class AiSummarizer {

    /**
     * Generates AI summaries for indexed files.
     * Uses the existing ClaudeClient and index content.
     *
     * Strategy: Send first 4000 chars of file content to Claude
     * with a structured prompt asking for:
     * - One-line summary
     * - Key topics (5-10 keywords)
     * - Target audience
     * - Related concepts
     */
    public AiSummary summarize(FileMetadata metadata, String contentPreview) {
        String prompt = String.format("""
            Summarize this %s file in a structured format:

            File: %s
            Content:
            %s

            Respond with:
            1. One-line summary (max 100 chars)
            2. Key topics (comma-separated, 5-10)
            3. Target audience
            4. Related concepts
            """, metadata.fileType(), metadata.fileName(),
            contentPreview.substring(0, Math.min(4000, contentPreview.length())));

        return claudeClient.call(prompt);
    }
}
```

### 7.4 Phase Summary

```
Phase 1: Deterministic Intelligence (Q2 2026)
  ├── Companion file generator (.synthesis.md)
  ├── Enhanced relationship detection (temporal, naming, structural)
  ├── Content fingerprinting (Lucene MoreLikeThis)
  ├── Bidirectional cross-references
  ├── `synthesis enrich` command
  └── Works in ALL editions (including air-gapped)

Phase 2: Local Media Enrichment (Q3 2026)
  ├── Whisper transcription integration
  ├── PDF slide extraction (pdftoppm)
  ├── Campaign batch processing
  ├── Duplicate detection during enrichment
  ├── Content similarity search
  ├── `synthesis enrich --transcribe` flag
  └── Works in Pro/Enterprise/Ultimate (local tools required)

Phase 3: Semantic Intelligence (Q4 2026)
  ├── Claude Vision for images
  ├── Claude Vision for PDF slides
  ├── AI-generated summaries
  ├── Concept clustering
  ├── Semantic search (embedding-based)
  ├── `synthesis enrich --ai` flag
  └── Works in Pro/Ultimate only (API key required)
```

---

## 8. File Watcher Decision: WatchService vs PathWatcher

### 8.1 Decision: Java WatchService (Recommended)

**Decision:** Continue using Java's `WatchService` API. Do not add PathWatcher dependency.

**Rationale:**

| Factor | WatchService | PathWatcher (Cantara) |
|--------|-------------|----------------------|
| **Multi-directory support** | Yes (recursive registration) | No (single directory only) |
| **Already implemented** | Yes (WatchCommand.java, 388 lines) | No (would require new integration) |
| **File-completion detection** | No (must implement debouncing) | Yes (built-in) |
| **External dependency** | None (JDK standard) | Yes (Cantara JAR + transitive deps) |
| **Platform behavior** | Platform-dependent but well-understood | Same (wraps OS-level APIs) |
| **Air-gapped compatibility** | Yes (JDK only) | Yes (but adds JAR size) |
| **Community/support** | JDK standard, widely documented | Cantara project, limited docs |
| **Synthesis workspace pattern** | Works well (recursive workspaces) | Would need multi-instance or restructure |

**Key factors for this decision:**

1. **Multi-directory is essential.** Synthesis workspaces are typically multi-repository, multi-directory structures. PathWatcher's single-directory limitation would require running multiple PathWatcher instances or restructuring the watch architecture. WatchService handles this natively.

2. **Already implemented and tested.** `WatchCommand.java` has 388 lines of working WatchService code including recursive registration, event debouncing (300ms configurable), incremental indexing, and graceful shutdown. Replacing this with PathWatcher would be net negative in the short term.

3. **Debouncing solves the file-completion problem.** PathWatcher's file-completion detection is valuable, but the current 300ms debounce in `WatchCommand` effectively handles the same problem for most use cases. For large file copies (videos), we can add a size-stability check (compare file size at two time points).

4. **No additional dependency.** The air-gapped edition cannot have unnecessary dependencies. WatchService is part of the JDK.

### 8.2 Enhancements to Current WatchService Implementation

To close the gap with PathWatcher's file-completion detection, add:

```java
/**
 * Enhanced file-completion check for large files.
 * Waits for file size to stabilize before processing.
 * Addresses the main advantage of PathWatcher.
 */
private boolean isFileComplete(Path file) throws IOException {
    long size1 = Files.size(file);
    Thread.sleep(200); // Brief pause
    long size2 = Files.size(file);
    return size1 == size2 && size2 > 0;
}
```

### 8.3 Future Consideration

If PathWatcher adds multi-directory support in a future release, reconsider. The abstraction boundary is clean: `WatchCommand.watchLoop()` could be refactored to accept a `FileChangeSource` interface, allowing either WatchService or PathWatcher as the backend.

---

## 9. Integration Architecture

### 9.1 New Package Structure

```
io.exoreaction.synthesis/
  enrichment/                    # NEW: Enrichment subsystem
    CompanionFileGenerator.java  # Phase 1: .synthesis.md generation
    EnrichmentLevel.java         # Enum: BASIC, LOCAL, AI
    EnrichmentResult.java        # Result record
    BatchProcessor.java          # Phase 2: Campaign batching
    WhisperTranscriber.java      # Phase 2: Whisper integration
    WhisperDetector.java         # Phase 2: Whisper availability check
    SlideExtractor.java          # Phase 2: pdftoppm integration
    PdftoppmDetector.java        # Phase 2: pdftoppm availability check
    VisionAnalyzer.java          # Phase 3: Claude Vision for images
    VisionPdfAnalyzer.java       # Phase 3: Claude Vision for PDFs
    AiSummarizer.java            # Phase 3: AI summaries
  graph/
    RelationshipDetector.java    # NEW: Enhanced relationship detection
    ContentFingerprinter.java    # NEW: Lucene MoreLikeThis similarity
  cli/
    EnrichCommand.java           # NEW: `synthesis enrich` command
```

### 9.2 Edition Gating

The enrichment subsystem respects the edition boundaries:

```java
public enum EnrichmentLevel {
    /** Deterministic only. No external tools, no AI. Works in Core edition. */
    BASIC,

    /** Deterministic + local tools (Whisper, pdftoppm, ffprobe). */
    LOCAL,

    /** Full AI enrichment (Vision, summaries, semantic). Requires API key. */
    AI;

    public static EnrichmentLevel forEdition(String edition) {
        return switch (edition) {
            case "core" -> BASIC;
            case "pro" -> AI;      // Pro has all capabilities
            case "enterprise" -> BASIC; // Enterprise is air-gapped
            case "ultimate" -> AI;
            default -> LOCAL;
        };
    }

    public static EnrichmentLevel maxAvailable() {
        if (SynthesisApp.isAirGapped()) return BASIC;
        if (ClaudeClient.isAvailable()) return AI;
        if (WhisperDetector.isAvailable() || PdftoppmDetector.isAvailable()) return LOCAL;
        return BASIC;
    }
}
```

### 9.3 Watch Mode Integration

The enrichment subsystem integrates with watch mode to provide **real-time enrichment**:

```java
// In WatchCommand.processChanges(), after indexing:
if (enrichmentEnabled) {
    for (Path file : newOrModifiedFiles) {
        CompanionFileGenerator generator = new CompanionFileGenerator(enrichmentLevel);
        Optional<Path> companion = generator.generate(metadata, analysis, relatedFiles);
        if (companion.isPresent()) {
            // Index the newly generated companion file
            index.addDocument(fileIndexer.createDocument(
                FileMetadata.of(companion.get(), workspaceRoot, ...),
                analyzers.analyze(companion.get())));
        }
    }
}
```

### 9.4 Configuration Extension

Add enrichment configuration to `SynthesisConfig`:

```yaml
# synthesis-config.yaml
enrichment:
  enabled: true
  level: auto          # auto | basic | local | ai
  companion-files:
    enabled: true
    gitignore: true    # Add .synthesis.md to .gitignore
    regenerate: false  # Don't overwrite existing companion files
  whisper:
    enabled: auto      # auto-detect whisper availability
    model: base        # base | small | medium | large
  slides:
    enabled: auto      # auto-detect pdftoppm availability
    dpi: 300
  vision:
    enabled: true      # Requires AI to be enabled
    confirm-before-batch: true
    max-image-size-mb: 20
    cost-per-image-usd: 0.005
```

### 9.5 .gitignore Integration

Companion files should be gitignored by default (they are generated artifacts):

```
# .gitignore (auto-added by synthesis enrich)
*.synthesis.md
*-slides/
```

The `synthesis enrich` command should offer to add these patterns to `.gitignore` on first run. Users who want companion files in version control can opt out.

### 9.6 Telemetry Integration

Enrichment operations should be tracked (building on existing `TelemetryService`):

```java
// New telemetry events for enrichment
telemetry.reportEnrichment(
    enrichmentLevel,        // BASIC, LOCAL, AI
    filesProcessed,         // How many files were enriched
    companionFilesGenerated,// How many .synthesis.md files created
    transcriptionsRun,      // How many Whisper runs
    visionAnalyses,         // How many Vision API calls
    durationMs,             // Total enrichment time
    estimatedCostUsd        // Estimated AI cost (for AI tier)
);
```

---

## 10. Appendix: Skills Inventory

### 10.1 Media Handling Skills

| Skill | Version | Validated On | Key Metric |
|-------|---------|-------------|------------|
| `video-integration-workflow` | -- | 106 videos | 12 min -> 2.2 min per video (campaign batching) |
| `batch-transcription-whisper` | -- | 38 videos | 16x realtime, 100% success rate |
| `pdf-vision-analysis-comprehensive` | -- | Strategic PDFs | 200-1500 line analysis per PDF |
| `screenshot-integration-batch` | -- | 589+ screenshots | $0.005/image, 92% accuracy |
| `extract-presentation-slides` | -- | 44 slides (3 presentations) | 35-40 min for 15 slides (3x faster) |

### 10.2 Knowledge Infrastructure Skills

| Skill | Version | Key Pattern |
|-------|---------|-------------|
| `integrate-assets` | -- | MD5 dedup, multi-format analysis, 3 companion templates |
| `organize-media-by-concept` | -- | Concept-based grouping, timestamp matching, 27 files validated |
| `knowledge-infrastructure-management` | 1.5.0 | Multi-company knowledge graphs, 5-phase automation |
| `content-seed-capture` | -- | Seed recognition, methodology linking, status tracking |
| `repository-documentation-extraction` | -- | "Extract 0.2%, support 100% of business" (48 from 25,823 files) |

### 10.3 Synthesis-Specific Skills

| Skill | Version | Purpose |
|-------|---------|---------|
| `synthesis-product-context` | 1.0.0 | Product knowledge, validated metrics |
| `synthesis-context` | -- | Technical architecture context |
| `synthesis-linkedin-campaign` | -- | 9-week content strategy |

---

## References

- [PRODUCT-VARIANTS-ROADMAP.md](./PRODUCT-VARIANTS-ROADMAP.md) -- Architecture decisions, build order, market analysis
- [VideoAnalyzer.java](../src/main/java/io/exoreaction/synthesis/analyzer/VideoAnalyzer.java) -- Current video/audio processing
- [ImageAnalyzer.java](../src/main/java/io/exoreaction/synthesis/analyzer/ImageAnalyzer.java) -- Current image processing
- [PdfAnalyzer.java](../src/main/java/io/exoreaction/synthesis/analyzer/PdfAnalyzer.java) -- Current PDF processing
- [WatchCommand.java](../src/main/java/io/exoreaction/synthesis/cli/WatchCommand.java) -- Current file watching
- [GraphBuilder.java](../src/main/java/io/exoreaction/synthesis/graph/GraphBuilder.java) -- Current relationship graph
- [InsightsEngine.java](../src/main/java/io/exoreaction/synthesis/insights/InsightsEngine.java) -- Current analysis engine
- [SynthesisConfig.java](../src/main/java/io/exoreaction/synthesis/config/SynthesisConfig.java) -- Current configuration

---

*Generated 2026-02-15. Analysis based on 120+ Claude Code skills and 83 Synthesis source files.*
