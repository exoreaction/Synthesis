# Feature: Local Media Enrichment (Phase 2)

**Status:** ✅ Implemented (v1.2.0)
**Category:** Enrichment / LOCAL Tier
**Dependencies:** Optional (Whisper, Tesseract, Poppler)

## Overview

Phase 2 Local Media Enrichment enables **air-gapped, privacy-first** media processing through local tool integration. This feature adds speech-to-text transcription, OCR text extraction, and scanned PDF processing **without requiring cloud services or API keys**.

### Core Capabilities

1. **Audio/Video Transcription** (Whisper)
   - Convert speech to searchable text
   - Support 99 languages
   - Process MP3, WAV, M4A, OGG, FLAC, Opus, AAC

2. **Image Text Extraction** (Tesseract OCR)
   - Extract text from screenshots, diagrams, charts
   - Support 100+ languages
   - Process PNG, JPEG, TIFF, BMP, GIF, WebP

3. **Scanned PDF Processing** (Poppler + Tesseract)
   - Convert PDF pages to images
   - Run OCR on each page
   - Generate fully searchable .synthesis.md companions

## Architecture

### Enrichment Tier Model

```
┌──────────┬──────────────────────────────────────────┐
│  BASIC   │ Deterministic metadata only              │
│          │ Works everywhere, zero dependencies      │
├──────────┼──────────────────────────────────────────┤
│  LOCAL   │ BASIC + Local tools (Whisper, Tesseract)│ ← Phase 2
│          │ Air-gapped, privacy-first               │
├──────────┼──────────────────────────────────────────┤
│    AI    │ LOCAL + Cloud AI (Claude Vision)        │
│          │ Requires API key and network             │
└──────────┴──────────────────────────────────────────┘
```

### Detection Priority

The system automatically detects available tools and upgrades enrichment tier:

```java
// EnrichmentLevel.maxAvailable() logic:
if (airGapped) → BASIC
if (ANTHROPIC_API_KEY present) → AI
if (Whisper || Tesseract || pdftoppm present) → LOCAL
else → BASIC
```

### Component Architecture

```
┌─────────────────────────────────────────────────────┐
│             EnrichmentLevel.java                    │
│  (Detection + Tier Selection)                       │
└──────────────┬──────────────────────────────────────┘
               │
               ├─── WhisperDetector.java
               │    └── WhisperTranscriber.java
               │
               ├─── TesseractDetector.java
               │    └── TesseractOcrExtractor.java
               │
               └─── PdftoppmDetector.java
                    └── PdfToImageConverter.java
```

## Implementation Details

### 1. Whisper Integration (Speech-to-Text)

**Detection:** `WhisperDetector.java`
- Priority 1: whisper.cpp (preferred, 100x faster)
- Priority 2: OpenAI Whisper Python CLI
- Checks: `whisper --version` or `whisper --help`

**Transcription:** `WhisperTranscriber.java`
- Model: `tiny` (default, 39M params, ~1GB RAM)
- Options: `base`, `small`, `medium`, `large`
- Timeout: 5 minutes per file
- Output: Plain text transcript with language detection

**Supported Formats:**
- Audio: MP3, WAV, M4A, OGG, FLAC, Opus, AAC
- Video: Extracts audio track automatically

**Example Usage:**
```java
if (WhisperDetector.isAvailable()) {
    WhisperTranscriber transcriber = new WhisperTranscriber();
    TranscriptionResult result = transcriber.transcribe(audioPath);
    if (result.success()) {
        String transcript = result.text();
        String language = result.language();
    }
}
```

### 2. Tesseract Integration (OCR)

**Detection:** `TesseractDetector.java`
- Checks system PATH and standard install locations
- Linux: `/usr/bin/tesseract`, `/usr/local/bin/tesseract`
- macOS: `/opt/homebrew/bin/tesseract`
- Windows: `C:\Program Files\Tesseract-OCR\tesseract.exe`

**Extraction:** `TesseractOcrExtractor.java`
- Language: `eng` (default), supports 100+ languages
- PSM: Auto page segmentation (mode 3)
- OEM: LSTM engine (mode 1, fastest/most accurate)
- Confidence: Estimates 0-100 based on text quality

**Supported Formats:**
- PNG, JPEG, TIFF (best quality)
- BMP, GIF, WebP (also supported)

**Example Usage:**
```java
if (TesseractDetector.isAvailable()) {
    TesseractOcrExtractor extractor = new TesseractOcrExtractor();
    OcrResult result = extractor.extractText(imagePath);
    if (result.hasGoodConfidence()) {
        String text = result.text();
    }
}
```

### 3. PDF Support (Poppler + Tesseract)

**Detection:** `PdftoppmDetector.java`
- Checks for `pdftoppm` (Poppler utilities)
- Handles exit code 99 (version flag quirk)

**Conversion:** `PdfToImageConverter.java`
- DPI: 300 (default, standard OCR quality)
- Format: PNG (best for OCR)
- Naming: `filename-1.png`, `filename-2.png`, etc.
- Cleanup: Automatic deletion after OCR

