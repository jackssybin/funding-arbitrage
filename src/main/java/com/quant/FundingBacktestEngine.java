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
import java.util.List;

/**
 * 最小资金费率策略回测引擎。
 *
 * CSV 格式:
 * time,symbol,price,fundingRate
 * 2026-01-01 00:00:00,BTCUSDT,60000,0.0008
 */
public class FundingBacktestEngine {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public BacktestResult run(List<MarketBar> bars, BacktestConfig config) {
        List<MarketBar> sorted = new ArrayList<>(bars);
        sorted.sort(Comparator.comparing((MarketBar b) -> b.time).thenComparing(b -> b.symbol));

        BacktestPosition position = null;
        BigDecimal cashPnl = BigDecimal.ZERO;
        BigDecimal equityHigh = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        int trades = 0;
        int fundingEvents = 0;

        for (MarketBar bar : sorted) {
            if (position != null && position.symbol.equals(bar.symbol)) {
                BigDecimal funding = position.notional.multiply(bar.fundingRate.abs());
                cashPnl = cashPnl.add(funding);
                fundingEvents++;

                BigDecimal unrealized = position.unrealizedPnl(bar.price);
                BigDecimal equity = cashPnl.add(unrealized);
                if (equity.compareTo(equityHigh) > 0) {
                    equityHigh = equity;
                }
                BigDecimal drawdown = equityHigh.subtract(equity);
                if (drawdown.compareTo(maxDrawdown) > 0) {
                    maxDrawdown = drawdown;
                }

                if (bar.fundingRate.abs().compareTo(config.closeRate) < 0
                        || position.unrealizedPnlRatio(bar.price).compareTo(config.stopLossRatio.negate()) < 0) {
                    cashPnl = cashPnl.add(unrealized).subtract(config.roundTripFee(position.notional));
                    position = null;
                    trades++;
                }
                continue;
            }

            if (position == null && bar.fundingRate.abs().compareTo(config.openRate) >= 0) {
                String side = bar.fundingRate.compareTo(BigDecimal.ZERO) >= 0 ? "SHORT" : "LONG";
                position = new BacktestPosition(bar.symbol, side, bar.price, config.notional, config.leverage);
                cashPnl = cashPnl.subtract(config.oneWayFee(config.notional));
                trades++;
            }
        }

        if (position != null) {
            MarketBar last = sorted.get(sorted.size() - 1);
            cashPnl = cashPnl.add(position.unrealizedPnl(last.price)).subtract(config.oneWayFee(position.notional));
            trades++;
        }

        BacktestResult result = new BacktestResult();
        result.totalPnl = cashPnl.setScale(6, RoundingMode.HALF_UP);
        result.maxDrawdown = maxDrawdown.setScale(6, RoundingMode.HALF_UP);
        result.trades = trades;
        result.fundingEvents = fundingEvents;
        return result;
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
        public BigDecimal notional = new BigDecimal("1000");
        public BigDecimal openRate = new BigDecimal("0.0010");
        public BigDecimal closeRate = new BigDecimal("0.0005");
        public BigDecimal takerFeeRate = new BigDecimal("0.0005");
        public BigDecimal stopLossRatio = new BigDecimal("0.05");
        public int leverage = Config.LEVERAGE;

        BigDecimal oneWayFee(BigDecimal notionalValue) {
            return notionalValue.multiply(takerFeeRate);
        }

        BigDecimal roundTripFee(BigDecimal notionalValue) {
            return oneWayFee(notionalValue).multiply(new BigDecimal("2"));
        }
    }

    public static class BacktestResult {
        public BigDecimal totalPnl;
        public BigDecimal maxDrawdown;
        public int trades;
        public int fundingEvents;
    }

    private static class BacktestPosition {
        private final String symbol;
        private final String side;
        private final BigDecimal entryPrice;
        private final BigDecimal notional;
        private final int leverage;

        BacktestPosition(String symbol, String side, BigDecimal entryPrice, BigDecimal notional, int leverage) {
            this.symbol = symbol;
            this.side = side;
            this.entryPrice = entryPrice;
            this.notional = notional;
            this.leverage = leverage;
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
