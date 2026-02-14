#Requires -Version 5.1
<#
.SYNOPSIS
    Synthesis Updater for Windows.

.DESCRIPTION
    Self-updating distribution script for Synthesis. Checks GitHub releases and Cantara Maven
    repository for updates, downloads and installs new versions, with rollback support.

.PARAMETER Check
    Check for updates without installing.

.PARAMETER Force
    Force update even if current version matches.

.PARAMETER Rollback
    Rollback to previous version.

.PARAMETER Version
    Specific version or pattern (e.g., "1.0.*", "1.1.0", "SNAPSHOT").

.PARAMETER Quiet
    Minimal output (for background checks).

.PARAMETER SelfUpdate
    Update this script itself from GitHub.

.EXAMPLE
    .\update.ps1                    # Update to latest
    .\update.ps1 -Check             # Check only
    .\update.ps1 -Rollback          # Rollback
    .\update.ps1 -Version "1.0.*"   # Specific version

.NOTES
    Copyright (c) 2026 eXOReaction AS. All rights reserved.
    Requires: Windows 10+, PowerShell 5.1+, Java 17+
#>

[CmdletBinding()]
param(
    [switch]$Check,
    [switch]$Force,
    [switch]$Rollback,
    [string]$Version,
    [switch]$Quiet,
    [switch]$SelfUpdate
)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
$ErrorActionPreference = 'Stop'

$SynthesisHome = if ($env:SYNTHESIS_HOME) { $env:SYNTHESIS_HOME } else { Join-Path $env:USERPROFILE ".synthesis" }
$GitHubRepo = "exoreaction/Synthesis"
$GitHubUrl = "https://github.com/$GitHubRepo"
$GitHubRaw = "https://raw.githubusercontent.com/$GitHubRepo/main"
$CantaraSnapshots = "https://mvnrepo.cantara.no/content/repositories/snapshots"
$CantaraReleases = "https://mvnrepo.cantara.no/content/repositories/releases"
$GroupPath = "io/exoreaction"
$ArtifactId = "synthesis"
$LibDir = Join-Path $SynthesisHome "lib"
$MetaDir = Join-Path $SynthesisHome ".metadata"

# ---------------------------------------------------------------------------
# Output Helpers
# ---------------------------------------------------------------------------
function Write-Info {
    param([string]$Message)
    if (-not $Quiet) { Write-Host "[INFO]  $Message" -ForegroundColor Green }
}
function Write-Warn {
    param([string]$Message)
    if (-not $Quiet) { Write-Host "[WARN]  $Message" -ForegroundColor Yellow }
}
function Write-Err {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}
function Write-Step {
    param([string]$Message)
    if (-not $Quiet) { Write-Host "==> $Message" -ForegroundColor Blue }
}
function Write-Detail {
    param([string]$Message)
    if (-not $Quiet) { Write-Host "    $Message" }
}

