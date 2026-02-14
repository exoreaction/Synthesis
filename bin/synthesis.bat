@echo off
REM =========================================================================
REM Synthesis Launcher for Windows
REM Launches the Synthesis CLI with pre-flight checks and auto-update
REM notifications.
REM
REM This script is installed to %USERPROFILE%\.synthesis\bin\synthesis.bat
REM by install.ps1. It can also be run directly from the repository.
REM
REM Usage:
REM   synthesis init %USERPROFILE%\my-project
REM   synthesis scan
REM   synthesis search "query"
REM   synthesis --version
REM
REM Environment:
REM   SYNTHESIS_HOME              Installation directory (default: %USERPROFILE%\.synthesis)
REM   SYNTHESIS_NO_UPDATE_CHECK   Set to 1 to disable auto-update checks
REM   SYNTHESIS_JAVA_OPTS         Extra JVM options (e.g., "-Xmx2g")
REM
REM Copyright (c) 2026 eXOReaction AS. All rights reserved.
REM =========================================================================

setlocal enabledelayedexpansion

REM ---------------------------------------------------------------------------
REM Configuration
REM ---------------------------------------------------------------------------
if "%SYNTHESIS_HOME%"=="" set "SYNTHESIS_HOME=%USERPROFILE%\.synthesis"
set "JAR_PATH=%SYNTHESIS_HOME%\lib\current.jar"
set "MIN_JAVA_VERSION=17"

REM ---------------------------------------------------------------------------
REM Pre-flight: Check JAR exists
REM ---------------------------------------------------------------------------
if not exist "%JAR_PATH%" (
    echo [ERROR] Synthesis JAR not found at %JAR_PATH% 1>&2
    echo. 1>&2
    echo   Synthesis is not installed, or the installation is incomplete. 1>&2
    echo   Install with: 1>&2
    echo     powershell -ExecutionPolicy Bypass -Command "iex (iwr -useb https://raw.githubusercontent.com/exoreaction/Synthesis/main/bin/install.ps1).Content" 1>&2
    echo. 1>&2
    echo   Or from a local clone: 1>&2
    echo     powershell -ExecutionPolicy Bypass -File bin\install.ps1 1>&2
    exit /b 1
)

REM ---------------------------------------------------------------------------
REM Pre-flight: Check Java exists
REM ---------------------------------------------------------------------------
where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found. Java %MIN_JAVA_VERSION%+ is required. 1>&2
    echo. 1>&2
    echo   Install Java: 1>&2
    echo     winget install Microsoft.OpenJDK.17 1>&2
    echo     Or: https://adoptium.net/temurin/releases/ 1>&2
    exit /b 1
)

REM ---------------------------------------------------------------------------
REM Pre-flight: Check Java version >= 17
REM ---------------------------------------------------------------------------
set "JAVA_MAJOR=0"
for /f "tokens=3 delims= " %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set "JAVA_VER_RAW=%%~v"
    goto :got_java_ver
)
:got_java_ver

REM Extract major version number from version string
REM Handles both "17.0.1" and "1.8.0" formats
for /f "tokens=1 delims=." %%m in ("%JAVA_VER_RAW%") do set "JAVA_MAJOR=%%m"

REM Handle legacy 1.x format (e.g., 1.8.0 -> major = 8)
if "%JAVA_MAJOR%"=="1" (
    for /f "tokens=2 delims=." %%m in ("%JAVA_VER_RAW%") do set "JAVA_MAJOR=%%m"
)

if %JAVA_MAJOR% LSS %MIN_JAVA_VERSION% (
    echo [ERROR] Java %JAVA_MAJOR% found, but Java %MIN_JAVA_VERSION%+ is required. 1>&2
    echo. 1>&2
    echo   Upgrade your Java installation: 1>&2
    echo     winget install Microsoft.OpenJDK.17 1>&2
    exit /b 1
)

REM ---------------------------------------------------------------------------
REM Auto-Update Check (daily, non-blocking)
REM ---------------------------------------------------------------------------
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

REM Check if we should run a new update check
REM Uses forfiles to test if last-update-check file is older than 1 day
if exist "%LAST_CHECK_FILE%" (
    forfiles /P "%SYNTHESIS_HOME%\.metadata" /M "last-update-check" /D -1 >nul 2>&1
    if not errorlevel 1 (
        REM File is older than 1 day - run background check
        if exist "%SYNTHESIS_HOME%\bin\update.ps1" (
            start "" /b powershell -NoProfile -ExecutionPolicy Bypass -Command ^
                "try { & '%SYNTHESIS_HOME%\bin\update.ps1' -Check -Quiet > '%RESULT_FILE%' 2>$null } catch {}; Get-Date -UFormat '%%s' > '%LAST_CHECK_FILE%'" >nul 2>&1
        )
    )
) else (
    REM No last check file exists - create one so forfiles works next time
    if exist "%SYNTHESIS_HOME%\.metadata\" (
        echo 0 > "%LAST_CHECK_FILE%"
    )
)

:skip_update_check

REM ---------------------------------------------------------------------------
REM Launch Synthesis
REM ---------------------------------------------------------------------------
set "JAVA_OPTS=%SYNTHESIS_JAVA_OPTS%"

java %JAVA_OPTS% -jar "%JAR_PATH%" %*
exit /b %errorlevel%
