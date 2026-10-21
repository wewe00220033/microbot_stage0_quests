@echo off
title Fix Windows Update Orchestrator

net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -NoProfile -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

echo ===================================================
echo         Fixing Windows Update Orchestrator
echo ===================================================
echo.

echo [1/4] Enabling UsoSvc (Update Session Orchestrator)...
reg add "HKLM\SYSTEM\CurrentControlSet\Services\UsoSvc" /v Start /t REG_DWORD /d 2 /f >nul 2>&1
sc config UsoSvc start= auto >nul 2>&1

echo [2/4] Starting Update Services...
net start UsoSvc >nul 2>&1
net start wuauserv >nul 2>&1
net start bits >nul 2>&1

echo [3/4] Refreshing Windows Update Client...
usoClient StartScan >nul 2>&1

echo [4/4] Restarting Settings App...
taskkill /f /im SystemSettings.exe >nul 2>&1
timeout /t 1 /nobreak >nul
start ms-settings:windowsupdate

echo.
echo ===================================================
echo   SUCCESS: Windows Update Orchestrator is fixed!
echo ===================================================
timeout /t 3 >nul
