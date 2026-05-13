@echo off
setlocal enabledelayedexpansion

set "PROPS=-Dminiterm.ffm=-ffm"
if not "%~1"=="" set "PROPS=%PROPS% -Dminiterm.version=%~1"

set "SCRIPT_DIR=%~dp0"

echo Warning: Make sure you have run 'mvn install' first.
echo.

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
"%SCRIPT_DIR%..\jbang.cmd" --java 22+ -R--enable-native-access=ALL-UNNAMED %PROPS% "%SCRIPT_DIR%%selected%.java"
