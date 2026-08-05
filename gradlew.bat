@ECHO OFF
SETLOCAL
SET "APP_HOME=%~dp0"
PowerShell -NoProfile -ExecutionPolicy Bypass -File "%APP_HOME%scripts\bootstrap-gradle-wrapper.ps1"
IF ERRORLEVEL 1 EXIT /B %ERRORLEVEL%

IF DEFINED JAVA_HOME (
  SET "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) ELSE (
  SET "JAVA_EXE=java.exe"
)

"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
ENDLOCAL
