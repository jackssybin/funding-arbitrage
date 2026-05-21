package com.quant;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Funding-rate strategy backtest engine with costs, rolling windows, symbol stats and market-state attribution.
 */
public class FundingBacktestEngine {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public BacktestResult run(List<MarketBar> bars, BacktestConfig config) {
        List<MarketBar> sorted = new ArrayList<>(bars);
        sorted.sort(Comparator.comparing((MarketBar b) -> b.time).thenComparing(b -> b.symbol));

        Map<String, BacktestPosition> positions = new HashMap<>();
        Map<String, BigDecimal> lastPrices = new HashMap<>();
        Map<String, List<MarketBar>> marketHistory = new HashMap<>();
        List<FilteredShadowPosition> filteredShadows = new ArrayList<>();
        BacktestResult result = new BacktestResult();
        BigDecimal cashPnl = BigDecimal.ZERO;
        BigDecimal equityHigh = BigDecimal.ZERO;

        for (MarketBar bar : sorted) {
            SymbolStats stats = result.symbolStats.computeIfAbsent(bar.symbol, SymbolStats::new);
            lastPrices.put(bar.symbol, bar.price);
            appendBar(marketHistory, bar);
            settleAndCloseFilteredShadows(filteredShadows, bar, config, result, stats);
            BacktestPosition position = positions.get(bar.symbol);

            if (position != null) {
                if (bar.fundingSettlement) {
                    BigDecimal funding = fundingIncome(position, bar.fundingRate);
                    position.accruedFunding = position.accruedFunding.add(funding);
                    cashPnl = cashPnl.add(funding);
                    result.fundingIncome = result.fundingIncome.add(funding);
                    result.fundingEvents++;
                    stats.fundingIncome = stats.fundingIncome.add(funding);
                    stats.fundingEvents++;
                }

                BigDecimal equity = cashPnl.add(totalUnrealized(positions, lastPrices));
                if (equity.compareTo(equityHigh) > 0) {
                    equityHigh = equity;
                }
                BigDecimal drawdown = equityHigh.subtract(equity);
                if (drawdown.compareTo(result.maxDrawdown) > 0) {
                    result.maxDrawdown = drawdown;
                }
                updateSymbolDrawdown(stats, position, bar.price);

                if (bar.fundingRate.abs().compareTo(config.closeRate) < 0
                        || position.unrealizedPnlRatio(bar.price).compareTo(config.stopLossRatio.negate()) < 0) {
                    BigDecimal unrealized = position.unrealizedPnl(bar.price);
                    BigDecimal tradePnl = closePosition(position, bar.price, config, result, stats);
                    cashPnl = cashPnl.add(unrealized)
                            .subtract(closeCashCost(position, config));
                    recordClosedTrade(tradePnl, result, stats);
                    positions.remove(bar.symbol);
                }
                continue;
            }

            if (positions.size() < config.maxConcurrentPositions
                    && bar.fundingRate.abs().compareTo(config.openRate) >= 0) {
                String side = bar.fundingRate.compareTo(BigDecimal.ZERO) >= 0 ? "SHORT" : "LONG";
                MarketStateDecision hedgeDecision = evaluateHedgeEntry(side, bar, config);
                if (!hedgeDecision.accepted) {
                    recordFilteredEntry(hedgeDecision.reason, result, stats);
                    if (hedgeDecision.trackHypothetical) {
                        filteredShadows.add(new FilteredShadowPosition(createPosition(bar, side, config),
                                hedgeDecision.reason));
                    }
                    continue;
                }
                MarketStateDecision decision = evaluateMarketState(bar.symbol, side, marketHistory, bar, config);
                if (!decision.accepted) {
                    recordFilteredEntry(decision.reason, result, stats);
                    filteredShadows.add(new FilteredShadowPosition(createPosition(bar, side, config), decision.reason));
                    continue;
                }

                position = createPosition(bar, side, config);
                positions.put(bar.symbol, position);
                BigDecimal openFee = position.totalOpenFee();
                BigDecimal openSlippage = position.totalOpenSlippage();
                cashPnl = cashPnl.subtract(openFee).subtract(openSlippage);
                result.feeCost = result.feeCost.add(openFee);
                result.slippageCost = result.slippageCost.add(openSlippage);
                result.spotFeeCost = result.spotFeeCost.add(position.spotOpenFee);
                result.spotSlippageCost = result.spotSlippageCost.add(position.spotOpenSlippage);
                result.maxCapitalUsed = result.maxCapitalUsed.max(position.capitalUsed());
                result.trades++;
                stats.feeCost = stats.feeCost.add(openFee);
                stats.slippageCost = stats.slippageCost.add(openSlippage);
                stats.spotFeeCost = stats.spotFeeCost.add(position.spotOpenFee);
                stats.spotSlippageCost = stats.spotSlippageCost.add(position.spotOpenSlippage);
                stats.maxCapitalUsed = stats.maxCapitalUsed.max(position.capitalUsed());
                stats.trades++;
                updateSymbolDrawdown(stats, position, bar.price);
            }
        }

        for (BacktestPosition position : new ArrayList<>(positions.values())) {
            MarketBar last = lastBarForSymbol(sorted, position.symbol);
            SymbolStats stats = result.symbolStats.computeIfAbsent(position.symbol, SymbolStats::new);
            updateSymbolDrawdown(stats, position, last.price);
            BigDecimal unrealized = position.unrealizedPnl(last.price);
            BigDecimal tradePnl = closePosition(position, last.price, config, result, stats);
            cashPnl = cashPnl.add(unrealized)
                    .subtract(closeCashCost(position, config));
            recordClosedTrade(tradePnl, result, stats);
        }

