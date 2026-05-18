package com.quant;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;

class DebugTest {
    @Test
    void debugTest() {
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

        System.out.println("=== Test 1: unrealizedPnlRatioUsesMarginDenominator ===");
        System.out.println("Config.LEVERAGE: " + Config.LEVERAGE);
        System.out.println("PnL: " + position.getUnrealizedPnl());
        System.out.println("Expected PnL: -50");
        System.out.println("PnL Ratio: " + position.getUnrealizedPnlRatio());
        System.out.println("Expected Ratio: -0.050000");
        System.out.println("Margin: " + position.getMarginValue());
        System.out.println("StopLoss 0.05 triggered? " + position.isStopLossTriggered(new BigDecimal("0.05")));
        
        System.out.println("\n=== Test 2: dynamicStopLossUsesSameMarginBasisAsPnlRatio ===");
        Position pos2 = new Position("BTCUSDT");
        pos2.restore(
                BigDecimal.ONE,
                new BigDecimal("3000"),
                new BigDecimal("0.001"),
                "SHORT",
                LocalDateTime.now().minusHours(8),
                1,
                new BigDecimal("50")
        );
        System.out.println("Margin: " + pos2.getMarginValue());
        System.out.println("Expected Margin: 1000.000000");
        System.out.println("Dynamic StopLoss: " + pos2.getDynamicStopLossRatio(new BigDecimal("0.05")));
        System.out.println("Expected: 0.100000");
        System.out.println("Safety Ratio: " + new BigDecimal("50").divide(pos2.getMarginValue(), 6, java.math.RoundingMode.HALF_UP));
    }
}
