#!/bin/bash
# ==========================================
# 资金费套利策略启动脚本
# 功能：1. 杀掉旧进程 2. 启动新进程 3. 确保只有一个进程在运行
# ==========================================

cd $(dirname $0)

echo "=========================================="
echo "  资金费套利策略启动脚本"
echo "=========================================="

# 1. 检查并杀掉旧进程
echo ""
echo "[1/4] 检查旧进程..."
OLD_PIDS=$(pgrep -f "funding-arbitrage-1.0.0.jar" 2>/dev/null)

if [ -n "$OLD_PIDS" ]; then
    echo "⚠️  发现旧进程在运行，PID: $OLD_PIDS"
    echo "正在优雅停止..."
    pkill -f "funding-arbitrage-1.0.0.jar" 2>/dev/null
    sleep 2

    # 二次检查，强制杀掉还在运行的
    REMAINING_PIDS=$(pgrep -f "funding-arbitrage-1.0.0.jar" 2>/dev/null)
    if [ -n "$REMAINING_PIDS" ]; then
        echo "⚠️  部分进程未停止，强制杀..."
        pkill -9 -f "funding-arbitrage-1.0.0.jar" 2>/dev/null
        sleep 1
    fi

    echo "✅ 旧进程已清理完毕"
else
    echo "✅ 没有发现旧进程"
fi

# 2. 确保日志目录存在
echo ""
echo "[2/4] 检查环境..."
mkdir -p logs
mkdir -p data

# 3. 编译（可选）
if [ "$1" = "build" ] || [ ! -f "target/funding-arbitrage-1.0.0.jar" ]; then
    echo ""
    echo "[3/4] 编译打包..."
    mvn package -DskipTests -q
    if [ $? -ne 0 ]; then
        echo "❌ 编译失败，退出"
        exit 1
    fi
    echo "✅ 编译完成"
else
    echo "[3/4] 跳过编译（jar包已存在）"
fi

# 4. 启动新进程
echo ""
echo "[4/4] 启动新进程..."
nohup java -Dfile.encoding=UTF-8 -jar target/funding-arbitrage-1.0.0.jar > logs/app.log 2>&1 &
echo $! > pid.txt

sleep 2

# 5. 验证启动成功
NEW_PID=$(cat pid.txt)
if ps -p $NEW_PID > /dev/null 2>&1; then
    echo ""
    echo "=========================================="
    echo "  ✅ 策略启动成功！"
    echo "  📍 PID: $NEW_PID"
    echo "  📊 日志: tail -f logs/app.log"
    echo "  📊 查看状态: ps aux | grep funding"
    echo "=========================================="
    exit 0
else
    echo ""
    echo "❌ 进程启动失败，请查看 logs/app.log"
    exit 1
fi
