#Requires -Version 5.1
<#
.SYNOPSIS
    Synthesis Installer for Windows.

.DESCRIPTION
    One-command installation for Synthesis - AI operations partner for knowledge infrastructure.
    Downloads or builds the Synthesis JAR, creates directory structure, configures PATH and
    PowerShell profile integration.

.PARAMETER Force
    Overwrite existing installation.

.PARAMETER Source
    Path to local Synthesis source directory (with pom.xml) for building from source.

.PARAMETER NoPathUpdate
    Skip adding Synthesis to the User PATH environment variable.

.PARAMETER NoProfile
    Skip adding alias/PATH to the PowerShell profile.

.EXAMPLE
    # One-liner install (requires RemoteSigned execution policy)
    iex (iwr -useb https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.ps1).Content

.EXAMPLE
    # Download and run
    Invoke-WebRequest -Uri "https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.ps1" -OutFile install.ps1
    .\install.ps1

.EXAMPLE
    # Install from local source
    .\install.ps1 -Source C:\src\Synthesis

.EXAMPLE
    # Force reinstall, skip profile changes
    .\install.ps1 -Force -NoProfile

.NOTES
    Copyright (c) 2026 eXOReaction AS. All rights reserved.
    Requires: Windows 10+, PowerShell 5.1+, Java 17+
#>

