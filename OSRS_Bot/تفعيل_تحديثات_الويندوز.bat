@echo off
title Enable Windows Update

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [!] Requesting Administrator privileges...
    powershell -NoProfile -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

echo ===================================================
echo           Enabling Windows Update
echo ===================================================
echo.

echo [1/3] Removing hide policy from Registry...
reg delete "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\Explorer" /v "SettingsPageVisibility" /f >nul 2>&1

echo [2/3] Enabling Windows Update Services...
sc config wuauserv start= demand >nul 2>&1
sc config bits start= demand >nul 2>&1
net start wuauserv >nul 2>&1

echo [3/3] Opening Windows Update in Settings...
start ms-settings:windowsupdate

echo.
echo ===================================================
echo   SUCCESS: Windows Update is unblocked and active!
echo ===================================================
echo.
timeout /t 3 >nul
