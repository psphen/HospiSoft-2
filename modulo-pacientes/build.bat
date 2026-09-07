@echo off
setlocal
chcp 65001 >nul

REM ============================================================
REM  build.bat - Compila y despliega modulo-pacientes (AA2-EV01 + AA2-EV02)
REM  Compila: consola JDBC (EV01) + servlets web (EV02) y despliega en Tomcat.
REM ============================================================

set "JAVAC=C:\Users\steph\AppData\Local\Temp\opencode\jdk21\extracted\jdk-21.0.12.1+1\bin\javac.exe"
set "MYSQL_JAR=web\WEB-INF\lib\mysql-connector-j-8.0.33.jar"
set "SERVLET_JAR=C:\xampp\tomcat\lib\servlet-api.jar"
set "JSP_JAR=C:\xampp\tomcat\lib\jsp-api.jar"
set "BUILD_DIR=build\classes"
set "WEB_CLASSES=web\WEB-INF\classes"
set "TOMCAT_DEPLOY=C:\xampp\tomcat\webapps\modulo-pacientes-web"

echo [1/4] Creando carpeta de compilacion: %BUILD_DIR%
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
if errorlevel 1 (
    echo ERROR: No se pudo crear la carpeta %BUILD_DIR%.
    exit /b 1
)

echo [2/4] Compilando clases Java (consola EV01 + servlet EV02)...
"%JAVAC%" -encoding UTF-8 -cp "%MYSQL_JAR%;%SERVLET_JAR%;%JSP_JAR%" -d "%BUILD_DIR%" ^
    src\com\hospisoft\model\Paciente.java ^
    src\com\hospisoft\util\ConexionDB.java ^
    src\com\hospisoft\dao\PacienteDAO.java ^
    src\com\hospisoft\controller\PacienteController.java ^
    src\com\hospisoft\servlet\PacienteServlet.java ^
    src\com\hospisoft\Main.java
if errorlevel 1 (
    echo ERROR: La compilacion fallo. Revise los errores anteriores.
    exit /b 1
)

echo [3/4] Copiando clases compiladas a %WEB_CLASSES%
if not exist "%WEB_CLASSES%" mkdir "%WEB_CLASSES%"
xcopy /E /I /Y "%BUILD_DIR%\com" "%WEB_CLASSES%\com" >nul
if errorlevel 1 (
    echo ERROR: No se pudieron copiar las clases compiladas.
    exit /b 1
)

echo [4/4] Desplegando en Tomcat: %TOMCAT_DEPLOY%
if exist "%TOMCAT_DEPLOY%" rmdir /S /Q "%TOMCAT_DEPLOY%"
xcopy /E /I /Y "web\*" "%TOMCAT_DEPLOY%\" >nul
if errorlevel 1 (
    echo ERROR: No se pudo desplegar la aplicacion en Tomcat.
    exit /b 1
)

echo.
echo DESPLIEGUE EXITOSO.
echo Consola EV01: java -cp "build\classes;%MYSQL_JAR%" com.hospisoft.Main
echo Web EV02:    http://localhost:8081/modulo-pacientes-web/pacientes
exit /b 0