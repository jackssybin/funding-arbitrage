package com.quant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Loads real historical funding rates and candles into backtest MarketBar inputs.
 */
public class FundingBacktestDataLoader {

    private static final DateTimeFormatter SPACE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int BINANCE_FUNDING_LIMIT = 1000;
    private static final int BINANCE_KLINE_LIMIT = 1500;

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;

    public FundingBacktestDataLoader() {
        this(Config.BINANCE_FUTURES_API);
    }

    FundingBacktestDataLoader(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
        this.mapper = new ObjectMapper();
    }

    public List<FundingBacktestEngine.MarketBar> loadCsv(String klineCsv, String fundingCsv) throws IOException {
        List<KlinePoint> klines = loadKlineCsv(klineCsv);
        List<FundingPoint> fundingPoints = loadFundingCsv(fundingCsv);
        return merge(klines, fundingPoints);
    }

    public List<FundingBacktestEngine.MarketBar> loadBinanceHistory(List<String> symbols, LocalDateTime start,
                                                                    LocalDateTime end, String interval)
            throws IOException {
        List<KlinePoint> klines = new ArrayList<>();
        List<FundingPoint> fundingPoints = new ArrayList<>();
        for (String symbol : symbols) {
            klines.addAll(fetchBinanceKlines(symbol, start, end, interval));
            fundingPoints.addAll(fetchBinanceFundingRates(symbol, start, end));
        }
        return merge(klines, fundingPoints);
    }

    public List<FundingBacktestEngine.MarketBar> merge(List<KlinePoint> klines, List<FundingPoint> fundingPoints) {
        Map<String, TreeMap<LocalDateTime, FundingPoint>> fundingBySymbol = new HashMap<>();
        for (FundingPoint point : fundingPoints) {
            fundingBySymbol
                    .computeIfAbsent(point.symbol, ignored -> new TreeMap<>())
                    .put(point.time, point);
        }

        List<FundingBacktestEngine.MarketBar> bars = new ArrayList<>();
        klines.sort(Comparator.comparing((KlinePoint k) -> k.time).thenComparing(k -> k.symbol));
        for (KlinePoint kline : klines) {
            TreeMap<LocalDateTime, FundingPoint> symbolFunding = fundingBySymbol.get(kline.symbol);
            BigDecimal fundingRate = BigDecimal.ZERO;
            BigDecimal predictedFundingRate = null;
            boolean settlement = false;
            if (symbolFunding != null) {
                Map.Entry<LocalDateTime, FundingPoint> exact = symbolFunding.floorEntry(kline.time);
                if (exact != null) {
                    fundingRate = exact.getValue().fundingRate;
                    predictedFundingRate = exact.getValue().predictedFundingRate;
                    settlement = exact.getKey().equals(kline.time);
                }
            }
            bars.add(new FundingBacktestEngine.MarketBar(
                    kline.time, kline.symbol, kline.closePrice, kline.highPrice, kline.lowPrice, kline.volume,
                    kline.bidAskSpreadRatio, fundingRate, predictedFundingRate, settlement));
        }
        return bars;
    }

