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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal funding-rate strategy backtest engine.
 *
 * CSV format:
 * time,symbol,price,fundingRate
 * 2026-01-01 00:00:00,BTCUSDT,60000,0.0008
 */
public class FundingBacktestEngine {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public BacktestResult run(List<MarketBar> bars, BacktestConfig config) {
        List<MarketBar> sorted = new ArrayList<>(bars);
        sorted.sort(Comparator.comparing((MarketBar b) -> b.time).thenComparing(b -> b.symbol));

        BacktestPosition position = null;
        BacktestResult result = new BacktestResult();
        BigDecimal cashPnl = BigDecimal.ZERO;
        BigDecimal equityHigh = BigDecimal.ZERO;

        for (MarketBar bar : sorted) {
            SymbolStats stats = result.symbolStats.computeIfAbsent(bar.symbol, SymbolStats::new);

            if (position != null && position.symbol.equals(bar.symbol)) {
                BigDecimal funding = position.notional.multiply(bar.fundingRate.abs());
                position.accruedFunding = position.accruedFunding.add(funding);
                cashPnl = cashPnl.add(funding);
                result.fundingIncome = result.fundingIncome.add(funding);
                result.fundingEvents++;
                stats.fundingIncome = stats.fundingIncome.add(funding);
                stats.fundingEvents++;

                BigDecimal unrealized = position.unrealizedPnl(bar.price);
                BigDecimal equity = cashPnl.add(unrealized);
                if (equity.compareTo(equityHigh) > 0) {
                    equityHigh = equity;
                }
                BigDecimal drawdown = equityHigh.subtract(equity);
                if (drawdown.compareTo(result.maxDrawdown) > 0) {
                    result.maxDrawdown = drawdown;
                }

                if (bar.fundingRate.abs().compareTo(config.closeRate) < 0
                        || position.unrealizedPnlRatio(bar.price).compareTo(config.stopLossRatio.negate()) < 0) {
                    BigDecimal tradePnl = closePosition(position, bar.price, config, result, stats);
                    cashPnl = cashPnl.add(unrealized).subtract(config.oneWayFee(position.notional)).subtract(config.oneWaySlippage(position.notional));
                    recordClosedTrade(tradePnl, result, stats);
                    position = null;
                }
                continue;
            }

            if (position == null && bar.fundingRate.abs().compareTo(config.openRate) >= 0) {
                String side = bar.fundingRate.compareTo(BigDecimal.ZERO) >= 0 ? "SHORT" : "LONG";
                BigDecimal openFee = config.oneWayFee(config.notional);
                BigDecimal openSlippage = config.oneWaySlippage(config.notional);
                position = new BacktestPosition(bar.symbol, side, bar.price, config.notional, config.leverage, openFee, openSlippage);
                cashPnl = cashPnl.subtract(openFee).subtract(openSlippage);
                result.feeCost = result.feeCost.add(openFee);
                result.slippageCost = result.slippageCost.add(openSlippage);
                result.trades++;
                stats.feeCost = stats.feeCost.add(openFee);
                stats.slippageCost = stats.slippageCost.add(openSlippage);
                stats.trades++;
            }
        }

        if (position != null && !sorted.isEmpty()) {
            MarketBar last = lastBarForSymbol(sorted, position.symbol);
            SymbolStats stats = result.symbolStats.computeIfAbsent(position.symbol, SymbolStats::new);
            BigDecimal unrealized = position.unrealizedPnl(last.price);
            BigDecimal tradePnl = closePosition(position, last.price, config, result, stats);
            cashPnl = cashPnl.add(unrealized).subtract(config.oneWayFee(position.notional)).subtract(config.oneWaySlippage(position.notional));
            recordClosedTrade(tradePnl, result, stats);
        }

        result.totalPnl = cashPnl.setScale(6, RoundingMode.HALF_UP);
        result.maxDrawdown = result.maxDrawdown.setScale(6, RoundingMode.HALF_UP);
        result.fundingIncome = result.fundingIncome.setScale(6, RoundingMode.HALF_UP);
        result.feeCost = result.feeCost.setScale(6, RoundingMode.HALF_UP);
        result.slippageCost = result.slippageCost.setScale(6, RoundingMode.HALF_UP);
        result.tradingPnl = result.tradingPnl.setScale(6, RoundingMode.HALF_UP);
        result.winRate = ratio(result.winningTrades, result.closedTrades);
        result.fundingIncomeRatio = result.totalPnl.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : result.fundingIncome.divide(result.totalPnl.abs(), 6, RoundingMode.HALF_UP);
        for (SymbolStats stats : result.symbolStats.values()) {
            stats.totalPnl = stats.totalPnl.setScale(6, RoundingMode.HALF_UP);
            stats.fundingIncome = stats.fundingIncome.setScale(6, RoundingMode.HALF_UP);
            stats.feeCost = stats.feeCost.setScale(6, RoundingMode.HALF_UP);
            stats.slippageCost = stats.slippageCost.setScale(6, RoundingMode.HALF_UP);
            stats.tradingPnl = stats.tradingPnl.setScale(6, RoundingMode.HALF_UP);
            stats.winRate = ratio(stats.winningTrades, stats.closedTrades);
        }
        return result;
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

