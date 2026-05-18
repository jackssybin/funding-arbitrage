package com.quant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FundingBacktestDataLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void mergesKlineCsvWithFundingCsv() throws Exception {
        Path klineCsv = tempDir.resolve("klines.csv");
        Path fundingCsv = tempDir.resolve("funding.csv");
        Files.write(klineCsv, Arrays.asList(
                "time,symbol,close",
                "2026-01-01 00:00:00,BTCUSDT,100",
                "2026-01-01 04:00:00,BTCUSDT,101",
                "2026-01-01 08:00:00,BTCUSDT,102"
        ));
        Files.write(fundingCsv, Arrays.asList(
                "time,symbol,fundingRate",
                "2026-01-01 00:00:00,BTCUSDT,0.0010",
                "2026-01-01 08:00:00,BTCUSDT,0.0020"
        ));

        FundingBacktestDataLoader loader = new FundingBacktestDataLoader();
        List<FundingBacktestEngine.MarketBar> bars = loader.loadCsv(klineCsv.toString(), fundingCsv.toString());

        assertEquals(3, bars.size());
        assertTrue(bars.get(0).fundingSettlement);
        assertEquals(0, bars.get(0).fundingRate.compareTo(new BigDecimal("0.0010")));
        assertFalse(bars.get(1).fundingSettlement);
        assertEquals(0, bars.get(1).fundingRate.compareTo(new BigDecimal("0.0010")));
        assertTrue(bars.get(2).fundingSettlement);
        assertEquals(0, bars.get(2).fundingRate.compareTo(new BigDecimal("0.0020")));
    }

    @Test
    void loadsOptionalMarketRiskColumnsFromCsv() throws Exception {
        Path klineCsv = tempDir.resolve("rich-klines.csv");
        Path fundingCsv = tempDir.resolve("rich-funding.csv");
        Files.write(klineCsv, Arrays.asList(
                "time,symbol,close,high,low,volume,bidAskSpreadRatio",
                "2026-01-01 00:00:00,BTCUSDT,100,105,95,1234,0.0008"
        ));
        Files.write(fundingCsv, Arrays.asList(
                "time,symbol,fundingRate,predictedFundingRate",
                "2026-01-01 00:00:00,BTCUSDT,0.0010,0.0012"
        ));

        FundingBacktestDataLoader loader = new FundingBacktestDataLoader();
        List<FundingBacktestEngine.MarketBar> bars = loader.loadCsv(klineCsv.toString(), fundingCsv.toString());

        assertEquals(0, bars.get(0).high.compareTo(new BigDecimal("105")));
        assertEquals(0, bars.get(0).low.compareTo(new BigDecimal("95")));
        assertEquals(0, bars.get(0).volume.compareTo(new BigDecimal("1234")));
        assertEquals(0, bars.get(0).bidAskSpreadRatio.compareTo(new BigDecimal("0.0008")));
        assertEquals(0, bars.get(0).predictedFundingRate.compareTo(new BigDecimal("0.0012")));
    }

    @Test
    void mergeSupportsMultipleSymbolsAndSortsBars() {
        FundingBacktestDataLoader loader = new FundingBacktestDataLoader();

        List<FundingBacktestEngine.MarketBar> bars = loader.merge(Arrays.asList(
                new FundingBacktestDataLoader.KlinePoint(LocalDateTime.parse("2026-01-01T08:00:00"), "ETHUSDT", new BigDecimal("2000")),
                new FundingBacktestDataLoader.KlinePoint(LocalDateTime.parse("2026-01-01T00:00:00"), "BTCUSDT", new BigDecimal("100"))
        ), Arrays.asList(
                new FundingBacktestDataLoader.FundingPoint(LocalDateTime.parse("2026-01-01T00:00:00"), "BTCUSDT", new BigDecimal("0.0010")),
                new FundingBacktestDataLoader.FundingPoint(LocalDateTime.parse("2026-01-01T08:00:00"), "ETHUSDT", new BigDecimal("-0.0015"))
        ));

        assertEquals("BTCUSDT", bars.get(0).symbol);
        assertEquals("ETHUSDT", bars.get(1).symbol);
        assertTrue(bars.get(0).fundingSettlement);
        assertTrue(bars.get(1).fundingSettlement);
        assertEquals(0, bars.get(1).fundingRate.compareTo(new BigDecimal("-0.0015")));
    }
}