    public static List<KlinePoint> loadKlineCsv(String file) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(file));
        List<KlinePoint> points = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(",");
            if (parts.length < 3) {
                throw new IllegalArgumentException("Invalid kline csv line: " + line);
            }
            points.add(parseKline(parts, headerIndex(lines.get(0))));
        }
        return points;
    }

    public static List<FundingPoint> loadFundingCsv(String file) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(file));
        List<FundingPoint> points = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(",");
            if (parts.length < 3) {
                throw new IllegalArgumentException("Invalid funding csv line: " + line);
            }
            Map<String, Integer> header = headerIndex(lines.get(0));
            BigDecimal predicted = optionalDecimal(parts, header, "predictedFundingRate");
            points.add(new FundingPoint(
                    parseTime(value(parts, header, "time", 0)),
                    value(parts, header, "symbol", 1),
                    new BigDecimal(value(parts, header, "fundingRate", 2)),
                    predicted));
        }
        return points;
    }

    public List<FundingPoint> fetchBinanceFundingRates(String symbol, LocalDateTime start, LocalDateTime end)
            throws IOException {
        List<FundingPoint> points = new ArrayList<>();
        long cursor = toMillis(start);
        long endMillis = toMillis(end);
        while (cursor <= endMillis) {
            String url = baseUrl + "/fapi/v1/fundingRate?symbol=" + symbol
                    + "&startTime=" + cursor
                    + "&endTime=" + endMillis
                    + "&limit=" + BINANCE_FUNDING_LIMIT;
            JsonNode array = getJson(url);
            if (!array.isArray() || array.size() == 0) {
                break;
            }
            long maxTime = cursor;
            for (JsonNode item : array) {
                long fundingTime = item.get("fundingTime").asLong();
                points.add(new FundingPoint(fromMillis(fundingTime), symbol,
                        new BigDecimal(item.get("fundingRate").asText())));
                if (fundingTime > maxTime) {
                    maxTime = fundingTime;
                }
            }
            if (array.size() < BINANCE_FUNDING_LIMIT || maxTime <= cursor) {
                break;
            }
            cursor = maxTime + 1;
        }
        return points;
    }

    public List<KlinePoint> fetchBinanceKlines(String symbol, LocalDateTime start, LocalDateTime end, String interval)
            throws IOException {
        List<KlinePoint> points = new ArrayList<>();
        long cursor = toMillis(start);
        long endMillis = toMillis(end);
        while (cursor <= endMillis) {
            String url = baseUrl + "/fapi/v1/klines?symbol=" + symbol
                    + "&interval=" + interval
                    + "&startTime=" + cursor
                    + "&endTime=" + endMillis
                    + "&limit=" + BINANCE_KLINE_LIMIT;
            JsonNode array = getJson(url);
            if (!array.isArray() || array.size() == 0) {
                break;
            }
            long maxOpenTime = cursor;
            for (JsonNode item : array) {
                long openTime = item.get(0).asLong();
                points.add(new KlinePoint(fromMillis(openTime), symbol,
                        new BigDecimal(item.get(4).asText()),
                        new BigDecimal(item.get(2).asText()),
                        new BigDecimal(item.get(3).asText()),
                        new BigDecimal(item.get(5).asText()),
                        null));
                if (openTime > maxOpenTime) {
                    maxOpenTime = openTime;
                }
            }
            if (array.size() < BINANCE_KLINE_LIMIT || maxOpenTime <= cursor) {
                break;
            }
            cursor = maxOpenTime + 1;
        }
        return points;
    }

    private JsonNode getJson(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Backtest history API failed: " + response.code() + " " + body);
            }
            return mapper.readTree(body);
        }
    }

    private static LocalDateTime parseTime(String value) {
        if (value.contains("T")) {
            return LocalDateTime.parse(value);
        }
        return LocalDateTime.parse(value, SPACE_TIME);
    }

    private static KlinePoint parseKline(String[] parts, Map<String, Integer> header) {
        BigDecimal close = new BigDecimal(value(parts, header, "close", 2));
        BigDecimal high = optionalDecimal(parts, header, "high");
        BigDecimal low = optionalDecimal(parts, header, "low");
        BigDecimal volume = optionalDecimal(parts, header, "volume");
        BigDecimal spread = optionalDecimal(parts, header, "bidAskSpreadRatio");
        return new KlinePoint(
                parseTime(value(parts, header, "time", 0)),
                value(parts, header, "symbol", 1),
                close,
                high == null ? close : high,
                low == null ? close : low,
                volume == null ? BigDecimal.ZERO : volume,
                spread);
    }

    private static Map<String, Integer> headerIndex(String headerLine) {
        String[] columns = headerLine.split(",");
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < columns.length; i++) {
            index.put(columns[i].trim(), i);
        }
        return index;
    }

    private static String value(String[] parts, Map<String, Integer> header, String name, int fallbackIndex) {
        Integer index = header.get(name);
        int effectiveIndex = index == null ? fallbackIndex : index;
        return parts[effectiveIndex].trim();
    }

    private static BigDecimal optionalDecimal(String[] parts, Map<String, Integer> header, String name) {
        Integer index = header.get(name);
        if (index == null || index >= parts.length || parts[index].trim().isEmpty()) {
            return null;
        }
        return new BigDecimal(parts[index].trim());
    }

    private static long toMillis(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static LocalDateTime fromMillis(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    public static class KlinePoint {
        public final LocalDateTime time;
        public final String symbol;
        public final BigDecimal closePrice;
        public final BigDecimal highPrice;
        public final BigDecimal lowPrice;
        public final BigDecimal volume;
        public final BigDecimal bidAskSpreadRatio;

        public KlinePoint(LocalDateTime time, String symbol, BigDecimal closePrice) {
            this(time, symbol, closePrice, closePrice, closePrice, BigDecimal.ZERO, null);
        }

        public KlinePoint(LocalDateTime time, String symbol, BigDecimal closePrice, BigDecimal highPrice,
                          BigDecimal lowPrice, BigDecimal volume, BigDecimal bidAskSpreadRatio) {
            this.time = time;
            this.symbol = symbol;
            this.closePrice = closePrice;
            this.highPrice = highPrice;
            this.lowPrice = lowPrice;
            this.volume = volume;
            this.bidAskSpreadRatio = bidAskSpreadRatio;
        }
    }

    public static class FundingPoint {
        public final LocalDateTime time;
        public final String symbol;
        public final BigDecimal fundingRate;
        public final BigDecimal predictedFundingRate;

        public FundingPoint(LocalDateTime time, String symbol, BigDecimal fundingRate) {
            this(time, symbol, fundingRate, null);
        }

        public FundingPoint(LocalDateTime time, String symbol, BigDecimal fundingRate,
                            BigDecimal predictedFundingRate) {
            this.time = time;
            this.symbol = symbol;
            this.fundingRate = fundingRate;
            this.predictedFundingRate = predictedFundingRate;
        }
    }
}
