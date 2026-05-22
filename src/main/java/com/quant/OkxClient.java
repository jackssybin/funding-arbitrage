package com.quant;
import java.math.RoundingMode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * OKX 永续合约 API 客户端（实现 ExchangeClient 接口）
 *
 * OKX 签名规则与 Binance 完全不同：
 * - 签名算法：Base64(HMAC-SHA256(timestamp + method + requestPath + body))
 * - 请求头：OK-ACCESS-KEY / OK-ACCESS-SIGN / OK-ACCESS-TIMESTAMP / OK-ACCESS-PASSPHRASE
 * - 时间格式：ISO 8601，例如 2024-01-01T00:00:00.000Z
 *
 * OKX 合约品种说明：
 * - 现货：BTC-USDT
 * - U本位永续：BTC-USDT-SWAP
 */
public class OkxClient implements ExchangeClient {

    private static final Logger log = LoggerFactory.getLogger(OkxClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String BASE_URL    = "https://www.okx.com";
    private static final String SIM_BASE_URL = "https://www.okx.com"; // OKX 模拟盘同域名，靠 header 区分

    private final String apiKey;
    private final String secretKey;
    private final String passphrase;
    private final boolean simulated; // OKX 官方模拟盘（Testnet），需要 x-simulated-trading:1 头

    private final OkHttpClient httpClient;
    private final Map<String, InstrumentSpec> swapInstrumentCache = new HashMap<>();
    private volatile PositionMode positionMode;

    // 模拟模式余额维护（解决余额永远10000的BUG）
    private BigDecimal simulatedBalance = new BigDecimal("10000");

    enum PositionMode {
        LONG_SHORT,
        NET
    }

    static class InstrumentSpec {
        final String symbol;
        final String instId;
        final BigDecimal ctVal;
        final BigDecimal lotSz;
        final BigDecimal minSz;
        final BigDecimal tickSz;

        InstrumentSpec(String symbol, String instId, BigDecimal ctVal, BigDecimal lotSz,
                       BigDecimal minSz, BigDecimal tickSz) {
            this.symbol = symbol;
            this.instId = instId;
            this.ctVal = ctVal;
            this.lotSz = lotSz;
            this.minSz = minSz;
            this.tickSz = tickSz;
        }
    }

    @Override
    public void updateSimulatedBalance(BigDecimal delta) {
        if (Config.SIMULATION_MODE) {
            this.simulatedBalance = this.simulatedBalance.add(delta);
            log.info("💰 [模拟模式] 余额更新: {} USDT ({})",
                    this.simulatedBalance.setScale(2, RoundingMode.HALF_UP),
                    delta.compareTo(BigDecimal.ZERO) >= 0 ? "+" + delta.setScale(2, RoundingMode.HALF_UP) : delta.setScale(2, RoundingMode.HALF_UP));
        }
    }

    @Override
    public BigDecimal getSimulatedBalance() {
        return this.simulatedBalance;
    }

    public OkxClient(String apiKey, String secretKey, String passphrase, boolean simulated) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.passphrase = passphrase;
        this.simulated = simulated;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getExchangeName() {
        return "OKX";
    }

    private TradeExecutionReport getSpotExecutionReport(String symbol, String orderId, BigDecimal fallbackQuantity)
            throws IOException {
        String instId = toOkxSpotInstId(symbol);
        TradeExecutionReport report = new TradeExecutionReport(symbol, orderId);

        String fillsPath = "/api/v5/trade/fills-history?instType=SPOT&instId=" + instId + "&ordId=" + orderId;
        Request fillsRequest = buildSignedRequest("GET", fillsPath, "");
        try (Response response = httpClient.newCall(fillsRequest).execute()) {
            String body = checkResponse(response, "query OKX spot fills");
            JsonNode fills = mapper.readTree(body).get("data");
            if (fills != null) {
                for (JsonNode fill : fills) {
                    report.addFill(
                            readDecimal(fill, "fillPx", "0"),
                            readDecimal(fill, "fillSz", "0"),
                            readDecimal(fill, "fee", "0"),
                            fill.hasNonNull("feeCcy") ? fill.get("feeCcy").asText() : "USDT",
                            BigDecimal.ZERO,
                            fill.hasNonNull("tradeId") ? fill.get("tradeId").asText() : null
                    );
                }
            }
        }

        if (report.getExecutedQuantity().compareTo(BigDecimal.ZERO) > 0) {
            return report;
        }

        String orderPath = "/api/v5/trade/order?instId=" + instId + "&ordId=" + orderId;
        Request orderRequest = buildSignedRequest("GET", orderPath, "");
        try (Response response = httpClient.newCall(orderRequest).execute()) {
            String body = checkResponse(response, "query OKX spot order");
            JsonNode data = mapper.readTree(body).get("data");
            if (data != null && data.size() > 0) {
                JsonNode order = data.get(0);
                BigDecimal avgPx = readDecimal(order, "avgPx", "0");
                BigDecimal accFillSz = readDecimal(order, "accFillSz",
                        fallbackQuantity == null ? "0" : fallbackQuantity.toPlainString());
                BigDecimal fee = readDecimal(order, "fee", "0");
                String feeCcy = order.hasNonNull("feeCcy") ? order.get("feeCcy").asText() : "USDT";
                if (avgPx.compareTo(BigDecimal.ZERO) > 0 && accFillSz.compareTo(BigDecimal.ZERO) > 0) {
                    report.addFill(avgPx, accFillSz, fee, feeCcy, BigDecimal.ZERO, orderId);
                }
            }
        }

        if (report.getExecutedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IOException("OKX spot order " + orderId + " has no confirmed fills");
        }
        return report;
    }

    // ===================================================================
    // 行情接口（无签名）
    // ===================================================================

    @Override
    public MarketSnapshot getMarketSnapshot(String symbol) throws IOException {
        if (Config.SIMULATION_MODE) {
            BigDecimal price = getCurrentPrice(symbol);
            return new MarketSnapshot(symbol, price,
                    price.multiply(new BigDecimal("1.01")),
                    price.multiply(new BigDecimal("0.99")),
                    BigDecimal.ZERO,
                    price.multiply(new BigDecimal("0.9999")),
                    price.multiply(new BigDecimal("1.0001")),
                    BigDecimal.ZERO);
        }

        String instId = toOkxInstId(symbol);
        String url = BASE_URL + "/api/v5/market/ticker?instId=" + instId;

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "market snapshot");
            JsonNode ticker = mapper.readTree(body).get("data").get(0);
            BigDecimal last = readDecimal(ticker, "last", "0");
            BigDecimal high24h = readDecimal(ticker, "high24h", last.toPlainString());
            BigDecimal low24h = readDecimal(ticker, "low24h", last.toPlainString());
            BigDecimal volume = readDecimal(ticker, "volCcy24h", readDecimal(ticker, "vol24h", "0").toPlainString());
            BigDecimal open24h = readDecimal(ticker, "open24h", last.toPlainString());
            BigDecimal priceChangeRatio = open24h.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : last.subtract(open24h).divide(open24h, 8, RoundingMode.HALF_UP);
            BigDecimal bid = ticker.hasNonNull("bidPx") && !ticker.get("bidPx").asText().isEmpty()
                    ? new BigDecimal(ticker.get("bidPx").asText())
                    : null;
            BigDecimal ask = ticker.hasNonNull("askPx") && !ticker.get("askPx").asText().isEmpty()
                    ? new BigDecimal(ticker.get("askPx").asText())
                    : null;
            return new MarketSnapshot(symbol, last, high24h, low24h, volume, bid, ask, priceChangeRatio);
        }
    }

    /**
     * 获取多个币种的资金费率
     * OKX 接口：GET /api/v5/public/funding-rate?instId=BTC-USDT-SWAP
     * 需要逐个请求（OKX 没有批量接口，需要并发）
     */
    @Override
    public Map<String, BigDecimal> getAllFundingRates(List<String> symbols) throws IOException {
        Map<String, BigDecimal> rates = new HashMap<String, BigDecimal>();

        if (Config.SIMULATION_MODE) {
            for (int i = 0; i < symbols.size(); i++) {
                double baseRate = 0.0001 + i * 0.0002;
                double variation = (Math.random() - 0.5) * 0.0004;
                rates.put(symbols.get(i), new BigDecimal(baseRate + variation).setScale(6, RoundingMode.HALF_UP));
            }
            return rates;
        }

        for (String symbol : symbols) {
            try {
                BigDecimal rate = getFundingRate(symbol);
                rates.put(symbol, rate);
            } catch (Exception e) {
                log.warn("获取 {} 资金费率失败: {}", symbol, e.getMessage());
            }
        }
        return rates;
    }

    /**
     * 获取单个币种的资金费率
     * OKX instId 格式：BTC-USDT-SWAP（从 BTCUSDT 转换）
     */
    @Override
    public BigDecimal getFundingRate(String symbol) throws IOException {
        if (Config.SIMULATION_MODE) {
            return new BigDecimal(0.0009 + Math.random() * 0.0003).setScale(6, RoundingMode.HALF_UP);
        }

        String instId = toOkxInstId(symbol);
        String url = BASE_URL + "/api/v5/public/funding-rate?instId=" + instId;

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "获取资金费率");
            JsonNode json = mapper.readTree(body);
            String rate = json.get("data").get(0).get("fundingRate").asText();
            return new BigDecimal(rate);
        }
    }

    @Override
    public BigDecimal getPredictedFundingRate(String symbol) throws IOException {
        if (Config.SIMULATION_MODE) {
            return getFundingRate(symbol);
        }

        String instId = toOkxInstId(symbol);
        String url = BASE_URL + "/api/v5/public/funding-rate?instId=" + instId;

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "get predicted funding rate");
            JsonNode item = mapper.readTree(body).get("data").get(0);
            String predicted = item.hasNonNull("nextFundingRate") && !item.get("nextFundingRate").asText().isEmpty()
                    ? item.get("nextFundingRate").asText()
                    : item.get("fundingRate").asText();
            return new BigDecimal(predicted);
        }
    }

    /**
     * 获取当前价格
     * OKX 接口：GET /api/v5/market/ticker?instId=BTC-USDT-SWAP
     */
    @Override
    public BigDecimal getCurrentPrice(String symbol) throws IOException {
        if (Config.SIMULATION_MODE) {
            if (symbol.contains("BTC")) return new BigDecimal(60000 + Math.random() * 5000).setScale(2, RoundingMode.HALF_UP);
            if (symbol.contains("ETH")) return new BigDecimal(3000 + Math.random() * 300).setScale(2, RoundingMode.HALF_UP);
            return new BigDecimal(10 + Math.random() * 5).setScale(4, RoundingMode.HALF_UP);
        }

        String instId = toOkxInstId(symbol);
        String url = BASE_URL + "/api/v5/market/ticker?instId=" + instId;

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "获取价格");
            JsonNode json = mapper.readTree(body);
            return new BigDecimal(json.get("data").get(0).get("last").asText());
        }
    }

    /**
     * 获取24h涨跌幅
     * OKX 接口：GET /api/v5/market/ticker?instId=BTC-USDT-SWAP
     */
    @Override
    public BigDecimal get24hChange(String symbol) throws IOException {
        if (Config.SIMULATION_MODE) {
            return BigDecimal.ZERO;
        }

        String instId = toOkxInstId(symbol);
        String url = BASE_URL + "/api/v5/market/ticker?instId=" + instId;

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "获取24h涨跌幅");
            JsonNode json = mapper.readTree(body);
            JsonNode ticker = json.get("data").get(0);
            BigDecimal last = new BigDecimal(ticker.get("last").asText());
            BigDecimal open24h = new BigDecimal(ticker.get("open24h").asText());
            if (open24h.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO;
            }
            return last.subtract(open24h).divide(open24h, 8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 获取当前持仓（实现ExchangeClient接口）
     */
    @Override
    public BigDecimal getCurrentPosition(String symbol) throws IOException {
        return getPositionAmount(symbol);
    }

    @Override
    public List<FundingIncomeRecord> getFundingIncomeRecords(String symbol, long startTimeMillis, long endTimeMillis)
            throws IOException {
        List<FundingIncomeRecord> records = new ArrayList<FundingIncomeRecord>();

        if (Config.SIMULATION_MODE) {
            return records;
        }

        String instId = toOkxInstId(symbol);
        String path = "/api/v5/account/bills?instType=SWAP&instId=" + instId
                + "&begin=" + startTimeMillis
                + "&end=" + endTimeMillis;

        Request request = buildSignedRequest("GET", path, "");
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "查询资金费账单");
            JsonNode json = mapper.readTree(body);
            JsonNode data = json.get("data");
            if (data == null) {
                return records;
            }

            for (JsonNode item : data) {
                String subType = item.has("subType") ? item.get("subType").asText() : "";
                if (!"173".equals(subType) && !"174".equals(subType)) {
                    continue;
                }
                BigDecimal income = new BigDecimal(item.get("balChg").asText("0"));
                long time = item.get("ts").asLong();
                String billId = item.has("billId") ? item.get("billId").asText() : instId + "-" + time;
                records.add(new FundingIncomeRecord(symbol, income, time, billId));
            }
        }

        return records;
    }

    Map<String, ExchangePrecision.SymbolFilters> loadOkxPrecisionFilters(List<String> symbols, boolean spot)
            throws IOException {
        Map<String, ExchangePrecision.SymbolFilters> filtersBySymbol = new HashMap<>();
        String instType = spot ? "SPOT" : "SWAP";
        String url = BASE_URL + "/api/v5/public/instruments?instType=" + instType;
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "load OKX " + instType + " instruments");
            JsonNode data = mapper.readTree(body).get("data");
            if (data == null) {
                return filtersBySymbol;
            }
            for (JsonNode item : data) {
                String symbol = fromOkxInstId(item.path("instId").asText());
                if (!symbols.contains(symbol)) {
                    continue;
                }
                ExchangePrecision.SymbolFilters filters = new ExchangePrecision.SymbolFilters();
                filters.symbol = symbol;
                filters.tickSize = readDecimal(item, "tickSz", "0.00000001");
                filters.tickScale = Math.max(0, filters.tickSize.stripTrailingZeros().scale());
                if (spot) {
                    filters.stepSize = readDecimal(item, "lotSz", "0.00000001");
                    filters.minQty = readDecimal(item, "minSz", filters.stepSize.toPlainString());
                    filters.maxQty = new BigDecimal("999999999");
                } else {
                    InstrumentSpec spec = toInstrumentSpec(symbol, item);
                    swapInstrumentCache.put(symbol, spec);
                    filters.stepSize = spec.ctVal.multiply(spec.lotSz);
                    filters.minQty = spec.ctVal.multiply(spec.minSz);
                    filters.maxQty = spec.ctVal.multiply(new BigDecimal("999999999"));
                }
                filters.stepScale = Math.max(0, filters.stepSize.stripTrailingZeros().scale());
                filtersBySymbol.put(symbol, filters);
            }
        }
        return filtersBySymbol;
    }

    private InstrumentSpec getSwapInstrument(String symbol) throws IOException {
        InstrumentSpec cached = swapInstrumentCache.get(symbol);
        if (cached != null) {
            return cached;
        }
        if (Config.SIMULATION_MODE) {
            InstrumentSpec spec = defaultSimulatedSwapInstrument(symbol);
            swapInstrumentCache.put(symbol, spec);
            return spec;
        }

        String instId = toOkxInstId(symbol);
        String url = BASE_URL + "/api/v5/public/instruments?instType=SWAP&instId=" + instId;
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "load OKX swap instrument");
            JsonNode data = mapper.readTree(body).get("data");
            if (data == null || data.size() == 0) {
                throw new IOException("OKX instrument not found: " + instId);
            }
            InstrumentSpec spec = toInstrumentSpec(symbol, data.get(0));
            swapInstrumentCache.put(symbol, spec);
            return spec;
        }
    }

    private InstrumentSpec toInstrumentSpec(String symbol, JsonNode item) {
        String instId = item.path("instId").asText(toOkxInstId(symbol));
        BigDecimal ctVal = readDecimal(item, "ctVal", "1");
        BigDecimal lotSz = readDecimal(item, "lotSz", "1");
        BigDecimal minSz = readDecimal(item, "minSz", lotSz.toPlainString());
        BigDecimal tickSz = readDecimal(item, "tickSz", "0.00000001");
        return new InstrumentSpec(symbol, instId, ctVal, lotSz, minSz, tickSz);
    }

    private InstrumentSpec defaultSimulatedSwapInstrument(String symbol) {
        BigDecimal ctVal;
        if (symbol.startsWith("BTC")) {
            ctVal = new BigDecimal("0.01");
        } else if (symbol.startsWith("ETH")) {
            ctVal = new BigDecimal("0.1");
        } else if (symbol.startsWith("DOGE")) {
            ctVal = new BigDecimal("100");
        } else {
            ctVal = BigDecimal.ONE;
        }
        return new InstrumentSpec(symbol, toOkxInstId(symbol), ctVal, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("0.00000001"));
    }

    static String normalizeOkxMarginMode(String configuredMode) {
        String mode = configuredMode == null ? "" : configuredMode.trim().toLowerCase(Locale.ROOT);
        if ("cross".equals(mode) || "cross_margin".equals(mode)) {
            return "cross";
        }
        if ("isolated".equals(mode) || "iso".equals(mode)) {
            return "isolated";
        }
        throw new IllegalArgumentException("Unsupported OKX_MARGIN_MODE: " + configuredMode
                + ". Use isolated or cross.");
    }

    private String okxMarginMode() {
        return normalizeOkxMarginMode(Config.OKX_MARGIN_MODE);
    }

    BigDecimal toContractSize(String symbol, BigDecimal baseQuantity) throws IOException {
        InstrumentSpec spec = getSwapInstrument(symbol);
        BigDecimal contracts = baseQuantity.divide(spec.ctVal, 0, RoundingMode.DOWN);
        BigDecimal aligned = contracts.divide(spec.lotSz, 0, RoundingMode.DOWN).multiply(spec.lotSz);
        if (aligned.compareTo(spec.minSz) < 0) {
            throw new IOException(symbol + " OKX contract size " + aligned + " is below minSz " + spec.minSz);
        }
        return aligned.stripTrailingZeros();
    }

    BigDecimal toBaseQuantity(String symbol, BigDecimal contracts) throws IOException {
        return contracts.multiply(getSwapInstrument(symbol).ctVal);
    }

    void cacheSwapInstrumentForTesting(String symbol, BigDecimal ctVal, BigDecimal lotSz, BigDecimal minSz) {
        swapInstrumentCache.put(symbol, new InstrumentSpec(symbol, toOkxInstId(symbol), ctVal, lotSz, minSz,
                new BigDecimal("0.00000001")));
    }

    private PositionMode getPositionMode() throws IOException {
        if (positionMode != null) {
            return positionMode;
        }
        if (Config.SIMULATION_MODE) {
            positionMode = PositionMode.LONG_SHORT;
            return positionMode;
        }
        String path = "/api/v5/account/config";
        Request request = buildSignedRequest("GET", path, "");
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "query OKX account config");
            JsonNode data = mapper.readTree(body).get("data");
            String mode = data != null && data.size() > 0 ? data.get(0).path("posMode").asText("") : "";
            positionMode = "net_mode".equalsIgnoreCase(mode) ? PositionMode.NET : PositionMode.LONG_SHORT;
            log.info("OKX position mode detected: {}", positionMode);
            return positionMode;
        }
    }

    private String placeSwapMarketOrder(String symbol, BigDecimal baseQuantity, String side,
                                        String longShortPosSide, boolean reduceOnly) throws IOException {
        BigDecimal contracts = toContractSize(symbol, baseQuantity);
        PositionMode mode = getPositionMode();
        String instId = toOkxInstId(symbol);
        String marginMode = okxMarginMode();
        StringBuilder body = new StringBuilder();
        body.append("{\"instId\":\"").append(instId)
                .append("\",\"tdMode\":\"").append(marginMode).append("\",\"side\":\"").append(side)
                .append("\",\"ordType\":\"market\",\"sz\":\"").append(contracts.toPlainString()).append("\"");
        if (mode == PositionMode.LONG_SHORT) {
            body.append(",\"posSide\":\"").append(longShortPosSide).append("\"");
        } else if (reduceOnly) {
            body.append(",\"reduceOnly\":\"true\"");
        }
        body.append("}");

        Request request = buildSignedRequest("POST", "/api/v5/trade/order", body.toString());
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = checkResponse(response, "place OKX swap order");
            JsonNode json = mapper.readTree(responseBody);
            String ordId = json.get("data").get(0).get("ordId").asText();
            log.info("OKX swap order placed: symbol={}, baseQty={}, contracts={}, mode={}, orderId={}",
                    symbol, baseQuantity, contracts, mode, ordId);
            return ordId;
        }
    }

    private void postSetLeverage(String instId, int leverage, String posSide) throws IOException {
        String path = "/api/v5/account/set-leverage";
        String marginMode = okxMarginMode();
        StringBuilder body = new StringBuilder();
        body.append("{\"instId\":\"").append(instId)
                .append("\",\"lever\":\"").append(leverage)
                .append("\",\"mgnMode\":\"").append(marginMode).append("\"");
        if (posSide != null && !posSide.isEmpty()) {
            body.append(",\"posSide\":\"").append(posSide).append("\"");
        }
        body.append("}");
        Request request = buildSignedRequest("POST", path, body.toString());
        try (Response response = httpClient.newCall(request).execute()) {
            checkResponse(response, "set OKX leverage");
        }
    }

    // ===================================================================
    // 合约接口（需要签名）
    // ===================================================================

    /**
     * 设置杠杆
     * OKX 接口：POST /api/v5/account/set-leverage
     */
    @Override
    public void setLeverage(String symbol, int leverage) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] OKX 设置 {} 杠杆 {}x", symbol, leverage);
            return;
        }

        String instId = toOkxInstId(symbol);
        if (getPositionMode() == PositionMode.LONG_SHORT) {
            postSetLeverage(instId, leverage, "long");
            postSetLeverage(instId, leverage, "short");
            log.info("OKX set {} leverage {}x for long/short position mode", symbol, leverage);
            return;
        }
        String path = "/api/v5/account/set-leverage";
        String bodyStr = String.format("{\"instId\":\"%s\",\"lever\":\"%d\",\"mgnMode\":\"%s\"}",
                instId, leverage, okxMarginMode());

        Request request = buildSignedRequest("POST", path, bodyStr);
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "设置杠杆");
            log.info("✅ OKX 设置 {} 杠杆 {}x 成功", symbol, leverage);
        }
    }

    /**
     * 合约做空（开空永续合约）
     * OKX 接口：POST /api/v5/trade/order
     * side=sell, posSide=short, tdMode=Config.OKX_MARGIN_MODE
     */
    @Override
    public String openShort(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] OKX 合约做空 {} 数量 {}", symbol, quantity);
            return "OKX_SIM_" + System.currentTimeMillis();
        }
        if (!Config.SIMULATION_MODE) {
            return placeSwapMarketOrder(symbol, quantity, "sell", "short", false);
        }

        String instId = toOkxInstId(symbol);
        String path = "/api/v5/trade/order";
        // OKX 合约下单数量单位是"张"，1张 = 合约乘数（sz 字段）
        // 这里假设已提前处理好，quantity 为张数
        String bodyStr = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"%s\",\"side\":\"sell\",\"posSide\":\"short\"," +
                "\"ordType\":\"market\",\"sz\":\"%s\"}",
                instId, okxMarginMode(), quantity.toPlainString());

        Request request = buildSignedRequest("POST", path, bodyStr);
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "合约做空");
            JsonNode json = mapper.readTree(body);
            String ordId = json.get("data").get(0).get("ordId").asText();
            log.info("✅ OKX 合约做空成功: orderId={}, sz={}", ordId, quantity);
            return ordId;
        }
    }

    /**
     * 合约平空（买入平仓）
     * OKX 接口：POST /api/v5/trade/order
     * side=buy, posSide=short, reduceOnly
     */
    @Override
    public String closeShort(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] OKX 平空 {} 数量 {}", symbol, quantity);
            return "OKX_SIM_CLOSE_" + System.currentTimeMillis();
        }
        if (!Config.SIMULATION_MODE) {
            return placeSwapMarketOrder(symbol, quantity, "buy", "short", true);
        }

        String instId = toOkxInstId(symbol);
        String path = "/api/v5/trade/order";
        String bodyStr = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"%s\",\"side\":\"buy\",\"posSide\":\"short\"," +
                "\"ordType\":\"market\",\"sz\":\"%s\"}",
                instId, okxMarginMode(), quantity.toPlainString());

        Request request = buildSignedRequest("POST", path, bodyStr);
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "平合约空单");
            JsonNode json = mapper.readTree(body);
            String ordId = json.get("data").get(0).get("ordId").asText();
            log.info("✅ OKX 平空成功: orderId={}", ordId);
            return ordId;
        }
    }

    /**
     * Bug-6修复: 合约做多（开多永续合约，负费率时使用）
     * side=buy, posSide=long, tdMode=Config.OKX_MARGIN_MODE
     */
    @Override
    public String openLong(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] OKX 合约做多 {} 数量 {}", symbol, quantity);
            return "OKX_SIM_LONG_" + System.currentTimeMillis();
        }
        if (!Config.SIMULATION_MODE) {
            return placeSwapMarketOrder(symbol, quantity, "buy", "long", false);
        }

        String instId = toOkxInstId(symbol);
        String path = "/api/v5/trade/order";
        String bodyStr = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"%s\",\"side\":\"buy\",\"posSide\":\"long\"," +
                "\"ordType\":\"market\",\"sz\":\"%s\"}",
                instId, okxMarginMode(), quantity.toPlainString());

        Request request = buildSignedRequest("POST", path, bodyStr);
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "合约做多");
            JsonNode json = mapper.readTree(body);
            String ordId = json.get("data").get(0).get("ordId").asText();
            log.info("✅ OKX 合约做多成功: orderId={}, sz={}", ordId, quantity);
            return ordId;
        }
    }

    /**
     * Bug-6修复: 合约平多（卖出平仓，reduceOnly 语义由 posSide=long 保证）
     * side=sell, posSide=long, tdMode=Config.OKX_MARGIN_MODE
     */
    @Override
    public String closeLong(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] OKX 平多 {} 数量 {}", symbol, quantity);
            return "OKX_SIM_CLOSE_LONG_" + System.currentTimeMillis();
        }
        if (!Config.SIMULATION_MODE) {
            return placeSwapMarketOrder(symbol, quantity, "sell", "long", true);
        }

        String instId = toOkxInstId(symbol);
        String path = "/api/v5/trade/order";
        String bodyStr = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"%s\",\"side\":\"sell\",\"posSide\":\"long\"," +
                "\"ordType\":\"market\",\"sz\":\"%s\"}",
                instId, okxMarginMode(), quantity.toPlainString());

        Request request = buildSignedRequest("POST", path, bodyStr);
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "平合约多单");
            JsonNode json = mapper.readTree(body);
            String ordId = json.get("data").get(0).get("ordId").asText();
            log.info("✅ OKX 平多成功: orderId={}", ordId);
            return ordId;
        }
    }

    /**
     * 查询合约持仓数量
     * OKX 接口：GET /api/v5/account/positions?instId=BTC-USDT-SWAP
     */
    @Override
    public TradeExecutionReport getTradeExecutionReport(String symbol, String orderIds, BigDecimal fallbackQuantity,
                                                        BigDecimal fallbackPrice) throws IOException {
        if (Config.SIMULATION_MODE || orderIds == null || orderIds.trim().isEmpty()) {
            return TradeExecutionReport.estimated(symbol, orderIds, fallbackQuantity, fallbackPrice);
        }

        String instId = toOkxInstId(symbol);
        TradeExecutionReport report = new TradeExecutionReport(symbol, orderIds);
        for (String rawOrderId : orderIds.split(",")) {
            String orderId = rawOrderId.trim();
            if (orderId.isEmpty() || orderId.startsWith("OKX_SIM_")) {
                continue;
            }
            appendOrderFills(report, instId, orderId);
        }

        if (report.getExecutedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("{} OKX order {} fills not found, using estimated execution report", symbol, orderIds);
            return TradeExecutionReport.estimated(symbol, orderIds, fallbackQuantity, fallbackPrice);
        }
        log.info("{} OKX order {} execution report: qty={}, avgPrice={}, fee={} {}",
                symbol,
                orderIds,
                report.getExecutedQuantity().setScale(8, RoundingMode.HALF_UP),
                report.getAveragePrice().setScale(8, RoundingMode.HALF_UP),
                report.getFee().setScale(8, RoundingMode.HALF_UP),
                report.getFeeAsset());
        return report;
    }

    private void appendOrderFills(TradeExecutionReport report, String instId, String orderId) throws IOException {
        String path = "/api/v5/trade/fills-history?instType=SWAP&instId=" + instId + "&ordId=" + orderId;
        Request request = buildSignedRequest("GET", path, "");
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "查询OKX成交明细");
            JsonNode fills = mapper.readTree(body).get("data");
            if (fills == null) {
                return;
            }
            String symbol = fromOkxInstId(instId);
            for (JsonNode fill : fills) {
                BigDecimal fillContracts = readDecimal(fill, "fillSz", "0");
                BigDecimal fillBaseQuantity = toBaseQuantity(symbol, fillContracts);
                report.addFill(
                        readDecimal(fill, "fillPx", "0"),
                        fillBaseQuantity,
                        readDecimal(fill, "fee", "0"),
                        fill.hasNonNull("feeCcy") ? fill.get("feeCcy").asText() : "USDT",
                        readDecimal(fill, "fillPnl", "0"),
                        fill.hasNonNull("tradeId") ? fill.get("tradeId").asText() : null
                );
            }
        }
    }

    @Override
    public BigDecimal getPositionAmount(String symbol) throws IOException {
        if (Config.SIMULATION_MODE) return BigDecimal.ZERO;

        String instId = toOkxInstId(symbol);
        String path = "/api/v5/account/positions?instType=SWAP&instId=" + instId;

        Request request = buildSignedRequest("GET", path, "");
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "查询持仓");
            JsonNode json = mapper.readTree(body);
            JsonNode data = json.get("data");
            BigDecimal totalBasePosition = BigDecimal.ZERO;
            if (data != null && data.size() > 0) {
                // pos 为正代表多仓，为负代表空仓（我们用空仓，所以取负值）
                for (JsonNode item : data) {
                    BigDecimal contracts = readDecimal(item, "pos", "0");
                    BigDecimal baseQty = toBaseQuantity(symbol, contracts.abs());
                    String posSide = item.path("posSide").asText("");
                    if ("short".equalsIgnoreCase(posSide) || contracts.compareTo(BigDecimal.ZERO) < 0) {
                        totalBasePosition = totalBasePosition.subtract(baseQty);
                    } else {
                        totalBasePosition = totalBasePosition.add(baseQty);
                    }
                }
                return totalBasePosition;
            }
            return BigDecimal.ZERO;
        }
    }

    /**
     * 查询账户余额 (USDT)
     * OKX 接口：GET /api/v5/account/balance?ccy=USDT
     */
    @Override
    public BigDecimal getBalance() throws IOException {
        if (Config.SIMULATION_MODE) return this.simulatedBalance;

        String path = "/api/v5/asset/balances?ccy=USDT";
        Request request = buildSignedRequest("GET", path, "");
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "查询余额");
            JsonNode json = mapper.readTree(body);
            // details[0].availBal
            JsonNode details = json.get("data").get(0).get("details");
            for (JsonNode detail : details) {
                if ("USDT".equals(detail.get("ccy").asText())) {
                    BigDecimal bal = new BigDecimal(detail.get("availBal").asText());
                    log.info("💰 OKX 可用余额: {} USDT", bal.setScale(2, RoundingMode.HALF_UP));
                    return bal;
                }
            }
            return BigDecimal.ZERO;
        }
    }

    // ===================================================================
    // 现货接口
    // ===================================================================

    /**
     * 获取现货账户 USDT 余额
     * OKX 接口：GET /api/v5/account/balance（ccy=USDT）
     */
    @Override
    public BigDecimal getSpotBalance() throws IOException {
        if (Config.SIMULATION_MODE) {
            // 模拟模式：返回合约余额相同的数字
            return getBalance().multiply(new BigDecimal("0.8"));
        }

        String path = "/api/v5/account/balance?ccy=USDT";
        Request request = buildSignedRequest("GET", path, "");
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "获取现货余额");
            JsonNode json = mapper.readTree(body);
            JsonNode data = json.get("data");
            if (data != null && data.size() > 0) {
                JsonNode details = data.get(0).get("details");
                if (details != null) {
                    for (JsonNode detail : details) {
                        if ("USDT".equalsIgnoreCase(detail.path("ccy").asText())) {
                            return readDecimal(detail, "availBal", "0");
                        }
                    }
                }
            }
            return BigDecimal.ZERO;
        }
    }

    /**
     * 现货买入
     * OKX 接口：POST /api/v5/trade/order（instType=SPOT）
     */
    @Override
    public String buySpot(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] OKX 现货买入 {} 数量 {}", symbol, quantity);
            return "OKX_SIM_SPOT_BUY_" + System.currentTimeMillis();
        }

        // 现货 instId 格式：BTC-USDT（不带 -SWAP）
        String instId = toOkxSpotInstId(symbol);
        String path = "/api/v5/trade/order";
        String bodyStr = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"cash\",\"side\":\"buy\",\"ordType\":\"market\","
                        + "\"sz\":\"%s\",\"tgtCcy\":\"base_ccy\"}",
                instId, quantity.toPlainString());

        Request request = buildSignedRequest("POST", path, bodyStr);
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "现货买入");
            JsonNode json = mapper.readTree(body);
            return json.get("data").get(0).get("ordId").asText();
        }
    }

    /**
     * 现货卖出
     */
    @Override
    public String sellSpot(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] OKX 现货卖出 {} 数量 {}", symbol, quantity);
            return "OKX_SIM_SPOT_SELL_" + System.currentTimeMillis();
        }

        String instId = toOkxSpotInstId(symbol);
        String path = "/api/v5/trade/order";
        String bodyStr = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"cash\",\"side\":\"sell\",\"ordType\":\"market\",\"sz\":\"%s\"}",
                instId, quantity.toPlainString());

        Request request = buildSignedRequest("POST", path, bodyStr);
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "现货卖出");
            JsonNode json = mapper.readTree(body);
            return json.get("data").get(0).get("ordId").asText();
        }
    }

    /** ========== P2 修复：现货买入返回完整成交报告 ========== */
    @Override
    public TradeExecutionReport buySpotWithReport(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] OKX 现货买入 {} 数量 {}", symbol, quantity);
            TradeExecutionReport report = new TradeExecutionReport(symbol, "OKX_SIM_SPOT_BUY_" + System.currentTimeMillis());
            BigDecimal price = getCurrentPrice(symbol);
            report.addFill(price, quantity, price.multiply(quantity).multiply(Config.SPOT_TAKER_FEE_RATE), "USDT", BigDecimal.ZERO, "sim");
            return report;
        }
        String orderId = buySpot(symbol, quantity);
        return getSpotExecutionReport(symbol, orderId, quantity);
    }

    /** ========== P2 修复：现货卖出返回完整成交报告 ========== */
    @Override
    public TradeExecutionReport sellSpotWithReport(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] OKX 现货卖出 {} 数量 {}", symbol, quantity);
            TradeExecutionReport report = new TradeExecutionReport(symbol, "OKX_SIM_SPOT_SELL_" + System.currentTimeMillis());
            BigDecimal price = getCurrentPrice(symbol);
            report.addFill(price, quantity, price.multiply(quantity).multiply(Config.SPOT_TAKER_FEE_RATE), "USDT", BigDecimal.ZERO, "sim");
            return report;
        }
        String orderId = sellSpot(symbol, quantity);
        return getSpotExecutionReport(symbol, orderId, quantity);
    }

    public boolean transferFromSpotToFutures(BigDecimal amount) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[simulation] OKX transfer {} USDT from funding to trading account", amount);
            return true;
        }
        String path = "/api/v5/asset/transfer";
        String bodyStr = String.format(
                "{\"ccy\":\"USDT\",\"amt\":\"%s\",\"from\":\"6\",\"to\":\"18\",\"type\":\"0\"}",
                amount.toPlainString());
        Request request = buildSignedRequest("POST", path, bodyStr);
        try (Response response = httpClient.newCall(request).execute()) {
            checkResponse(response, "OKX asset transfer");
            return true;
        }
    }

    // ===================================================================
    // 签名与工具方法
    // ===================================================================

    @Override
    public boolean testConnection() {
        if (Config.SIMULATION_MODE) {
            log.info("✅ [模拟模式] OKX API 连接正常（模拟）");
            return true;
        }
        try {
            BigDecimal rate = getFundingRate("BTCUSDT");
            log.info("✅ OKX API 连接正常，当前 BTC 资金费率: {}%",
                    rate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
            return true;
        } catch (Exception e) {
            log.error("❌ OKX API 连接失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 构建带签名的请求
     * OKX 签名 = Base64(HMAC-SHA256(timestamp + method + requestPath + body))
     */
    private Request buildSignedRequest(String method, String path, String body) {
        String requestBody = body == null ? "" : body;
        String timestamp = getIsoTimestamp();
        String preHash = timestamp + method.toUpperCase() + path + requestBody;
        String sign = base64HmacSha256(preHash, secretKey);

        Request.Builder builder = new Request.Builder()
                .url(BASE_URL + path)
                .header("OK-ACCESS-KEY",       apiKey)
                .header("OK-ACCESS-SIGN",      sign)
                .header("OK-ACCESS-TIMESTAMP", timestamp)
                .header("OK-ACCESS-PASSPHRASE", passphrase)
                .header("Content-Type",        "application/json");

        // OKX 模拟盘需要加这个头
        if (simulated) {
            builder.header("x-simulated-trading", "1");
        }

        if ("POST".equalsIgnoreCase(method)) {
            RequestBody rb = RequestBody.create(requestBody, MediaType.parse("application/json"));
            builder.post(rb);
        } else {
            builder.get();
        }

        return builder.build();
    }

    /** ISO 8601 格式时间戳，例如：2024-01-01T00:00:00.000Z */
    private String getIsoTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    /** HMAC-SHA256 后 Base64 编码 */
    private String base64HmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("OKX 签名失败", e);
        }
    }

    /** 检查响应状态，提取 body 字符串，如果 OKX 业务码非 0 则抛出异常 */
    private String checkResponse(Response response, String action) throws IOException {
        String body = response.body().string();
        if (!response.isSuccessful()) {
            throw new IOException(action + " HTTP 失败 " + response.code() + ": " + body);
        }
        // OKX 业务层错误码在 code 字段，0 = 成功
        JsonNode json;
        try {
            json = mapper.readTree(body);
        } catch (Exception e) {
            return body; // 非 JSON 响应直接返回
        }
        if (json.has("code") && !"0".equals(json.get("code").asText())) {
            String msg = json.has("msg") ? json.get("msg").asText() : body;
            throw new IOException(action + " OKX 业务错误: code=" + json.get("code").asText() + ", msg=" + msg);
        }
        return body;
    }

    private static BigDecimal readDecimal(JsonNode node, String field, String fallback) {
        if (node == null || !node.hasNonNull(field) || node.get(field).asText().isEmpty()) {
            return new BigDecimal(fallback);
        }
        return new BigDecimal(node.get(field).asText());
    }

    /**
     * 将 Binance 风格的 symbol 转换为 OKX 永续合约 instId
     * BTCUSDT -> BTC-USDT-SWAP
     */
    private String toOkxInstId(String symbol) {
        if (symbol.endsWith("USDT")) {
            String base = symbol.substring(0, symbol.length() - 4);
            return base + "-USDT-SWAP";
        }
        // 兜底：原样返回
        return symbol;
    }

    /**
     * 将 Binance 风格的 symbol 转换为 OKX 现货 instId
     * BTCUSDT -> BTC-USDT
     */
    private String toOkxSpotInstId(String symbol) {
        if (symbol.endsWith("USDT")) {
            String base = symbol.substring(0, symbol.length() - 4);
            return base + "-USDT";
        }
        return symbol;
    }

    private String fromOkxInstId(String instId) {
        if (instId == null) {
            return "";
        }
        String normalized = instId.endsWith("-SWAP")
                ? instId.substring(0, instId.length() - "-SWAP".length())
                : instId;
        return normalized.replace("-", "");
    }
}
