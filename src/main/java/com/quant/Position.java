package com.quant;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个币种的持仓状态
 */
public class Position {

    private String symbol;                  // 交易对
    private boolean hasPosition;            // 是否持仓
    private BigDecimal positionSize;        // 持仓数量
    private BigDecimal entryPrice;          // 开仓均价
    private BigDecimal lastFundingRate;     // 持仓时的资金费率
    private LocalDateTime entryTime;        // 开仓时间
    private BigDecimal totalFundingEarned;  // 累计资金费收益
    private BigDecimal gridProfit;          // 网格收益
    private List<GridOrder> gridOrders;     // 网格订单列表

    public Position(String symbol) {
        this.symbol = symbol;
        this.hasPosition = false;
        this.positionSize = BigDecimal.ZERO;
        this.entryPrice = BigDecimal.ZERO;
        this.lastFundingRate = BigDecimal.ZERO;
        this.totalFundingEarned = BigDecimal.ZERO;
        this.gridProfit = BigDecimal.ZERO;
        this.gridOrders = new ArrayList<>();
    }

    /**
     * 开仓
     */
    public void open(BigDecimal size, BigDecimal price, BigDecimal fundingRate) {
        this.hasPosition = true;
        this.positionSize = size;
        this.entryPrice = price;
        this.lastFundingRate = fundingRate;
        this.entryTime = LocalDateTime.now();
        this.gridOrders.clear();
    }

    /**
     * 平仓
     */
    public void close() {
        this.hasPosition = false;
        this.positionSize = BigDecimal.ZERO;
        this.gridOrders.clear();
    }

    /**
     * 累计资金费收益
     */
    public void addFundingEarning(BigDecimal earning) {
        this.totalFundingEarned = this.totalFundingEarned.add(earning);
    }

    /**
     * 累计网格收益
     */
    public void addGridProfit(BigDecimal profit) {
        this.gridProfit = this.gridProfit.add(profit);
    }

    // Getters and Setters
    public String getSymbol() {
        return symbol;
    }

    public boolean hasPosition() {
        return hasPosition;
    }

    public BigDecimal getPositionSize() {
        return positionSize;
    }

    public BigDecimal getEntryPrice() {
        return entryPrice;
    }

    public BigDecimal getLastFundingRate() {
        return lastFundingRate;
    }

    public void setLastFundingRate(BigDecimal lastFundingRate) {
        this.lastFundingRate = lastFundingRate;
    }

    public LocalDateTime getEntryTime() {
        return entryTime;
    }

    public BigDecimal getTotalFundingEarned() {
        return totalFundingEarned;
    }

    public BigDecimal getGridProfit() {
        return gridProfit;
    }

    public List<GridOrder> getGridOrders() {
        return gridOrders;
    }

    public void addGridOrder(GridOrder order) {
        this.gridOrders.add(order);
    }

    public void removeGridOrder(String orderId) {
        this.gridOrders.removeIf(o -> o.getOrderId().equals(orderId));
    }

    /**
     * 网格订单
     */
    public static class GridOrder {
        private String orderId;
        private String side;      // BUY/SELL
        private BigDecimal price;
        private BigDecimal quantity;
        private boolean active;

        public GridOrder(String orderId, String side, BigDecimal price, BigDecimal quantity) {
            this.orderId = orderId;
            this.side = side;
            this.price = price;
            this.quantity = quantity;
            this.active = true;
        }

        public String getOrderId() {
            return orderId;
        }

        public String getSide() {
            return side;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public BigDecimal getQuantity() {
            return quantity;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }
    }
}
