package com.quant;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OkxClientTest {

    @Test
    void convertsBaseCoinQuantityToOkxContractSize() throws Exception {
        OkxClient client = new OkxClient("key", "secret", "passphrase", true);
        client.cacheSwapInstrumentForTesting("BTCUSDT", new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE);
        client.cacheSwapInstrumentForTesting("DOGEUSDT", new BigDecimal("100"), BigDecimal.ONE, BigDecimal.ONE);

        assertEquals(0, client.toContractSize("BTCUSDT", new BigDecimal("0.025")).compareTo(new BigDecimal("2")));
        assertEquals(0, client.toBaseQuantity("BTCUSDT", new BigDecimal("2")).compareTo(new BigDecimal("0.02")));
        assertEquals(0, client.toContractSize("DOGEUSDT", new BigDecimal("250")).compareTo(new BigDecimal("2")));
        assertEquals(0, client.toBaseQuantity("DOGEUSDT", new BigDecimal("2")).compareTo(new BigDecimal("200")));
    }

    @Test
    void rejectsOkxContractOrderBelowMinimumContractSize() {
        OkxClient client = new OkxClient("key", "secret", "passphrase", true);
        client.cacheSwapInstrumentForTesting("DOGEUSDT", new BigDecimal("100"), BigDecimal.ONE, BigDecimal.ONE);

        assertThrows(java.io.IOException.class,
                () -> client.toContractSize("DOGEUSDT", new BigDecimal("99")));
    }

    @Test
    void normalizesOkxMarginModeAliases() {
        assertEquals("isolated", OkxClient.normalizeOkxMarginMode("iso"));
        assertEquals("isolated", OkxClient.normalizeOkxMarginMode("isolated"));
        assertEquals("cross", OkxClient.normalizeOkxMarginMode("cross_margin"));
        assertEquals("cross", OkxClient.normalizeOkxMarginMode("cross"));
        assertThrows(IllegalArgumentException.class, () -> OkxClient.normalizeOkxMarginMode("portfolio"));
    }
}
