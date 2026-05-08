@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"

if not exist "%SCRIPT_DIR%..\miniterm-ffm\target\miniterm-*.jar" (
    echo Warning: No jar files found. Please run 'mvn package' first.
    exit /b 1
)

set count=0
for /f "delims=" %%f in ('dir /b /o:n "%SCRIPT_DIR%*.java" 2^>nul') do (
    set /a count+=1
    set "file[!count!]=%%~nf"
)

if %count% equ 0 (
    echo No .java files found.
    exit /b 1
)

echo Available examples:
for /l %%i in (1,1,%count%) do (
    echo   %%i^) !file[%%i]!
)

echo.
set /p choice=Enter number: 

echo %choice%| findstr /r "^[0-9][0-9]*$" >nul 2>&1
if errorlevel 1 (
    echo Invalid selection.
    exit /b 1
)

if %choice% lss 1 (
    echo Invalid selection.
    exit /b 1
)
if %choice% gtr %count% (
    echo Invalid selection.
    exit /b 1
)

set "selected=!file[%choice%]!"
for /f "delims=" %%j in ('dir /b /o:n "%SCRIPT_DIR%..\miniterm-ffm\target\miniterm-*.jar" 2^>nul') do set "JARFILE=%%~j"
"%SCRIPT_DIR%..\jbang.cmd" --java 22+ -R--enable-native-access=ALL-UNNAMED --cp "%SCRIPT_DIR%..\miniterm-ffm\target\!JARFILE!" "%SCRIPT_DIR%%selected%.java"
