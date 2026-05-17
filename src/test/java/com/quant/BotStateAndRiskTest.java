package com.quant;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotStateAndRiskTest {

    @Test
    void restoresPersistedPositionState() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        Map<String, Position> positions = positionsOf(bot);
        positions.put("BTCUSDT", new Position("BTCUSDT"));

        StrategyPersistence.StrategyState state = new StrategyPersistence.StrategyState();
        StrategyPersistence.PositionState ps = new StrategyPersistence.PositionState();
        ps.symbol = "BTCUSDT";
        ps.positionSide = "SHORT";
        ps.positionSize = 2.0;
        ps.entryPrice = 50000.0;
        ps.lastFundingRate = 0.001;
        ps.entryTime = LocalDateTime.now().minusHours(9).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        ps.fundingCount = 3;
        ps.totalFundingEarned = 42.5;
        state.positions.add(ps);

        Method restore = FundingArbitrageBot.class.getDeclaredMethod("restorePositions", StrategyPersistence.StrategyState.class);
        restore.setAccessible(true);
        restore.invoke(bot, state);

        Position restored = positions.get("BTCUSDT");
        assertTrue(restored.hasPosition());
        assertEquals("SHORT", restored.getPositionSide());
        assertEquals(3, restored.getFundingCount());
        assertEquals(0, restored.getTotalFundingEarned().compareTo(new BigDecimal("42.5")));
        assertTrue(restored.getHoldingHours() >= 8);
    }

    @Test
    void acceptsOppositeSideWhenItReducesNetExposure() throws Exception {
        FundingArbitrageBot bot = new FundingArbitrageBot();
        Map<String, Position> positions = positionsOf(bot);

        Position shortPosition = new Position("BTCUSDT");
        BigDecimal existingNotional = Config.POSITION_VALUE_USDT.multiply(BigDecimal.valueOf(Config.LEVERAGE));
        shortPosition.restore(
                BigDecimal.ONE,
                existingNotional,
                new BigDecimal("0.001"),
                "SHORT",
                LocalDateTime.now().minusHours(8),
                0,
                BigDecimal.ZERO
        );
        positions.put("BTCUSDT", shortPosition);

        Method exposureCheck = FundingArbitrageBot.class.getDeclaredMethod("isExposureAcceptable", String.class);
        exposureCheck.setAccessible(true);

        assertTrue((Boolean) exposureCheck.invoke(bot, "LONG"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Position> positionsOf(FundingArbitrageBot bot) throws Exception {
        Field field = FundingArbitrageBot.class.getDeclaredField("positions");
        field.setAccessible(true);
        return (Map<String, Position>) field.get(bot);
    }
}
