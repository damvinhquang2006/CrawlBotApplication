@echo off
chcp 65001 >nul
echo =======================================================
echo          KHOI DONG JAVA + PYTHON CRAWLER BOT           
echo =======================================================
echo.

if not exist "target\crawler-bot-1.0.0.jar" (
    echo [INFO] Dang bien dich du an bang Maven...
    call mvn clean package -DskipTests
    if errorlevel 1 (
        echo [LOI] Khong the bien dich du an. Vui long kiem tra lai Maven.
        pause
        exit /b 1
    )
)

echo [INFO] Dang chay Bot...
java -Dfile.encoding=UTF-8 -jar target\crawler-bot-1.0.0.jar
pause
