# Synthesis - Windows Installation Guide

Detailed installation instructions for Synthesis on Windows 10 and Windows 11.

## Prerequisites

### Required

| Requirement | Minimum | Recommended | Check Command |
|-------------|---------|-------------|---------------|
| **Windows** | Windows 10 (1809+) | Windows 11 | `winver` |
| **PowerShell** | 5.1 | 7.x | `$PSVersionTable.PSVersion` |
| **Java** | 17 | 21 (LTS) | `java -version` |

### Optional

| Tool | Purpose | Install |
|------|---------|---------|
| **Git** | Source builds, version control | `winget install Git.Git` |
| **Maven** | Source builds | `winget install Apache.Maven` |

## Step 1: Install Java 17+

If Java is not installed or is below version 17:

**Option A: Using winget (recommended)**
```powershell
winget install Microsoft.OpenJDK.17
```

**Option B: Using Adoptium/Temurin**
1. Visit https://adoptium.net/temurin/releases/
2. Download the `.msi` installer for Windows x64
3. Run the installer (check "Add to PATH" option)

**Option C: Using Amazon Corretto**
```powershell
winget install Amazon.Corretto.17
```

**Verify installation:**
```powershell
java -version
# Should show version 17 or higher
```

## Step 2: Configure PowerShell Execution Policy

PowerShell's default execution policy (`Restricted`) prevents running scripts. You need to change it once:

```powershell
# Check current policy
Get-ExecutionPolicy -Scope CurrentUser

# Set to RemoteSigned (allows local scripts, requires signatures for downloaded scripts)
Set-ExecutionPolicy RemoteSigned -Scope CurrentUser
```

**Understanding execution policies:**

| Policy | Local Scripts | Downloaded Scripts | Recommended |
|--------|--------------|-------------------|-------------|
| `Restricted` | Blocked | Blocked | No (default) |
| `AllSigned` | Signed only | Signed only | High security |
| `RemoteSigned` | Allowed | Signed only | Yes (recommended) |
| `Unrestricted` | Allowed | Allowed (with warning) | Development only |

**Note:** `RemoteSigned` is safe for normal use. It allows you to run scripts you create locally while requiring downloaded scripts to be signed.

## Step 3: Install Synthesis

### Option A: One-Command Install (recommended)

```powershell
iex (iwr -useb https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.ps1).Content
```

### Option B: Download and Run

```powershell
# Download the installer
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.ps1" -OutFile install.ps1

# Run it
.\install.ps1
```

### Option C: Install from Source

```powershell
# Clone the repository
git clone https://github.com/exoreaction/Synthesis.git
cd Synthesis

# Build (requires Maven)
mvn clean package -DskipTests

# Install from the built JAR
.\bin\install.ps1 -Source .
```

### Option D: Using Bypass (if execution policy cannot be changed)

```powershell
powershell -ExecutionPolicy Bypass -File install.ps1
```

### Installer Parameters

| Parameter | Description |
|-----------|-------------|
| `-Force` | Overwrite existing installation |
| `-Source <path>` | Use local source directory |
| `-NoPathUpdate` | Skip adding to User PATH |
| `-NoProfile` | Skip PowerShell profile modification |

## Step 4: Verify Installation

Open a **new** terminal (CMD or PowerShell) and run:

```powershell
synthesis --version
```

If the command is not found, verify PATH:
```powershell
# Check if .synthesis\bin is in PATH
$env:PATH -split ';' | Where-Object { $_ -like '*synthesis*' }

# Or run directly
& "$env:USERPROFILE\.synthesis\bin\synthesis.bat" --version
```

## Directory Structure

After installation, the following structure is created:

```
%USERPROFILE%\.synthesis\
    bin\
        synthesis.bat           # Main launcher (called from CMD/PowerShell)
        synthesis-update.bat    # Update convenience wrapper
        update.ps1              # PowerShell updater script
        uninstall.ps1           # PowerShell uninstaller
    lib\
        synthesis-1.0.0-SNAPSHOT.jar    # Versioned JAR
        current.jar                     # Hard link (or copy) to active version
    .metadata\
        version                 # Current version string
        install-date            # ISO 8601 installation timestamp
        last-update-check       # Unix timestamp of last update check
        os                      # "windows"
        link-method             # "hardlink" or "copy"
        source-dir              # Path to source (if installed from source)
```

## Updating

### Check for Updates

```powershell
synthesis-update -Check
```

### Update to Latest

```powershell
synthesis-update
```

### Update to Specific Version

```powershell
synthesis-update -Version "1.0.*"
```

### Force Update

```powershell
synthesis-update -Force
```

### Rollback

```powershell
synthesis-update -Rollback
```

### Update the Updater Itself

```powershell
synthesis-update -SelfUpdate
```

### Auto-Update Check

The launcher (`synthesis.bat`) performs a daily background check for updates using PowerShell. When an update is available, you will see a notification the next time you run `synthesis`.

Disable auto-update checks:
```powershell
# For current session
$env:SYNTHESIS_NO_UPDATE_CHECK = "1"

# Permanently (add to profile)
[Environment]::SetEnvironmentVariable("SYNTHESIS_NO_UPDATE_CHECK", "1", "User")
```

## Uninstalling

### Interactive Uninstall

```powershell
& "$env:USERPROFILE\.synthesis\bin\uninstall.ps1"
```

### Silent Uninstall

```powershell
& "$env:USERPROFILE\.synthesis\bin\uninstall.ps1" -Yes
```

