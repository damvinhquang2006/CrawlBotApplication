@echo off
chcp 65001 >nul
echo =======================================================
echo          KHOI DONG MOCK HTTP SERVER CHO BOT            
echo =======================================================
echo.
echo Dang khoi dong Web Server cuc bo tai cong 8085...
py test_server\mock_server.py 8085
pause
