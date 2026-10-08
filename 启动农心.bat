@echo off
setlocal EnableExtensions DisableDelayedExpansion
title Nongxin Agent - Team Development
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\development\dev.ps1" -Action Start %*
set "NX_EXIT=%ERRORLEVEL%"
if not "%NX_EXIT%"=="0" (
  echo Startup failed. See the error above and the teammate quickstart.
  pause
)
exit /b %NX_EXIT%
