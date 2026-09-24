@echo off
rem dsb - thin Windows wrapper so you can type "dsb" instead of "python <path>\dsb.py"
rem Usage: dsb health / dsb start --browser firefox / dsb run go_to_url -p url=https://example.com
rem NOTE: keep this file ASCII-only. cmd.exe reads .cmd in the OEM codepage, and UTF-8 Chinese
rem comments get mangled into characters cmd treats as syntax (stray "not recognized" errors).
setlocal
set "PY=python"
where python >nul 2>nul || set "PY=py"
"%PY%" "%~dp0dsb.py" %*
exit /b %ERRORLEVEL%
