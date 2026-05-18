package com.quant;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolEligibilityFilterTest {

    @Test
    void rejectsWeakSymbolsAndKeepsStableSymbols() {
        FundingBacktestEngine.BacktestResult result = new FundingBacktestEngine.BacktestResult();
        FundingBacktestEngine.SymbolStats btc = new FundingBacktestEngine.SymbolStats("BTCUSDT");
        btc.totalPnl = new BigDecimal("100");
        btc.maxDrawdown = new BigDecimal("50");
        btc.winRate = new BigDecimal("0.55");
        btc.closedTrades = 20;
        result.symbolStats.put("BTCUSDT", btc);

        FundingBacktestEngine.SymbolStats sol = new FundingBacktestEngine.SymbolStats("SOLUSDT");
        sol.totalPnl = new BigDecimal("-500");
        sol.maxDrawdown = new BigDecimal("900");
        sol.winRate = new BigDecimal("0.35");
        sol.closedTrades = 80;
        result.symbolStats.put("SOLUSDT", sol);

        SymbolEligibilityFilter filter = new SymbolEligibilityFilter(
                BigDecimal.ZERO,
                new BigDecimal("500"),
                new BigDecimal("0.40"),
                10,
                Collections.emptySet());

        SymbolEligibilityFilter.EligibilityReport report = filter.evaluate(result);

        assertEquals(Collections.singletonList("BTCUSDT"), report.acceptedSymbols);
        assertTrue(report.rejectedSymbols.containsKey("SOLUSDT"));
        assertTrue(report.rejectedSymbols.get("SOLUSDT").stream().anyMatch(r -> r.startsWith("totalPnl")));
        assertTrue(report.rejectedSymbols.get("SOLUSDT").stream().anyMatch(r -> r.startsWith("maxDrawdown")));
        assertTrue(report.rejectedSymbols.get("SOLUSDT").stream().anyMatch(r -> r.startsWith("winRate")));

        List<FundingBacktestEngine.MarketBar> filtered = filter.filterBars(java.util.Arrays.asList(
                new FundingBacktestEngine.MarketBar(java.time.LocalDateTime.parse("2026-01-01T00:00:00"),
                        "BTCUSDT", BigDecimal.ONE, BigDecimal.ZERO),
                new FundingBacktestEngine.MarketBar(java.time.LocalDateTime.parse("2026-01-01T00:00:00"),
                        "SOLUSDT", BigDecimal.ONE, BigDecimal.ZERO)
        ), report);

        assertEquals(1, filtered.size());
        assertEquals("BTCUSDT", filtered.get(0).symbol);
    }
}
