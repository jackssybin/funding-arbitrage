package com.quant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Grid-search optimizer for funding-arbitrage backtest parameters.
 */
public class FundingParameterOptimizer {

    private final FundingBacktestEngine engine;

    public FundingParameterOptimizer() {
        this.engine = new FundingBacktestEngine();
    }

    public List<OptimizationResult> optimize(List<FundingBacktestEngine.MarketBar> bars,
                                             OptimizationConfig config) {
        List<OptimizationResult> results = new ArrayList<>();
        for (BigDecimal openRate : config.openRates) {
            for (BigDecimal closeRate : config.closeRates) {
                if (closeRate.compareTo(openRate) >= 0) {
                    continue;
                }
                for (BigDecimal slippageRate : config.slippageRates) {
                    for (BigDecimal symbolMaxDrawdown : config.symbolMaxDrawdowns) {
                        for (BigDecimal maxLookbackReturn : config.maxLookbackReturns) {
                            for (BigDecimal maxStepReturn : config.maxStepReturns) {
                                for (BigDecimal maxAdverseTrendReturn : config.maxAdverseTrendReturns) {
                                    FundingBacktestEngine.BacktestConfig backtestConfig = new FundingBacktestEngine.BacktestConfig();
                                    backtestConfig.openRate = openRate;
                                    backtestConfig.closeRate = closeRate;
                                    backtestConfig.slippageRate = slippageRate;
                                    backtestConfig.marketStateFilterEnabled = true;
                                    backtestConfig.marketStateLookbackBars = config.marketStateLookbackBars;
                                    backtestConfig.maxLookbackReturn = maxLookbackReturn;
                                    backtestConfig.maxStepReturn = maxStepReturn;
                                    backtestConfig.maxAdverseTrendReturn = maxAdverseTrendReturn;

                                    FundingBacktestEngine.BacktestResult rawResult = engine.run(bars, backtestConfig);
                                    SymbolEligibilityFilter filter = new SymbolEligibilityFilter(
                                            config.symbolMinTotalPnl,
                                            symbolMaxDrawdown,
                                            config.symbolMinWinRate,
                                            config.symbolMinClosedTrades,
                                            config.hardExcludedSymbols);
                                    SymbolEligibilityFilter.EligibilityReport eligibility = filter.evaluate(rawResult);
                                    if (eligibility.acceptedSymbols.isEmpty()) {
                                        continue;
                                    }

                                    List<FundingBacktestEngine.MarketBar> filteredBars = filter.filterBars(bars, eligibility);
                                    FundingBacktestEngine.BacktestResult filteredResult = engine.run(filteredBars, backtestConfig);
                                    FundingBacktestEngine.WindowBacktestResult windows =
                                            engine.runRollingWindows(filteredBars, backtestConfig, config.windowDays, config.stepDays);
                                    results.add(new OptimizationResult(
                                            openRate, closeRate, slippageRate, symbolMaxDrawdown,
                                            maxLookbackReturn, maxStepReturn, maxAdverseTrendReturn,
                                            eligibility, filteredResult, windows));
                                }
                            }
                        }
                    }
                }
            }
        }
        results.sort(Comparator
                .comparing((OptimizationResult r) -> r.windows.profitWindowRate, Comparator.reverseOrder())
                .thenComparing((OptimizationResult r) -> r.result.totalPnl, Comparator.reverseOrder())
                .thenComparing(r -> r.result.maxDrawdown));
        return results;
    }

    public static OptimizationConfig defaultConfig() {
        OptimizationConfig config = new OptimizationConfig();
        config.openRates = decimals("0.00006", "0.00008", "0.00010", "0.00012", "0.00015");
        config.closeRates = decimals("0.00002", "0.00003", "0.00005", "0.00008");
        config.slippageRates = decimals("0.0001", "0.0002", "0.0003");
        config.symbolMaxDrawdowns = decimals("150", "250", "500", "750");
        config.maxLookbackReturns = decimals("0.04", "0.06", "0.08");
        config.maxStepReturns = decimals("0.025", "0.04");
        config.maxAdverseTrendReturns = decimals("0.015", "0.03", "0.05");
        config.symbolMinTotalPnl = Config.SYMBOL_MIN_BACKTEST_TOTAL_PNL;
        config.symbolMinWinRate = Config.SYMBOL_MIN_BACKTEST_WIN_RATE;
        config.symbolMinClosedTrades = Config.SYMBOL_MIN_BACKTEST_CLOSED_TRADES;
        config.hardExcludedSymbols = Config.EXCLUDED_SYMBOLS;
        return config;
    }

