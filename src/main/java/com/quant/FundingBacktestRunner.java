package com.quant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Simple CLI for long-period funding-arbitrage backtests.
 */
public class FundingBacktestRunner {

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            printUsage();
            return;
        }

        FundingBacktestDataLoader loader = new FundingBacktestDataLoader();
        List<FundingBacktestEngine.MarketBar> bars;
        if ("csv".equalsIgnoreCase(args[0])) {
            if (args.length < 3) {
                printUsage();
                return;
            }
            bars = loader.loadCsv(args[1], args[2]);
        } else if ("optimize-csv".equalsIgnoreCase(args[0])) {
            if (args.length < 3) {
                printUsage();
                return;
            }
            bars = loader.loadCsv(args[1], args[2]);
            printOptimization(bars);
            return;
        } else if ("binance".equalsIgnoreCase(args[0])) {
            if (args.length < 5) {
                printUsage();
                return;
            }
            bars = loader.loadBinanceHistory(
                    Arrays.asList(args[1].split(",")),
                    LocalDateTime.parse(args[2]),
                    LocalDateTime.parse(args[3]),
                    args[4]);
        } else {
            printUsage();
            return;
        }

        FundingBacktestEngine engine = new FundingBacktestEngine();
        FundingBacktestEngine.BacktestConfig config = new FundingBacktestEngine.BacktestConfig();
        FundingBacktestEngine.BacktestResult result = engine.run(bars, config);
        FundingBacktestEngine.WindowBacktestResult windows = engine.runRollingWindows(bars, config, 30, 7);
        SymbolEligibilityFilter filter = SymbolEligibilityFilter.fromConfig();
        SymbolEligibilityFilter.EligibilityReport eligibility = filter.evaluate(result);
        printResult(result, windows, eligibility);

        if (!eligibility.rejectedSymbols.isEmpty() && !eligibility.acceptedSymbols.isEmpty()) {
            List<FundingBacktestEngine.MarketBar> filteredBars = filter.filterBars(bars, eligibility);
            FundingBacktestEngine.BacktestResult filteredResult = engine.run(filteredBars, config);
            FundingBacktestEngine.WindowBacktestResult filteredWindows =
                    engine.runRollingWindows(filteredBars, config, 30, 7);
            System.out.println();
            System.out.println("=== Backtest After Symbol Eligibility Filter ===");
            printResult(filteredResult, filteredWindows, filter.evaluate(filteredResult));
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java ... com.quant.FundingBacktestRunner csv <klineCsv> <fundingCsv>");
        System.out.println("  java ... com.quant.FundingBacktestRunner optimize-csv <klineCsv> <fundingCsv>");
        System.out.println("  java ... com.quant.FundingBacktestRunner binance <BTCUSDT,ETHUSDT> <startISO> <endISO> <interval>");
        System.out.println("Examples:");
        System.out.println("  binance BTCUSDT,ETHUSDT 2025-01-01T00:00:00 2026-01-01T00:00:00 8h");
    }

    private static void printOptimization(List<FundingBacktestEngine.MarketBar> bars) {
        FundingParameterOptimizer optimizer = new FundingParameterOptimizer();
        List<FundingParameterOptimizer.OptimizationResult> results =
                optimizer.optimize(bars, FundingParameterOptimizer.defaultConfig());
        System.out.println("=== Parameter Optimization Top 10 ===");
        int limit = Math.min(10, results.size());
        for (int i = 0; i < limit; i++) {
            System.out.println("#" + (i + 1) + " " + results.get(i).summaryLine());
        }
        if (results.isEmpty()) {
            System.out.println("No parameter set passed symbol eligibility filters.");
        }
    }

    private static void printResult(FundingBacktestEngine.BacktestResult result,
                                    FundingBacktestEngine.WindowBacktestResult windows,
                                    SymbolEligibilityFilter.EligibilityReport eligibility) {
        System.out.println("=== Backtest Summary ===");
        System.out.println("totalPnl=" + money(result.totalPnl)
                + ", fundingIncome=" + money(result.fundingIncome)
                + ", tradingPnl=" + money(result.tradingPnl)
                + ", feeCost=" + money(result.feeCost)
                + ", slippageCost=" + money(result.slippageCost));
        System.out.println("maxDrawdown=" + money(result.maxDrawdown)
                + ", winRate=" + pct(result.winRate)
                + ", fundingIncomeRatio=" + pct(result.fundingIncomeRatio)
                + ", closedTrades=" + result.closedTrades
                + ", fundingEvents=" + result.fundingEvents
                + ", filteredTrades=" + result.filteredByMarketState
                + ", filteredHypotheticalPnl=" + money(result.filteredHypotheticalPnl)
                + ", filteredWinRate=" + pct(result.filteredWinRate));

        System.out.println("=== Symbol Stats ===");
        for (Map.Entry<String, FundingBacktestEngine.SymbolStats> entry : result.symbolStats.entrySet()) {
            FundingBacktestEngine.SymbolStats stats = entry.getValue();
            System.out.println(entry.getKey()
                    + ": totalPnl=" + money(stats.totalPnl)
                    + ", fundingIncome=" + money(stats.fundingIncome)
                    + ", tradingPnl=" + money(stats.tradingPnl)
                    + ", maxDrawdown=" + money(stats.maxDrawdown)
                    + ", winRate=" + pct(stats.winRate)
                    + ", fundingIncomeRatio=" + pct(stats.fundingIncomeRatio)
                    + ", closedTrades=" + stats.closedTrades
                    + ", filteredTrades=" + stats.filteredByMarketState
                    + ", filteredHypotheticalPnl=" + money(stats.filteredHypotheticalPnl));
            printFilterReasons("  ", stats.filteredByReason);
        }

        System.out.println("=== Market State Filter Attribution ===");
        printFilterReasons("", result.filteredByReason);

        System.out.println("=== Rolling Windows: 30d step 7d ===");
        System.out.println("windows=" + windows.windows.size()
                + ", profitable=" + windows.profitableWindows
                + ", losing=" + windows.losingWindows
                + ", profitWindowRate=" + pct(windows.profitWindowRate));
        if (windows.bestWindow != null && windows.worstWindow != null) {
            System.out.println("best=" + windows.bestWindow.start + " -> " + windows.bestWindow.end
                    + " pnl=" + money(windows.bestWindow.result.totalPnl));
            System.out.println("worst=" + windows.worstWindow.start + " -> " + windows.worstWindow.end
                    + " pnl=" + money(windows.worstWindow.result.totalPnl));
        }

        System.out.println("=== Symbol Eligibility ===");
        System.out.println("accepted=" + eligibility.acceptedSymbols);
        for (Map.Entry<String, List<String>> entry : eligibility.rejectedSymbols.entrySet()) {
            System.out.println("rejected " + entry.getKey() + ": " + entry.getValue());
        }
    }

    private static void printFilterReasons(String prefix,
                                           Map<String, FundingBacktestEngine.FilterReasonStats> reasons) {
        for (Map.Entry<String, FundingBacktestEngine.FilterReasonStats> entry : reasons.entrySet()) {
            FundingBacktestEngine.FilterReasonStats stats = entry.getValue();
            System.out.println(prefix + entry.getKey()
                    + ": filtered=" + stats.filteredCount
                    + ", hypotheticalPnl=" + money(stats.hypotheticalPnl)
                    + ", winRate=" + pct(stats.winRate)
                    + ", closed=" + stats.closedTrades);
        }
    }

    private static String money(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct(BigDecimal value) {
        return value.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
