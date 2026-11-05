@echo off
set SCRCPY=%~dp0scrcpy\scrcpy-win64-v5.0.1\scrcpy.exe
start "" "%SCRCPY%" --serial GZ25030832206578 --no-audio --max-size 1600 --stay-awake --window-title "RayNeo PocketTV2"
