package com.quant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 原子性事务管理器 - 纯合约版本
 * 
 * 确保开仓/平仓操作的原子性
 * P0 修复：砍掉现货，只保留合约操作
 * P1 修复：并发下单，消除时间差滑点
 */
public class AtomicTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(AtomicTransactionManager.class);
    private static final int MAX_RETRY = 3;
    private static final long RETRY_DELAY_MS = 1000;

    private final ExchangeClient exchangeClient;
    private final SmartOrderExecutor smartOrderExecutor;

    public AtomicTransactionManager(ExchangeClient exchangeClient, SmartOrderExecutor smartOrderExecutor) {
        this.exchangeClient = exchangeClient;
        this.smartOrderExecutor = smartOrderExecutor;
    }

    /**
     * 原子性开仓 - 纯合约版本
     * 
     * @param symbol 交易对
     * @param quantity 数量
     * @param fundingRate 资金费率（决定方向）
     * @return 开仓结果
     */
    public TxResult atomicOpenPosition(String symbol, BigDecimal quantity, BigDecimal fundingRate) {
        log.info("");
        log.info("┌──────────────────────────────────────────────────────────┐");
        log.info("│              🚀 纯合约开仓操作                              │");
        log.info("└──────────────────────────────────────────────────────────┘");

        TxResult result = new TxResult();
        String side = fundingRate.compareTo(BigDecimal.ZERO) >= 0 ? "SELL" : "BUY";
        String sideName = fundingRate.compareTo(BigDecimal.ZERO) >= 0 ? "做空" : "做多";

        log.info("币种: {}, 方向: {} (费率 {}%), 数量: {}", 
                symbol, sideName, 
                fundingRate.abs().multiply(new BigDecimal("100")).setScale(4),
                quantity);

        TxOperation operation = new TxOperation("FUTURES_OPEN", symbol, quantity);
        result.operations.add(operation);

        boolean success = executeOperationWithRetry(operation, side);

        if (success) {
            result.status = TxStatus.SUCCESS;
            result.message = "✅ 合约开仓成功";
            log.info("✅ 合约开仓成功");
        } else {
            result.status = TxStatus.FAILED;
            result.message = "❌ 合约开仓失败: " + operation.errorMsg;
            log.error("❌ {}", result.message);
        }

        logResult(result);
        return result;
    }

    /**
     * 原子性平仓 - 纯合约版本
     */
    public TxResult atomicClosePosition(String symbol, BigDecimal quantity, String currentSide) {
        log.info("");
        log.info("┌──────────────────────────────────────────────────────────┐");
        log.info("│              📉 纯合约平仓操作                              │");
        log.info("└──────────────────────────────────────────────────────────┘");
        log.info("币种: {}, 数量: {}, 原方向: {}", symbol, quantity, currentSide);

        TxResult result = new TxResult();
        String closeSide = "LONG".equals(currentSide) ? "SELL" : "BUY";

        TxOperation operation = new TxOperation("FUTURES_CLOSE", symbol, quantity);
        result.operations.add(operation);

        boolean success = executeOperationWithRetry(operation, closeSide);

        if (success) {
            result.status = TxStatus.SUCCESS;
            result.message = "✅ 合约平仓成功";
            log.info("✅ 合约平仓成功");
        } else {
            result.status = TxStatus.FAILED;
            result.message = "❌ 合约平仓失败: " + operation.errorMsg;
            log.error("❌ {}", result.message);
        }

        logResult(result);
        return result;
    }

    /**
     * 批量并行开仓 - 多币种同时开仓，极致速度
     */
    public List<TxResult> batchOpenPositions(List<OpenPositionRequest> requests) {
        log.info("🚀 开始批量并行开仓，共 {} 个币种", requests.size());

        List<CompletableFuture<TxResult>> futures = new ArrayList<>();

        for (OpenPositionRequest request : requests) {
            futures.add(CompletableFuture.supplyAsync(() ->
                    atomicOpenPosition(request.symbol, request.quantity, request.fundingRate)));
        }

        List<TxResult> results = new ArrayList<>();
        for (CompletableFuture<TxResult> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                log.error("批量开仓异常: {}", e.getMessage());
            }
        }

        long successCount = results.stream().filter(TxResult::isSuccess).count();
        log.info("📊 批量开仓完成: 成功 {}/{}", successCount, results.size());

        return results;
    }

    /**
     * 执行操作（带重试）
     */
    private boolean executeOperationWithRetry(TxOperation op, String side) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                log.info("执行 {} (第 {} 次尝试)...", op, attempt);

                if ("FUTURES_OPEN".equals(op.type)) {
                    String orderId;
                    if ("SELL".equals(side)) {
                        orderId = smartOrderExecutor.smartOpenShort(op.symbol, op.quantity);
                    } else {
                        orderId = smartOrderExecutor.smartOpenLong(op.symbol, op.quantity);
                    }
                    op.orderId = orderId;
                    op.status = "SUCCESS";
                    log.info("✅ 开仓订单执行成功: {}", orderId);
                    return true;
                } else if ("FUTURES_CLOSE".equals(op.type)) {
                    String orderId;
                    if ("BUY".equals(side)) {
                        // 平空：SELL -> BUY back
                        orderId = smartOrderExecutor.smartCloseShort(op.symbol, op.quantity);
                    } else {
                        // 平多：BUY -> SELL back
                        orderId = smartOrderExecutor.smartCloseLong(op.symbol, op.quantity);
                    }
                    op.orderId = orderId;
                    op.status = "SUCCESS";
                    log.info("✅ 平仓订单执行成功: {}", orderId);
                    return true;
                }

            } catch (Exception e) {
                op.errorMsg = e.getMessage();
                log.warn("❌ {} 失败 (第 {} 次): {}", op.type, attempt, e.getMessage());

                if (attempt < MAX_RETRY) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        op.status = "FAILED";
        log.error("❌ {} 重试 {} 次全部失败!", op.type, MAX_RETRY);
        return false;
    }

    /**
     * 打印事务结果
     */
    private void logResult(TxResult result) {
        log.info("");
        log.info("┌─────────────────────────────────────────────────────┐");
        log.info("│              执行结果                                   │");
        log.info("├─────────────────────────────────────────────────────┤");
        for (TxOperation op : result.operations) {
            String statusIcon = "SUCCESS".equals(op.status) ? "✅" : "❌";
            log.info("│  {} {}", statusIcon, op);
        }
        log.info("│");
        log.info("│  最终状态: {}", result.status);
        log.info("│  {}", result.message);
        log.info("└─────────────────────────────────────────────────────┘");
        log.info("");
    }

    // ============ 内部类 ============

    public enum TxStatus {
        PENDING, SUCCESS, FAILED, PARTIAL, ROLLBACK_SUCCESS, ROLLBACK_FAILED
    }

    public static class TxOperation {
        public String id;
        public String type;
        public String symbol;
        public BigDecimal quantity;
        public String status;
        public String orderId;
        public String errorMsg;

        public TxOperation(String type, String symbol, BigDecimal quantity) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.type = type;
            this.symbol = symbol;
            this.quantity = quantity;
            this.status = "PENDING";
        }

        @Override
        public String toString() {
            return String.format("%s[%s] %s %s", type, status, quantity, symbol);
        }
    }

    public static class TxResult {
        public TxStatus status;
        public List<TxOperation> operations = new ArrayList<>();
        public String message;

        public boolean isSuccess() {
            return status == TxStatus.SUCCESS;
        }

        public boolean isDangerous() {
            return status == TxStatus.PARTIAL || status == TxStatus.ROLLBACK_FAILED;
        }
    }

    public static class OpenPositionRequest {
        public String symbol;
        public BigDecimal quantity;
        public BigDecimal fundingRate;

        public OpenPositionRequest(String symbol, BigDecimal quantity, BigDecimal fundingRate) {
            this.symbol = symbol;
            this.quantity = quantity;
            this.fundingRate = fundingRate;
        }
    }
}
