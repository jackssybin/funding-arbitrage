package com.quant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 多币种资金费率套利机器人 - 纯合约版【全量修复版】
 * 
 * ============================================
 * P0 核心修复：砍掉现货，纯合约套利
 * - 费率 > 0：开空合约，赚资金费
 * - 费率 < 0：开多合约，赚资金费（负费率时空付多）
 * ============================================
 * P1 费率阈值优化：考虑手续费成本
 * - 开仓阈值: >= 0.015%
 * - 平仓阈值: < 0.008%
 * - 移仓阈值: 差值 > 0.05% + 手续费
 * ============================================
 * P2 资金费结算逻辑：记录每次结算收益
 * ============================================
 * P3 网格优化：保留但不默认启用（用户可选择）
 * ============================================
 * P4 移仓冷却：持仓 8 小时内不轻易移仓
 * ============================================
 * P5 多交易所支持：Binance / OKX 统一接口
 * ============================================
 */
public class FundingArbitrageBot {

    private static final Logger log = LoggerFactory.getLogger(FundingArbitrageBot.class);
    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ========== 费率阈值（P1 修复：考虑实际手续费成本）==========
    // 【实盘修正】：
    // - Binance 合约市价 taker 手续费: 0.04%
    // - 3倍杠杆后: 0.04% × 3 = 0.12% （单次开仓）
    // - 开平两次: 0.12% × 2 = 0.24% （总成本）
    // - 每次结算 8 小时，需要至少 3 次结算（24小时）才能覆盖手续费
    // - 结论：开仓阈值必须远高于手续费成本！
    private static final BigDecimal MIN_FUNDING_RATE = new BigDecimal("0.0005");   // 0.05% 开仓阈值（原来的3.3倍）
    private static final BigDecimal CLOSE_FUNDING_RATE = new BigDecimal("0.0002");  // 0.02% 平仓阈值
    private static final BigDecimal SWITCH_THRESHOLD = new BigDecimal("0.001");      // 0.1% 移仓阈值（原来的2倍）
    private static final BigDecimal FEE_PER_TRADE = new BigDecimal("0.0012");        // 开平完整手续费 0.24%（原来的2倍）

    // 持仓最短时间（小时）：至少持仓到下一个结算时间点
    private static final int MIN_HOLDING_HOURS = 6;

    // 止损比例：亏 5% 强制平仓
    private static final BigDecimal STOP_LOSS_RATIO = new BigDecimal("0.05");

    // 止盈比例：赚 10% 止盈（资金费率不会涨这么多，主要是极端行情）
    private static final BigDecimal TAKE_PROFIT_RATIO = new BigDecimal("0.1");

    // 费率有效性范围：正常资金费率在 [-1%, +1%]，超过这个范围的都是异常数据
    private static final BigDecimal MAX_VALID_RATE = new BigDecimal("0.01");  // 1%
    private static final BigDecimal MIN_VALID_RATE = new BigDecimal("-0.01"); // -1%

    // ========== 结算时间（币安 UTC 0, 8, 16 点 = 北京时间 8, 16, 24 点）
    private static final List<Integer> FUNDING_HOURS = Arrays.asList(0, 8, 16);  // UTC 时间

    // ========== 核心组件 ==========
    private ExchangeClient exchangeClient;
    private GridTrading gridTrading;
    private ExchangePrecision precision;
    private AtomicTransactionManager txManager;
    private SmartOrderExecutor smartOrderExecutor;
    private MarginGuardian marginGuardian;

    // ========== 状态变量 ==========
    private final Map<String, Position> positions = new HashMap<>();
    private final Map<String, BigDecimal> fundingRates = new HashMap<>();
    private volatile BigDecimal totalPnl = BigDecimal.ZERO;
    private volatile int totalTrades = 0;
    private volatile LocalDateTime lastRateUpdate = null;

    public static void main(String[] args) {
        FundingArbitrageBot bot = new FundingArbitrageBot();

        log.info("");
        log.info("╔════════════════════════════════════════════════════════════════╗");
        log.info("║           多币种资金费率套利机器人 v2.2 [纯合约版]                  ║");
        log.info("║  ✅ P0 核心修复：纯合约套利                                        ║");
        log.info("║  ✅ P1 费率阈值优化：考虑手续费                                     ║");
        log.info("║  ✅ P2 资金费结算逻辑                                                ║");
        log.info("║  ✅ P4 移仓冷却：持仓8小时内不轻易移仓                               ║");
        log.info("║  ✅ P5 多交易所支持：Binance / OKX                                    ║");
        log.info("╚════════════════════════════════════════════════════════════════╝");
        log.info("");

        if (!Config.validate()) {
            System.exit(1);
        }

        if (bot.init()) {
            bot.run();
        } else {
            log.error("❌ 初始化失败，程序退出");
            System.exit(1);
        }
    }

