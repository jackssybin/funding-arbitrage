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
    private BigDecimal positionSize;        // 合约持仓数量
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
    private LocalDateTime lastStopLossTime; // 最后止损时间（冷却期用）

    // ========== 现货对冲相关字段 ==========
    private boolean hedged;                 // 是否启用现货对冲
    private BigDecimal spotPositionSize;    // 现货持仓数量
    private BigDecimal spotEntryPrice;      // 现货开仓均价
    private BigDecimal hedgeRatio;          // 对冲比例
    private java.util.LinkedList<BigDecimal> rateHistory = new java.util.LinkedList<>(); // 费率历史（用于稳定检查）

    public Position(String symbol) {
        this.symbol = symbol;
        this.hasPosition = false;
        this.positionSize = BigDecimal.ZERO;
        this.entryPrice = BigDecimal.ZERO;
        this.lastFundingRate = BigDecimal.ZERO;
        this.totalFundingEarned = BigDecimal.ZERO;
        this.gridProfit = BigDecimal.ZERO;
        this.gridOrders = new ArrayList<>();
        this.hedged = false;
        this.spotPositionSize = BigDecimal.ZERO;
        this.spotEntryPrice = BigDecimal.ZERO;
        this.hedgeRatio = BigDecimal.ZERO;
    }

    /**
     * 开仓（纯合约模式）
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
        this.hedged = false;
        this.spotPositionSize = BigDecimal.ZERO;
        this.spotEntryPrice = BigDecimal.ZERO;
        this.hedgeRatio = BigDecimal.ZERO;
    }

    /**
     * 开仓（带现货对冲）
     */
    public void openWithHedge(BigDecimal contractSize, BigDecimal contractPrice, 
                              BigDecimal spotSize, BigDecimal spotPrice,
                              BigDecimal fundingRate, String side, BigDecimal hedgeRatio) {
        this.hasPosition = true;
        this.positionSize = contractSize;
        this.entryPrice = contractPrice;
        this.spotPositionSize = spotSize;
        this.spotEntryPrice = spotPrice;
        this.lastFundingRate = fundingRate;
        this.positionSide = side;
        this.entryTime = LocalDateTime.now();
        this.fundingCount = 0;
        this.totalFundingEarned = BigDecimal.ZERO;
        this.gridOrders.clear();
        this.hedged = true;
        this.hedgeRatio = hedgeRatio;
    }

    /**
     * 从持久化状态恢复持仓，保留原始开仓时间和资金费安全垫。
     */
    public void restore(BigDecimal size, BigDecimal price, BigDecimal fundingRate, String side,
                        LocalDateTime entryTime, int fundingCount, BigDecimal totalFundingEarned) {
        this.hasPosition = true;
        this.positionSize = size;
        this.entryPrice = price;
        this.lastFundingRate = fundingRate;
        this.positionSide = side;
        this.entryTime = entryTime;
        this.fundingCount = fundingCount;
        this.totalFundingEarned = totalFundingEarned == null ? BigDecimal.ZERO : totalFundingEarned;
        this.gridOrders.clear();
        this.hedged = false;
        this.spotPositionSize = BigDecimal.ZERO;
        this.spotEntryPrice = BigDecimal.ZERO;
        this.hedgeRatio = BigDecimal.ZERO;
    }

    public void restoreWithHedge(BigDecimal contractSize, BigDecimal contractPrice,
                                 BigDecimal spotSize, BigDecimal spotPrice,
                                 BigDecimal fundingRate, String side, LocalDateTime entryTime,
                                 int fundingCount, BigDecimal totalFundingEarned,
                                 BigDecimal hedgeRatio) {
        restore(contractSize, contractPrice, fundingRate, side, entryTime, fundingCount, totalFundingEarned);
        this.hedged = true;
        this.spotPositionSize = spotSize == null ? BigDecimal.ZERO : spotSize;
        this.spotEntryPrice = spotPrice == null ? BigDecimal.ZERO : spotPrice;
        this.hedgeRatio = hedgeRatio == null ? BigDecimal.ZERO : hedgeRatio;
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
     * 获取持仓分钟数（用于初期风控）
     */
    public long getHoldingMinutes() {
        if (entryTime == null) return 0;
        return java.time.Duration.between(entryTime, LocalDateTime.now()).toMinutes();
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
     * 平仓（同时平现货）
     */
    public void close() {
        this.hasPosition = false;
        this.positionSize = BigDecimal.ZERO;
        this.spotPositionSize = BigDecimal.ZERO;
        this.hedged = false;
        this.gridOrders.clear();
    }

    // ========== 现货对冲相关方法 ==========
    public void markContractLegClosed() {
        this.positionSize = BigDecimal.ZERO;
        this.unrealizedPnl = BigDecimal.ZERO;
        this.unrealizedPnlRatio = BigDecimal.ZERO;
    }

    public boolean isSpotOnlyAfterContractClose() {
        return hasPosition
                && hedged
                && (positionSize == null || positionSize.compareTo(BigDecimal.ZERO) <= 0)
                && spotPositionSize != null
                && spotPositionSize.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isHedged() {
        return hedged;
    }

    public BigDecimal getSpotPositionSize() {
        return spotPositionSize;
    }

    public BigDecimal getSpotEntryPrice() {
        return spotEntryPrice;
    }

    public BigDecimal getHedgeRatio() {
        return hedgeRatio;
    }

    /**
     * 获取现货持仓的名义价值
     */
    public BigDecimal getSpotNotionalValue(BigDecimal currentPrice) {
        if (spotPositionSize == null || spotPositionSize.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return spotPositionSize.multiply(currentPrice);
    }

    /**
     * 获取合约持仓的名义价值
     */
    public BigDecimal getContractNotionalValue(BigDecimal currentPrice) {
        if (positionSize == null || positionSize.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return positionSize.multiply(currentPrice);
    }

    /**
     * 计算现货持仓盈亏
     */
    public BigDecimal getSpotPnl(BigDecimal currentPrice) {
        if (!hedged || spotPositionSize.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal priceReturn = currentPrice.subtract(spotEntryPrice)
                .divide(spotEntryPrice, 8, RoundingMode.HALF_UP);
        return getSpotNotionalValue(spotEntryPrice).multiply(priceReturn);
    }

    /**
     * 计算总盈亏（合约+现货）
     */
    public BigDecimal getTotalPnlWithHedge(BigDecimal currentPrice) {
        BigDecimal contractPnl = unrealizedPnl != null ? unrealizedPnl : BigDecimal.ZERO;
        BigDecimal spotPnl = getSpotPnl(currentPrice);
        // SHORT 方向：合约亏 = 现货赚，所以直接相加即可（负负得正）
        return contractPnl.add(spotPnl);
    }

    /**
     * 检查是否满足最低持仓时间要求
     */
    public boolean meetsMinHoldingTime() {
        return getHoldingHours() >= Config.MIN_HOLDING_HOURS;
    }

    /**
     * 计算持仓偏离度（用于再平衡检查）
     * @return 偏离比例（如 0.10 = 偏离10%）
     */
    public BigDecimal getHedgeDeviationRatio(BigDecimal currentPrice) {
        if (!hedged) return BigDecimal.ZERO;
        BigDecimal contractNotional = getContractNotionalValue(currentPrice);
        BigDecimal spotNotional = getSpotNotionalValue(currentPrice);
        if (contractNotional.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        
        // |合约价值 - 现货价值| / 合约价值
        return contractNotional.subtract(spotNotional).abs()
                .divide(contractNotional, 8, RoundingMode.HALF_UP);
    }

    /**
     * 检查是否需要再平衡
     * @param threshold 偏离阈值（如 0.10 = 10%）
     */
    public boolean needsRebalance(BigDecimal currentPrice, BigDecimal threshold) {
        if (!hedged) return false;
        return getHedgeDeviationRatio(currentPrice).compareTo(threshold) > 0;
    }

    /**
     * 记录止损时间（用于冷却期检查）
     */
    public void recordStopLossTime() {
        this.lastStopLossTime = LocalDateTime.now();
    }

    /**
     * 检查是否在止损冷却期内
     * @return true=在冷却期内，不能开仓
     */
    public boolean isInStopLossCooldown() {
        if (lastStopLossTime == null) {
            return false;
        }
        long msSinceStopLoss = java.time.Duration.between(lastStopLossTime, LocalDateTime.now()).toMillis();
        return msSinceStopLoss < Config.STOP_LOSS_COOLDOWN_MS;
    }

    /**
     * 记录费率历史（用于费率稳定检查）
     */
    public void addRateHistory(BigDecimal rate) {
        rateHistory.addLast(rate);
        // 最多保留最近 N 次费率记录
        while (rateHistory.size() > Config.RATE_STABLE_CHECK_COUNT + 1) {
            rateHistory.removeFirst();
        }
    }

    /**
     * 检查费率是否稳定（最近 N 次都高于阈值）
     * @param minRate 最小费率阈值
     * @return true=费率稳定，可以开仓
     */
    public boolean isRateStable(BigDecimal minRate) {
        // 历史记录不足，暂时认为不稳定
        if (rateHistory.size() < Config.RATE_STABLE_CHECK_COUNT) {
            return false;
        }
        // 检查最近 N 次费率是否都高于阈值
        int checkCount = 0;
        for (BigDecimal r : rateHistory) {
            if (r.abs().compareTo(minRate) >= 0) {
                checkCount++;
            }
        }
        return checkCount >= Config.RATE_STABLE_CHECK_COUNT;
    }

    /**
     * 获取费率历史记录数量（用于日志）
     */
    public int getRateHistorySize() {
        return rateHistory.size();
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
        
        // ✅ 修复：盈亏比例 = 盈亏 / 保证金（而不是名义价值）
        // 保证金 = 名义价值 / 杠杆倍数
        // 这样 5% 止损就是真实的 5% 保证金亏损
        BigDecimal margin = getMarginValue(notionalValue);
        if (margin.compareTo(BigDecimal.ZERO) > 0) {
            this.unrealizedPnlRatio = this.unrealizedPnl.divide(margin, 6, RoundingMode.HALF_UP);
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
        BigDecimal margin = getMarginValue(notionalValue);
        if (BigDecimal.ZERO.compareTo(margin) >= 0) {
            return baseStopLossRatio;
        }
        
        // 已赚资金费占仓位的比例 = 安全垫比例
        BigDecimal safetyRatio = totalFundingEarned.divide(margin, 6, RoundingMode.HALF_UP);
        
        // 动态止损 = 原始止损 + 安全垫
        // 例如：原始止损5%，已赚3%资金费 → 动态止损8%
        BigDecimal dynamicStopLoss = baseStopLossRatio.add(safetyRatio);
        
        // 最大不超过15%（防止极端情况）
        BigDecimal maxStopLoss = new BigDecimal("0.15");
        return dynamicStopLoss.compareTo(maxStopLoss) < 0 ? dynamicStopLoss : maxStopLoss;
    }

    public BigDecimal getMarginValue() {
        return getMarginValue(positionSize.multiply(entryPrice));
    }

    private BigDecimal getMarginValue(BigDecimal notionalValue) {
        if (notionalValue == null || BigDecimal.ZERO.compareTo(notionalValue) >= 0 || Config.LEVERAGE <= 0) {
            return BigDecimal.ZERO;
        }
        return notionalValue.divide(BigDecimal.valueOf(Config.LEVERAGE), 6, RoundingMode.HALF_UP);
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
