package com.quant;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectionExecutionTest {

    @Test
    void positiveFundingOpensShort() {
        RecordingSmartOrderExecutor executor = new RecordingSmartOrderExecutor();
        AtomicTransactionManager manager = new AtomicTransactionManager(new FakeExchangeClient(), executor);

        AtomicTransactionManager.TxResult result = manager.atomicOpenPosition(
                "BTCUSDT", new BigDecimal("0.01"), new BigDecimal("0.0010"));

        assertTrue(result.isSuccess());
        assertEquals("openShort", executor.lastCall);
    }

    @Test
    void negativeFundingOpensLong() {
        RecordingSmartOrderExecutor executor = new RecordingSmartOrderExecutor();
        AtomicTransactionManager manager = new AtomicTransactionManager(new FakeExchangeClient(), executor);

        AtomicTransactionManager.TxResult result = manager.atomicOpenPosition(
                "BTCUSDT", new BigDecimal("0.01"), new BigDecimal("-0.0015"));

        assertTrue(result.isSuccess());
        assertEquals("openLong", executor.lastCall);
    }

    @Test
    void longPositionClosesLong() {
        RecordingSmartOrderExecutor executor = new RecordingSmartOrderExecutor();
        AtomicTransactionManager manager = new AtomicTransactionManager(new FakeExchangeClient(), executor);

        AtomicTransactionManager.TxResult result = manager.atomicClosePosition(
                "BTCUSDT", new BigDecimal("0.01"), "LONG");

        assertTrue(result.isSuccess());
        assertEquals("closeLong", executor.lastCall);
    }

    @Test
    void shortPositionClosesShort() {
        RecordingSmartOrderExecutor executor = new RecordingSmartOrderExecutor();
        AtomicTransactionManager manager = new AtomicTransactionManager(new FakeExchangeClient(), executor);

        AtomicTransactionManager.TxResult result = manager.atomicClosePosition(
                "BTCUSDT", new BigDecimal("0.01"), "SHORT");

        assertTrue(result.isSuccess());
        assertEquals("closeShort", executor.lastCall);
    }

    @Test
    void smartExecutorDispatchesAllFourDirections() throws IOException {
        FakeExchangeClient client = new FakeExchangeClient();
        SmartOrderExecutor executor = new NonSimulationSmartOrderExecutor(client);

        executor.smartOpenShort("BTCUSDT", new BigDecimal("0.01"));
        assertEquals("openShort", client.lastCall);

        executor.smartOpenLong("BTCUSDT", new BigDecimal("0.01"));
        assertEquals("openLong", client.lastCall);

        executor.smartCloseShort("BTCUSDT", new BigDecimal("0.01"));
        assertEquals("closeShort", client.lastCall);

        executor.smartCloseLong("BTCUSDT", new BigDecimal("0.01"));
        assertEquals("closeLong", client.lastCall);
    }

    private static class RecordingSmartOrderExecutor extends SmartOrderExecutor {
        String lastCall;

        RecordingSmartOrderExecutor() {
            super(new FakeExchangeClient(), null);
        }

        @Override
        public String smartOpenShort(String symbol, BigDecimal totalQuantity) {
            lastCall = "openShort";
            return "order-open-short";
        }

        @Override
        public String smartCloseShort(String symbol, BigDecimal totalQuantity) {
            lastCall = "closeShort";
            return "order-close-short";
        }

        @Override
        public String smartOpenLong(String symbol, BigDecimal totalQuantity) {
            lastCall = "openLong";
            return "order-open-long";
        }

        @Override
        public String smartCloseLong(String symbol, BigDecimal totalQuantity) {
            lastCall = "closeLong";
            return "order-close-long";
        }
    }

    private static class NonSimulationSmartOrderExecutor extends SmartOrderExecutor {
        NonSimulationSmartOrderExecutor(ExchangeClient client) {
            super(client, null);
        }

        @Override
        protected boolean isSimulationMode() {
            return false;
        }
    }

    private static class FakeExchangeClient implements ExchangeClient {
        String lastCall;

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
            return BigDecimal.ONE;
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
            lastCall = "openShort";
            return "order-open-short";
        }

        @Override
        public String openLong(String symbol, BigDecimal quantity) {
            lastCall = "openLong";
            return "order-open-long";
        }

        @Override
        public String closeShort(String symbol, BigDecimal quantity) {
            lastCall = "closeShort";
            return "order-close-short";
        }

        @Override
        public String closeLong(String symbol, BigDecimal quantity) {
            lastCall = "closeLong";
            return "order-close-long";
        }

        @Override
        public BigDecimal getPositionAmount(String symbol) {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal getBalance() {
            return BigDecimal.ZERO;
        }

        @Override
        public String buySpot(String symbol, BigDecimal quantity) {
            return "spot-buy";
        }

        @Override
        public String sellSpot(String symbol, BigDecimal quantity) {
            return "spot-sell";
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
