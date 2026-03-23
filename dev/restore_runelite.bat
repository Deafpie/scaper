@echo off
REM Restore the original RuneLite.exe

set RUNELITE_DIR=%LOCALAPPDATA%\RuneLite

if not exist "%RUNELITE_DIR%\RuneLite_original.exe" (
    echo No backup found. Nothing to restore.
    pause
    exit /b 1
)

echo Restoring original RuneLite.exe...
copy /Y "%RUNELITE_DIR%\RuneLite_original.exe" "%RUNELITE_DIR%\RuneLite.exe"
echo Done! RuneLite is back to normal.
pause
