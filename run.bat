@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"

rem Find JDK installed by bootstrap.bat in deps\jdk\
set "JAVA=%SCRIPT_DIR%deps\jdk\bin\java.exe"
if not exist "%JAVA%" (
  echo No JDK found. Run bootstrap.bat first.
  exit /b 1
)

set "FAT_JAR=%SCRIPT_DIR%build\mrcpdf.jar"
if not exist "%FAT_JAR%" (
  set "JAVA_HOME=%SCRIPT_DIR%deps\jdk"
  "%SCRIPT_DIR%gradlew.bat" build
)

rem Run from the repo root so bundled deps (jbig2enc, fonts, settings.jsonc)
rem resolve correctly regardless of the calling directory.
cd /d "%SCRIPT_DIR%"

"%JAVA%" -jar "%FAT_JAR%" %*