# ---------------------------------------------------------------------------
# Helper Functions
# ---------------------------------------------------------------------------
function Test-CommandExists {
    param([string]$Name)
    $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

function Invoke-Download {
    param(
        [string]$Url,
        [string]$OutFile
    )
    try {
        $ProgressPreference_Saved = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing -ErrorAction Stop
        $ProgressPreference = $ProgressPreference_Saved
        return $true
    } catch {
        $ProgressPreference = $ProgressPreference_Saved
        return $false
    }
}

function Test-UrlExists {
    param([string]$Url)
    try {
        $ProgressPreference_Saved = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'
        $response = Invoke-WebRequest -Uri $Url -Method Head -UseBasicParsing -ErrorAction Stop
        $ProgressPreference = $ProgressPreference_Saved
        return ($response.StatusCode -eq 200)
    } catch {
        $ProgressPreference = $ProgressPreference_Saved
        return $false
    }
}

function Compare-Versions {
    <#
    .SYNOPSIS
        Compare two version strings. Returns 1 if v1 > v2, 0 if equal, -1 if v1 < v2.
        Handles SNAPSHOT versions (treated as pre-release, lower than release).
    #>
    param(
        [string]$V1,
        [string]$V2
    )

    $v1IsSnap = $V1 -like '*-SNAPSHOT'
    $v2IsSnap = $V2 -like '*-SNAPSHOT'
    $v1Base = $V1 -replace '-SNAPSHOT$', ''
    $v2Base = $V2 -replace '-SNAPSHOT$', ''

    $v1Parts = $v1Base.Split('.') | ForEach-Object { [int]$_ }
    $v2Parts = $v2Base.Split('.') | ForEach-Object { [int]$_ }

    $maxLen = [Math]::Max($v1Parts.Count, $v2Parts.Count)
    for ($i = 0; $i -lt $maxLen; $i++) {
        $p1 = if ($i -lt $v1Parts.Count) { $v1Parts[$i] } else { 0 }
        $p2 = if ($i -lt $v2Parts.Count) { $v2Parts[$i] } else { 0 }

        if ($p1 -gt $p2) { return 1 }
        if ($p1 -lt $p2) { return -1 }
    }

    # Same base version - SNAPSHOT is lower than release
    if ($v1IsSnap -and -not $v2IsSnap) { return -1 }
    if (-not $v1IsSnap -and $v2IsSnap) { return 1 }

    return 0
}

function Test-VersionMatches {
    <#
    .SYNOPSIS
        Check if a version matches a glob pattern (e.g., "1.0.*" matches "1.0.0").
    #>
    param(
        [string]$VersionStr,
        [string]$Pattern
    )

    # Convert glob pattern to regex
    $regex = '^' + [regex]::Escape($Pattern).Replace('\*', '[0-9A-Za-z._-]*') + '$'
    return $VersionStr -match $regex
}

function Get-CurrentVersion {
    <#
    .SYNOPSIS
        Get the currently installed version.
    #>
    $versionFile = Join-Path $MetaDir "version"
    if (Test-Path $versionFile) {
        return (Get-Content $versionFile -ErrorAction SilentlyContinue).Trim()
    }

    # Try to extract from current.jar target
    $currentJar = Join-Path $LibDir "current.jar"
    if (Test-Path $currentJar) {
        $item = Get-Item $currentJar
        if ($item.Target) {
            $target = Split-Path $item.Target -Leaf
            return $target -replace '^synthesis-', '' -replace '\.jar$', ''
        }
    }
    return "unknown"
}

function Get-InstalledVersions {
    <#
    .SYNOPSIS
        Get list of installed versions from lib\ directory.
    #>
    $jars = Get-ChildItem (Join-Path $LibDir "synthesis-*.jar") -ErrorAction SilentlyContinue
    if (-not $jars) { return @() }

    $versions = $jars | ForEach-Object {
        $_.Name -replace '^synthesis-', '' -replace '\.jar$', ''
    } | Sort-Object { [version]($_ -replace '-SNAPSHOT$', '' -replace '-.*$', '') }

    return $versions
}

function Get-PreviousVersion {
    <#
    .SYNOPSIS
        Get the previous version (for rollback).
    #>
    $current = Get-CurrentVersion
    $versions = Get-InstalledVersions
    $previous = $versions | Where-Object { $_ -ne $current } | Select-Object -Last 1
    return $previous
}

function Test-SynthesisRunning {
    <#
    .SYNOPSIS
        Check if any Synthesis process is running.
    #>
    $processes = Get-Process java -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match 'synthesis.*\.jar' }
    return ($null -ne $processes -and $processes.Count -gt 0)
}

function Get-FileHash256 {
    <#
    .SYNOPSIS
        Compute SHA256 hash of a file.
    #>
    param([string]$FilePath)
    return (Get-FileHash $FilePath -Algorithm SHA256).Hash.ToLower()
}

function New-JarLink {
    <#
    .SYNOPSIS
        Create a link from current.jar to the target JAR.
    #>
    param(
        [string]$TargetJar,
        [string]$LinkPath
    )

    if (Test-Path $LinkPath) {
        Remove-Item $LinkPath -Force
    }

    try {
        New-Item -ItemType HardLink -Path $LinkPath -Target $TargetJar -ErrorAction Stop | Out-Null
        return "hardlink"
    } catch {
        Copy-Item $TargetJar $LinkPath -Force
        return "copy"
    }
}