        for (FilteredShadowPosition shadow : new ArrayList<>(filteredShadows)) {
            MarketBar last = lastBarForSymbol(sorted, shadow.position.symbol);
            SymbolStats stats = result.symbolStats.computeIfAbsent(shadow.position.symbol, SymbolStats::new);
            closeFilteredShadow(shadow, last.price, config, result, stats);
        }

        finishResult(result);
        return result;
    }

    public WindowBacktestResult runRollingWindows(List<MarketBar> bars, BacktestConfig config,
                                                  int windowDays, int stepDays) {
        if (windowDays <= 0 || stepDays <= 0) {
            throw new IllegalArgumentException("windowDays and stepDays must be positive");
        }
        List<MarketBar> sorted = new ArrayList<>(bars);
        sorted.sort(Comparator.comparing((MarketBar b) -> b.time).thenComparing(b -> b.symbol));
        WindowBacktestResult result = new WindowBacktestResult();
        if (sorted.isEmpty()) {
            return result;
        }

        LocalDateTime start = sorted.get(0).time;
        LocalDateTime last = sorted.get(sorted.size() - 1).time;
        while (!start.isAfter(last)) {
            LocalDateTime end = start.plusDays(windowDays);
            List<MarketBar> slice = new ArrayList<>();
            for (MarketBar bar : sorted) {
                if (!bar.time.isBefore(start) && bar.time.isBefore(end)) {
                    slice.add(bar);
                }
            }
            if (!slice.isEmpty()) {
                WindowResult window = new WindowResult(start, end, run(slice, config));
                result.windows.add(window);
                if (window.result.totalPnl.compareTo(BigDecimal.ZERO) > 0) {
                    result.profitableWindows++;
                } else {
                    result.losingWindows++;
                }
                if (result.worstWindow == null
                        || window.result.totalPnl.compareTo(result.worstWindow.result.totalPnl) < 0) {
                    result.worstWindow = window;
                }
                if (result.bestWindow == null
                        || window.result.totalPnl.compareTo(result.bestWindow.result.totalPnl) > 0) {
                    result.bestWindow = window;
                }
            }
            start = start.plusDays(stepDays);
        }
        result.profitWindowRate = ratio(result.profitableWindows, result.windows.size());
        return result;
    }

    private void appendBar(Map<String, List<MarketBar>> marketHistory, MarketBar bar) {
        marketHistory.computeIfAbsent(bar.symbol, ignored -> new ArrayList<>()).add(bar);
    }

    private MarketStateDecision evaluateHedgeEntry(String side, MarketBar current, BacktestConfig config) {
        if (!config.hedgeEnabled) {
            return MarketStateDecision.accepted();
        }
        if (config.hedgePositiveFundingOnly && !"SHORT".equals(side)) {
            return MarketStateDecision.rejected("UNSUPPORTED_HEDGE_DIRECTION", false);
        }
        BigDecimal expectedFunding = config.notional
                .multiply(current.fundingRate.abs())
                .multiply(BigDecimal.valueOf(config.expectedFundingSettlements));
        BigDecimal expectedCost = config.roundTripFee(config.notional)
                .add(config.roundTripSlippage(config.notional))
                .add(config.roundTripSpotFee(config.spotNotional()))
                .add(config.roundTripSpotSlippage(config.spotNotional()));
        BigDecimal expectedNet = expectedFunding.subtract(expectedCost);
        if (expectedNet.compareTo(config.minExpectedNetFundingAfterCosts) < 0) {
            return MarketStateDecision.rejected("EXPECTED_NET_AFTER_COSTS");
        }
        return MarketStateDecision.accepted();
    }

    private BacktestPosition createPosition(MarketBar bar, String side, BacktestConfig config) {
        BigDecimal futuresOpenFee = config.oneWayFee(config.notional);
        BigDecimal futuresOpenSlippage = config.oneWaySlippage(config.notional);
        boolean hedged = config.hedgeEnabled && "SHORT".equals(side) && bar.fundingRate.compareTo(BigDecimal.ZERO) > 0;
        BigDecimal spotNotional = hedged ? config.spotNotional() : BigDecimal.ZERO;
        BigDecimal spotOpenFee = hedged ? config.oneWaySpotFee(spotNotional) : BigDecimal.ZERO;
        BigDecimal spotOpenSlippage = hedged ? config.oneWaySpotSlippage(spotNotional) : BigDecimal.ZERO;
        return new BacktestPosition(bar.symbol, side, bar.price, config.notional, config.leverage,
                futuresOpenFee, futuresOpenSlippage, hedged, spotNotional, spotOpenFee, spotOpenSlippage);
    }

    private void recordFilteredEntry(String reason, BacktestResult result, SymbolStats stats) {
        result.filteredByMarketState++;
        stats.filteredByMarketState++;
        stats.filteredByReason.computeIfAbsent(reason, FilterReasonStats::new).filteredCount++;
        result.filteredByReason.computeIfAbsent(reason, FilterReasonStats::new).filteredCount++;
    }

