package com.quant;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FundingParameterOptimizerTest {

    @Test
    void scansParametersAndSortsByWindowStability() {
        List<FundingBacktestEngine.MarketBar> bars = Arrays.asList(
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-01T00:00:00"), "BTCUSDT", new BigDecimal("100"), new BigDecimal("0.0010")),
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-01T08:00:00"), "BTCUSDT", new BigDecimal("99"), new BigDecimal("0.0010")),
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-02T00:00:00"), "BTCUSDT", new BigDecimal("100"), new BigDecimal("0.0010")),
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-02T08:00:00"), "BTCUSDT", new BigDecimal("101"), BigDecimal.ZERO),
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-03T00:00:00"), "SOLUSDT", new BigDecimal("100"), new BigDecimal("0.0010")),
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-03T08:00:00"), "SOLUSDT", new BigDecimal("120"), BigDecimal.ZERO)
        );

        FundingParameterOptimizer.OptimizationConfig config = new FundingParameterOptimizer.OptimizationConfig();
        config.openRates = Arrays.asList(new BigDecimal("0.0005"), new BigDecimal("0.0010"));
        config.closeRates = Collections.singletonList(new BigDecimal("0.0001"));
        config.slippageRates = Collections.singletonList(BigDecimal.ZERO);
        config.symbolMaxDrawdowns = Collections.singletonList(new BigDecimal("500"));
        config.maxLookbackReturns = Collections.singletonList(new BigDecimal("0.50"));
        config.maxStepReturns = Collections.singletonList(new BigDecimal("0.50"));
        config.maxAdverseTrendReturns = Collections.singletonList(new BigDecimal("0.50"));
        config.symbolMinTotalPnl = new BigDecimal("-1000");
        config.symbolMinWinRate = BigDecimal.ZERO;
        config.symbolMinClosedTrades = 1;
        config.windowDays = 1;
        config.stepDays = 1;

        List<FundingParameterOptimizer.OptimizationResult> results =
                new FundingParameterOptimizer().optimize(bars, config);

        assertFalse(results.isEmpty());
        assertFalse(results.get(0).eligibility.acceptedSymbols.isEmpty());
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).windows.profitWindowRate
                    .compareTo(results.get(i).windows.profitWindowRate) >= 0);
        }
    }
}