# ---------------------------------------------------------------------------
# Update Strategies
# ---------------------------------------------------------------------------
function Find-GitHubRelease {
    <#
    .SYNOPSIS
        Check GitHub releases for latest version.
        Returns hashtable with Source, Version, Url or $null.
    #>
    try {
        $ProgressPreference_Saved = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'
        $apiResponse = Invoke-RestMethod -Uri "https://api.github.com/repos/$GitHubRepo/releases/latest" -UseBasicParsing -ErrorAction Stop
        $ProgressPreference = $ProgressPreference_Saved

        $tag = $apiResponse.tag_name -replace '^v', ''
        $asset = $apiResponse.assets | Where-Object { $_.name -match 'synthesis.*\.jar$' } | Select-Object -First 1

        if ($tag -and $asset) {
            return @{
                Source  = "github"
                Version = $tag
                Url     = $asset.browser_download_url
            }
        } elseif ($tag) {
            return @{
                Source  = "github"
                Version = $tag
                Url     = ""
            }
        }
    } catch {
        $ProgressPreference = $ProgressPreference_Saved
    }
    return $null
}

function Find-CantaraRelease {
    <#
    .SYNOPSIS
        Check Cantara Maven repository for latest version.
        Returns hashtable with Source, Version, Url or $null.
    #>

    # Try releases first
    $metaUrl = "$CantaraReleases/$GroupPath/$ArtifactId/maven-metadata.xml"
    try {
        $ProgressPreference_Saved = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'
        [xml]$metaXml = (Invoke-WebRequest -Uri $metaUrl -UseBasicParsing -ErrorAction Stop).Content
        $ProgressPreference = $ProgressPreference_Saved

        $latestRelease = $metaXml.metadata.versioning.release
        if ($latestRelease) {
            $jarUrl = "$CantaraReleases/$GroupPath/$ArtifactId/$latestRelease/synthesis-$latestRelease.jar"
            return @{
                Source  = "cantara-release"
                Version = $latestRelease
                Url     = $jarUrl
            }
        }
    } catch {
        $ProgressPreference = $ProgressPreference_Saved
    }

    # Try snapshots
    $metaUrl = "$CantaraSnapshots/$GroupPath/$ArtifactId/maven-metadata.xml"
    try {
        $ProgressPreference_Saved = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'
        [xml]$metaXml = (Invoke-WebRequest -Uri $metaUrl -UseBasicParsing -ErrorAction Stop).Content
        $ProgressPreference = $ProgressPreference_Saved

        $versions = $metaXml.metadata.versioning.versions.version
        if ($versions) {
            $latestSnapshot = if ($versions -is [array]) { $versions[-1] } else { $versions }

            # Resolve timestamped JAR name
            $snapshotJarName = "synthesis-$latestSnapshot.jar"
            $snapMetaUrl = "$CantaraSnapshots/$GroupPath/$ArtifactId/$latestSnapshot/maven-metadata.xml"
            try {
                $ProgressPreference = 'SilentlyContinue'
                [xml]$snapMeta = (Invoke-WebRequest -Uri $snapMetaUrl -UseBasicParsing -ErrorAction Stop).Content
                $ProgressPreference = $ProgressPreference_Saved

                $ts = $snapMeta.metadata.versioning.snapshot.timestamp
                $bn = $snapMeta.metadata.versioning.snapshot.buildNumber
                if ($ts -and $bn) {
                    $baseVer = $latestSnapshot -replace '-SNAPSHOT$', ''
                    $snapshotJarName = "synthesis-$baseVer-$ts-$bn.jar"
                }
            } catch {
                $ProgressPreference = $ProgressPreference_Saved
            }

            $jarUrl = "$CantaraSnapshots/$GroupPath/$ArtifactId/$latestSnapshot/$snapshotJarName"
            return @{
                Source  = "cantara-snapshot"
                Version = $latestSnapshot
                Url     = $jarUrl
            }
        }
    } catch {
        $ProgressPreference = $ProgressPreference_Saved
    }

    return $null
}

