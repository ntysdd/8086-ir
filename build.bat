@echo off
rem ===========================================================================
rem 8086-ir build entry point. Plain batch only: no PowerShell, no downloaded
rem tools, no network access. Build output goes to build\ and is never
rem committed.
rem
rem   build.bat                compile everything and run the test suite
rem   build.bat clean          delete build\
rem
rem Compiler flags:
rem
rem   --release 8       pins the language level *and* the platform API to Java 8,
rem                     so a newer JDK cannot leak a newer library method into
rem                     Java 8 code, and no bootstrap-classpath warning appears.
rem
rem   -Xlint:all        every lint on, minus the one below.
rem
rem   -Xlint:-options   off deliberately. JDK 9 and later warn that
rem                     source/target 8 is obsolete. That warning is about the
rem                     choice made above rather than about the code, and the
rem                     build has to stay warning-clean, so it is suppressed
rem                     here and nowhere else.
rem ===========================================================================

setlocal
set "ROOT=%~dp0"
set "BUILD=%ROOT%build"
set "CLASSES=%BUILD%\classes"
set "TESTCLASSES=%BUILD%\test-classes"
set "JAVAC_FLAGS=--release 8 -Xlint:all,-options -encoding UTF-8"

if /i "%~1"=="clean" (
    if exist "%BUILD%" rmdir /s /q "%BUILD%"
    echo build: removed build\
    exit /b 0
)

if not exist "%BUILD%" mkdir "%BUILD%"

rem --- collect sources; each path is quoted, a checkout may contain spaces ---
call :collect "%ROOT%src\main\java" "%BUILD%\main-sources.txt"
if errorlevel 1 exit /b 1
call :collect "%ROOT%src\test\java" "%BUILD%\test-sources.txt"
if errorlevel 1 exit /b 1

rem --- compile the product code ---------------------------------------------
if not exist "%CLASSES%" mkdir "%CLASSES%"
javac %JAVAC_FLAGS% -d "%CLASSES%" @"%BUILD%\main-sources.txt"
if errorlevel 1 goto :compile-failed

rem --- `build.bat run ...` compiles the product and runs it, without the tests
if /i "%~1"=="run" goto :run

rem --- compile the tests against it, then run them --------------------------
if not exist "%TESTCLASSES%" mkdir "%TESTCLASSES%"
javac %JAVAC_FLAGS% -cp "%CLASSES%" -d "%TESTCLASSES%" @"%BUILD%\test-sources.txt"
if errorlevel 1 goto :compile-failed

java -cp "%CLASSES%;%TESTCLASSES%" i8086.testing.TestMain
if errorlevel 1 goto :tests-failed

echo build: OK
exit /b 0

rem ---------------------------------------------------------------------------
:run
rem The whole command line is passed through, leading `run` and all, and
rem i8086.cli.Main drops that first word: batch cannot rebuild a shifted
rem argument list without losing the quoting it was given.
java -cp "%CLASSES%" i8086.cli.Main %*
exit /b %errorlevel%

:collect
rem %~1 = source root, %~2 = list file to write
rem
rem Paths are written with forward slashes: javac reads an @argfile with
rem backslash as an escape character, so a Windows path written verbatim loses
rem every separator. They are also quoted, because a checkout may live under a
rem directory whose name contains spaces.
if not exist "%~1" (
    type nul > "%~2"
    exit /b 0
)
(for /f "delims=" %%F in ('dir /s /b "%~1\*.java" 2^>nul') do @call :quote "%%~fF") > "%~2"
exit /b 0

:quote
set "SLASHED=%~1"
echo "%SLASHED:\=/%"
exit /b 0

:compile-failed
echo build: compilation failed
exit /b 1

:tests-failed
echo build: tests failed
exit /b 1
