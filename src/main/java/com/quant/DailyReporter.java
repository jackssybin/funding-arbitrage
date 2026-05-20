package com.quant;
import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动日报生成器
 * 
 * 功能：
 * 1. 自动记录每天的开仓/平仓次数
 * 2. 记录资金费收益
 * 3. 记录浮盈浮亏
 * 4. 记录余额变动
 * 5. 每天凌晨自动生成markdown日报
 * 
 * 日报保存位置：data/daily_reports/YYYY-MM-DD.md
 */
public class DailyReporter {

    private static final Logger log = LoggerFactory.getLogger(DailyReporter.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // 日报数据目录
    private static final String REPORT_DIR = "data/daily_reports";

    // 当日统计数据
    private volatile int openCount = 0;
    private volatile int closeCount = 0;
    private volatile BigDecimal totalFundingEarning = BigDecimal.ZERO;
    private volatile BigDecimal startBalance = null;
    private volatile BigDecimal lastBalance = null;
    private final List<String> eventLogs = new ArrayList<>();
    private final Map<String, PositionSnapshot> positionSnapshots = new ConcurrentHashMap<>();

    private LocalDate currentDate;
    private FeishuNotifier feishuNotifier;

    public DailyReporter() {
        this(null);
    }

    public DailyReporter(FeishuNotifier feishuNotifier) {
        this.feishuNotifier = feishuNotifier;
        this.currentDate = LocalDate.now();
        initReportDir();
        log.info("📝 自动日报生成器已启动");
    }

    private void initReportDir() {
        File dir = new File(REPORT_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
            log.info("📁 创建日报目录: {}", REPORT_DIR);
        }
    }

    /**
     * 检查是否是新的一天，如果是就生成昨天的日报并重置数据
     */
    public void checkAndGenerateReport() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDate)) {
            // 新的一天，生成昨天的日报
            String report = generateDailyReport(currentDate);
            
            // 飞书推送日报
            if (feishuNotifier != null) {
                feishuNotifier.sendDailyReport(report);
                log.info("📤 日报已推送到飞书");
            }
            
            // 重置数据
            resetDailyStats();
            currentDate = today;
            
            log.info("📅 新的一天开始，已生成昨日日报");
        }
    }

    /**
     * 记录开仓事件
     */
    public void recordOpen(String symbol, String side, BigDecimal size, BigDecimal rate) {
        openCount++;
        String time = LocalDateTime.now().format(TIME_FORMAT);
        String logMsg = String.format("[%s] 🚀 开仓 %s %s, 数量: %s, 费率: %.4f%%",
                time, symbol, side, size.toPlainString(), rate.multiply(new BigDecimal("100")).doubleValue());
        eventLogs.add(logMsg);
        log.info("📝 日报记录: {}", logMsg);
    }

    /**
     * 记录平仓事件
     */
    public void recordClose(String symbol, BigDecimal fundingEarned, BigDecimal pnl, String reason) {
        closeCount++;
        String time = LocalDateTime.now().format(TIME_FORMAT);
        String logMsg = String.format("[%s] 📉 平仓 %s, 资金费收益: %.2f USDT, 买卖盈亏: %.2f USDT, 原因: %s",
                time, symbol, fundingEarned.doubleValue(), pnl.doubleValue(), reason);
        eventLogs.add(logMsg);
        log.info("📝 日报记录: {}", logMsg);
    }

    /**
     * 记录资金费结算
     */
    public void recordFundingSettlement(String symbol, BigDecimal earning, BigDecimal rate) {
        totalFundingEarning = totalFundingEarning.add(earning);
        String time = LocalDateTime.now().format(TIME_FORMAT);
        String logMsg = String.format("[%s] 💰 %s 资金费结算: +%.4f USDT, 费率: %.4f%%",
                time, symbol, earning.doubleValue(), rate.abs().multiply(new BigDecimal("100")).doubleValue());
        eventLogs.add(logMsg);
        log.info("📝 日报记录: {}", logMsg);
    }

    /**
     * 更新余额
     */
    public void updateBalance(BigDecimal balance) {
        if (startBalance == null) {
            startBalance = balance;
        }
        lastBalance = balance;
    }

    /**
     * 记录持仓快照
     */
    public void snapshotPosition(String symbol, String side, BigDecimal size, BigDecimal entryPrice,
                                  BigDecimal unrealizedPnl, BigDecimal unrealizedPnlRatio, BigDecimal currentRate) {
        PositionSnapshot snapshot = new PositionSnapshot();
        snapshot.symbol = symbol;
        snapshot.side = side;
        snapshot.size = size;
        snapshot.entryPrice = entryPrice;
        snapshot.unrealizedPnl = unrealizedPnl;
        snapshot.unrealizedPnlRatio = unrealizedPnlRatio;
        snapshot.currentRate = currentRate;
        snapshot.snapshotTime = LocalDateTime.now();
        positionSnapshots.put(symbol, snapshot);
    }

    /**
     * 记录异常事件
     */
    public void recordAlert(String level, String message) {
        String time = LocalDateTime.now().format(TIME_FORMAT);
        String logMsg = String.format("[%s] ⚠️  [%s] %s", time, level, message);
        eventLogs.add(logMsg);
        log.warn("📝 日报告警记录: {}", logMsg);
    }

    /**
     * 生成日报
     * @return 日报内容字符串
     */
    public String generateDailyReport(LocalDate date) {
        String filename = REPORT_DIR + "/" + date.format(DATE_FORMAT) + ".md";
        
        StringBuilder report = new StringBuilder();
        
        // 标题
        report.append("# 📊 资金费率套利机器人 - 日报\n\n");
        report.append("**日期**: ").append(date.format(DATE_FORMAT)).append("\n\n");
        report.append("---\n\n");

        // 当日统计摘要
        report.append("## 📈 当日统计摘要\n\n");
        report.append("| 指标 | 数值 |\n");
        report.append("|------|------|\n");
        report.append("| 🚀 开仓次数 | ").append(openCount).append(" 次 |\n");
        report.append("| 📉 平仓次数 | ").append(closeCount).append(" 次 |\n");
        report.append("| 💰 资金费收益 | ").append(totalFundingEarning.setScale(4, RoundingMode.HALF_UP)).append(" USDT |\n");
        
        if (startBalance != null && lastBalance != null) {
            BigDecimal balanceChange = lastBalance.subtract(startBalance);
            String changeSign = balanceChange.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
            report.append("| 💵 余额变动 | ").append(changeSign).append(balanceChange.setScale(2, RoundingMode.HALF_UP)).append(" USDT |\n");
        }
        
        report.append("| 📊 当前持仓数 | ").append(positionSnapshots.size()).append(" 个 |\n");
        report.append("\n");

        // 当前持仓详情
        report.append("## 📦 当前持仓详情\n\n");
        if (positionSnapshots.isEmpty()) {
            report.append("暂无持仓\n\n");
        } else {
            report.append("| 币种 | 方向 | 数量 | 开仓价格 | 当前费率 | 浮盈浮亏 | 盈亏比例 |\n");
            report.append("|------|------|------|---------|---------|---------|---------|\n");
            
            BigDecimal totalPnl = BigDecimal.ZERO;
            
            for (PositionSnapshot pos : positionSnapshots.values()) {
                String pnlStr = pos.unrealizedPnl.setScale(4, RoundingMode.HALF_UP).toPlainString();
                String pnlRatioStr = pos.unrealizedPnlRatio.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toPlainString();
                String pnlSign = pos.unrealizedPnl.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
                
                totalPnl = totalPnl.add(pos.unrealizedPnl);
                
                report.append("| ")
                      .append(pos.symbol).append(" | ")
                      .append(pos.side).append(" | ")
                      .append(pos.size.setScale(4, RoundingMode.HALF_UP)).append(" | ")
                      .append(pos.entryPrice.setScale(2, RoundingMode.HALF_UP)).append(" | ")
                      .append(pos.currentRate.abs().multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP)).append("% | ")
                      .append(pnlSign).append(pnlStr).append(" USDT | ")
                      .append(pnlSign).append(pnlRatioStr).append("% |\n");
            }
            
            report.append("\n");
            report.append("**持仓总浮盈浮亏**: ").append(totalPnl.setScale(4, RoundingMode.HALF_UP)).append(" USDT\n\n");
        }

        // 事件日志
        report.append("## 📝 当日事件日志\n\n");
        if (eventLogs.isEmpty()) {
            report.append("暂无事件记录\n\n");
        } else {
            for (String event : eventLogs) {
                report.append("- ").append(event).append("\n");
            }
            report.append("\n");
        }

        // 余额详情
        report.append("## 💵 余额情况\n\n");
        if (startBalance != null) {
            report.append("- 初始余额: ").append(startBalance.setScale(2, RoundingMode.HALF_UP)).append(" USDT\n");
        }
        if (lastBalance != null) {
            report.append("- 当前余额: ").append(lastBalance.setScale(2, RoundingMode.HALF_UP)).append(" USDT\n");
            if (startBalance != null) {
                BigDecimal change = lastBalance.subtract(startBalance);
                String sign = change.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
                report.append("- 余额变动: ").append(sign).append(change.setScale(2, RoundingMode.HALF_UP)).append(" USDT\n");
            }
        }
        report.append("\n");

        // 策略运行状态
        report.append("## 🤖 策略运行状态\n\n");
        report.append("- 运行时长: 自启动至今 ").append(startBalance == null ? "N/A" : "正常运行").append("\n");
        report.append("- 策略版本: v2.5\n");
        report.append("- 开仓阈值: 0.15%\n");
        report.append("- 止损比例: 8%\n");
        report.append("- 止盈比例: 10%\n");
        report.append("- 连续亏损保护: 2次亏损后暂停4小时\n");
        report.append("\n");

        // 明日计划
        report.append("## 📋 明日观察要点\n\n");
        report.append("1. 观察当前持仓的费率变化\n");
        report.append("2. 关注是否有新高费率币种出现\n");
        report.append("3. 检查止损止盈是否正常触发\n");
        report.append("\n");

        // 备注
        report.append("---\n\n");
        report.append("*报告自动生成于: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("*\n");

        // 写入文件
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write(report.toString());
            log.info("✅ 日报已生成: {}", filename);
        } catch (IOException e) {
            log.error("❌ 生成日报失败: {}", e.getMessage(), e);
        }
        
        return report.toString();
    }

    /**
     * 立即生成今日日报（手动触发用）
     */
    public void generateTodayReport() {
        generateDailyReport(LocalDate.now());
        log.info("✅ 已手动生成今日日报");
    }

    /**
     * 重置每日统计数据
     */
    private void resetDailyStats() {
        openCount = 0;
        closeCount = 0;
        totalFundingEarning = BigDecimal.ZERO;
        startBalance = lastBalance; // 昨天的收盘余额是今天的起始余额
        eventLogs.clear();
        // 注意：不清除 positionSnapshots，因为持仓是跨天的
    }

    /**
     * 持仓快照
     */
    private static class PositionSnapshot {
        String symbol;
        String side;
        BigDecimal size;
        BigDecimal entryPrice;
        BigDecimal unrealizedPnl;
        BigDecimal unrealizedPnlRatio;
        BigDecimal currentRate;
        LocalDateTime snapshotTime;
    }

    /**
     * 获取当日统计摘要（用于仪表盘显示）
     */
    public Map<String, Object> getDailyStats() {
        Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("date", currentDate.format(DATE_FORMAT));
        stats.put("openCount", openCount);
        stats.put("closeCount", closeCount);
        stats.put("fundingEarning", totalFundingEarning);
        stats.put("startBalance", startBalance);
        stats.put("lastBalance", lastBalance);
        stats.put("positionCount", positionSnapshots.size());
        return stats;
    }
}
