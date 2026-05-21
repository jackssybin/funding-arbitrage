package com.quant;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataServiceTest {

    @Test
    void removesSymbolWhenPredictedFundingDisagreesWithCurrentDirection() throws Exception {
        FakeExchangeClient client = new FakeExchangeClient();
        MarketDataService service = new MarketDataService(client);

        client.currentRate = new BigDecimal("0.0010");
        client.predictedRate = new BigDecimal("0.0010");
        service.updateFundingRates(Collections.singletonList("BTCUSDT"));
        assertTrue(service.getFundingRates().containsKey("BTCUSDT"));

        client.predictedRate = new BigDecimal("-0.0010");
        service.updateFundingRates(Collections.singletonList("BTCUSDT"));
        assertFalse(service.getFundingRates().containsKey("BTCUSDT"));
    }

    @Test
    void rejectsFundingSpikeComparedWithRecentHistory() throws Exception {
        FakeExchangeClient client = new FakeExchangeClient();
        MarketDataService service = new MarketDataService(client);

        client.currentRate = new BigDecimal("0.0010");
        client.predictedRate = new BigDecimal("0.0010");
        for (int i = 0; i < 3; i++) {
            service.updateFundingRates(Collections.singletonList("BTCUSDT"));
        }
        assertTrue(service.getFundingRates().containsKey("BTCUSDT"));

        client.currentRate = new BigDecimal("0.0040");
        client.predictedRate = new BigDecimal("0.0040");
        service.updateFundingRates(Collections.singletonList("BTCUSDT"));
        assertFalse(service.getFundingRates().containsKey("BTCUSDT"));
    }

    @Test
    void fallsBackToSecondaryExchangeWhenPrimaryFundingSourceFails() throws Exception {
        FakeExchangeClient primary = new FakeExchangeClient();
        primary.failAllFundingRates = true;

        FakeExchangeClient secondary = new FakeExchangeClient();
        secondary.currentRate = new BigDecimal("0.0012");
        secondary.predictedRate = new BigDecimal("0.0012");

        MarketDataService service = new MarketDataService(Arrays.asList(primary, secondary));
        service.updateFundingRates(Collections.singletonList("BTCUSDT"));

        assertTrue(service.getFundingRates().containsKey("BTCUSDT"));
    }

    @Test
    void rejectsEntryWhenLiveBidAskSpreadIsTooWide() throws Exception {
        FakeExchangeClient client = new FakeExchangeClient();
        client.currentRate = new BigDecimal("0.0010");
        client.predictedRate = new BigDecimal("0.0010");
        client.snapshot = new MarketSnapshot("BTCUSDT",
                new BigDecimal("100"),
                new BigDecimal("101"),
                new BigDecimal("99"),
                new BigDecimal("1000"),
                new BigDecimal("99"),
                new BigDecimal("101"),
                BigDecimal.ZERO);

        MarketDataService service = new MarketDataService(client);
        service.updateFundingRates(Collections.singletonList("BTCUSDT"));
        service.update24hChanges(Collections.singletonList("BTCUSDT"));

        assertFalse(service.isMarketStateAcceptableForEntry("BTCUSDT", client.currentRate));
    }

    @Test
    void rejectsEntryWhenLiveMarketSnapshotIsIncomplete() throws Exception {
        FakeExchangeClient client = new FakeExchangeClient();
        client.currentRate = new BigDecimal("0.0010");
        client.predictedRate = new BigDecimal("0.0010");

        MarketDataService service = new MarketDataService(client);
        service.updateFundingRates(Collections.singletonList("BTCUSDT"));
        service.update24hChanges(Collections.singletonList("BTCUSDT"));

        assertFalse(service.isMarketStateAcceptableForEntry("BTCUSDT", client.currentRate));
    }

    private static class FakeExchangeClient implements ExchangeClient {
        BigDecimal currentRate = BigDecimal.ZERO;
        BigDecimal predictedRate = BigDecimal.ZERO;
        MarketSnapshot snapshot;
        boolean failAllFundingRates;

        @Override
        public Map<String, BigDecimal> getAllFundingRates(List<String> symbols) throws IOException {
            if (failAllFundingRates) {
                throw new IOException("funding source unavailable");
            }
            return Collections.singletonMap(symbols.get(0), currentRate);
        }

        @Override
        public BigDecimal getFundingRate(String symbol) {
            return currentRate;
        }

        @Override
        public BigDecimal getPredictedFundingRate(String symbol) {
            return predictedRate;
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
        public MarketSnapshot getMarketSnapshot(String symbol) {
            if (snapshot != null) {
                return snapshot;
            }
            return new MarketSnapshot(symbol, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                    BigDecimal.ZERO, null, null, BigDecimal.ZERO);
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
        public BigDecimal getPositionAmount(String symbol) {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal getBalance() {
            return BigDecimal.ZERO;
        }

        @Override
        public void updateSimulatedBalance(BigDecimal delta) {
        }

        @Override
        public BigDecimal getSimulatedBalance() {
            return BigDecimal.ZERO;
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