**Workflow:**
1. Convert PDF pages to PNG images (300 DPI)
2. Run Tesseract OCR on each image
3. Combine text into single .synthesis.md
4. Clean up temporary images

**Example Usage:**
```java
if (PdftoppmDetector.isAvailable() && TesseractDetector.isAvailable()) {
    PdfToImageConverter converter = new PdfToImageConverter();
    List<Path> images = converter.convertToImages(pdfPath);

    TesseractOcrExtractor extractor = new TesseractOcrExtractor();
    for (Path image : images) {
        OcrResult result = extractor.extractText(image);
        // Aggregate results
    }

    PdfToImageConverter.cleanupImages(images);
}
```

## Installation

### Whisper (Speech-to-Text)

**Option 1: whisper.cpp (Recommended)**
```bash
# macOS
brew install whisper-cpp

# Linux (build from source)
git clone https://github.com/ggerganov/whisper.cpp.git
cd whisper.cpp
make
sudo cp main /usr/local/bin/whisper

# Download model (tiny = 75MB)
bash ./models/download-ggml-model.sh tiny
```

**Option 2: OpenAI Whisper (Python)**
```bash
pip install openai-whisper
```

### Tesseract OCR

**macOS:**
```bash
brew install tesseract
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt install tesseract-ocr
sudo apt install tesseract-ocr-eng  # English language data
```

**Linux (RHEL/CentOS):**
```bash
sudo yum install tesseract
```

**Windows:**
Download installer from: https://github.com/UB-Mannheim/tesseract/wiki

### Poppler (pdftoppm)

**macOS:**
```bash
brew install poppler
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt install poppler-utils
```

**Linux (RHEL/CentOS):**
```bash
sudo yum install poppler-utils
```

**Windows:**
Download from: https://github.com/oschwartz10612/poppler-windows/releases

## Testing

### Test Coverage

**Total Tests:** 43 tests across 6 test classes

1. **WhisperDetectorTest** (7 tests)
   - Format support detection
   - Install hints
   - Status display
   - Caching behavior
   - Version consistency

2. **WhisperTranscriberTest** (8 tests)
   - Transcriber creation
   - Error handling (missing file, tool not available)
   - Result structures (success, failed)
   - TranscriptionException
   - Integration test (ffmpeg + whisper)

3. **TesseractDetectorTest** (7 tests)
   - Format support detection
   - Install hints
   - Status display
   - Caching behavior
   - Version consistency
   - Data path detection

4. **TesseractOcrExtractorTest** (9 tests)
   - Extractor creation (default, custom language, custom PSM)
   - Error handling (missing file, tool not available)
   - Result structures (success, failed, low confidence)
   - Available languages detection
   - Integration test (ImageMagick + Tesseract)

5. **PdftoppmDetectorTest** (5 tests)
   - Install hints
   - Status display
   - Caching behavior
   - Version consistency
   - Path consistency

6. **PdfToImageConverterTest** (7 tests)
   - Converter creation
   - Error handling
   - Page count estimation
   - Image cleanup
   - ConversionException
   - Integration test (Ghostscript + pdftoppm)

### Running Tests

```bash
# Run all Phase 2 tests
mvn test -Dtest="Whisper*Test,Tesseract*Test,Pdftoppm*Test,PdfToImage*Test"

# Run specific test class
mvn test -Dtest=WhisperTranscriberTest

# Run with integration tests (requires tools installed)
mvn test -Dtest=WhisperTranscriberTest#testTranscribeIntegration
```

### Integration Test Requirements

Integration tests are conditionally enabled via `@EnabledIf`:

- **WhisperTranscriberTest.testTranscribeIntegration**
  - Requires: whisper + ffmpeg
  - Creates 1-second silent MP3, transcribes

- **TesseractOcrExtractorTest.testExtractTextIntegration**
  - Requires: tesseract + ImageMagick convert
  - Creates text image, performs OCR

- **PdfToImageConverterTest.testConvertToImagesIntegration**
  - Requires: pdftoppm + Ghostscript
  - Creates 1-page PDF, converts to PNG

## Error Handling

### Graceful Degradation

If LOCAL tools are not available, Synthesis automatically falls back to BASIC enrichment:

```
┌─────────────────────────────────────────────────────┐
│  Tool Missing? → Fallback to BASIC                  │
├─────────────────────────────────────────────────────┤
│  Audio file + no Whisper → Metadata only            │
│  Image + no Tesseract → EXIF/IPTC metadata only     │
│  Scanned PDF + no tools → PDFBox text extraction    │
└─────────────────────────────────────────────────────┘
```

### Clear Error Messages

All detectors provide actionable install hints:

```java
if (!WhisperDetector.isAvailable()) {
    System.err.println("Whisper not available.");
    System.err.println("Install with: " + WhisperDetector.getInstallHint());
}
```

