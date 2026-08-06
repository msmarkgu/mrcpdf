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
  echo.
  echo Downloading OpenJDK 21 for Windows x64...
  call :installJdk "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse" "Adoptium"
  if not defined JAVA_EXE (
    echo WARNING: Adoptium download failed; falling back to jdk.java.net/archive...
    call :installJdk "https://download.java.net/java/GA/jdk21.0.2/f2283984656d49d69e91c558476027ac/13/GPL/openjdk-21.0.2_windows-x64_bin.zip" "jdk.java.net/archive"
  )
  if not defined JAVA_EXE (
    echo ERROR: Could not download OpenJDK 21 from either source.
    exit /b 1
  )
  echo OpenJDK 21 installed to %JDK_DIR%
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
  echo.
  echo Downloading Gradle %GRADLE_VERSION% for Windows...
  echo   URL: https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip
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

rem ── 3. jbig2enc (Windows x64) ──────────────────────────────────────────
rem Static MSVC v0.32 from upstream agl/jbig2enc releases (Apache 2.0).
rem No DLLs needed; leptonica is linked in. If download/verify fails, fall
rem back to CCITT G4 (same "never abort" guarantee as bootstrap.sh).
set "JBIG2ENC_DIR=%SCRIPT_DIR%deps\jbig2enc\win"
set "JBIG2_EXE=%JBIG2ENC_DIR%\jbig2enc.exe"
set "WIN_ZIP_URL=https://github.com/agl/jbig2enc/releases/download/0.32/jbig2enc-0.32-Windows-X64-MSVC.zip"

if not exist "%JBIG2ENC_DIR%" mkdir "%JBIG2ENC_DIR%"
if "%FORCE_DOWNLOAD%"=="true" (
  if exist "%JBIG2_EXE%" del /Q "%JBIG2_EXE%"
)

if not exist "%JBIG2_EXE%" (
  echo.
  echo Downloading jbig2enc v0.32 (MSVC x64) for Windows...
  echo   URL: %WIN_ZIP_URL%
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri '%WIN_ZIP_URL%' -OutFile '%SCRIPT_DIR%jbig2enc-win.zip'" 2>nul
  if exist "%SCRIPT_DIR%jbig2enc-win.zip" (
    powershell -NoProfile -Command "Expand-Archive -Path '%SCRIPT_DIR%jbig2enc-win.zip' -DestinationPath '%SCRIPT_DIR%deps\jbig2enc\win-tmp' -Force" 2>nul
    if exist "%SCRIPT_DIR%deps\jbig2enc\win-tmp\bin\jbig2.exe" (
      copy /Y "%SCRIPT_DIR%deps\jbig2enc\win-tmp\bin\jbig2.exe" "%JBIG2_EXE%" >nul
    )
    rmdir /S /Q "%SCRIPT_DIR%deps\jbig2enc\win-tmp" 2>nul
    del /Q "%SCRIPT_DIR%jbig2enc-win.zip" 2>nul
  )
  if exist "%JBIG2_EXE%" (
    echo jbig2enc installed to %JBIG2ENC_DIR%
  ) else (
    echo WARNING: Failed to download jbig2enc; using CCITT G4 fallback.
  )
)

rem Runtime verification: if the exe can't run, treat as uninstalled (CCITT G4)
if exist "%JBIG2_EXE%" (
  "%JBIG2_EXE%" -h >nul 2>&1
  if errorlevel 1 (
    echo WARNING: jbig2enc failed to run; removing it and using CCITT G4 fallback.
    del /Q "%JBIG2_EXE%"
  )
)

rem ── 4. Download bundled CJK font for invisible text layer ──────────────
rem Noto Sans SC (SIL OFL 1.1) — covers Latin + CJK with TrueType outlines
set "FONTS_DIR=%SCRIPT_DIR%deps\fonts"
if not exist "%FONTS_DIR%" mkdir "%FONTS_DIR%"
if not exist "%FONTS_DIR%\NotoSansSC-Regular.ttf" (
  echo.
  echo Downloading Noto Sans SC CJK font ^(~34 MB^)...
  echo   URL: https://github.com/google/fonts/raw/main/ofl/notosanssc/NotoSansSC%%5Bwght%%5D.ttf
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
goto :eof

rem ── JDK install subroutine ────────────────────────────────────────────
rem Downloads an OpenJDK 21 archive, flattens the versioned folder so
rem java.exe lands at %JDK_DIR%\bin\java.exe. Sets JAVA_EXE on success;
rem leaves it unset (after cleaning up) on failure so the caller can retry.
:installJdk
set "JDK_URL=%~1"
set "JDK_SRC=%~2"
set "JDK_OK=false"
echo   URL: %JDK_URL%
echo   Downloading from %JDK_SRC%...
if exist "%JDK_DIR%" rmdir /S /Q "%JDK_DIR%" 2>nul
mkdir "%JDK_DIR%" 2>nul
powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri '%JDK_URL%' -OutFile '%SCRIPT_DIR%openjdk21-win.zip'" 2>nul
if exist "%SCRIPT_DIR%openjdk21-win.zip" (
  powershell -NoProfile -Command "Expand-Archive -Path '%SCRIPT_DIR%openjdk21-win.zip' -DestinationPath '%JDK_DIR%'" 2>nul
  del /Q "%SCRIPT_DIR%openjdk21-win.zip" 2>nul
  rem Flatten versioned folder (jdk-*) so java.exe is at %JDK_DIR%\bin\java.exe
  for /d %%d in ("%JDK_DIR%\jdk-*") do (
    xcopy /E /Y "%%d\*" "%JDK_DIR%\" >nul 2>nul
    rmdir /S /Q "%%d"
  )
  if exist "%JDK_DIR%\bin\java.exe" (
    set "JAVA_EXE=%JDK_DIR%\bin\java.exe"
    set "JDK_OK=true"
  )
)
if not "%JDK_OK%"=="true" (
  echo   Failed to install JDK from %JDK_SRC%.
  if exist "%JDK_DIR%" rmdir /S /Q "%JDK_DIR%" 2>nul
  mkdir "%JDK_DIR%" 2>nul
)
exit /b 0