    private static List<BigDecimal> decimals(String... values) {
        List<BigDecimal> result = new ArrayList<>();
        for (String value : values) {
            result.add(new BigDecimal(value));
        }
        return result;
    }

    public static class OptimizationConfig {
        public List<BigDecimal> openRates = new ArrayList<>();
        public List<BigDecimal> closeRates = new ArrayList<>();
        public List<BigDecimal> slippageRates = new ArrayList<>();
        public List<BigDecimal> symbolMaxDrawdowns = new ArrayList<>();
        public List<BigDecimal> maxLookbackReturns = new ArrayList<>();
        public List<BigDecimal> maxStepReturns = new ArrayList<>();
        public List<BigDecimal> maxAdverseTrendReturns = new ArrayList<>();
        public int marketStateLookbackBars = Config.BACKTEST_MARKET_STATE_LOOKBACK_BARS;
        public BigDecimal symbolMinTotalPnl = BigDecimal.ZERO;
        public BigDecimal symbolMinWinRate = new BigDecimal("0.40");
        public int symbolMinClosedTrades = 10;
        public java.util.Set<String> hardExcludedSymbols = new java.util.HashSet<>();
        public int windowDays = 30;
        public int stepDays = 7;
    }

    public static class OptimizationResult {
        public final BigDecimal openRate;
        public final BigDecimal closeRate;
        public final BigDecimal slippageRate;
        public final BigDecimal symbolMaxDrawdown;
        public final BigDecimal maxLookbackReturn;
        public final BigDecimal maxStepReturn;
        public final BigDecimal maxAdverseTrendReturn;
        public final SymbolEligibilityFilter.EligibilityReport eligibility;
        public final FundingBacktestEngine.BacktestResult result;
        public final FundingBacktestEngine.WindowBacktestResult windows;

        OptimizationResult(BigDecimal openRate, BigDecimal closeRate, BigDecimal slippageRate,
                           BigDecimal symbolMaxDrawdown,
                           BigDecimal maxLookbackReturn, BigDecimal maxStepReturn, BigDecimal maxAdverseTrendReturn,
                           SymbolEligibilityFilter.EligibilityReport eligibility,
                           FundingBacktestEngine.BacktestResult result,
                           FundingBacktestEngine.WindowBacktestResult windows) {
            this.openRate = openRate;
            this.closeRate = closeRate;
            this.slippageRate = slippageRate;
            this.symbolMaxDrawdown = symbolMaxDrawdown;
            this.maxLookbackReturn = maxLookbackReturn;
            this.maxStepReturn = maxStepReturn;
            this.maxAdverseTrendReturn = maxAdverseTrendReturn;
            this.eligibility = eligibility;
            this.result = result;
            this.windows = windows;
        }

        public String summaryLine() {
            return "open=" + pct(openRate)
                    + ", close=" + pct(closeRate)
                    + ", slip=" + pct(slippageRate)
                    + ", symbolMaxDD=" + symbolMaxDrawdown.setScale(0, RoundingMode.HALF_UP).toPlainString()
                    + ", lookbackMax=" + pct(maxLookbackReturn)
                    + ", stepMax=" + pct(maxStepReturn)
                    + ", adverseMax=" + pct(maxAdverseTrendReturn)
                    + ", symbols=" + eligibility.acceptedSymbols
                    + ", pnl=" + money(result.totalPnl)
                    + ", maxDD=" + money(result.maxDrawdown)
                    + ", winRate=" + pct(result.winRate)
                    + ", windowProfitRate=" + pct(windows.profitWindowRate)
                    + ", windows=" + windows.profitableWindows + "/" + windows.windows.size();
        }

        private static String money(BigDecimal value) {
            return value.setScale(6, RoundingMode.HALF_UP).toPlainString();
        }

        private static String pct(BigDecimal value) {
            return value.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP).toPlainString() + "%";
        }
    }
}
