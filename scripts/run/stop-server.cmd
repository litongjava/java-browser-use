@echo off
rem stop-server - shutdown tasks + shared browser, then kill the process tree (no orphan browser)
rem Usage: scripts\run\stop-server.cmd [-Port 10049]
rem NOTE: keep this file ASCII-only (cmd.exe reads .cmd in the OEM codepage).
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-server.ps1" %*
exit /b %ERRORLEVEL%
