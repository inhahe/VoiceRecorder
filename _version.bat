@echo off
REM ---------------------------------------------------------------------------
REM Internal helper -- not meant to be run on its own.
REM
REM Reads the single source of truth for the app version, namely versionName /
REM versionCode in app\build.gradle.kts, and exports into the CALLER's
REM environment:
REM
REM     VR_VERSION       e.g. 0.8.0
REM     VR_VERSION_CODE  e.g. 8
REM     VR_TAG           e.g. v0.8.0
REM     VR_APK           full path of the APK that build-apk.bat produces
REM
REM Usage:   call "%~dp0_version.bat" || exit /b 1
REM ---------------------------------------------------------------------------

set "VR_GRADLE=%~dp0app\build.gradle.kts"
set "VR_VERSION="
set "VR_VERSION_CODE="

if not exist "%VR_GRADLE%" (
  echo ERROR: cannot find "%VR_GRADLE%".
  exit /b 1
)

for /f "tokens=2 delims==" %%A in ('findstr /r /c:"versionName *=" "%VR_GRADLE%"') do set "VR_VERSION=%%A"
for /f "tokens=2 delims==" %%A in ('findstr /r /c:"versionCode *=" "%VR_GRADLE%"') do set "VR_VERSION_CODE=%%A"

REM Strip the spaces and quotes around the parsed values.
set VR_VERSION=%VR_VERSION: =%
set VR_VERSION=%VR_VERSION:"=%
set VR_VERSION_CODE=%VR_VERSION_CODE: =%

if "%VR_VERSION%"=="" (
  echo ERROR: could not parse versionName from "%VR_GRADLE%".
  exit /b 1
)
if "%VR_VERSION_CODE%"=="" (
  echo ERROR: could not parse versionCode from "%VR_GRADLE%".
  exit /b 1
)

set "VR_TAG=v%VR_VERSION%"
set "VR_APK=%~dp0VoiceRecorder-%VR_VERSION%.apk"
exit /b 0
