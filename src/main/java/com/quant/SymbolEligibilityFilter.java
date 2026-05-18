package com.quant;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Filters symbols whose historical backtest profile is too weak for live trading.
 */
public class SymbolEligibilityFilter {

    private final BigDecimal minTotalPnl;
    private final BigDecimal maxDrawdown;
    private final BigDecimal minWinRate;
    private final int minClosedTrades;
    private final Set<String> hardExcludedSymbols;

    public SymbolEligibilityFilter(BigDecimal minTotalPnl, BigDecimal maxDrawdown, BigDecimal minWinRate,
                                   int minClosedTrades, Set<String> hardExcludedSymbols) {
        this.minTotalPnl = minTotalPnl;
        this.maxDrawdown = maxDrawdown;
        this.minWinRate = minWinRate;
        this.minClosedTrades = minClosedTrades;
        this.hardExcludedSymbols = hardExcludedSymbols == null ? new HashSet<>() : new HashSet<>(hardExcludedSymbols);
    }

    public static SymbolEligibilityFilter fromConfig() {
        return new SymbolEligibilityFilter(
                Config.SYMBOL_MIN_BACKTEST_TOTAL_PNL,
                Config.SYMBOL_MAX_BACKTEST_DRAWDOWN_USDT,
                Config.SYMBOL_MIN_BACKTEST_WIN_RATE,
                Config.SYMBOL_MIN_BACKTEST_CLOSED_TRADES,
                Config.EXCLUDED_SYMBOLS);
    }

    public EligibilityReport evaluate(FundingBacktestEngine.BacktestResult result) {
        EligibilityReport report = new EligibilityReport();
        for (Map.Entry<String, FundingBacktestEngine.SymbolStats> entry : result.symbolStats.entrySet()) {
            String symbol = entry.getKey();
            FundingBacktestEngine.SymbolStats stats = entry.getValue();
            List<String> reasons = rejectionReasons(symbol, stats);
            if (reasons.isEmpty()) {
                report.acceptedSymbols.add(symbol);
            } else {
                report.rejectedSymbols.put(symbol, reasons);
            }
        }
        return report;
    }

    public List<FundingBacktestEngine.MarketBar> filterBars(List<FundingBacktestEngine.MarketBar> bars,
                                                            EligibilityReport report) {
        Set<String> accepted = new HashSet<>(report.acceptedSymbols);
        List<FundingBacktestEngine.MarketBar> filtered = new ArrayList<>();
        for (FundingBacktestEngine.MarketBar bar : bars) {
            if (accepted.contains(bar.symbol)) {
                filtered.add(bar);
            }
        }
        return filtered;
    }

    private List<String> rejectionReasons(String symbol, FundingBacktestEngine.SymbolStats stats) {
        List<String> reasons = new ArrayList<>();
        if (hardExcludedSymbols.contains(symbol.toUpperCase())) {
            reasons.add("hard-excluded");
        }
        if (stats.closedTrades < minClosedTrades) {
            reasons.add("closedTrades " + stats.closedTrades + " < " + minClosedTrades);
        }
        if (stats.totalPnl.compareTo(minTotalPnl) < 0) {
            reasons.add("totalPnl " + stats.totalPnl + " < " + minTotalPnl);
        }
        if (stats.maxDrawdown.compareTo(maxDrawdown) > 0) {
            reasons.add("maxDrawdown " + stats.maxDrawdown + " > " + maxDrawdown);
        }
        if (stats.winRate.compareTo(minWinRate) < 0) {
            reasons.add("winRate " + stats.winRate + " < " + minWinRate);
        }
        return reasons;
    }

    public static class EligibilityReport {
        public final List<String> acceptedSymbols = new ArrayList<>();
        public final Map<String, List<String>> rejectedSymbols = new LinkedHashMap<>();
    }
}
