package com.quant;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class ExecutionCostEstimate {
    private final String symbol;
    private final String side;
    private final BigDecimal quantity;
    private final BigDecimal midPrice;
    private final BigDecimal averageExecutionPrice;
    private final BigDecimal notional;
    private final BigDecimal slippageCost;
    private final BigDecimal feeCost;
    private final BigDecimal totalCost;
    private final boolean estimated;

    public ExecutionCostEstimate(String symbol, String side, BigDecimal quantity, BigDecimal midPrice,
                                 BigDecimal averageExecutionPrice, BigDecimal feeCost, boolean estimated) {
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity == null ? BigDecimal.ZERO : quantity;
        this.midPrice = midPrice == null ? BigDecimal.ZERO : midPrice;
        this.averageExecutionPrice = averageExecutionPrice == null ? this.midPrice : averageExecutionPrice;
        this.notional = this.quantity.multiply(this.averageExecutionPrice);
        this.slippageCost = this.quantity.multiply(this.averageExecutionPrice.subtract(this.midPrice).abs());
        this.feeCost = feeCost == null ? BigDecimal.ZERO : feeCost;
        this.totalCost = this.slippageCost.add(this.feeCost);
        this.estimated = estimated;
    }

    public static ExecutionCostEstimate fallback(String symbol, String side, BigDecimal quantity, BigDecimal midPrice) {
        BigDecimal qty = quantity == null ? BigDecimal.ZERO : quantity;
        BigDecimal price = midPrice == null ? BigDecimal.ZERO : midPrice;
        BigDecimal notional = qty.multiply(price);
        BigDecimal fee = notional.multiply(Config.LIVE_TAKER_FEE_RATE);
        BigDecimal slippage = notional.multiply(Config.LIVE_SLIPPAGE_RATE);
        BigDecimal adjustedPrice = price;
        if (qty.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal priceAdjustment = slippage.divide(qty, 12, RoundingMode.HALF_UP);
            adjustedPrice = "BUY".equals(side) ? price.add(priceAdjustment) : price.subtract(priceAdjustment);
        }
        return new ExecutionCostEstimate(symbol, side, qty, price, adjustedPrice, fee, true);
    }

    public String getSymbol() {
        return symbol;
    }

    public String getSide() {
        return side;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getMidPrice() {
        return midPrice;
    }

    public BigDecimal getAverageExecutionPrice() {
        return averageExecutionPrice;
    }

    public BigDecimal getNotional() {
        return notional;
    }

    public BigDecimal getSlippageCost() {
        return slippageCost;
    }

    public BigDecimal getFeeCost() {
        return feeCost;
    }

    public BigDecimal getTotalCost() {
        return totalCost;
    }

    public boolean isEstimated() {
        return estimated;
    }
}