Example output:
```
Whisper not available.
Install with: brew install whisper-cpp  # or: pip install openai-whisper
```

### Timeout Protection

- **Whisper transcription:** 5 minutes per file
- **Tesseract OCR:** No built-in timeout (Tesseract is fast)
- **PDF conversion:** 30 seconds per page

## Performance

### Benchmarks (Approximate)

**Whisper Transcription (tiny model):**
- 1 minute audio → ~5-10 seconds transcription
- 10 minute audio → ~30-60 seconds transcription
- whisper.cpp is ~100x faster than Python implementation

**Tesseract OCR:**
- Standard screenshot (1920×1080) → ~1-2 seconds
- High-res scan (300 DPI A4) → ~3-5 seconds
- Language data affects speed (eng is fastest)

**PDF Conversion (pdftoppm):**
- 1 page @ 300 DPI → ~1-2 seconds
- 10 pages @ 300 DPI → ~10-15 seconds
- 100 pages @ 300 DPI → ~2-3 minutes

### Memory Usage

- **whisper.cpp (tiny model):** ~1 GB RAM
- **Tesseract OCR:** ~100-200 MB RAM per process
- **pdftoppm:** ~50-100 MB RAM per page

## Use Cases

### 1. Engineering Teams

**Problem:** Meeting recordings, design review videos not searchable
**Solution:** Whisper transcribes audio → indexed by Lucene
**Benefit:** Find "that conversation about API design" in seconds

### 2. Documentation Teams

**Problem:** Screenshots, diagrams, architecture images not searchable
**Solution:** Tesseract extracts text from images → fully indexed
**Benefit:** Search by error message visible in screenshot

### 3. Legacy Document Migration

**Problem:** Scanned PDFs (old contracts, reports) not searchable
**Solution:** pdftoppm + Tesseract converts to searchable text
**Benefit:** Find clauses, terms, references across thousands of scanned docs

### 4. Air-Gapped Environments

**Problem:** Enterprise/government teams can't use cloud AI (Claude Vision)
**Solution:** LOCAL tier works offline with local tools
**Benefit:** Same enrichment power, zero cloud dependencies

## Privacy & Security

### Zero Cloud Dependencies

ALL Phase 2 processing is **100% local**:
- ✅ Audio transcription: Local Whisper models
- ✅ OCR processing: Local Tesseract engine
- ✅ PDF conversion: Local Poppler utilities
- ❌ NO data sent to Claude API
- ❌ NO data sent to OpenAI
- ❌ NO network calls

### Air-Gapped Compliance

Perfect for:
- Government agencies
- Financial institutions
- Healthcare organizations
- Enterprise environments with strict data policies

## Roadmap Integration

### Current Status (v1.2.0)

- ✅ Whisper integration (speech-to-text)
- ✅ Tesseract integration (OCR)
- ✅ Poppler integration (PDF → images)
- ✅ Comprehensive test coverage (43 tests)
- ✅ Documentation

### Future Enhancements (v1.3.0+)

- [ ] Video keyframe extraction (ffmpeg)
- [ ] Automatic language detection for OCR
- [ ] Batch processing optimization (parallel OCR)
- [ ] Progress reporting for long-running operations
- [ ] Model size selection (whisper: tiny/base/small)
- [ ] OCR confidence-based filtering

## Related Documentation

- [PRODUCT-VARIANTS-ROADMAP.md](https://github.com/exoreaction/Synthesis/blob/main/docs/PRODUCT-VARIANTS-ROADMAP.md) - Edition comparison
- [EnrichmentLevel.java](https://github.com/exoreaction/Synthesis/blob/main/src/main/java/io/exoreaction/synthesis/enrichment/EnrichmentLevel.java) - Architecture
- [CompanionFileGenerator.java](https://github.com/exoreaction/Synthesis/blob/main/src/main/java/io/exoreaction/synthesis/enrichment/CompanionFileGenerator.java) - Integration point

## FAQ

**Q: Do I need to install all three tools?**
A: No. Install only what you need. Even one tool upgrades you to LOCAL tier.

**Q: Which Whisper implementation is faster?**
A: whisper.cpp is ~100x faster than Python (C++ vs Python + PyTorch).

**Q: What happens if tools are missing?**
A: Automatic fallback to BASIC enrichment (metadata only). No errors.

**Q: Can I use custom Whisper models?**
A: Yes. Pass model name to `WhisperTranscriber("base")` constructor.

**Q: Does Tesseract support my language?**
A: Probably! 100+ languages supported. Install with `tesseract-ocr-<lang>`.

**Q: How much disk space do I need?**
A: Whisper tiny: 75MB, Tesseract: ~10MB per language, Poppler: ~50MB.

**Q: Are there bundled binaries?**
A: Not yet (Phase 3). Currently requires system installation.

---

**Implementation Date:** February 15, 2026
**Author:** Claude Sonnet 4.5
**Version:** 1.2.0-SNAPSHOT
