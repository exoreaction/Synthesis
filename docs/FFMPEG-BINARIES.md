# Bundled FFprobe Binaries

## Overview

Synthesis bundles platform-specific ffprobe binaries to provide full video
format support without requiring users to install FFmpeg manually.

## Supported Platforms

| Platform | Architecture | Binary | Version | Size |
|----------|-------------|--------|---------|------|
| Linux | x86_64 (amd64) | `ffprobe` | 7.0.2-static | 76 MB |
| macOS | x86_64 (Rosetta 2 on Apple Silicon) | `ffprobe` | 7.1.1-tessus | 76 MB |
| Windows | x86_64 (amd64) | `ffprobe.exe` | 8.0.1-essentials | 95 MB |

## Binary Sources

### Linux x64
- **Source:** https://johnvansickle.com/ffmpeg/
- **Type:** Static build (no external dependencies)
- **Download:** `ffmpeg-release-amd64-static.tar.xz`
- **Extract:** `ffprobe` binary from the archive

### macOS x86_64
- **Source:** https://evermeet.cx/ffmpeg/
- **Type:** Mach-O 64-bit x86_64 (runs on Apple Silicon via Rosetta 2)
- **Download:** `ffprobe-7.1.1.zip`
- **Alternative:** Build from Homebrew (`brew install ffmpeg`)

### Windows x64
- **Source:** https://www.gyan.dev/ffmpeg/builds/
- **Type:** Release essentials (statically linked)
- **Download:** `ffmpeg-release-essentials.zip`
- **Extract:** `bin/ffprobe.exe` from the archive

## How It Works

1. **Build Time:** The download script (`scripts/download-ffprobe-binaries.sh`)
   fetches platform-specific binaries and places them in
   `src/main/resources/binaries/<platform>/`.

2. **JAR Packaging:** Maven includes the binaries in the JAR file during
   `mvn package`. The binaries are stored without filtering to preserve
   their binary content.

3. **First Use:** When `VideoAnalyzer` first needs ffprobe,
   `BundledBinaryManager` detects the current platform and extracts the
   appropriate binary from the JAR to `~/.synthesis/bin/ffprobe`.

4. **Subsequent Uses:** The extracted binary is cached on disk and reused
   across JVM restarts. No re-extraction needed.

5. **Fallback:** If the bundled binary is not available (e.g., unsupported
   platform or extraction failure), Synthesis falls back to the system
   PATH `ffprobe`.

## Extraction Location

```
~/.synthesis/bin/
├── ffprobe          # Extracted platform-specific binary (Linux/macOS)
└── ffprobe.exe      # Extracted platform-specific binary (Windows)
```

The extraction directory can be overridden with the system property:
```
-Dsynthesis.home=/custom/path
```

## Detection Priority

1. **Bundled binary** (`~/.synthesis/bin/ffprobe`) -- extracted from JAR
2. **System PATH** (`ffprobe`) -- user-installed FFmpeg
3. **Not available** -- graceful degradation, pure Java metadata extraction

## Downloading Binaries

```bash
# From the project root:
./scripts/download-ffprobe-binaries.sh
```

The script downloads binaries for all three platforms. It requires `curl`,
`tar`, and `unzip` to be available.

## Verifying Binaries

After downloading, verify each binary:

```bash
# Linux
src/main/resources/binaries/linux-x64/ffprobe -version

# macOS (on a Mac)
src/main/resources/binaries/darwin-universal/ffprobe -version

# Windows (on Windows or via Wine)
src/main/resources/binaries/windows-x64/ffprobe.exe -version
```

## Size Impact

| Component | Without Binaries | With Binaries | Increase |
|-----------|-----------------|---------------|----------|
| JAR size (compressed) | ~49 MB | ~136 MB | +87 MB |
| Linux binary (uncompressed) | - | 76 MB | +76 MB |
| macOS binary (uncompressed) | - | 76 MB | +76 MB |
| Windows binary (uncompressed) | - | 95 MB | +95 MB |
| Total uncompressed binaries | - | 247 MB | - |

Note: The JAR uses ZIP compression, which significantly reduces the binary sizes.
The actual JAR size increase is approximately 87 MB (not the full 247 MB).

## Current Binary Checksums

Last downloaded: 2026-02-14

| Platform | SHA256 |
|----------|--------|
| Linux x64 | `4f231a1960d83e403d08f7971e271707bec278a9ae18e21b8b5b03186668450d` |
| macOS x86_64 | `f7928a29c68c15cad6ef95b759d40477e3c97a3e74ff8ca69412d09f5889e9f6` |
| Windows x64 | `192a1d6899059765ac8c39764fc3148d4e6049955956dc2029f81f4bd6a8972d` |

Full binary manifest with download URLs: `src/main/resources/binaries/BINARIES.md`

## Status Display

The `synthesis status` command shows ffprobe detection:

```
External Tools:
  ffprobe: Bundled (FFmpeg 7.0.2)     # Using bundled binary
  ffprobe: Available (FFmpeg 6.1)      # Using system-installed ffprobe
  ffprobe: Not installed (optional)    # No ffprobe available
```

## License

FFprobe is part of FFmpeg, licensed under LGPL 2.1+.
See `LICENSE-FFMPEG.txt` for full license details.
