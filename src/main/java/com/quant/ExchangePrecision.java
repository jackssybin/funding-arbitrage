package com.quant;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * 交易所精度对齐工具 - 第一道硬核防线
 * 
 * 功能：
 * 1. 从 exchangeInfo 获取每个币种的 stepSize（数量精度）和 tickSize（价格精度）
 * 2. 下单前自动裁剪精度，避免 Filter failure 拒单
 * 
 * 例如：
 * - BTCUSDT: stepSize = 0.001 (只能买 0.001 的整数倍)
 * - DOGEUSDT: stepSize = 1.0 (只能买整数个)
 */
public class ExchangePrecision {

    private static final Logger log = LoggerFactory.getLogger(ExchangePrecision.class);

    private final BinanceFuturesClient client;
    
    // 缓存每个币种的精度规则
    private final Map<String, SymbolFilters> symbolFilters = new HashMap<>();

    public ExchangePrecision(BinanceFuturesClient client) {
        this.client = client;
    }

    /**
     * 单个币种的精度过滤器
     */
    public static class SymbolFilters {
        public String symbol;
        public BigDecimal stepSize;        // 数量精度 (LOT_SIZE)
        public int stepScale;              // stepSize 的小数位数
        public BigDecimal tickSize;        // 价格精度 (PRICE_FILTER)
        public int tickScale;              // tickSize 的小数位数
        public BigDecimal minQty;          // 最小下单量
        public BigDecimal maxQty;          // 最大下单量

        @Override
        public String toString() {
            return String.format("%s: step=%s(%d), tick=%s(%d), minQty=%s",
                    symbol, stepSize, stepScale, tickSize, tickScale, minQty);
        }
    }

    /**
     * 从交易所加载所有币种的精度规则
     */
    public void loadAllSymbolFilters() throws IOException {
        log.info("🔧 正在加载交易所精度规则...");
        
        String url = "https://fapi.binance.com/fapi/v1/exchangeInfo";
        okhttp3.Request request = new okhttp3.Request.Builder().url(url).get().build();

        try (okhttp3.Response response = client.getHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("获取 exchangeInfo 失败: " + response.code());
            }
            
            String body = response.body().string();
            JsonNode json = client.getMapper().readTree(body);
            JsonNode symbols = json.get("symbols");

            int loaded = 0;
            for (JsonNode symbolNode : symbols) {
                String symbol = symbolNode.get("symbol").asText();
                
                // 只加载我们关心的币种
                if (!Config.TRADING_SYMBOLS.contains(symbol)) {
                    continue;
                }

                SymbolFilters filters = new SymbolFilters();
                filters.symbol = symbol;

                JsonNode filtersNode = symbolNode.get("filters");
                for (JsonNode filter : filtersNode) {
                    String filterType = filter.get("filterType").asText();
                    
                    // LOT_SIZE: 数量精度
                    if ("LOT_SIZE".equals(filterType)) {
                        filters.stepSize = new BigDecimal(filter.get("stepSize").asText());
                        filters.stepScale = filters.stepSize.stripTrailingZeros().scale();
                        filters.minQty = new BigDecimal(filter.get("minQty").asText());
                        filters.maxQty = new BigDecimal(filter.get("maxQty").asText());
                    }
                    
                    // PRICE_FILTER: 价格精度
                    if ("PRICE_FILTER".equals(filterType)) {
                        filters.tickSize = new BigDecimal(filter.get("tickSize").asText());
                        filters.tickScale = filters.tickSize.stripTrailingZeros().scale();
                    }
                }

                symbolFilters.put(symbol, filters);
                loaded++;
                log.info("✅ {} 精度规则: {}", symbol, filters);
            }

            if (loaded == 0) {
                log.warn("⚠️ 没有加载到任何币种的精度规则，检查 TRADING_SYMBOLS 配置");
            } else {
                log.info("✅ 成功加载 {} 个币种的精度规则", loaded);
            }
        }
    }

    /**
     * 对齐数量精度（向下取整，避免超过预算）
     */
    public BigDecimal alignQuantity(String symbol, BigDecimal quantity) {
        SymbolFilters filters = symbolFilters.get(symbol);
        if (filters == null) {
            log.warn("⚠️ 没有 {} 的精度规则，使用默认精度 6", symbol);
            return quantity.setScale(6, RoundingMode.DOWN);
        }

        // 按照 stepSize 对齐: quantity = floor(quantity / stepSize) * stepSize
        BigDecimal divided = quantity.divide(filters.stepSize, 0, RoundingMode.DOWN);
        BigDecimal aligned = divided.multiply(filters.stepSize);
        
        // 检查最小数量
        if (aligned.compareTo(filters.minQty) < 0) {
            log.warn("⚠️ {} 数量 {} 小于最小下单量 {}", symbol, aligned, filters.minQty);
            return BigDecimal.ZERO;
        }

        // 检查最大数量
        if (aligned.compareTo(filters.maxQty) > 0) {
            aligned = filters.maxQty;
        }

        log.debug("{} 数量精度对齐: {} -> {}", symbol, quantity, aligned);
        return aligned;
    }

    /**
     * 对齐价格精度（限价单用）
     */
    public BigDecimal alignPrice(String symbol, BigDecimal price) {
        SymbolFilters filters = symbolFilters.get(symbol);
        if (filters == null) {
            log.warn("⚠️ 没有 {} 的精度规则，使用默认精度 2", symbol);
            return price.setScale(2, RoundingMode.HALF_UP);
        }

        // 按照 tickSize 对齐
        BigDecimal divided = price.divide(filters.tickSize, 0, RoundingMode.HALF_UP);
        BigDecimal aligned = divided.multiply(filters.tickSize);

        log.debug("{} 价格精度对齐: {} -> {}", symbol, price, aligned);
        return aligned;
    }

    /**
     * 获取某个币种的精度规则
     */
    public SymbolFilters getFilters(String symbol) {
        return symbolFilters.get(symbol);
    }

    /**
     * 模拟模式下加载默认精度规则（不需要联网）
     */
    public void loadMockFilters() {
        log.info("🔧 [模拟模式] 加载默认精度规则...");
        
        // 常用币种的精度规则
        addMockFilter("BTCUSDT", "0.001", "0.1");
        addMockFilter("ETHUSDT", "0.001", "0.01");
        addMockFilter("SOLUSDT", "0.01", "0.001");
        addMockFilter("XRPUSDT", "1.0", "0.0001");
        addMockFilter("DOGEUSDT", "1.0", "0.00001");
        addMockFilter("ADAUSDT", "0.1", "0.0001");
        addMockFilter("AVAXUSDT", "0.01", "0.01");
        addMockFilter("DOTUSDT", "0.01", "0.001");
        addMockFilter("MATICUSDT", "0.1", "0.0001");
        addMockFilter("LINKUSDT", "0.01", "0.001");

        for (String symbol : Config.TRADING_SYMBOLS) {
            if (symbolFilters.containsKey(symbol)) {
                log.info("✅ {} 精度规则: {}", symbol, symbolFilters.get(symbol));
            }
        }
    }

    private void addMockFilter(String symbol, String stepSize, String tickSize) {
        SymbolFilters filters = new SymbolFilters();
        filters.symbol = symbol;
        filters.stepSize = new BigDecimal(stepSize);
        filters.stepScale = filters.stepSize.stripTrailingZeros().scale();
        filters.tickSize = new BigDecimal(tickSize);
        filters.tickScale = filters.tickSize.stripTrailingZeros().scale();
        filters.minQty = filters.stepSize;
        filters.maxQty = new BigDecimal("999999");
        symbolFilters.put(symbol, filters);
    }
}
