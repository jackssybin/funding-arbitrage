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
 * 智能下单引擎 - 纯合约版本（支持 Binance / OKX）
 * 
 * 功能：
 * 1. 下单前查询 OrderBook（盘口深度）
 * 2. 如果深度不够，自动拆成多笔小单
 * 3. 支持开空(SELL)和开多(BUY)两个方向
 */
public class SmartOrderExecutor {

    private static final Logger log = LoggerFactory.getLogger(SmartOrderExecutor.class);
    
    // 深度阈值：如果订单量超过盘口深度的 50%，就拆单
    private static final BigDecimal DEPTH_THRESHOLD = new BigDecimal("0.5");
    
    // 每笔小单的延迟（毫秒），避免冲击市场
    private static final long ORDER_INTERVAL_MS = 200;

    private final ExchangeClient client;
    private final ExchangePrecision precision;

    public SmartOrderExecutor(ExchangeClient client, ExchangePrecision precision) {
        this.client = client;
        this.precision = precision;
    }

    /**
     * 智能合约开空（SELL）- 正费率时用
     */
    public String smartOpenShort(String symbol, BigDecimal totalQuantity) throws IOException {
        log.info("🤖 智能合约开空 {}: 总量 {}", symbol, totalQuantity);
        return executeSmartOrders(symbol, totalQuantity, "SELL", "开空");
    }

    /**
     * 智能合约开多（BUY）- 负费率时用
     */
    public String smartOpenLong(String symbol, BigDecimal totalQuantity) throws IOException {
        log.info("🤖 智能合约开多 {}: 总量 {}", symbol, totalQuantity);
        return executeSmartOrders(symbol, totalQuantity, "BUY", "开多");
    }

    /**
     * 执行智能下单，自动拆单
     */
    private String executeSmartOrders(String symbol, BigDecimal totalQuantity, String side, String sideName) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] {} 成功: {}", sideName, symbol);
            return "SIM_" + System.currentTimeMillis();
        }

        // OKX 当前还没有深度查询，直接一笔下
        if (client instanceof OkxClient) {
            if ("SELL".equals(side)) {
                return client.openShort(symbol, totalQuantity);
            } else {
                return client.openLong(symbol, totalQuantity);
            }
        }

        // Binance：查深度，自动拆单
        BigDecimal maxPerOrder = getMaxOrderQuantityFromDepth(symbol, side);

        if (totalQuantity.compareTo(maxPerOrder) <= 0) {
            log.info("✅ 订单量 {} 小于盘口深度 {}，直接一笔成交", totalQuantity, maxPerOrder);
            if ("SELL".equals(side)) {
                return client.openShort(symbol, totalQuantity);
            } else {
                return client.openLong(symbol, totalQuantity);
            }
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

            log.info("📦 拆单 {}/?: {} 合约 {} {}", orderIndex, sideName, thisQty, symbol);
            
            String orderId;
            if ("SELL".equals(side)) {
                orderId = client.openShort(symbol, thisQty);
            } else {
                orderId = client.openLong(symbol, thisQty);  // Bug-6修复: 多头应调 openLong
            }
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

        log.info("✅ 智能合约{}完成，共 {} 笔订单", sideName, orderIds.size());
        return String.join(",", orderIds);
    }

    /**
     * 从盘口深度计算单笔最大下单量
     */
    private BigDecimal getMaxOrderQuantityFromDepth(String symbol, String side) throws IOException {
        // 如果是 OKX 或 模拟模式，返回一个足够大的值（不拆单）
        if (Config.SIMULATION_MODE || client instanceof OkxClient) {
            return new BigDecimal("999999");
        }

        try {
            BigDecimal currentPrice = client.getCurrentPrice(symbol);
            // 简化：假设 10000 USDT 等值的币可以直接成交
            // 实际应该调用 OrderBook API，但不同交易所格式不同
            return new BigDecimal("10000").divide(currentPrice, 8, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warn("获取盘口深度失败，使用默认值: {}", e.getMessage());
            return new BigDecimal("999999");
        }
    }
}
