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

    /** 获取当前最新价格 */
    BigDecimal getCurrentPrice(String symbol) throws IOException;

    // ===== 合约操作 =====

    /** 设置杠杆倍数 */
    void setLeverage(String symbol, int leverage) throws IOException;

    /** 合约开空（做空永续合约） */
    String openShort(String symbol, BigDecimal quantity) throws IOException;

    /** 合约平空（买入平仓） */
    String closeShort(String symbol, BigDecimal quantity) throws IOException;

    /** 查询合约持仓数量（负数=空仓） */
    BigDecimal getPositionAmount(String symbol) throws IOException;

    /** 查询合约账户可用余额 (USDT) */
    BigDecimal getBalance() throws IOException;

    // ===== 现货操作 =====

    /** 现货买入 */
    String buySpot(String symbol, BigDecimal quantity) throws IOException;

    /** 现货卖出 */
    String sellSpot(String symbol, BigDecimal quantity) throws IOException;

    // ===== 工具 =====

    /** 测试 API 连通性 */
    boolean testConnection();

    /** 获取交易所名称（用于日志） */
    String getExchangeName();
}
