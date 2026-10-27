@echo off
setlocal
chcp 65001 >nul
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0installer\Install-X04G.ps1" -PackageRoot "%~dp0." -Disable %*
set "X04G_EXIT_CODE=%ERRORLEVEL%"
echo.
if not "%X04G_NONINTERACTIVE%"=="1" pause
exit /b %X04G_EXIT_CODE%
