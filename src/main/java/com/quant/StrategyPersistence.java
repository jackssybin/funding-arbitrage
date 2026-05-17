package com.quant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 策略状态持久化 - 解决重启丢失数据问题
 * 
 * 功能：
 * 1. 自动保存持仓状态、收益数据、历史费率
 * 2. 启动时自动恢复上次状态
 * 3. 每日生成交易记录CSV
 */
public class StrategyPersistence {

    private static final Logger log = LoggerFactory.getLogger(StrategyPersistence.class);
    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter dateDtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final String dataDir;
    private final ObjectMapper mapper;
    private final String stateFile;
    private final String tradesFile;

    public StrategyPersistence() {
        this.dataDir = System.getProperty("user.dir") + File.separator + "data";
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        this.stateFile = dataDir + File.separator + "strategy_state.json";
        this.tradesFile = dataDir + File.separator + "trades_history.csv";

        initDataDir();
    }

    private void initDataDir() {
        File dir = new File(dataDir);
        if (!dir.exists()) {
            dir.mkdirs();
            log.info("📁 创建数据目录: {}", dataDir);
        }

        // 初始化CSV表头
        File csv = new File(tradesFile);
        if (!csv.exists()) {
            try {
                String header = "时间,类型,币种,方向,数量,价格,费率,收益,累计收益\n";
                java.nio.file.Files.write(csv.toPath(), header.getBytes("UTF-8"));
            } catch (IOException e) {
                log.warn("初始化交易记录失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 保存策略状态
     */
    public void saveState(Map<String, Position> positions, BigDecimal totalPnl, int totalTrades) {
        try {
            StrategyState state = new StrategyState();
            state.saveTime = LocalDateTime.now().format(dtf);
            state.totalPnl = totalPnl.doubleValue();
            state.totalTrades = totalTrades;

            for (Position pos : positions.values()) {
                if (pos.hasPosition()) {
                    PositionState ps = new PositionState();
                    ps.symbol = pos.getSymbol();
                    ps.positionSide = pos.getPositionSide();
                    ps.positionSize = pos.getPositionSize().doubleValue();
                    ps.entryPrice = pos.getEntryPrice().doubleValue();
                    ps.lastFundingRate = pos.getLastFundingRate().doubleValue();
                    ps.entryTime = pos.getEntryTime().format(dtf);
                    ps.fundingCount = pos.getFundingCount();
                    ps.totalFundingEarned = pos.getTotalFundingEarned().doubleValue();
                    state.positions.add(ps);
                }
            }

            mapper.writeValue(new File(stateFile), state);
            log.debug("💾 策略状态已保存");
        } catch (Exception e) {
            log.error("保存状态失败: {}", e.getMessage());
        }
    }

    /**
     * 恢复策略状态
     */
    public StrategyState restoreState() {
        try {
            File file = new File(stateFile);
            if (file.exists()) {
                StrategyState state = mapper.readValue(file, StrategyState.class);
                log.info("💾 恢复策略状态: {} 持仓, 累计收益 {} USDT", 
                        state.positions.size(), state.totalPnl);
                return state;
            }
        } catch (Exception e) {
            log.error("恢复状态失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 记录开仓
     */
    public void recordOpen(String symbol, String side, BigDecimal size, BigDecimal price, BigDecimal rate) {
        String line = String.format("%s,OPEN,%s,%s,%.8f,%.2f,%.6f,,\n",
                LocalDateTime.now().format(dtf),
                symbol, side, size, price, rate);
        appendToCsv(line);
    }

    /**
     * 记录平仓
     */
    public void recordClose(String symbol, BigDecimal earning, BigDecimal totalPnl) {
        String line = String.format("%s,CLOSE,%s,,,,,%.4f,%.4f\n",
                LocalDateTime.now().format(dtf),
                symbol, earning, totalPnl);
        appendToCsv(line);
    }

    /**
     * 记录资金费结算
     */
    public void recordFunding(String symbol, BigDecimal earning, BigDecimal rate) {
        String line = String.format("%s,FUNDING,%s,,,%.6f,%.4f,\n",
                LocalDateTime.now().format(dtf),
                symbol, rate, earning);
        appendToCsv(line);
    }

    private void appendToCsv(String line) {
        try {
            java.nio.file.Files.write(
                    new File(tradesFile).toPath(),
                    line.getBytes("UTF-8"),
                    java.nio.file.StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            log.warn("写入交易记录失败: {}", e.getMessage());
        }
    }

    /**
     * 获取今日交易统计
     */
    public DailyStats getTodayStats() {
        DailyStats stats = new DailyStats();
        String today = LocalDateTime.now().format(dateDtf);
        
        try {
            List<String> lines = java.nio.file.Files.readAllLines(new File(tradesFile).toPath());
            for (String line : lines) {
                if (line.startsWith(today)) {
                    String[] parts = line.split(",");
                    if (parts.length >= 3) {
                        String type = parts[1];
                        if ("OPEN".equals(type)) stats.openCount++;
                        if ("CLOSE".equals(type)) stats.closeCount++;
                        if ("FUNDING".equals(type)) stats.fundingCount++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取今日统计失败: {}", e.getMessage());
        }
        
        return stats;
    }

    // ===== 内部类 =====

    public static class StrategyState {
        public String saveTime;
        public double totalPnl;
        public int totalTrades;
        public List<PositionState> positions = new ArrayList<>();
    }

    public static class PositionState {
        public String symbol;
        public String positionSide;
        public double positionSize;
        public double entryPrice;
        public double lastFundingRate;
        public String entryTime;
        public int fundingCount;
        public double totalFundingEarned;
    }

    public static class DailyStats {
        public int openCount = 0;
        public int closeCount = 0;
        public int fundingCount = 0;
    }
}
