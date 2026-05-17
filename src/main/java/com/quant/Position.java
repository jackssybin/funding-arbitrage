package com.quant;
import java.math.RoundingMode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
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
     * ========== 优化3: 移仓成本测算 - 移仓前确保净收益为正 ==========
     * 计算移仓的净收益预期（考虑所有成本）
     */
    public SwitchCostAnalysis analyzeSwitchCost(BigDecimal newRate, BigDecimal switchThreshold, BigDecimal feeCost) {
        BigDecimal currentRate = this.lastFundingRate.abs();
        BigDecimal rateDiff = newRate.subtract(currentRate);
        
        SwitchCostAnalysis result = new SwitchCostAnalysis();
        result.currentRate = currentRate;
        result.newRate = newRate;
        result.rateDiff = rateDiff;
        result.feeCost = feeCost;
        
        // 成本1：移仓手续费（双边）
        result.totalCost = feeCost.multiply(new BigDecimal("2"));  // 开仓+平仓
        
        // 成本2：错过本次结算的机会成本
        // 如果临近结算，这个成本很大
        long hoursToNextFunding = getHoursToNextFunding();
        if (hoursToNextFunding <= 2) {
            // 2小时内要结算了，错过这次结算的成本 = 一次完整的资金费
            result.missedFundingCost = currentRate;
            result.totalCost = result.totalCost.add(currentRate);
        } else if (hoursToNextFunding <= 4) {
            // 4小时内结算，机会成本按比例计算
            BigDecimal missedRatio = new BigDecimal(4 - hoursToNextFunding).divide(new BigDecimal("4"), 4, RoundingMode.HALF_UP);
            result.missedFundingCost = currentRate.multiply(missedRatio);
            result.totalCost = result.totalCost.add(result.missedFundingCost);
        }
        
        // 预期收益：费率差 × 预计持仓时间（假设至少持仓2个结算周期=16小时）
        // 预期每8小时收益 = 费率差
        // 预期2个周期收益 = 费率差 × 2
        result.expectedReturn = rateDiff.multiply(new BigDecimal("2"));
        
        // 净收益 = 预期收益 - 总成本
        result.netReturn = result.expectedReturn.subtract(result.totalCost);
        
        // 是否划算：净收益 > 0，且满足最小阈值
        result.worthIt = result.netReturn.compareTo(BigDecimal.ZERO) > 0 
                && rateDiff.compareTo(switchThreshold) > 0
                && getHoldingHours() >= 6;
        
        return result;
    }
    
    /**
     * 获取距离下一次资金费结算的小时数（UTC 0,8,16点）
     */
    public long getHoursToNextFunding() {
        ZonedDateTime utcNow = ZonedDateTime.now(java.time.ZoneOffset.UTC);
        int currentHour = utcNow.getHour();
        
        // 结算小时：0, 8, 16
        int[] fundingHours = {0, 8, 16, 24};  // 24用于边界处理
        
        for (int fundingHour : fundingHours) {
            if (fundingHour > currentHour) {
                return fundingHour - currentHour;
            }
        }
        // 如果当前是23点，下一次是0点（次日）
        return 24 - currentHour;
    }
    
    /**
     * ========== 优化1: 结算时间感知 - 临近结算不轻易平仓/移仓 ==========
     * 是否临近资金费结算时间（2小时内）
     */
    public boolean isNearFundingTime() {
        long hoursToNext = getHoursToNextFunding();
        return hoursToNext <= 2;
    }
    
    /**
     * 是否非常临近结算（1小时内）- 这个时间段绝对不移仓/平仓
     */
    public boolean isVeryNearFundingTime() {
        long hoursToNext = getHoursToNextFunding();
        return hoursToNext <= 1;
    }
    
    /**
     * 简化版移仓判断（向后兼容）
     */
    public boolean isSwitchWorthIt(BigDecimal newRate, BigDecimal switchThreshold, BigDecimal feeCost) {
        SwitchCostAnalysis analysis = analyzeSwitchCost(newRate, switchThreshold, feeCost);
        
        // 临近结算时不移仓
        if (isVeryNearFundingTime()) {
            return false;
        }
        
        return analysis.worthIt;
    }
    
    /**
     * 移仓成本分析结果
     */
    public static class SwitchCostAnalysis {
        public BigDecimal currentRate;       // 当前费率
        public BigDecimal newRate;           // 新费率
        public BigDecimal rateDiff;          // 费率差
        public BigDecimal feeCost;           // 单次手续费
        public BigDecimal totalCost;         // 总成本（手续费+机会成本）
        public BigDecimal missedFundingCost; // 错过结算的机会成本
        public BigDecimal expectedReturn;    // 预期收益
        public BigDecimal netReturn;         // 净收益
        public boolean worthIt;              // 是否划算
        
        @Override
        public String toString() {
            return String.format(
                "费率差:%.4f%%, 总成本:%.4f%%, 预期收益:%.4f%%, 净收益:%.4f%%, %s",
                rateDiff.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                totalCost.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                expectedReturn.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                netReturn.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                worthIt ? "✅划算" : "❌不划算"
            );
        }
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
     * 设置累计资金费收益（用于状态恢复）
     */
    public void setTotalFundingEarned(BigDecimal amount) {
        this.totalFundingEarned = amount;
    }

    /**
     * 设置资金费结算次数（用于状态恢复）
     */
    public void setFundingCount(int count) {
        this.fundingCount = count;
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
     * ========== 优化1: 动态止损 - 用已赚资金费做安全垫 ==========
     * 计算动态止损比例：已赚资金费越多，止损越宽松
     * 原则：确保不亏本金（资金费收益 >= 价格亏损时不止损）
     */
    public BigDecimal getDynamicStopLossRatio(BigDecimal baseStopLossRatio) {
        if (totalFundingEarned == null || BigDecimal.ZERO.compareTo(totalFundingEarned) >= 0) {
            return baseStopLossRatio;  // 还没赚到资金费，用原始止损
        }
        
        // 仓位名义价值
        BigDecimal notionalValue = positionSize.multiply(entryPrice);
        if (BigDecimal.ZERO.compareTo(notionalValue) >= 0) {
            return baseStopLossRatio;
        }
        
        // 已赚资金费占仓位的比例 = 安全垫比例
        BigDecimal safetyRatio = totalFundingEarned.divide(notionalValue, 6, RoundingMode.HALF_UP);
        
        // 动态止损 = 原始止损 + 安全垫
        // 例如：原始止损5%，已赚3%资金费 → 动态止损8%
        BigDecimal dynamicStopLoss = baseStopLossRatio.add(safetyRatio);
        
        // 最大不超过15%（防止极端情况）
        BigDecimal maxStopLoss = new BigDecimal("0.15");
        return dynamicStopLoss.compareTo(maxStopLoss) < 0 ? dynamicStopLoss : maxStopLoss;
    }
    
    /**
     * 是否触发止损（动态版本）
     */
    public boolean isStopLossTriggered(BigDecimal stopLossRatio) {
        if (unrealizedPnlRatio == null) return false;
        // 使用动态止损比例
        BigDecimal dynamicStopLoss = getDynamicStopLossRatio(stopLossRatio);
        return unrealizedPnlRatio.compareTo(dynamicStopLoss.negate()) < 0;
    }
    
    /**
     * 获取当前止损缓冲（已赚资金费 - 当前浮亏）
     * 正数表示还安全，负数表示已经开始亏本金
     */
    public BigDecimal getSafetyBuffer() {
        if (totalFundingEarned == null || unrealizedPnl == null) {
            return BigDecimal.ZERO;
        }
        return totalFundingEarned.add(unrealizedPnl);
    }
    
    /**
     * 是否保本（已赚资金费覆盖当前浮亏）
     */
    public boolean isPrincipalSafe() {
        BigDecimal buffer = getSafetyBuffer();
        return buffer.compareTo(BigDecimal.ZERO) >= 0;
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
