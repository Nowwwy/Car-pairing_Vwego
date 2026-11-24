@echo off
chcp 65001 >nul
cd /d "%~dp0.."
where py >nul 2>&1
if errorlevel 1 (python -m scripts.setup_local_ai) else (py -3 -m scripts.setup_local_ai)
pause
