@echo off
chcp 65001 >nul
title OSRS Microbot
echo ========================================================
echo   جاري تشغيل عميل Microbot الآلي...
echo   برجاء الانتظار ثواني لتحميل اللعبة وظهور النافذة...
echo ========================================================
cd /d "C:\Users\USER\AppData\Local\RuneLite"
"jre\bin\java.exe" -Xmx1024m -jar "microbot-2.6.34.jar"
pause