function Update-FromSource {
    <#
    .SYNOPSIS
        Build from local source directory. Returns hashtable with Version and SourceDir or $null.
    #>
    $sourceDir = $null

    # Check saved source directory
    $sourceDirFile = Join-Path $MetaDir "source-dir"
    if (Test-Path $sourceDirFile) {
        $sourceDir = (Get-Content $sourceDirFile -ErrorAction SilentlyContinue).Trim()
    }

    # Auto-detect common locations
    if (-not $sourceDir -or -not (Test-Path $sourceDir)) {
        $candidates = @(
            (Join-Path $env:USERPROFILE "src\synthesis"),
            (Join-Path $env:USERPROFILE "src\exoreaction\synthesis"),
            (Join-Path $env:USERPROFILE "projects\synthesis")
        )
        foreach ($candidate in $candidates) {
            $pomFile = Join-Path $candidate "pom.xml"
            if ((Test-Path $pomFile) -and ((Get-Content $pomFile -Raw) -match 'synthesis')) {
                $sourceDir = $candidate
                break
            }
        }
    }

    if (-not $sourceDir -or -not (Test-Path (Join-Path $sourceDir "pom.xml"))) {
        return $null
    }

    Write-Detail "Source directory: $sourceDir"

    # Pull latest changes
    $gitDir = Join-Path $sourceDir ".git"
    if ((Test-Path $gitDir) -and (Test-CommandExists "git")) {
        Write-Detail "Pulling latest changes..."
        Push-Location $sourceDir
        try {
            & git pull --ff-only 2>&1 | Out-Null
        } catch {
            Write-Warn "Git pull failed (local changes?). Building current state..."
        } finally {
            Pop-Location
        }
    }

    # Build
    if (-not (Test-CommandExists "mvn")) {
        Write-Err "Maven not found. Cannot build from source."
        return $null
    }

    Write-Detail "Building from source (mvn package -DskipTests)..."
    Push-Location $sourceDir
    try {
        & mvn package -DskipTests -q 2>&1 | Out-Null
    } catch {
        Write-Err "Maven build failed"
        Pop-Location
        return $null
    }
    Pop-Location

    # Find built JAR
    [xml]$pomXml = Get-Content (Join-Path $sourceDir "pom.xml")
    $buildVersion = $pomXml.project.version
    if (-not $buildVersion) { $buildVersion = $pomXml.project.parent.version }
    $jarName = "synthesis-$buildVersion.jar"
    $targetJar = Join-Path $sourceDir "target\$jarName"

    if (-not (Test-Path $targetJar)) {
        Write-Err "Expected JAR not found: $targetJar"
        return $null
    }

    # Install
    Copy-Item $targetJar (Join-Path $LibDir $jarName) -Force
    $currentJarPath = Join-Path $LibDir "current.jar"
    New-JarLink -TargetJar (Join-Path $LibDir $jarName) -LinkPath $currentJarPath | Out-Null
    Set-Content -Path (Join-Path $MetaDir "version") -Value $buildVersion
    Set-Content -Path (Join-Path $MetaDir "source-dir") -Value $sourceDir

    return @{
        Version   = $buildVersion
        SourceDir = $sourceDir
    }
}

# ---------------------------------------------------------------------------
# Sanity Checks
# ---------------------------------------------------------------------------
if (-not (Test-Path $SynthesisHome)) {
    Write-Err "Synthesis not installed at $SynthesisHome"
    Write-Detail "Run the installer first:"
    Write-Detail "  iex (iwr -useb https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.ps1).Content"
    exit 1
}

if (-not (Test-Path $LibDir)) {
    Write-Err "Library directory missing: $LibDir"
    exit 1
}

if (-not (Test-Path $MetaDir)) {
    New-Item -ItemType Directory -Path $MetaDir -Force | Out-Null
}

# ---------------------------------------------------------------------------
# Action: Self-Update
# ---------------------------------------------------------------------------
if ($SelfUpdate) {
    Write-Step "Updating update script..."
    $tempScript = Join-Path $env:TEMP "update-$(Get-Date -Format 'yyyyMMddHHmmss').ps1"
    $updateUrl = "$GitHubRaw/bin/update.ps1"

    if (Invoke-Download -Url $updateUrl -OutFile $tempScript) {
        $destScript = Join-Path $SynthesisHome "bin\update.ps1"
        Copy-Item $tempScript $destScript -Force
        Remove-Item $tempScript -Force -ErrorAction SilentlyContinue
        Write-Info "Update script updated successfully"
    } else {
        Remove-Item $tempScript -Force -ErrorAction SilentlyContinue
        Write-Err "Failed to download update script"
        exit 1
    }
    exit 0
}

