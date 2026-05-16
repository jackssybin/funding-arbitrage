package com.quant;
import java.math.RoundingMode;

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
    private String positionSide;            // 持仓方向：LONG=做多, SHORT=做空
    private BigDecimal positionSize;        // 持仓数量
    private BigDecimal entryPrice;          // 开仓均价
    private BigDecimal lastFundingRate;     // 持仓时的资金费率
    private LocalDateTime entryTime;        // 开仓时间
    private LocalDateTime lastFundingTime;  // 上次资金费结算时间
    private int fundingCount;               // 已结算次数
    private BigDecimal totalFundingEarned;  // 累计资金费收益
    private BigDecimal unrealizedPnl;       // 未实现盈亏
    private BigDecimal unrealizedPnlRatio;  // 未实现盈亏比例
    private BigDecimal markPrice;           // 当前标记价格
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
    public void open(BigDecimal size, BigDecimal price, BigDecimal fundingRate, String side) {
        this.hasPosition = true;
        this.positionSize = size;
        this.entryPrice = price;
        this.lastFundingRate = fundingRate;
        this.positionSide = side;
        this.entryTime = LocalDateTime.now();
        this.fundingCount = 0;
        this.totalFundingEarned = BigDecimal.ZERO;
        this.gridOrders.clear();
    }

    /**
     * 记录一次资金费结算
     */
    public void recordFundingSettlement(BigDecimal earning) {
        this.totalFundingEarned = this.totalFundingEarned.add(earning);
        this.lastFundingTime = LocalDateTime.now();
        this.fundingCount++;
    }

    /**
     * 获取持仓小时数（用于判断是否足够拿到几次资金费）
     */
    public long getHoldingHours() {
        if (entryTime == null) return 0;
        return java.time.Duration.between(entryTime, LocalDateTime.now()).toHours();
    }

    /**
     * 计算移仓是否划算（考虑手续费成本）
     */
    public boolean isSwitchWorthIt(BigDecimal newRate, BigDecimal switchThreshold, BigDecimal feeCost) {
        BigDecimal currentRate = this.lastFundingRate.abs();
        BigDecimal diff = newRate.subtract(currentRate).abs();
        
        // 至少持仓 6 小时才考虑移仓（至少接近下一个结算点）
        if (getHoldingHours() < 6) {
            return false;
        }
        
        // 新费率必须比当前费率高出阈值 + 手续费成本才划算
        // 原则：移仓后至少需要持仓 2 个结算周期才能回本，否则不划算
        return diff.compareTo(switchThreshold.add(feeCost)) > 0;
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
     * 更新浮盈浮亏
     */
    public void updateUnrealizedPnl(BigDecimal currentPrice) {
        if (!hasPosition || BigDecimal.ZERO.equals(positionSize)) {
            this.unrealizedPnl = BigDecimal.ZERO;
            this.unrealizedPnlRatio = BigDecimal.ZERO;
            this.markPrice = currentPrice;
            return;
        }

        this.markPrice = currentPrice;
        BigDecimal notionalValue = positionSize.multiply(entryPrice);
        
        if ("SHORT".equals(positionSide)) {
            // 做空：(开仓价 - 当前价) * 数量 = 盈利
            this.unrealizedPnl = entryPrice.subtract(currentPrice).multiply(positionSize);
        } else {
            // 做多：(当前价 - 开仓价) * 数量 = 盈利
            this.unrealizedPnl = currentPrice.subtract(entryPrice).multiply(positionSize);
        }
        
        // 盈亏比例 = 盈亏 / 名义价值
        if (notionalValue.compareTo(BigDecimal.ZERO) > 0) {
            this.unrealizedPnlRatio = this.unrealizedPnl.divide(notionalValue, 6, RoundingMode.HALF_UP);
        } else {
            this.unrealizedPnlRatio = BigDecimal.ZERO;
        }
    }

    /**
     * 判断是否需要止损（亏损超过 5%）
     */
    public boolean isStopLossTriggered(BigDecimal stopLossRatio) {
        if (unrealizedPnlRatio == null) return false;
        return unrealizedPnlRatio.compareTo(stopLossRatio.negate()) < 0;
    }

    /**
     * 判断是否需要止盈（盈利超过 10%）
     */
    public boolean isTakeProfitTriggered(BigDecimal takeProfitRatio) {
        if (unrealizedPnlRatio == null) return false;
        return unrealizedPnlRatio.compareTo(takeProfitRatio) > 0;
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

    public String getPositionSide() {
        return positionSide;
    }

    public BigDecimal getPositionSize() {
        return positionSize;
    }

    public BigDecimal getEntryPrice() {
        return entryPrice;
    }

    public LocalDateTime getEntryTime() {
        return entryTime;
    }

    public int getFundingCount() {
        return fundingCount;
    }

    public BigDecimal getLastFundingRate() {
        return lastFundingRate;
    }

    public void setLastFundingRate(BigDecimal lastFundingRate) {
        this.lastFundingRate = lastFundingRate;
    }

    public BigDecimal getTotalFundingEarned() {
        return totalFundingEarned;
    }

    public BigDecimal getUnrealizedPnl() {
        return unrealizedPnl;
    }

    public BigDecimal getUnrealizedPnlRatio() {
        return unrealizedPnlRatio;
    }

    public BigDecimal getMarkPrice() {
        return markPrice;
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
