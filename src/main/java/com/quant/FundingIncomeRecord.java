package com.quant;

import java.math.BigDecimal;

/**
 * 交易所真实资金费账单记录。
 */
public class FundingIncomeRecord {
    private final String symbol;
    private final BigDecimal income;
    private final long timeMillis;
    private final String sourceId;

    public FundingIncomeRecord(String symbol, BigDecimal income, long timeMillis, String sourceId) {
        this.symbol = symbol;
        this.income = income;
        this.timeMillis = timeMillis;
        this.sourceId = sourceId;
    }

    public String getSymbol() {
        return symbol;
    }

    public BigDecimal getIncome() {
        return income;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    public String getSourceId() {
        return sourceId;
    }
}
