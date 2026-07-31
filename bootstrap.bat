@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"

rem ── 1. Download OpenJDK 21 LTS for Windows ────────────────────────────
set "JDK_DIR=%SCRIPT_DIR%deps\jdk"
if not exist "%JDK_DIR%" mkdir "%JDK_DIR%"

set "FORCE_DOWNLOAD=false"
for %%a in (%*) do (
  if /i "%%a"=="--force" set "FORCE_DOWNLOAD=true"
)

if "%FORCE_DOWNLOAD%"=="true" (
  echo Forcing re-download of all dependencies...
  if exist "%JDK_DIR%" rmdir /S /Q "%JDK_DIR%" 2>nul
)

rem Check if JDK already present
set "JAVA_EXE="
if exist "%JDK_DIR%\bin\java.exe" set "JAVA_EXE=%JDK_DIR%\bin\java.exe"

if not defined JAVA_EXE (
  echo Downloading OpenJDK 21 for Windows x64...
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://api.adoptium.net/v3/binary/latest/21/ga/win/x64/jdk/hotspot/normal/eclipse' -OutFile '%SCRIPT_DIR%openjdk21-win.zip'"
  powershell -NoProfile -Command "Expand-Archive -Path '%SCRIPT_DIR%openjdk21-win.zip' -DestinationPath '%JDK_DIR%'"
  del "%SCRIPT_DIR%openjdk21-win.zip"
  rem Flatten versioned folder (jdk-*) so java.exe is at %JDK_DIR%\bin\java.exe
  for /d %%d in ("%JDK_DIR%\jdk-*") do (
    xcopy /E /Y "%%d\*" "%JDK_DIR%\" >nul
    rmdir /S /Q "%%d"
  )
  if exist "%JDK_DIR%\bin\java.exe" set "JAVA_EXE=%JDK_DIR%\bin\java.exe"
  echo OpenJDK 21 downloaded to %JDK_DIR%
) else (
  echo OpenJDK already present
)

rem ── 2. Download Gradle 8.0.1 ───────────────────────────────────────────
set "GRADLE_DIR=%SCRIPT_DIR%deps\gradle"
set "GRADLE_VERSION=8.0.1"
if "%FORCE_DOWNLOAD%"=="true" (
  if exist "%GRADLE_DIR%" rmdir /S /Q "%GRADLE_DIR%" 2>nul
)
if not exist "%GRADLE_DIR%\bin\gradle.bat" (
  echo Downloading Gradle %GRADLE_VERSION% for Windows...
  if not exist "%GRADLE_DIR%" mkdir "%GRADLE_DIR%"
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%SCRIPT_DIR%gradle-bin.zip'"
  powershell -NoProfile -Command "Expand-Archive -Path '%SCRIPT_DIR%gradle-bin.zip' -DestinationPath '%SCRIPT_DIR%deps'"
  del "%SCRIPT_DIR%gradle-bin.zip"
  rem Flatten versioned folder
  for /d %%d in ("%SCRIPT_DIR%deps\gradle-%GRADLE_VERSION%") do (
    if exist "%%d" (
      xcopy /E /Y "%%d\*" "%GRADLE_DIR%\" >nul
      rmdir /S /Q "%%d"
    )
  )
  if exist "%GRADLE_DIR%\bin\gradle.bat" echo Gradle %GRADLE_VERSION% downloaded to %GRADLE_DIR%
) else (
  echo Gradle already present
)

rem ── 3. jbig2enc — not available as precompiled Windows binary ──────────
rem JBIG2 compression will be unavailable; CCITT G4 fallback is used.
set "JBIG2ENC_DIR=%SCRIPT_DIR%deps\jbig2enc\win"
if not exist "%JBIG2ENC_DIR%" mkdir "%JBIG2ENC_DIR%"
if not exist "%JBIG2ENC_DIR%\jbig2enc.exe" (
  echo NOTE: jbig2enc is not available for Windows.
  echo       JBIG2 compression disabled; using CCITT G4 fallback.
)

rem ── 4. Download bundled CJK font for invisible text layer ──────────────
rem Noto Sans SC (SIL OFL 1.1) — covers Latin + CJK with TrueType outlines
set "FONTS_DIR=%SCRIPT_DIR%deps\fonts"
if not exist "%FONTS_DIR%" mkdir "%FONTS_DIR%"
if not exist "%FONTS_DIR%\NotoSansSC-Regular.ttf" (
  echo Downloading Noto Sans SC CJK font ^(~34 MB^)...
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/google/fonts/raw/main/ofl/notosanssc/NotoSansSC%%5Bwght%%5D.ttf' -OutFile '%FONTS_DIR%\NotoSansSC-Regular.ttf'"
  echo Noto Sans SC downloaded to %FONTS_DIR%
) else (
  echo Noto Sans SC already present in %FONTS_DIR%
)

rem ── 5. Build project ───────────────────────────────────────────────────
echo.
echo Bootstrap complete.
echo Run:  run.bat input.pdf -o output.pdf
echo Flags: --force  force re-download of all dependencies
