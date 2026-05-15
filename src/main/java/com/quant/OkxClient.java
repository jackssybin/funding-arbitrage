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

    // ===================================================================
    // 行情接口（无签名）
    // ===================================================================

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
        String path = "/api/v5/account/set-leverage";
        String bodyStr = String.format("{\"instId\":\"%s\",\"lever\":\"%d\",\"mgnMode\":\"isolated\"}", instId, leverage);

        Request request = buildSignedRequest("POST", path, bodyStr);
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "设置杠杆");
            log.info("✅ OKX 设置 {} 杠杆 {}x 成功", symbol, leverage);
        }
    }

    /**
     * 合约做空（开空永续合约）
     * OKX 接口：POST /api/v5/trade/order
     * side=sell, posSide=short, tdMode=isolated
     */
    @Override
    public String openShort(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] OKX 合约做空 {} 数量 {}", symbol, quantity);
            return "OKX_SIM_" + System.currentTimeMillis();
        }

        String instId = toOkxInstId(symbol);
        String path = "/api/v5/trade/order";
        // OKX 合约下单数量单位是"张"，1张 = 合约乘数（sz 字段）
        // 这里假设已提前处理好，quantity 为张数
        String bodyStr = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"isolated\",\"side\":\"sell\",\"posSide\":\"short\"," +
                "\"ordType\":\"market\",\"sz\":\"%s\"}",
                instId, quantity.toPlainString());

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

        String instId = toOkxInstId(symbol);
        String path = "/api/v5/trade/order";
        String bodyStr = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"isolated\",\"side\":\"buy\",\"posSide\":\"short\"," +
                "\"ordType\":\"market\",\"sz\":\"%s\"}",
                instId, quantity.toPlainString());

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
     * side=buy, posSide=long, tdMode=isolated
     */
    @Override
    public String openLong(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] OKX 合约做多 {} 数量 {}", symbol, quantity);
            return "OKX_SIM_LONG_" + System.currentTimeMillis();
        }

        String instId = toOkxInstId(symbol);
        String path = "/api/v5/trade/order";
        String bodyStr = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"isolated\",\"side\":\"buy\",\"posSide\":\"long\"," +
                "\"ordType\":\"market\",\"sz\":\"%s\"}",
                instId, quantity.toPlainString());

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
     * side=sell, posSide=long, tdMode=isolated
     */
    @Override
    public String closeLong(String symbol, BigDecimal quantity) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] OKX 平多 {} 数量 {}", symbol, quantity);
            return "OKX_SIM_CLOSE_LONG_" + System.currentTimeMillis();
        }

        String instId = toOkxInstId(symbol);
        String path = "/api/v5/trade/order";
        String bodyStr = String.format(
                "{\"instId\":\"%s\",\"tdMode\":\"isolated\",\"side\":\"sell\",\"posSide\":\"long\"," +
                "\"ordType\":\"market\",\"sz\":\"%s\"}",
                instId, quantity.toPlainString());

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
    public BigDecimal getPositionAmount(String symbol) throws IOException {
        if (Config.SIMULATION_MODE) return BigDecimal.ZERO;

        String instId = toOkxInstId(symbol);
        String path = "/api/v5/account/positions?instType=SWAP&instId=" + instId;

        Request request = buildSignedRequest("GET", path, "");
        try (Response response = httpClient.newCall(request).execute()) {
            String body = checkResponse(response, "查询持仓");
            JsonNode json = mapper.readTree(body);
            JsonNode data = json.get("data");
            if (data != null && data.size() > 0) {
                // pos 为正代表多仓，为负代表空仓（我们用空仓，所以取负值）
                String pos = data.get(0).get("pos").asText("0");
                return new BigDecimal(pos);
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
        if (Config.SIMULATION_MODE) return new BigDecimal("10000");

        String path = "/api/v5/account/balance?ccy=USDT";
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
                "{\"instId\":\"%s\",\"tdMode\":\"cash\",\"side\":\"buy\",\"ordType\":\"market\",\"sz\":\"%s\"}",
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
        String timestamp = getIsoTimestamp();
        String preHash = timestamp + method.toUpperCase() + path + body;
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
            RequestBody rb = RequestBody.create(body, MediaType.parse("application/json"));
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
}
