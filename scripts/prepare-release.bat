@echo off
setlocal

if "%PYTHON%"=="" set "PYTHON=python"
"%PYTHON%" "%~dp0build\main.py" prepare-release %*
exit /b %ERRORLEVEL%
