@echo off
REM ============================================================
REM  Scaper Dev Launcher Setup
REM  Swaps RuneLite.exe with a wrapper that launches the dev
REM  client through Gradle, so Jagex Launcher auth works.
REM ============================================================

set RUNELITE_DIR=%LOCALAPPDATA%\RuneLite
set PLUGIN_DIR=C:\Users\Jared\Desktop\scaper-plugin
set CSC=C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe

echo.
echo === Scaper Dev Launcher Setup ===
echo.

REM Check RuneLite is installed
if not exist "%RUNELITE_DIR%\RuneLite.exe" (
    echo ERROR: RuneLite.exe not found at %RUNELITE_DIR%
    pause
    exit /b 1
)

REM Back up original RuneLite.exe (only if not already backed up)
if not exist "%RUNELITE_DIR%\RuneLite_original.exe" (
    echo Backing up original RuneLite.exe...
    copy "%RUNELITE_DIR%\RuneLite.exe" "%RUNELITE_DIR%\RuneLite_original.exe"
    echo   Backed up to: RuneLite_original.exe
) else (
    echo Original RuneLite.exe already backed up.
)

REM Compile the wrapper
echo Compiling dev wrapper...
"%CSC%" /nologo /out:"%RUNELITE_DIR%\RuneLite.exe" "%PLUGIN_DIR%\dev\RuneLiteDevWrapper.cs"

if %ERRORLEVEL% neq 0 (
    echo ERROR: Compilation failed! Restoring original...
    copy "%RUNELITE_DIR%\RuneLite_original.exe" "%RUNELITE_DIR%\RuneLite.exe"
    pause
    exit /b 1
)

echo.
echo === Setup Complete ===
echo.
echo How to use:
echo   1. Open the Jagex Launcher
echo   2. Click "Play" on RuneLite
echo   3. Your dev client will launch with the Scaper plugin loaded
echo   4. You can log in with your Jagex account normally
echo.
echo To restore the original RuneLite.exe later, run:
echo   copy "%RUNELITE_DIR%\RuneLite_original.exe" "%RUNELITE_DIR%\RuneLite.exe"
echo.
pause
