package com.quant;
import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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

    private volatile int lastSettledHourUtc = -1;
    private volatile long lastFundingIncomeQueryTime = 0L;
    private final Map<String, Long> lastFundingIncomeQueryTimes = new HashMap<>();

    // ========== 核心组件 ==========
    private ExchangeClient exchangeClient;
    private ExchangePrecision precision;
    private AtomicTransactionManager txManager;
    private SmartOrderExecutor smartOrderExecutor;
    private MarginGuardian marginGuardian;
    private MarketDataService marketDataService;
    private RiskManager riskManager;
    private StrategyControl strategyControl;      // 策略控制器
    private StrategyPersistence persistence;       // 持久化
    private StrategyDashboard dashboard;           // Web仪表盘
    private RetryManager retryManager;             // 重试管理器
    private DailyReporter dailyReporter;           // 自动日报生成器
    private FeishuNotifier feishuNotifier;         // 飞书推送

    // ========== 状态变量 ==========
    private final Map<String, Position> positions = new HashMap<>();
    private final Map<String, BigDecimal> fundingRates = new HashMap<>();
    
    // ========== 风控熔断机制 ==========
    private volatile int consecutiveLosses = 0;  // 连续亏损次数
    private volatile LocalDateTime pauseUntilTime = null;  // 暂停开仓直到该时间
    private volatile BigDecimal dailyPnl = BigDecimal.ZERO;  // 当日盈亏
    private volatile LocalDateTime dailyPnlDate = LocalDateTime.now();
    private volatile BigDecimal totalPnl = BigDecimal.ZERO;
    private volatile int totalTrades = 0;
    private volatile BigDecimal initialBalance = null;
    private volatile BigDecimal maxBalance     = BigDecimal.ZERO;
    private volatile boolean dashboardEnabled = true; // 是否启用Web仪表盘

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

            // 1. 初始化策略控制器
            strategyControl = new StrategyControl();
            strategyControl.setBot(this);
            log.info("✅ 策略控制器初始化");

            // 2. 初始化持久化
            persistence = new StrategyPersistence();
            log.info("✅ 持久化模块初始化");

            // 3. 初始化飞书推送
            feishuNotifier = new FeishuNotifier();
            log.info("✅ 飞书推送模块初始化");

            // 4. 初始化日报生成器（传入飞书推送用于日报通知）
            dailyReporter = new DailyReporter(feishuNotifier);
            log.info("✅ 自动日报生成器初始化");

            // 5. 初始化重试管理器
            retryManager = new RetryManager();
            retryManager.start();
            log.info("✅ 重试管理器初始化");

            // 4. 初始化交易所客户端
            exchangeClient = Config.createExchangeClient();
            if (!exchangeClient.testConnection()) {
                throw new RuntimeException(exchangeClient.getExchangeName() + " API 连接失败");
            }
            log.info("✅ {} API 连接成功", exchangeClient.getExchangeName());

            // 5. 精度对齐
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

            // 6. 事务与订单执行器
            smartOrderExecutor = new SmartOrderExecutor(exchangeClient, precision, retryManager);
            txManager = new AtomicTransactionManager(exchangeClient, smartOrderExecutor);
            marketDataService = new MarketDataService(exchangeClient);
            riskManager = new RiskManager();
            log.info("✅ 事务管理器初始化");

            // 7. OKX 也支持保证金守护了
            if (exchangeClient instanceof BinanceFuturesClient) {
                marginGuardian = MarginGuardian.forBinance((BinanceFuturesClient) exchangeClient);
            } else if (exchangeClient instanceof OkxClient) {
                marginGuardian = MarginGuardian.forOkx((OkxClient) exchangeClient);
            } else {
                marginGuardian = MarginGuardian.forBinance(null);
            }
            marginGuardian.start();
            log.info("✅ 保证金守护线程已启动");

            // 8. 网格交易
            // 网格交易已移除，保持策略单一纯粹

            // 9. 初始化持仓对象
            for (String symbol : Config.TRADING_SYMBOLS) {
                positions.put(symbol, new Position(symbol));
            }
            log.info("✅ 监控 {} 个币种", Config.TRADING_SYMBOLS.size());

            // 10. 恢复上次状态
            StrategyPersistence.StrategyState savedState = persistence.restoreState();
            if (savedState != null) {
                totalPnl = BigDecimal.valueOf(savedState.totalPnl);
                totalTrades = savedState.totalTrades;
                lastFundingIncomeQueryTime = savedState.lastFundingIncomeQueryTime;
                restoreFundingIncomeQueryTimes(savedState);
                restorePositions(savedState);
                log.info("✅ 已恢复历史收益: {} USDT, 交易次数: {}", savedState.totalPnl, savedState.totalTrades);
            }

            // 11. 启动异常恢复检查
            if (!Config.SIMULATION_MODE) {
                retryManager.recoverOnStartup(exchangeClient, positions);
            }

            // 12. 初始化数据
            updateFundingRates();
            update24hPrices();

            // 13. 启动Web仪表盘
            if (dashboardEnabled) {
                dashboard = new StrategyDashboard(Config.DASHBOARD_PORT, this, strategyControl, persistence);
                dashboard.start();
            }

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
        log.info("│ 正费率开仓:     {}%", Config.MIN_FUNDING_RATE_POSITIVE.multiply(new BigDecimal("100")).setScale(3, RoundingMode.HALF_UP));
        log.info("│ 负费率开仓:     {}%", Config.MIN_FUNDING_RATE_NEGATIVE.multiply(new BigDecimal("100")).setScale(3, RoundingMode.HALF_UP));
        log.info("│ 平仓阈值:       {}%", Config.CLOSE_FUNDING_RATE.multiply(new BigDecimal("100")).setScale(3, RoundingMode.HALF_UP));
        log.info("│ 止损比例:       {}%", Config.STOP_LOSS_RATIO.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
        log.info("│ 止盈比例:       {}%", Config.TAKE_PROFIT_RATIO.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
        log.info("│ 网格交易:       {}", Config.GRID_ENABLED ? "启用 ✓" : "禁用");
        log.info("│ 模拟模式:       {}", Config.SIMULATION_MODE ? "开启 ✅" : "关闭 ❌");
        if (dashboardEnabled) {
        log.info("│ 仪表盘地址:     http://localhost:{}", Config.DASHBOARD_PORT);
        }
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

                // 检查是否需要生成新的日报（新的一天）
                dailyReporter.checkAndGenerateReport();

                checkAndSettleFunding(now);
                updateFundingRatesIfNeeded();
                update24hPrices(); // 更新24h涨跌幅用于风险过滤
                checkRiskControl();
                printCurrentStatus();
                executeStrategy();
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

    // ==================== P2 修复：资金费结算逻辑（Bug-1 防重复 + Bug-5 UTC）====================
    private void checkAndSettleFunding(LocalDateTime ignoredLocalNow) {
        // Bug-5修复: 使用 UTC 时间判断结算窗口
        ZonedDateTime utcNow = ZonedDateTime.now(ZoneOffset.UTC);
        int utcHour   = utcNow.getHour();
        int utcMinute = utcNow.getMinute();

        // Bug-1修复: 只在结算窗口的前 15 分钟内执行，且每个窗口只结算一次
        if (Config.FUNDING_HOURS_UTC.contains(utcHour) && utcMinute < 15) {
            if (lastSettledHourUtc != utcHour) {
                log.info("💰 检测到资金费结算窗口 UTC {}:00，开始结算...", utcHour);
                settleFundingFee();
                lastSettledHourUtc = utcHour;  // 标记已结算，本窗口不再重复
            } else {
                log.debug("⏸️  UTC {}:00 本轮结算已完成，跳过重复结算", utcHour);
            }
        }
    }

    private void settleFundingFee() {
        long now = System.currentTimeMillis();

        for (Position position : positions.values()) {
            if (!position.hasPosition()) continue;

            BigDecimal currentRate = fundingRates.getOrDefault(position.getSymbol(), position.getLastFundingRate());
            FundingIncomeResult incomeResult = getActualFundingIncome(position, currentRate, now);
            BigDecimal earning = incomeResult.income;
            if (incomeResult.maxRecordTime > getLastFundingIncomeQueryTime(position.getSymbol())) {
                lastFundingIncomeQueryTimes.put(position.getSymbol(), incomeResult.maxRecordTime);
            }
            if (earning.compareTo(BigDecimal.ZERO) == 0) {
                log.info("⏸️  {} 本窗口未查询到新的真实资金费入账，跳过记账", position.getSymbol());
                continue;
            }

            position.recordFundingSettlement(earning);
            position.setLastFundingRate(currentRate);
            totalPnl = totalPnl.add(earning);

            // ✅ 资金费结算时更新模拟账户余额
            exchangeClient.updateSimulatedBalance(earning);

            // 持久化记录
            persistence.recordFunding(position.getSymbol(), earning, currentRate);
            // 日报记录
            dailyReporter.recordFundingSettlement(position.getSymbol(), earning, currentRate);

            log.info("💰 {} 资金费结算: {} USDT | 实时费率: {}% | 累计: {} USDT | 第{}次",
                    position.getSymbol(),
                    earning.setScale(4, RoundingMode.HALF_UP),
                    currentRate.abs().multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                    position.getTotalFundingEarned().setScale(4, RoundingMode.HALF_UP),
                    position.getFundingCount());
            // 飞书推送：资金费结算通知
            try {
                BigDecimal balance = exchangeClient.getBalance();
                BigDecimal positionValue = position.getEntryPrice().multiply(position.getPositionSize());
                BigDecimal expectedEarning = positionValue.multiply(currentRate.abs());
                feishuNotifier.sendFundingSettlement(position.getSymbol(), earning, currentRate, position.getFundingCount(), balance, expectedEarning);
            } catch (IOException e) {
                log.warn("⚠️  获取余额失败，飞书通知将不带余额信息: {}", e.getMessage());
                BigDecimal positionValue = position.getEntryPrice().multiply(position.getPositionSize());
                BigDecimal expectedEarning = positionValue.multiply(currentRate.abs());
                feishuNotifier.sendFundingSettlement(position.getSymbol(), earning, currentRate, position.getFundingCount(), BigDecimal.ZERO, expectedEarning);
            }
        }
        lastFundingIncomeQueryTime = lastFundingIncomeQueryTimes.values().stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(lastFundingIncomeQueryTime);
        // 结算后保存状态
        persistence.saveState(positions, totalPnl, totalTrades,
                lastFundingIncomeQueryTime, new HashMap<>(lastFundingIncomeQueryTimes));
    }

    private FundingIncomeResult getActualFundingIncome(Position position, BigDecimal currentRate, long endTime) {
        if (Config.SIMULATION_MODE) {
            return new FundingIncomeResult(
                    estimateFundingIncome(position, currentRate),
                    getLastFundingIncomeQueryTime(position.getSymbol()));
        }

        long symbolLastQueryTime = getLastFundingIncomeQueryTime(position.getSymbol());
        long startTime = symbolLastQueryTime > 0
                ? symbolLastQueryTime + 1
                : endTime - Config.FUNDING_INCOME_LOOKBACK_MS;

        try {
            List<FundingIncomeRecord> records = exchangeClient.getFundingIncomeRecords(
                    position.getSymbol(), startTime, endTime);
            BigDecimal actualIncome = BigDecimal.ZERO;
            long maxRecordTime = symbolLastQueryTime;
            for (FundingIncomeRecord record : records) {
                actualIncome = actualIncome.add(record.getIncome());
                if (record.getTimeMillis() > maxRecordTime) {
                    maxRecordTime = record.getTimeMillis();
                }
            }
            return new FundingIncomeResult(actualIncome.setScale(6, RoundingMode.HALF_UP), maxRecordTime);
        } catch (Exception e) {
            log.error("❌ 查询 {} 真实资金费账单失败，本轮不做本地估算记账: {}",
                    position.getSymbol(), e.getMessage());
            return new FundingIncomeResult(BigDecimal.ZERO, symbolLastQueryTime);
        }
    }

    private long getLastFundingIncomeQueryTime(String symbol) {
        return lastFundingIncomeQueryTimes.getOrDefault(symbol, lastFundingIncomeQueryTime);
    }

    private BigDecimal estimateFundingIncome(Position position, BigDecimal currentRate) {
        BigDecimal notionalValue = position.getPositionSize().multiply(position.getEntryPrice());
        return notionalValue
                .multiply(currentRate.abs())
                .setScale(6, RoundingMode.HALF_UP);
    }

    private void restorePositions(StrategyPersistence.StrategyState savedState) {
        if (savedState.positions == null || savedState.positions.isEmpty()) {
            return;
        }

        int restored = 0;
        for (StrategyPersistence.PositionState ps : savedState.positions) {
            Position position = positions.get(ps.symbol);
            if (position == null) {
                log.warn("⚠️  持久化中存在未配置币种 {}，跳过恢复", ps.symbol);
                continue;
            }

            try {
                LocalDateTime entryTime = ps.entryTime == null || ps.entryTime.isEmpty()
                        ? LocalDateTime.now()
                        : LocalDateTime.parse(ps.entryTime, dtf);
                position.restore(
                        BigDecimal.valueOf(ps.positionSize),
                        BigDecimal.valueOf(ps.entryPrice),
                        BigDecimal.valueOf(ps.lastFundingRate),
                        ps.positionSide,
                        entryTime,
                        ps.fundingCount,
                        BigDecimal.valueOf(ps.totalFundingEarned)
                );
                restored++;
            } catch (Exception e) {
                log.error("恢复 {} 持仓状态失败: {}", ps.symbol, e.getMessage());
            }
        }
        log.info("✅ 已恢复 {} 个本地持仓状态", restored);
    }

    private void restoreFundingIncomeQueryTimes(StrategyPersistence.StrategyState savedState) {
        if (savedState.lastFundingIncomeQueryTimes != null && !savedState.lastFundingIncomeQueryTimes.isEmpty()) {
            lastFundingIncomeQueryTimes.putAll(savedState.lastFundingIncomeQueryTimes);
            return;
        }
        if (savedState.lastFundingIncomeQueryTime > 0 && savedState.positions != null) {
            for (StrategyPersistence.PositionState ps : savedState.positions) {
                lastFundingIncomeQueryTimes.put(ps.symbol, savedState.lastFundingIncomeQueryTime);
            }
        }
    }

    private static class FundingIncomeResult {
        final BigDecimal income;
        final long maxRecordTime;

        FundingIncomeResult(BigDecimal income, long maxRecordTime) {
            this.income = income;
            this.maxRecordTime = maxRecordTime;
        }
    }

    /**
     * 更新24h涨跌幅 - 用于负费率风险过滤
     */
    private void update24hPrices() {
        marketDataService.update24hChanges(Config.TRADING_SYMBOLS);
    }

    private boolean isExtremeMarket(String symbol, BigDecimal rate) {
        if (marketDataService == null) {
            marketDataService = new MarketDataService(exchangeClient);
        }
        return marketDataService.isExtremeMarket(symbol, rate);
    }

    private boolean isRateTrendGood(String symbol, BigDecimal currentRate) {
        if (marketDataService == null) {
            marketDataService = new MarketDataService(exchangeClient);
        }
        return marketDataService.isRateTrendGood(symbol, currentRate);
    }

    private boolean isLiveMarketStateAcceptable(String symbol, BigDecimal currentRate) {
        if (marketDataService == null) {
            marketDataService = new MarketDataService(exchangeClient);
        }
        return marketDataService.isMarketStateAcceptableForEntry(symbol, currentRate);
    }

    private boolean isExpectedNetFundingAcceptable(String symbol, BigDecimal fundingRate) {
        BigDecimal expectedFunding = expectedFundingForConfiguredSettlements(fundingRate);
        BigDecimal roundTripFee = estimatedRoundTripFee();
        BigDecimal roundTripSlippage = estimatedRoundTripSlippage();
        BigDecimal costDeviationBuffer = historicalCostDeviationBuffer(symbol);
        BigDecimal expectedNet = expectedNetFundingAfterCosts(fundingRate).subtract(costDeviationBuffer);

        if (expectedNet.compareTo(Config.LIVE_MIN_EXPECTED_NET_FUNDING_AFTER_COSTS) < 0) {
            log.info("{} 跳过开仓：预计资金费({}次结算) {} USDT，往返手续费 {} USDT，往返滑点 {} USDT，历史成本偏差缓冲 {} USDT，净收益 {} USDT，低于最低要求 {} USDT",
                    symbol,
                    Config.LIVE_EXPECTED_FUNDING_SETTLEMENTS,
                    expectedFunding.setScale(4, RoundingMode.HALF_UP),
                    roundTripFee.setScale(4, RoundingMode.HALF_UP),
                    roundTripSlippage.setScale(4, RoundingMode.HALF_UP),
                    costDeviationBuffer.setScale(4, RoundingMode.HALF_UP),
                    expectedNet.setScale(4, RoundingMode.HALF_UP),
                    Config.LIVE_MIN_EXPECTED_NET_FUNDING_AFTER_COSTS.setScale(4, RoundingMode.HALF_UP));
            return false;
        }
        log.debug("{} 预计净收益通过：资金费({}次结算) {} USDT - 手续费 {} USDT - 滑点 {} USDT - 历史成本偏差缓冲 {} USDT = {} USDT",
                symbol,
                Config.LIVE_EXPECTED_FUNDING_SETTLEMENTS,
                expectedFunding.setScale(4, RoundingMode.HALF_UP),
                roundTripFee.setScale(4, RoundingMode.HALF_UP),
                roundTripSlippage.setScale(4, RoundingMode.HALF_UP),
                costDeviationBuffer.setScale(4, RoundingMode.HALF_UP),
                expectedNet.setScale(4, RoundingMode.HALF_UP));
        return true;
    }

    private BigDecimal expectedFundingForConfiguredSettlements(BigDecimal fundingRate) {
        return Config.POSITION_VALUE_USDT
                .multiply(fundingRate.abs())
                .multiply(BigDecimal.valueOf(Config.LIVE_EXPECTED_FUNDING_SETTLEMENTS));
    }

    private BigDecimal estimatedRoundTripFee() {
        return Config.POSITION_VALUE_USDT
                .multiply(Config.LIVE_TAKER_FEE_RATE)
                .multiply(new BigDecimal("2"));
    }

    private BigDecimal estimatedRoundTripSlippage() {
        return Config.POSITION_VALUE_USDT
                .multiply(Config.LIVE_SLIPPAGE_RATE)
                .multiply(new BigDecimal("2"));
    }

    private BigDecimal expectedNetFundingAfterCosts(BigDecimal fundingRate) {
        return expectedFundingForConfiguredSettlements(fundingRate)
                .subtract(estimatedRoundTripFee())
                .subtract(estimatedRoundTripSlippage());
    }

    private BigDecimal historicalCostDeviationBuffer(String symbol) {
        if (!Config.LIVE_COST_DEVIATION_BUFFER_ENABLED || persistence == null) {
            return BigDecimal.ZERO;
        }
        StrategyPersistence.CostDeviationStats stats = persistence.getRecentCostDeviationStats(
                symbol,
                Config.LIVE_COST_DEVIATION_LOOKBACK_ROWS,
                Config.LIVE_COST_DEVIATION_INCLUDE_ESTIMATED);
        if (stats.sampleCount < Config.LIVE_COST_DEVIATION_MIN_SAMPLES) {
            return BigDecimal.ZERO;
        }
        return stats.averagePositiveOneWayDelta()
                .multiply(new BigDecimal("2"))
                .multiply(Config.LIVE_COST_DEVIATION_BUFFER_MULTIPLIER);
    }

    private BigDecimal calculateNetExposure() {
        if (riskManager == null) {
            riskManager = new RiskManager();
        }
        BigDecimal accountBalance = BigDecimal.ZERO;
        try {
            accountBalance = exchangeClient == null ? new BigDecimal("10000") : exchangeClient.getBalance();
        } catch (IOException e) {
            log.warn("⚠️  获取账户余额失败: {}", e.getMessage());
        }
        return riskManager.calculateNetExposure(positions, accountBalance);
    }

    private boolean isExposureAcceptable(String newSide) {
        if (riskManager == null) {
            riskManager = new RiskManager();
        }
        BigDecimal accountBalance = BigDecimal.ZERO;
        try {
            accountBalance = exchangeClient == null ? new BigDecimal("10000") : exchangeClient.getBalance();
        } catch (IOException e) {
            log.warn("⚠️  获取账户余额失败: {}", e.getMessage());
            return false;
        }
        if (accountBalance.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("⚠️  账户余额不可用或为0，跳过开仓风控检查: balance={}", accountBalance);
            return false;
        }
        return riskManager.isExposureAcceptable(positions, newSide, accountBalance);
    }
    private boolean isTradingAllowed() {
        // 0. 检查是否在暂停期内
        if (pauseUntilTime != null && LocalDateTime.now().isBefore(pauseUntilTime)) {
            long remainingMinutes = java.time.Duration.between(LocalDateTime.now(), pauseUntilTime).toMinutes();
            log.warn("⏸️  连续亏损保护中，暂停开仓，剩余 {} 分钟", remainingMinutes);
            return false;
        } else if (pauseUntilTime != null) {
            // 暂停期已过，重置
            pauseUntilTime = null;
            log.info("▶️  连续亏损保护期已结束，恢复开仓");
        }

        // 1. 连续亏损熔断
        if (consecutiveLosses >= Config.MAX_CONSECUTIVE_LOSSES) {
            log.warn("🚨 连续亏损{}次触发熔断，暂停开仓", consecutiveLosses);
            return false;
        }

        // 2. 单日亏损熔断
        if (dailyPnl.compareTo(Config.MAX_DAILY_LOSS_AMOUNT.negate()) < 0) {
            log.warn("🚨 单日亏损{}USDT触发熔断，暂停开仓", dailyPnl.setScale(2, RoundingMode.HALF_UP));
            return false;
        }

        // 3. 策略控制层面暂停
        if (strategyControl != null && strategyControl.isTradingPaused()) {
            return false;
        }

        // 4. 现货对冲模式下，检查现货资金是否充足
        if (Config.SPOT_HEDGE_ENABLED && !isSpotFundsSufficient()) {
            log.warn("💰 现货资金不足，暂停开仓（需要{} USDT）", Config.POSITION_VALUE_USDT);
            return false;
        }

        return true;
    }

    /**
     * 检查现货资金是否充足
     */
    private boolean isSpotFundsSufficient() {
        try {
            BigDecimal spotBalance = exchangeClient.getSpotBalance();
            BigDecimal requiredSpotFunds = Config.POSITION_VALUE_USDT.multiply(Config.SPOT_HEDGE_RATIO);
            // 预留20%缓冲
            BigDecimal buffer = requiredSpotFunds.multiply(new BigDecimal("1.2"));
            boolean sufficient = spotBalance.compareTo(buffer) >= 0;
            if (!sufficient) {
                log.warn("现货资金检查: 余额 {} USDT, 需要 {} USDT (含缓冲)", 
                        spotBalance.setScale(2, RoundingMode.HALF_UP), 
                        buffer.setScale(2, RoundingMode.HALF_UP));
            }
            return sufficient;
        } catch (Exception e) {
            log.warn("⚠️  获取现货余额失败，假定资金充足: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 记录一次平仓盈亏，更新熔断状态和总收益
     */
    private void recordClosePnl(BigDecimal pnl) {
        resetDailyPnlIfNeeded();
        dailyPnl = dailyPnl.add(pnl);
        totalPnl = totalPnl.add(pnl);  // ✅ 修复：平仓盈亏也要计入总收益
        if (pnl.compareTo(BigDecimal.ZERO) < 0) {
            consecutiveLosses++;
            // ========== 连续亏损暂停保护 ==========
            if (consecutiveLosses >= Config.PAUSE_AFTER_CONSECUTIVE_LOSSES) {
                pauseUntilTime = LocalDateTime.now().plusNanos(Config.CONSECUTIVE_LOSS_PAUSE_MS * 1_000_000);
                long pauseHours = Config.CONSECUTIVE_LOSS_PAUSE_MS / 3600000;
                log.error("🚨 连续亏损{}次，触发保护机制，暂停开仓{}小时", consecutiveLosses, pauseHours);
                // 推送通知
                if (feishuNotifier != null) {
                    feishuNotifier.sendAlert(String.format(
                        "🚨 连续亏损保护触发\n连续亏损: %d次\n暂停时长: %d小时\n暂停至: %s",
                        consecutiveLosses, pauseHours, pauseUntilTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    ), null);
                }
            }
        } else {
            consecutiveLosses = 0;
        }
    }

    private void updateFundingRates() {
        try {
            marketDataService.updateFundingRates(Config.TRADING_SYMBOLS);
            fundingRates.clear();
            fundingRates.putAll(marketDataService.getFundingRates());
        } catch (IOException e) {
            log.error("更新资金费率失败: {}", e.getMessage());
        }
    }

    private void updateFundingRatesIfNeeded() {
        try {
            marketDataService.updateFundingRatesIfNeeded(Config.TRADING_SYMBOLS);
            fundingRates.clear();
            fundingRates.putAll(marketDataService.getFundingRates());
        } catch (IOException e) {
            log.error("更新资金费率失败: {}", e.getMessage());
        }
    }
    private void checkRiskControl() {
        updateBalance();
        marginGuardian.checkAndTopupIfNeeded();
    }

    // Bug-4修复: updateBalance 补全风控逻辑
    private void updateBalance() {
        try {
            resetDailyPnlIfNeeded();
            BigDecimal balance = exchangeClient.getBalance();
            if (initialBalance == null) {
                initialBalance = balance;
                maxBalance = balance;
                log.info("📊 初始余额: {} USDT", balance.setScale(2, RoundingMode.HALF_UP));
            }
            if (balance.compareTo(maxBalance) > 0) {
                maxBalance = balance;
            }
            // 更新日报余额
            dailyReporter.updateBalance(balance);

            // 最低余额检查
            if (balance.compareTo(Config.MIN_BALANCE) < 0) {
                log.error("🚨 风控触发！账户余额 {} USDT 低于最低要求 {} USDT，紧急平仓所有仓位！",
                        balance.setScale(2, RoundingMode.HALF_UP), Config.MIN_BALANCE);
                emergencyCloseAll();
                return;
            }

            // 最大回撤检查
            if (maxBalance.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal drawdown = maxBalance.subtract(balance)
                        .divide(maxBalance, 6, RoundingMode.HALF_UP);
                if (drawdown.compareTo(Config.MAX_DRAWDOWN_PERCENT) > 0) {
                    log.error("🚨 风控触发！最大回撤 {}% 超过阈值 {}%，紧急平仓所有仓位！",
                            drawdown.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                            Config.MAX_DRAWDOWN_PERCENT.multiply(new BigDecimal("100")));
                    emergencyCloseAll();
                }
            }

            // 单日亏损检查
            if (dailyPnl.compareTo(Config.MAX_DAILY_LOSS.negate()) < 0) {
                log.error("🚨 风控触发！单日亏损 {} USDT 超过阈值 {} USDT，停止交易！",
                        dailyPnl.setScale(2, RoundingMode.HALF_UP), Config.MAX_DAILY_LOSS);
                emergencyCloseAll();
            }

        } catch (IOException e) {
            log.error("❌ 获取余额失败（风控无法执行）: {}", e.getMessage());
        }
    }

    private void resetDailyPnlIfNeeded() {
        LocalDateTime now = LocalDateTime.now();
        if (dailyPnlDate == null || dailyPnlDate.toLocalDate().isBefore(now.toLocalDate())) {
            dailyPnl = BigDecimal.ZERO;
            consecutiveLosses = 0;
            dailyPnlDate = now;
            log.info("📅 新交易日开始，已重置单日盈亏与连续亏损计数");
        }
    }

    public void emergencyCloseAll() {
        log.warn("⚠️  执行紧急平仓...");
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
            String ratePercent = rate.abs().multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP).toString() + "%";
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
                // 日报：记录持仓快照
                dailyReporter.snapshotPosition(
                        position.getSymbol(),
                        position.getPositionSide(),
                        position.getPositionSize(),
                        position.getEntryPrice(),
                        position.getUnrealizedPnl() != null ? position.getUnrealizedPnl() : BigDecimal.ZERO,
                        position.getUnrealizedPnlRatio() != null ? position.getUnrealizedPnlRatio() : BigDecimal.ZERO,
                        position.getLastFundingRate()
                );
                
                // P2 修复：显示浮盈浮亏
                String pnlStr = "";
                if (position.getUnrealizedPnlRatio() != null) {
                    BigDecimal pnlPercent = position.getUnrealizedPnlRatio().multiply(new BigDecimal("100"));
                    String pnlSign = pnlPercent.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
                    pnlStr = String.format(", 浮盈浮亏: %s%s%%", pnlSign, pnlPercent.setScale(2, RoundingMode.HALF_UP));
                }
                
                log.info("│   ✓ {}: {} {} (年化 {}%, 第 {} 次, 持仓 {}h, 已赚 {} USDT{})",
                        position.getSymbol(),
                        position.getPositionSide(),
                        position.getPositionSize().setScale(4, RoundingMode.HALF_UP),
                        annualized.setScale(2, RoundingMode.HALF_UP),
                        position.getFundingCount(),
                        position.getHoldingHours(),
                        position.getTotalFundingEarned().setScale(4, RoundingMode.HALF_UP),
                        pnlStr);
            }
        }
        if (holdCount == 0) {
            log.info("│   暂无持仓");
        }
        log.info("│   持仓总数: {} / {}", holdCount, Config.MAX_POSITIONS);
        log.info("│   累计收益: {} USDT", totalPnl.setScale(4, RoundingMode.HALF_UP));
        log.info("└───────────────────────────────────────────────────────┘");
        log.info("");
    }

    // ==================== P0 + P4 核心策略逻辑 ====================
    private void executeStrategy() {
        // 如果交易暂停，只监控不开仓
        if (strategyControl != null && strategyControl.isTradingPaused()) {
            log.debug("⏸️  交易已暂停，跳过策略执行");
            return;
        }

        List<Map.Entry<String, BigDecimal>> sortedRates = new ArrayList<>(fundingRates.entrySet());
        sortedRates.sort((a, b) -> b.getValue().abs().compareTo(a.getValue().abs()));

        int currentPositionsCount = (int) positions.values().stream().filter(Position::hasPosition).count();
        int maxPositions = strategyControl != null ? strategyControl.getMaxPositions() : Config.MAX_POSITIONS;

        for (Position position : positions.values()) {
            if (!position.hasPosition()) continue;

            String symbol = position.getSymbol();
            BigDecimal currentRate = fundingRates.getOrDefault(symbol, BigDecimal.ZERO);

            // P2 修复：实时更新浮盈浮亏
            try {
                BigDecimal currentPrice = exchangeClient.getCurrentPrice(symbol);
                position.updateUnrealizedPnl(currentPrice);

                // ========== 问题2：持仓再平衡检查（对冲模式） ==========
                if (position.isHedged()) {
                    BigDecimal deviation = position.getHedgeDeviationRatio(currentPrice);
                    if (deviation.compareTo(new BigDecimal("0.10")) > 0) { // 偏离超过10%
                        log.warn("⚠️  {} 持仓偏离过大: {}%，需要再平衡", symbol, 
                                deviation.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
                        // 暂时只记录日志，不自动再平衡（避免频繁交易增加手续费）
                        // 未来可以考虑：偏离超过20%时触发自动再平衡
                    }
                }
            } catch (Exception e) {
                log.warn("更新 {} 盈亏失败: {}", symbol, e.getMessage());
            }

            // ========== 优化1: 结算时间感知 - 临近结算不轻易平仓 ==========
            boolean nearFunding = position.isNearFundingTime();
            boolean veryNearFunding = position.isVeryNearFundingTime();
            
            // 显示距离下次结算的时间
            long hoursToFunding = position.getHoursToNextFunding();
            String fundingHint = veryNearFunding ? " ⚠️ 1小时内结算！" : 
                                  nearFunding ? " ⏰ 2小时内结算" : "";
            
            // ========== 优化2: 动态止损 - 显示安全垫状态 ==========
            BigDecimal dynamicStopLoss = position.getDynamicStopLossRatio(Config.STOP_LOSS_RATIO);
            boolean principalSafe = position.isPrincipalSafe();
            BigDecimal safetyBuffer = position.getSafetyBuffer();
            
            // 检查费率是否低于平仓阈值（临近结算时提高平仓门槛）
            BigDecimal effectiveCloseRate = nearFunding ? 
                    Config.CLOSE_FUNDING_RATE.multiply(Config.NEAR_FUNDING_CLOSE_RATE_MULTIPLIER) : // 临近结算时，费率要低一半才平仓
                    Config.CLOSE_FUNDING_RATE;
            
            if (currentRate.abs().compareTo(effectiveCloseRate) < 0) {
                // 临近结算时，除非费率特别低，否则等拿到资金费再平仓
                if (veryNearFunding) {
                    log.info("⏰ {} 费率低于阈值，但距离结算仅{}小时，等拿到资金费再平仓{}",
                            symbol, hoursToFunding, fundingHint);
                } else {
                    log.info("📉 {} 费率 {}% 低于平仓阈值，准备平仓...{}",
                            symbol,
                            currentRate.abs().multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                            fundingHint);
                    closePosition(symbol, "费率降低");
                    currentPositionsCount--;
                    continue;
                }
            }

            // ========== 问题6：对冲模式 vs 纯合约模式 止盈止损逻辑 ==========
            if (position.isHedged()) {
                // ========== 对冲模式：基于资金费进度，不基于价格波动 ==========
                // 对冲后价格涨跌互相抵消，主要赚资金费
                // 只有在极端情况下（价格剧烈波动导致严重偏离）才平仓
                if (position.getTotalFundingEarned().compareTo(BigDecimal.ZERO) > 0 &&
                        position.getFundingCount() >= 3) {
                    // 已经赚了3次资金费，落袋为安
                    log.info("💰 {} 对冲模式：已累计{}次资金费收益，落袋为安{}",
                            symbol, position.getFundingCount(), fundingHint);
                    closePosition(symbol, "对冲模式：累计资金费达标");
                    currentPositionsCount--;
                    continue;
                }
                // 对冲模式下，除非极端情况，否则不止损不止盈，安心赚资金费
                log.debug("🔒 {} 对冲模式：价格波动已对冲，不触发止盈止损", symbol);
            } else {
                // ========== 纯合约模式：原来的动态止盈止损逻辑 ==========
                if (position.isStopLossTriggered(Config.STOP_LOSS_RATIO)) {
                    if (principalSafe) {
                        log.info("🛡️  {} 触发原始止损{}%，但资金费安全垫已覆盖浮亏（缓冲{} USDT），继续持有{}",
                                symbol,
                                Config.STOP_LOSS_RATIO.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                                safetyBuffer.setScale(4, RoundingMode.HALF_UP),
                                fundingHint);
                    } else {
                        log.error("🚨 {} 触发动态止损！原始{}% → 动态{}%，盈亏{}%，强制平仓{}",
                                symbol,
                                Config.STOP_LOSS_RATIO.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                                dynamicStopLoss.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                                position.getUnrealizedPnlRatio().multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                                fundingHint);
                        closePosition(symbol, "触发止损");
                        currentPositionsCount--;
                        continue;
                    }
                }

                // 纯合约模式：止盈检查
                BigDecimal effectiveTakeProfit = nearFunding ?
                        Config.TAKE_PROFIT_RATIO.multiply(Config.NEAR_FUNDING_TAKE_PROFIT_MULTIPLIER) : // 临近结算时止盈线提高50%
                        Config.TAKE_PROFIT_RATIO;

                if (position.isTakeProfitTriggered(effectiveTakeProfit)) {
                    log.info("🎯 {} 触发止盈！盈亏 {}%，主动平仓{}",
                            symbol,
                            position.getUnrealizedPnlRatio().multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                            fundingHint);
                    closePosition(symbol, "触发止盈");
                    currentPositionsCount--;
                }
            }
        }

        for (Map.Entry<String, BigDecimal> entry : sortedRates) {
            String symbol = entry.getKey();
            BigDecimal rate = entry.getValue();
            Position position = positions.get(symbol);
            if (!Config.isSymbolAllowed(symbol)) {
                log.info("Skip {} because it is excluded by symbol eligibility config", symbol);
                continue;
            }

            // P2 修复：跳过无效的异常费率数据
            if (rate.compareTo(Config.MIN_VALID_FUNDING_RATE) < 0 || rate.compareTo(Config.MAX_VALID_FUNDING_RATE) > 0) {
                log.warn("⚠️ {} 费率 {}% 异常，跳过", symbol,
                        rate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
                continue;
            }

            // 动态阈值：正费率0.06%，负费率0.08%（负费率风险更高）
            BigDecimal minRate = rate.compareTo(BigDecimal.ZERO) >= 0 
                    ? Config.MIN_FUNDING_RATE_POSITIVE
                    : Config.MIN_FUNDING_RATE_NEGATIVE;

            if (rate.abs().compareTo(minRate) < 0) {
                break;
            }

            // 负费率额外检查：是否是极端行情
            if (rate.compareTo(BigDecimal.ZERO) < 0 && isExtremeMarket(symbol, rate)) {
                continue;
            }

            // 【新增1: 策略熔断检查
            if (!isTradingAllowed()) {
                continue;
            }

            // 【新增2: 费率趋势检查 - 不接下落的刀
            if (!isRateTrendGood(symbol, rate)) {
                continue;
            }
            if (!isLiveMarketStateAcceptable(symbol, rate)) {
                continue;
            }
            if (!isExpectedNetFundingAcceptable(symbol, rate)) {
                continue;
            }

            // 【新增3: 净敞口检查 - 防止单边全空/全多
            String side = rate.compareTo(BigDecimal.ZERO) >= 0 ? "SHORT" : "LONG";
            if (!isExposureAcceptable(side)) {
                continue;
            }

            // ✅ 新增：记录费率历史，用于稳定性检查
            position.addRateHistory(rate);

            // ✅ 新增：止损冷却期检查
            if (!position.hasPosition() && position.isInStopLossCooldown()) {
                continue;
            }

            // ✅ 新增：费率稳定性检查（复用前面的 minRate 变量）
            if (!position.hasPosition() && !position.isRateStable(minRate)) {
                if (position.getRateHistorySize() > 0) {
                    log.debug("⏳ {} 费率还不稳定，当前已记录 {} 次，需要 {} 次", 
                            symbol, position.getRateHistorySize(), Config.RATE_STABLE_CHECK_COUNT);
                }
                continue;
            }

            if (!position.hasPosition() && currentPositionsCount < maxPositions) {
                log.info("🎯 {} 费率 {}% 达标且已稳定，准备开仓...",
                        symbol, rate.abs().multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
                openPosition(symbol, rate);
                currentPositionsCount++;
            } else if (!position.hasPosition() && currentPositionsCount >= maxPositions) {
                Position toClose = positions.values().stream()
                        .filter(Position::hasPosition)
                        .min(Comparator.comparing(p -> p.getLastFundingRate().abs()))
                        .orElse(null);

                if (toClose != null) {
                    BigDecimal diff = rate.abs().subtract(toClose.getLastFundingRate().abs());
                    
                    // ========== 优化3: 移仓成本测算 ==========
                    Position.SwitchCostAnalysis costAnalysis = 
                            toClose.analyzeSwitchCost(rate.abs(), Config.SWITCH_FUNDING_RATE_THRESHOLD, Config.FEE_PER_TRADE);
                    
                    // 临近结算时不轻易移仓
                    boolean veryNearFunding = toClose.isVeryNearFundingTime();
                    long hoursToFunding = toClose.getHoursToNextFunding();
                    
                    if (veryNearFunding) {
                        log.info("⏰ {} 距离结算仅{}小时，不移仓，先拿到资金费", 
                                toClose.getSymbol(), hoursToFunding);
                    } else if (costAnalysis.worthIt && isExpectedNetFundingAcceptable(symbol, rate)) {
                        log.info("🔄 移仓分析：{}", costAnalysis);
                        log.info("🔄 移仓：从 {} ({}%) 到 {} ({}%), 差值 {}% 净收益为正，执行移仓",
                                toClose.getSymbol(),
                                toClose.getLastFundingRate().abs().multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                                symbol,
                                rate.abs().multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                                diff.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
                        closePosition(toClose.getSymbol(), "移仓到更高费率币种");
                        openPosition(symbol, rate);
                    } else {
                        log.info("⏸️  {} 移仓不划算：{}", symbol, costAnalysis);
                    }
                }
            }
        }
    }

    // ==================== P0 修复：支持现货对冲开仓 ====================
    private void openPosition(String symbol, BigDecimal fundingRate) {
        boolean useHedge = Config.SPOT_HEDGE_ENABLED && fundingRate.compareTo(BigDecimal.ZERO) > 0;
        log.info("");
        log.info("┌──────────────────────────────────────────────────────────┐");
        log.info("│              🚀 开仓操作（{}）                          │", useHedge ? "现货对冲" : "纯合约");
        log.info("└──────────────────────────────────────────────────────────┘");

        try {
            exchangeClient.setLeverage(symbol, Config.LEVERAGE);

            BigDecimal currentPrice = exchangeClient.getCurrentPrice(symbol);
            // ✅ 修复：POSITION_VALUE_USDT 已经是杠杆后的名义价值，不需要再乘杠杆！
            BigDecimal quantity = Config.POSITION_VALUE_USDT
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

            if (!isExpectedNetFundingAcceptable(symbol, fundingRate)) {
                return;
            }

            BigDecimal estimatedOpenNotional = currentPrice.multiply(alignedQuantity);
            AtomicTransactionManager.TxResult result = txManager.atomicOpenPosition(symbol, alignedQuantity, fundingRate);
            if (!result.isSuccess()) {
                throw new IOException("开仓失败: " + result.message);
            }

            TradeExecutionReport execution = result.executionReport;
            BigDecimal executionPrice = executionPriceOrFallback(execution, currentPrice);
            BigDecimal executedQuantity = executionQuantityOrFallback(execution, alignedQuantity);
            BigDecimal executedNotional = executionNotionalOrFallback(execution, executionPrice, executedQuantity);

            Position position = positions.get(symbol);
            if (useHedge) {
                // ========== 现货对冲模式：开合约同时买现货 ==========
                log.info("🔄 启用现货对冲模式，对冲比例: {}%", 
                        Config.SPOT_HEDGE_RATIO.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP));
                
                // 计算现货数量：按对冲比例
                BigDecimal spotQuantity = alignedQuantity.multiply(Config.SPOT_HEDGE_RATIO);
                BigDecimal spotAlignedQuantity = precision.alignSpotQuantity(symbol, spotQuantity);
                
                if (spotAlignedQuantity.compareTo(BigDecimal.ZERO) > 0) {
                    try {
                        // 现货下单（市价买入）
                        log.info("📈 现货买入: {} {}", spotAlignedQuantity, symbol);
                        exchangeClient.openSpotPosition(symbol, spotAlignedQuantity);
                        
                        // 获取现货成交价格（简化：使用当前价格）
                        BigDecimal spotExecutionPrice = currentPrice;
                        
                        // 记录带现货对冲的持仓
                        position.openWithHedge(executedQuantity, executionPrice, 
                                spotAlignedQuantity, spotExecutionPrice, 
                                fundingRate, side, Config.SPOT_HEDGE_RATIO);
                        
                        log.info("✅ 现货对冲开仓完成: 合约 {} {}, 现货 {} {}", 
                                side, executedQuantity, "买入", spotAlignedQuantity);
                    } catch (Exception e) {
                        log.error("❌ 现货下单失败，合约持仓已开，现货未开！需要手动处理: {}", e.getMessage());
                        // 降级为纯合约模式
                        position.open(executedQuantity, executionPrice, fundingRate, side);
                    }
                } else {
                    log.warn("⚠️  现货数量为0，降级为纯合约模式");
                    position.open(executedQuantity, executionPrice, fundingRate, side);
                }
            } else {
                // 纯合约模式
                position.open(executedQuantity, executionPrice, fundingRate, side);
            }
            totalTrades++;
            // 日报记录开仓
            dailyReporter.recordOpen(symbol, side, executedQuantity, fundingRate);

            // ✅ 计算开仓手续费（双边：开仓）
            BigDecimal positionValue = executedNotional;
            BigDecimal openFee = executionFeeOrFallback(execution, positionValue);
            recordExecutionCost(
                    "OPEN",
                    symbol,
                    side,
                    executionOrderIds(execution, result),
                    estimatedOneWayFee(estimatedOpenNotional),
                    openFee,
                    estimatedOneWaySlippage(estimatedOpenNotional),
                    executionSlippageCost(currentPrice, executionPrice, executedQuantity),
                    expectedNetFundingAfterCosts(fundingRate),
                    positionValue,
                    executionPrice,
                    currentPrice,
                    execution);
            // ✅ 更新模拟账户余额：扣除开仓手续费
            exchangeClient.updateSimulatedBalance(openFee.negate());

            BigDecimal annualized = fundingRate.abs()
                    .multiply(new BigDecimal("1095"))  // 3年=1095次资金费结算
                    .multiply(new BigDecimal("100"))   // 转成百分比
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal expectedEarningOpen = positionValue.multiply(fundingRate.abs());
            
            try {
                BigDecimal openBalance = exchangeClient.getBalance();
                log.info("");
                log.info("🎉 开仓完成！ {} {} ({}), 成交均价 {}, 预计年化 {}%, 手续费 {} USDT", symbol, sideName, executedQuantity, executionPrice.setScale(8, RoundingMode.HALF_UP), annualized, openFee.setScale(4, RoundingMode.HALF_UP));
                log.info("");
                // 飞书推送：开仓通知
                feishuNotifier.sendOpenPosition(symbol, sideName, executedQuantity, fundingRate, annualized, openBalance, expectedEarningOpen, openFee);
            } catch (IOException e) {
                log.warn("⚠️  获取余额失败，飞书通知将不带余额信息: {}", e.getMessage());
                log.info("");
                log.info("🎉 开仓完成！ {} {} ({}), 成交均价 {}, 预计年化 {}%, 手续费 {} USDT", symbol, sideName, executedQuantity, executionPrice.setScale(8, RoundingMode.HALF_UP), annualized, openFee.setScale(4, RoundingMode.HALF_UP));
                log.info("");
                // 飞书推送：开仓通知
                feishuNotifier.sendOpenPosition(symbol, sideName, executedQuantity, fundingRate, annualized, BigDecimal.ZERO, expectedEarningOpen, openFee);
            }

        } catch (Exception e) {
            log.error("❌ 开仓失败: {}", e.getMessage(), e);
        }
    }

    private void closePosition(String symbol) {
        closePosition(symbol, "费率降低/止盈止损");
    }
    
    private BigDecimal executionPriceOrFallback(TradeExecutionReport execution, BigDecimal fallbackPrice) {
        if (execution != null && execution.getAveragePrice().compareTo(BigDecimal.ZERO) > 0) {
            return execution.getAveragePrice();
        }
        return fallbackPrice;
    }

    private BigDecimal executionQuantityOrFallback(TradeExecutionReport execution, BigDecimal fallbackQuantity) {
        if (execution != null && execution.getExecutedQuantity().compareTo(BigDecimal.ZERO) > 0) {
            return execution.getExecutedQuantity();
        }
        return fallbackQuantity;
    }

    private BigDecimal executionNotionalOrFallback(TradeExecutionReport execution, BigDecimal price,
                                                   BigDecimal quantity) {
        if (execution != null && execution.getNotional().compareTo(BigDecimal.ZERO) > 0) {
            return execution.getNotional();
        }
        return price.multiply(quantity);
    }

    private BigDecimal executionFeeOrFallback(TradeExecutionReport execution, BigDecimal notional) {
        if (execution != null && "USDT".equalsIgnoreCase(execution.getFeeAsset())
                && execution.getFee().compareTo(BigDecimal.ZERO) > 0) {
            return execution.getFee();
        }
        return notional.multiply(Config.LIVE_TAKER_FEE_RATE);
    }

    private BigDecimal estimatedOneWayFee(BigDecimal notional) {
        return notional.multiply(Config.LIVE_TAKER_FEE_RATE);
    }

    private BigDecimal estimatedOneWaySlippage(BigDecimal notional) {
        return notional.multiply(Config.LIVE_SLIPPAGE_RATE);
    }

    private BigDecimal executionSlippageCost(BigDecimal estimatedPrice, BigDecimal executionPrice,
                                             BigDecimal quantity) {
        if (estimatedPrice == null || executionPrice == null || quantity == null) {
            return BigDecimal.ZERO;
        }
        return executionPrice.subtract(estimatedPrice).abs().multiply(quantity.abs());
    }

    private String executionOrderIds(TradeExecutionReport execution, AtomicTransactionManager.TxResult result) {
        if (execution != null && execution.getOrderIds() != null && !execution.getOrderIds().isEmpty()) {
            return execution.getOrderIds();
        }
        if (result != null && result.operations != null) {
            return result.operations.stream()
                    .map(op -> op.orderId)
                    .filter(Objects::nonNull)
                    .filter(id -> !id.isEmpty())
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
        }
        return "";
    }

    private void recordExecutionCost(String type, String symbol, String side, String orderIds,
                                     BigDecimal estimatedFee, BigDecimal actualFee,
                                     BigDecimal estimatedSlippage, BigDecimal actualSlippage,
                                     BigDecimal expectedNetFunding, BigDecimal actualNotional,
                                     BigDecimal executionPrice, BigDecimal estimatedPrice,
                                     TradeExecutionReport execution) {
        if (persistence == null) {
            return;
        }
        boolean estimated = execution == null || execution.isEstimated();
        persistence.recordExecutionCost(type, symbol, side, orderIds, estimatedFee, actualFee,
                estimatedSlippage, actualSlippage, expectedNetFunding, actualNotional,
                executionPrice, estimatedPrice, estimated);
    }

    private void closePosition(String symbol, String reason) {
        Position position = positions.get(symbol);
        if (!position.hasPosition()) return;

        boolean isHedged = position.isHedged();
        log.info("");
        log.info("┌──────────────────────────────────────────────────────────┐");
        log.info("│              📉 平仓操作（{}）                          │", isHedged ? "现货对冲" : "纯合约");
        log.info("└──────────────────────────────────────────────────────────┘");

        // ========== 最低持仓时间检查 ==========
        long holdingHours = position.getHoldingHours();
        if (holdingHours < Config.MIN_HOLDING_HOURS && !reason.contains("止损") && !reason.contains("止损")) {
            log.info("⏰ 持仓时间{}小时不足{}小时（最低持仓要求），除非止损否则不平仓", 
                    holdingHours, Config.MIN_HOLDING_HOURS);
            log.info("   平仓原因: {}", reason);
            return;
        }

        try {
            BigDecimal alignedQuantity = precision.alignQuantity(symbol, position.getPositionSize());

            String side = position.getPositionSide();

            // ========== 先平现货（如果有对冲） ==========
            if (isHedged && position.getSpotPositionSize().compareTo(BigDecimal.ZERO) > 0) {
                try {
                    BigDecimal spotQuantity = position.getSpotPositionSize();
                    BigDecimal spotAlignedQuantity = precision.alignSpotQuantity(symbol, spotQuantity);
                    log.info("📉 现货卖出平仓: {} {}", spotAlignedQuantity, symbol);
                    exchangeClient.closeSpotPosition(symbol, spotAlignedQuantity);
                    log.info("✅ 现货平仓成功");
                } catch (Exception e) {
                    log.error("❌ 现货平仓失败，需要手动处理: {}", e.getMessage());
                }
            }

            // ========== 平合约 ==========
            AtomicTransactionManager.TxResult result = txManager.atomicClosePosition(symbol, alignedQuantity, side);
            if (!result.isSuccess()) {
                try {
                    BigDecimal actualPos = exchangeClient.getCurrentPosition(symbol);
                    if (actualPos.abs().compareTo(BigDecimal.valueOf(0.001)) <= 0) {
                        log.info("✅ 交易所实际已无持仓，可能是后台重试成功或触发交易所平仓，将同步清理本地状态。");
                    } else {
                        throw new IOException("⚠️ 平仓失败（交易所未成交）: " + result.message);
                    }
                } catch (Exception ex) {
                    if (ex instanceof IOException) throw (IOException) ex;
                    throw new IOException("⚠️ 平仓失败且无法验证真实仓位: " + result.message);
                }
            }

            BigDecimal closePrice = position.getMarkPrice() != null ? position.getMarkPrice() : position.getEntryPrice();
            try {
                closePrice = exchangeClient.getCurrentPrice(symbol);
            } catch (Exception e) {
                log.warn("⚠️  获取 {} 最新价格失败，使用已有浮盈亏计算平仓收益: {}", symbol, e.getMessage());
            }

            // 计算平仓盈亏（浮盈浮亏实现化）
            // ⚠️ 注意：资金费收益已经在每次结算时加到totalPnl中了，这里只加买卖盈亏！
            BigDecimal estimatedClosePrice = closePrice;
            BigDecimal estimatedCloseNotional = estimatedClosePrice.multiply(alignedQuantity);
            TradeExecutionReport execution = result.executionReport;
            closePrice = executionPriceOrFallback(execution, closePrice);
            BigDecimal executedQuantity = executionQuantityOrFallback(execution, alignedQuantity);
            BigDecimal executedNotional = executionNotionalOrFallback(execution, closePrice, executedQuantity);
            position.updateUnrealizedPnl(closePrice);

            BigDecimal closePnl = BigDecimal.ZERO;
            if (position.getUnrealizedPnl() != null) {
                closePnl = position.getUnrealizedPnl();
            }
            // 只计算买卖盈亏（资金费已经在结算时统计过了）
            BigDecimal totalReturn = closePnl;

            // ✅ 计算平仓手续费
            BigDecimal positionValue = position.getEntryPrice().multiply(position.getPositionSize());
            BigDecimal closePositionValue = executedNotional;
            BigDecimal closeFee = executionFeeOrFallback(execution, closePositionValue);
            recordExecutionCost(
                    "CLOSE",
                    symbol,
                    side,
                    executionOrderIds(execution, result),
                    estimatedOneWayFee(estimatedCloseNotional),
                    closeFee,
                    estimatedOneWaySlippage(estimatedCloseNotional),
                    executionSlippageCost(estimatedClosePrice, closePrice, executedQuantity),
                    BigDecimal.ZERO,
                    closePositionValue,
                    closePrice,
                    estimatedClosePrice,
                    execution);
            // ✅ 更新模拟账户余额：加上平仓盈亏，扣除平仓手续费
            //   注意：资金费收益已经在每次结算时加到totalPnl了，这里只加买卖盈亏
            exchangeClient.updateSimulatedBalance(closePnl.subtract(closeFee));

            // 动态止损相关信息
            BigDecimal safetyBuffer = position.getSafetyBuffer();
            boolean principalSafe = position.isPrincipalSafe();
            
            log.info("💵 平仓结算: 资金费收益{} USDT(已累计), 本次买卖盈亏{} USDT, 开仓手续费{} USDT, 平仓手续费{} USDT (持仓{}小时, {}次结算, 安全垫{} USDT, 保本:{})",
                    position.getTotalFundingEarned().setScale(4, RoundingMode.HALF_UP),
                    closePnl.setScale(4, RoundingMode.HALF_UP),
                    positionValue.multiply(Config.LIVE_TAKER_FEE_RATE).setScale(4, RoundingMode.HALF_UP),
                    closeFee.setScale(4, RoundingMode.HALF_UP),
                    position.getHoldingHours(),
                    position.getFundingCount(),
                    safetyBuffer.setScale(4, RoundingMode.HALF_UP),
                    principalSafe ? "是✅" : "否❌");
            log.info("📋 平仓原因: {}", reason);

            // 记录盈亏用于熔断机制
            recordClosePnl(totalReturn);

            // 平仓前先保存用于日报的数据（因为 position.close() 会清空状态）
            BigDecimal finalPnl = closePnl;
            BigDecimal totalFundingEarned = position.getTotalFundingEarned();
            BigDecimal totalFees = positionValue.multiply(Config.LIVE_TAKER_FEE_RATE).add(closeFee);
            
            // ✅ 如果是止损平仓，记录止损时间（用于冷却期）
            if (reason.contains("止损")) {
                position.recordStopLossTime();
                log.info("⏸️ {} 止损后进入{}小时冷却期，暂不开仓", symbol, Config.STOP_LOSS_COOLDOWN_MS / 3600000);
            }
            
            position.close();
            totalTrades++;

            // 日报记录平仓
            dailyReporter.recordClose(symbol, totalFundingEarned, finalPnl, reason);

            try {
                BigDecimal closeBalance = exchangeClient.getBalance();
                log.info("");
                log.info("✅ 平仓完成！");
                log.info("");
                // 飞书推送：平仓通知（带详细原因）
                feishuNotifier.sendClosePosition(symbol, totalFundingEarned, finalPnl, reason, closeBalance, totalFees);
            } catch (IOException e) {
                log.warn("⚠️  获取余额失败，飞书通知将不带余额信息: {}", e.getMessage());
                log.info("");
                log.info("✅ 平仓完成！");
                log.info("");
                // 飞书推送：平仓通知（带详细原因）
                feishuNotifier.sendClosePosition(symbol, totalFundingEarned, finalPnl, reason, BigDecimal.ZERO, totalFees);
            }

        } catch (Exception e) {
            log.error("❌ 平仓失败: {}", e.getMessage(), e);
        }
    }

    private void sleep() {
        try {
            long interval = strategyControl != null ? strategyControl.getCheckIntervalMs() : Config.CHECK_INTERVAL_MS;
            log.info("💤 等待 {} 分钟后下次检查...", interval / 1000 / 60);
            log.info("");
            Thread.sleep(interval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("程序被中断，正在退出...");
        }
    }

    // ===== Getters for Dashboard =====
    public Map<String, Position> getPositions() {
        return positions;
    }

    public BigDecimal getTotalPnl() {
        return totalPnl;
    }

    public int getTotalTrades() {
        return totalTrades;
    }

    public DailyReporter getDailyReporter() {
        return dailyReporter;
    }
}
