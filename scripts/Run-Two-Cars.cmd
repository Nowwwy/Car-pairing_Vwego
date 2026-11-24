@echo off
chcp 65001 >nul
cd /d "%~dp0.."
where py >nul 2>&1
if errorlevel 1 (python -X utf8 -m scripts.run_two) else (py -3 -X utf8 -m scripts.run_two)
pause