    public boolean init() {
        try {
            log.info("=== 开始初始化 ===");

            exchangeClient = Config.createExchangeClient();
            if (!exchangeClient.testConnection()) {
                throw new RuntimeException(exchangeClient.getExchangeName() + " API 连接失败");
            }
            log.info("✅ {} API 连接成功", exchangeClient.getExchangeName());

            if (exchangeClient instanceof BinanceFuturesClient) {
                precision = new ExchangePrecision((BinanceFuturesClient) exchangeClient);
            } else {
                precision = new ExchangePrecision(null);
            }
            if (Config.SIMULATION_MODE) {
                precision.loadMockFilters();
            } else {
                precision.loadAllSymbolFilters();
            }
            log.info("✅ 精度对齐工具初始化");

            smartOrderExecutor = new SmartOrderExecutor(exchangeClient, precision);
            txManager = new AtomicTransactionManager(exchangeClient, smartOrderExecutor);
            log.info("✅ 事务管理器初始化");

            if (exchangeClient instanceof BinanceFuturesClient) {
                marginGuardian = new MarginGuardian((BinanceFuturesClient) exchangeClient);
            } else {
                marginGuardian = new MarginGuardian(null);
            }
            marginGuardian.start();
            log.info("✅ 保证金守护线程已启动");

            if (exchangeClient instanceof BinanceFuturesClient) {
                gridTrading = new GridTrading((BinanceFuturesClient) exchangeClient);
            } else {
                gridTrading = new GridTrading(null);
            }
            log.info("✅ 网格交易组件初始化");

            for (String symbol : Config.TRADING_SYMBOLS) {
                positions.put(symbol, new Position(symbol));
            }
            log.info("✅ 监控 {} 个币种", Config.TRADING_SYMBOLS.size());

            updateFundingRates();

            printConfig();

            log.info("✅ 初始化完成!");
            return true;

        } catch (Exception e) {
            log.error("❌ 初始化失败: {}", e.getMessage(), e);
            return false;
        }
    }

    private void printConfig() {
        log.info("");
        log.info("┌───────────────────────────────────────────────────────┐");
        log.info("│                   当前配置                                │");
        log.info("├───────────────────────────────────────────────────────┤");
        log.info("│ 交易所:         {}", exchangeClient.getExchangeName());
        log.info("│ 监控币种:       {}", Config.TRADING_SYMBOLS);
        log.info("│ 最大持仓数:     {} 个币种", Config.MAX_POSITIONS);
        log.info("│ 杠杆倍数:       {}x", Config.LEVERAGE);
        log.info("│ 单仓位价值:     {} USDT", Config.POSITION_VALUE_USDT);
        log.info("│ 开仓阈值:       {}%", MIN_FUNDING_RATE.multiply(new BigDecimal("100")).setScale(3));
        log.info("│ 平仓阈值:       {}%", CLOSE_FUNDING_RATE.multiply(new BigDecimal("100")).setScale(3));
        log.info("│ 移仓阈值:       {}% + 手续费", SWITCH_THRESHOLD.multiply(new BigDecimal("100")).setScale(3));
        log.info("│ 网格交易:       {}", Config.GRID_ENABLED ? "启用 ✓" : "禁用");
        log.info("│ 模拟模式:       {}", Config.SIMULATION_MODE ? "开启 ✅" : "关闭 ❌");
        log.info("└───────────────────────────────────────────────────────┘");
        log.info("");
    }

