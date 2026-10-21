@echo off
chcp 65001 > nul
set PYTHONIOENCODING=utf-8
title "OSRS Stronghold Bot - 100%% Local"
color 0A

echo ===================================================================
echo    OSRS Stronghold of Security Bot (Local 100%%)
echo    Goal: 10,000 GP + Fancy Boots + Bank Deposit
echo ===================================================================
echo.

python "d:\SMS\.hRhR_gateway-service\OSRS_Bot\run_stronghold_bot.py"

pause
