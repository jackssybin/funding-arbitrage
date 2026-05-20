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
    void closePositionRefreshesPnlAndCloseFeeWithLatestPrice() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        FakeExchangeClient client = new FakeExchangeClient(false, new BigDecimal("10000"), new BigDecimal("110"));
        setField(bot, "exchangeClient", client);
        setField(bot, "precision", new ExchangePrecision(null));
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
    }

    @Test
    void closePositionUsesExecutionReportPriceAndFeeWhenAvailable() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        FakeExchangeClient client = new FakeExchangeClient(false, new BigDecimal("10000"), new BigDecimal("110"));
        client.executionReport = new TradeExecutionReport("BTCUSDT", "close-long");
        client.executionReport.addFill(new BigDecimal("108"), BigDecimal.ONE,
                new BigDecimal("0.040"), "USDT", BigDecimal.ZERO, "fill-1");
        setField(bot, "exchangeClient", client);
        setField(bot, "precision", new ExchangePrecision(null));
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

    @SuppressWarnings("unchecked")
    private Map<String, Position> positionsOf(FundingArbitrageBot bot) throws Exception {
        Field field = FundingArbitrageBot.class.getDeclaredField("positions");
        field.setAccessible(true);
        return (Map<String, Position>) field.get(bot);
    }

    private void setField(FundingArbitrageBot bot, String name, Object value) throws Exception {
        Field field = FundingArbitrageBot.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(bot, value);
    }

    private static class ClosingSmartOrderExecutor extends SmartOrderExecutor {
        ClosingSmartOrderExecutor(ExchangeClient client) {
            super(client, null);
        }

        @Override
        public String smartCloseLong(String symbol, BigDecimal totalQuantity) {
            return "close-long";
        }

        @Override
        public String smartCloseShort(String symbol, BigDecimal totalQuantity) {
            return "close-short";
        }
    }

    private static class FakeExchangeClient implements ExchangeClient {
        final boolean failBalance;
        final BigDecimal balance;
        final BigDecimal currentPrice;
        BigDecimal balanceDelta = BigDecimal.ZERO;
        TradeExecutionReport executionReport;

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
            return "close-short";
        }

        @Override
        public String closeLong(String symbol, BigDecimal quantity) {
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
        public boolean testConnection() {
            return true;
        }

        @Override
        public String getExchangeName() {
            return "fake";
        }
    }
}
