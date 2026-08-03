@echo off
REM ---------------------------------------------------------------------------
REM Builds the current source and publishes it as a NEW GitHub release on
REM     https://github.com/inhahe/VoiceRecorder
REM tagged v<versionName> with VoiceRecorder-<versionName>.apk attached.
REM
REM Needs the GitHub CLI (gh) logged in:  gh auth login
REM
REM The version comes from app\build.gradle.kts -- if a release with that tag
REM already exists the script stops and tells you to bump the version, so a
REM release is never silently overwritten.
REM
REM Release notes: the first argument if given, otherwise the commit subjects
REM since the previous release tag in the local repo.
REM
REM Usage:  publish-release.bat
REM         publish-release.bat "Adds filename history and take numbering"
REM ---------------------------------------------------------------------------

setlocal EnableExtensions
call "%~dp0_version.bat" || exit /b 1

set "REPO=inhahe/VoiceRecorder"
set "NOTES=%TEMP%\vr_notes_%RANDOM%.md"

REM --- prerequisites --------------------------------------------------------
where gh >nul 2>&1
if errorlevel 1 (
  echo ERROR: the GitHub CLI is not installed or not on PATH.
  echo Get it from https://cli.github.com/ then run: gh auth login
  exit /b 1
)
gh auth status >nul 2>&1
if errorlevel 1 (
  echo ERROR: the GitHub CLI is not logged in. Run: gh auth login
  exit /b 1
)

REM --- refuse to reuse an existing version ----------------------------------
gh release view %VR_TAG% --repo %REPO% >nul 2>&1
if not errorlevel 1 (
  echo.
  echo ERROR: release %VR_TAG% already exists on %REPO%.
  echo Bump versionName and versionCode in app\build.gradle.kts, then retry.
  exit /b 1
)

REM --- release notes --------------------------------------------------------
if not "%~1"=="" (
  >"%NOTES%" echo %~1
) else (
  set "LASTTAG="
  for /f "delims=" %%A in ('git -C "%~dp0." describe --tags --abbrev^=0 2^>nul') do set "LASTTAG=%%A"
  call :write_notes
)

echo.
echo === Publishing Voice Recorder %VR_VERSION% ===
echo   repo:  %REPO%
echo   tag:   %VR_TAG%
echo   asset: VoiceRecorder-%VR_VERSION%.apk
echo.
echo Release notes:
echo ---------------------------------------------------------------
type "%NOTES%"
echo.
echo ---------------------------------------------------------------
echo.
echo NOTE: the tag is created on whatever is currently the head of the repo's
echo default branch. If you want the released source to match this APK, run
echo copy-to-github.bat and push from D:\github\voicerecorder first.
echo.

set "ANS="
set /p "ANS=Build and publish this release? [y/N] "
if /i not "%ANS%"=="y" (
  echo Cancelled.
  del "%NOTES%" >nul 2>&1
  exit /b 1
)

REM --- build ----------------------------------------------------------------
call "%~dp0build-apk.bat"
if errorlevel 1 (
  del "%NOTES%" >nul 2>&1
  exit /b 1
)

REM --- publish --------------------------------------------------------------
echo.
echo Uploading to GitHub...
gh release create %VR_TAG% "%VR_APK%" --repo %REPO% --title "%VR_TAG%" --notes-file "%NOTES%"
set "RC=%ERRORLEVEL%"
del "%NOTES%" >nul 2>&1

if not "%RC%"=="0" (
  echo.
  echo Publish FAILED - gh exit code %RC%
  exit /b %RC%
)

REM Tag the local repo too, so the next release can work out "what changed".
git -C "%~dp0." rev-parse -q --verify "refs/tags/%VR_TAG%" >nul 2>&1
if errorlevel 1 git -C "%~dp0." tag %VR_TAG% >nul 2>&1

echo.
echo Published: https://github.com/%REPO%/releases/tag/%VR_TAG%
endlocal
exit /b 0

REM --- helper: commit subjects since the previous release tag ---------------
:write_notes
if defined LASTTAG (
  git -C "%~dp0." log %LASTTAG%..HEAD --pretty=format:"- %%s" > "%NOTES%" 2>nul
) else (
  git -C "%~dp0." log -n 15 --pretty=format:"- %%s" > "%NOTES%" 2>nul
)
set "NSIZE=0"
if exist "%NOTES%" for %%F in ("%NOTES%") do set "NSIZE=%%~zF"
if %NSIZE% LSS 3 >"%NOTES%" echo Voice Recorder %VR_VERSION%
exit /b 0
