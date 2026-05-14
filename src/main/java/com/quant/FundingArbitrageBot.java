package com.quant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 多币种资金费率套利机器人 - 增强版
 * 
 * 功能实现：
 * 1. 多币种轮动：监控BTC、ETH、SOL等多个币种，哪个费率高开哪个
 * 2. 杠杆优化：可配置2-3倍杠杆提高资金利用率
 * 3. 自动移仓：费率不足时自动移到更高费率的币种
 * 4. 网格增强：持仓期间用小网格赚额外收益
 */
public class FundingArbitrageBot {

    private static final Logger log = LoggerFactory.getLogger(FundingArbitrageBot.class);
    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    // ========== 核心组件 ==========
    private BinanceFuturesClient futuresClient;
    private GridTrading gridTrading;
    private ExchangePrecision precision;           // 防线1: 精度对齐
    private AtomicTransactionManager txManager;     // 防线2: 原子性回滚

    // ========== 状态变量 ==========
    private final Map<String, Position> positions = new HashMap<>();  // 各币种持仓
    private final Map<String, BigDecimal> fundingRates = new HashMap<>();  // 最新费率
    private volatile BigDecimal dailyPnl = BigDecimal.ZERO;
    private volatile LocalDateTime lastRateUpdate = null;
    private volatile BigDecimal initialBalance = BigDecimal.ZERO;
    private volatile BigDecimal maxBalance = BigDecimal.ZERO;

    public static void main(String[] args) {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        
        // 打印启动信息
        log.info("");
        log.info("╔═════════════════════════════════════════════════════════════╗");
        log.info("║           多币种资金费率套利机器人 v2.0 [增强版]              ║");
        log.info("║    ✓ 多币种轮动   ✓ 杠杆优化   ✓ 自动移仓   ✓ 网格增强       ║");
        log.info("╚═════════════════════════════════════════════════════════════╝");
        log.info("");
        
        // 检查配置
        if (!Config.validate()) {
            System.exit(1);
        }

        // 初始化
        if (bot.init()) {
            bot.run();
        } else {
            log.error("❌ 初始化失败，程序退出");
            System.exit(1);
        }
    }

