@echo off
REM ---------------------------------------------------------------------------
REM Builds the current source and installs it on the phone over USB, keeping
REM the existing app data and settings.
REM
REM Needs: the phone plugged in with USB debugging on and this PC authorised
REM ("Allow USB debugging?" prompt), plus adb -- taken from PATH, or from the
REM SDK in local.properties, or from the usual install locations.
REM
REM Usage:  update-phone.bat            build, then install
REM         update-phone.bat clean      clean build, then install
REM ---------------------------------------------------------------------------

setlocal EnableExtensions
call "%~dp0_version.bat" || exit /b 1

set "PKG=com.lithic.voicerecorder"

REM --- find adb -------------------------------------------------------------
set "ADB_DIR="
for %%X in (adb.exe) do if not defined ADB_DIR if not "%%~$PATH:X"=="" set "ADB_DIR=%%~dp$PATH:X"

if not defined ADB_DIR (
  if exist "%~dp0local.properties" (
    for /f "tokens=2 delims==" %%A in ('findstr /b /c:"sdk.dir" "%~dp0local.properties"') do set "SDK=%%A"
  )
)
REM One statement per line: inside a ( ) block %SDK% would expand before the
REM slash substitution above it had run.
if not defined ADB_DIR if defined SDK set "SDK=%SDK:/=\%"
if not defined ADB_DIR if defined SDK call :try_adb "%SDK%\platform-tools"
if not defined ADB_DIR call :try_adb "%LOCALAPPDATA%\Android\Sdk\platform-tools"
if not defined ADB_DIR call :try_adb "%ProgramFiles(x86)%\Android\android-sdk\platform-tools"
if not defined ADB_DIR call :try_adb "%ProgramFiles%\Android\android-sdk\platform-tools"

if not defined ADB_DIR (
  echo ERROR: adb.exe not found.
  echo Install the Android SDK platform-tools, or put adb on your PATH.
  exit /b 1
)
set "PATH=%ADB_DIR%;%PATH%"

REM --- check exactly one usable device --------------------------------------
echo Looking for a connected device...
set /a READY=0
set /a UNAUTH=0
set /a OFFLINE=0
for /f "skip=1 tokens=1,2" %%A in ('adb devices') do (
  if "%%B"=="device" set /a READY+=1
  if "%%B"=="unauthorized" set /a UNAUTH+=1
  if "%%B"=="offline" set /a OFFLINE+=1
)

if %UNAUTH% GTR 0 (
  echo.
  echo ERROR: the device is connected but not authorised for debugging.
  echo Unlock the phone and accept the "Allow USB debugging?" prompt, then retry.
  exit /b 1
)
if %OFFLINE% GTR 0 (
  echo.
  echo ERROR: the device is offline. Unplug and replug the cable, then retry.
  exit /b 1
)
if %READY% EQU 0 (
  echo.
  echo ERROR: no device found. Plug the phone in, unlock it, and make sure
  echo USB debugging is enabled in Developer options.
  exit /b 1
)
if %READY% GTR 1 (
  echo.
  echo ERROR: %READY% devices are connected - unplug the extras so it's clear
  echo which one to install on. Connected devices:
  adb devices
  exit /b 1
)

echo Currently installed:
adb shell dumpsys package %PKG% 2>nul | findstr /c:"versionName=" || echo   ^(not installed yet^)

REM --- build ----------------------------------------------------------------
call "%~dp0build-apk.bat" %1
if errorlevel 1 exit /b 1

REM --- install --------------------------------------------------------------
set "LOG=%TEMP%\vr_install_%RANDOM%.txt"
echo.
echo Installing %VR_VERSION% on the phone...
adb install -r "%VR_APK%" > "%LOG%" 2>&1
set "RC=%ERRORLEVEL%"
type "%LOG%"

findstr /c:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" "%LOG%" >nul && (
  echo.
  echo The installed copy was signed with a different key. Uninstall it first:
  echo     adb uninstall %PKG%
  echo Note that this also wipes the app's settings.
  set "RC=1"
)
findstr /c:"INSTALL_FAILED_VERSION_DOWNGRADE" "%LOG%" >nul && (
  echo.
  echo The phone already has a newer versionCode than %VR_VERSION_CODE%.
  echo Bump versionCode in app\build.gradle.kts and rebuild.
  set "RC=1"
)
del "%LOG%" >nul 2>&1

if not "%RC%"=="0" (
  echo.
  echo Install FAILED - exit code %RC%
  exit /b %RC%
)

echo.
echo Voice Recorder %VR_VERSION% is now on the phone.
endlocal
exit /b 0

REM --- helper: set ADB_DIR if adb.exe lives in %~1 ---------------------------
:try_adb
if defined ADB_DIR exit /b 0
if exist "%~1\adb.exe" set "ADB_DIR=%~1"
exit /b 0
