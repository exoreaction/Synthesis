# Feature: `synthesis enrich` -- Companion File Generation

**Status:** Implemented (v1.0.4-SNAPSHOT)
**Priority:** P1 (Build Now)
**Effort:** 2 weeks (estimated) / completed
**Revenue Impact:** 50-100K NOK (SpareBank 1 binary search + Mynder GDPR docs)

> **Implementation Note (Feb 2026):** This feature is fully implemented with CLI command (`synthesis enrich`), MCP tool (`enrich`), and comprehensive tests (25 tests in `CompanionFileGeneratorTest`). All three enrichment levels (BASIC, LOCAL, AI) are supported. The MCP tool supports both single-file and batch mode.

---

## Problem Statement

Binary files (images, videos, PDFs, audio) represent 15-40% of typical workspace content but are invisible to text search. Synthesis indexes metadata (filename, size, format, duration) but cannot search content within binary files. A developer searching for "quarterly revenue chart" will never find `revenue-q3.png`.

**Impact:** Users lose trust in search completeness. Enterprise customers (SpareBank 1 with 200 devs) generate thousands of media assets. Mynder needs GDPR compliance documents searchable. This is the #1 gap customers notice in demos.

**Quantified pain:** 15-40% of workspace content is unsearchable. Users fall back to manual browsing or OS-level search, defeating the purpose of Synthesis.

## Solution Overview

Generate `.synthesis.md` companion files alongside every binary file, containing structured metadata, extracted text, and relationship data. These companion files are automatically indexed by standard scanning, making all binary content fully text-searchable.

## Architecture

```
Binary File (e.g., demo.mp4)
    |
    v
+------------------------+     Already exists
| Existing Analyzers     |     (VideoAnalyzer,
| (metadata extraction)  |      ImageAnalyzer,
+------------------------+      PdfAnalyzer)
    |
    v
+------------------------+     NEW
| CompanionFileGenerator |     Template-based
| (.synthesis.md)        |     .synthesis.md
+------------------------+     generation
    |
    v
+------------------------+
| demo.mp4.synthesis.md  |     Generated companion file
+------------------------+
    |
    v
+------------------------+     Already exists
| synthesis scan         |     Standard scan
| (Lucene indexing)      |     picks up .synthesis.md
+------------------------+
    |
    v
Search "demo video" -> finds demo.mp4 via companion
```

## API Design

### CLI
```
synthesis enrich                    # Generate companions for all binary files
synthesis enrich --force            # Regenerate even if companions exist
synthesis enrich --type video       # Only for video files
synthesis enrich --type image       # Only for image files
synthesis enrich --dry-run          # Show what would be generated
synthesis enrich --stats            # Show enrichment coverage statistics
synthesis enrich --verbose          # Detailed output per file
```

### Configuration (synthesis-config.yaml)
```yaml
enrichment:
  enabled: true
  level: auto          # auto | basic | local | ai
  companion-files:
    enabled: true
    gitignore: true    # Auto-add *.synthesis.md to .gitignore
    regenerate: false  # Don't overwrite existing
```

### MCP Tool (Implemented)
```json
{
  "name": "enrich",
  "description": "Generate .synthesis.md companion files for binary assets",
  "inputSchema": {
    "type": "object",
    "properties": {
      "filePath": { "type": "string", "description": "Path to specific file (omit for batch)" },
      "level": { "type": "string", "enum": ["basic", "local", "ai"], "default": "basic" },
      "force": { "type": "boolean", "default": false },
      "workspace": { "type": "string" }
    }
  }
}
```

## Implementation Details

### New Files
| File | Lines (est.) | Purpose |
|------|-------------|---------|
| `enrichment/CompanionFileGenerator.java` | ~300 | Template-based .synthesis.md generation |
| `enrichment/EnrichmentLevel.java` | ~60 | Enum: BASIC, LOCAL, AI |
| `enrichment/EnrichmentResult.java` | ~40 | Result record |
| `cli/EnrichCommand.java` | ~200 | CLI command |

### Modified Files
| File | Change |
|------|--------|
| `SynthesisConfig.java` | Add EnrichmentConfig inner class |
| `SynthesisApp.java` | Register EnrichCommand |

### Companion File Templates

**Video/Audio:**
```markdown
# demo-video.mp4

**Type:** Video (MP4)
**Duration:** 5m 23s
**Resolution:** 1920x1080
**Size:** 45.2 MB
**Modified:** 2026-01-15 14:32

## Keywords
video, mp4, medium-video, 1080p

## Transcript
Transcript available: [demo-video.txt](demo-video.txt)

## Related Files
- [slides.pdf](slides.pdf) -- Same directory
```

**Image:**
```markdown
# architecture.png

**Type:** Image (PNG)
**Dimensions:** 1920x1080
**Classification:** screenshot
**Size:** 234 KB
**Modified:** 2026-01-15 14:32

## Keywords
image, png, screenshot

## Related Files
- [architecture.md](architecture.md) -- Same base name
```

**PDF:**
```markdown
# report.pdf

**Type:** PDF
**Pages:** 24
**Media Type:** document
**Creator:** Microsoft Word
**Size:** 1.2 MB

## Content Preview
[First 2000 chars of extracted text]

## Headings
- Introduction
- Market Analysis
- Conclusions

## Keywords
pdf, document
```

## Testing Strategy

1. **Unit tests:** CompanionFileGenerator template output for each file type
2. **Integration tests:** Full enrich cycle (generate + scan + search finds binary)
3. **Idempotency tests:** Run enrich twice, verify no duplicate/corrupt files
4. **Edge cases:** Empty files, corrupted metadata, missing analyzers, very large files
5. **Stats tests:** Verify coverage calculation accuracy

## Rollout Plan

1. **Week 1:** CompanionFileGenerator + EnrichCommand (BASIC tier only)
2. **Week 2:** Integration with scan, stats, dry-run, config, testing
3. **Day 1 after merge:** Demo to SpareBank 1 ("search your PDFs and images")
4. **Follow-up:** Phase 2 adds Whisper transcription, Phase 3 adds Vision descriptions

## Dependencies

None beyond current Synthesis. This is a Phase 1 deterministic feature that works in all editions including air-gapped Core.
