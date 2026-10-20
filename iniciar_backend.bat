@echo off
REM ============================================================
REM  Inicia el backend de EduRacha en esta PC (puerto 8080).
REM  La app Android (emulador) se conecta a http://10.0.2.2:8080/
REM  Deja esta ventana abierta mientras pruebas la app.
REM ============================================================
title EduRacha Backend (local)
cd /d "%~dp0"
if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio4\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio4\jbr"
if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
echo Usando Java: %JAVA_HOME%
echo Iniciando backend... (la primera vez puede tardar unos minutos)
echo Cuando veas "Responding at http://0.0.0.0:8080" ya esta listo.
echo Diagnostico de IA: http://localhost:8080/api/ia/diagnostico
call gradlew.bat run
echo.
echo El backend se detuvo. Revisa los mensajes de arriba.
pause