    /**
     * 初始化所有组件
     */
    public boolean init() {
        try {
            log.info("=== 开始初始化 ===");

            // 1. 初始化合约客户端
            futuresClient = new BinanceFuturesClient(Config.API_KEY, Config.SECRET_KEY);
            if (!futuresClient.testConnection()) {
                throw new RuntimeException("合约API连接失败");
            }

            // 2. 初始化两道硬核防线
            // 防线1: 精度对齐工具
            precision = new ExchangePrecision(futuresClient);
            if (Config.SIMULATION_MODE) {
                precision.loadMockFilters();
            } else {
                precision.loadAllSymbolFilters();
            }
            
            // 防线2: 原子性事务管理器
            txManager = new AtomicTransactionManager(futuresClient);

            // 3. 初始化网格交易组件
            gridTrading = new GridTrading(futuresClient);

            // 3. 初始化持仓对象
            for (String symbol : Config.TRADING_SYMBOLS) {
                positions.put(symbol, new Position(symbol));
            }

            // 4. 获取初始资金费率
            updateFundingRates();

            // 5. 获取初始余额
            updateBalance();

            // 6. 打印配置
            printConfig();

            log.info("✅ 初始化完成!");
            return true;

        } catch (Exception e) {
            log.error("❌ 初始化失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 打印当前配置
     */
    private void printConfig() {
        log.info("");
        log.info("┌───────────────────────────────────────────────────────┐");
        log.info("│                   当前配置                                │");
        log.info("├───────────────────────────────────────────────────────┤");
        log.info("│ 监控币种:       {}", Config.TRADING_SYMBOLS);
        log.info("│ 最大持仓数:     {} 个币种", Config.MAX_POSITIONS);
        log.info("│ 杠杆倍数:       {}x", Config.LEVERAGE);
        log.info("│ 单仓位价值:     {} USDT", Config.POSITION_VALUE_USDT);
        log.info("│ 开仓阈值:       {}%", Config.FUNDING_RATE_THRESHOLD
                .multiply(new BigDecimal("100")).setScale(3, RoundingMode.HALF_UP));
        log.info("│ 平仓阈值:       {}%", Config.FUNDING_RATE_CLOSE_THRESHOLD
                .multiply(new BigDecimal("100")).setScale(3, RoundingMode.HALF_UP));
        log.info("│ 移仓阈值:       {}%", Config.SWITCH_THRESHOLD
                .multiply(new BigDecimal("100")).setScale(3, RoundingMode.HALF_UP));
        log.info("│ 网格交易:       {}", Config.GRID_ENABLED ? "启用 ✓" : "禁用");
        if (Config.GRID_ENABLED) {
            log.info("│ 网格层数:       {} 层", Config.GRID_LEVELS);
            log.info("│ 网格间距:       {}%", Config.GRID_SPACING
                    .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
        }
        log.info("│ 模拟模式:       {}", Config.SIMULATION_MODE ? "开启 ✅" : "关闭 ❌");
        log.info("└───────────────────────────────────────────────────────┘");
        log.info("");
    }

    /**
     * 主运行循环
     */
    public void run() {
        log.info("🚀 机器人启动，开始监控多币种资金费率...");
        log.info("");

        while (true) {
            try {
                LocalDateTime now = LocalDateTime.now();
                log.info("═══════════════════════════════════════════════════════════");
                log.info("⏰ 检查时间: {}", now.format(dtf));

                // 1. 更新资金费率（按配置的时间间隔）
                updateFundingRatesIfNeeded();

                // 2. 风控检查
                if (!checkRiskControl()) {
                    log.warn("⚠️ 触发风控，等待下次检查...");
                    sleep();
                    continue;
                }

                // 3. 打印当前状态
                printCurrentStatus();

                // 4. 执行策略：多币种轮动 + 自动移仓
                executeStrategy();

                // 5. 维护网格订单
                maintainGrids();

                // 6. 等待下一次检查
                sleep();

            } catch (Exception e) {
                log.error("❌ 运行异常: {}", e.getMessage(), e);
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * 更新所有币种的资金费率
     */
    private void updateFundingRates() {
        try {
            Map<String, BigDecimal> rates = futuresClient.getAllFundingRates(Config.TRADING_SYMBOLS);
            this.fundingRates.putAll(rates);
            this.lastRateUpdate = LocalDateTime.now();
            log.info("✅ 已更新 {} 个币种的资金费率", rates.size());
        } catch (IOException e) {
            log.error("更新资金费率失败: {}", e.getMessage());
        }
    }

    /**
     * 如果需要，更新资金费率
     */
    private void updateFundingRatesIfNeeded() {
        if (lastRateUpdate == null ||
                lastRateUpdate.plusSeconds(Config.RATE_UPDATE_INTERVAL_MS / 1000).isBefore(LocalDateTime.now())) {
            updateFundingRates();
        }
    }

    /**
     * 更新余额
     */
    private void updateBalance() {
        try {
            BigDecimal balance = futuresClient.getBalance();
            if (BigDecimal.ZERO.equals(initialBalance)) {
                initialBalance = balance;
                maxBalance = balance;
            }
            if (balance.compareTo(maxBalance) > 0) {
                maxBalance = balance;
            }
        } catch (IOException e) {
            log.error("更新余额失败: {}", e.getMessage());
        }
    }

    /**
     * 执行核心策略
     * 1. 选出费率最高的币种
     * 2. 对持仓中费率不足的平仓
     * 3. 对有更好费率的币种执行移仓或开仓
     */
    private void executeStrategy() throws IOException {
        // 1. 按费率排序（从高到低）
        List<Map.Entry<String, BigDecimal>> sortedRates = new ArrayList<>(fundingRates.entrySet());
        sortedRates.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        // 2. 获取当前持仓数和当前持仓的最低费率
        int currentPositions = (int) positions.values().stream().filter(Position::hasPosition).count();
        BigDecimal minHeldRate = positions.values().stream()
                .filter(Position::hasPosition)
                .map(Position::getLastFundingRate)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        // 3. 处理现有持仓：费率不足的平仓
        for (Position position : positions.values()) {
            if (position.hasPosition()) {
                BigDecimal currentRate = fundingRates.getOrDefault(position.getSymbol(), BigDecimal.ZERO);
                if (currentRate.compareTo(Config.FUNDING_RATE_CLOSE_THRESHOLD) < 0) {
                    log.info("📉 {} 费率 {}% 低于平仓阈值，准备平仓...",
                            position.getSymbol(),
                            currentRate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
                    closePosition(position.getSymbol());
                    currentPositions--;
                }
            }
        }

        // 4. 对高费率币种开仓或移仓
        for (Map.Entry<String, BigDecimal> entry : sortedRates) {
            String symbol = entry.getKey();
            BigDecimal rate = entry.getValue();
            Position position = positions.get(symbol);

            // 跳过费率不足的币种
            if (rate.compareTo(Config.FUNDING_RATE_THRESHOLD) < 0) {
                break;  // 已排序，后面费率更低
            }

            // 情况1：还没持仓，有空位 -> 开仓
            if (!position.hasPosition() && currentPositions < Config.MAX_POSITIONS) {
                log.info("🎯 {} 费率 {}% 达标，准备开仓...",
                        symbol, rate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
                openPosition(symbol, rate);
                currentPositions++;
            }
            // 情况2：没持仓，没空位，但费率比持仓中最低的高很多 -> 移仓 [功能3]
            else if (!position.hasPosition() && currentPositions >= Config.MAX_POSITIONS) {
                BigDecimal diff = rate.subtract(minHeldRate);
                if (diff.compareTo(Config.SWITCH_THRESHOLD) > 0) {
                    // 找到费率最低的持仓，移仓
                    Position toClose = positions.values().stream()
                            .filter(Position::hasPosition)
                            .min(Comparator.comparing(Position::getLastFundingRate))
                            .orElse(null);
                    if (toClose != null) {
                        log.info("🔄 移仓：从 {} ({}%) 到 {} ({}%)，差值 {}%",
                                toClose.getSymbol(),
                                toClose.getLastFundingRate().multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                                symbol,
                                rate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                                diff.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
                        closePosition(toClose.getSymbol());
                        openPosition(symbol, rate);
                    }
                }
            }
        }
    }

    /**
     * 开仓：两道硬核防线加持
     * 防线1: 先进行精度对齐，避免 Filter failure
     * 防线2: 原子性开仓，现货+合约要么都成，要么都不成，失败自动回滚
     */
    private void openPosition(String symbol, BigDecimal fundingRate) throws IOException {
        log.info("");
        log.info("┌──────────────────────────────────────────────────────────┐");
        log.info("│              🚀 开仓操作 (双防线加持)                        │");
        log.info("│  防线1: 精度对齐 ✓    防线2: 原子性事务 ✓                   │");
        log.info("└──────────────────────────────────────────────────────────┘");

        try {
            // 1. 设置杠杆
            futuresClient.setLeverage(symbol, Config.LEVERAGE);

            // 2. 获取当前价格，计算开仓数量
            BigDecimal currentPrice = futuresClient.getCurrentPrice(symbol);
            // 数量 = (仓位价值 * 杠杆) / 价格
            BigDecimal rawQuantity = Config.POSITION_VALUE_USDT
                    .multiply(BigDecimal.valueOf(Config.LEVERAGE))
                    .divide(currentPrice, 12, RoundingMode.DOWN);

            log.info("当前价格: {} USDT", currentPrice);
            log.info("计算原始数量: {}", rawQuantity);

            // ========== 防线1: 精度对齐 ==========
            BigDecimal alignedQuantity = precision.alignQuantity(symbol, rawQuantity);
            if (BigDecimal.ZERO.compareTo(alignedQuantity) >= 0) {
                log.error("❌ 精度对齐后数量为0，无法开仓");
                return;
            }
            log.info("✅ 精度对齐后: {} (原始: {})", alignedQuantity, rawQuantity);

            // ========== 防线2: 原子性开仓（现货+合约） ==========
            AtomicTransactionManager.TxResult txResult = txManager.atomicOpenPosition(
                    symbol, alignedQuantity);

            if (!txResult.isSuccess()) {
                throw new IOException("原子性开仓失败: " + txResult.message);
            }

            // 3. 更新持仓状态
            Position position = positions.get(symbol);
            position.open(alignedQuantity, currentPrice, fundingRate);

            // 4. 设置网格交易
            if (Config.GRID_ENABLED) {
                gridTrading.setupGrid(position, currentPrice);
            }

            // 计算年化收益
            BigDecimal annualized = fundingRate
                    .multiply(new BigDecimal("1095"))  // 3 * 365
                    .multiply(new BigDecimal(Config.LEVERAGE))
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);

            log.info("");
            log.info("🎉 开仓完成！{} {} 倍杠杆，预计年化 {}%",
                    symbol, Config.LEVERAGE, annualized);
            log.info("✅ 两道防线全部通过 ✓");
            log.info("");

        } catch (Exception e) {
            log.error("❌ 开仓失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 平仓：同样双防线加持
     */
    private void closePosition(String symbol) throws IOException {
        log.info("");
        log.info("┌──────────────────────────────────────────────────────────┐");
        log.info("│              📉 平仓操作 (双防线加持)                        │");
        log.info("└──────────────────────────────────────────────────────────┘");

        Position position = positions.get(symbol);
        if (!position.hasPosition()) {
            return;
        }

        try {
            // 1. 取消所有网格订单
            if (Config.GRID_ENABLED) {
                gridTrading.cancelAllGridOrders(symbol);
            }

            // ========== 精度对齐 ==========
            BigDecimal alignedQuantity = precision.alignQuantity(
                    symbol, position.getPositionSize());

            // ========== 原子性平仓（现货+合约） ==========
            AtomicTransactionManager.TxResult txResult = txManager.atomicClosePosition(
                    symbol, alignedQuantity);

            if (!txResult.isSuccess() && txResult.isDangerous()) {
                throw new IOException("⚠️ 原子性平仓异常: " + txResult.message);
            }

            // 2. 打印收益
            log.info("💵 持仓期间资金费收益: {} USDT", 
                    position.getTotalFundingEarned().setScale(2, RoundingMode.HALF_UP));
            if (Config.GRID_ENABLED) {
                log.info("💵 网格交易额外收益: {} USDT",
                        position.getGridProfit().setScale(2, RoundingMode.HALF_UP));
            }

            // 3. 更新持仓状态
            position.close();

            log.info("");
            log.info("✅ 平仓完成！");
            log.info("");

        } catch (Exception e) {
            log.error("❌ 平仓失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 维护所有持仓的网格订单
     */
    private void maintainGrids() {
        if (!Config.GRID_ENABLED) {
            return;
        }

        for (Position position : positions.values()) {
            if (position.hasPosition()) {
                try {
                    BigDecimal currentPrice = futuresClient.getCurrentPrice(position.getSymbol());
                    gridTrading.maintainGridOrders(position, currentPrice);
                } catch (IOException e) {
                    log.warn("维护 {} 网格失败: {}", position.getSymbol(), e.getMessage());
                }
            }
        }
    }

    /**
     * 风控检查
     */
    private boolean checkRiskControl() {
        try {
            updateBalance();

            // 1. 检查账户余额
            BigDecimal balance = futuresClient.getBalance();
            if (balance.compareTo(Config.MIN_BALANCE) < 0) {
                log.error("❌ 账户余额不足: {} USDT < {} USDT",
                        balance.setScale(2, RoundingMode.HALF_UP), Config.MIN_BALANCE);
                return false;
            }

            // 2. 检查最大回撤
            if (!BigDecimal.ZERO.equals(maxBalance)) {
                BigDecimal drawdown = maxBalance.subtract(balance).divide(maxBalance, 6, RoundingMode.HALF_UP);
                if (drawdown.compareTo(Config.MAX_DRAWDOWN_PERCENT) > 0) {
                    log.error("❌ 最大回撤超过阈值: {}%，紧急平仓所有仓位",
                            drawdown.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
                    emergencyCloseAll();
                    return false;
                }
            }

            // 3. 检查单日亏损
            if (dailyPnl.compareTo(Config.MAX_DAILY_LOSS.negate()) < 0) {
                log.error("❌ 单日亏损超过阈值: {} USDT，停止交易", dailyPnl);
                return false;
            }

            return true;

        } catch (Exception e) {
            log.error("❌ 风控检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 紧急平仓所有仓位
     */
    private void emergencyCloseAll() {
        log.warn("⚠️ 执行紧急平仓！");
        for (Position position : positions.values()) {
            if (position.hasPosition()) {
                try {
                    closePosition(position.getSymbol());
                } catch (Exception e) {
                    log.error("紧急平仓 {} 失败: {}", position.getSymbol(), e.getMessage());
                }
            }
        }
    }

    /**
     * 打印当前状态
     */
    private void printCurrentStatus() {
        log.info("");
        log.info("┌───────────────────────────────────────────────────────┐");
        log.info("│                   当前市场状态                           │");
        log.info("├───────────────────────────────────────────────────────┤");

        // 打印各币种费率（前5个）
        List<Map.Entry<String, BigDecimal>> sortedRates = new ArrayList<>(fundingRates.entrySet());
        sortedRates.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        log.info("│ 资金费率排行 (前5名):                                    │");
        for (int i = 0; i < Math.min(5, sortedRates.size()); i++) {
            Map.Entry<String, BigDecimal> entry = sortedRates.get(i);
            String symbol = entry.getKey();
            BigDecimal rate = entry.getValue();
            BigDecimal ratePercent = rate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP);
            Position pos = positions.get(symbol);
            String holdingFlag = pos != null && pos.hasPosition() ? "✓ " : "  ";
            log.info("│   {} {}: {}%", holdingFlag, symbol, ratePercent);
        }

        log.info("│");
        log.info("│ 当前持仓:");
        int holdCount = 0;
        for (Position position : positions.values()) {
            if (position.hasPosition()) {
                holdCount++;
                BigDecimal annualized = position.getLastFundingRate()
                        .multiply(new BigDecimal("1095"))
                        .multiply(new BigDecimal(Config.LEVERAGE))
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
                log.info("│   ✓ {}: 数量 {}，年化 {}%",
                        position.getSymbol(), position.getPositionSize(), annualized);
            }
        }
        if (holdCount == 0) {
            log.info("│   暂无持仓");
        }
        log.info("│   持仓总数: {} / {}", holdCount, Config.MAX_POSITIONS);

        log.info("└───────────────────────────────────────────────────────┘");
        log.info("");
    }

    /**
     * 休眠等待
     */
    private void sleep() {
        try {
            log.info("💤 等待 {} 分钟后下次检查...",
                    Config.CHECK_INTERVAL_MS / 1000 / 60);
            log.info("");
            Thread.sleep(Config.CHECK_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("程序被中断，正在退出...");
        }
    }
}
