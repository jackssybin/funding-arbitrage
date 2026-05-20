package com.quant;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 交易所客户端统一接口
 * 所有交易所（Binance / OKX）都实现此接口，上层业务无需关心具体交易所
 */
public interface ExchangeClient {

    // ===== 行情 =====

    /** 获取多个币种的当前资金费率 */
    Map<String, BigDecimal> getAllFundingRates(List<String> symbols) throws IOException;

    /** 获取单个币种的当前资金费率 */
    BigDecimal getFundingRate(String symbol) throws IOException;

    default BigDecimal getPredictedFundingRate(String symbol) throws IOException {
        return getFundingRate(symbol);
    }

    /** 获取当前最新价格 */
    BigDecimal getCurrentPrice(String symbol) throws IOException;

    default BigDecimal getMidPrice(String symbol) throws IOException {
        return getCurrentPrice(symbol);
    }

    /** 获取24小时涨跌幅 */
    BigDecimal get24hChange(String symbol) throws IOException;

    default MarketSnapshot getMarketSnapshot(String symbol) throws IOException {
        BigDecimal price = getCurrentPrice(symbol);
        return new MarketSnapshot(symbol, price, price, price, BigDecimal.ZERO,
                null, null, get24hChange(symbol));
    }

    /** 获取当前持仓（兼容不同交易所） */
    BigDecimal getCurrentPosition(String symbol) throws IOException;

    /**
     * 获取指定时间范围内的真实资金费账单。
     * 返回值以交易所实际入账方向为准：正数表示收入，负数表示支出。
     */
    default List<FundingIncomeRecord> getFundingIncomeRecords(String symbol, long startTimeMillis, long endTimeMillis)
            throws IOException {
        return java.util.Collections.emptyList();
    }

    // ===== 合约操作 =====

    /** 设置杠杆倍数 */
    void setLeverage(String symbol, int leverage) throws IOException;

    /** 合约开空（做空永续合约）- 正费率时用 */
    String openShort(String symbol, BigDecimal quantity) throws IOException;

    /** 合约开多（做多永续合约）- 资金费为负时使用 */
    String openLong(String symbol, BigDecimal quantity) throws IOException;

    /** 合约平空（买入平仓） */
    String closeShort(String symbol, BigDecimal quantity) throws IOException;

    /** 合约平多（卖出平仓） */
    String closeLong(String symbol, BigDecimal quantity) throws IOException;

    default TradeExecutionReport getTradeExecutionReport(String symbol, String orderIds, BigDecimal fallbackQuantity,
                                                         BigDecimal fallbackPrice) throws IOException {
        return TradeExecutionReport.estimated(symbol, orderIds, fallbackQuantity, fallbackPrice);
    }
    /** 查询合约持仓数量（负数=空仓） - 已废弃，请使用 getCurrentPosition */
    @Deprecated
    BigDecimal getPositionAmount(String symbol) throws IOException;

    /** 查询合约账户可用余额 (USDT) */
    BigDecimal getBalance() throws IOException;

    /** 更新模拟账户余额（仅模拟模式有效） */
    void updateSimulatedBalance(BigDecimal delta);

    /** 获取模拟账户余额（仅模拟模式有效） */
    BigDecimal getSimulatedBalance();

    // ===== 现货操作 =====

    /** 获取现货账户可用余额 (USDT) */
    default BigDecimal getSpotBalance() throws IOException {
        throw new IOException("Spot balance API is not implemented by " + getExchangeName());
    }

    /** 现货买入 */
    String buySpot(String symbol, BigDecimal quantity) throws IOException;

    /** 现货卖出 */
    String sellSpot(String symbol, BigDecimal quantity) throws IOException;

    /** 开现货持仓（默认调用 buySpot） */
    default void openSpotPosition(String symbol, BigDecimal quantity) throws IOException {
        buySpot(symbol, quantity);
    }

    /** 平现货持仓（默认调用 sellSpot） */
    default void closeSpotPosition(String symbol, BigDecimal quantity) throws IOException {
        sellSpot(symbol, quantity);
    }

    // ===== 工具 =====

    /** 测试 API 连通性 */
    boolean testConnection();

    /** 获取交易所名称（用于日志） */
    String getExchangeName();
}