# ---------------------------------------------------------------------------
# Action: Rollback
# ---------------------------------------------------------------------------
if ($Rollback) {
    Write-Step "Rolling back to previous version..."

    $currentVer = Get-CurrentVersion
    $previousVer = Get-PreviousVersion

    if (-not $previousVer) {
        Write-Err "No previous version available for rollback"
        Write-Detail "Installed versions:"
        $installedVersions = Get-InstalledVersions
        foreach ($v in $installedVersions) {
            if ($v -eq $currentVer) {
                Write-Detail "  $v (current)"
            } else {
                Write-Detail "  $v"
            }
        }
        exit 1
    }

    Write-Info "Current version:  $currentVer"
    Write-Info "Rollback target:  $previousVer"

    $localJar = "synthesis-$previousVer.jar"
    $localJarPath = Join-Path $LibDir $localJar
    if (-not (Test-Path $localJarPath)) {
        Write-Err "Previous version JAR not found: $localJarPath"
        exit 1
    }

    $currentJarPath = Join-Path $LibDir "current.jar"
    New-JarLink -TargetJar $localJarPath -LinkPath $currentJarPath | Out-Null
    Set-Content -Path (Join-Path $MetaDir "version") -Value $previousVer

    # Verify
    try {
        $verOutput = & java -jar $currentJarPath --version 2>&1
        Write-Info "Rollback successful: now running $previousVer"
    } catch {
        Write-Warn "Rollback completed but verification failed. The JAR may still work."
    }
    exit 0
}

# ---------------------------------------------------------------------------
# Get Current Version
# ---------------------------------------------------------------------------
$currentVersion = Get-CurrentVersion
if (-not $Quiet) { Write-Step "Current version: $currentVersion" }

# ---------------------------------------------------------------------------
# Check for Running Processes
# ---------------------------------------------------------------------------
if (Test-SynthesisRunning) {
    Write-Warn "Synthesis appears to be running. The update will take effect on next launch."
}

# ---------------------------------------------------------------------------
# Find Latest Available Version
# ---------------------------------------------------------------------------
Write-Step "Checking for updates..."

$bestSource = ""
$bestVersion = ""
$bestUrl = ""

# Check GitHub releases
Write-Detail "Checking GitHub releases..."
$ghResult = Find-GitHubRelease
if ($ghResult) {
    $ghVersion = $ghResult.Version

    if ($Version) {
        if (Test-VersionMatches -VersionStr $ghVersion -Pattern $Version) {
            $bestSource = $ghResult.Source
            $bestVersion = $ghVersion
            $bestUrl = $ghResult.Url
        }
    } else {
        $bestSource = $ghResult.Source
        $bestVersion = $ghVersion
        $bestUrl = $ghResult.Url
    }

    Write-Detail "GitHub: $ghVersion available"
}

# Check Cantara Maven repository
Write-Detail "Checking Cantara Maven repository..."
$cantaraResult = Find-CantaraRelease
if ($cantaraResult) {
    $cantaraVersion = $cantaraResult.Version

    $useThis = $false
    if ($Version) {
        if (Test-VersionMatches -VersionStr $cantaraVersion -Pattern $Version) {
            if (-not $bestVersion) {
                $useThis = $true
            } else {
                $cmp = Compare-Versions -V1 $cantaraVersion -V2 $bestVersion
                if ($cmp -eq 1) { $useThis = $true }
            }
        }
    } else {
        if (-not $bestVersion) {
            $useThis = $true
        } else {
            $cmp = Compare-Versions -V1 $cantaraVersion -V2 $bestVersion
            if ($cmp -eq 1) { $useThis = $true }
        }
    }

    if ($useThis) {
        $bestSource = $cantaraResult.Source
        $bestVersion = $cantaraVersion
        $bestUrl = $cantaraResult.Url
    }

    Write-Detail "Cantara: $cantaraVersion available"
}

