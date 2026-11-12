@echo off

setlocal EnableDelayedExpansion
set "dirpath=%~dp0"

SET libs=
for %%i in (*.jar) do SET libs=!libs!;%%i
for /r "%dirpath%\libs" %%i in (*.jar) do SET libs=!libs!;%%i
SET libs=%libs:~1%

java -cp "%libs%" %*
exit %ERRORLEVEL%
