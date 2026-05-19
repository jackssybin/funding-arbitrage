package com.quant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

public class RiskManager {
    private static final Logger log = LoggerFactory.getLogger(RiskManager.class);

    /**
     * 计算净敞口比例（基于账户总余额）
     * @param positions 当前持仓
     * @param accountBalance 账户总余额
     * @return 净敞口比例，正数表示净多头，负数表示净空头
     */
    public BigDecimal calculateNetExposure(Map<String, Position> positions, BigDecimal accountBalance) {
        BigDecimal netNotional = BigDecimal.ZERO;
        for (Position pos : positions.values()) {
            if (!pos.hasPosition()) {
                continue;
            }
            BigDecimal notional = pos.getPositionSize().multiply(pos.getEntryPrice());
            netNotional = "LONG".equals(pos.getPositionSide())
                    ? netNotional.add(notional)
                    : netNotional.subtract(notional);
        }
        if (accountBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        // ✅ 修复：敞口 = 净头寸名义价值 / 账户总余额，而非总持仓价值
        return netNotional.divide(accountBalance, 4, RoundingMode.HALF_UP);
    }

    /**
     * 检查开仓后净敞口是否在允许范围内
     * @param positions 当前持仓
     * @param newSide 新开仓方向 (LONG/SHORT)
     * @param accountBalance 账户总余额
     * @return 是否允许开仓
     */
    public boolean isExposureAcceptable(Map<String, Position> positions, String newSide, BigDecimal accountBalance) {
        BigDecimal currentExposure = calculateNetExposure(positions, accountBalance);
        BigDecimal netNotional = BigDecimal.ZERO;
        for (Position pos : positions.values()) {
            if (!pos.hasPosition()) {
                continue;
            }
            BigDecimal notional = pos.getPositionSize().multiply(pos.getEntryPrice());
            netNotional = "LONG".equals(pos.getPositionSide())
                    ? netNotional.add(notional)
                    : netNotional.subtract(notional);
        }
        // POSITION_VALUE_USDT 是杠杆后的名义价值
        BigDecimal newPositionNotional = Config.POSITION_VALUE_USDT;
        netNotional = "LONG".equals(newSide)
                ? netNotional.add(newPositionNotional)
                : netNotional.subtract(newPositionNotional);
        BigDecimal newExposure = accountBalance.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : netNotional.divide(accountBalance, 4, RoundingMode.HALF_UP);
        boolean acceptable = newExposure.abs().compareTo(Config.MAX_NET_EXPOSURE) <= 0;
        if (!acceptable) {
            log.warn("⚠️  净敞口超限: 当前{}%，开{}后将达{}%，限制{}%，跳过开仓",
                    currentExposure.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                    newSide,
                    newExposure.abs().multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                    Config.MAX_NET_EXPOSURE.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP));
        }
        return acceptable;
    }
}
