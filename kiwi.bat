@echo off
:: KIWI launcher script for Windows
set "DIR=%~dp0"
if "%DIR:~-1%"=="\" set "DIR=%DIR:~0,-1%"
java -cp "%DIR%" src.KIWI %*


