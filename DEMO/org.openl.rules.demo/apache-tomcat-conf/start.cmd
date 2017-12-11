@rem set JRE_HOME=C:\Program Files\Java\jre1.8.0_92
@rem set _JAVA_MEMORY=-Xms512m -Xmx2g

@setlocal
@if exist "setenv.cmd" (
@echo ### Loading environment from setenv.cmd ...
@echo.
@call setenv.cmd
@echo.
)
@set errorcode=0
@set delay=20
@echo ### Checking Java environment ...
@echo.
@call :start & if not errorlevel 1 goto :end
@set delay=120

@rem Try to detect installed Java
@where /Q java.exe
@if errorlevel 1 (
  @echo       Probably, you have not installed Java...
) else (
  @for /f "tokens=*" %%i in ('@where java.exe') do @call :startJava "%%i" & echo. & if not errorlevel 1 goto :end
)

@rem Try to all known locations of java
@for /f "tokens=*" %%i in ('dir "%ProgramFiles%\Java\" /O-D /AD /B') do @call :startJava "%ProgramFiles%\Java\%%~i\bin\java.exe" & echo. & if not errorlevel 1 goto :end
@for /f "tokens=*" %%i in ('dir "%ProgramFiles(x86)%\Java\" /O-D /AD /B') do @call :startJava "%ProgramFiles(x86)%\Java\%%~i\bin\java.exe" & echo. & if not errorlevel 1 goto :end

@set errorcode=1
@echo       Check JRE_HOME and JAVA_HOME environment variables.
@echo       JRE_HOME should point to the directory where Java was installed.
@echo.

@if "%JRE_HOME%" == "" @if "%JAVA_HOME%" == "" (
  @echo       Neither the JAVA_HOME nor the JRE_HOME environment variable is defined
  @echo       At least one of these environment variable is needed to run this program
  @echo.
  @echo       !!!    JRE_HOME variable is not set 
  @echo.
  @echo       You can define this variable in this file "start.cmd"
  @echo       The first line should be like this:
  @echo.
  @echo           set JRE_HOME=C:\Program Files\Java\jre1.8.0_92
  @echo.
  @echo       where  "C:\Program Files\Java\jre1.8.0_92"
  @echo       is the directory where Java was installed.
  @goto :end
)

@echo       Current values of Java environment variables:
@if "%JAVA_HOME%" neq "" @echo           set JAVA_HOME=%JAVA_HOME%
@if "%JRE_HOME%" neq ""  @echo           set JRE_HOME=%JRE_HOME%

goto :end

rem SUBROUTINES

:startJava
@setlocal
@if not exist "%~1" exit /b 2 & endlocal
@set _ARG=%~1
@echo ### Found executable java.exe is located at:
@echo        %_ARG%

@rem Try to resolve JRE_HOME from the found executable java.
@call :startJRE "%_ARG%" & if not errorlevel 1 exit /b 0 & endlocal

@rem Try to resolve JRE_HOME from the symlink on the found executable java.
@for /f "tokens=2 delims=[]" %%i in ('@dir "%_ARG%" ^| findstr /l \bin\java.exe') do @set _VAR=%%i
@if "%_VAR%" == "" exit /b 4 & endlocal
@echo ### Resolving symlink...
@call :startJRE "%_VAR%" & if not errorlevel 1 exit /b 0 & endlocal
@exit /b 3 & endlocal

:startJRE
@setlocal
@set _ARG=%~1
@echo ### Composing JRE_HOME from %_ARG% ...
@if "%_ARG:~-13%" neq "\bin\java.exe" @echo ###    ... it does not match & exit /b 2 & endlocal
@set JRE_HOME=%_ARG:~0,-13%
@echo ### Trying to use JRE_HOME=%JRE_HOME% ...

:start
@rem check JAVA installation and save valid JRE_HOME
@setlocal & pushd %~dp0 & call bin\setclasspath.bat > NUL & popd & endlocal & if errorlevel 1 exit /b 1 & endlocal
@setlocal & pushd %~dp0 & call bin\setclasspath.bat & popd
@endlocal & set _JRE_HOME=%JRE_HOME%

@rem Determine Java version
@pushd "%_JRE_HOME%"
@FOR /f "tokens=3" %%G IN ('bin\java.exe -version 2^>^&1 ^| find "java version"') DO set _JAVA_VERSION=%%~G
@popd
@if "%_JAVA_VERSION%" == "" set _JAVA_VERSION=UNKNOWN

@if not defined _JAVA_OPTS (
@rem set parameters for Java 9
@if "%_JAVA_VERSION%" == "9" set _JAVA_OPTS=--add-modules java.se.ee
@if "%_JAVA_VERSION:~0,2%" == "9." set _JAVA_OPTS=--add-modules java.se.ee

@rem set parameters for Java 8
@if "%_JAVA_VERSION:~0,3%" == "1.8" set _JAVA_OPTS=-XX:+UseParNewGC -XX:+UseConcMarkSweepGC

@rem set parameters for Java 7
@if "%_JAVA_VERSION:~0,3%" == "1.7" set _JAVA_OPTS=-XX:+UseParNewGC -XX:+UseConcMarkSweepGC -XX:PermSize=128m -XX:MaxPermSize=512m
)

@rem Determine memory size
@for /f %%G in ('wmic ComputerSystem get TotalPhysicalMemory ^| findstr [0123456789]') do set _MEMORY=%%G
@if "%_MEMORY%" == "" set _MEMORY=0
set _MEMORY=%_MEMORY:~0,-9%

%if not defined _JAVA_MEMORY (
@rem default memory settings
@set _JAVA_MEMORY=-Xms512m -Xmx2g

@rem 4GiB
@if %_MEMORY% GEQ 4 set _JAVA_MEMORY=-Xms2g -Xmx3g

@rem 6GiB
@if %_MEMORY% GEQ 6 set _JAVA_MEMORY=-Xms3g -Xmx5g

@rem 8GiB
@if %_MEMORY% GEQ 8 set _JAVA_MEMORY=-Xms4g -Xmx7g

@rem 12GiB
@if %_MEMORY% GEQ 12 set _JAVA_MEMORY=-Xms8g -Xmx10g
)

@rem Run Apache Tomcat
@setlocal
@echo.
@echo ### Starting OpenL Tablets DEMO ...
@echo.
@set JAVA_OPTS=%JAVA_OPTS% %_JAVA_MEMORY% %_JAVA_OPTS%
@set CATALINA_OPTS=-DDEMO=DEMO -Dwebstudio.home=openl-demo -Dwebstudio.configured=true -Dws.port=8080 %CATALINA_OPTS%
@echo Memory size:           "%_MEMORY%GBytes"
@echo Java version:          "%_JAVA_VERSION%"
@echo Using JRE_HOME:        "%_JRE_HOME%"
@echo Using JAVA_OPTS:       "%JAVA_OPTS%"
@echo Using CATALINA_OPTS:   "%CATALINA_OPTS%"
@pushd %~dp0
@call bin\startup.bat
@popd
@exit /b 0 & endlocal

:end
@echo.
@echo.
@echo       To get more information about OpenL Tablets DEMO,
@echo       please refer to "Demo Package Guide" on our site:
@echo.
@echo       http://openl-tablets.org/documentation/user-guides
@echo.
@echo %delay% seconds delay before closing this window.
@choice /C C /T %delay% /D C /N /M "Press [C] key to Close this windows immediatly." & endlocal & exit /b %errorcode%