    private MarketStateDecision evaluateMarketState(String symbol, String side,
                                                    Map<String, List<MarketBar>> marketHistory,
                                                    MarketBar current,
                                                    BacktestConfig config) {
        if (!config.marketStateFilterEnabled) {
            return MarketStateDecision.accepted();
        }
        List<MarketBar> history = marketHistory.get(symbol);
        int required = config.marketStateLookbackBars + 1;
        if (history == null || history.size() < required) {
            return MarketStateDecision.accepted();
        }

        int startIndex = history.size() - required;
        BigDecimal first = history.get(startIndex).price;
        BigDecimal last = current.price;
        if (first.compareTo(BigDecimal.ZERO) <= 0) {
            return MarketStateDecision.rejected("INVALID_PRICE");
        }
        BigDecimal lookbackReturn = last.subtract(first).divide(first, 8, RoundingMode.HALF_UP);
        if (lookbackReturn.abs().compareTo(config.maxLookbackReturn) > 0) {
            return MarketStateDecision.rejected("LOOKBACK_RETURN");
        }

        for (int i = startIndex + 1; i < history.size(); i++) {
            BigDecimal previous = history.get(i - 1).price;
            if (previous.compareTo(BigDecimal.ZERO) <= 0) {
                return MarketStateDecision.rejected("INVALID_PRICE");
            }
            BigDecimal stepReturn = history.get(i).price.subtract(previous).divide(previous, 8, RoundingMode.HALF_UP);
            if (stepReturn.abs().compareTo(config.maxStepReturn) > 0) {
                return MarketStateDecision.rejected("STEP_RETURN");
            }
        }

        boolean adverseTrend = ("SHORT".equals(side) && lookbackReturn.compareTo(BigDecimal.ZERO) > 0)
                || ("LONG".equals(side) && lookbackReturn.compareTo(BigDecimal.ZERO) < 0);
        if (adverseTrend && lookbackReturn.abs().compareTo(config.maxAdverseTrendReturn) > 0) {
            return MarketStateDecision.rejected("ADVERSE_TREND");
        }
        if (atrRatio(history, startIndex).compareTo(config.maxAtrRatio) > 0) {
            return MarketStateDecision.rejected("ATR");
        }
        if (current.highLowRangeRatio().compareTo(config.maxHighLowRangeRatio) > 0) {
            return MarketStateDecision.rejected("HIGH_LOW_RANGE");
        }
        if (realizedVolatility(history, startIndex).compareTo(config.maxRealizedVolatility) > 0) {
            return MarketStateDecision.rejected("REALIZED_VOLATILITY");
        }
        BigDecimal spreadRatio = current.effectiveBidAskSpreadRatio();
        if (spreadRatio != null && spreadRatio.compareTo(config.maxBidAskSpreadRatio) > 0) {
            return MarketStateDecision.rejected("BID_ASK_SPREAD");
        }
        if (volumeSpikeRatio(history, startIndex).compareTo(config.maxVolumeSpikeRatio) > 0) {
            return MarketStateDecision.rejected("VOLUME_SPIKE");
        }
        if (current.predictedFundingRate != null) {
            boolean directionMismatch = current.fundingRate.signum() != 0
                    && current.predictedFundingRate.signum() != 0
                    && current.fundingRate.signum() != current.predictedFundingRate.signum();
            BigDecimal deviation = current.predictedFundingRate.subtract(current.fundingRate).abs();
            if (directionMismatch || deviation.compareTo(config.maxFundingPredictionDeviation) > 0) {
                return MarketStateDecision.rejected("FUNDING_PREDICTION");
            }
        }
        return MarketStateDecision.accepted();
    }

