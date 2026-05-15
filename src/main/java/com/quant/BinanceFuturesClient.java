package com.quant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
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

    public BinanceFuturesClient(String apiKey, String secretKey) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.baseUrl = Config.BINANCE_FUTURES_API;
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
            return new BigDecimal(json.get("priceChangePercent").asText());
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
            return new BigDecimal("10000");
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

    // ==================== 【已废弃】现货API - 纯合约套利不需要现货 ====================
    
    @Deprecated
    public String buySpot(String symbol, BigDecimal quantity) throws IOException {
        log.warn("⚠️  现货API已废弃！纯合约资金费率套利不需要现货！");
        return "DEPRECATED_" + System.currentTimeMillis();
    }
    
    @Deprecated
    public String sellSpot(String symbol, BigDecimal quantity) throws IOException {
        log.warn("⚠️  现货API已废弃！纯合约资金费率套利不需要现货！");
        return "DEPRECATED_" + System.currentTimeMillis();
    }

    // ==================== 内部访问器 ====================

    public okhttp3.OkHttpClient getHttpClient() {
        return httpClient;
    }

    public com.fasterxml.jackson.databind.ObjectMapper getMapper() {
        return mapper;
    }
}
