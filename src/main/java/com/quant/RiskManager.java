package com.quant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

public class RiskManager {
    private static final Logger log = LoggerFactory.getLogger(RiskManager.class);

    public BigDecimal calculateNetExposure(Map<String, Position> positions) {
        BigDecimal totalNotional = BigDecimal.ZERO;
        BigDecimal netNotional = BigDecimal.ZERO;
        for (Position pos : positions.values()) {
            if (!pos.hasPosition()) {
                continue;
            }
            BigDecimal notional = pos.getPositionSize().multiply(pos.getEntryPrice());
            totalNotional = totalNotional.add(notional);
            netNotional = "LONG".equals(pos.getPositionSide())
                    ? netNotional.add(notional)
                    : netNotional.subtract(notional);
        }
        if (totalNotional.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return netNotional.divide(totalNotional, 4, RoundingMode.HALF_UP);
    }

    public boolean isExposureAcceptable(Map<String, Position> positions, String newSide) {
        BigDecimal currentExposure = calculateNetExposure(positions);
        BigDecimal totalNotional = BigDecimal.ZERO;
        BigDecimal netNotional = BigDecimal.ZERO;
        for (Position pos : positions.values()) {
            if (!pos.hasPosition()) {
                continue;
            }
            BigDecimal notional = pos.getPositionSize().multiply(pos.getEntryPrice());
            totalNotional = totalNotional.add(notional);
            netNotional = "LONG".equals(pos.getPositionSide())
                    ? netNotional.add(notional)
                    : netNotional.subtract(notional);
        }
        BigDecimal newPositionNotional = Config.POSITION_VALUE_USDT.multiply(BigDecimal.valueOf(Config.LEVERAGE));
        totalNotional = totalNotional.add(newPositionNotional);
        netNotional = "LONG".equals(newSide)
                ? netNotional.add(newPositionNotional)
                : netNotional.subtract(newPositionNotional);
        BigDecimal newExposure = totalNotional.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : netNotional.divide(totalNotional, 4, RoundingMode.HALF_UP);
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
