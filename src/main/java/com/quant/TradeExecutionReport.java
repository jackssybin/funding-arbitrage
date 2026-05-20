package com.quant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class TradeExecutionReport {
    private final String symbol;
    private final String orderIds;
    private BigDecimal executedQuantity = BigDecimal.ZERO;
    private BigDecimal notional = BigDecimal.ZERO;
    private BigDecimal averagePrice = BigDecimal.ZERO;
    private BigDecimal fee = BigDecimal.ZERO;
    private String feeAsset = "USDT";
    private BigDecimal realizedPnl = BigDecimal.ZERO;
    private final List<String> fills = new ArrayList<>();
    private boolean estimated;

    public TradeExecutionReport(String symbol, String orderIds) {
        this.symbol = symbol;
        this.orderIds = orderIds;
    }

    public static TradeExecutionReport estimated(String symbol, String orderIds, BigDecimal quantity, BigDecimal price) {
        TradeExecutionReport report = new TradeExecutionReport(symbol, orderIds);
        report.executedQuantity = quantity == null ? BigDecimal.ZERO : quantity.abs();
        report.averagePrice = price == null ? BigDecimal.ZERO : price;
        report.notional = report.executedQuantity.multiply(report.averagePrice);
        report.estimated = true;
        return report;
    }

    public void addFill(BigDecimal price, BigDecimal quantity, BigDecimal commission, String commissionAsset,
                        BigDecimal realizedPnl, String fillId) {
        BigDecimal fillQty = quantity == null ? BigDecimal.ZERO : quantity.abs();
        BigDecimal fillPrice = price == null ? BigDecimal.ZERO : price;
        executedQuantity = executedQuantity.add(fillQty);
        notional = notional.add(fillQty.multiply(fillPrice));
        if (commission != null) {
            fee = fee.add(commission.abs());
        }
        if (commissionAsset != null && !commissionAsset.isEmpty()) {
            feeAsset = commissionAsset;
        }
        if (realizedPnl != null) {
            this.realizedPnl = this.realizedPnl.add(realizedPnl);
        }
        if (fillId != null) {
            fills.add(fillId);
        }
        recalculateAveragePrice();
    }

    private void recalculateAveragePrice() {
        if (executedQuantity.compareTo(BigDecimal.ZERO) > 0) {
            averagePrice = notional.divide(executedQuantity, 12, RoundingMode.HALF_UP);
        }
    }

    public String getSymbol() {
        return symbol;
    }

    public String getOrderIds() {
        return orderIds;
    }

    public String getOrderId() {
        return orderIds;
    }

    public BigDecimal getExecutedQuantity() {
        return executedQuantity;
    }

    public BigDecimal getNotional() {
        return notional;
    }

    public BigDecimal getAveragePrice() {
        return averagePrice;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public String getFeeAsset() {
        return feeAsset;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public List<String> getFills() {
        return fills;
    }

    public boolean isEstimated() {
        return estimated;
    }

    /**
     * ========== P2 修复：获取以 USDT 计价的总手续费 ==========
     * 如果手续费币种是交易对的基础币（如BTC），则用成交价换算为 USDT
     */
    public BigDecimal getTotalFeeUsdtValue() {
        if (fee == null || fee.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        // 如果手续费已经是 USDT 或 U本位，直接返回
        if ("USDT".equals(feeAsset) || "USDC".equals(feeAsset) || "BUSD".equals(feeAsset)) {
            return fee;
        }
        // 如果手续费是基础币，用成交价格换算
        if (averagePrice != null && averagePrice.compareTo(BigDecimal.ZERO) > 0) {
            return fee.multiply(averagePrice);
        }
        // fallback: 假设为 USDT
        return fee;
    }
}
