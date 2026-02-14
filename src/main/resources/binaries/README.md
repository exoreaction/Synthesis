# Bundled FFprobe Binaries

This directory contains platform-specific ffprobe binaries that are bundled
into the JAR and extracted on first use.

## Directory Structure

```
binaries/
├── linux-x64/        # Linux x86_64 static binary
│   └── ffprobe
├── darwin-universal/  # macOS universal binary (Intel + Apple Silicon)
│   └── ffprobe
└── windows-x64/      # Windows x86_64 binary
    └── ffprobe.exe
```

## Downloading Binaries

Run the download script from the project root:

```bash
./scripts/download-ffprobe-binaries.sh
```

## Sources

- **Linux:** https://johnvansickle.com/ffmpeg/ (static builds)
- **macOS:** https://evermeet.cx/ffmpeg/ (universal builds)
- **Windows:** https://www.gyan.dev/ffmpeg/builds/ (release essentials)

## Important

- Binaries are NOT committed to Git (they are in .gitignore)
- Each binary is ~60-90 MB
- The download script fetches the latest release versions
- License: LGPL 2.1+ (see docs/LICENSE-FFMPEG.txt)
