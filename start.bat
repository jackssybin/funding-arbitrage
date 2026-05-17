@echo off
chcp 65001 >nul
echo ========================================
echo   资金费率套利机器人 - Windows启动脚本
echo ========================================
echo.

REM 设置Java参数，解决中文乱码
set JAVA_OPTS=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8

REM 启动程序
java %JAVA_OPTS% -jar target/funding-arbitrage-1.0.0.jar

pause
