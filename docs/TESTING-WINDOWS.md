# Synthesis - Windows Testing Checklist

Manual testing checklist for validating Windows distribution scripts.

**Target environments:** Windows 10 (22H2+), Windows 11, PowerShell 5.1 and 7.x

---

## Test Environment Setup

Before testing, ensure a clean environment:

```powershell
# Remove any existing installation
Remove-Item "$env:USERPROFILE\.synthesis" -Recurse -Force -ErrorAction SilentlyContinue

# Remove PATH entry if present
$path = [Environment]::GetEnvironmentVariable("PATH", "User")
$path = ($path -split ';' | Where-Object { $_ -notlike '*\.synthesis*' }) -join ';'
[Environment]::SetEnvironmentVariable("PATH", $path, "User")

# Remove profile entries if present
if (Test-Path $PROFILE) {
    $content = Get-Content $PROFILE | Where-Object { $_ -notmatch 'synthesis' -and $_ -notmatch 'Synthesis' }
    Set-Content $PROFILE ($content -join "`n")
}
```

---

## 1. install.ps1 Tests

### 1.1 Prerequisites Check

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 1.1.1 | Java 21+ detected | Have Java 21+ installed, run `.\install.ps1` | `[INFO] Java NN found (>= 17 required)` | |
| 1.1.2 | Java too old | Set PATH to Java 11 only, run `.\install.ps1` | Error message with install instructions, exit 1 | |
| 1.1.3 | No Java | Remove Java from PATH, run `.\install.ps1` | Error message with install instructions, exit 1 | |
| 1.1.4 | Git detected | Have git installed | `[INFO] Git found (git version X.Y.Z)` | |
| 1.1.5 | No git | Remove git from PATH | `[WARN] Git not found...` (continues) | |
| 1.1.6 | Maven detected | Have mvn installed | `[INFO] Maven found (source builds enabled)` | |
| 1.1.7 | Execution policy warning | Set policy to Restricted | `[WARN] PowerShell execution policy is 'Restricted'` | |

### 1.2 Installation

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 1.2.1 | Fresh install | Clean env, run `.\install.ps1` | Directories created, JAR obtained, success message | |
| 1.2.2 | Directory structure | Check `%USERPROFILE%\.synthesis\` | Has `bin\`, `lib\`, `.metadata\` directories | |
| 1.2.3 | current.jar exists | Check `lib\current.jar` | File exists, same content as versioned JAR | |
| 1.2.4 | Metadata files | Check `.metadata\` | `version`, `install-date`, `os` (= "windows"), `link-method` | |
| 1.2.5 | Existing install blocked | Run `.\install.ps1` again | Error: "already installed", suggests `-Force` | |
| 1.2.6 | Force reinstall | Run `.\install.ps1 -Force` | Removes old, installs fresh | |
| 1.2.7 | Install from source | Build JAR, run `.\install.ps1 -Source .` | Copies built JAR, saves source-dir | |

### 1.3 JAR Acquisition Strategies

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 1.3.1 | GitHub release | If releases exist | Downloads from GitHub | |
| 1.3.2 | Cantara Maven | If releases exist | Downloads from Cantara | |
| 1.3.3 | Local source (pre-built) | `mvn package`, then install | Copies target/*.jar | |
| 1.3.4 | Local source (build) | No target/*.jar, has mvn | Runs `mvn package`, copies result | |
| 1.3.5 | Auto-detect source | JAR in `%USERPROFILE%\src\synthesis\target\` | Finds and copies automatically | |
| 1.3.6 | All fail | No internet, no source, no JAR | Clear error with options listed | |

### 1.4 Environment Setup

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 1.4.1 | PATH updated | Check User PATH after install | Contains `%USERPROFILE%\.synthesis\bin` | |
| 1.4.2 | -NoPathUpdate | Install with `-NoPathUpdate` | PATH not modified | |
| 1.4.3 | Profile updated | Check `$PROFILE` content | Contains marker, PATH, aliases | |
| 1.4.4 | -NoProfile | Install with `-NoProfile` | Profile not modified | |
| 1.4.5 | Profile created | Delete profile, install | New profile created with entries | |
| 1.4.6 | Idempotent profile | Install twice (with -Force) | Profile has entries only once | |

### 1.5 Cleanup on Failure

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 1.5.1 | Cleanup on JAR fail | Force JAR download to fail | `.synthesis\` directory removed | |
| 1.5.2 | No cleanup on existing | Have existing install, fail | Existing install preserved | |

---

## 2. synthesis.bat Tests

### 2.1 Pre-flight Checks

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 2.1.1 | Normal launch | `synthesis --version` | Prints version, exits 0 | |
| 2.1.2 | No JAR | Rename current.jar | Error: "JAR not found" | |
| 2.1.3 | No Java | Remove Java from PATH | Error: "Java not found" | |
| 2.1.4 | Java too old | Point to Java 11 | Error: "Java NN found, but 17+ required" | |
| 2.1.5 | Pass arguments | `synthesis search "test"` | Arguments forwarded to JAR | |
| 2.1.6 | Exit code forwarded | Run command that fails | synthesis.bat returns same exit code | |

### 2.2 From CMD

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 2.2.1 | CMD launch | Open CMD, run `synthesis --version` | Works correctly | |
| 2.2.2 | CMD with args | `synthesis init %USERPROFILE%\test` | Initializes workspace | |
| 2.2.3 | CMD update | `synthesis-update -Check` | Calls update.ps1 via wrapper | |

### 2.3 From PowerShell

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 2.3.1 | PS launch | `synthesis --version` | Works correctly | |
| 2.3.2 | PS alias | Check `Get-Alias synthesis` | Points to synthesis.bat | |
| 2.3.3 | PS with special chars | `synthesis search '"exact phrase"'` | Quotes preserved | |

### 2.4 Auto-Update Check

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 2.4.1 | Shows update | Place "Update available: X.Y.Z" in result file | Shows notification before launch | |
| 2.4.2 | Clears after show | Run synthesis after showing notification | Result file deleted, not shown again | |
| 2.4.3 | No update check env | Set `SYNTHESIS_NO_UPDATE_CHECK=1` | No update check performed | |
| 2.4.4 | Daily check trigger | Set last-update-check to old date | Background check triggered | |

### 2.5 Environment Variables

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 2.5.1 | SYNTHESIS_JAVA_OPTS | Set to "-Xmx512m", launch | JVM uses 512m max heap | |
| 2.5.2 | SYNTHESIS_HOME | Set to alternate path, install there | Uses alternate path | |

---

## 3. update.ps1 Tests

### 3.1 Version Check

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 3.1.1 | Check mode | `.\update.ps1 -Check` | Reports current version and available updates | |
| 3.1.2 | Already current | Current = latest | "Already up to date: X.Y.Z" | |
| 3.1.3 | Update available | Current < latest | "Update available: X.Y.Z (current: A.B.C)" | |
| 3.1.4 | Quiet mode | `.\update.ps1 -Check -Quiet` | Minimal output | |

### 3.2 Update Execution

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 3.2.1 | Normal update | `.\update.ps1` | Downloads new JAR, updates link, verifies | |
| 3.2.2 | Force update | `.\update.ps1 -Force` | Re-downloads even if current | |
| 3.2.3 | Version pattern | `.\update.ps1 -Version "1.0.*"` | Only matches 1.0.x versions | |
| 3.2.4 | Source build | No remote, has source dir | Builds from source, installs | |

### 3.3 Version Management

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 3.3.1 | Keeps 3 versions | Update 4 times | Only 3 JARs remain in lib\ | |
| 3.3.2 | Version file updated | After update | `.metadata\version` matches new version | |
| 3.3.3 | Last update recorded | After update | `.metadata\last-update` has timestamp | |

### 3.4 Integrity Verification

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 3.4.1 | Valid JAR check | Download valid JAR | Passes PK header check | |
| 3.4.2 | Invalid download | Replace download with HTML | Error: "not a valid JAR" | |
| 3.4.3 | SHA256 match | If .sha256 file exists | "SHA256 checksum verified" | |
| 3.4.4 | SHA256 mismatch | Corrupt JAR with sha256 available | Error with expected vs actual | |

### 3.5 Rollback

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 3.5.1 | Normal rollback | Update, then `.\update.ps1 -Rollback` | Reverts to previous version | |
| 3.5.2 | No previous version | Only one version installed, rollback | Error: "No previous version" | |
| 3.5.3 | Verification failure | Make new JAR invalid, update | Auto-rollback to previous | |

### 3.6 Self-Update

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 3.6.1 | Self-update | `.\update.ps1 -SelfUpdate` | Downloads new update.ps1 from GitHub | |
| 3.6.2 | Self-update fail | Block GitHub access, self-update | Error: "Failed to download" | |

### 3.7 Edge Cases

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 3.7.1 | Not installed | Remove .synthesis, run update | Error: "not installed" | |
| 3.7.2 | Process running | While synthesis runs, update | Warning about running process | |
| 3.7.3 | SNAPSHOT handling | Current = 1.0.0-SNAPSHOT, available = 1.0.0 | Treats release as newer | |
| 3.7.4 | Version comparison | Various version pairs | Correct ordering | |

---

## 4. uninstall.ps1 Tests

### 4.1 Survey

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 4.1.1 | Shows what will be removed | Run uninstall, do not confirm | Lists directory, size, PATH, profile entries | |
| 4.1.2 | Shows JAR count | Have 3 JARs | "lib\ (3 JAR files)" | |
| 4.1.3 | Shows skills | Have synthesis skills | Lists skill file paths | |

### 4.2 Confirmation

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 4.2.1 | Cancel uninstall | Answer "N" to prompt | "Uninstall cancelled" | |
| 4.2.2 | Confirm uninstall | Answer "Y" to prompt | Proceeds with removal | |
| 4.2.3 | Skip confirmation | `.\uninstall.ps1 -Yes` | No prompt, proceeds directly | |

### 4.3 Removal

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 4.3.1 | Directory removed | Uninstall | `.synthesis\` directory gone | |
| 4.3.2 | PATH cleaned | Check User PATH after uninstall | `.synthesis\bin` removed | |
| 4.3.3 | Profile cleaned | Check $PROFILE after uninstall | Synthesis entries removed | |
| 4.3.4 | Skills kept | Uninstall without `-RemoveSkills` | Skill files preserved | |
| 4.3.5 | Skills removed | Uninstall with `-RemoveSkills` | Skill files deleted | |
| 4.3.6 | -KeepData | Uninstall with `-KeepData` | Directory kept, env cleaned | |
| 4.3.7 | Workspace preserved | Have `.synthesis\` in a project | Project `.synthesis\` untouched | |

### 4.4 Edge Cases

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 4.4.1 | Not installed | Remove .synthesis, run uninstall | "not installed", exit 0 | |
| 4.4.2 | Partial install | Delete some files, run uninstall | Removes what exists, no errors | |
| 4.4.3 | Re-install after uninstall | Uninstall, then install | Clean install succeeds | |

---

## 5. Cross-Platform Parity Tests

| # | Feature | Linux/macOS | Windows | Parity |
|---|---------|-------------|---------|--------|
| 5.1 | Banner displayed | ASCII art | ASCII art | |
| 5.2 | Color output | ANSI codes | Write-Host colors | |
| 5.3 | GitHub acquisition | curl + API | Invoke-RestMethod | |
| 5.4 | Cantara acquisition | curl + XML | Invoke-WebRequest + XML | |
| 5.5 | Source build | mvn package | mvn package | |
| 5.6 | Auto-detect source | ~/src/synthesis | %USERPROFILE%\src\synthesis | |
| 5.7 | Version comparison | Bash function | PowerShell function | |
| 5.8 | SNAPSHOT handling | Pre-release | Pre-release | |
| 5.9 | JAR verification | xxd (PK header) | [File]::ReadAllBytes | |
| 5.10 | SHA256 verification | sha256sum/shasum | Get-FileHash | |
| 5.11 | Rollback | Symlink swap | Hard link/copy swap | |
| 5.12 | Old version cleanup | Keep 3 | Keep 3 | |
| 5.13 | Background update check | disown | start /b powershell | |
| 5.14 | Metadata stored | Same fields | Same fields + link-method | |
| 5.15 | Cleanup on failure | trap + rm -rf | trap + Remove-Item | |

---

## 6. PowerShell Version Tests

| # | Test | PS 5.1 | PS 7.x | Status |
|---|------|--------|--------|--------|
| 6.1 | install.ps1 runs | | | |
| 6.2 | update.ps1 runs | | | |
| 6.3 | uninstall.ps1 runs | | | |
| 6.4 | synthesis.bat from PS | | | |
| 6.5 | Profile path correct | | | |
| 6.6 | Invoke-WebRequest works | | | |
| 6.7 | Get-FileHash works | | | |
| 6.8 | Hard link creation | | | |

---

## 7. Windows-Specific Edge Cases

| # | Test | Steps | Expected | Status |
|---|------|-------|----------|--------|
| 7.1 | Spaces in USERPROFILE | User "John Doe" | All paths handle spaces correctly | |
| 7.2 | Long path support | Nested deep directories | Paths work (260 char limit aware) | |
| 7.3 | Network drive | Install on mapped drive | Graceful fallback (copy vs hardlink) | |
| 7.4 | Unicode in path | User with non-ASCII name | Paths handled correctly | |
| 7.5 | Windows Defender | Fresh download | No false positive blocking | |
| 7.6 | Corporate proxy | Behind corporate proxy | Download fails gracefully with message | |
| 7.7 | OneDrive Documents | Profile in OneDrive path | PS profile creation works | |

---

## Known Limitations

1. **No symlinks without admin:** Windows hard links are used instead. If hard links fail (e.g., on network drives), falls back to copy. This means updating requires replacing the copy rather than swapping a link.

2. **Background update check in CMD:** Uses `start /b powershell` which briefly flashes a window on some configurations. The update check is non-blocking.

3. **Execution policy:** Users must set execution policy before first run. The installer cannot change this for itself.

4. **Profile locations vary:** PowerShell 5.1 and 7.x use different profile paths. The installer uses `$PROFILE` which is correct for the running PowerShell version, but users switching between versions may need to configure both.

5. **CMD vs PowerShell aliases:** CMD uses `synthesis.bat` from PATH. PowerShell can also use the `synthesis` alias if profile is configured. Both work.

---

## Test Execution Record

| Date | Tester | OS | PS Version | Java | Result | Notes |
|------|--------|----|------------|------|--------|-------|
| | | | | | | |

---

*Copyright (c) 2026 eXOReaction AS. Licensed under the Apache License, Version 2.0.*
