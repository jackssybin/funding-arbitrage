package com.quant;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FundingBacktestEngineTest {

    @Test
    void stopLossUsesMarginBasisLikeLiveTrading() {
        FundingBacktestEngine engine = new FundingBacktestEngine();
        FundingBacktestEngine.BacktestConfig config = new FundingBacktestEngine.BacktestConfig();
        config.notional = new BigDecimal("1000");
        config.openRate = new BigDecimal("0.0010");
        config.closeRate = new BigDecimal("0.0001");
        config.takerFeeRate = BigDecimal.ZERO;
        config.slippageRate = BigDecimal.ZERO;
        config.stopLossRatio = new BigDecimal("0.05");
        config.leverage = 3;

        FundingBacktestEngine.BacktestResult result = engine.run(Arrays.asList(
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-01T00:00:00"), "BTCUSDT", new BigDecimal("100"), new BigDecimal("0.0010")),
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-01T08:00:00"), "BTCUSDT", new BigDecimal("102"), new BigDecimal("0.0010")),
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-01T16:00:00"), "BTCUSDT", new BigDecimal("100"), BigDecimal.ZERO)
        ), config);

        assertTrue(result.totalPnl.compareTo(BigDecimal.ZERO) < 0);
        assertEquals(0, result.fundingIncome.compareTo(new BigDecimal("1.000000")));
        assertEquals(0, result.tradingPnl.compareTo(new BigDecimal("-20.000000")));
        assertEquals(1, result.closedTrades);
        assertEquals(0, result.winRate.compareTo(new BigDecimal("0.000000")));
        assertTrue(result.symbolStats.containsKey("BTCUSDT"));
    }

    @Test
    void reportsCostsWinRateFundingRatioAndSymbolStats() {
        FundingBacktestEngine engine = new FundingBacktestEngine();
        FundingBacktestEngine.BacktestConfig config = new FundingBacktestEngine.BacktestConfig();
        config.notional = new BigDecimal("1000");
        config.openRate = new BigDecimal("0.0010");
        config.closeRate = new BigDecimal("0.0001");
        config.takerFeeRate = new BigDecimal("0.0010");
        config.slippageRate = new BigDecimal("0.0005");
        config.stopLossRatio = new BigDecimal("0.50");
        config.leverage = 3;

        FundingBacktestEngine.BacktestResult result = engine.run(Arrays.asList(
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-01T00:00:00"), "BTCUSDT", new BigDecimal("100"), new BigDecimal("0.0010")),
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-01T08:00:00"), "BTCUSDT", new BigDecimal("99"), BigDecimal.ZERO),
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-01T16:00:00"), "ETHUSDT", new BigDecimal("2000"), new BigDecimal("-0.0012")),
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-02T00:00:00"), "ETHUSDT", new BigDecimal("2100"), BigDecimal.ZERO)
        ), config);

        assertEquals(2, result.closedTrades);
        assertEquals(2, result.winningTrades);
        assertEquals(0, result.winRate.compareTo(new BigDecimal("1.000000")));
        assertEquals(0, result.feeCost.compareTo(new BigDecimal("4.000000")));
        assertEquals(0, result.slippageCost.compareTo(new BigDecimal("2.000000")));
        assertEquals(0, result.fundingIncome.compareTo(new BigDecimal("0.000000")));
        assertEquals(0, result.symbolStats.get("BTCUSDT").totalPnl.compareTo(new BigDecimal("7.000000")));
        assertEquals(0, result.symbolStats.get("ETHUSDT").totalPnl.compareTo(new BigDecimal("47.000000")));
        assertEquals(0, result.fundingIncomeRatio.compareTo(BigDecimal.ZERO));
    }

    @Test
    void marketStateFilterRejectsWideHighLowRangeLikeLiveFilter() {
        FundingBacktestEngine engine = new FundingBacktestEngine();
        FundingBacktestEngine.BacktestConfig config = new FundingBacktestEngine.BacktestConfig();
        config.marketStateFilterEnabled = true;
        config.marketStateLookbackBars = 1;
        config.openRate = new BigDecimal("0.0010");
        config.maxAtrRatio = new BigDecimal("1");
        config.maxHighLowRangeRatio = new BigDecimal("0.05");

        FundingBacktestEngine.BacktestResult result = engine.run(Arrays.asList(
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-01T00:00:00"),
                        "BTCUSDT", new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                        BigDecimal.ZERO, null, new BigDecimal("0.0001"), null, true),
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-01T08:00:00"),
                        "BTCUSDT", new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"),
                        BigDecimal.ZERO, null, new BigDecimal("0.0010"), null, true)
        ), config);

        assertEquals(1, result.filteredByMarketState);
        assertTrue(result.filteredByReason.containsKey("HIGH_LOW_RANGE"));
    }
}
