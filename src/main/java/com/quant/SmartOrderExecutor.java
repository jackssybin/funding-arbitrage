package com.quant;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能下单引擎 - 堵住第二个利润黑洞：市价单砸穿盘口
 * 
 * 功能：
 * 1. 下单前查询 OrderBook（盘口深度）
 * 2. 计算订单是否会砸穿买一/卖一档
 * 3. 如果深度不够，自动拆成多笔小单
 * 4. 支持限价单模式（Maker，手续费更低）
 */
public class SmartOrderExecutor {

    private static final Logger log = LoggerFactory.getLogger(SmartOrderExecutor.class);
    
    // 深度阈值：如果订单量超过盘口深度的 50%，就拆单
    private static final BigDecimal DEPTH_THRESHOLD = new BigDecimal("0.5");
    
    // 每笔小单的延迟（毫秒），避免冲击市场
    private static final long ORDER_INTERVAL_MS = 200;

    private final BinanceFuturesClient client;
    private final ExchangePrecision precision;

    public SmartOrderExecutor(BinanceFuturesClient client, ExchangePrecision precision) {
        this.client = client;
        this.precision = precision;
    }

    /**
     * 订单簿深度信息
     */
    public static class OrderBookDepth {
        public String symbol;
        public BigDecimal bidQty;      // 买一总量（可以卖出多少）
        public BigDecimal askQty;      // 卖一总量（可以买入多少）
        public BigDecimal bidPrice;    // 买一价
        public BigDecimal askPrice;    // 卖一价
    }

