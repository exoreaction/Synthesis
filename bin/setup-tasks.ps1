#Requires -Version 5.1
<#
.SYNOPSIS
    Set up Windows Scheduled Tasks for Synthesis automatic maintenance.

.DESCRIPTION
    Registers two scheduled tasks under your Windows user account (no admin required):

      SynthesisMaintain   - Runs 'synthesis maintain --quiet' every 4 hours
                            during business hours (08:00, 12:00, 16:00, 20:00),
                            Monday to Friday. Keeps the search index fresh.

      SynthesisMCP        - Starts the Synthesis MCP server at login so Claude
                            Desktop / Claude Code can always connect to it.

.PARAMETER Workspace
    The folder Synthesis should index. Defaults to your Documents folder.

.PARAMETER HttpPort
    Port for the MCP HTTP server. Default: 8765.

.PARAMETER SkipMaintain
    Skip registering the SynthesisMaintain task.

.PARAMETER SkipMcp
    Skip registering the SynthesisMCP task.

.PARAMETER Remove
    Remove both tasks instead of creating them.

.EXAMPLE
    # Set up with defaults (Documents folder, port 8765)
    .\setup-tasks.ps1

.EXAMPLE
    # Set up with a custom workspace
    .\setup-tasks.ps1 -Workspace "C:\Work\Projects"

.EXAMPLE
    # Remove both tasks
    .\setup-tasks.ps1 -Remove

.NOTES
    Tasks run under your user account — no Administrator rights required.
    Logs are written to %USERPROFILE%\.synthesis\logs\
    Copyright (c) 2026 eXOReaction AS. All rights reserved.
#>

[CmdletBinding()]
param(
    [string]$Workspace = (Join-Path $env:USERPROFILE "Documents"),
    [int]$HttpPort = 8765,
    [switch]$SkipMaintain,
    [switch]$SkipMcp,
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'

$SynthesisHome = if ($env:SYNTHESIS_HOME) { $env:SYNTHESIS_HOME } else { Join-Path $env:USERPROFILE ".synthesis" }
$LogDir        = Join-Path $SynthesisHome "logs"
$MaintainLog   = Join-Path $LogDir "maintain.log"
$McpLog        = Join-Path $LogDir "mcp-server.log"

$MaintainTaskName = "SynthesisMaintain"
$McpTaskName      = "SynthesisMCP"

function Write-Ok   { param([string]$m) Write-Host "  [OK]   $m" -ForegroundColor Green }
function Write-Warn { param([string]$m) Write-Host "  [WARN] $m" -ForegroundColor Yellow }
function Write-Step { param([string]$m) Write-Host "`n==> $m" -ForegroundColor Cyan }

# ---------------------------------------------------------------------------
# Remove mode
# ---------------------------------------------------------------------------
if ($Remove) {
    Write-Step "Removing Synthesis scheduled tasks..."
    foreach ($name in @($MaintainTaskName, $McpTaskName)) {
        if (Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue) {
            Unregister-ScheduledTask -TaskName $name -Confirm:$false
            Write-Ok "Removed: $name"
        } else {
            Write-Warn "Not found (nothing to remove): $name"
        }
    }
    Write-Host "`nDone. Tasks removed." -ForegroundColor Green
    exit 0
}

# ---------------------------------------------------------------------------
# Validate
# ---------------------------------------------------------------------------
Write-Step "Validating..."

if (-not (Test-Path $SynthesisHome)) {
    Write-Host "[ERROR] Synthesis is not installed at $SynthesisHome" -ForegroundColor Red
    Write-Host "        Run install.ps1 first." -ForegroundColor Red
    exit 1
}

$synthesisBat = Join-Path $SynthesisHome "bin\synthesis.bat"
if (-not (Test-Path $synthesisBat)) {
    Write-Host "[ERROR] synthesis.bat not found at $synthesisBat" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $Workspace)) {
    Write-Host "[ERROR] Workspace not found: $Workspace" -ForegroundColor Red
    Write-Host "        Create it first, or run: synthesis init <path>" -ForegroundColor Yellow
    exit 1
}

Write-Ok "Synthesis home:  $SynthesisHome"
Write-Ok "Workspace:       $Workspace"
Write-Ok "MCP port:        $HttpPort"

# Create log directory
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