### Full Removal (including Claude Code skills)

```powershell
& "$env:USERPROFILE\.synthesis\bin\uninstall.ps1" -Yes -RemoveSkills
```

### From Source Directory

```powershell
.\bin\uninstall.ps1
```

### What Gets Removed

- `%USERPROFILE%\.synthesis\` directory (JARs, scripts, metadata)
- User PATH entry for `.synthesis\bin`
- PowerShell profile entries (aliases, PATH)
- Optionally: Claude Code skills matching `*synthesis*`

### What Gets Preserved

- Workspace `.synthesis\` directories inside your projects (search indexes)
- System-wide Java installation
- Git configuration

## Troubleshooting

### "synthesis" is not recognized

**Cause:** PATH not updated in current terminal.

**Fix:** Open a new terminal window, or manually refresh:
```powershell
$env:PATH = [Environment]::GetEnvironmentVariable("PATH", "User") + ";" + [Environment]::GetEnvironmentVariable("PATH", "Machine")
```

### Script execution is disabled

**Cause:** PowerShell execution policy is `Restricted`.

**Fix:**
```powershell
Set-ExecutionPolicy RemoteSigned -Scope CurrentUser
```

### Java not found despite being installed

**Cause:** Java not on PATH, or installed for a different architecture.

**Fix:**
```powershell
# Check if java.exe exists anywhere
Get-ChildItem "C:\Program Files\*\*\bin\java.exe" -ErrorAction SilentlyContinue
Get-ChildItem "C:\Program Files (x86)\*\*\bin\java.exe" -ErrorAction SilentlyContinue

# Add to PATH manually
$javaHome = "C:\Program Files\Microsoft\jdk-17.0.x.y-hotspot"  # adjust path
[Environment]::SetEnvironmentVariable("JAVA_HOME", $javaHome, "User")
[Environment]::SetEnvironmentVariable("PATH", "$javaHome\bin;" + [Environment]::GetEnvironmentVariable("PATH", "User"), "User")
```

### Antivirus blocks download

**Cause:** Corporate antivirus may block PowerShell downloads.

**Fix:**
1. Add `github.com` and `mvnrepo.cantara.no` to allowlist
2. Or download manually and install from source:
   ```powershell
   # Download JAR manually via browser
   # Place at: %USERPROFILE%\.synthesis\lib\synthesis-1.0.0-SNAPSHOT.jar
   .\bin\install.ps1 -Source .
   ```

### Firewall blocks update checks

**Cause:** Corporate firewall blocking outbound HTTPS to GitHub API.

**Fix:**
```powershell
# Disable auto-update checks
$env:SYNTHESIS_NO_UPDATE_CHECK = "1"

# Update manually by downloading the JAR
Invoke-WebRequest -Uri "https://github.com/exoreaction/Synthesis/releases/latest/download/synthesis.jar" `
    -OutFile "$env:USERPROFILE\.synthesis\lib\synthesis-NEW.jar"
```

### Hard link creation fails

**Cause:** File system does not support hard links (network drives, some USB drives).

**Impact:** None. The installer automatically falls back to copying the JAR file. The metadata file `.metadata/link-method` records which method was used.

### "Access denied" during uninstall

**Cause:** A terminal or process is still using files in `.synthesis\`.

**Fix:**
1. Close all terminals running `synthesis`
2. Close any file manager windows showing `.synthesis\`
3. Retry the uninstall

### PowerShell profile not loading

**Cause:** Profile file location varies by PowerShell version.

**Fix:**
```powershell
# Check your profile path
$PROFILE

# Verify it exists
Test-Path $PROFILE

# Create if missing
if (-not (Test-Path $PROFILE)) {
    New-Item -ItemType File -Path $PROFILE -Force
}
```

Profile locations by PowerShell version:
- **PS 5.1:** `%USERPROFILE%\Documents\WindowsPowerShell\Microsoft.PowerShell_profile.ps1`
- **PS 7.x:** `%USERPROFILE%\Documents\PowerShell\Microsoft.PowerShell_profile.ps1`

## Using from CMD (Command Prompt)

Synthesis works from both CMD and PowerShell. The `synthesis.bat` launcher handles both:

```batch
REM CMD usage
synthesis init %USERPROFILE%\projects\my-project
synthesis scan
synthesis search "query"
synthesis --version
```

Updating from CMD:
```batch
synthesis-update
```

## Cross-Platform Notes

| Feature | Linux/macOS | Windows |
|---------|-------------|---------|
| Install directory | `~/.synthesis/` | `%USERPROFILE%\.synthesis\` |
| Launcher | `synthesis` (bash) | `synthesis.bat` (batch) |
| JAR link | Symlink (`ln -sf`) | Hard link or copy |
| PATH setup | Shell RC file | User PATH + PS profile |
| Update script | `update.sh` (bash) | `update.ps1` (PowerShell) |
| Uninstaller | `uninstall.sh` (bash) | `uninstall.ps1` (PowerShell) |
| Background check | `disown` (bash) | `start /b powershell` (batch) |
| Metadata OS | `linux` or `macos` | `windows` |

All features have full parity between platforms. The same acquisition strategies (GitHub releases, Cantara Maven, source build) are available on all platforms.

## Support

- **Issues:** https://github.com/exoreaction/Synthesis/issues
- **Source:** https://github.com/exoreaction/Synthesis

---

*Copyright (c) 2026 eXOReaction AS. All rights reserved.*