    public void run() {
        log.info("🚀 机器人启动，开始监控多币种资金费率...");
        log.info("💡 纯合约套利，每8小时结算一次资金费");
        log.info("");

        while (true) {
            try {
                LocalDateTime now = LocalDateTime.now();
                log.info("═══════════════════════════════════════════════════════");
                log.info("⏰ 检查时间: {}", now.format(dtf));

                checkAndSettleFunding(now);
                updateFundingRatesIfNeeded();
                checkRiskControl();
                printCurrentStatus();
                executeStrategy();
                maintainGrids();
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

    // ==================== P2 修复：资金费结算逻辑 ====================
    private void checkAndSettleFunding(LocalDateTime now) {
        int hour = now.getHour();
        int minute = now.getMinute();
        if (FUNDING_HOURS.contains(hour) && minute >= 0 && minute < 15) {
            log.info("💰 资金费结算时间窗口！检查持仓...");
            settleFundingFee();
        }
    }

    private void settleFundingFee() {
        for (Position position : positions.values()) {
            if (!position.hasPosition()) continue;

            BigDecimal notionalValue = position.getPositionSize().multiply(position.getEntryPrice());
            BigDecimal earning = notionalValue.multiply(position.getLastFundingRate().abs()).setScale(6, RoundingMode.HALF_UP);

            position.recordFundingSettlement(earning);
            totalPnl = totalPnl.add(earning);

            log.info("💰 {} 结算资金费: {} USDT (累计: {} USDT, 第 {} 次)",
                    position.getSymbol(),
                    earning.setScale(4),
                    position.getTotalFundingEarned().setScale(4),
                    position.getFundingCount());
        }
    }

    private void updateFundingRates() {
        try {
            Map<String, BigDecimal> rates = exchangeClient.getAllFundingRates(Config.TRADING_SYMBOLS);
            this.fundingRates.putAll(rates);
            this.lastRateUpdate = LocalDateTime.now();
            log.info("✅ 已更新 {} 个币种的资金费率", rates.size());
        } catch (IOException e) {
            log.error("更新资金费率失败: {}", e.getMessage());
        }
    }

    private void updateFundingRatesIfNeeded() {
        if (lastRateUpdate == null ||
                lastRateUpdate.plusSeconds(Config.RATE_UPDATE_INTERVAL_MS / 1000).isBefore(LocalDateTime.now())) {
            updateFundingRates();
        }
    }

    private void checkRiskControl() {
        updateBalance();
        marginGuardian.checkAndTopupIfNeeded();
    }

    private void updateBalance() {
    }

    private void printCurrentStatus() {
        log.info("");
        log.info("┌───────────────────────────────────────────────────────┐");
        log.info("│              当前市场状态                               │");
        log.info("├───────────────────────────────────────────────────────┤");

        List<Map.Entry<String, BigDecimal>> sortedRates = new ArrayList<>(fundingRates.entrySet());
        sortedRates.sort((a, b) -> b.getValue().abs().compareTo(a.getValue().abs()));

        log.info("│ 资金费率排行 (前5名):");
        for (int i = 0; i < Math.min(5, sortedRates.size()); i++) {
            Map.Entry<String, BigDecimal> entry = sortedRates.get(i);
            String symbol = entry.getKey();
            BigDecimal rate = entry.getValue();
            Position pos = positions.get(symbol);
            String holdingFlag = (pos != null && pos.hasPosition()) ? "✓" : "";
            String ratePercent = rate.abs().multiply(new BigDecimal("100")).setScale(4).toString() + "%";
            String rateDir = rate.compareTo(BigDecimal.ZERO) >= 0 ? "正(开空)" : "负(开多)";
            log.info("│   {} {}: {} ({})", holdingFlag, symbol, ratePercent, rateDir);
        }

        log.info("│");
        log.info("│ 当前持仓:");
        int holdCount = 0;
        BigDecimal totalEarning = BigDecimal.ZERO;
        for (Position position : positions.values()) {
            if (position.hasPosition()) {
                holdCount++;
                totalEarning = totalEarning.add(position.getTotalFundingEarned());
                BigDecimal annualized = position.getLastFundingRate().abs()
                        .multiply(new BigDecimal("1095"))
                        .multiply(new BigDecimal(Config.LEVERAGE))
                        .multiply(new BigDecimal("100"));
                
                // P2 修复：显示浮盈浮亏
                String pnlStr = "";
                if (position.getUnrealizedPnlRatio() != null) {
                    BigDecimal pnlPercent = position.getUnrealizedPnlRatio().multiply(new BigDecimal("100"));
                    String pnlSign = pnlPercent.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
                    pnlStr = String.format(", 浮盈浮亏: %s%s%%", pnlSign, pnlPercent.setScale(2));
                }
                
                log.info("│   ✓ {}: {} {} (年化 {}%, 第 {} 次, 持仓 {}h, 已赚 {} USDT{})",
                        position.getSymbol(),
                        position.getPositionSide(),
                        position.getPositionSize().setScale(4),
                        annualized.setScale(2),
                        position.getFundingCount(),
                        position.getHoldingHours(),
                        position.getTotalFundingEarned().setScale(4),
                        pnlStr);
            }
        }
        if (holdCount == 0) {
            log.info("│   暂无持仓");
        }
        log.info("│   持仓总数: {} / {}", holdCount, Config.MAX_POSITIONS);
        log.info("│   累计收益: {} USDT", totalPnl.setScale(4));
        log.info("└───────────────────────────────────────────────────────┘");
        log.info("");
    }

    // ==================== P0 + P4 核心策略逻辑 ====================
    private void executeStrategy() {
        List<Map.Entry<String, BigDecimal>> sortedRates = new ArrayList<>(fundingRates.entrySet());
        sortedRates.sort((a, b) -> b.getValue().abs().compareTo(a.getValue().abs()));

        int currentPositions = (int) positions.values().stream().filter(Position::hasPosition).count();

        for (Position position : positions.values()) {
            if (!position.hasPosition()) continue;

            String symbol = position.getSymbol();
            BigDecimal currentRate = fundingRates.getOrDefault(symbol, BigDecimal.ZERO);

            // P2 修复：实时更新浮盈浮亏
            try {
                BigDecimal currentPrice = exchangeClient.getCurrentPrice(symbol);
                position.updateUnrealizedPnl(currentPrice);
            } catch (Exception e) {
                log.warn("更新 {} 盈亏失败: {}", symbol, e.getMessage());
            }

            // 检查费率是否低于平仓阈值
            if (currentRate.abs().compareTo(CLOSE_FUNDING_RATE) < 0) {
                log.info("📉 {} 费率 {}% 低于平仓阈值，准备平仓...",
                        symbol,
                        currentRate.abs().multiply(new BigDecimal("100")).setScale(4));
                closePosition(symbol);
                currentPositions--;
                continue;
            }

            // P2 修复：止损检查 - 强制平仓防止极端行情
            if (position.isStopLossTriggered(STOP_LOSS_RATIO)) {
                log.error("🚨 {} 触发止损！盈亏 {}%，强制平仓",
                        symbol,
                        position.getUnrealizedPnlRatio().multiply(new BigDecimal("100")).setScale(2));
                closePosition(symbol);
                currentPositions--;
                continue;
            }

            // P2 修复：止盈检查 - 大行情主动止盈离场
            if (position.isTakeProfitTriggered(TAKE_PROFIT_RATIO)) {
                log.info("🎯 {} 触发止盈！盈亏 {}%，主动平仓",
                        symbol,
                        position.getUnrealizedPnlRatio().multiply(new BigDecimal("100")).setScale(2));
                closePosition(symbol);
                currentPositions--;
            }
        }

        for (Map.Entry<String, BigDecimal> entry : sortedRates) {
            String symbol = entry.getKey();
            BigDecimal rate = entry.getValue();
            Position position = positions.get(symbol);

            // P2 修复：跳过无效的异常费率数据
            if (rate.compareTo(MIN_VALID_RATE) < 0 || rate.compareTo(MAX_VALID_RATE) > 0) {
                log.warn("⚠️ {} 费率 {}% 异常，跳过", symbol,
                        rate.multiply(new BigDecimal("100")).setScale(4));
                continue;
            }

            if (rate.abs().compareTo(MIN_FUNDING_RATE) < 0) {
                break;
            }

            if (!position.hasPosition() && currentPositions < Config.MAX_POSITIONS) {
                log.info("🎯 {} 费率 {}% 达标，准备开仓...",
                        symbol, rate.abs().multiply(new BigDecimal("100")).setScale(4));
                openPosition(symbol, rate);
                currentPositions++;
            } else if (!position.hasPosition() && currentPositions >= Config.MAX_POSITIONS) {
                Position toClose = positions.values().stream()
                        .filter(Position::hasPosition)
                        .min(Comparator.comparing(p -> p.getLastFundingRate().abs()))
                        .orElse(null);

                if (toClose != null) {
                    BigDecimal diff = rate.abs().subtract(toClose.getLastFundingRate().abs());

                    if (toClose.isSwitchWorthIt(rate.abs(), SWITCH_THRESHOLD, FEE_PER_TRADE)) {
                        log.info("🔄 移仓：从 {} ({}%) 到 {} ({}%), 差值 {}% + 手续费划算",
                                toClose.getSymbol(),
                                toClose.getLastFundingRate().abs().multiply(new BigDecimal("100")).setScale(4),
                                symbol,
                                rate.abs().multiply(new BigDecimal("100")).setScale(4),
                                diff.multiply(new BigDecimal("100")).setScale(4));
                        closePosition(toClose.getSymbol());
                        openPosition(symbol, rate);
                    } else {
                        log.debug("⏸️  {} 移仓不划算（持仓时间太短或费率差不够），跳过", symbol);
                    }
                }
            }
        }
    }

    // ==================== P0 修复：纯合约开仓 ====================
    private void openPosition(String symbol, BigDecimal fundingRate) {
        log.info("");
        log.info("┌──────────────────────────────────────────────────────────┐");
        log.info("│              🚀 开仓操作（纯合约）                          │");
        log.info("└──────────────────────────────────────────────────────────┘");

        try {
            exchangeClient.setLeverage(symbol, Config.LEVERAGE);

            BigDecimal currentPrice = exchangeClient.getCurrentPrice(symbol);
            BigDecimal quantity = Config.POSITION_VALUE_USDT
                    .multiply(BigDecimal.valueOf(Config.LEVERAGE))
                    .divide(currentPrice, 12, RoundingMode.DOWN);

            log.info("当前价格: {} USDT", currentPrice);
            log.info("计算原始数量: {}", quantity);

            BigDecimal alignedQuantity = precision.alignQuantity(symbol, quantity);
            if (BigDecimal.ZERO.compareTo(alignedQuantity) >= 0) {
                log.error("❌ 精度对齐后数量为0，无法开仓");
                return;
            }
            log.info("✅ 精度对齐后: {} (原始: {})", alignedQuantity, quantity);

            String side = fundingRate.compareTo(BigDecimal.ZERO) >= 0 ? "SHORT" : "LONG";
            String sideName = fundingRate.compareTo(BigDecimal.ZERO) >= 0 ? "做空" : "做多";
            log.info("资金费率方向: {} ({}), 合约方向: {}", fundingRate,
                    fundingRate.compareTo(BigDecimal.ZERO) >= 0 ? "正（多头付空头）" : "负（空头付多头）", sideName);

            AtomicTransactionManager.TxResult result = txManager.atomicOpenPosition(symbol, alignedQuantity, fundingRate);
            if (!result.isSuccess()) {
                throw new IOException("开仓失败: " + result.message);
            }

            Position position = positions.get(symbol);
            position.open(alignedQuantity, currentPrice, fundingRate, side);
            totalTrades++;

            if (Config.GRID_ENABLED) {
                gridTrading.setupGrid(position, currentPrice);
            }

            BigDecimal annualized = fundingRate.abs()
                    .multiply(new BigDecimal("1095"))
                    .multiply(new BigDecimal(Config.LEVERAGE))
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);

            log.info("");
            log.info("🎉 开仓完成！ {} {} ({}), 预计年化 {}%", symbol, sideName, alignedQuantity, annualized);
            log.info("");

        } catch (Exception e) {
            log.error("❌ 开仓失败: {}", e.getMessage(), e);
        }
    }

    private void closePosition(String symbol) {
        log.info("");
        log.info("┌──────────────────────────────────────────────────────────┐");
        log.info("│              📉 平仓操作（纯合约）                          │");
        log.info("└──────────────────────────────────────────────────────────┘");

        Position position = positions.get(symbol);
        if (!position.hasPosition()) return;

        try {
            if (Config.GRID_ENABLED) {
                gridTrading.cancelAllGridOrders(symbol);
            }

            BigDecimal alignedQuantity = precision.alignQuantity(symbol, position.getPositionSize());

            String side = position.getPositionSide();
            String closeSide = "LONG".equals(side) ? "SELL" : "BUY";
            AtomicTransactionManager.TxResult result = txManager.atomicClosePosition(symbol, alignedQuantity, closeSide);
            if (!result.isSuccess() && result.isDangerous()) {
                throw new IOException("⚠️ 平仓异常: " + result.message);
            }

            log.info("💵 持仓期间资金费收益: {} USDT (持仓 {} 小时, {} 次结算)",
                    position.getTotalFundingEarned().setScale(4),
                    position.getHoldingHours(),
                    position.getFundingCount());

            position.close();
            totalTrades++;

            log.info("");
            log.info("✅ 平仓完成！");
            log.info("");

        } catch (Exception e) {
            log.error("❌ 平仓失败: {}", e.getMessage(), e);
        }
    }

    private void maintainGrids() {
        if (!Config.GRID_ENABLED) return;

        for (Position position : positions.values()) {
            if (position.hasPosition()) {
                try {
                    BigDecimal currentPrice = exchangeClient.getCurrentPrice(position.getSymbol());
                    gridTrading.maintainGridOrders(position, currentPrice);
                } catch (Exception e) {
                    log.warn("维护 {} 网格订单失败: {}", position.getSymbol(), e.getMessage());
                }
            }
        }
    }

    private void sleep() {
        try {
            log.info("💤 等待 {} 分钟后下次检查...", Config.CHECK_INTERVAL_MS / 1000 / 60);
            log.info("");
            Thread.sleep(Config.CHECK_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("程序被中断，正在退出...");
        }
    }
}
