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

    // ========== 费率阈值（优化后）==========
    // 提高到 0.1%，只做高收益机会
    private static final BigDecimal MIN_FUNDING_RATE_POSITIVE = new BigDecimal("0.0010"); // 正费率0.1%
    private static final BigDecimal MIN_FUNDING_RATE_NEGATIVE = new BigDecimal("0.0015"); // 负费率0.15%
    
    // ========== 费率趋势预测（防止开在下降通道
    private static final int RATE_TREND_CHECK_MINUTES = 60; // 检查过去1小时费率趋势
    private static final BigDecimal RATE_DECREASING_THRESHOLD = new BigDecimal("0.7"); // 费率下降超过30%不开仓
    private static final BigDecimal CLOSE_FUNDING_RATE = new BigDecimal("0.0005"); // 0.05% 平仓阈值
    private static final BigDecimal SWITCH_THRESHOLD   = new BigDecimal("0.0015");  // 0.15% 移仓阈值
    private static final BigDecimal FEE_PER_TRADE      = new BigDecimal("0.003");  // 0.30% 手续费
    
    // ========== 风控参数（优化：降低杠杆，放宽止损）==========
    private static final BigDecimal STOP_LOSS_RATIO    = new BigDecimal("0.05");  // 5% 止损（给价格波动留出空间）
    private static final BigDecimal TAKE_PROFIT_RATIO  = new BigDecimal("0.08");  // 8% 止盈
    
    // ========== 负费率风险控制 ==========
    private static final BigDecimal MAX_NEGATIVE_RATE  = new BigDecimal("-0.003"); // 低于-0.3%不做多（极端行情）
    private static final BigDecimal MAX_24H_CHANGE     = new BigDecimal("0.1");    // 24h涨跌幅超过10%不交易

    // ========== 结算时间（UTC 0, 8, 16 点） ==========
    private static final List<Integer> FUNDING_HOURS = Arrays.asList(0, 8, 16);
    private volatile int lastSettledHourUtc = -1;

    // ========== 核心组件 ==========
    private ExchangeClient exchangeClient;
    private ExchangePrecision precision;
    private AtomicTransactionManager txManager;
    private SmartOrderExecutor smartOrderExecutor;
    private MarginGuardian marginGuardian;
    private StrategyControl strategyControl;      // 策略控制器
    private StrategyPersistence persistence;       // 持久化
    private StrategyDashboard dashboard;           // Web仪表盘
    private RetryManager retryManager;             // 重试管理器
    private DailyReporter dailyReporter;           // 自动日报生成器
    private FeishuNotifier feishuNotifier;         // 飞书推送

    // ========== 状态变量 ==========
    private final Map<String, Position> positions = new HashMap<>();
    private final Map<String, BigDecimal> fundingRates = new HashMap<>();
    private final Map<String, BigDecimal> price24hChange = new HashMap<>(); // 24h涨跌幅
    private final Map<String, List<BigDecimal>> rateHistory = new HashMap<>(); // 费率历史用于趋势判断
    
    // ========== 风控熔断机制 ==========
    private volatile int consecutiveLosses = 0;  // 连续亏损次数
    private volatile BigDecimal dailyPnl = BigDecimal.ZERO;  // 当日盈亏
    private static final int MAX_CONSECUTIVE_LOSSES = 3;  // 最多连续亏损3次
    private static final BigDecimal MAX_DAILY_LOSS_AMOUNT = new BigDecimal("500");  // 单日最大亏损500USDT
    private static final BigDecimal MAX_NET_EXPOSURE = new BigDecimal("0.5");  // 最大净敞口50%

    // 费率有效性范围
    private static final BigDecimal MIN_VALID_RATE = new BigDecimal("-0.01");
    private static final BigDecimal MAX_VALID_RATE = new BigDecimal("0.01");
    private volatile BigDecimal totalPnl = BigDecimal.ZERO;
    private volatile int totalTrades = 0;
    private volatile LocalDateTime lastRateUpdate = null;
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
        log.info("│ 正费率开仓:     {}%", MIN_FUNDING_RATE_POSITIVE.multiply(new BigDecimal("100")).setScale(3, RoundingMode.HALF_UP));
        log.info("│ 负费率开仓:     {}%", MIN_FUNDING_RATE_NEGATIVE.multiply(new BigDecimal("100")).setScale(3, RoundingMode.HALF_UP));
        log.info("│ 平仓阈值:       {}%", CLOSE_FUNDING_RATE.multiply(new BigDecimal("100")).setScale(3, RoundingMode.HALF_UP));
        log.info("│ 止损比例:       {}%", STOP_LOSS_RATIO.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
        log.info("│ 止盈比例:       {}%", TAKE_PROFIT_RATIO.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
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
        if (FUNDING_HOURS.contains(utcHour) && utcMinute < 15) {
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
        for (Position position : positions.values()) {
            if (!position.hasPosition()) continue;

            BigDecimal currentRate = fundingRates.getOrDefault(position.getSymbol(), position.getLastFundingRate());
            BigDecimal notionalValue = position.getPositionSize().multiply(position.getEntryPrice());
            BigDecimal earning = notionalValue
                    .multiply(currentRate.abs())
                    .setScale(6, RoundingMode.HALF_UP);

            position.recordFundingSettlement(earning);
            position.setLastFundingRate(currentRate);
            totalPnl = totalPnl.add(earning);

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
            feishuNotifier.sendFundingSettlement(position.getSymbol(), earning, currentRate, position.getFundingCount());
        }
        // 结算后保存状态
        persistence.saveState(positions, totalPnl, totalTrades);
    }

    /**
     * 更新24h涨跌幅 - 用于负费率风险过滤
     */
    private void update24hPrices() {
        try {
            for (String symbol : Config.TRADING_SYMBOLS) {
                BigDecimal change = exchangeClient.get24hChange(symbol);
                price24hChange.put(symbol, change);
            }
            log.debug("✅ 已更新24h涨跌幅");
        } catch (Exception e) {
            log.warn("更新24h涨跌幅失败: {}", e.getMessage());
        }
    }

    /**
     * 检查极端行情风险（负费率专用）
     * 费率太负说明市场极度看空，此时做多风险极大
     */
    private boolean isExtremeMarket(String symbol, BigDecimal rate) {
        // 1. 费率低于 -0.3%，可能是极端行情
        if (rate.compareTo(MAX_NEGATIVE_RATE) < 0) {
            log.warn("⚠️  {} 费率 {}% 异常低，可能是极端行情，跳过做多", 
                    symbol, rate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
            return true;
        }

        // 2. 24h涨跌幅超过 ±10%
        BigDecimal change = price24hChange.getOrDefault(symbol, BigDecimal.ZERO);
        if (change.abs().compareTo(MAX_24H_CHANGE) > 0) {
            log.warn("⚠️  {} 24h涨跌幅 {}% 过大，跳过交易", 
                    symbol, change.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
            return true;
        }

        return false;
    }

    /**
     * 检查费率趋势 - 防止开在费率下降通道
     * 返回 true 表示趋势良好（正费率上升/负费率更负），可以开仓
     */
    private boolean isRateTrendGood(String symbol, BigDecimal currentRate) {
        List<BigDecimal> history = rateHistory.get(symbol);
        if (history == null || history.size() < 5) {
            // 历史数据不足，保守判断为趋势良好
            return true;
        }

        // 取最近5次数据的平均值
        BigDecimal avgRate = BigDecimal.ZERO;
        for (BigDecimal r : history) {
            avgRate = avgRate.add(r);
        }
        avgRate = avgRate.divide(new BigDecimal(history.size()), 8, RoundingMode.HALF_UP);

        // 趋势判断
        if (currentRate.compareTo(BigDecimal.ZERO) > 0) {
            // 正费率：当前费率应 > 历史平均的70%（即下降不超过30%）
            BigDecimal threshold = avgRate.multiply(RATE_DECREASING_THRESHOLD);
            boolean trendGood = currentRate.compareTo(threshold) >= 0;
            if (!trendGood) {
                log.warn("⚠️  {} 费率处于下降通道: 当前{}% vs 平均{}%，跳过开仓", symbol,
                        currentRate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                        avgRate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
            }
            return trendGood;
        } else {
            // 负费率：绝对值应 > 历史平均的70%
            BigDecimal threshold = avgRate.abs().multiply(RATE_DECREASING_THRESHOLD);
            boolean trendGood = currentRate.abs().compareTo(threshold) >= 0;
            if (!trendGood) {
                log.warn("⚠️  {} 负费率处于衰减通道: 当前{}% vs 平均{}%，跳过开仓", symbol,
                        currentRate.abs().multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                        avgRate.abs().multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
            }
            return trendGood;
        }
    }

    /**
     * 计算净敞口 - 多空净方向暴露
     * 返回正值表示净多头比例，负值表示净空头比例（0-1之间）
     */
    private BigDecimal calculateNetExposure() {
        BigDecimal totalNotional = BigDecimal.ZERO;
        BigDecimal netNotional = BigDecimal.ZERO;

        for (Position pos : positions.values()) {
            if (!pos.hasPosition()) continue;

            BigDecimal notional = pos.getPositionSize().multiply(pos.getEntryPrice());
            totalNotional = totalNotional.add(notional);

            if ("LONG".equals(pos.getPositionSide())) {
                netNotional = netNotional.add(notional);
            } else {
                netNotional = netNotional.subtract(notional);
            }
        }

        if (totalNotional.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return netNotional.divide(totalNotional, 4, RoundingMode.HALF_UP);
    }

    /**
     * 检查净敞口是否超限
     * 开仓前调用，确保不会出现单边全空/全多
     */
    private boolean isExposureAcceptable(String newSide) {
        BigDecimal currentExposure = calculateNetExposure();

        // 假设新仓位大小（简化计算，不精确但保守
        BigDecimal singlePositionRatio = BigDecimal.ONE.divide(
                new BigDecimal(strategyControl.getMaxPositions()), 4, RoundingMode.HALF_UP
        );

        BigDecimal newExposure;
        if ("LONG".equals(newSide)) {
            newExposure = currentExposure.add(singlePositionRatio);
        } else {
            newExposure = currentExposure.subtract(singlePositionRatio);
        }

        boolean acceptable = newExposure.abs().compareTo(MAX_NET_EXPOSURE) <= 0;

        if (!acceptable) {
            log.warn("⚠️  净敞口超限: 当前{}%，开{}后将达{}%，限制{}%，跳过开仓",
                    currentExposure.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                    newSide,
                    newExposure.abs().multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                    MAX_NET_EXPOSURE.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP));
        }

        return acceptable;
    }

    /**
     * 策略熔断检查 - 连续亏损/单日大亏后暂停
     */
    private boolean isTradingAllowed() {
        // 1. 连续亏损熔断
        if (consecutiveLosses >= MAX_CONSECUTIVE_LOSSES) {
            log.warn("🚨 连续亏损{}次触发熔断，暂停开仓", consecutiveLosses);
            return false;
        }

        // 2. 单日亏损熔断
        if (dailyPnl.compareTo(MAX_DAILY_LOSS_AMOUNT.negate()) < 0) {
            log.warn("🚨 单日亏损{}USDT触发熔断，暂停开仓", dailyPnl.setScale(2, RoundingMode.HALF_UP));
            return false;
        }

        // 3. 策略控制层面暂停
        if (strategyControl != null && strategyControl.isTradingPaused()) {
            return false;
        }

        return true;
    }

    /**
     * 记录一次平仓盈亏，更新熔断状态
     */
    private void recordClosePnl(BigDecimal pnl) {
        dailyPnl = dailyPnl.add(pnl);
        if (pnl.compareTo(BigDecimal.ZERO) < 0) {
            consecutiveLosses++;
        } else {
            consecutiveLosses = 0;
        }
    }

    private void updateFundingRates() {
        try {
            Map<String, BigDecimal> rates = exchangeClient.getAllFundingRates(Config.TRADING_SYMBOLS);
            this.fundingRates.putAll(rates);
            this.lastRateUpdate = LocalDateTime.now();

            // 记录费率历史用于趋势判断
            for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
                String symbol = entry.getKey();
                BigDecimal rate = entry.getValue();
                rateHistory.computeIfAbsent(symbol, k -> new LinkedList<>());
                List<BigDecimal> history = rateHistory.get(symbol);
                history.add(rate);
                // 只保留最近20条数据
                while (history.size() > 20) {
                    ((LinkedList<BigDecimal>) history).removeFirst();
                }
            }

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

    // Bug-4修复: updateBalance 补全风控逻辑
    private void updateBalance() {
        try {
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
            if (totalPnl.compareTo(Config.MAX_DAILY_LOSS.negate()) < 0) {
                log.error("🚨 风控触发！单日亏损 {} USDT 超过阈值 {} USDT，停止交易！",
                        totalPnl.setScale(2, RoundingMode.HALF_UP), Config.MAX_DAILY_LOSS);
                emergencyCloseAll();
            }

        } catch (IOException e) {
            log.error("❌ 获取余额失败（风控无法执行）: {}", e.getMessage());
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
            } catch (Exception e) {
                log.warn("更新 {} 盈亏失败: {}", symbol, e.getMessage());
            }

            // 检查费率是否低于平仓阈值
            if (currentRate.abs().compareTo(CLOSE_FUNDING_RATE) < 0) {
                log.info("📉 {} 费率 {}% 低于平仓阈值，准备平仓...",
                        symbol,
                        currentRate.abs().multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
                closePosition(symbol);
                currentPositionsCount--;
                continue;
            }

            // P2 修复：止损检查 - 强制平仓防止极端行情
            if (position.isStopLossTriggered(STOP_LOSS_RATIO)) {
                log.error("🚨 {} 触发止损！盈亏 {}%，强制平仓",
                        symbol,
                        position.getUnrealizedPnlRatio().multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
                closePosition(symbol);
                currentPositionsCount--;
                continue;
            }

            // P2 修复：止盈检查 - 大行情主动止盈离场
            if (position.isTakeProfitTriggered(TAKE_PROFIT_RATIO)) {
                log.info("🎯 {} 触发止盈！盈亏 {}%，主动平仓",
                        symbol,
                        position.getUnrealizedPnlRatio().multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
                closePosition(symbol);
                currentPositionsCount--;
            }
        }

        for (Map.Entry<String, BigDecimal> entry : sortedRates) {
            String symbol = entry.getKey();
            BigDecimal rate = entry.getValue();
            Position position = positions.get(symbol);

            // P2 修复：跳过无效的异常费率数据
            if (rate.compareTo(MIN_VALID_RATE) < 0 || rate.compareTo(MAX_VALID_RATE) > 0) {
                log.warn("⚠️ {} 费率 {}% 异常，跳过", symbol,
                        rate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
                continue;
            }

            // 动态阈值：正费率0.06%，负费率0.08%（负费率风险更高）
            BigDecimal minRate = rate.compareTo(BigDecimal.ZERO) >= 0 
                    ? MIN_FUNDING_RATE_POSITIVE 
                    : MIN_FUNDING_RATE_NEGATIVE;

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

            // 【新增3: 净敞口检查 - 防止单边全空/全多
            String side = rate.compareTo(BigDecimal.ZERO) >= 0 ? "SHORT" : "LONG";
            if (!isExposureAcceptable(side)) {
                continue;
            }

            if (!position.hasPosition() && currentPositionsCount < maxPositions) {
                log.info("🎯 {} 费率 {}% 达标，准备开仓...",
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

                    if (toClose.isSwitchWorthIt(rate.abs(), SWITCH_THRESHOLD, FEE_PER_TRADE)) {
                        log.info("🔄 移仓：从 {} ({}%) 到 {} ({}%), 差值 {}% + 手续费划算",
                                toClose.getSymbol(),
                                toClose.getLastFundingRate().abs().multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                                symbol,
                                rate.abs().multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                                diff.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
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
            // 日报记录开仓
            dailyReporter.recordOpen(symbol, side, alignedQuantity, fundingRate);

            BigDecimal annualized = fundingRate.abs()
                    .multiply(new BigDecimal("1095"))
                    .multiply(new BigDecimal(Config.LEVERAGE))
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);

            log.info("");
            log.info("🎉 开仓完成！ {} {} ({}), 预计年化 {}%", symbol, sideName, alignedQuantity, annualized);
            log.info("");
            // 飞书推送：开仓通知
            feishuNotifier.sendOpenPosition(symbol, sideName, alignedQuantity, fundingRate, annualized);

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
            BigDecimal alignedQuantity = precision.alignQuantity(symbol, position.getPositionSize());

            String side = position.getPositionSide();
            String closeSide = "LONG".equals(side) ? "SELL" : "BUY";
            AtomicTransactionManager.TxResult result = txManager.atomicClosePosition(symbol, alignedQuantity, closeSide);
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

            // 计算平仓盈亏（浮盈浮亏实现化）
            BigDecimal closePnl = BigDecimal.ZERO;
            if (position.getUnrealizedPnl() != null) {
                closePnl = position.getUnrealizedPnl();
            }
            // 加上资金费收益
            BigDecimal totalReturn = position.getTotalFundingEarned().add(closePnl);

            log.info("💵 平仓结算: 资金费收益{} USDT, 买卖盈亏{} USDT, 合计{} USDT (持仓{}小时, {}次结算)",
                    position.getTotalFundingEarned().setScale(4, RoundingMode.HALF_UP),
                    closePnl.setScale(4, RoundingMode.HALF_UP),
                    totalReturn.setScale(4, RoundingMode.HALF_UP),
                    position.getHoldingHours(),
                    position.getFundingCount());

            // 记录盈亏用于熔断机制
            recordClosePnl(totalReturn);

            position.close();
            totalTrades++;

            // 日报记录平仓
            BigDecimal finalPnl = position.getUnrealizedPnl() != null ? position.getUnrealizedPnl() : BigDecimal.ZERO;
            dailyReporter.recordClose(symbol, position.getTotalFundingEarned(), finalPnl, "费率降低/止盈止损");

            log.info("");
            log.info("✅ 平仓完成！");
            log.info("");
            // 飞书推送：平仓通知
            feishuNotifier.sendClosePosition(symbol, position.getTotalFundingEarned(), finalPnl, "费率降低/止盈止损");

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