    private BigDecimal atrRatio(List<MarketBar> history, int startIndex) {
        BigDecimal totalTrueRange = BigDecimal.ZERO;
        int count = 0;
        for (int i = startIndex + 1; i < history.size(); i++) {
            MarketBar bar = history.get(i);
            BigDecimal previousClose = history.get(i - 1).price;
            BigDecimal trueRange = bar.high.subtract(bar.low).abs()
                    .max(bar.high.subtract(previousClose).abs())
                    .max(bar.low.subtract(previousClose).abs());
            totalTrueRange = totalTrueRange.add(trueRange);
            count++;
        }
        BigDecimal last = history.get(history.size() - 1).price;
        if (count == 0 || last.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return totalTrueRange.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP)
                .divide(last, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal realizedVolatility(List<MarketBar> history, int startIndex) {
        List<Double> returns = new ArrayList<>();
        for (int i = startIndex + 1; i < history.size(); i++) {
            BigDecimal previous = history.get(i - 1).price;
            if (previous.compareTo(BigDecimal.ZERO) > 0) {
                returns.add(history.get(i).price.subtract(previous).divide(previous, 8, RoundingMode.HALF_UP)
                        .doubleValue());
            }
        }
        if (returns.size() < 2) {
            return BigDecimal.ZERO;
        }
        double mean = 0;
        for (double value : returns) {
            mean += value;
        }
        mean /= returns.size();
        double variance = 0;
        for (double value : returns) {
            variance += Math.pow(value - mean, 2);
        }
        variance /= returns.size();
        return BigDecimal.valueOf(Math.sqrt(variance)).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal volumeSpikeRatio(List<MarketBar> history, int startIndex) {
        MarketBar current = history.get(history.size() - 1);
        if (current.volume == null || current.volume.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (int i = startIndex; i < history.size() - 1; i++) {
            BigDecimal volume = history.get(i).volume;
            if (volume != null && volume.compareTo(BigDecimal.ZERO) > 0) {
                total = total.add(volume);
                count++;
            }
        }
        if (count == 0) {
            return BigDecimal.ONE;
        }
        BigDecimal average = total.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP);
        if (average.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        return current.volume.divide(average, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal fundingIncome(BacktestPosition position, BigDecimal fundingRate) {
        boolean earnsFunding = ("SHORT".equals(position.side) && fundingRate.compareTo(BigDecimal.ZERO) > 0)
                || ("LONG".equals(position.side) && fundingRate.compareTo(BigDecimal.ZERO) < 0);
        BigDecimal income = position.notional.multiply(fundingRate.abs());
        return earnsFunding ? income : income.negate();
    }

    private BigDecimal totalUnrealized(Map<String, BacktestPosition> positions, Map<String, BigDecimal> lastPrices) {
        BigDecimal total = BigDecimal.ZERO;
        for (BacktestPosition position : positions.values()) {
            BigDecimal price = lastPrices.get(position.symbol);
            if (price != null) {
                total = total.add(position.unrealizedPnl(price));
            }
        }
        return total;
    }

    private void updateSymbolDrawdown(SymbolStats stats, BacktestPosition position, BigDecimal price) {
        BigDecimal equity = stats.totalPnl
                .add(position.accruedFunding)
                .add(position.unrealizedPnl(price))
                .subtract(position.totalOpenFee())
                .subtract(position.totalOpenSlippage());
        if (equity.compareTo(stats.equityHigh) > 0) {
            stats.equityHigh = equity;
        }
        BigDecimal drawdown = stats.equityHigh.subtract(equity);
        if (drawdown.compareTo(stats.maxDrawdown) > 0) {
            stats.maxDrawdown = drawdown;
        }
    }

    private MarketBar lastBarForSymbol(List<MarketBar> sorted, String symbol) {
        for (int i = sorted.size() - 1; i >= 0; i--) {
            MarketBar bar = sorted.get(i);
            if (bar.symbol.equals(symbol)) {
                return bar;
            }
        }
        throw new IllegalArgumentException("No market bar for open position symbol: " + symbol);
    }

    private BigDecimal closeCashCost(BacktestPosition position, BacktestConfig config) {
        return config.oneWayFee(position.notional)
                .add(config.oneWaySlippage(position.notional))
                .add(position.hedged ? config.oneWaySpotFee(position.spotNotional) : BigDecimal.ZERO)
                .add(position.hedged ? config.oneWaySpotSlippage(position.spotNotional) : BigDecimal.ZERO);
    }

    private BigDecimal closePosition(BacktestPosition position, BigDecimal price, BacktestConfig config,
                                     BacktestResult result, SymbolStats stats) {
        BigDecimal closeFee = config.oneWayFee(position.notional);
        BigDecimal closeSlippage = config.oneWaySlippage(position.notional);
        BigDecimal spotCloseFee = position.hedged ? config.oneWaySpotFee(position.spotNotional) : BigDecimal.ZERO;
        BigDecimal spotCloseSlippage = position.hedged ? config.oneWaySpotSlippage(position.spotNotional) : BigDecimal.ZERO;
        BigDecimal tradingPnl = position.unrealizedPnl(price);
        BigDecimal tradePnl = tradingPnl
                .add(position.accruedFunding)
                .subtract(position.totalOpenFee())
                .subtract(closeFee)
                .subtract(spotCloseFee)
                .subtract(position.totalOpenSlippage())
                .subtract(closeSlippage)
                .subtract(spotCloseSlippage);

        result.feeCost = result.feeCost.add(closeFee).add(spotCloseFee);
        result.slippageCost = result.slippageCost.add(closeSlippage).add(spotCloseSlippage);
        result.spotFeeCost = result.spotFeeCost.add(spotCloseFee);
        result.spotSlippageCost = result.spotSlippageCost.add(spotCloseSlippage);
        result.tradingPnl = result.tradingPnl.add(tradingPnl);
        result.hedgePricePnl = result.hedgePricePnl.add(position.hedgePricePnl(price));
        stats.feeCost = stats.feeCost.add(closeFee).add(spotCloseFee);
        stats.slippageCost = stats.slippageCost.add(closeSlippage).add(spotCloseSlippage);
        stats.spotFeeCost = stats.spotFeeCost.add(spotCloseFee);
        stats.spotSlippageCost = stats.spotSlippageCost.add(spotCloseSlippage);
        stats.tradingPnl = stats.tradingPnl.add(tradingPnl);
        stats.hedgePricePnl = stats.hedgePricePnl.add(position.hedgePricePnl(price));
        return tradePnl;
    }

    private void settleAndCloseFilteredShadows(List<FilteredShadowPosition> shadows, MarketBar bar,
                                               BacktestConfig config, BacktestResult result, SymbolStats stats) {
        List<FilteredShadowPosition> closed = new ArrayList<>();
        for (FilteredShadowPosition shadow : shadows) {
            if (!shadow.position.symbol.equals(bar.symbol)) {
                continue;
            }
            if (bar.fundingSettlement) {
                shadow.position.accruedFunding = shadow.position.accruedFunding
                        .add(fundingIncome(shadow.position, bar.fundingRate));
            }
            if (bar.fundingRate.abs().compareTo(config.closeRate) < 0
                    || shadow.position.unrealizedPnlRatio(bar.price).compareTo(config.stopLossRatio.negate()) < 0) {
                closeFilteredShadow(shadow, bar.price, config, result, stats);
                closed.add(shadow);
            }
        }
        shadows.removeAll(closed);
    }

    private void closeFilteredShadow(FilteredShadowPosition shadow, BigDecimal price, BacktestConfig config,
                                     BacktestResult result, SymbolStats stats) {
        BigDecimal closeFee = config.oneWayFee(shadow.position.notional);
        BigDecimal closeSlippage = config.oneWaySlippage(shadow.position.notional);
        BigDecimal spotCloseFee = shadow.position.hedged ? config.oneWaySpotFee(shadow.position.spotNotional)
                : BigDecimal.ZERO;
        BigDecimal spotCloseSlippage = shadow.position.hedged
                ? config.oneWaySpotSlippage(shadow.position.spotNotional)
                : BigDecimal.ZERO;
        BigDecimal pnl = shadow.position.unrealizedPnl(price)
                .add(shadow.position.accruedFunding)
                .subtract(shadow.position.totalOpenFee())
                .subtract(closeFee)
                .subtract(spotCloseFee)
                .subtract(shadow.position.totalOpenSlippage())
                .subtract(closeSlippage)
                .subtract(spotCloseSlippage);

        result.filteredHypotheticalPnl = result.filteredHypotheticalPnl.add(pnl);
        result.filteredClosedTrades++;
        stats.filteredHypotheticalPnl = stats.filteredHypotheticalPnl.add(pnl);
        stats.filteredClosedTrades++;

        FilterReasonStats resultReason = result.filteredByReason.computeIfAbsent(shadow.reason, FilterReasonStats::new);
        FilterReasonStats symbolReason = stats.filteredByReason.computeIfAbsent(shadow.reason, FilterReasonStats::new);
        resultReason.hypotheticalPnl = resultReason.hypotheticalPnl.add(pnl);
        resultReason.closedTrades++;
        symbolReason.hypotheticalPnl = symbolReason.hypotheticalPnl.add(pnl);
        symbolReason.closedTrades++;

        if (pnl.compareTo(BigDecimal.ZERO) > 0) {
            result.filteredWinningTrades++;
            stats.filteredWinningTrades++;
            resultReason.winningTrades++;
            symbolReason.winningTrades++;
        } else {
            result.filteredLosingTrades++;
            stats.filteredLosingTrades++;
            resultReason.losingTrades++;
            symbolReason.losingTrades++;
        }
    }

    private void recordClosedTrade(BigDecimal tradePnl, BacktestResult result, SymbolStats stats) {
        result.closedTrades++;
        stats.closedTrades++;
        result.totalPnl = result.totalPnl.add(tradePnl);
        stats.totalPnl = stats.totalPnl.add(tradePnl);
        if (tradePnl.compareTo(BigDecimal.ZERO) > 0) {
            result.winningTrades++;
            stats.winningTrades++;
        } else {
            result.losingTrades++;
            stats.losingTrades++;
        }
    }

    private void finishResult(BacktestResult result) {
        result.totalPnl = result.totalPnl.setScale(6, RoundingMode.HALF_UP);
        result.maxDrawdown = result.maxDrawdown.setScale(6, RoundingMode.HALF_UP);
        result.fundingIncome = result.fundingIncome.setScale(6, RoundingMode.HALF_UP);
        result.feeCost = result.feeCost.setScale(6, RoundingMode.HALF_UP);
        result.slippageCost = result.slippageCost.setScale(6, RoundingMode.HALF_UP);
        result.tradingPnl = result.tradingPnl.setScale(6, RoundingMode.HALF_UP);
        result.spotFeeCost = result.spotFeeCost.setScale(6, RoundingMode.HALF_UP);
        result.spotSlippageCost = result.spotSlippageCost.setScale(6, RoundingMode.HALF_UP);
        result.hedgePricePnl = result.hedgePricePnl.setScale(6, RoundingMode.HALF_UP);
        result.maxCapitalUsed = result.maxCapitalUsed.setScale(6, RoundingMode.HALF_UP);
        result.filteredHypotheticalPnl = result.filteredHypotheticalPnl.setScale(6, RoundingMode.HALF_UP);
        result.winRate = ratio(result.winningTrades, result.closedTrades);
        result.filteredWinRate = ratio(result.filteredWinningTrades, result.filteredClosedTrades);
        result.fundingIncomeRatio = result.totalPnl.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : result.fundingIncome.divide(result.totalPnl.abs(), 6, RoundingMode.HALF_UP);
        finishReasonStats(result.filteredByReason);
        for (SymbolStats stats : result.symbolStats.values()) {
            stats.totalPnl = stats.totalPnl.setScale(6, RoundingMode.HALF_UP);
            stats.fundingIncome = stats.fundingIncome.setScale(6, RoundingMode.HALF_UP);
            stats.feeCost = stats.feeCost.setScale(6, RoundingMode.HALF_UP);
            stats.slippageCost = stats.slippageCost.setScale(6, RoundingMode.HALF_UP);
            stats.tradingPnl = stats.tradingPnl.setScale(6, RoundingMode.HALF_UP);
            stats.spotFeeCost = stats.spotFeeCost.setScale(6, RoundingMode.HALF_UP);
            stats.spotSlippageCost = stats.spotSlippageCost.setScale(6, RoundingMode.HALF_UP);
            stats.hedgePricePnl = stats.hedgePricePnl.setScale(6, RoundingMode.HALF_UP);
            stats.maxCapitalUsed = stats.maxCapitalUsed.setScale(6, RoundingMode.HALF_UP);
            stats.maxDrawdown = stats.maxDrawdown.setScale(6, RoundingMode.HALF_UP);
            stats.filteredHypotheticalPnl = stats.filteredHypotheticalPnl.setScale(6, RoundingMode.HALF_UP);
            stats.winRate = ratio(stats.winningTrades, stats.closedTrades);
            stats.filteredWinRate = ratio(stats.filteredWinningTrades, stats.filteredClosedTrades);
            stats.fundingIncomeRatio = stats.totalPnl.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : stats.fundingIncome.divide(stats.totalPnl.abs(), 6, RoundingMode.HALF_UP);
            finishReasonStats(stats.filteredByReason);
        }
    }

    private void finishReasonStats(Map<String, FilterReasonStats> reasons) {
        for (FilterReasonStats stats : reasons.values()) {
            stats.hypotheticalPnl = stats.hypotheticalPnl.setScale(6, RoundingMode.HALF_UP);
            stats.winRate = ratio(stats.winningTrades, stats.closedTrades);
        }
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    public static List<MarketBar> loadCsv(String file) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(file));
        List<MarketBar> bars = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(",");
            if (parts.length < 4) {
                throw new IllegalArgumentException("Invalid backtest csv line: " + line);
            }
            bars.add(new MarketBar(
                    LocalDateTime.parse(parts[0].trim(), DTF),
                    parts[1].trim(),
                    new BigDecimal(parts[2].trim()),
                    new BigDecimal(parts[3].trim())
            ));
        }
        return bars;
    }

    public static class MarketBar {
        public final LocalDateTime time;
        public final String symbol;
        public final BigDecimal price;
        public final BigDecimal high;
        public final BigDecimal low;
        public final BigDecimal volume;
        public final BigDecimal bidPrice;
        public final BigDecimal askPrice;
        public final BigDecimal bidAskSpreadRatio;
        public final BigDecimal fundingRate;
        public final BigDecimal predictedFundingRate;
        public final boolean fundingSettlement;

        public MarketBar(LocalDateTime time, String symbol, BigDecimal price, BigDecimal fundingRate) {
            this(time, symbol, price, fundingRate, isDefaultFundingSettlementTime(time));
        }

        public MarketBar(LocalDateTime time, String symbol, BigDecimal price, BigDecimal fundingRate,
                         boolean fundingSettlement) {
            this(time, symbol, price, price, price, BigDecimal.ZERO, null,
                    fundingRate, null, fundingSettlement);
        }

        public MarketBar(LocalDateTime time, String symbol, BigDecimal price, BigDecimal high, BigDecimal low,
                         BigDecimal volume, BigDecimal bidAskSpreadRatio, BigDecimal fundingRate,
                         BigDecimal predictedFundingRate, boolean fundingSettlement) {
            this(time, symbol, price, high, low, volume, null, null, bidAskSpreadRatio,
                    fundingRate, predictedFundingRate, fundingSettlement);
        }

        public MarketBar(LocalDateTime time, String symbol, BigDecimal price, BigDecimal high, BigDecimal low,
                         BigDecimal volume, BigDecimal bidPrice, BigDecimal askPrice,
                         BigDecimal bidAskSpreadRatio, BigDecimal fundingRate,
                         BigDecimal predictedFundingRate, boolean fundingSettlement) {
            this.time = time;
            this.symbol = symbol;
            this.price = price;
            this.high = high == null ? price : high;
            this.low = low == null ? price : low;
            this.volume = volume;
            this.bidPrice = bidPrice;
            this.askPrice = askPrice;
            this.bidAskSpreadRatio = bidAskSpreadRatio;
            this.fundingRate = fundingRate;
            this.predictedFundingRate = predictedFundingRate;
            this.fundingSettlement = fundingSettlement;
        }

        public BigDecimal effectiveBidAskSpreadRatio() {
            if (bidAskSpreadRatio != null) {
                return bidAskSpreadRatio;
            }
            if (bidPrice == null || askPrice == null || bidPrice.compareTo(BigDecimal.ZERO) <= 0
                    || askPrice.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            BigDecimal mid = bidPrice.add(askPrice).divide(new BigDecimal("2"), 12, RoundingMode.HALF_UP);
            if (mid.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            return askPrice.subtract(bidPrice).abs().divide(mid, 8, RoundingMode.HALF_UP);
        }

        public BigDecimal highLowRangeRatio() {
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0 || high == null || low == null) {
                return BigDecimal.ZERO;
            }
            return high.subtract(low).abs().divide(price, 8, RoundingMode.HALF_UP);
        }
    }

    public static class BacktestConfig {
        public BigDecimal notional = Config.POSITION_VALUE_USDT;
        public BigDecimal openRate = Config.MIN_FUNDING_RATE_POSITIVE;
        public BigDecimal closeRate = Config.CLOSE_FUNDING_RATE;
        public BigDecimal takerFeeRate = new BigDecimal("0.0005");
        public BigDecimal slippageRate = Config.BACKTEST_SLIPPAGE_RATE;
        public BigDecimal stopLossRatio = Config.STOP_LOSS_RATIO;
        public int leverage = Config.LEVERAGE;
        public int maxConcurrentPositions = Config.MAX_POSITIONS;
        public boolean marketStateFilterEnabled = Config.BACKTEST_MARKET_STATE_FILTER_ENABLED;
        public int marketStateLookbackBars = Config.BACKTEST_MARKET_STATE_LOOKBACK_BARS;
        public BigDecimal maxLookbackReturn = Config.BACKTEST_MAX_LOOKBACK_RETURN;
        public BigDecimal maxStepReturn = Config.BACKTEST_MAX_STEP_RETURN;
        public BigDecimal maxAdverseTrendReturn = Config.BACKTEST_MAX_ADVERSE_TREND_RETURN;
        public BigDecimal maxAtrRatio = Config.BACKTEST_MAX_ATR_RATIO;
        public BigDecimal maxHighLowRangeRatio = Config.BACKTEST_MAX_HIGH_LOW_RANGE_RATIO;
        public BigDecimal maxRealizedVolatility = Config.BACKTEST_MAX_REALIZED_VOLATILITY;
        public BigDecimal maxBidAskSpreadRatio = Config.BACKTEST_MAX_BID_ASK_SPREAD_RATIO;
        public BigDecimal maxVolumeSpikeRatio = Config.BACKTEST_MAX_VOLUME_SPIKE_RATIO;
        public BigDecimal maxFundingPredictionDeviation = Config.BACKTEST_MAX_FUNDING_PREDICTION_DEVIATION;
        public boolean hedgeEnabled = Config.BACKTEST_SPOT_HEDGE_ENABLED;
        public boolean hedgePositiveFundingOnly = Config.BACKTEST_SPOT_HEDGE_POSITIVE_FUNDING_ONLY;
        public BigDecimal hedgeRatio = Config.BACKTEST_SPOT_HEDGE_RATIO;
        public BigDecimal spotTakerFeeRate = Config.BACKTEST_SPOT_TAKER_FEE_RATE;
        public BigDecimal spotSlippageRate = Config.BACKTEST_SPOT_SLIPPAGE_RATE;
        public BigDecimal minExpectedNetFundingAfterCosts = Config.BACKTEST_MIN_EXPECTED_NET_FUNDING_AFTER_COSTS;
        public int expectedFundingSettlements = Config.BACKTEST_EXPECTED_FUNDING_SETTLEMENTS;

        BigDecimal oneWayFee(BigDecimal notionalValue) {
            return notionalValue.multiply(takerFeeRate);
        }

        BigDecimal oneWaySlippage(BigDecimal notionalValue) {
            return notionalValue.multiply(slippageRate);
        }

        BigDecimal spotNotional() {
            return notional.multiply(hedgeRatio);
        }

        BigDecimal oneWaySpotFee(BigDecimal notionalValue) {
            return notionalValue.multiply(spotTakerFeeRate);
        }

        BigDecimal oneWaySpotSlippage(BigDecimal notionalValue) {
            return notionalValue.multiply(spotSlippageRate);
        }

        BigDecimal roundTripFee(BigDecimal notionalValue) {
            return oneWayFee(notionalValue).multiply(new BigDecimal("2"));
        }

        BigDecimal roundTripSlippage(BigDecimal notionalValue) {
            return oneWaySlippage(notionalValue).multiply(new BigDecimal("2"));
        }

        BigDecimal roundTripSpotFee(BigDecimal notionalValue) {
            return oneWaySpotFee(notionalValue).multiply(new BigDecimal("2"));
        }

        BigDecimal roundTripSpotSlippage(BigDecimal notionalValue) {
            return oneWaySpotSlippage(notionalValue).multiply(new BigDecimal("2"));
        }
    }

    public static class BacktestResult {
        public BigDecimal totalPnl = BigDecimal.ZERO;
        public BigDecimal fundingIncome = BigDecimal.ZERO;
        public BigDecimal tradingPnl = BigDecimal.ZERO;
        public BigDecimal feeCost = BigDecimal.ZERO;
        public BigDecimal slippageCost = BigDecimal.ZERO;
        public BigDecimal spotFeeCost = BigDecimal.ZERO;
        public BigDecimal spotSlippageCost = BigDecimal.ZERO;
        public BigDecimal hedgePricePnl = BigDecimal.ZERO;
        public BigDecimal maxCapitalUsed = BigDecimal.ZERO;
        public BigDecimal maxDrawdown = BigDecimal.ZERO;
        public BigDecimal winRate = BigDecimal.ZERO;
        public BigDecimal fundingIncomeRatio = BigDecimal.ZERO;
        public BigDecimal filteredHypotheticalPnl = BigDecimal.ZERO;
        public BigDecimal filteredWinRate = BigDecimal.ZERO;
        public int trades;
        public int closedTrades;
        public int winningTrades;
        public int losingTrades;
        public int fundingEvents;
        public int filteredByMarketState;
        public int filteredClosedTrades;
        public int filteredWinningTrades;
        public int filteredLosingTrades;
        public final Map<String, FilterReasonStats> filteredByReason = new LinkedHashMap<>();
        public final Map<String, SymbolStats> symbolStats = new LinkedHashMap<>();
    }

    public static class SymbolStats {
        public final String symbol;
        public BigDecimal totalPnl = BigDecimal.ZERO;
        public BigDecimal fundingIncome = BigDecimal.ZERO;
        public BigDecimal tradingPnl = BigDecimal.ZERO;
        public BigDecimal feeCost = BigDecimal.ZERO;
        public BigDecimal slippageCost = BigDecimal.ZERO;
        public BigDecimal spotFeeCost = BigDecimal.ZERO;
        public BigDecimal spotSlippageCost = BigDecimal.ZERO;
        public BigDecimal hedgePricePnl = BigDecimal.ZERO;
        public BigDecimal maxCapitalUsed = BigDecimal.ZERO;
        public BigDecimal maxDrawdown = BigDecimal.ZERO;
        public BigDecimal winRate = BigDecimal.ZERO;
        public BigDecimal fundingIncomeRatio = BigDecimal.ZERO;
        public BigDecimal filteredHypotheticalPnl = BigDecimal.ZERO;
        public BigDecimal filteredWinRate = BigDecimal.ZERO;
        public int trades;
        public int closedTrades;
        public int winningTrades;
        public int losingTrades;
        public int fundingEvents;
        public int filteredByMarketState;
        public int filteredClosedTrades;
        public int filteredWinningTrades;
        public int filteredLosingTrades;
        public final Map<String, FilterReasonStats> filteredByReason = new LinkedHashMap<>();
        private BigDecimal equityHigh = BigDecimal.ZERO;

        SymbolStats(String symbol) {
            this.symbol = symbol;
        }
    }

    public static class FilterReasonStats {
        public final String reason;
        public BigDecimal hypotheticalPnl = BigDecimal.ZERO;
        public BigDecimal winRate = BigDecimal.ZERO;
        public int filteredCount;
        public int closedTrades;
        public int winningTrades;
        public int losingTrades;

        FilterReasonStats(String reason) {
            this.reason = reason;
        }
    }

    public static class WindowBacktestResult {
        public final List<WindowResult> windows = new ArrayList<>();
        public WindowResult bestWindow;
        public WindowResult worstWindow;
        public BigDecimal profitWindowRate = BigDecimal.ZERO;
        public int profitableWindows;
        public int losingWindows;
    }

    public static class WindowResult {
        public final LocalDateTime start;
        public final LocalDateTime end;
        public final BacktestResult result;

        WindowResult(LocalDateTime start, LocalDateTime end, BacktestResult result) {
            this.start = start;
            this.end = end;
            this.result = result;
        }
    }

    private static boolean isDefaultFundingSettlementTime(LocalDateTime time) {
        return time.getMinute() == 0
                && time.getSecond() == 0
                && time.getNano() == 0
                && time.getHour() % 8 == 0;
    }

    private static class BacktestPosition {
        private final String symbol;
        private final String side;
        private final BigDecimal entryPrice;
        private final BigDecimal notional;
        private final int leverage;
        private final BigDecimal openFee;
        private final BigDecimal openSlippage;
        private final boolean hedged;
        private final BigDecimal spotNotional;
        private final BigDecimal spotOpenFee;
        private final BigDecimal spotOpenSlippage;
        private BigDecimal accruedFunding = BigDecimal.ZERO;

        BacktestPosition(String symbol, String side, BigDecimal entryPrice, BigDecimal notional, int leverage,
                         BigDecimal openFee, BigDecimal openSlippage) {
            this(symbol, side, entryPrice, notional, leverage, openFee, openSlippage,
                    false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BacktestPosition(String symbol, String side, BigDecimal entryPrice, BigDecimal notional, int leverage,
                         BigDecimal openFee, BigDecimal openSlippage, boolean hedged, BigDecimal spotNotional,
                         BigDecimal spotOpenFee, BigDecimal spotOpenSlippage) {
            this.symbol = symbol;
            this.side = side;
            this.entryPrice = entryPrice;
            this.notional = notional;
            this.leverage = leverage;
            this.openFee = openFee;
            this.openSlippage = openSlippage;
            this.hedged = hedged;
            this.spotNotional = spotNotional;
            this.spotOpenFee = spotOpenFee;
            this.spotOpenSlippage = spotOpenSlippage;
        }

        BigDecimal unrealizedPnl(BigDecimal currentPrice) {
            return futuresPricePnl(currentPrice).add(hedgePricePnl(currentPrice));
        }

        BigDecimal futuresPricePnl(BigDecimal currentPrice) {
            BigDecimal priceReturn = currentPrice.subtract(entryPrice).divide(entryPrice, 8, RoundingMode.HALF_UP);
            if ("SHORT".equals(side)) {
                priceReturn = priceReturn.negate();
            }
            return notional.multiply(priceReturn);
        }

        BigDecimal hedgePricePnl(BigDecimal currentPrice) {
            if (!hedged || spotNotional.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal priceReturn = currentPrice.subtract(entryPrice).divide(entryPrice, 8, RoundingMode.HALF_UP);
            return spotNotional.multiply(priceReturn);
        }

        BigDecimal unrealizedPnlRatio(BigDecimal currentPrice) {
            if (leverage <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal margin = capitalUsed();
            if (BigDecimal.ZERO.compareTo(margin) >= 0) {
                return BigDecimal.ZERO;
            }
            return unrealizedPnl(currentPrice).divide(margin, 8, RoundingMode.HALF_UP);
        }

        BigDecimal totalOpenFee() {
            return openFee.add(spotOpenFee);
        }

        BigDecimal totalOpenSlippage() {
            return openSlippage.add(spotOpenSlippage);
        }

        BigDecimal capitalUsed() {
            if (leverage <= 0) {
                return spotNotional;
            }
            return notional.divide(BigDecimal.valueOf(leverage), 8, RoundingMode.HALF_UP).add(spotNotional);
        }
    }

    private static class FilteredShadowPosition {
        private final BacktestPosition position;
        private final String reason;

        FilteredShadowPosition(BacktestPosition position, String reason) {
            this.position = position;
            this.reason = reason;
        }
    }

    private static class MarketStateDecision {
        private final boolean accepted;
        private final String reason;
        private final boolean trackHypothetical;

        private MarketStateDecision(boolean accepted, String reason) {
            this(accepted, reason, true);
        }

        private MarketStateDecision(boolean accepted, String reason, boolean trackHypothetical) {
            this.accepted = accepted;
            this.reason = reason;
            this.trackHypothetical = trackHypothetical;
        }

        static MarketStateDecision accepted() {
            return new MarketStateDecision(true, "ACCEPTED");
        }

        static MarketStateDecision rejected(String reason) {
            return new MarketStateDecision(false, reason);
        }

        static MarketStateDecision rejected(String reason, boolean trackHypothetical) {
            return new MarketStateDecision(false, reason, trackHypothetical);
        }
    }
}
