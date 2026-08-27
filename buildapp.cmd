@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

title HEIMDALL Android Builder

echo ==========================================================
echo   HEIMDALL - LOCAL ANDROID APK BUILDER
echo ==========================================================
echo.

rem ----------------------------------------------------------
rem Find Java 17+ (prefer Android Studio's bundled JBR)
rem ----------------------------------------------------------
set "JAVA_EXE="

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

if not defined JAVA_EXE if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
    set "JAVA_EXE=!JAVA_HOME!\bin\java.exe"
)

if not defined JAVA_EXE if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
    set "JAVA_EXE=!JAVA_HOME!\bin\java.exe"
)

if not defined JAVA_EXE (
    for /f "delims=" %%J in ('where java.exe 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%J"
)

if not defined JAVA_EXE (
    echo [ERROR] Java was not found.
    echo Install Android Studio, then run this file again.
    echo https://developer.android.com/studio
    pause
    exit /b 1
)

echo [OK] Java: %JAVA_EXE%

rem ----------------------------------------------------------
rem Locate Android SDK
rem ----------------------------------------------------------
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

if not defined ANDROID_HOME (
    echo [ERROR] Android SDK was not found.
    echo Open Android Studio once and install the Android SDK.
    pause
    exit /b 1
)

echo [OK] Android SDK: %ANDROID_HOME%

rem ----------------------------------------------------------
rem Bootstrap Gradle locally - nothing is installed system-wide
rem ----------------------------------------------------------
set "GRADLE_VERSION=8.9"
set "TOOLS_DIR=%~dp0.tools"
set "GRADLE_DIR=%TOOLS_DIR%\gradle-%GRADLE_VERSION%"
set "GRADLE_ZIP=%TOOLS_DIR%\gradle-%GRADLE_VERSION%-bin.zip"
set "GRADLE_EXE=%GRADLE_DIR%\bin\gradle.bat"

if not exist "%TOOLS_DIR%" mkdir "%TOOLS_DIR%"

if not exist "%GRADLE_EXE%" (
    echo.
    echo [SETUP] Gradle %GRADLE_VERSION% is not cached yet.
    echo [SETUP] Downloading it once for local builds...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%GRADLE_ZIP%'"
    if errorlevel 1 goto :download_error

    echo [SETUP] Extracting Gradle...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%GRADLE_ZIP%' -DestinationPath '%TOOLS_DIR%' -Force"
    if errorlevel 1 goto :download_error
    del /q "%GRADLE_ZIP%" >nul 2>&1
)

if not exist "%GRADLE_EXE%" (
    echo [ERROR] Gradle setup failed.
    pause
    exit /b 1
)

echo [OK] Gradle: %GRADLE_VERSION%

echo.
echo ==========================================================
echo   BUILDING HEIMDALL DEBUG APK
echo ==========================================================
echo.

call "%GRADLE_EXE%" --no-daemon clean assembleDebug
if errorlevel 1 goto :build_error

set "APK_SOURCE=%~dp0app\build\outputs\apk\debug\app-debug.apk"
set "APK_DEST=%~dp0HEIMDALL.apk"

if not exist "%APK_SOURCE%" (
    echo [ERROR] Gradle completed but the APK was not found.
    pause
    exit /b 1
)

copy /y "%APK_SOURCE%" "%APK_DEST%" >nul

echo.
echo ==========================================================
echo   SUCCESS

echo   APK: %APK_DEST%
echo ==========================================================
echo.

rem Optional: install immediately when an Android device is connected.
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if exist "%ADB%" (
    for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices 2^>nul') do (
        if "%%B"=="device" (
            echo Android device detected: %%A
            choice /c YN /n /m "Install HEIMDALL now? [Y/N]: "
            if errorlevel 2 goto :done
            if errorlevel 1 "%ADB%" install -r "%APK_DEST%"
            goto :done
        )
    )
)

:done
pause
exit /b 0

:download_error
echo.
echo [ERROR] Could not download/extract Gradle.
echo Check your internet connection and run buildapp.cmd again.
pause
exit /b 1

:build_error
echo.
echo [ERROR] Android build failed. The Gradle error is shown above.
pause
exit /b 1
