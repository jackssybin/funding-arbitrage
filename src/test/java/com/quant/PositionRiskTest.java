package com.quant;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionRiskTest {

    @Test
    void unrealizedPnlRatioUsesMarginDenominator() {
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

        assertEquals(0, position.getUnrealizedPnl().compareTo(new BigDecimal("-50")));
        assertEquals(0, position.getUnrealizedPnlRatio().compareTo(new BigDecimal("-0.050000")));
        assertFalse(position.isStopLossTriggered(new BigDecimal("0.05")));

        position.updateUnrealizedPnl(new BigDecimal("2949"));

        assertTrue(position.isStopLossTriggered(new BigDecimal("0.05")));
    }

    @Test
    void dynamicStopLossUsesSameMarginBasisAsPnlRatio() {
        Position position = new Position("BTCUSDT");
        position.restore(
                BigDecimal.ONE,
                new BigDecimal("3000"),
                new BigDecimal("0.001"),
                "SHORT",
                LocalDateTime.now().minusHours(8),
                1,
                new BigDecimal("50")
        );

        assertEquals(0, position.getMarginValue().compareTo(new BigDecimal("1000.000000")));
        assertEquals(0, position.getDynamicStopLossRatio(new BigDecimal("0.05")).compareTo(new BigDecimal("0.100000")));
    }

    @Test
    void fundingBufferCanKeepPositionOnlyWhenPrincipalIsCovered() {
        Position position = new Position("BTCUSDT");
        position.restore(
                BigDecimal.ONE,
                new BigDecimal("3000"),
                new BigDecimal("0.001"),
                "LONG",
                LocalDateTime.now(),
                0,
                new BigDecimal("60")
        );

        position.updateUnrealizedPnl(new BigDecimal("2950"));

        assertTrue(position.isPrincipalSafe());
        assertEquals(0, position.getSafetyBuffer().compareTo(new BigDecimal("10")));
    }
}
