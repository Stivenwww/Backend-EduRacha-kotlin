@echo off
REM ============================================================
REM  Verifica el build de produccion y sube los cambios a GitHub
REM  (rama dev2) para que Sevalla los despliegue.
REM  No sube .env ni serviceAccountKey.json.
REM ============================================================
title EduRacha - subir a Sevalla
cd /d "%~dp0"
if not defined JAVA_HOME if exist "C:\Program Files\Eclipse Adoptium\jdk-17.0.16.8-hotspot\bin\java.exe" set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.16.8-hotspot"

echo [1/3] Compilando como en Sevalla (installDist)...
call gradlew.bat installDist -x test --console=plain
if errorlevel 1 (
  echo.
  echo ERROR: el build fallo. No se subio nada.
  pause
  exit /b 1
)
if not exist "build\install\ktor-sample\bin\ktor-sample" (
  echo ERROR: no se genero build\install\ktor-sample. No se subio nada.
  pause
  exit /b 1
)

echo [2/3] Creando commit (solo archivos del despliegue y la correccion del quiz)...
git checkout dev2
git add Dockerfile .dockerignore subir_a_sevalla.bat src/main/resources/application.yaml src/main/kotlin/com/eduracha/services/ServicioProgramacionCurso.kt src/main/kotlin/com/eduracha/services/QuizService.kt src/test/kotlin/com/eduracha/services/DisponibilidadQuizTest.kt
git commit -m "Despliegue en Sevalla y quiz sin bloqueo por fechas" -m "Dockerfile con installDist, .dockerignore sin secretos, puerto desde PORT." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_017dKagech8A68CSX7kSnFX5"

echo [3/3] Subiendo a GitHub (origin dev2)...
git push origin dev2
if errorlevel 1 (
  echo ERROR: el push fallo. Revisa tu sesion de GitHub.
  pause
  exit /b 1
)
echo.
echo LISTO: cambios en GitHub (rama dev2). Ya puedes desplegar en Sevalla.
git log --oneline -1
pause
