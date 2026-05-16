package com.quant;
import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * 网格交易增强模块 [功能4]
 * 持仓期间在上下挂小单，通过震荡赚取额外收益
 */
public class GridTrading {

    private static final Logger log = LoggerFactory.getLogger(GridTrading.class);

    private final BinanceFuturesClient futuresClient;

    /** 支持传入 null，传入 null 时所有操作均使用模拟模式跨过 */
    public GridTrading(BinanceFuturesClient futuresClient) {
        this.futuresClient = futuresClient;
    }

    /**
     * 为某个币种设置网格订单
     * 
     * @param position    持仓对象
     * @param currentPrice 当前价格
     */
    public void setupGrid(Position position, BigDecimal currentPrice) throws IOException {
        if (!Config.GRID_ENABLED) {
            return;
        }

        String symbol = position.getSymbol();
        log.info("🔧 为 {} 设置网格交易，当前价格: {}", symbol, currentPrice);

        // 先取消该币种的所有挂单
        if (futuresClient != null) {
            futuresClient.cancelAllOrders(symbol);
        }

        // 计算网格订单的基础参数
        BigDecimal orderQuantity = position.getPositionSize()
                .multiply(Config.GRID_ORDER_RATIO)
                .setScale(6, RoundingMode.DOWN);

        // 创建网格挂单
        for (int i = 1; i <= Config.GRID_LEVELS; i++) {
            // 计算网格价格
            BigDecimal gridSpacing = Config.GRID_SPACING;
            BigDecimal buyPrice = currentPrice.multiply(BigDecimal.ONE.subtract(gridSpacing.multiply(BigDecimal.valueOf(i))));
            BigDecimal sellPrice = currentPrice.multiply(BigDecimal.ONE.add(gridSpacing.multiply(BigDecimal.valueOf(i))));

            if (Config.SIMULATION_MODE || futuresClient == null) {
                log.info("[模拟模式] 挂买入单 {}: 价格 {}, 数量 {}", symbol, buyPrice, orderQuantity);
                log.info("[模拟模式] 挂卖出单 {}: 价格 {}, 数量 {}", symbol, sellPrice, orderQuantity);
                continue;
            }

            try {
                // 挂买单
                futuresClient.placeLimitOrder(symbol, "BUY", orderQuantity, buyPrice);
                // 挂卖单
                futuresClient.placeLimitOrder(symbol, "SELL", orderQuantity, sellPrice);

                // 记录网格订单（简化处理，实际需要获取订单ID）
                position.addGridOrder(new Position.GridOrder(
                        "grid_buy_" + i, "BUY", buyPrice, orderQuantity
                ));
                position.addGridOrder(new Position.GridOrder(
                        "grid_sell_" + i, "SELL", sellPrice, orderQuantity
                ));

            } catch (IOException e) {
                log.warn("设置网格订单失败: {}", e.getMessage());
            }
        }

        log.info("✅ 为 {} 设置了 {} 层网格，每层 {} 间距", 
                symbol, Config.GRID_LEVELS, Config.GRID_SPACING.multiply(BigDecimal.valueOf(100)) + "%");
    }

    /**
     * 检查并维护网格订单（成交的单需要重新挂单）
     */
    public void maintainGridOrders(Position position, BigDecimal currentPrice) throws IOException {
        if (!Config.GRID_ENABLED || !position.hasPosition()) {
            return;
        }

        log.debug("检查 {} 的网格订单状态", position.getSymbol());

        // 重新平衡网格：取消所有现有网格单，重新挂新的网格
        if (shouldRebalanceGrid(position, currentPrice)) {
            log.info("🔄 价格偏离较多，重新平衡 {} 的网格订单", position.getSymbol());
            setupGrid(position, currentPrice);
        }
    }

    /**
     * 判断是否需要重新平衡网格（当前价格偏离开仓价超过一层网格间距）
     */
    private boolean shouldRebalanceGrid(Position position, BigDecimal currentPrice) {
        if (BigDecimal.ZERO.equals(position.getEntryPrice())) {
            return false;
        }
        
        BigDecimal priceDiffPercent = currentPrice.subtract(position.getEntryPrice())
                .abs()
                .divide(position.getEntryPrice(), 6, RoundingMode.HALF_UP);
        
        // 偏离超过两层网格间距，需要重新平衡
        return priceDiffPercent.compareTo(Config.GRID_SPACING.multiply(BigDecimal.valueOf(2))) > 0;
    }

    /**
     * 取消某个币种的所有网格订单
     */
    public void cancelAllGridOrders(String symbol) {
        if (futuresClient == null) return;
        try {
            futuresClient.cancelAllOrders(symbol);
            log.info("已取消 {} 的所有网格订单", symbol);
        } catch (IOException e) {
            log.warn("取消网格订单失败: {}", e.getMessage());
        }
    }
}
