package com.quant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 原子性事务管理器 - 第二道硬核防线
 * 
 * 解决问题：
 * 现货买入和合约做空是两笔独立的 HTTP 请求。
 * 如果网络抖动导致只有一笔成功，将瞬间面临 100% 的现货裸多风险！
 * 
 * 实现机制：
 * 1. 两阶段执行：先记录所有操作，然后执行
 * 2. 操作日志：每一步都有状态记录
 * 3. 自动回滚：失败时，反向平仓已成功的操作
 * 4. 最大重试：失败后重试 N 次
 * 5. 终极防护：极端情况下发送警报（需要人工介入）
 */
public class AtomicTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(AtomicTransactionManager.class);
    private static final int MAX_RETRY = 3;
    private static final long RETRY_DELAY_MS = 1000;

    private final BinanceFuturesClient futuresClient;

    // 事务状态
    public enum TxStatus {
        PENDING,      // 待执行
        SUCCESS,      // 全部成功
        PARTIAL,      // 部分成功（危险！）
        FAILED,       // 全部失败
        ROLLBACK_SUCCESS,  // 回滚成功
        ROLLBACK_FAILED    // 回滚失败（极度危险！）
    }

    // 单个操作
    public static class TxOperation {
        public String id;
        public String type;        // SPOT_BUY, SPOT_SELL, FUTURES_SHORT, FUTURES_CLOSE
        public String symbol;
        public BigDecimal quantity;
        public String status;      // PENDING, SUCCESS, FAILED
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

    // 事务结果
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

    public AtomicTransactionManager(BinanceFuturesClient futuresClient) {
        this.futuresClient = futuresClient;
    }

    /**
     * 原子性开仓：现货买入 + 合约做空
     * 要么都成功，要么都失败（失败自动回滚）
     */
    public TxResult atomicOpenPosition(String symbol, BigDecimal quantity) {
        log.info("");
        log.info("╔══════════════════════════════════════════════════════════╗");
        log.info("║           🛡️  开始原子性开仓事务                              ║");
        log.info("║           现货买入 + 合约做空，要么都成，要么都不成           ║");
        log.info("╚══════════════════════════════════════════════════════════╝");
        log.info("币种: {}, 数量: {}", symbol, quantity);

        TxResult result = new TxResult();
        
        // 1. 定义两个操作
        TxOperation spotBuy = new TxOperation("SPOT_BUY", symbol, quantity);
        TxOperation futuresShort = new TxOperation("FUTURES_SHORT", symbol, quantity);
        result.operations.add(spotBuy);
        result.operations.add(futuresShort);

        // 2. 执行操作1：现货买入
        boolean spotOk = executeOperationWithRetry(spotBuy);
        
        // 3. 执行操作2：合约做空
        boolean futuresOk = executeOperationWithRetry(futuresShort);

        // 4. 判断结果
        if (spotOk && futuresOk) {
            result.status = TxStatus.SUCCESS;
            result.message = "✅ 原子性开仓成功，两笔订单都已成交";
            log.info("✅ {}", result.message);
        } else if (!spotOk && !futuresOk) {
            result.status = TxStatus.FAILED;
            result.message = "❌ 原子性开仓失败，两笔订单都未成交";
            log.error("❌ {}", result.message);
        } else {
            // 部分成功！危险！必须回滚！
            result.status = TxStatus.PARTIAL;
            result.message = "⚠️ 原子性开仓部分成功，启动回滚...";
            log.warn("{}", result.message);
            
            // 执行回滚
            boolean rollbackOk = rollbackOpenPosition(result);
            if (rollbackOk) {
                result.status = TxStatus.ROLLBACK_SUCCESS;
                result.message = "✅ 回滚成功，无单边敞口";
                log.info("✅ {}", result.message);
            } else {
                result.status = TxStatus.ROLLBACK_FAILED;
                result.message = "🚨 极度危险！回滚失败！存在单边敞口！请立即人工检查！";
                log.error("🚨 {}", result.message);
                // TODO: 这里可以集成飞书/钉钉/短信报警
            }
        }

        logResult(result);
        return result;
    }

    /**
     * 原子性平仓：现货卖出 + 合约平空
     */
    public TxResult atomicClosePosition(String symbol, BigDecimal quantity) {
        log.info("");
        log.info("╔══════════════════════════════════════════════════════════╗");
        log.info("║           🛡️  开始原子性平仓事务                              ║");
        log.info("║           现货卖出 + 平合约空单，要么都成，要么都不成           ║");
        log.info("╚══════════════════════════════════════════════════════════╝");
        log.info("币种: {}, 数量: {}", symbol, quantity);

        TxResult result = new TxResult();
        
        TxOperation spotSell = new TxOperation("SPOT_SELL", symbol, quantity);
        TxOperation futuresClose = new TxOperation("FUTURES_CLOSE", symbol, quantity);
        result.operations.add(spotSell);
        result.operations.add(futuresClose);

        boolean spotOk = executeOperationWithRetry(spotSell);
        boolean futuresOk = executeOperationWithRetry(futuresClose);

        if (spotOk && futuresOk) {
            result.status = TxStatus.SUCCESS;
            result.message = "✅ 原子性平仓成功";
            log.info("✅ {}", result.message);
        } else if (!spotOk && !futuresOk) {
            result.status = TxStatus.FAILED;
            result.message = "❌ 原子性平仓失败";
            log.error("❌ {}", result.message);
        } else {
            result.status = TxStatus.PARTIAL;
            result.message = "⚠️ 原子性平仓部分成功，启动回滚...";
            log.warn("{}", result.message);
            
            boolean rollbackOk = rollbackClosePosition(result);
            if (rollbackOk) {
                result.status = TxStatus.ROLLBACK_SUCCESS;
                result.message = "✅ 回滚成功";
                log.info("✅ {}", result.message);
            } else {
                result.status = TxStatus.ROLLBACK_FAILED;
                result.message = "🚨 回滚失败！请立即人工检查！";
                log.error("🚨 {}", result.message);
            }
        }

        logResult(result);
        return result;
    }

    /**
     * 执行操作（带重试）
     */
    private boolean executeOperationWithRetry(TxOperation op) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                log.info("执行 {} (第 {} 次尝试)...", op, attempt);
                
                switch (op.type) {
                    case "SPOT_BUY":
                        op.orderId = futuresClient.buySpot(op.symbol, op.quantity);
                        op.status = "SUCCESS";
                        log.info("✅ 现货买入成功: orderId={}", op.orderId);
                        return true;
                        
                    case "SPOT_SELL":
                        op.orderId = futuresClient.sellSpot(op.symbol, op.quantity);
                        op.status = "SUCCESS";
                        log.info("✅ 现货卖出成功: orderId={}", op.orderId);
                        return true;
                        
                    case "FUTURES_SHORT":
                        op.orderId = futuresClient.openShort(op.symbol, op.quantity);
                        op.status = "SUCCESS";
                        log.info("✅ 合约做空成功: orderId={}", op.orderId);
                        return true;
                        
                    case "FUTURES_CLOSE":
                        op.orderId = futuresClient.closeShort(op.symbol, op.quantity);
                        op.status = "SUCCESS";
                        log.info("✅ 合约平仓成功: orderId={}", op.orderId);
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
     * 回滚开仓操作：
     * - 如果现货买成功了，就卖出现货
     * - 如果合约空成功了，就平掉合约空单
     */
    private boolean rollbackOpenPosition(TxResult result) {
        log.warn("🔄 开始回滚开仓操作...");
        boolean allRolledBack = true;

        for (TxOperation op : result.operations) {
            if (!"SUCCESS".equals(op.status)) {
                continue;
            }

            try {
                log.warn("🔄 回滚 {}: 卖出已买入的现货/平掉已开的合约...", op);
                
                switch (op.type) {
                    case "SPOT_BUY":
                        futuresClient.sellSpot(op.symbol, op.quantity);
                        log.info("✅ 现货买入已回滚（卖出）");
                        break;
                        
                    case "FUTURES_SHORT":
                        futuresClient.closeShort(op.symbol, op.quantity);
                        log.info("✅ 合约做空已回滚（平仓）");
                        break;
                }
                
            } catch (Exception e) {
                log.error("❌ 回滚 {} 失败: {}", op, e.getMessage());
                allRolledBack = false;
            }
        }

        return allRolledBack;
    }

    /**
     * 回滚平仓操作：
     * - 如果现货卖成功了，就买回现货
     * - 如果合约平成功了，就重新开空
     */
    private boolean rollbackClosePosition(TxResult result) {
        log.warn("🔄 开始回滚平仓操作...");
        boolean allRolledBack = true;

        for (TxOperation op : result.operations) {
            if (!"SUCCESS".equals(op.status)) {
                continue;
            }

            try {
                switch (op.type) {
                    case "SPOT_SELL":
                        futuresClient.buySpot(op.symbol, op.quantity);
                        log.info("✅ 现货卖出已回滚（买回）");
                        break;
                        
                    case "FUTURES_CLOSE":
                        futuresClient.openShort(op.symbol, op.quantity);
                        log.info("✅ 合约平仓已回滚（重新开空）");
                        break;
                }
                
            } catch (Exception e) {
                log.error("❌ 回滚 {} 失败: {}", op, e.getMessage());
                allRolledBack = false;
            }
        }

        return allRolledBack;
    }

    /**
     * 打印事务结果
     */
    private void logResult(TxResult result) {
        log.info("");
        log.info("┌─────────────────────────────────────────────────────┐");
        log.info("│              事务执行结果                              │");
        log.info("├─────────────────────────────────────────────────────┤");
        for (TxOperation op : result.operations) {
            String statusIcon = "SUCCESS".equals(op.status) ? "✅" : "❌";
            log.info("│  {} {}", statusIcon, op);
        }
        log.info("│");
        log.info("│  最终状态: {}", result.status);
        log.info("│  {}", result.message);
        if (result.isDangerous()) {
            log.error("│  🚨 危险！存在单边敞口风险！请立即人工检查！");
        }
        log.info("└─────────────────────────────────────────────────────┘");
        log.info("");
    }
}
