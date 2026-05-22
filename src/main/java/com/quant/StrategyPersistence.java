package com.quant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final String executionCostsFile;

    public StrategyPersistence() {
        this(new File(System.getProperty("user.dir"), "data"));
    }

    StrategyPersistence(File dataDir) {
        this.dataDir = dataDir.getPath();
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        this.stateFile = dataDir + File.separator + "strategy_state.json";
        this.tradesFile = dataDir + File.separator + "trades_history.csv";
        this.executionCostsFile = dataDir + File.separator + "execution_costs.csv";

        initDataDir();
        ensureExecutionCostsFile();
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
    private void ensureExecutionCostsFile() {
        File costsCsv = new File(executionCostsFile);
        if (!costsCsv.exists()) {
            try {
                String header = "time,type,symbol,side,orderIds,estimatedFee,actualFee,feeDelta,"
                        + "estimatedSlippage,actualSlippage,slippageDelta,expectedNetFunding,"
                        + "actualNotional,executionPrice,estimatedPrice,executionEstimated\n";
                java.nio.file.Files.write(costsCsv.toPath(), header.getBytes("UTF-8"));
            } catch (IOException e) {
                log.warn("鍒濆鍖栨墽琛屾垚鏈褰曞け璐? {}", e.getMessage());
            }
        }
    }

    public void saveState(Map<String, Position> positions, BigDecimal totalPnl, int totalTrades) {
        saveState(positions, totalPnl, totalTrades, 0L);
    }

    /**
     * 保存策略状态（带 lastSettledHourUtc，防止重启后重复结算）
     */
    public void saveState(Map<String, Position> positions, BigDecimal totalPnl, int totalTrades,
                          long lastFundingIncomeQueryTime, Map<String, Long> lastFundingIncomeQueryTimes,
                          int lastSettledHourUtc) {
        try {
            StrategyState state = new StrategyState();
            state.saveTime = LocalDateTime.now().format(dtf);
            state.totalPnl = totalPnl.doubleValue();
            state.totalTrades = totalTrades;
            state.lastFundingIncomeQueryTime = lastFundingIncomeQueryTime;
            state.lastFundingIncomeQueryTimes = lastFundingIncomeQueryTimes == null
                    ? new HashMap<>()
                    : new HashMap<>(lastFundingIncomeQueryTimes);
            state.lastSettledHourUtc = lastSettledHourUtc;

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
                    ps.hedged = pos.isHedged();
                    ps.spotPositionSize = pos.getSpotPositionSize().doubleValue();
                    ps.spotEntryPrice = pos.getSpotEntryPrice().doubleValue();
                    ps.hedgeRatio = pos.getHedgeRatio().doubleValue();
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
     * 保存策略状态
     */
    public void saveState(Map<String, Position> positions, BigDecimal totalPnl, int totalTrades,
                          long lastFundingIncomeQueryTime) {
        saveState(positions, totalPnl, totalTrades, lastFundingIncomeQueryTime, new HashMap<>());
    }

    /**
     * 保存策略状态
     */
    public void saveState(Map<String, Position> positions, BigDecimal totalPnl, int totalTrades,
                          long lastFundingIncomeQueryTime, Map<String, Long> lastFundingIncomeQueryTimes) {
        try {
            StrategyState state = new StrategyState();
            state.saveTime = LocalDateTime.now().format(dtf);
            state.totalPnl = totalPnl.doubleValue();
            state.totalTrades = totalTrades;
            state.lastFundingIncomeQueryTime = lastFundingIncomeQueryTime;
            state.lastFundingIncomeQueryTimes = lastFundingIncomeQueryTimes == null
                    ? new HashMap<>()
                    : new HashMap<>(lastFundingIncomeQueryTimes);

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
                    ps.hedged = pos.isHedged();
                    ps.spotPositionSize = pos.getSpotPositionSize().doubleValue();
                    ps.spotEntryPrice = pos.getSpotEntryPrice().doubleValue();
                    ps.hedgeRatio = pos.getHedgeRatio().doubleValue();
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

    public void recordExecutionCost(String type, String symbol, String side, String orderIds,
                                    BigDecimal estimatedFee, BigDecimal actualFee,
                                    BigDecimal estimatedSlippage, BigDecimal actualSlippage,
                                    BigDecimal expectedNetFunding, BigDecimal actualNotional,
                                    BigDecimal executionPrice, BigDecimal estimatedPrice,
                                    boolean executionEstimated) {
        BigDecimal feeDelta = safe(actualFee).subtract(safe(estimatedFee));
        BigDecimal slippageDelta = safe(actualSlippage).subtract(safe(estimatedSlippage));
        String line = String.join(",",
                LocalDateTime.now().format(dtf),
                csv(type),
                csv(symbol),
                csv(side),
                csv(orderIds),
                decimal(estimatedFee),
                decimal(actualFee),
                decimal(feeDelta),
                decimal(estimatedSlippage),
                decimal(actualSlippage),
                decimal(slippageDelta),
                decimal(expectedNetFunding),
                decimal(actualNotional),
                decimal(executionPrice),
                decimal(estimatedPrice),
                Boolean.toString(executionEstimated)
        ) + "\n";

        try {
            java.nio.file.Files.write(
                    new File(executionCostsFile).toPath(),
                    line.getBytes("UTF-8"),
                    java.nio.file.StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            log.warn("鍐欏叆鎵ц鎴愭湰璁板綍澶辫触: {}", e.getMessage());
        }
    }

    public CostDeviationStats getRecentCostDeviationStats(String symbol, int maxRows, boolean includeEstimated) {
        File file = new File(executionCostsFile);
        if (!file.exists()) {
            return CostDeviationStats.empty();
        }

        try {
            List<String> lines = java.nio.file.Files.readAllLines(file.toPath());
            int start = Math.max(1, lines.size() - Math.max(0, maxRows));
            CostDeviationStats stats = new CostDeviationStats();
            for (int i = start; i < lines.size(); i++) {
                String[] parts = splitCsvLine(lines.get(i));
                if (parts.length < 16) {
                    continue;
                }
                if (symbol != null && !symbol.isEmpty() && !symbol.equalsIgnoreCase(parts[2])) {
                    continue;
                }
                boolean estimated = Boolean.parseBoolean(parts[15]);
                if (estimated && !includeEstimated) {
                    continue;
                }
                stats.sampleCount++;
                stats.positiveFeeDelta = stats.positiveFeeDelta.add(positive(parseDecimal(parts[7])));
                stats.positiveSlippageDelta = stats.positiveSlippageDelta.add(positive(parseDecimal(parts[10])));
            }
            return stats;
        } catch (Exception e) {
            log.warn("璇诲彇鎵ц鎴愭湰鍋忓樊澶辫触: {}", e.getMessage());
            return CostDeviationStats.empty();
        }
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
    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String decimal(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String[] splitCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values.toArray(new String[0]);
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.trim());
    }

    private BigDecimal positive(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) > 0 ? value : BigDecimal.ZERO;
    }

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
        public long lastFundingIncomeQueryTime;
        public Map<String, Long> lastFundingIncomeQueryTimes = new HashMap<>();
        public List<PositionState> positions = new ArrayList<>();
        /** 最近一次已结算的 UTC 结算小时（0/8/16），用于重启后避免重复结算 */
        public int lastSettledHourUtc = -1;
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
        public boolean hedged;
        public double spotPositionSize;
        public double spotEntryPrice;
        public double hedgeRatio;
    }

    public static class DailyStats {
        public int openCount = 0;
        public int closeCount = 0;
        public int fundingCount = 0;
    }

    public static class CostDeviationStats {
        public int sampleCount = 0;
        public BigDecimal positiveFeeDelta = BigDecimal.ZERO;
        public BigDecimal positiveSlippageDelta = BigDecimal.ZERO;

        public static CostDeviationStats empty() {
            return new CostDeviationStats();
        }

        public BigDecimal averagePositiveOneWayDelta() {
            if (sampleCount <= 0) {
                return BigDecimal.ZERO;
            }
            return positiveFeeDelta.add(positiveSlippageDelta)
                    .divide(BigDecimal.valueOf(sampleCount), 8, RoundingMode.HALF_UP);
        }
    }
}
