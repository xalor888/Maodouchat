@echo off
setlocal
REM 钉死 JDK 21：系统默认 JDK 25 会让 Kotlin DSL 初始化抛 IllegalArgumentException: 25.0.2
set "JAVA_HOME=C:\Program Files\Android\Android Studio1\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d D:\Maodouchat

echo === JAVA used by this build === > D:\Maodouchat\build_verify.log
"%JAVA_HOME%\bin\java" -version >> D:\Maodouchat\build_verify.log 2>&1
echo. >> D:\Maodouchat\build_verify.log

call gradlew.bat :app:compileDebugKotlin --no-daemon -Dorg.gradle.jvmargs="-Xmx2048m" >> D:\Maodouchat\build_verify.log 2>&1
set "RC=%ERRORLEVEL%"
echo EXITCODE:%RC% >> D:\Maodouchat\build_verify.log
endlocal & exit /b %RC%