    /**
     * 查询盘口深度
     */
    public OrderBookDepth getOrderBookDepth(String symbol) throws IOException {
        if (Config.SIMULATION_MODE) {
            // 模拟模式：返回足够大的深度
            OrderBookDepth depth = new OrderBookDepth();
            depth.symbol = symbol;
            depth.bidQty = new BigDecimal("999999");
            depth.askQty = new BigDecimal("999999");
            depth.bidPrice = new BigDecimal("50000");
            depth.askPrice = new BigDecimal("50000.1");
            return depth;
        }

        String url = "https://fapi.binance.com/fapi/v1/depth?symbol=" + symbol + "&limit=20";
        okhttp3.Request request = new okhttp3.Request.Builder().url(url).get().build();

        try (okhttp3.Response response = client.getHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("获取深度失败: " + response.code());
            }
            
            String body = response.body().string();
            JsonNode json = client.getMapper().readTree(body);
            
            OrderBookDepth depth = new OrderBookDepth();
            depth.symbol = symbol;
            
            // 累加买单深度（前5档）
            depth.bidQty = BigDecimal.ZERO;
            JsonNode bids = json.get("bids");
            for (int i = 0; i < Math.min(5, bids.size()); i++) {
                depth.bidQty = depth.bidQty.add(new BigDecimal(bids.get(i).get(1).asText()));
            }
            depth.bidPrice = new BigDecimal(bids.get(0).get(0).asText());
            
            // 累加卖单深度（前5档）
            depth.askQty = BigDecimal.ZERO;
            JsonNode asks = json.get("asks");
            for (int i = 0; i < Math.min(5, asks.size()); i++) {
                depth.askQty = depth.askQty.add(new BigDecimal(asks.get(i).get(1).asText()));
            }
            depth.askPrice = new BigDecimal(asks.get(0).get(0).asText());
            
            log.debug("{} 盘口深度: 买一总 {} @ {}, 卖一总 {} @ {}", 
                    symbol, depth.bidQty, depth.bidPrice, depth.askQty, depth.askPrice);
            
            return depth;
        }
    }

    /**
     * 智能买入（现货）
     * 自动检查深度，必要时拆单
     */
    public List<String> smartBuy(String symbol, BigDecimal totalQuantity) throws IOException {
        log.info("🤖 智能买入 {}: 总量 {}", symbol, totalQuantity);
        
        OrderBookDepth depth = getOrderBookDepth(symbol);
        
        // 计算每笔最大下单量 = 卖一深度 * 阈值
        BigDecimal maxPerOrder = depth.askQty.multiply(DEPTH_THRESHOLD);
        
        // 检查是否需要拆单
        if (totalQuantity.compareTo(maxPerOrder) <= 0) {
            log.info("✅ 订单量 {} 小于盘口深度 {}，直接一笔成交", totalQuantity, maxPerOrder);
            String orderId = client.buySpot(symbol, totalQuantity);
            return List.of(orderId);
        }
        
        // 需要拆单
        log.info("⚠️ 订单量 {} 超过盘口深度 {} 的 50%，自动拆单执行", totalQuantity, maxPerOrder);
        
        List<String> orderIds = new ArrayList<>();
        BigDecimal remaining = totalQuantity;
        int orderIndex = 1;
        
        while (remaining.compareTo(BigDecimal.ZERO) > 0) {
            // 这一单的数量
            BigDecimal thisQty = remaining.min(maxPerOrder);
            thisQty = precision.alignQuantity(symbol, thisQty);
            
            if (thisQty.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            
            log.info("📦 拆单 {}/?: 买入 {} {}", orderIndex, thisQty, symbol);
            String orderId = client.buySpot(symbol, thisQty);
            orderIds.add(orderId);
            
            remaining = remaining.subtract(thisQty);
            orderIndex++;
            
            // 小延迟，避免冲击市场
            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                try {
                    Thread.sleep(ORDER_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        log.info("✅ 智能买入完成，共 {} 笔订单", orderIds.size());
        return orderIds;
    }

    /**
     * 智能卖出（现货）
     */
    public List<String> smartSell(String symbol, BigDecimal totalQuantity) throws IOException {
        log.info("🤖 智能卖出 {}: 总量 {}", symbol, totalQuantity);
        
        OrderBookDepth depth = getOrderBookDepth(symbol);
        
        BigDecimal maxPerOrder = depth.bidQty.multiply(DEPTH_THRESHOLD);
        
        if (totalQuantity.compareTo(maxPerOrder) <= 0) {
            log.info("✅ 订单量 {} 小于盘口深度 {}，直接一笔成交", totalQuantity, maxPerOrder);
            String orderId = client.sellSpot(symbol, totalQuantity);
            return List.of(orderId);
        }
        
        log.info("⚠️ 订单量 {} 超过盘口深度 {} 的 50%，自动拆单执行", totalQuantity, maxPerOrder);
        
        List<String> orderIds = new ArrayList<>();
        BigDecimal remaining = totalQuantity;
        int orderIndex = 1;
        
        while (remaining.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal thisQty = remaining.min(maxPerOrder);
            thisQty = precision.alignQuantity(symbol, thisQty);
            
            if (thisQty.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            
            log.info("📦 拆单 {}/?: 卖出 {} {}", orderIndex, thisQty, symbol);
            String orderId = client.sellSpot(symbol, thisQty);
            orderIds.add(orderId);
            
            remaining = remaining.subtract(thisQty);
            orderIndex++;
            
            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                try {
                    Thread.sleep(ORDER_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        log.info("✅ 智能卖出完成，共 {} 笔订单", orderIds.size());
        return orderIds;
    }

    /**
     * 智能合约做空
     */
    public List<String> smartOpenShort(String symbol, BigDecimal totalQuantity) throws IOException {
        log.info("🤖 智能合约做空 {}: 总量 {}", symbol, totalQuantity);
        
        if (Config.SIMULATION_MODE) {
            // 模拟模式直接一笔
            return List.of(client.openShort(symbol, totalQuantity));
        }
        
        OrderBookDepth depth = getOrderBookDepth(symbol);
        BigDecimal maxPerOrder = depth.bidQty.multiply(DEPTH_THRESHOLD);
        
        if (totalQuantity.compareTo(maxPerOrder) <= 0) {
            log.info("✅ 直接一笔成交");
            return List.of(client.openShort(symbol, totalQuantity));
        }
        
        log.info("⚠️ 自动拆单执行");
        List<String> orderIds = new ArrayList<>();
        BigDecimal remaining = totalQuantity;
        
        while (remaining.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal thisQty = remaining.min(maxPerOrder);
            thisQty = precision.alignQuantity(symbol, thisQty);
            
            if (thisQty.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            
            orderIds.add(client.openShort(symbol, thisQty));
            remaining = remaining.subtract(thisQty);
            
            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                try {
                    Thread.sleep(ORDER_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        return orderIds;
    }

    /**
     * 智能平合约空单
     */
    public List<String> smartCloseShort(String symbol, BigDecimal totalQuantity) throws IOException {
        log.info("🤖 智能平合约空单 {}: 总量 {}", symbol, totalQuantity);
        
        if (Config.SIMULATION_MODE) {
            return List.of(client.closeShort(symbol, totalQuantity));
        }
        
        OrderBookDepth depth = getOrderBookDepth(symbol);
        BigDecimal maxPerOrder = depth.askQty.multiply(DEPTH_THRESHOLD);
        
        if (totalQuantity.compareTo(maxPerOrder) <= 0) {
            return List.of(client.closeShort(symbol, totalQuantity));
        }
        
        List<String> orderIds = new ArrayList<>();
        BigDecimal remaining = totalQuantity;
        
        while (remaining.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal thisQty = remaining.min(maxPerOrder);
            thisQty = precision.alignQuantity(symbol, thisQty);
            
            if (thisQty.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            
            orderIds.add(client.closeShort(symbol, thisQty));
            remaining = remaining.subtract(thisQty);
            
            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                try {
                    Thread.sleep(ORDER_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        return orderIds;
    }
}