# ---------------------------------------------------------------------------
# Action: Check Only
# ---------------------------------------------------------------------------
if ($Check) {
    if (-not $bestVersion) {
        # No remote versions found, check source
        $sourceDirFile = Join-Path $MetaDir "source-dir"
        if (Test-Path $sourceDirFile) {
            $sourceDir = (Get-Content $sourceDirFile -ErrorAction SilentlyContinue).Trim()
            $gitDir = Join-Path $sourceDir ".git"
            if ((Test-Path $gitDir) -and (Test-CommandExists "git")) {
                Push-Location $sourceDir
                try {
                    $remoteHead = & git ls-remote origin HEAD 2>$null | ForEach-Object { ($_ -split '\s+')[0] }
                    $localHead = & git rev-parse HEAD 2>$null
                    if ($remoteHead -and ($remoteHead -ne $localHead)) {
                        Write-Output "Update available (source): new commits in repository"
                        Pop-Location
                        exit 0
                    }
                } catch { } finally {
                    Pop-Location
                }
            }
        }
        if (-not $Quiet) {
            Write-Info "No updates found. Current version: $currentVersion"
        }
        exit 0
    }

    $cmp = Compare-Versions -V1 $bestVersion -V2 $currentVersion
    if ($cmp -eq 1) {
        Write-Output "Update available: $bestVersion (current: $currentVersion)"
    } elseif ($cmp -eq 0) {
        if (-not $Quiet) { Write-Info "Already up to date: $currentVersion" }
    } else {
        if (-not $Quiet) { Write-Info "Current version ($currentVersion) is newer than latest ($bestVersion)" }
    }
    exit 0
}

# ---------------------------------------------------------------------------
# Action: Update
# ---------------------------------------------------------------------------
$needUpdate = $false

if (-not $bestVersion) {
    # No remote version found, try source build
    Write-Detail "No remote versions found. Attempting source build..."
    $sourceResult = Update-FromSource
    if ($sourceResult) {
        Write-Info "Updated from source to version $($sourceResult.Version)"
        Set-Content -Path (Join-Path $MetaDir "last-update-check") -Value ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
        exit 0
    } else {
        Write-Err "No update sources available."
        Write-Detail "Ensure GitHub access or a local source directory."
        exit 1
    }
}

# Compare versions
if ($Force) {
    $needUpdate = $true
    Write-Info "Forcing update (-Force)"
} else {
    $cmp = Compare-Versions -V1 $bestVersion -V2 $currentVersion
    if ($cmp -eq 1) {
        $needUpdate = $true
    } elseif ($cmp -eq 0) {
        Write-Info "Already up to date: $currentVersion"
        Set-Content -Path (Join-Path $MetaDir "last-update-check") -Value ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
        exit 0
    } else {
        Write-Info "Current version ($currentVersion) is newer than latest ($bestVersion)"
        Write-Info "Use -Force to downgrade."
        Set-Content -Path (Join-Path $MetaDir "last-update-check") -Value ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
        exit 0
    }
}

if (-not $needUpdate) { exit 0 }

# ---------------------------------------------------------------------------
# Download and Install
# ---------------------------------------------------------------------------
Write-Step "Updating: $currentVersion -> $bestVersion"

$jarName = "synthesis-$bestVersion.jar"
$tempJar = Join-Path $env:TEMP "synthesis-update-$(Get-Date -Format 'yyyyMMddHHmmss').jar"

# Backup info
$currentJarPath = Join-Path $LibDir "current.jar"
if (Test-Path $currentJarPath) {
    Write-Detail "Backing up current version"
}

# Download
if ($bestUrl) {
    Write-Detail "Downloading from $bestSource..."
    if (-not (Invoke-Download -Url $bestUrl -OutFile $tempJar)) {
        Remove-Item $tempJar -Force -ErrorAction SilentlyContinue
        Write-Err "Download failed from $bestUrl"

        # Fallback to source build
        Write-Detail "Attempting source build as fallback..."
        $sourceResult = Update-FromSource
        if ($sourceResult) {
            Write-Info "Updated from source to version $($sourceResult.Version)"
            Set-Content -Path (Join-Path $MetaDir "last-update-check") -Value ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
            exit 0
        }
        exit 1
    }
} else {
    # Source build
    Remove-Item $tempJar -Force -ErrorAction SilentlyContinue
    $sourceResult = Update-FromSource
    if ($sourceResult) {
        Write-Info "Updated from source to version $($sourceResult.Version)"
        Set-Content -Path (Join-Path $MetaDir "last-update-check") -Value ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
        exit 0
    }
    Write-Err "No download URL and source build failed."
    exit 1
}

