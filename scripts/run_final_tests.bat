@echo off
cd /d D:\Maodouchat
echo === Android Debug Compile ===
call gradlew.bat :app:compileDebugKotlin --no-daemon --quiet
if %errorlevel% neq 0 (echo ANDROID COMPILE FAILED & exit /b 1)

echo === Android Unit Tests ===
call gradlew.bat :app:testDebugUnitTest --no-daemon --quiet
if %errorlevel% neq 0 (echo ANDROID TESTS FAILED & exit /b 1)

cd server
echo === Server Tests ===
call ..\gradlew.bat test --no-daemon --quiet
if %errorlevel% neq 0 (echo SERVER TESTS FAILED & exit /b 1)

echo === ALL GREEN ===
