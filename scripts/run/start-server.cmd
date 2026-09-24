@echo off
rem start-server - start deepseek-browser-use in the background and wait for /playwright/health
rem Usage: scripts\run\start-server.cmd [-Port 10049] [-Engine chromium] [-ProfileDir <dir>] [-Jar <jar>]
rem NOTE: keep this file ASCII-only (cmd.exe reads .cmd in the OEM codepage).
rem Why a .cmd wrapper: this machine's PowerShell execution policy is Restricted, so a bare
rem ".\start-server.ps1" is refused. -ExecutionPolicy Bypass only affects this child process.
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-server.ps1" %*
exit /b %ERRORLEVEL%