# Verify download (check it starts with PK zip header)
if (Test-Path $tempJar) {
    $headerBytes = [System.IO.File]::ReadAllBytes($tempJar) | Select-Object -First 2
    if ($headerBytes.Count -lt 2 -or $headerBytes[0] -ne 0x50 -or $headerBytes[1] -ne 0x4B) {
        Remove-Item $tempJar -Force
        Write-Err "Downloaded file is not a valid JAR (ZIP archive)"
        exit 1
    }
}

# SHA verification (if checksum file available)
$shaUrl = "$bestUrl.sha256"
if (Test-UrlExists -Url $shaUrl) {
    Write-Detail "Verifying SHA256 checksum..."
    try {
        $ProgressPreference_Saved = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'
        $expectedSha = ((Invoke-WebRequest -Uri $shaUrl -UseBasicParsing -ErrorAction Stop).Content).Trim().Split(' ')[0].ToLower()
        $ProgressPreference = $ProgressPreference_Saved

        $actualSha = Get-FileHash256 -FilePath $tempJar

        if ($expectedSha -and ($expectedSha -ne $actualSha)) {
            Remove-Item $tempJar -Force
            Write-Err "SHA256 checksum mismatch!"
            Write-Detail "Expected: $expectedSha"
            Write-Detail "Actual:   $actualSha"
            exit 1
        }
        Write-Info "SHA256 checksum verified"
    } catch {
        $ProgressPreference = $ProgressPreference_Saved
        Write-Detail "SHA256 verification failed (non-fatal)"
    }
} else {
    Write-Detail "No SHA256 checksum available (skipping verification)"
}

# Install new JAR
$destJar = Join-Path $LibDir $jarName
Move-Item $tempJar $destJar -Force
Write-Detail "Installed $jarName"

# Update link
New-JarLink -TargetJar $destJar -LinkPath $currentJarPath | Out-Null
Write-Detail "Updated current.jar -> $jarName"

# Update metadata
Set-Content -Path (Join-Path $MetaDir "version") -Value $bestVersion
Set-Content -Path (Join-Path $MetaDir "last-update-check") -Value ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
Set-Content -Path (Join-Path $MetaDir "last-update") -Value (Get-Date -Format "o")

# ---------------------------------------------------------------------------
# Verify New Version
# ---------------------------------------------------------------------------
Write-Step "Verifying update..."

try {
    $verOutput = & java -jar $currentJarPath --version 2>&1
    Write-Info "Verification passed: $verOutput"
} catch {
    Write-Warn "Verification failed. Rolling back..."

    $previousVer = Get-PreviousVersion
    if ($previousVer) {
        $prevJar = Join-Path $LibDir "synthesis-$previousVer.jar"
        New-JarLink -TargetJar $prevJar -LinkPath $currentJarPath | Out-Null
        Set-Content -Path (Join-Path $MetaDir "version") -Value $previousVer
        Write-Warn "Rolled back to $previousVer"
        Write-Warn "The new JAR is kept at $destJar for inspection."
    }
    exit 1
}

# ---------------------------------------------------------------------------
# Cleanup Old Versions (keep last 3)
# ---------------------------------------------------------------------------
Write-Step "Cleaning up old versions..."

$installedVersions = Get-InstalledVersions
$installedCount = $installedVersions.Count

if ($installedCount -gt 3) {
    $removeCount = $installedCount - 3
    $toRemove = $installedVersions | Select-Object -First $removeCount

    foreach ($oldVersion in $toRemove) {
        $oldJar = Join-Path $LibDir "synthesis-$oldVersion.jar"
        if (Test-Path $oldJar) {
            Write-Detail "Removing old version: $oldVersion"
            Remove-Item $oldJar -Force
        }
    }
    Write-Info "Kept last 3 versions"
} else {
    Write-Detail "No cleanup needed ($installedCount versions installed)"
}

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "Update complete!" -ForegroundColor Green
Write-Host "  Previous:  $currentVersion"
Write-Host "  Current:   $bestVersion"
Write-Host "  Source:    $bestSource"
Write-Host ""

# Show installed versions
Write-Detail "Installed versions:"
$currentInstalledVersions = Get-InstalledVersions
foreach ($v in $currentInstalledVersions) {
    if ($v -eq $bestVersion) {
        Write-Host "      $v (current)" -ForegroundColor Green
    } else {
        Write-Host "      $v" -ForegroundColor DarkGray
    }
}
Write-Host ""
