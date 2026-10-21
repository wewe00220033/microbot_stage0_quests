@echo off
chcp 65001 >nul
title تحديث واسترجاع كافة محادثات Antigravity IDE (400+ شات)
color 0A

echo ===============================================================================
echo          أداة تثبيت فهارس المحادثات التاريخية بالكامل (400+ شات)
echo ===============================================================================
echo.
echo [1/3] إغلاق برنامج Antigravity IDE لفك قفل قاعدة البيانات ومنع مسح الذاكرة...
taskkill /F /IM "Antigravity IDE.exe" >nul 2>&1
timeout /t 2 /nobreak >nul

echo.
echo [2/3] جاري دمج وتثبيت فهرس الـ 417 محادثة في state.vscdb ونقل مساحات العمل...
python "C:\Users\USER\.gemini\antigravity-ide\brain\839de17b-c769-42ef-b953-bebdf6e2b45a\scratch\master_restore_index.py"

echo.
echo [3/3] جاري تشغيل Antigravity IDE مجدداً بالفهرس المكتمل...
timeout /t 1 /nobreak >nul
start "" "C:\Users\USER\AppData\Local\Programs\Antigravity IDE\Antigravity IDE.exe" "c:\Users\USER\Downloads\111"

echo.
echo ===============================================================================
echo   ✅ تم الانتهاء بنجاح! افتح الآن قائمة المحادثات (Search all convos) في البرنامج.
echo ===============================================================================
timeout /t 5
