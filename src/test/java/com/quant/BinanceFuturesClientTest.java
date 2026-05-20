package com.quant;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BinanceFuturesClientTest {

    @Test
    void parsesSpotFullOrderExecutionFills() throws IOException {
        String response = "{"
                + "\"symbol\":\"BTCUSDT\","
                + "\"orderId\":12345,"
                + "\"executedQty\":\"0.00300000\","
                + "\"cummulativeQuoteQty\":\"180.30000000\","
                + "\"fills\":["
                + "{\"price\":\"60000.00000000\",\"qty\":\"0.00100000\","
                + "\"commission\":\"0.00000100\",\"commissionAsset\":\"BTC\",\"tradeId\":1},"
                + "{\"price\":\"60150.00000000\",\"qty\":\"0.00200000\","
                + "\"commission\":\"0.00000200\",\"commissionAsset\":\"BTC\",\"tradeId\":2}"
                + "]}";

        TradeExecutionReport report = BinanceFuturesClient.parseSpotOrderExecution("BTCUSDT", "12345", response);

        assertEquals(0, report.getExecutedQuantity().compareTo(new BigDecimal("0.00300000")));
        assertEquals(0, report.getNotional().compareTo(new BigDecimal("180.3000000000000000")));
        assertEquals(0, report.getAveragePrice().compareTo(new BigDecimal("60100.000000000000")));
        assertEquals(0, report.getFee().compareTo(new BigDecimal("0.00000300")));
        assertEquals("BTC", report.getFeeAsset());
        assertEquals(2, report.getFills().size());
    }

    @Test
    void fallsBackToCumulativeQuoteQtyWhenSpotFillsAreMissing() throws IOException {
        String response = "{"
                + "\"symbol\":\"ETHUSDT\","
                + "\"orderId\":67890,"
                + "\"executedQty\":\"0.50000000\","
                + "\"cummulativeQuoteQty\":\"1500.00000000\""
                + "}";

        TradeExecutionReport report = BinanceFuturesClient.parseSpotOrderExecution("ETHUSDT", "67890", response);

        assertEquals(0, report.getExecutedQuantity().compareTo(new BigDecimal("0.50000000")));
        assertEquals(0, report.getAveragePrice().compareTo(new BigDecimal("3000.000000000000")));
        assertEquals(0, report.getFee().compareTo(BigDecimal.ZERO));
        assertEquals("USDT", report.getFeeAsset());
    }
}
