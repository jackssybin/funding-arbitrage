package com.quant;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotStateAndRiskTest {

    @Test
    void restoresPersistedPositionState() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        Map<String, Position> positions = positionsOf(bot);
        positions.put("BTCUSDT", new Position("BTCUSDT"));

        StrategyPersistence.StrategyState state = new StrategyPersistence.StrategyState();
        StrategyPersistence.PositionState ps = new StrategyPersistence.PositionState();
        ps.symbol = "BTCUSDT";
        ps.positionSide = "SHORT";
        ps.positionSize = 2.0;
        ps.entryPrice = 50000.0;
        ps.lastFundingRate = 0.001;
        ps.entryTime = LocalDateTime.now().minusHours(9).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        ps.fundingCount = 3;
        ps.totalFundingEarned = 42.5;
        state.positions.add(ps);

        Method restore = FundingArbitrageBot.class.getDeclaredMethod("restorePositions", StrategyPersistence.StrategyState.class);
        restore.setAccessible(true);
        restore.invoke(bot, state);

        Position restored = positions.get("BTCUSDT");
        assertTrue(restored.hasPosition());
        assertEquals("SHORT", restored.getPositionSide());
        assertEquals(3, restored.getFundingCount());
        assertEquals(0, restored.getTotalFundingEarned().compareTo(new BigDecimal("42.5")));
        assertTrue(restored.getHoldingHours() >= 8);
    }

    @Test
    void restoresPersistedHedgedPositionState() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        Map<String, Position> positions = positionsOf(bot);
        positions.put("BTCUSDT", new Position("BTCUSDT"));

        StrategyPersistence.StrategyState state = new StrategyPersistence.StrategyState();
        StrategyPersistence.PositionState ps = new StrategyPersistence.PositionState();
        ps.symbol = "BTCUSDT";
        ps.positionSide = "SHORT";
        ps.positionSize = 2.0;
        ps.entryPrice = 50000.0;
        ps.lastFundingRate = 0.001;
        ps.entryTime = LocalDateTime.now().minusHours(9).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        ps.fundingCount = 3;
        ps.totalFundingEarned = 42.5;
        ps.hedged = true;
        ps.spotPositionSize = 1.5;
        ps.spotEntryPrice = 49900.0;
        ps.hedgeRatio = 0.75;
        state.positions.add(ps);

        Method restore = FundingArbitrageBot.class.getDeclaredMethod("restorePositions", StrategyPersistence.StrategyState.class);
        restore.setAccessible(true);
        restore.invoke(bot, state);

        Position restored = positions.get("BTCUSDT");
        assertTrue(restored.hasPosition());
        assertTrue(restored.isHedged());
        assertEquals(0, restored.getSpotPositionSize().compareTo(new BigDecimal("1.5")));
        assertEquals(0, restored.getSpotEntryPrice().compareTo(new BigDecimal("49900.0")));
        assertEquals(0, restored.getHedgeRatio().compareTo(new BigDecimal("0.75")));
    }

    @Test
    void acceptsOppositeSideWhenItReducesNetExposure() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        Map<String, Position> positions = positionsOf(bot);

        Position shortPosition = new Position("BTCUSDT");
        BigDecimal existingNotional = Config.POSITION_VALUE_USDT.multiply(BigDecimal.valueOf(Config.LEVERAGE));
        shortPosition.restore(
                BigDecimal.ONE,
                existingNotional,
                new BigDecimal("0.001"),
                "SHORT",
                LocalDateTime.now().minusHours(8),
                0,
                BigDecimal.ZERO
        );
        positions.put("BTCUSDT", shortPosition);

        Method exposureCheck = FundingArbitrageBot.class.getDeclaredMethod("isExposureAcceptable", String.class);
        exposureCheck.setAccessible(true);

        assertTrue((Boolean) exposureCheck.invoke(bot, "LONG"));
    }

    @Test
    void rejectsNewExposureWhenBalanceCannotBeRead() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        setField(bot, "exchangeClient", new FakeExchangeClient(true, new BigDecimal("10000"), new BigDecimal("100")));

        Method exposureCheck = FundingArbitrageBot.class.getDeclaredMethod("isExposureAcceptable", String.class);
        exposureCheck.setAccessible(true);

        assertFalse((Boolean) exposureCheck.invoke(bot, "LONG"));
    }

    @Test
    void rejectsSpotHedgeEntryWhenSpotBalanceCannotBeRead() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        FakeExchangeClient client = new FakeExchangeClient(false, new BigDecimal("10000"), new BigDecimal("100"));
        client.failSpotBalance = true;
        setField(bot, "exchangeClient", client);

        Method spotFundsCheck = FundingArbitrageBot.class.getDeclaredMethod("isSpotFundsSufficient");
        spotFundsCheck.setAccessible(true);

        assertFalse((Boolean) spotFundsCheck.invoke(bot));
    }

    @Test
    void compensatesFuturesLegWhenSpotHedgeOpenFails() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        FakeExchangeClient client = new FakeExchangeClient(false, new BigDecimal("10000"), new BigDecimal("100"));
        setField(bot, "exchangeClient", client);
        setField(bot, "txManager", new AtomicTransactionManager(client, new ClosingSmartOrderExecutor(client)));

        Method compensate = FundingArbitrageBot.class.getDeclaredMethod(
                "compensateFuturesOpen", String.class, BigDecimal.class, String.class);
        compensate.setAccessible(true);

        assertTrue((Boolean) compensate.invoke(bot, "BTCUSDT", BigDecimal.ONE, "SHORT"));
        assertEquals(1, client.closeShortCount);
    }

    @Test
    void openPositionRecordsOpenCostInTotalPnlWithoutCountingTradeLoss() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        FakeExchangeClient client = new FakeExchangeClient(false, new BigDecimal("10000"), new BigDecimal("100"));
        setField(bot, "exchangeClient", client);
        setField(bot, "precision", new ExchangePrecision((BinanceFuturesClient) null));
        setField(bot, "txManager", new AtomicTransactionManager(client, new ClosingSmartOrderExecutor(client)));
        setField(bot, "feishuNotifier", new FeishuNotifier());
        setField(bot, "dailyReporter", new DailyReporter());
        positionsOf(bot).put("BTCUSDT", new Position("BTCUSDT"));

        Method open = FundingArbitrageBot.class.getDeclaredMethod("openPosition", String.class, BigDecimal.class);
        open.setAccessible(true);

        assertTrue((Boolean) open.invoke(bot, "BTCUSDT", new BigDecimal("0.0100")));
        assertEquals(0, client.balanceDelta.compareTo(new BigDecimal("-0.5000000000")));
        assertEquals(0, bot.getTotalPnl().compareTo(new BigDecimal("-0.5000000000")));
        assertEquals(0, getField(bot, "consecutiveLosses"));
    }

    @Test
    void closePositionRefreshesPnlAndCloseFeeWithLatestPrice() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        FakeExchangeClient client = new FakeExchangeClient(false, new BigDecimal("10000"), new BigDecimal("110"));
        setField(bot, "exchangeClient", client);
        setField(bot, "precision", new ExchangePrecision((BinanceFuturesClient) null));
        setField(bot, "txManager", new AtomicTransactionManager(client, new ClosingSmartOrderExecutor(client)));
        setField(bot, "feishuNotifier", new FeishuNotifier());
        setField(bot, "dailyReporter", new DailyReporter());

        Map<String, Position> positions = positionsOf(bot);
        Position position = new Position("BTCUSDT");
        position.restore(BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("-0.0010"),
                "LONG", LocalDateTime.now().minusHours(8), 0, BigDecimal.ZERO);
        positions.put("BTCUSDT", position);

        Method close = FundingArbitrageBot.class.getDeclaredMethod("closePosition", String.class, String.class);
        close.setAccessible(true);
        close.invoke(bot, "BTCUSDT", "test-close");

        assertEquals(0, client.balanceDelta.compareTo(new BigDecimal("9.945000000")));
        assertEquals(0, bot.getTotalPnl().compareTo(new BigDecimal("9.945000000")));
    }

    @Test
    void closePositionUsesExecutionReportPriceAndFeeWhenAvailable() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        FakeExchangeClient client = new FakeExchangeClient(false, new BigDecimal("10000"), new BigDecimal("110"));
        client.executionReport = new TradeExecutionReport("BTCUSDT", "close-long");
        client.executionReport.addFill(new BigDecimal("108"), BigDecimal.ONE,
                new BigDecimal("0.040"), "USDT", BigDecimal.ZERO, "fill-1");
        setField(bot, "exchangeClient", client);
        setField(bot, "precision", new ExchangePrecision((BinanceFuturesClient) null));
        setField(bot, "txManager", new AtomicTransactionManager(client, new ClosingSmartOrderExecutor(client)));
        setField(bot, "feishuNotifier", new FeishuNotifier());
        setField(bot, "dailyReporter", new DailyReporter());

        Map<String, Position> positions = positionsOf(bot);
        Position position = new Position("BTCUSDT");
        position.restore(BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("-0.0010"),
                "LONG", LocalDateTime.now().minusHours(8), 0, BigDecimal.ZERO);
        positions.put("BTCUSDT", position);

        Method close = FundingArbitrageBot.class.getDeclaredMethod("closePosition", String.class, String.class);
        close.setAccessible(true);
        close.invoke(bot, "BTCUSDT", "test-close");

        assertEquals(0, client.balanceDelta.compareTo(new BigDecimal("7.960")));
    }

    @Test
    void hedgedCloseKeepsSpotOnlyStateWhenSpotCloseFailsAfterFuturesClose() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        FakeExchangeClient client = new FakeExchangeClient(false, new BigDecimal("10000"), new BigDecimal("110"));
        client.failSpotClose = true;
        setField(bot, "exchangeClient", client);
        setField(bot, "precision", new ExchangePrecision((BinanceFuturesClient) null));
        setField(bot, "txManager", new AtomicTransactionManager(client, new ClosingSmartOrderExecutor(client)));
        setField(bot, "feishuNotifier", new FeishuNotifier());
        setField(bot, "dailyReporter", new DailyReporter());

        Map<String, Position> positions = positionsOf(bot);
        Position position = new Position("BTCUSDT");
        position.restoreWithHedge(BigDecimal.ONE, new BigDecimal("100"),
                BigDecimal.ONE, new BigDecimal("100"),
                new BigDecimal("0.0010"), "SHORT",
                LocalDateTime.now().minusHours(8), 0, BigDecimal.ZERO, BigDecimal.ONE);
        positions.put("BTCUSDT", position);

        Method close = FundingArbitrageBot.class.getDeclaredMethod("closePosition", String.class, String.class);
        close.setAccessible(true);
        close.invoke(bot, "BTCUSDT", "test-close");

        assertTrue(position.hasPosition());
        assertTrue(position.isHedged());
        assertEquals(0, position.getPositionSize().compareTo(BigDecimal.ZERO));
        assertTrue(position.isSpotOnlyAfterContractClose());

        int closeShortCountAfterContractClose = client.closeShortCount;
        client.failSpotClose = false;
        close.invoke(bot, "BTCUSDT", "retry-spot-only-close");

        assertFalse(position.hasPosition());
        assertEquals(closeShortCountAfterContractClose, client.closeShortCount);
    }

    @Test
    void spotOnlyRemainderCloseBypassesMinimumHoldingTime() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        FakeExchangeClient client = new FakeExchangeClient(false, new BigDecimal("10000"), new BigDecimal("110"));
        setField(bot, "exchangeClient", client);
        setField(bot, "precision", new ExchangePrecision((BinanceFuturesClient) null));
        setField(bot, "txManager", new AtomicTransactionManager(client, new ClosingSmartOrderExecutor(client)));
        setField(bot, "feishuNotifier", new FeishuNotifier());
        setField(bot, "dailyReporter", new DailyReporter());

        Position position = new Position("BTCUSDT");
        position.restoreWithHedge(BigDecimal.ZERO, new BigDecimal("100"),
                BigDecimal.ONE, new BigDecimal("100"),
                new BigDecimal("0.0010"), "SHORT",
                LocalDateTime.now(), 0, BigDecimal.ZERO, BigDecimal.ONE);
        positionsOf(bot).put("BTCUSDT", position);

        Method close = FundingArbitrageBot.class.getDeclaredMethod("closePosition", String.class, String.class);
        close.setAccessible(true);
        close.invoke(bot, "BTCUSDT", "manual-close");

        assertFalse(position.hasPosition());
        assertEquals(0, client.closeShortCount);
        assertEquals(0, bot.getTotalPnl().compareTo(new BigDecimal("9.890000000")));
    }

    @Test
    void rateHistorySamplesOnlyOncePerFundingSnapshot() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        MarketDataService marketData = new MarketDataService(new FakeExchangeClient(false, new BigDecimal("10000"), new BigDecimal("100")));
        setField(bot, "marketDataService", marketData);

        Position position = new Position("BTCUSDT");
        Method sample = FundingArbitrageBot.class.getDeclaredMethod(
                "addRateHistoryOncePerSnapshot", Position.class, BigDecimal.class);
        sample.setAccessible(true);

        LocalDateTime firstSnapshot = LocalDateTime.now();
        setObjectField(marketData, "lastRateUpdate", firstSnapshot);

        assertTrue((Boolean) sample.invoke(bot, position, new BigDecimal("0.0010")));
        assertFalse((Boolean) sample.invoke(bot, position, new BigDecimal("0.0010")));
        assertEquals(1, position.getRateHistorySize());

        setObjectField(marketData, "lastRateUpdate", firstSnapshot.plusMinutes(30));
        assertTrue((Boolean) sample.invoke(bot, position, new BigDecimal("0.0010")));
        assertEquals(2, position.getRateHistorySize());
    }

    @Test
    void hedgedPositionClosesWhenHedgeDeviationExceedsThreshold() throws Exception {
        FundingArbitrageBot bot = botWithClosingClient(new BigDecimal("100"));
        FakeExchangeClient client = (FakeExchangeClient) getField(bot, "exchangeClient");

        Position position = new Position("BTCUSDT");
        position.restoreWithHedge(BigDecimal.ONE, new BigDecimal("100"),
                new BigDecimal("0.5"), new BigDecimal("100"),
                new BigDecimal("0.0010"), "SHORT",
                LocalDateTime.now().minusHours(8), 0, BigDecimal.ZERO, BigDecimal.ONE);
        positionsOf(bot).put("BTCUSDT", position);
        fundingRatesOf(bot).put("BTCUSDT", new BigDecimal("0.0010"));

        invokeExecuteStrategy(bot);

        assertFalse(position.hasPosition());
        assertEquals(1, client.closeShortCount);
    }

    @Test
    void hedgedPositionClosesWhenCombinedHedgeLossExceedsStopLoss() throws Exception {
        FundingArbitrageBot bot = botWithClosingClient(new BigDecimal("200"));
        FakeExchangeClient client = (FakeExchangeClient) getField(bot, "exchangeClient");

        Position position = new Position("BTCUSDT");
        position.restoreWithHedge(BigDecimal.ONE, new BigDecimal("100"),
                new BigDecimal("0.9"), new BigDecimal("100"),
                new BigDecimal("0.0010"), "SHORT",
                LocalDateTime.now().minusHours(8), 0, BigDecimal.ZERO, BigDecimal.ONE);
        positionsOf(bot).put("BTCUSDT", position);
        fundingRatesOf(bot).put("BTCUSDT", new BigDecimal("0.0010"));

        invokeExecuteStrategy(bot);

        assertFalse(position.hasPosition());
        assertEquals(1, client.closeShortCount);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Position> positionsOf(FundingArbitrageBot bot) throws Exception {
        Field field = FundingArbitrageBot.class.getDeclaredField("positions");
        field.setAccessible(true);
        return (Map<String, Position>) field.get(bot);
    }

    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> fundingRatesOf(FundingArbitrageBot bot) throws Exception {
        Field field = FundingArbitrageBot.class.getDeclaredField("fundingRates");
        field.setAccessible(true);
        return (Map<String, BigDecimal>) field.get(bot);
    }

    private void setField(FundingArbitrageBot bot, String name, Object value) throws Exception {
        Field field = FundingArbitrageBot.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(bot, value);
    }

    private void setObjectField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object getField(FundingArbitrageBot bot, String name) throws Exception {
        Field field = FundingArbitrageBot.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(bot);
    }

    private FundingArbitrageBot botWithClosingClient(BigDecimal currentPrice) throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        FakeExchangeClient client = new FakeExchangeClient(false, new BigDecimal("10000"), currentPrice);
        setField(bot, "exchangeClient", client);
        setField(bot, "precision", new ExchangePrecision((BinanceFuturesClient) null));
        setField(bot, "txManager", new AtomicTransactionManager(client, new ClosingSmartOrderExecutor(client)));
        setField(bot, "feishuNotifier", new FeishuNotifier());
        setField(bot, "dailyReporter", new DailyReporter());
        return bot;
    }

    private void invokeExecuteStrategy(FundingArbitrageBot bot) throws Exception {
        Method execute = FundingArbitrageBot.class.getDeclaredMethod("executeStrategy");
        execute.setAccessible(true);
        execute.invoke(bot);
    }

    private static class ClosingSmartOrderExecutor extends SmartOrderExecutor {
        private final ExchangeClient client;

        ClosingSmartOrderExecutor(ExchangeClient client) {
            super(client, null);
            this.client = client;
        }

        @Override
        public String smartCloseLong(String symbol, BigDecimal totalQuantity) throws IOException {
            return client.closeLong(symbol, totalQuantity);
        }

        @Override
        public String smartCloseShort(String symbol, BigDecimal totalQuantity) throws IOException {
            return client.closeShort(symbol, totalQuantity);
        }
    }

    private static class FakeExchangeClient implements ExchangeClient {
        final boolean failBalance;
        final BigDecimal balance;
        final BigDecimal currentPrice;
        BigDecimal balanceDelta = BigDecimal.ZERO;
        TradeExecutionReport executionReport;
        boolean failSpotClose;
        boolean failSpotBalance;
        int closeShortCount;
        int closeLongCount;

        FakeExchangeClient(boolean failBalance, BigDecimal balance, BigDecimal currentPrice) {
            this.failBalance = failBalance;
            this.balance = balance;
            this.currentPrice = currentPrice;
        }

        @Override
        public Map<String, BigDecimal> getAllFundingRates(List<String> symbols) {
            return Collections.emptyMap();
        }

        @Override
        public BigDecimal getFundingRate(String symbol) {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal getCurrentPrice(String symbol) {
            return currentPrice;
        }

        @Override
        public BigDecimal get24hChange(String symbol) {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal getCurrentPosition(String symbol) {
            return BigDecimal.ZERO;
        }

        @Override
        public void setLeverage(String symbol, int leverage) {
        }

        @Override
        public String openShort(String symbol, BigDecimal quantity) {
            return "open-short";
        }

        @Override
        public String openLong(String symbol, BigDecimal quantity) {
            return "open-long";
        }

        @Override
        public String closeShort(String symbol, BigDecimal quantity) {
            closeShortCount++;
            return "close-short";
        }

        @Override
        public String closeLong(String symbol, BigDecimal quantity) {
            closeLongCount++;
            return "close-long";
        }

        @Override
        public TradeExecutionReport getTradeExecutionReport(String symbol, String orderIds, BigDecimal fallbackQuantity,
                                                            BigDecimal fallbackPrice) throws IOException {
            if (executionReport != null) {
                return executionReport;
            }
            return ExchangeClient.super.getTradeExecutionReport(symbol, orderIds, fallbackQuantity, fallbackPrice);
        }

        @Override
        public BigDecimal getPositionAmount(String symbol) {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal getBalance() throws IOException {
            if (failBalance) {
                throw new IOException("balance unavailable");
            }
            return balance;
        }

        @Override
        public BigDecimal getSpotBalance() throws IOException {
            if (failSpotBalance) {
                throw new IOException("spot balance unavailable");
            }
            return balance;
        }

        @Override
        public void updateSimulatedBalance(BigDecimal delta) {
            balanceDelta = balanceDelta.add(delta);
        }

        @Override
        public BigDecimal getSimulatedBalance() {
            return balance.add(balanceDelta);
        }

        @Override
        public String buySpot(String symbol, BigDecimal quantity) {
            return "buy-spot";
        }

        @Override
        public String sellSpot(String symbol, BigDecimal quantity) {
            return "sell-spot";
        }

        @Override
        public TradeExecutionReport closeSpotPosition(String symbol, BigDecimal quantity) throws IOException {
            if (failSpotClose) {
                throw new IOException("spot close unavailable");
            }
            TradeExecutionReport report = new TradeExecutionReport(symbol, "spot-close");
            report.addFill(currentPrice, quantity, currentPrice.multiply(quantity).multiply(Config.SPOT_TAKER_FEE_RATE),
                    "USDT", BigDecimal.ZERO, "spot-fill");
            return report;
        }

        @Override
        public boolean testConnection() {
            return true;
        }

        @Override
        public String getExchangeName() {
            return "fake";
        }
    }
}