    private BigDecimal closePosition(BacktestPosition position, BigDecimal price, BacktestConfig config,
                                     BacktestResult result, SymbolStats stats) {
        BigDecimal closeFee = config.oneWayFee(position.notional);
        BigDecimal closeSlippage = config.oneWaySlippage(position.notional);
        BigDecimal tradingPnl = position.unrealizedPnl(price);
        BigDecimal tradePnl = tradingPnl
                .add(position.accruedFunding)
                .subtract(position.openFee)
                .subtract(closeFee)
                .subtract(position.openSlippage)
                .subtract(closeSlippage);

        result.feeCost = result.feeCost.add(closeFee);
        result.slippageCost = result.slippageCost.add(closeSlippage);
        result.tradingPnl = result.tradingPnl.add(tradingPnl);
        stats.feeCost = stats.feeCost.add(closeFee);
        stats.slippageCost = stats.slippageCost.add(closeSlippage);
        stats.tradingPnl = stats.tradingPnl.add(tradingPnl);
        return tradePnl;
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
        public final BigDecimal fundingRate;

        public MarketBar(LocalDateTime time, String symbol, BigDecimal price, BigDecimal fundingRate) {
            this.time = time;
            this.symbol = symbol;
            this.price = price;
            this.fundingRate = fundingRate;
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

        BigDecimal oneWayFee(BigDecimal notionalValue) {
            return notionalValue.multiply(takerFeeRate);
        }

        BigDecimal oneWaySlippage(BigDecimal notionalValue) {
            return notionalValue.multiply(slippageRate);
        }
    }

    public static class BacktestResult {
        public BigDecimal totalPnl = BigDecimal.ZERO;
        public BigDecimal fundingIncome = BigDecimal.ZERO;
        public BigDecimal tradingPnl = BigDecimal.ZERO;
        public BigDecimal feeCost = BigDecimal.ZERO;
        public BigDecimal slippageCost = BigDecimal.ZERO;
        public BigDecimal maxDrawdown = BigDecimal.ZERO;
        public BigDecimal winRate = BigDecimal.ZERO;
        public BigDecimal fundingIncomeRatio = BigDecimal.ZERO;
        public int trades;
        public int closedTrades;
        public int winningTrades;
        public int losingTrades;
        public int fundingEvents;
        public Map<String, SymbolStats> symbolStats = new LinkedHashMap<>();
    }

    public static class SymbolStats {
        public final String symbol;
        public BigDecimal totalPnl = BigDecimal.ZERO;
        public BigDecimal fundingIncome = BigDecimal.ZERO;
        public BigDecimal tradingPnl = BigDecimal.ZERO;
        public BigDecimal feeCost = BigDecimal.ZERO;
        public BigDecimal slippageCost = BigDecimal.ZERO;
        public BigDecimal winRate = BigDecimal.ZERO;
        public int trades;
        public int closedTrades;
        public int winningTrades;
        public int losingTrades;
        public int fundingEvents;

        SymbolStats(String symbol) {
            this.symbol = symbol;
        }
    }

    private static class BacktestPosition {
        private final String symbol;
        private final String side;
        private final BigDecimal entryPrice;
        private final BigDecimal notional;
        private final int leverage;
        private final BigDecimal openFee;
        private final BigDecimal openSlippage;
        private BigDecimal accruedFunding = BigDecimal.ZERO;

        BacktestPosition(String symbol, String side, BigDecimal entryPrice, BigDecimal notional, int leverage,
                         BigDecimal openFee, BigDecimal openSlippage) {
            this.symbol = symbol;
            this.side = side;
            this.entryPrice = entryPrice;
            this.notional = notional;
            this.leverage = leverage;
            this.openFee = openFee;
            this.openSlippage = openSlippage;
        }

        BigDecimal unrealizedPnl(BigDecimal currentPrice) {
            BigDecimal priceReturn = currentPrice.subtract(entryPrice).divide(entryPrice, 8, RoundingMode.HALF_UP);
            if ("SHORT".equals(side)) {
                priceReturn = priceReturn.negate();
            }
            return notional.multiply(priceReturn);
        }

        BigDecimal unrealizedPnlRatio(BigDecimal currentPrice) {
            if (leverage <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal margin = notional.divide(BigDecimal.valueOf(leverage), 8, RoundingMode.HALF_UP);
            if (BigDecimal.ZERO.compareTo(margin) >= 0) {
                return BigDecimal.ZERO;
            }
            return unrealizedPnl(currentPrice).divide(margin, 8, RoundingMode.HALF_UP);
        }
    }
}
