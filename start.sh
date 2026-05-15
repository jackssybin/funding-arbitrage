#!/bin/bash
echo "========================================"
echo "  资金费率套利机器人 - Linux/Mac启动脚本"
echo "========================================"
echo ""

# 确保日志目录存在
mkdir -p logs
mkdir -p data

# 设置Java参数
JAVA_OPTS="-Dfile.encoding=UTF-8"

# 启动程序
java $JAVA_OPTS -jar target/funding-arbitrage-1.0-SNAPSHOT.jar
