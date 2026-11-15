@echo off
chcp 65001 >nul
cd /d "%~dp0"
where py >nul 2>&1
if errorlevel 1 (python setup_local_ai.py) else (py -3 setup_local_ai.py)
pause