# ---------------------------------------------------------------------------
# Task 1: SynthesisMaintain
# ---------------------------------------------------------------------------
if (-not $SkipMaintain) {
    Write-Step "Registering SynthesisMaintain (index refresh every 4 hours)..."

    # Wrapper: run maintain and append timestamped output to log
    $maintainCmd = @"
cmd /c "echo [%DATE% %TIME%] Starting maintain >> "$MaintainLog" 2>&1 & "$synthesisBat" maintain --workspace "$Workspace" --quiet >> "$MaintainLog" 2>&1 & echo [%DATE% %TIME%] Done >> "$MaintainLog" 2>&1"
"@

    $action = New-ScheduledTaskAction `
        -Execute "powershell.exe" `
        -Argument "-NoProfile -NonInteractive -WindowStyle Hidden -Command `"& '$synthesisBat' maintain --workspace '$Workspace' --quiet 2>&1 | Add-Content -Path '$MaintainLog'`""

    # Run at 08:00, 12:00, 16:00, 20:00 Mon–Fri
    $triggers = @(
        $(New-ScheduledTaskTrigger -Weekly -DaysOfWeek Monday,Tuesday,Wednesday,Thursday,Friday -At "08:00"),
        $(New-ScheduledTaskTrigger -Weekly -DaysOfWeek Monday,Tuesday,Wednesday,Thursday,Friday -At "12:00"),
        $(New-ScheduledTaskTrigger -Weekly -DaysOfWeek Monday,Tuesday,Wednesday,Thursday,Friday -At "16:00"),
        $(New-ScheduledTaskTrigger -Weekly -DaysOfWeek Monday,Tuesday,Wednesday,Thursday,Friday -At "20:00")
    )

    $settings = New-ScheduledTaskSettingsSet `
        -ExecutionTimeLimit (New-TimeSpan -Minutes 30) `
        -StartWhenAvailable `
        -RunOnlyIfNetworkAvailable:$false `
        -WakeToRun:$false

    # Remove old task if it exists
    if (Get-ScheduledTask -TaskName $MaintainTaskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $MaintainTaskName -Confirm:$false
    }

    Register-ScheduledTask `
        -TaskName $MaintainTaskName `
        -Action $action `
        -Trigger $triggers `
        -Settings $settings `
        -Description "Synthesis: refresh search index every 4 hours (Mon-Fri)" `
        -RunLevel Limited | Out-Null

    Write-Ok "Registered: $MaintainTaskName"
    Write-Ok "Schedule:   08:00 / 12:00 / 16:00 / 20:00 (Mon-Fri)"
    Write-Ok "Log:        $MaintainLog"
}

# ---------------------------------------------------------------------------
# Task 2: SynthesisMCP (start MCP server at login)
# ---------------------------------------------------------------------------
if (-not $SkipMcp) {
    Write-Step "Registering SynthesisMCP (MCP server at login)..."

    $mcpBat = Join-Path $SynthesisHome "bin\synthesis-mcp-server.bat"
    if (-not (Test-Path $mcpBat)) {
        Write-Warn "synthesis-mcp-server.bat not found — skipping MCP task."
        Write-Warn "Install the MCP server JAR first, then re-run this script."
    } else {
        $mcpAction = New-ScheduledTaskAction `
            -Execute $mcpBat `
            -Argument "--workspace `"$Workspace`" --http-port $HttpPort"

        $mcpTrigger = New-ScheduledTaskTrigger -AtLogon

        $mcpSettings = New-ScheduledTaskSettingsSet `
            -ExecutionTimeLimit (New-TimeSpan -Days 1) `
            -RestartCount 3 `
            -RestartInterval (New-TimeSpan -Minutes 2) `
            -StartWhenAvailable `
            -WakeToRun:$false

        if (Get-ScheduledTask -TaskName $McpTaskName -ErrorAction SilentlyContinue) {
            Unregister-ScheduledTask -TaskName $McpTaskName -Confirm:$false
        }

        Register-ScheduledTask `
            -TaskName $McpTaskName `
            -Action $mcpAction `
            -Trigger $mcpTrigger `
            -Settings $mcpSettings `
            -Description "Synthesis: start MCP server at login (port $HttpPort)" `
            -RunLevel Limited | Out-Null

        Write-Ok "Registered: $McpTaskName"
        Write-Ok "Trigger:    at login"
        Write-Ok "Port:       $HttpPort"
        Write-Ok "Restarts:   up to 3x if it crashes"
    }
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "Setup complete!" -ForegroundColor Green
Write-Host ""
Write-Host "  Tasks registered under your user account (no admin required)."
Write-Host "  View / edit them: open Task Scheduler (search in Start menu)"
Write-Host ""

if (-not $SkipMaintain) {
    Write-Host "  SynthesisMaintain — next run at 08:00 tomorrow (Mon-Fri)" -ForegroundColor Cyan
    Write-Host "  Run it now to verify:  synthesis maintain --workspace `"$Workspace`" --quiet" -ForegroundColor DarkGray
}

if (-not $SkipMcp) {
    Write-Host "  SynthesisMCP       — starts at your next login" -ForegroundColor Cyan
    Write-Host "  Start it now:          synthesis-mcp-server --workspace `"$Workspace`" --http-port $HttpPort" -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "  Logs: $LogDir" -ForegroundColor DarkGray
Write-Host "  To remove all tasks: .\setup-tasks.ps1 -Remove" -ForegroundColor DarkGray
Write-Host ""
