#Requires -Version 5.1
<#
.SYNOPSIS
    Synthesis Uninstaller for Windows.

.DESCRIPTION
    Cleanly removes the Synthesis installation, including the installation directory,
    User PATH entries, PowerShell profile entries, and optionally Claude Code skills.

.PARAMETER Yes
    Skip confirmation prompt.

.PARAMETER KeepData
    Keep the installation directory but clean environment entries.

.PARAMETER RemoveSkills
    Also remove Claude Code skills for Synthesis.

.EXAMPLE
    .\uninstall.ps1                       # Interactive uninstall
    .\uninstall.ps1 -Yes                  # Skip confirmation
    .\uninstall.ps1 -Yes -RemoveSkills    # Remove everything including skills

.NOTES
    Copyright (c) 2026 eXOReaction AS. All rights reserved.
    Requires: Windows 10+, PowerShell 5.1+
#>

[CmdletBinding()]
param(
    [switch]$Yes,
    [switch]$KeepData,
    [switch]$RemoveSkills
)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
$ErrorActionPreference = 'Stop'

$SynthesisHome = if ($env:SYNTHESIS_HOME) { $env:SYNTHESIS_HOME } else { Join-Path $env:USERPROFILE ".synthesis" }

# ---------------------------------------------------------------------------
# Output Helpers
# ---------------------------------------------------------------------------
function Write-Info  { param([string]$Message) Write-Host "[INFO]  $Message" -ForegroundColor Green }
function Write-Warn  { param([string]$Message) Write-Host "[WARN]  $Message" -ForegroundColor Yellow }
function Write-Err   { param([string]$Message) Write-Host "[ERROR] $Message" -ForegroundColor Red }
function Write-Step  { param([string]$Message) Write-Host "==> $Message" -ForegroundColor Blue }
function Write-Detail { param([string]$Message) Write-Host "    $Message" }

# ---------------------------------------------------------------------------
# Check Installation
# ---------------------------------------------------------------------------
if (-not (Test-Path $SynthesisHome)) {
    Write-Err "Synthesis is not installed at $SynthesisHome"
    Write-Detail "Nothing to uninstall."
    exit 0
}

# ---------------------------------------------------------------------------
# Survey What Will Be Removed
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "Synthesis Uninstaller" -ForegroundColor White
Write-Host ""

Write-Step "The following will be removed:"

# Installation directory
$dirSize = "?"
$jarCount = 0
try {
    $dirSizeBytes = (Get-ChildItem $SynthesisHome -Recurse -ErrorAction SilentlyContinue |
        Measure-Object -Property Length -Sum).Sum
    if ($dirSizeBytes -ge 1MB) {
        $dirSize = "{0:N1} MB" -f ($dirSizeBytes / 1MB)
    } elseif ($dirSizeBytes -ge 1KB) {
        $dirSize = "{0:N0} KB" -f ($dirSizeBytes / 1KB)
    } else {
        $dirSize = "$dirSizeBytes bytes"
    }
} catch { }

$jars = Get-ChildItem (Join-Path $SynthesisHome "lib\synthesis-*.jar") -ErrorAction SilentlyContinue
$jarCount = if ($jars) { $jars.Count } else { 0 }

Write-Detail "$SynthesisHome\ ($dirSize)"
Write-Detail "  bin\synthesis.bat (launcher)"
Write-Detail "  bin\synthesis-update.bat (update launcher)"
Write-Detail "  bin\update.ps1 (updater)"
Write-Detail "  bin\uninstall.ps1 (this script)"
Write-Detail "  lib\ ($jarCount JAR files)"
Write-Detail "  .metadata\ (installation data)"

# User PATH
$binDir = Join-Path $SynthesisHome "bin"
$currentUserPath = [Environment]::GetEnvironmentVariable("PATH", "User")
$pathContainsSynthesis = $false
if ($currentUserPath) {
    $pathEntries = $currentUserPath.Split(';')
    if ($pathEntries -contains $binDir) {
        $pathContainsSynthesis = $true
    }
}

if ($pathContainsSynthesis) {
    Write-Host ""
    Write-Detail "User PATH:"
    Write-Detail "  $binDir (will be removed from PATH)"
}

# PowerShell Profile
$profilePath = $PROFILE
$profileContainsSynthesis = $false
$marker = "# Synthesis - AI operations partner"

if ($profilePath -and (Test-Path $profilePath)) {
    $profileContent = Get-Content $profilePath -Raw -ErrorAction SilentlyContinue
    if ($profileContent -and $profileContent -match [regex]::Escape($marker)) {
        $profileContainsSynthesis = $true
        Write-Host ""
        Write-Detail "PowerShell profile:"
        Write-Detail "  Synthesis entries in $profilePath"
    }
}

