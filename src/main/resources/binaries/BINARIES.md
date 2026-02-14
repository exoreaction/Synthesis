# Bundled FFprobe Binary Manifest

**Download Date:** 2026-02-14
**Downloaded By:** `scripts/download-ffprobe-binaries.sh`

## Binaries

### Linux x64

| Field | Value |
|-------|-------|
| **File** | `linux-x64/ffprobe` |
| **Version** | 7.0.2-static |
| **Source** | https://johnvansickle.com/ffmpeg/ |
| **Download URL** | https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz |
| **Type** | Static build (no external dependencies) |
| **Architecture** | x86_64 (amd64) |
| **File Size** | 79,665,792 bytes (76 MB) |
| **SHA256** | `4f231a1960d83e403d08f7971e271707bec278a9ae18e21b8b5b03186668450d` |

### macOS (x86_64)

| Field | Value |
|-------|-------|
| **File** | `darwin-universal/ffprobe` |
| **Version** | 7.1.1-tessus |
| **Source** | https://evermeet.cx/ffmpeg/ |
| **Download URL** | https://evermeet.cx/ffmpeg/ffprobe-7.1.1.zip |
| **Type** | Mach-O 64-bit x86_64 executable |
| **Architecture** | x86_64 (works on Apple Silicon via Rosetta 2) |
| **File Size** | 79,521,376 bytes (76 MB) |
| **SHA256** | `f7928a29c68c15cad6ef95b759d40477e3c97a3e74ff8ca69412d09f5889e9f6` |

### Windows x64

| Field | Value |
|-------|-------|
| **File** | `windows-x64/ffprobe.exe` |
| **Version** | 8.0.1-essentials_build |
| **Source** | https://www.gyan.dev/ffmpeg/builds/ |
| **Download URL** | https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip |
| **Type** | PE32+ executable (console), x86-64 |
| **Architecture** | x86_64 (amd64) |
| **File Size** | 99,066,368 bytes (95 MB) |
| **SHA256** | `192a1d6899059765ac8c39764fc3148d4e6049955956dc2029f81f4bd6a8972d` |

## Total Size

| Platform | Size |
|----------|------|
| Linux x64 | 76 MB |
| macOS x86_64 | 76 MB |
| Windows x64 | 95 MB |
| **Total** | **247 MB** |

## Verification

To verify checksums after download:

```bash
sha256sum src/main/resources/binaries/linux-x64/ffprobe
sha256sum src/main/resources/binaries/darwin-universal/ffprobe
sha256sum src/main/resources/binaries/windows-x64/ffprobe.exe
```

To verify Linux binary works:

```bash
src/main/resources/binaries/linux-x64/ffprobe -version
```

## License

FFprobe is part of FFmpeg, licensed under LGPL 2.1+.
See `docs/LICENSE-FFMPEG.txt` for full license details.

## Notes

- These binaries are NOT committed to Git (listed in `.gitignore`)
- Re-download with: `./scripts/download-ffprobe-binaries.sh`
- The macOS binary is x86_64 only (not a universal binary); it runs on Apple Silicon via Rosetta 2
- Binary versions may differ across platforms as they come from different sources
