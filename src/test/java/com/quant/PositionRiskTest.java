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

        // 名义价值 3000 USDT，默认杠杆 3 倍 → 保证金 1000 USDT
        // 亏损 50 USDT → 亏损比例 50/1000 = 5%
        assertEquals(0, position.getUnrealizedPnl().compareTo(new BigDecimal("-50")));
        // 根据配置的杠杆计算预期的比例（默认3倍 = -0.05，如果是2倍 = -0.033333）
        BigDecimal expectedRatio = new BigDecimal("-50").divide(
                new BigDecimal("3000").divide(BigDecimal.valueOf(Config.LEVERAGE), 10, java.math.RoundingMode.HALF_UP),
                6,
                java.math.RoundingMode.HALF_UP
        );
        assertEquals(0, position.getUnrealizedPnlRatio().compareTo(expectedRatio));
        assertFalse(position.isStopLossTriggered(new BigDecimal("0.05")));

        position.updateUnrealizedPnl(new BigDecimal("2949"));

        // 亏损51时，如果是3倍杠杆 = 5.1% 触发；如果是2倍杠杆 = 3.4% 也触发
        assertTrue(position.isStopLossTriggered(new BigDecimal("0.03")));
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

        // 根据配置的杠杆计算预期的保证金
        BigDecimal expectedMargin = new BigDecimal("3000")
                .divide(BigDecimal.valueOf(Config.LEVERAGE), 6, java.math.RoundingMode.HALF_UP);
        assertEquals(0, position.getMarginValue().compareTo(expectedMargin));
        
        // 动态止损 = 基础止损(5%) + 安全垫比例(50 / 保证金)
        BigDecimal safetyRatio = new BigDecimal("50").divide(expectedMargin, 6, java.math.RoundingMode.HALF_UP);
        BigDecimal expectedDynamicStopLoss = new BigDecimal("0.05").add(safetyRatio);
        assertEquals(0, position.getDynamicStopLossRatio(new BigDecimal("0.05")).compareTo(expectedDynamicStopLoss));
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
