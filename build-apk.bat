@echo off
REM ---------------------------------------------------------------------------
REM Builds the Voice Recorder APK and drops it in this folder as
REM     VoiceRecorder-<versionName>.apk
REM
REM Debug-signed on purpose: that's the signature the phone already has, so
REM update-phone.bat can install straight over the top, and it's what earlier
REM GitHub releases used. *.apk is gitignored, so the file never gets committed.
REM
REM Usage:  build-apk.bat            build (incremental)
REM         build-apk.bat clean      wipe build outputs first
REM ---------------------------------------------------------------------------

setlocal EnableExtensions
call "%~dp0_version.bat" || exit /b 1

set "TASKS=assembleDebug"
if /i "%~1"=="clean" set "TASKS=clean assembleDebug"

echo.
echo === Voice Recorder %VR_VERSION%  [versionCode %VR_VERSION_CODE%] ===
echo Running: gradlew.bat %TASKS%
echo.

REM "%~dp0." -- the trailing backslash of %~dp0 would escape the closing quote.
pushd "%~dp0."
call "%~dp0gradlew.bat" %TASKS%
set "RC=%ERRORLEVEL%"
popd

if not "%RC%"=="0" (
  echo.
  echo BUILD FAILED - gradle exit code %RC%
  exit /b %RC%
)

set "OUT=%~dp0app\build\outputs\apk\debug\app-debug.apk"
if not exist "%OUT%" (
  echo.
  echo ERROR: build succeeded but the APK is missing:
  echo   "%OUT%"
  exit /b 1
)

copy /y "%OUT%" "%VR_APK%" >nul
if errorlevel 1 (
  echo.
  echo ERROR: could not copy the APK to "%VR_APK%".
  exit /b 1
)

for %%F in ("%VR_APK%") do set "SIZE=%%~zF"
echo.
echo Built: "%VR_APK%"
echo Size:  %SIZE% bytes
endlocal
exit /b 0
