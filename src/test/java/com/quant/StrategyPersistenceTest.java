package com.quant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategyPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void costDeviationStatsUseOnlyPositiveRealExecutionDeltas() {
        StrategyPersistence persistence = new StrategyPersistence(tempDir.toFile());

        persistence.recordExecutionCost("OPEN", "BTCUSDT", "SHORT", "order-1",
                new BigDecimal("0.50"), new BigDecimal("0.70"),
                new BigDecimal("0.20"), new BigDecimal("0.25"),
                BigDecimal.ONE, new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("100"),
                false);
        persistence.recordExecutionCost("CLOSE", "BTCUSDT", "SHORT", "order-2",
                new BigDecimal("0.50"), new BigDecimal("0.40"),
                new BigDecimal("0.20"), new BigDecimal("0.30"),
                BigDecimal.ZERO, new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("100"),
                false);
        persistence.recordExecutionCost("OPEN", "BTCUSDT", "SHORT", "estimated",
                new BigDecimal("0.50"), new BigDecimal("1.00"),
                new BigDecimal("0.20"), new BigDecimal("1.00"),
                BigDecimal.ONE, new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("100"),
                true);

        StrategyPersistence.CostDeviationStats stats =
                persistence.getRecentCostDeviationStats("BTCUSDT", 100, false);

        assertEquals(2, stats.sampleCount);
        assertEquals(0, stats.positiveFeeDelta.compareTo(new BigDecimal("0.20000000")));
        assertEquals(0, stats.positiveSlippageDelta.compareTo(new BigDecimal("0.15000000")));
        assertEquals(0, stats.averagePositiveOneWayDelta().compareTo(new BigDecimal("0.17500000")));
    }
}
