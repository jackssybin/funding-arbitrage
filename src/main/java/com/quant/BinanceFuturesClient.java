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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 币安合约API客户端
 * 完整实现:资金费率获取、合约下单、平仓、持仓查询
 */
public class BinanceFuturesClient implements ExchangeClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceFuturesClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    private final String apiKey;
    private final String secretKey;
    private final String baseUrl;
    private final String spotBaseUrl;

    // ========== P0 修复：时间戳同步 ==========
    // Binance API 要求时间戳偏差 < 1000ms
    private long timeOffsetMs = 0;
    private static final long RECV_WINDOW_MS = 10000;  // 10秒超时窗口

    // 模拟模式余额维护（解决余额永远10000的BUG）
    private BigDecimal simulatedBalance = new BigDecimal("10000");

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

    public BinanceFuturesClient(String apiKey, String secretKey) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.baseUrl = Config.BINANCE_FUTURES_API;
        this.spotBaseUrl = Config.BINANCE_SPOT_API;
        // ========== P0 修复：初始化时同步服务器时间 ==========
        syncTimeOffset();
    }

    /**
     * P0 修复：同步服务器时间偏移
     * 避免 Binance -1021 错误："Timestamp for this request is outside of the recvWindow"
     */
    private void syncTimeOffset() {
        try {
            String url = spotBaseUrl + "/api/v3/time";
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String body = response.body().string();
                    long serverTime = mapper.readTree(body).path("serverTime").asLong();
                    long localTime = System.currentTimeMillis();
                    this.timeOffsetMs = serverTime - localTime;
                    log.info("⏰ Binance 时间同步完成：本地={}, 服务器={}, 偏移={}ms", 
                            localTime, serverTime, timeOffsetMs);
                }
            }
        } catch (Exception e) {
            log.warn("⚠️  Binance 时间同步失败，使用本地时间: {}", e.getMessage());
            this.timeOffsetMs = 0;
        }
    }

    /**
     * 获取同步后的时间戳
     */
    private long getSyncedTimestamp() {
        return System.currentTimeMillis() + timeOffsetMs;
    }

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

        String tickerUrl = baseUrl + "/fapi/v1/ticker/24hr?symbol=" + symbol;
        Request tickerRequest = new Request.Builder().url(tickerUrl).get().build();
        JsonNode ticker;
        try (Response response = httpClient.newCall(tickerRequest).execute()) {
            String body = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("market snapshot ticker request failed: " + response.code() + " " + body);
            }
            ticker = mapper.readTree(body);
        }

        BigDecimal lastPrice = readDecimal(ticker, "lastPrice", "0");
        BigDecimal highPrice = readDecimal(ticker, "highPrice", lastPrice.toPlainString());
        BigDecimal lowPrice = readDecimal(ticker, "lowPrice", lastPrice.toPlainString());
        BigDecimal volume = readDecimal(ticker, "quoteVolume", readDecimal(ticker, "volume", "0").toPlainString());
        BigDecimal priceChangeRatio = readDecimal(ticker, "priceChangePercent", "0")
                .divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);

        BigDecimal bidPrice = null;
        BigDecimal askPrice = null;
        String bookUrl = baseUrl + "/fapi/v1/ticker/bookTicker?symbol=" + symbol;
        Request bookRequest = new Request.Builder().url(bookUrl).get().build();
        try (Response response = httpClient.newCall(bookRequest).execute()) {
            String body = response.body().string();
            if (response.isSuccessful()) {
                JsonNode book = mapper.readTree(body);
                bidPrice = readDecimal(book, "bidPrice", "0");
                askPrice = readDecimal(book, "askPrice", "0");
            } else {
                log.warn("{} book ticker request failed: {} {}", symbol, response.code(), body);
            }
        }

        return new MarketSnapshot(symbol, lastPrice, highPrice, lowPrice, volume,
                bidPrice, askPrice, priceChangeRatio);
    }

    /**
     * 获取所有交易对的资金费率 [功能1: 多币种轮动]
     */
    public java.util.Map<String, BigDecimal> getAllFundingRates(java.util.List<String> symbols) throws IOException {
        java.util.Map<String, BigDecimal> rates = new java.util.HashMap<>();

        if (Config.SIMULATION_MODE) {
            // 模拟模式:每个币种返回不同的随机费率,方便演示轮动效果
            for (int i = 0; i < symbols.size(); i++) {
                String symbol = symbols.get(i);
                // 不同币种有不同的费率基准,让轮动效果更明显
                double baseRate = 0.0001 + i * 0.0002;  // 从0.01%到0.09%
                double randomVariation = (Math.random() - 0.5) * 0.0004;  // ±0.02%
                BigDecimal rate = new BigDecimal(baseRate + randomVariation).setScale(6, RoundingMode.HALF_UP);
                rates.put(symbol, rate);
            }
            return rates;
        }

        String url = baseUrl + "/fapi/v1/premiumIndex";
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API请求失败: " + response.code());
            }

            String body = response.body().string();
            JsonNode array = mapper.readTree(body);

            for (JsonNode item : array) {
                String symbol = item.get("symbol").asText();
                if (symbols.contains(symbol)) {
                    String rate = item.get("lastFundingRate").asText();
                    rates.put(symbol, new BigDecimal(rate));
                }
            }
            return rates;
        }
    }

    /**
     * 获取单个币种的资金费率
     */
    public BigDecimal getFundingRate(String symbol) throws IOException {
        if (Config.SIMULATION_MODE) {
            double randomRate = 0.0009 + Math.random() * 0.0003;
            BigDecimal rate = new BigDecimal(randomRate).setScale(6, RoundingMode.HALF_UP);
            return rate;
        }

        String url = baseUrl + "/fapi/v1/premiumIndex?symbol=" + symbol;
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API请求失败: " + response.code());
            }

            String body = response.body().string();
            JsonNode json = mapper.readTree(body);
            return new BigDecimal(json.get("lastFundingRate").asText());
        }
    }

    /**
     * 设置杠杆倍数 [功能2: 杠杆优化]
     */
    public void setLeverage(String symbol, int leverage) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] 设置 {} 杠杆为 {}x", symbol, leverage);
            return;
        }

        long timestamp = System.currentTimeMillis();
        String params = "symbol=" + symbol + "&leverage=" + leverage + "&timestamp=" + timestamp;
        String signature = sign(params);
        String url = baseUrl + "/fapi/v1/leverage?" + params + "&signature=" + signature;

        RequestBody body = RequestBody.create("", MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", apiKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                log.info("✅ 设置 {} 杠杆为 {}x", symbol, leverage);
            } else {
                String responseBody = response.body().string();
                if (responseBody.contains("-4046")) {
                    // 杠杆未变动,忽略
                    log.debug("{} 杠杆已是 {}x", symbol, leverage);
                } else {
                    log.warn("设置杠杆失败: {}", responseBody);
                }
            }
        }
    }

    /**
     * 下限价单(用于网格交易)[功能4: 网格增强]
     */
    public String placeLimitOrder(String symbol, String side, BigDecimal quantity, BigDecimal price) throws IOException {
        if (Config.SIMULATION_MODE) {
            return "SIM_LIMIT_" + System.currentTimeMillis();
        }

        long timestamp = System.currentTimeMillis();
        String params = "symbol=" + symbol +
                "&side=" + side +
                "&type=LIMIT" +
                "&timeInForce=GTC" +
                "&quantity=" + quantity +
                "&price=" + price +
                "&timestamp=" + timestamp;

        String signature = sign(params);
        String url = baseUrl + "/fapi/v1/order?" + params + "&signature=" + signature;

        RequestBody body = RequestBody.create("", MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", apiKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("限价单失败: " + responseBody);
            }
            JsonNode json = mapper.readTree(responseBody);
            return json.get("orderId").asText();
        }
    }

    /**
     * 取消所有挂单
     */
    public void cancelAllOrders(String symbol) throws IOException {
        if (Config.SIMULATION_MODE) {
            return;
        }

        long timestamp = System.currentTimeMillis();
        String params = "symbol=" + symbol + "&timestamp=" + timestamp;
        String signature = sign(params);
        String url = baseUrl + "/fapi/v1/allOpenOrders?" + params + "&signature=" + signature;

        Request request = new Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", apiKey)
                .delete()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.debug("取消订单响应: {}", response.body().string());
            }
        }
    }

    /**
     * 获取当前价格
     */
    public BigDecimal getCurrentPrice(String symbol) throws IOException {
        if (Config.SIMULATION_MODE) {
            // 根据币种返回不同的基准价格
            if (symbol.contains("BTC")) {
                return new BigDecimal(60000 + Math.random() * 5000).setScale(2, RoundingMode.HALF_UP);
            } else if (symbol.contains("ETH")) {
                return new BigDecimal(3000 + Math.random() * 300).setScale(2, RoundingMode.HALF_UP);
            } else if (symbol.contains("SOL")) {
                return new BigDecimal(100 + Math.random() * 20).setScale(2, RoundingMode.HALF_UP);
            } else {
                return new BigDecimal(10 + Math.random() * 5).setScale(4, RoundingMode.HALF_UP);
            }
        }

        String url = baseUrl + "/fapi/v1/ticker/price?symbol=" + symbol;
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body().string();
            JsonNode json = mapper.readTree(body);
            return new BigDecimal(json.get("price").asText());
        }
    }

    /**
     * 获取24h涨跌幅
     */
    public BigDecimal get24hChange(String symbol) throws IOException {
        if (Config.SIMULATION_MODE) {
            return BigDecimal.ZERO; // 模拟模式返回0
        }

        String url = baseUrl + "/fapi/v1/ticker/24hr?symbol=" + symbol;
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body().string();
            JsonNode json = mapper.readTree(body);
            return new BigDecimal(json.get("priceChangePercent").asText())
                    .divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
        }
    }

    /**
     * 获取当前持仓（实现ExchangeClient接口）
     */
    public BigDecimal getCurrentPosition(String symbol) throws IOException {
        return getPositionAmount(symbol);
    }

    /**
     * 获取预计下一期资金费率
     */
    public BigDecimal getNextFundingRate(String symbol) throws IOException {
        String url = baseUrl + "/fapi/v1/fundingRate?symbol=" + symbol + "&limit=1";

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API请求失败: " + response.code());
            }

            String body = response.body().string();
            JsonNode array = mapper.readTree(body);

            if (array.size() > 0) {
                String rate = array.get(0).get("fundingRate").asText();
                return new BigDecimal(rate);
            }
            return BigDecimal.ZERO;
        }
    }

    @Override
    public BigDecimal getPredictedFundingRate(String symbol) throws IOException {
        return getNextFundingRate(symbol);
    }

    @Override
    public List<FundingIncomeRecord> getFundingIncomeRecords(String symbol, long startTimeMillis, long endTimeMillis)
            throws IOException {
        List<FundingIncomeRecord> records = new ArrayList<>();

        if (Config.SIMULATION_MODE) {
            return records;
        }

        long timestamp = System.currentTimeMillis();
        String params = "symbol=" + symbol
                + "&incomeType=FUNDING_FEE"
                + "&startTime=" + startTimeMillis
                + "&endTime=" + endTimeMillis
                + "&timestamp=" + timestamp;
        String signature = sign(params);
        String url = baseUrl + "/fapi/v1/income?" + params + "&signature=" + signature;

        Request request = new Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("获取资金费账单失败: " + responseBody);
            }

            JsonNode array = mapper.readTree(responseBody);
            for (JsonNode item : array) {
                BigDecimal income = new BigDecimal(item.get("income").asText());
                long time = item.get("time").asLong();
                String tranId = item.has("tranId") ? item.get("tranId").asText() : symbol + "-" + time;
                records.add(new FundingIncomeRecord(symbol, income, time, tranId));
            }
        }

        return records;
    }

    /**
     * 合约做空开仓（市价单）- 正费率时用
     */
    public String openShort(String symbol, BigDecimal quantity) throws IOException {
        return executeOrder(symbol, "SELL", quantity);
    }

    /**
     * 合约做多开仓（市价单）- 负费率时用
     */
    public String openLong(String symbol, BigDecimal quantity) throws IOException {
        return executeOrder(symbol, "BUY", quantity);
    }



    @Override
    public String getExchangeName() {
        return "Binance";
    }

    /**
     * 统一执行合约订单
     */
    private String executeOrder(String symbol, String side, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] 合约{}开仓: {} 数量: {}",
                    "BUY".equals(side) ? "做多" : "做空", symbol, quantity);
            return "SIM_" + System.currentTimeMillis();
        }

        long timestamp = System.currentTimeMillis();
        String params = "symbol=" + symbol +
                "&side=" + side +
                "&type=MARKET" +
                "&quantity=" + quantity +
                "&timestamp=" + timestamp;

        String signature = sign(params);
        String url = baseUrl + "/fapi/v1/order?" + params + "&signature=" + signature;

        RequestBody body = RequestBody.create("", MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", apiKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("开仓失败: " + responseBody);
            }

            JsonNode json = mapper.readTree(responseBody);
            String orderId = json.get("orderId").asText();
            log.info("✅ 合约{}成功 - 订单ID: {}, 数量: {}",
                    "BUY".equals(side) ? "做多" : "做空", orderId, quantity);
            return orderId;
        }
    }

    /**
     * 平掉合约空单(买入平仓)
     */
    public String closeShort(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] 平合约空单: {} 数量: {}", symbol, quantity);
            return "SIM_CLOSE_" + System.currentTimeMillis();
        }

        long timestamp = System.currentTimeMillis();
        String params = "symbol=" + symbol +
                "&side=BUY" +
                "&type=MARKET" +
                "&quantity=" + quantity +
                "&reduceOnly=true" +
                "&timestamp=" + timestamp;

        String signature = sign(params);
        String url = baseUrl + "/fapi/v1/order?" + params + "&signature=" + signature;

        RequestBody body = RequestBody.create("", MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", apiKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("平仓失败: " + responseBody);
            }

            JsonNode json = mapper.readTree(responseBody);
            String orderId = json.get("orderId").asText();
            log.info("✅ 平合约空单成功 - 订单ID: {}", orderId);

            return orderId;
        }
    }

    /**
     * Bug-6修复: 平合约多单（卖出平仓，reduceOnly=true 防止误开反向仓）
     */
    @Override
    public String closeLong(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] 平合约多单: {} 数量: {}", symbol, quantity);
            return "SIM_CLOSE_LONG_" + System.currentTimeMillis();
        }
        long timestamp = System.currentTimeMillis();
        String params = "symbol=" + symbol + "&side=SELL&type=MARKET&quantity=" + quantity
                + "&reduceOnly=true&timestamp=" + timestamp;
        String signature = sign(params);
        String url = baseUrl + "/fapi/v1/order?" + params + "&signature=" + signature;
        RequestBody body = RequestBody.create("", MediaType.parse("application/json"));
        Request request = new Request.Builder().url(url).header("X-MBX-APIKEY", apiKey).post(body).build();
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) throw new IOException("平多仓失败: " + responseBody);
            String orderId = mapper.readTree(responseBody).get("orderId").asText();
            log.info("✅ 平合约多单成功 - 订单ID: {}", orderId);
            return orderId;
        }
    }

    /**
     * 获取当前持仓数量
     * GET /fapi/v2/positionRisk
     */
    @Override
    public TradeExecutionReport getTradeExecutionReport(String symbol, String orderIds, BigDecimal fallbackQuantity,
                                                        BigDecimal fallbackPrice) throws IOException {
        if (Config.SIMULATION_MODE || orderIds == null || orderIds.trim().isEmpty()) {
            return TradeExecutionReport.estimated(symbol, orderIds, fallbackQuantity, fallbackPrice);
        }

        TradeExecutionReport report = new TradeExecutionReport(symbol, orderIds);
        for (String rawOrderId : orderIds.split(",")) {
            String orderId = rawOrderId.trim();
            if (orderId.isEmpty() || orderId.startsWith("SIM_")) {
                continue;
            }
            appendOrderTrades(report, symbol, orderId);
        }

        if (report.getExecutedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("{} order {} trade fills not found, using estimated execution report", symbol, orderIds);
            return TradeExecutionReport.estimated(symbol, orderIds, fallbackQuantity, fallbackPrice);
        }
        log.info("{} order {} execution report: qty={}, avgPrice={}, fee={} {}",
                symbol,
                orderIds,
                report.getExecutedQuantity().setScale(8, RoundingMode.HALF_UP),
                report.getAveragePrice().setScale(8, RoundingMode.HALF_UP),
                report.getFee().setScale(8, RoundingMode.HALF_UP),
                report.getFeeAsset());
        return report;
    }

    private void appendOrderTrades(TradeExecutionReport report, String symbol, String orderId) throws IOException {
        long timestamp = System.currentTimeMillis();
        String params = "symbol=" + symbol + "&orderId=" + orderId + "&timestamp=" + timestamp;
        String signature = sign(params);
        String url = baseUrl + "/fapi/v1/userTrades?" + params + "&signature=" + signature;

        Request request = new Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("query order trades failed: " + response.code() + " " + responseBody);
            }
            JsonNode trades = mapper.readTree(responseBody);
            for (JsonNode trade : trades) {
                report.addFill(
                        readDecimal(trade, "price", "0"),
                        readDecimal(trade, "qty", "0"),
                        readDecimal(trade, "commission", "0"),
                        trade.hasNonNull("commissionAsset") ? trade.get("commissionAsset").asText() : "USDT",
                        readDecimal(trade, "realizedPnl", "0"),
                        trade.hasNonNull("id") ? trade.get("id").asText() : null
                );
            }
        }
    }

    public BigDecimal getPositionAmount(String symbol) throws IOException {
        if (Config.SIMULATION_MODE) {
            return BigDecimal.ZERO;
        }

        long timestamp = System.currentTimeMillis();
        String params = "symbol=" + symbol + "&timestamp=" + timestamp;
        String signature = sign(params);
        String url = baseUrl + "/fapi/v2/positionRisk?" + params + "&signature=" + signature;

        Request request = new Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("获取持仓失败: " + responseBody);
            }

            JsonNode array = mapper.readTree(responseBody);
            for (JsonNode pos : array) {
                if (pos.get("symbol").asText().equals(symbol)) {
                    String positionAmt = pos.get("positionAmt").asText();
                    BigDecimal amount = new BigDecimal(positionAmt);
                    log.info("📊 当前持仓: {} {}", amount, symbol);
                    return amount;
                }
            }
            return BigDecimal.ZERO;
        }
    }

    /**
     * 获取合约账户余额
     */
    public BigDecimal getBalance() throws IOException {
        if (Config.SIMULATION_MODE) {
            return this.simulatedBalance;
        }

        long timestamp = System.currentTimeMillis();
        String params = "timestamp=" + timestamp;
        String signature = sign(params);
        String url = baseUrl + "/fapi/v2/account?" + params + "&signature=" + signature;

        Request request = new Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("获取余额失败: " + responseBody);
            }

            JsonNode json = mapper.readTree(responseBody);
            JsonNode assets = json.get("assets");

            for (JsonNode asset : assets) {
                if (asset.get("asset").asText().equals("USDT")) {
                    String availableBalance = asset.get("availableBalance").asText();
                    BigDecimal balance = new BigDecimal(availableBalance);
                    log.info("💰 合约账户可用余额: {} USDT", balance.setScale(2, RoundingMode.HALF_UP));
                    return balance;
                }
            }
            return BigDecimal.ZERO;
        }
    }

    /**
     * 设置逐仓模式(推荐)
     */
    public void setIsolatedMargin(String symbol) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] 设置逐仓模式: {}", symbol);
            return;
        }

        long timestamp = System.currentTimeMillis();
        String params = "symbol=" + symbol + "&marginType=ISOLATED&timestamp=" + timestamp;
        String signature = sign(params);
        String url = baseUrl + "/fapi/v1/marginType?" + params + "&signature=" + signature;

        RequestBody body = RequestBody.create("", MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", apiKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                log.info("✅ 已设置逐仓模式: {}", symbol);
            } else {
                // 可能已经是逐仓模式了,忽略错误
                log.debug("设置逐仓模式响应: {}", response.body().string());
            }
        }
    }

    /**
     * HMAC SHA256 签名
     */
    private String sign(String data) {
        try {
            Mac sha256HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    this.secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256HMAC.init(keySpec);
            byte[] hash = sha256HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("签名失败", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static BigDecimal readDecimal(JsonNode node, String field, String fallback) {
        if (node == null || !node.hasNonNull(field) || node.get(field).asText().isEmpty()) {
            return new BigDecimal(fallback);
        }
        return new BigDecimal(node.get(field).asText());
    }

    /**
     * 测试连接
     */
    public boolean testConnection() {
        if (Config.SIMULATION_MODE) {
            log.info("✅ [模拟模式] 币安合约API连接正常(模拟)");
            return true;
        }
        try {
            BigDecimal rate = getFundingRate("BTCUSDT");
            log.info("✅ 币安合约API连接正常,当前BTC资金费率: {}%",
                    rate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
            return true;
        } catch (Exception e) {
            log.error("❌ 币安合约API连接失败: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 现货API - 用于现货对冲模式 ====================
    
    /**
     * 获取现货账户 USDT 余额
     */
    @Override
    public BigDecimal getSpotBalance() throws IOException {
        if (Config.SIMULATION_MODE) {
            // 模拟模式：返回合约余额的80%作为现货余额
            return getBalance().multiply(new BigDecimal("0.8"));
        }
        // ========== P0 修复：使用同步后的时间戳 + recvWindow ==========
        long timestamp = getSyncedTimestamp();
        String params = "recvWindow=" + RECV_WINDOW_MS + "&timestamp=" + timestamp;
        String signature = sign(params);
        String url = spotBaseUrl + "/api/v3/account?" + params + "&signature=" + signature;

        Request request = new Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Binance spot balance request failed: " + response.code() + " " + responseBody);
            }

            JsonNode json = mapper.readTree(responseBody);
            JsonNode balances = json.get("balances");
            if (balances != null) {
                for (JsonNode balance : balances) {
                    if ("USDT".equals(balance.path("asset").asText())) {
                        BigDecimal free = readDecimal(balance, "free", "0");
                        log.info("💰 Binance 现货账户可用余额: {} USDT", free.setScale(2, RoundingMode.HALF_UP));
                        return free;
                    }
                }
            }
            return BigDecimal.ZERO;
        }
    }

    /**
     * 现货买入（市价）
     */
    @Override
    public String buySpot(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] Binance 现货买入 {} 数量 {}", symbol, quantity);
            return "BINANCE_SIM_SPOT_BUY_" + System.currentTimeMillis();
        }
        return executeSpotMarketOrder(symbol, "BUY", quantity).getOrderId();
    }
    
    /**
     * 现货卖出（市价）
     */
    @Override
    public String sellSpot(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] Binance 现货卖出 {} 数量 {}", symbol, quantity);
            return "BINANCE_SIM_SPOT_SELL_" + System.currentTimeMillis();
        }
        return executeSpotMarketOrder(symbol, "SELL", quantity).getOrderId();
    }

    /** ========== P2 修复：现货买入返回完整成交报告 ========== */
    @Override
    public TradeExecutionReport buySpotWithReport(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] Binance 现货买入 {} 数量 {}", symbol, quantity);
            TradeExecutionReport report = new TradeExecutionReport(symbol, "BINANCE_SIM_SPOT_BUY_" + System.currentTimeMillis());
            BigDecimal price = getCurrentPrice(symbol);
            report.addFill(price, quantity, price.multiply(quantity).multiply(Config.SPOT_TAKER_FEE_RATE), "USDT", BigDecimal.ZERO, "sim");
            return report;
        }
        return executeSpotMarketOrder(symbol, "BUY", quantity);
    }

    /** ========== P2 修复：现货卖出返回完整成交报告 ========== */
    @Override
    public TradeExecutionReport sellSpotWithReport(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] Binance 现货卖出 {} 数量 {}", symbol, quantity);
            TradeExecutionReport report = new TradeExecutionReport(symbol, "BINANCE_SIM_SPOT_SELL_" + System.currentTimeMillis());
            BigDecimal price = getCurrentPrice(symbol);
            report.addFill(price, quantity, price.multiply(quantity).multiply(Config.SPOT_TAKER_FEE_RATE), "USDT", BigDecimal.ZERO, "sim");
            return report;
        }
        return executeSpotMarketOrder(symbol, "SELL", quantity);
    }

    private TradeExecutionReport executeSpotMarketOrder(String symbol, String side, BigDecimal quantity) throws IOException {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IOException("Binance spot " + side + " quantity must be positive: " + quantity);
        }

        // ========== P0 修复：使用同步后的时间戳 + recvWindow ==========
        long timestamp = getSyncedTimestamp();
        String params = "symbol=" + symbol
                + "&side=" + side
                + "&type=MARKET"
                + "&quantity=" + quantity.toPlainString()
                + "&newOrderRespType=FULL"
                + "&recvWindow=" + RECV_WINDOW_MS
                + "&timestamp=" + timestamp;
        String signature = sign(params);
        String url = spotBaseUrl + "/api/v3/order?" + params + "&signature=" + signature;

        RequestBody body = RequestBody.create("", MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", apiKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Binance spot " + side + " failed: " + response.code() + " " + responseBody);
            }

            String orderId = mapper.readTree(responseBody).path("orderId").asText();
            TradeExecutionReport report = parseSpotOrderExecution(symbol, orderId, responseBody);
            log.info("✅ Binance 现货{}成功 - 订单ID: {}, 成交数量: {}, 成交均价: {}, 手续费: {} {}",
                    "BUY".equals(side) ? "买入" : "卖出",
                    orderId,
                    report.getExecutedQuantity().setScale(8, RoundingMode.HALF_UP),
                    report.getAveragePrice().setScale(8, RoundingMode.HALF_UP),
                    report.getFee().setScale(8, RoundingMode.HALF_UP),
                    report.getFeeAsset());
            return report;
        }
    }

    static TradeExecutionReport parseSpotOrderExecution(String symbol, String orderId, String responseBody)
            throws IOException {
        JsonNode json = mapper.readTree(responseBody);
        String resolvedOrderId = orderId == null || orderId.isEmpty()
                ? json.path("orderId").asText()
                : orderId;
        TradeExecutionReport report = new TradeExecutionReport(symbol, resolvedOrderId);

        JsonNode fills = json.get("fills");
        if (fills != null && fills.isArray() && fills.size() > 0) {
            for (JsonNode fill : fills) {
                report.addFill(
                        readDecimal(fill, "price", "0"),
                        readDecimal(fill, "qty", "0"),
                        readDecimal(fill, "commission", "0"),
                        fill.hasNonNull("commissionAsset") ? fill.get("commissionAsset").asText() : "USDT",
                        BigDecimal.ZERO,
                        fill.hasNonNull("tradeId") ? fill.get("tradeId").asText() : null
                );
            }
            return report;
        }

        BigDecimal executedQty = readDecimal(json, "executedQty", "0");
        BigDecimal quoteQty = readDecimal(json, "cummulativeQuoteQty", "0");
        if (executedQty.compareTo(BigDecimal.ZERO) > 0 && quoteQty.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal avgPrice = quoteQty.divide(executedQty, 12, RoundingMode.HALF_UP);
            report.addFill(avgPrice, executedQty, BigDecimal.ZERO, "USDT", BigDecimal.ZERO, resolvedOrderId);
        }
        return report;
    }

    // ==================== 内部访问器 ====================

    public okhttp3.OkHttpClient getHttpClient() {
        return httpClient;
    }

    public com.fasterxml.jackson.databind.ObjectMapper getMapper() {
        return mapper;
    }
}