# Claude Code skills
$skillFiles = @()
$claudeSkillsDir = Join-Path $env:USERPROFILE ".claude\skills"
if (Test-Path $claudeSkillsDir) {
    $skillFiles = Get-ChildItem $claudeSkillsDir -Filter "*synthesis*" -Recurse -ErrorAction SilentlyContinue
}

if ($skillFiles.Count -gt 0) {
    Write-Host ""
    if ($RemoveSkills) {
        Write-Detail "Claude Code skills (will remove):"
    } else {
        Write-Detail "Claude Code skills (keeping, use -RemoveSkills to remove):"
    }
    foreach ($f in $skillFiles) {
        Write-Detail "  $($f.FullName)"
    }
}

# Workspace data note
Write-Host ""
Write-Detail "Note: Workspace .synthesis\ directories (project indexes) are NOT removed."
Write-Detail "  These live inside your project directories and contain search indexes."
Write-Detail "  Remove them manually if desired."

# ---------------------------------------------------------------------------
# Confirmation
# ---------------------------------------------------------------------------
Write-Host ""

if (-not $Yes) {
    $response = Read-Host "Are you sure you want to uninstall Synthesis? [y/N]"
    if ($response -notmatch '^[yY]') {
        Write-Info "Uninstall cancelled."
        exit 0
    }
}

Write-Host ""

# ---------------------------------------------------------------------------
# Remove Installation Directory
# ---------------------------------------------------------------------------
if (-not $KeepData) {
    Write-Step "Removing installation..."

    if (Test-Path $SynthesisHome) {
        Remove-Item $SynthesisHome -Recurse -Force -ErrorAction SilentlyContinue
        if (-not (Test-Path $SynthesisHome)) {
            Write-Info "Removed $SynthesisHome"
        } else {
            Write-Warn "Could not fully remove $SynthesisHome (some files may be in use)"
            Write-Detail "Try closing any terminals using Synthesis and retry."
        }
    }
} else {
    Write-Detail "Keeping installation directory (-KeepData specified)"
}

# ---------------------------------------------------------------------------
# Clean User PATH
# ---------------------------------------------------------------------------
Write-Step "Cleaning environment..."

if ($pathContainsSynthesis) {
    $newPathEntries = $currentUserPath.Split(';') | Where-Object { $_ -ne $binDir -and $_ -ne '' }
    $newPath = $newPathEntries -join ';'
    [Environment]::SetEnvironmentVariable("PATH", $newPath, "User")
    Write-Info "Removed $binDir from User PATH"
} else {
    Write-Detail "No PATH entry to clean"
}

# ---------------------------------------------------------------------------
# Clean PowerShell Profile
# ---------------------------------------------------------------------------
Write-Step "Cleaning PowerShell profile..."

if ($profileContainsSynthesis -and (Test-Path $profilePath)) {
    $lines = Get-Content $profilePath
    $newLines = @()
    $skipBlock = $false

    foreach ($line in $lines) {
        if ($line -match [regex]::Escape($marker)) {
            $skipBlock = $true
            continue
        }

        if ($skipBlock) {
            # Skip lines that are part of the Synthesis block
            if ($line -match '\.synthesis' -or $line -match 'synthesis\.bat' -or $line -match 'synthesis-update') {
                continue
            }
            # Empty line after block - skip one
            if ($line.Trim() -eq '') {
                $skipBlock = $false
                continue
            }
            # Non-Synthesis line - stop skipping
            $skipBlock = $false
        }

        $newLines += $line
    }

    # Remove trailing blank lines
    while ($newLines.Count -gt 0 -and $newLines[-1].Trim() -eq '') {
        $newLines = $newLines[0..($newLines.Count - 2)]
    }

    Set-Content -Path $profilePath -Value ($newLines -join "`n")
    Write-Info "Cleaned $profilePath"
} else {
    Write-Detail "No profile entries to clean"
}

# ---------------------------------------------------------------------------
# Remove Claude Code Skills (if requested)
# ---------------------------------------------------------------------------
if ($RemoveSkills -and $skillFiles.Count -gt 0) {
    Write-Step "Removing Claude Code skills..."
    foreach ($skillFile in $skillFiles) {
        if (Test-Path $skillFile.FullName) {
            Remove-Item $skillFile.FullName -Force
            Write-Info "Removed $($skillFile.FullName)"
        }
    }
}

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "Synthesis has been uninstalled." -ForegroundColor Green
Write-Host ""
Write-Host "  Open a new terminal for PATH changes to take effect." -ForegroundColor DarkGray

if ($skillFiles.Count -gt 0 -and -not $RemoveSkills) {
    Write-Host "  Claude Code skills were kept. Use -RemoveSkills to remove them." -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "  To reinstall:"
Write-Host '    iex (iwr -useb https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.ps1).Content'
Write-Host ""
