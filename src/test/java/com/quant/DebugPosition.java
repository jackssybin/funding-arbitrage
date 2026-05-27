package com.quant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

public class DebugPosition {
    public static void main(String[] args) {
        System.out.println("Config.LEVERAGE = " + Config.LEVERAGE);
        
        Position position = new Position("BTCUSDT");
        position.restore(
                BigDecimal.ONE,
                new BigDecimal("3000"),
                new BigDecimal("0.001"),
                "LONG",
                LocalDateTime.now(),
                0,
                BigDecimal.ZERO
        );
        
        position.updateUnrealizedPnl(new BigDecimal("2950"));
        
        System.out.println("unrealizedPnl = " + position.getUnrealizedPnl());
        System.out.println("unrealizedPnlRatio = " + position.getUnrealizedPnlRatio());
        
        BigDecimal stopLossRatio = new BigDecimal("0.05");
        BigDecimal dynamicStopLoss = position.getDynamicStopLossRatio(stopLossRatio);
        System.out.println("stopLossRatio = " + stopLossRatio);
        System.out.println("dynamicStopLoss = " + dynamicStopLoss);
        System.out.println("dynamicStopLoss.negate() = " + dynamicStopLoss.negate());
        
        int compareResult = position.getUnrealizedPnlRatio().compareTo(dynamicStopLoss.negate());
        System.out.println("unrealizedPnlRatio.compareTo(dynamicStopLoss.negate()) = " + compareResult);
        System.out.println("isStopLossTriggered(0.05) = " + position.isStopLossTriggered(stopLossRatio));
        
        BigDecimal expectedRatio = new BigDecimal("-50").divide(
                new BigDecimal("3000").divide(BigDecimal.valueOf(Config.LEVERAGE), 10, RoundingMode.HALF_UP),
                6,
                RoundingMode.HALF_UP
        );
        System.out.println("expectedRatio = " + expectedRatio);
        System.out.println("ratio equals? " + (position.getUnrealizedPnlRatio().compareTo(expectedRatio) == 0));
    }
}
