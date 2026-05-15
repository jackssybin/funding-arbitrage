package com.quant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 策略控制器 - 实现远程启停、参数热更新
 * 
 * 功能：
 * 1. 远程暂停/恢复策略
 * 2. 参数热更新（不需要重启）
 * 3. 紧急一键平仓
 * 4. 运行状态查询
 */
public class StrategyControl {

    private static final Logger log = LoggerFactory.getLogger(StrategyControl.class);

    // 运行状态
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean pauseTrading = new AtomicBoolean(false);

    // 可热更新的参数
    private volatile BigDecimal minFundingRate;
    private volatile BigDecimal closeFundingRate;
    private volatile BigDecimal switchThreshold;
    private volatile BigDecimal stopLossRatio;
    private volatile BigDecimal takeProfitRatio;
    private volatile int maxPositions;
    private volatile long checkIntervalMs;

    private FundingArbitrageBot bot;

    public StrategyControl() {
        // 初始化默认值
        this.minFundingRate = new BigDecimal("0.0006");   // 0.06% 开仓阈值
        this.closeFundingRate = new BigDecimal("0.0002"); // 0.02% 平仓阈值
        this.switchThreshold = new BigDecimal("0.001");   // 0.10% 移仓阈值
        this.stopLossRatio = new BigDecimal("0.02");      // 2% 止损
        this.takeProfitRatio = new BigDecimal("0.05");    // 5% 止盈
        this.maxPositions = 3;
        this.checkIntervalMs = 600_000; // 10分钟
    }

    public void setBot(FundingArbitrageBot bot) {
        this.bot = bot;
    }

    // ===== 启停控制 =====

    /**
     * 暂停交易（只监控，不开新仓）
     */
    public void pauseTrading() {
        pauseTrading.set(true);
        log.warn("⏸️  交易已暂停 - 继续监控但不新开仓位");
    }

    /**
     * 恢复交易
     */
    public void resumeTrading() {
        pauseTrading.set(false);
        log.info("▶️  交易已恢复");
    }

    /**
     * 完全停止策略
     */
    public void stop() {
        running.set(false);
        log.warn("🛑 策略已停止");
    }

    /**
     * 紧急一键平仓所有仓位
     */
    public void emergencyCloseAll() {
        log.warn("🚨 执行紧急一键平仓！");
        if (bot != null) {
            bot.emergencyCloseAll();
        }
        pauseTrading.set(true);
    }

    // ===== 参数热更新 =====

    /**
     * 更新开仓阈值
     */
    public void setMinFundingRate(BigDecimal rate) {
        log.info("🔧 热更新开仓阈值: {}% -> {}%",
                this.minFundingRate.multiply(BigDecimal.valueOf(100)),
                rate.multiply(BigDecimal.valueOf(100)));
        this.minFundingRate = rate;
    }

    /**
     * 更新平仓阈值
     */
    public void setCloseFundingRate(BigDecimal rate) {
        log.info("🔧 热更新平仓阈值: {}% -> {}%",
                this.closeFundingRate.multiply(BigDecimal.valueOf(100)),
                rate.multiply(BigDecimal.valueOf(100)));
        this.closeFundingRate = rate;
    }

    /**
     * 更新止损比例
     */
    public void setStopLossRatio(BigDecimal ratio) {
        log.info("🔧 热更新止损比例: {}% -> {}%",
                this.stopLossRatio.multiply(BigDecimal.valueOf(100)),
                ratio.multiply(BigDecimal.valueOf(100)));
        this.stopLossRatio = ratio;
    }

    /**
     * 更新止盈比例
     */
    public void setTakeProfitRatio(BigDecimal ratio) {
        log.info("🔧 热更新止盈比例: {}% -> {}%",
                this.takeProfitRatio.multiply(BigDecimal.valueOf(100)),
                ratio.multiply(BigDecimal.valueOf(100)));
        this.takeProfitRatio = ratio;
    }

    /**
     * 更新最大持仓数
     */
    public void setMaxPositions(int max) {
        log.info("🔧 热更新最大持仓数: {} -> {}", this.maxPositions, max);
        this.maxPositions = max;
    }

    /**
     * 更新检查间隔
     */
    public void setCheckIntervalMs(long interval) {
        log.info("🔧 热更新检查间隔: {}s -> {}s",
                this.checkIntervalMs / 1000, interval / 1000);
        this.checkIntervalMs = interval;
    }

    // ===== Getters =====

    public boolean isRunning() {
        return running.get();
    }

    public boolean isTradingPaused() {
        return pauseTrading.get();
    }

    public BigDecimal getMinFundingRate() {
        return minFundingRate;
    }

    public BigDecimal getCloseFundingRate() {
        return closeFundingRate;
    }

    public BigDecimal getSwitchThreshold() {
        return switchThreshold;
    }

    public BigDecimal getStopLossRatio() {
        return stopLossRatio;
    }

    public BigDecimal getTakeProfitRatio() {
        return takeProfitRatio;
    }

    public int getMaxPositions() {
        return maxPositions;
    }

    public long getCheckIntervalMs() {
        return checkIntervalMs;
    }

    /**
     * 获取当前状态摘要
     */
    public String getStatusSummary() {
        return String.format(
            "运行状态: %s | 交易: %s | 阈值: %s/%s%% | 止损: %s%% | 持仓: %d个",
            running.get() ? "运行中" : "已停止",
            pauseTrading.get() ? "已暂停" : "正常",
            minFundingRate.multiply(BigDecimal.valueOf(100)),
            closeFundingRate.multiply(BigDecimal.valueOf(100)),
            stopLossRatio.multiply(BigDecimal.valueOf(100)),
            maxPositions
        );
    }
}
