@echo off
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy-phone.ps1"
if errorlevel 1 (
  echo.
  echo 部署失败，请查看上面的提示。
  pause
)
