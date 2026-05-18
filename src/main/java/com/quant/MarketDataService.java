package com.quant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class MarketDataService {
    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final List<ExchangeClient> exchangeClients;
    private final Map<String, BigDecimal> fundingRates = new HashMap<>();
    private final Map<String, BigDecimal> predictedFundingRates = new HashMap<>();
    private final Map<String, BigDecimal> price24hChange = new HashMap<>();
    private final Map<String, List<BigDecimal>> rateHistory = new HashMap<>();
    private LocalDateTime lastRateUpdate;

    public MarketDataService(ExchangeClient exchangeClient) {
        this(Collections.singletonList(exchangeClient));
    }

    public MarketDataService(List<ExchangeClient> exchangeClients) {
        if (exchangeClients == null || exchangeClients.isEmpty()) {
            throw new IllegalArgumentException("At least one exchange client is required");
        }
        this.exchangeClients = new ArrayList<>(exchangeClients);
    }

    public void update24hChanges(List<String> symbols) {
        for (String symbol : symbols) {
            try {
                BigDecimal change = read24hChange(symbol);
                if (change != null) {
                    price24hChange.put(symbol, change);
                }
            } catch (Exception e) {
                log.warn("更新 {} 24h 涨跌幅失败: {}", symbol, e.getMessage());
            }
        }
    }

    public void updateFundingRates(List<String> symbols) throws IOException {
        Map<String, BigDecimal> fetched = readAllFundingRates(symbols);
        Map<String, BigDecimal> acceptedRates = new HashMap<>();
        Map<String, BigDecimal> acceptedPredictedRates = new HashMap<>();
        for (String symbol : symbols) {
            BigDecimal rate = fetched.get(symbol);
            if (rate == null) {
                rate = readFundingRate(symbol);
            }
            BigDecimal predicted = readPredictedFundingRate(symbol);
            if (isFundingRateUsable(symbol, rate, predicted)) {
                acceptedRates.put(symbol, rate);
                acceptedPredictedRates.put(symbol, predicted);
                appendRateHistory(symbol, rate);
            }
        }
        fundingRates.clear();
        fundingRates.putAll(acceptedRates);
        predictedFundingRates.clear();
        predictedFundingRates.putAll(acceptedPredictedRates);
        lastRateUpdate = LocalDateTime.now();
        log.info("✅ 已更新 {} 个币种的资金费率", fundingRates.size());
    }

    public void updateFundingRatesIfNeeded(List<String> symbols) throws IOException {
        if (lastRateUpdate == null ||
                lastRateUpdate.plusSeconds(Config.RATE_UPDATE_INTERVAL_MS / 1000).isBefore(LocalDateTime.now())) {
            updateFundingRates(symbols);
        }
    }

    public BigDecimal getFundingRate(String symbol, BigDecimal fallback) {
        return fundingRates.getOrDefault(symbol, fallback);
    }

    public Map<String, BigDecimal> getFundingRates() {
        return fundingRates;
    }

    public boolean isExtremeMarket(String symbol, BigDecimal rate) {
        if (rate.compareTo(Config.MAX_NEGATIVE_RATE) < 0) {
            log.warn("⚠️  {} 费率 {}% 异常低，可能是极端行情，跳过做多",
                    symbol, rate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
            return true;
        }
        BigDecimal change = price24hChange.getOrDefault(symbol, BigDecimal.ZERO);
        if (change.abs().compareTo(Config.MAX_24H_CHANGE) > 0) {
            log.warn("⚠️  {} 24h涨跌幅 {}% 过大，跳过交易",
                    symbol, change.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
            return true;
        }
        return false;
    }

    public boolean isRateTrendGood(String symbol, BigDecimal currentRate) {
        List<BigDecimal> history = rateHistory.get(symbol);
        if (history == null || history.size() < 5) {
            return true;
        }

        BigDecimal avgRate = BigDecimal.ZERO;
        for (BigDecimal r : history) {
            avgRate = avgRate.add(r);
        }
        avgRate = avgRate.divide(new BigDecimal(history.size()), 8, RoundingMode.HALF_UP);

        BigDecimal threshold = currentRate.compareTo(BigDecimal.ZERO) > 0
                ? avgRate.multiply(Config.RATE_DECREASING_THRESHOLD)
                : avgRate.abs().multiply(Config.RATE_DECREASING_THRESHOLD);
        boolean trendGood = currentRate.compareTo(BigDecimal.ZERO) > 0
                ? currentRate.compareTo(threshold) >= 0
                : currentRate.abs().compareTo(threshold) >= 0;
        if (!trendGood) {
            log.warn("⚠️  {} 费率趋势衰减: 当前{}% vs 平均{}%，跳过开仓",
                    symbol,
                    currentRate.abs().multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
                    avgRate.abs().multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP));
        }
        return trendGood;
    }

    private boolean isFundingRateUsable(String symbol, BigDecimal rate, BigDecimal predicted) {
        if (rate == null || rate.compareTo(Config.MIN_VALID_FUNDING_RATE) < 0
                || rate.compareTo(Config.MAX_VALID_FUNDING_RATE) > 0) {
            log.warn("⚠️ {} 资金费率异常，跳过: {}", symbol, rate);
            return false;
        }
        if (predicted != null && predicted.signum() != 0 && rate.signum() != predicted.signum()) {
            log.warn("⚠️ {} 当前费率与预测费率方向相反: current={}, predicted={}，跳过",
                    symbol, rate, predicted);
            return false;
        }
        List<BigDecimal> history = rateHistory.get(symbol);
        if (history != null && history.size() >= 3) {
            BigDecimal avgAbs = BigDecimal.ZERO;
            for (BigDecimal old : history) {
                avgAbs = avgAbs.add(old.abs());
            }
            avgAbs = avgAbs.divide(new BigDecimal(history.size()), 8, RoundingMode.HALF_UP);
            if (avgAbs.compareTo(BigDecimal.ZERO) > 0
                    && rate.abs().compareTo(avgAbs.multiply(Config.FUNDING_SPIKE_MULTIPLIER)) > 0) {
                log.warn("⚠️ {} 资金费率疑似插针: current={}, avgAbs={}，跳过", symbol, rate, avgAbs);
                return false;
            }
        }
        return true;
    }

    private Map<String, BigDecimal> readAllFundingRates(List<String> symbols) throws IOException {
        IOException lastError = null;
        for (ExchangeClient client : exchangeClients) {
            try {
                Map<String, BigDecimal> rates = client.getAllFundingRates(symbols);
                return rates == null ? Collections.emptyMap() : rates;
            } catch (IOException e) {
                lastError = e;
                log.warn("{} 批量资金费率读取失败，尝试备用数据源: {}", client.getExchangeName(), e.getMessage());
            }
        }
        throw lastError == null ? new IOException("No exchange client available") : lastError;
    }

    private BigDecimal readFundingRate(String symbol) throws IOException {
        IOException lastError = null;
        for (ExchangeClient client : exchangeClients) {
            try {
                BigDecimal rate = client.getFundingRate(symbol);
                if (rate != null) {
                    return rate;
                }
            } catch (IOException e) {
                lastError = e;
                log.warn("{} {} 资金费率读取失败，尝试备用数据源: {}", client.getExchangeName(), symbol, e.getMessage());
            }
        }
        throw lastError == null ? new IOException("No funding rate for " + symbol) : lastError;
    }

    private BigDecimal readPredictedFundingRate(String symbol) {
        for (ExchangeClient client : exchangeClients) {
            try {
                BigDecimal predicted = client.getPredictedFundingRate(symbol);
                if (predicted != null) {
                    return predicted;
                }
            } catch (IOException e) {
                log.warn("{} {} 预测资金费率读取失败，尝试备用数据源: {}", client.getExchangeName(), symbol, e.getMessage());
            }
        }
        return null;
    }

    private BigDecimal read24hChange(String symbol) {
        for (ExchangeClient client : exchangeClients) {
            try {
                BigDecimal change = client.get24hChange(symbol);
                if (change != null) {
                    return change;
                }
            } catch (IOException e) {
                log.warn("{} {} 24h涨跌幅读取失败，尝试备用数据源: {}", client.getExchangeName(), symbol, e.getMessage());
            }
        }
        return null;
    }

    private void appendRateHistory(String symbol, BigDecimal rate) {
        rateHistory.computeIfAbsent(symbol, k -> new LinkedList<>());
        List<BigDecimal> history = rateHistory.get(symbol);
        history.add(rate);
        while (history.size() > Config.RATE_HISTORY_LIMIT) {
            ((LinkedList<BigDecimal>) history).removeFirst();
        }
    }
}