[CmdletBinding()]
param(
    [switch]$Force,
    [string]$Source,
    [switch]$NoPathUpdate,
    [switch]$NoProfile
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
$MinJavaVersion = 17

# Track whether we created the home directory (for cleanup on failure)
$CreatedSynthesisHome = $false

# ---------------------------------------------------------------------------
# Output Helpers
# ---------------------------------------------------------------------------
function Write-Info  { param([string]$Message) Write-Host "[INFO]  $Message" -ForegroundColor Green }
function Write-Warn  { param([string]$Message) Write-Host "[WARN]  $Message" -ForegroundColor Yellow }
function Write-Err   { param([string]$Message) Write-Host "[ERROR] $Message" -ForegroundColor Red }
function Write-Step  { param([string]$Message) Write-Host "==> $Message" -ForegroundColor Blue }
function Write-Detail { param([string]$Message) Write-Host "    $Message" }

# ---------------------------------------------------------------------------
# Helper Functions
# ---------------------------------------------------------------------------
function Test-CommandExists {
    param([string]$Name)
    $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

function Get-JavaVersion {
    <#
    .SYNOPSIS
        Detect Java major version from java -version output.
    #>
    try {
        $output = & java -version 2>&1 | Select-Object -First 1
        if ($output -match 'version "(\d+)') {
            return [int]$Matches[1]
        }
        # Handle old 1.x format
        if ($output -match 'version "1\.(\d+)') {
            return [int]$Matches[1]
        }
    } catch {
        # java not found or failed
    }
    return 0
}

function Find-JavaInRegistry {
    <#
    .SYNOPSIS
        Check Windows registry for Java installations.
    #>
    $regPaths = @(
        "HKLM:\SOFTWARE\JavaSoft\JDK",
        "HKLM:\SOFTWARE\JavaSoft\Java Development Kit",
        "HKLM:\SOFTWARE\JavaSoft\Java Runtime Environment"
    )

    foreach ($regPath in $regPaths) {
        if (Test-Path $regPath) {
            try {
                $versions = Get-ChildItem $regPath -ErrorAction SilentlyContinue |
                    ForEach-Object { $_.PSChildName } |
                    Where-Object { $_ -match '^\d+' } |
                    Sort-Object -Descending
                if ($versions) {
                    return $versions[0]
                }
            } catch {
                continue
            }
        }
    }
    return $null
}

function Invoke-Download {
    <#
    .SYNOPSIS
        Download a file from URL. Returns $true on success.
    #>
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
    <#
    .SYNOPSIS
        Check if a URL returns HTTP 200.
    #>
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

function New-JarLink {
    <#
    .SYNOPSIS
        Create a link from current.jar to the target JAR.
        Tries HardLink first (no admin), falls back to copy.
        Returns the method used: "hardlink" or "copy".
    #>
    param(
        [string]$TargetJar,
        [string]$LinkPath
    )

    # Remove existing current.jar if present
    if (Test-Path $LinkPath) {
        Remove-Item $LinkPath -Force
    }

    # Try hard link (works for files, no admin required)
    try {
        New-Item -ItemType HardLink -Path $LinkPath -Target $TargetJar -ErrorAction Stop | Out-Null
        return "hardlink"
    } catch {
        # Fall back to copy
        Copy-Item $TargetJar $LinkPath -Force
        return "copy"
    }
}

# ---------------------------------------------------------------------------
# Cleanup on Failure
# ---------------------------------------------------------------------------
function Invoke-CleanupOnFailure {
    if ($CreatedSynthesisHome -and (Test-Path $SynthesisHome)) {
        Write-Warn "Installation failed. Cleaning up..."
        Remove-Item $SynthesisHome -Recurse -Force -ErrorAction SilentlyContinue
    }
}

trap { Invoke-CleanupOnFailure; break }

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "  ____              _   _               _     " -ForegroundColor Cyan
Write-Host " / ___| _   _ _ __ | |_| |__   ___  ___(_)___ " -ForegroundColor Cyan
Write-Host " \___ \| | | | '_ \| __| '_ \ / _ \/ __| / __|" -ForegroundColor Cyan
Write-Host "  ___) | |_| | | | | |_| | | |  __/\__ \ \__ \" -ForegroundColor Cyan
Write-Host " |____/ \__, |_| |_|\__|_| |_|\___||___/_|___/" -ForegroundColor Cyan
Write-Host "        |___/                                  " -ForegroundColor Cyan
Write-Host ""
Write-Host "  AI operations partner for knowledge infrastructure" -ForegroundColor White
Write-Host "  https://github.com/exoreaction/Synthesis" -ForegroundColor Cyan
Write-Host ""

# ---------------------------------------------------------------------------
# Step 1: Check Prerequisites
# ---------------------------------------------------------------------------
Write-Step "Checking prerequisites..."

# Check: PowerShell execution policy
$execPolicy = Get-ExecutionPolicy -Scope CurrentUser
if ($execPolicy -eq 'Restricted') {
    Write-Warn "PowerShell execution policy is 'Restricted'."
    Write-Detail "Scripts may not run. To fix, run (as Administrator or for current user):"
    Write-Detail "  Set-ExecutionPolicy RemoteSigned -Scope CurrentUser"
}

# Check: Java 17+
$javaOnPath = Test-CommandExists "java"
$javaVersion = 0

if ($javaOnPath) {
    $javaVersion = Get-JavaVersion
}

if ($javaVersion -eq 0) {
    # Try registry
    $regVersion = Find-JavaInRegistry
    if ($regVersion) {
        Write-Warn "Java found in registry ($regVersion) but not on PATH."
        Write-Detail "Add Java to your PATH or set JAVA_HOME."
    }
}

if ($javaVersion -ge $MinJavaVersion) {
    Write-Info "Java $javaVersion found (>= $MinJavaVersion required)"
} elseif ($javaVersion -gt 0) {
    Write-Err "Java $javaVersion found, but Java $MinJavaVersion+ is required."
    Write-Detail "Install Java 17+:"
    Write-Detail "  winget install Microsoft.OpenJDK.17"
    Write-Detail "  Or: https://adoptium.net/temurin/releases/"
    Invoke-CleanupOnFailure
    exit 1
} else {
    Write-Err "Java not found. Java $MinJavaVersion+ is required."
    Write-Detail "Install Java 17+:"
    Write-Detail "  winget install Microsoft.OpenJDK.17"
    Write-Detail "  Or: https://adoptium.net/temurin/releases/"
    Invoke-CleanupOnFailure
    exit 1
}

# Check: Invoke-WebRequest (always available on PS 5.1+ / Windows 10+)
Write-Info "Download tool available (Invoke-WebRequest)"

# Check: git (optional)
if (Test-CommandExists "git") {
    $gitVer = & git --version 2>$null | Select-Object -First 1
    Write-Info "Git found ($gitVer)"
} else {
    Write-Warn "Git not found. Source builds from GitHub will not be available."
    Write-Detail "Install: winget install Git.Git"
}

# Check: Maven (optional)
$hasMaven = Test-CommandExists "mvn"
if ($hasMaven) {
    Write-Info "Maven found (source builds enabled)"
} else {
    Write-Detail "Maven not found (optional, needed for source builds)"
    Write-Detail "  Install: winget install Apache.Maven"
}

# ---------------------------------------------------------------------------
# Step 2: Check Existing Installation
# ---------------------------------------------------------------------------
Write-Step "Checking for existing installation..."

if (Test-Path $SynthesisHome) {
    if ($Force) {
        Write-Warn "Existing installation found at $SynthesisHome"
        Write-Warn "Removing (-Force specified)..."
        Remove-Item $SynthesisHome -Recurse -Force
    } else {
        Write-Err "Synthesis is already installed at $SynthesisHome"
        Write-Detail "To reinstall, use:  .\install.ps1 -Force"
        Write-Detail "To update, use:     synthesis-update"
        Write-Detail "To uninstall, use:  & `"$SynthesisHome\bin\uninstall.ps1`""
        exit 1
    }
}

# ---------------------------------------------------------------------------
# Step 3: Create Directory Structure
# ---------------------------------------------------------------------------
Write-Step "Creating directory structure..."

$dirs = @(
    $SynthesisHome,
    (Join-Path $SynthesisHome "bin"),
    (Join-Path $SynthesisHome "lib"),
    (Join-Path $SynthesisHome ".metadata")
)

foreach ($dir in $dirs) {
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
}
$CreatedSynthesisHome = $true

Write-Detail "Created $SynthesisHome\"
Write-Detail "Created $SynthesisHome\bin\"
Write-Detail "Created $SynthesisHome\lib\"
Write-Detail "Created $SynthesisHome\.metadata\"

# ---------------------------------------------------------------------------
# Step 4: Obtain Synthesis JAR
# ---------------------------------------------------------------------------
Write-Step "Obtaining Synthesis..."

$jarObtained = $false
$installedVersion = ""
$libDir = Join-Path $SynthesisHome "lib"

# Strategy 1: Check GitHub releases
if (-not $jarObtained) {
    Write-Detail "Checking GitHub releases..."
    try {
        $ProgressPreference_Saved = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'
        $apiResponse = Invoke-RestMethod -Uri "https://api.github.com/repos/$GitHubRepo/releases/latest" -UseBasicParsing -ErrorAction Stop
        $ProgressPreference = $ProgressPreference_Saved

        $releaseTag = $apiResponse.tag_name
        $releaseAsset = $apiResponse.assets | Where-Object { $_.name -match 'synthesis.*\.jar$' } | Select-Object -First 1

        if ($releaseAsset) {
            $downloadUrl = $releaseAsset.browser_download_url
            $installedVersion = $releaseTag -replace '^v', ''
            $jarName = "synthesis-$installedVersion.jar"
            $jarPath = Join-Path $libDir $jarName

            Write-Detail "Downloading from GitHub release..."
            if (Invoke-Download -Url $downloadUrl -OutFile $jarPath) {
                $jarObtained = $true
                Write-Info "Downloaded $jarName from GitHub release"
            } else {
                Write-Warn "GitHub release download failed, trying next method..."
            }
        } else {
            Write-Detail "No JAR asset in GitHub release, trying next method..."
        }
    } catch {
        $ProgressPreference = $ProgressPreference_Saved
        Write-Detail "No GitHub releases found, trying next method..."
    }
}

# Strategy 2: Check Cantara Maven repository (releases first, then snapshots)
if (-not $jarObtained) {
    Write-Detail "Checking Cantara Maven repository..."

    # Try releases
    $mavenMetaUrl = "$CantaraReleases/$GroupPath/$ArtifactId/maven-metadata.xml"
    try {
        $ProgressPreference_Saved = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'
        [xml]$metaXml = (Invoke-WebRequest -Uri $mavenMetaUrl -UseBasicParsing -ErrorAction Stop).Content
        $ProgressPreference = $ProgressPreference_Saved

        $latestRelease = $metaXml.metadata.versioning.release
        if ($latestRelease) {
            $installedVersion = $latestRelease
            $jarName = "synthesis-$installedVersion.jar"
            $jarUrl = "$CantaraReleases/$GroupPath/$ArtifactId/$installedVersion/$jarName"
            $jarPath = Join-Path $libDir $jarName

            if (Invoke-Download -Url $jarUrl -OutFile $jarPath) {
                $jarObtained = $true
                Write-Info "Downloaded $jarName from Cantara releases"
            }
        }
    } catch {
        $ProgressPreference = $ProgressPreference_Saved
    }

    # Try snapshots
    if (-not $jarObtained) {
        $mavenMetaUrl = "$CantaraSnapshots/$GroupPath/$ArtifactId/maven-metadata.xml"
        try {
            $ProgressPreference_Saved = $ProgressPreference
            $ProgressPreference = 'SilentlyContinue'
            [xml]$metaXml = (Invoke-WebRequest -Uri $mavenMetaUrl -UseBasicParsing -ErrorAction Stop).Content
            $ProgressPreference = $ProgressPreference_Saved

            $versions = $metaXml.metadata.versioning.versions.version
            if ($versions) {
                $latestSnapshot = if ($versions -is [array]) { $versions[-1] } else { $versions }

                # Resolve timestamped JAR name for snapshots
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

                $installedVersion = $latestSnapshot
                $jarUrl = "$CantaraSnapshots/$GroupPath/$ArtifactId/$latestSnapshot/$snapshotJarName"
                $jarPath = Join-Path $libDir "synthesis-$installedVersion.jar"

                if (Invoke-Download -Url $jarUrl -OutFile $jarPath) {
                    $jarObtained = $true
                    Write-Info "Downloaded synthesis-$installedVersion.jar from Cantara snapshots"
                }
            }
        } catch {
            $ProgressPreference = $ProgressPreference_Saved
        }
    }
}

# Strategy 3: Use local source directory (-Source parameter)
if (-not $jarObtained -and $Source) {
    Write-Detail "Using local source directory: $Source"
    $pomFile = Join-Path $Source "pom.xml"
    if (Test-Path $pomFile) {
        # Extract version from pom.xml
        [xml]$pomXml = Get-Content $pomFile
        $installedVersion = $pomXml.project.version
        if (-not $installedVersion) {
            # Try parent version
            $installedVersion = $pomXml.project.parent.version
        }
        $jarName = "synthesis-$installedVersion.jar"
        $targetJar = Join-Path $Source "target\$jarName"

        if (Test-Path $targetJar) {
            Copy-Item $targetJar (Join-Path $libDir $jarName)
            $jarObtained = $true
            Write-Info "Copied pre-built $jarName from source directory"
        } elseif ($hasMaven) {
            Write-Detail "Building from source (mvn package -DskipTests)..."
            Push-Location $Source
            try {
                & mvn package -DskipTests -q 2>&1 | Out-Null
                if (Test-Path $targetJar) {
                    Copy-Item $targetJar (Join-Path $libDir $jarName)
                    $jarObtained = $true
                    Write-Info "Built and installed $jarName from source"
                }
            } catch {
                Write-Warn "Maven build failed"
            } finally {
                Pop-Location
            }
        } else {
            Write-Err "Maven not found. Cannot build from source."
            Write-Detail "Install Maven: winget install Apache.Maven"
        }
    } else {
        Write-Err "No pom.xml found in $Source"
    }
}

# Strategy 4: Clone from GitHub and build
if (-not $jarObtained -and (Test-CommandExists "git") -and $hasMaven) {
    Write-Detail "Cloning from GitHub and building..."
    $cloneDir = Join-Path $env:TEMP "synthesis-install-$(Get-Date -Format 'yyyyMMddHHmmss')"

    try {
        & git clone --depth 1 "$GitHubUrl.git" $cloneDir 2>$null

        if (Test-Path (Join-Path $cloneDir "pom.xml")) {
            [xml]$pomXml = Get-Content (Join-Path $cloneDir "pom.xml")
            $installedVersion = $pomXml.project.version
            $jarName = "synthesis-$installedVersion.jar"

            Write-Detail "Building from source (mvn package -DskipTests)..."
            Push-Location $cloneDir
            try {
                & mvn package -DskipTests -q 2>&1 | Out-Null
                $targetJar = Join-Path $cloneDir "target\$jarName"
                if (Test-Path $targetJar) {
                    Copy-Item $targetJar (Join-Path $libDir $jarName)
                    $jarObtained = $true
                    Write-Info "Built and installed $jarName from GitHub source"
                }
            } catch {
                Write-Warn "Maven build failed"
            } finally {
                Pop-Location
            }
        }
    } catch {
        Write-Warn "Git clone failed"
    } finally {
        if (Test-Path $cloneDir) {
            Remove-Item $cloneDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

# Strategy 5: Auto-detect source in common locations
if (-not $jarObtained) {
    $candidates = @(
        (Join-Path $env:USERPROFILE "src\synthesis"),
        (Join-Path $env:USERPROFILE "src\exoreaction\synthesis"),
        (Join-Path $env:USERPROFILE "projects\synthesis"),
        (Get-Location).Path
    )

    foreach ($candidate in $candidates) {
        $pomFile = Join-Path $candidate "pom.xml"
        if ((Test-Path $pomFile) -and ((Get-Content $pomFile -Raw) -match 'synthesis')) {
            [xml]$pomXml = Get-Content $pomFile
            $installedVersion = $pomXml.project.version
            $jarName = "synthesis-$installedVersion.jar"
            $targetJar = Join-Path $candidate "target\$jarName"

            if (Test-Path $targetJar) {
                Copy-Item $targetJar (Join-Path $libDir $jarName)
                $jarObtained = $true
                Write-Info "Found and copied $jarName from $candidate"

                # Save source location for future updates
                Set-Content -Path (Join-Path $SynthesisHome ".metadata\source-dir") -Value $candidate
                break
            }
        }
    }
}

if (-not $jarObtained) {
    Write-Err "Could not obtain Synthesis JAR."
    Write-Host ""
    Write-Detail "Options:"
    Write-Detail "  1. Build from source first:"
    Write-Detail "     cd $env:USERPROFILE\src\synthesis"
    Write-Detail "     mvn package -DskipTests"
    Write-Detail "     .\bin\install.ps1 -Source $env:USERPROFILE\src\synthesis"
    Write-Detail ""
    Write-Detail "  2. Install Maven for automatic builds:"
    Write-Detail "     winget install Apache.Maven"
    Write-Detail ""
    Write-Detail "  3. Download a release JAR manually:"
    Write-Detail "     Place it at: $libDir\synthesis-VERSION.jar"
    Invoke-CleanupOnFailure
    exit 1
}

# ---------------------------------------------------------------------------
# Step 5: Create JAR Link
# ---------------------------------------------------------------------------
Write-Step "Setting up JAR link..."

$jarName = "synthesis-$installedVersion.jar"
$jarFullPath = Join-Path $libDir $jarName
$currentJarPath = Join-Path $libDir "current.jar"
$linkMethod = New-JarLink -TargetJar $jarFullPath -LinkPath $currentJarPath

Write-Info "Linked current.jar -> $jarName ($linkMethod)"

# ---------------------------------------------------------------------------
# Step 6: Install Launcher Script (synthesis.bat)
# ---------------------------------------------------------------------------
Write-Step "Installing launcher script..."

$launcherPath = Join-Path $SynthesisHome "bin\synthesis.bat"

$launcherContent = @'
@echo off
REM Synthesis Launcher for Windows
REM Launches the Synthesis CLI with pre-flight checks and auto-update notifications.
REM
REM Environment:
REM   SYNTHESIS_HOME              Installation directory (default: %USERPROFILE%\.synthesis)
REM   SYNTHESIS_NO_UPDATE_CHECK   Set to 1 to disable auto-update checks
REM   SYNTHESIS_JAVA_OPTS         Extra JVM options (e.g., "-Xmx2g")
REM
REM Copyright (c) 2026 eXOReaction AS. All rights reserved.

setlocal enabledelayedexpansion

REM Configuration
if "%SYNTHESIS_HOME%"=="" set "SYNTHESIS_HOME=%USERPROFILE%\.synthesis"
set "JAR_PATH=%SYNTHESIS_HOME%\lib\current.jar"
set "MIN_JAVA_VERSION=17"

REM Check JAR exists
if not exist "%JAR_PATH%" (
    echo [ERROR] Synthesis JAR not found at %JAR_PATH% 1>&2
    echo   Run the installer: 1>&2
    echo     powershell -ExecutionPolicy Bypass -File "%SYNTHESIS_HOME%\bin\install.ps1" 1>&2
    exit /b 1
)

REM Check Java exists
where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found. Java %MIN_JAVA_VERSION%+ is required. 1>&2
    echo   Install: winget install Microsoft.OpenJDK.17 1>&2
    exit /b 1
)

REM Check Java version
for /f "tokens=3 delims= " %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set "JAVA_VER_RAW=%%~v"
    goto :got_ver
)
:got_ver
REM Extract major version number
for /f "tokens=1 delims=." %%m in ("%JAVA_VER_RAW%") do set "JAVA_MAJOR=%%m"
REM Handle 1.x format
if "%JAVA_MAJOR%"=="1" (
    for /f "tokens=2 delims=." %%m in ("%JAVA_VER_RAW%") do set "JAVA_MAJOR=%%m"
)

if %JAVA_MAJOR% LSS %MIN_JAVA_VERSION% (
    echo [ERROR] Java %JAVA_MAJOR% found, but Java %MIN_JAVA_VERSION%+ is required. 1>&2
    exit /b 1
)

REM Auto-update check (daily, non-blocking)
if "%SYNTHESIS_NO_UPDATE_CHECK%"=="1" goto :skip_update_check

set "LAST_CHECK_FILE=%SYNTHESIS_HOME%\.metadata\last-update-check"
set "RESULT_FILE=%SYNTHESIS_HOME%\.metadata\update-check-result"

REM Show result from previous background check
if exist "%RESULT_FILE%" (
    set /p UPDATE_MSG=<"%RESULT_FILE%"
    if defined UPDATE_MSG (
        echo !UPDATE_MSG! | findstr /i "update available" >nul 2>&1
        if not errorlevel 1 (
            echo [update] !UPDATE_MSG!
            echo   Run: synthesis-update
            echo.
            del "%RESULT_FILE%" >nul 2>&1
        )
    )
)

REM Check if we need a new update check (every 24 hours)
REM Note: Batch cannot easily do epoch math; we use file age instead
if exist "%LAST_CHECK_FILE%" (
    REM Check if file is older than 1 day using forfiles
    forfiles /P "%SYNTHESIS_HOME%\.metadata" /M "last-update-check" /D -1 >nul 2>&1
    if not errorlevel 1 (
        REM File is older than 1 day, run check in background
        if exist "%SYNTHESIS_HOME%\bin\update.ps1" (
            start /b "" powershell -NoProfile -ExecutionPolicy Bypass -Command ^
                "& '%SYNTHESIS_HOME%\bin\update.ps1' -Check -Quiet > '%RESULT_FILE%' 2>$null; Get-Date -UFormat '%%s' > '%LAST_CHECK_FILE%'" >nul 2>&1
        )
    )
) else (
    REM No last check file, create one
    echo 0 > "%LAST_CHECK_FILE%"
)

:skip_update_check

REM Launch Synthesis
set "JAVA_OPTS=%SYNTHESIS_JAVA_OPTS%"
java %JAVA_OPTS% -jar "%JAR_PATH%" %*
exit /b %errorlevel%
'@

Set-Content -Path $launcherPath -Value $launcherContent -Encoding ASCII
Write-Info "Installed launcher at $launcherPath"

# Also create synthesis-update.bat convenience script
$updateBatPath = Join-Path $SynthesisHome "bin\synthesis-update.bat"
$updateBatContent = @'
@echo off
REM Synthesis Update Launcher
REM Convenience wrapper for update.ps1
REM Copyright (c) 2026 eXOReaction AS. All rights reserved.

if "%SYNTHESIS_HOME%"=="" set "SYNTHESIS_HOME=%USERPROFILE%\.synthesis"
powershell -NoProfile -ExecutionPolicy Bypass -File "%SYNTHESIS_HOME%\bin\update.ps1" %*
'@
Set-Content -Path $updateBatPath -Value $updateBatContent -Encoding ASCII
Write-Info "Installed synthesis-update.bat"

# ---------------------------------------------------------------------------
# Step 7: Install Update Script
# ---------------------------------------------------------------------------
Write-Step "Installing update script..."

$updateScriptInstalled = $false
$updateDest = Join-Path $SynthesisHome "bin\update.ps1"

# Try: copy from same directory as this install script
$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
$localUpdate = Join-Path $scriptDir "update.ps1"
if (Test-Path $localUpdate) {
    Copy-Item $localUpdate $updateDest -Force
    $updateScriptInstalled = $true
    Write-Info "Copied update.ps1 from source"
}

# Try: copy from -Source directory
if (-not $updateScriptInstalled -and $Source) {
    $sourceUpdate = Join-Path $Source "bin\update.ps1"
    if (Test-Path $sourceUpdate) {
        Copy-Item $sourceUpdate $updateDest -Force
        $updateScriptInstalled = $true
        Write-Info "Copied update.ps1 from source directory"
    }
}

# Try: download from GitHub
if (-not $updateScriptInstalled) {
    $updateUrl = "$GitHubRaw/bin/update.ps1"
    if (Invoke-Download -Url $updateUrl -OutFile $updateDest) {
        $updateScriptInstalled = $true
        Write-Info "Downloaded update.ps1 from GitHub"
    }
}

# Fallback: create stub
if (-not $updateScriptInstalled) {
    $stubContent = @'
Write-Host "Update script not yet available. Download it from:"
Write-Host "  https://github.com/exoreaction/Synthesis/blob/main/bin/update.ps1"
Write-Host ""
Write-Host "Or rebuild from source:"
Write-Host "  cd $env:USERPROFILE\src\synthesis; git pull; mvn package -DskipTests"
Write-Host "  Copy-Item target\synthesis-*.jar $env:USERPROFILE\.synthesis\lib\"
exit 1
'@
    Set-Content -Path $updateDest -Value $stubContent
    Write-Warn "Update script installed as stub (download full version from GitHub)"
}

# Also install uninstall.ps1
$uninstallDest = Join-Path $SynthesisHome "bin\uninstall.ps1"
$localUninstall = Join-Path $scriptDir "uninstall.ps1"
if (Test-Path $localUninstall) {
    Copy-Item $localUninstall $uninstallDest -Force
    Write-Info "Copied uninstall.ps1 from source"
} elseif ($Source) {
    $sourceUninstall = Join-Path $Source "bin\uninstall.ps1"
    if (Test-Path $sourceUninstall) {
        Copy-Item $sourceUninstall $uninstallDest -Force
        Write-Info "Copied uninstall.ps1 from source directory"
    }
} else {
    $uninstallUrl = "$GitHubRaw/bin/uninstall.ps1"
    if (Invoke-Download -Url $uninstallUrl -OutFile $uninstallDest) {
        Write-Info "Downloaded uninstall.ps1 from GitHub"
    }
}

# ---------------------------------------------------------------------------
# Step 8: Save Metadata
# ---------------------------------------------------------------------------
Write-Step "Saving installation metadata..."

$metaDir = Join-Path $SynthesisHome ".metadata"
Set-Content -Path (Join-Path $metaDir "version") -Value $installedVersion
Set-Content -Path (Join-Path $metaDir "install-date") -Value (Get-Date -Format "o")
Set-Content -Path (Join-Path $metaDir "last-update-check") -Value ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
Set-Content -Path (Join-Path $metaDir "os") -Value "windows"
Set-Content -Path (Join-Path $metaDir "link-method") -Value $linkMethod

# Save source directory if provided
if ($Source -and -not (Test-Path (Join-Path $metaDir "source-dir"))) {
    Set-Content -Path (Join-Path $metaDir "source-dir") -Value (Resolve-Path $Source).Path
}

Write-Info "Version: $installedVersion"
Write-Info "Installed: $(Get-Date)"

# ---------------------------------------------------------------------------
# Step 9: Environment Setup (PATH + PowerShell Profile)
# ---------------------------------------------------------------------------
Write-Step "Setting up environment..."

$binDir = Join-Path $SynthesisHome "bin"

# 9a: Add to User PATH
if (-not $NoPathUpdate) {
    $currentPath = [Environment]::GetEnvironmentVariable("PATH", "User")
    if ($currentPath -and $currentPath.Split(';') -contains $binDir) {
        Write-Info "PATH already contains $binDir"
    } else {
        $newPath = if ($currentPath) { "$binDir;$currentPath" } else { $binDir }
        [Environment]::SetEnvironmentVariable("PATH", $newPath, "User")
        Write-Info "Added $binDir to User PATH"
        Write-Detail "Open a new terminal for PATH changes to take effect."
    }

    # Also add to current session
    if ($env:PATH -notlike "*$binDir*") {
        $env:PATH = "$binDir;$env:PATH"
    }
} else {
    Write-Detail "Skipping PATH update (-NoPathUpdate specified)"
}

# 9b: PowerShell Profile
if (-not $NoProfile) {
    $profilePath = $PROFILE
    $marker = "# Synthesis - AI operations partner"

    if ($profilePath -and (Test-Path $profilePath)) {
        $profileContent = Get-Content $profilePath -Raw -ErrorAction SilentlyContinue
        if ($profileContent -and $profileContent -match [regex]::Escape($marker)) {
            Write-Info "PowerShell profile already configured"
        } else {
            $profileBlock = @"

$marker
`$env:PATH = "`$env:USERPROFILE\.synthesis\bin;`$env:PATH"
Set-Alias synthesis "`$env:USERPROFILE\.synthesis\bin\synthesis.bat"
Set-Alias synthesis-update "`$env:USERPROFILE\.synthesis\bin\synthesis-update.bat"
"@
            Add-Content -Path $profilePath -Value $profileBlock
            Write-Info "Added to PowerShell profile: $profilePath"
        }
    } else {
        # Profile does not exist; create it
        $profileDir = Split-Path $profilePath -Parent
        if (-not (Test-Path $profileDir)) {
            New-Item -ItemType Directory -Path $profileDir -Force | Out-Null
        }
        $profileBlock = @"
$marker
`$env:PATH = "`$env:USERPROFILE\.synthesis\bin;`$env:PATH"
Set-Alias synthesis "`$env:USERPROFILE\.synthesis\bin\synthesis.bat"
Set-Alias synthesis-update "`$env:USERPROFILE\.synthesis\bin\synthesis-update.bat"
"@
        Set-Content -Path $profilePath -Value $profileBlock
        Write-Info "Created PowerShell profile: $profilePath"
    }
} else {
    Write-Detail "Skipping profile update (-NoProfile specified)"
}

# ---------------------------------------------------------------------------
# Step 10: Verify Installation
# ---------------------------------------------------------------------------
Write-Step "Verifying installation..."

$currentJar = Join-Path $libDir "current.jar"
try {
    $verOutput = & java -jar $currentJar --version 2>&1
    Write-Info "Verification passed: $verOutput"
} catch {
    Write-Warn "JAR verification skipped (may need terminal restart)"
}

# ---------------------------------------------------------------------------
# Done!
# ---------------------------------------------------------------------------
$CreatedSynthesisHome = $false  # Disable cleanup

Write-Host ""
Write-Host "Synthesis installed successfully!" -ForegroundColor Green
Write-Host ""
Write-Host "  Installation:  $SynthesisHome"
Write-Host "  Version:       $installedVersion"
Write-Host "  Launcher:      $launcherPath"
Write-Host "  JAR:           $libDir\$jarName"
Write-Host "  Link method:   $linkMethod"
Write-Host ""
Write-Host "To get started, open a new terminal and then:" -ForegroundColor Yellow
Write-Host ""
Write-Host "  synthesis --help                  # Show all commands" -ForegroundColor Cyan
Write-Host "  synthesis init ~\my-project       # Initialize a workspace" -ForegroundColor Cyan
Write-Host "  synthesis scan                    # Scan and index files" -ForegroundColor Cyan
Write-Host '  synthesis search "query"          # Search your workspace' -ForegroundColor Cyan
Write-Host ""
Write-Host "  synthesis-update                  # Update to latest version" -ForegroundColor Cyan
Write-Host "  synthesis-update -Check           # Check for updates" -ForegroundColor Cyan
Write-Host ""
