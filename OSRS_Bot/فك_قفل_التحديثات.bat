@echo off
title Unlock Windows Update Services
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0unlock_update_services.ps1"
