@echo off
REM Compiles the Windows noop stub for nucleus_system_info.
REM This is a placeholder until Windows implementation is added.

setlocal

set SCRIPT_DIR=%~dp0
set RESOURCE_DIR=%SCRIPT_DIR%..\..\resources\nucleus\native
set OUT_DIR_X64=%RESOURCE_DIR%\win32-x64
set OUT_DIR_ARM64=%RESOURCE_DIR%\win32-aarch64

if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME not set. >&2
    exit /b 1
)

set JNI_INCLUDE=%JAVA_HOME%\include
set JNI_INCLUDE_WIN=%JAVA_HOME%\include\win32

if not exist "%OUT_DIR_X64%" mkdir "%OUT_DIR_X64%"
if not exist "%OUT_DIR_ARM64%" mkdir "%OUT_DIR_ARM64%"

cl /nologo /LD /O2 /I"%JNI_INCLUDE%" /I"%JNI_INCLUDE_WIN%" ^
    "%SCRIPT_DIR%nucleus_system_info.c" ^
    /Fe"%OUT_DIR_X64%\nucleus_system_info.dll" /link /DEF:

echo Built Windows stub (x64)

REM ARM64 cross-compilation would require the ARM64 cl.exe toolchain
REM For now, copy x64 as placeholder if ARM64 toolchain is not available
if not exist "%OUT_DIR_ARM64%\nucleus_system_info.dll" (
    copy "%OUT_DIR_X64%\nucleus_system_info.dll" "%OUT_DIR_ARM64%\nucleus_system_info.dll" >nul 2>&1
)

endlocal
