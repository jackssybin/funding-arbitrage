package com.quant;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class MarketSnapshot {
    public final String symbol;
    public final BigDecimal lastPrice;
    public final BigDecimal high24h;
    public final BigDecimal low24h;
    public final BigDecimal volume24h;
    public final BigDecimal bidPrice;
    public final BigDecimal askPrice;
    public final BigDecimal priceChangeRatio;

    public MarketSnapshot(String symbol, BigDecimal lastPrice, BigDecimal high24h, BigDecimal low24h,
                          BigDecimal volume24h, BigDecimal bidPrice, BigDecimal askPrice,
                          BigDecimal priceChangeRatio) {
        this.symbol = symbol;
        this.lastPrice = nullToZero(lastPrice);
        this.high24h = high24h;
        this.low24h = low24h;
        this.volume24h = volume24h;
        this.bidPrice = bidPrice;
        this.askPrice = askPrice;
        this.priceChangeRatio = nullToZero(priceChangeRatio);
    }

    public BigDecimal spreadRatio() {
        if (bidPrice == null || askPrice == null || bidPrice.compareTo(BigDecimal.ZERO) <= 0
                || askPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal mid = bidPrice.add(askPrice).divide(new BigDecimal("2"), 12, RoundingMode.HALF_UP);
        if (mid.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return askPrice.subtract(bidPrice).abs().divide(mid, 8, RoundingMode.HALF_UP);
    }

    public BigDecimal highLowRangeRatio() {
        if (high24h == null || low24h == null || lastPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return high24h.subtract(low24h).abs().divide(lastPrice, 8, RoundingMode.HALF_UP);
    }

    public boolean hasVolume() {
        return volume24h != null && volume24h.compareTo(BigDecimal.ZERO) > 0;
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
