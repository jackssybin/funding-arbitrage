package com.quant;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FundingBacktestEngineTest {

    @Test
    void stopLossUsesMarginBasisLikeLiveTrading() {
        FundingBacktestEngine engine = new FundingBacktestEngine();
        FundingBacktestEngine.BacktestConfig config = new FundingBacktestEngine.BacktestConfig();
        config.notional = new BigDecimal("1000");
        config.openRate = new BigDecimal("0.0010");
        config.closeRate = new BigDecimal("0.0001");
        config.takerFeeRate = BigDecimal.ZERO;
        config.stopLossRatio = new BigDecimal("0.05");
        config.leverage = 3;

        FundingBacktestEngine.BacktestResult result = engine.run(Arrays.asList(
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-01T00:00:00"), "BTCUSDT", new BigDecimal("100"), new BigDecimal("0.0010")),
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-01T08:00:00"), "BTCUSDT", new BigDecimal("102"), new BigDecimal("0.0010")),
                new FundingBacktestEngine.MarketBar(LocalDateTime.parse("2026-01-01T16:00:00"), "BTCUSDT", new BigDecimal("100"), BigDecimal.ZERO)
        ), config);

        assertTrue(result.totalPnl.compareTo(BigDecimal.ZERO) < 0);
    }
}
