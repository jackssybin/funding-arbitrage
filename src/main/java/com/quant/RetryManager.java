package com.quant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * 重试管理器 - 解决API调用失败、网络波动问题
 * 
 * 功能：
 * 1. 自动重试失败的API调用
 * 2. 失败后进入降级队列
 * 3. 启动时恢复未完成操作
 * 4. 防止极端情况留下单边敞口
 */
public class RetryManager {

    private static final Logger log = LoggerFactory.getLogger(RetryManager.class);

    // 默认重试配置
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 2000;

    // 重试队列 - 保存需要执行的操作
    private final BlockingQueue<RetryOperation> retryQueue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean running = false;

    public void start() {
        running = true;
        // 启动重试处理线程
        executor.submit(this::processRetryQueue);
        log.info("🔄 重试管理器已启动");
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
        executor.shutdown();
        log.info("🛑 重试管理器已停止");
    }

    /**
     * 执行带重试的操作
     */
    public <T> T executeWithRetry(String operationName, Supplier<T> operation) {
        return executeWithRetry(operationName, operation, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY_MS);
    }

    /**
     * 执行带重试的操作（自定义配置）
     */
    public <T> T executeWithRetry(String operationName, Supplier<T> operation, int maxRetries, long retryDelayMs) {
        int attempts = 0;
        Exception lastException = null;

        while (attempts < maxRetries && running) {
            try {
                attempts++;
                T result = operation.get();
                
                if (attempts > 1) {
                    log.info("✅ {} 重试成功 (第{}次)", operationName, attempts);
                }
                return result;

            } catch (Exception e) {
                lastException = e;
                log.warn("⚠️  {} 失败 (第{}/{}次): {}", operationName, attempts, maxRetries, e.getMessage());

                if (attempts < maxRetries) {
                    try {
                        Thread.sleep(retryDelayMs * attempts); // 指数退避
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // 全部重试失败，加入重试队列
        RetryOperation failedOp = new RetryOperation(operationName, operation, maxRetries, retryDelayMs);
        retryQueue.offer(failedOp);
        log.error("❌ {} 重试{}次全部失败，已加入后台重试队列", operationName, maxRetries);

        throw new RuntimeException("操作失败: " + operationName, lastException);
    }

    /**
     * 执行无返回值的重试操作
     */
    public void executeWithRetry(String operationName, Runnable operation) {
        executeWithRetry(operationName, () -> {
            operation.run();
            return null;
        });
    }

    /**
     * 后台处理重试队列
     */
    private void processRetryQueue() {
        while (running) {
            try {
                RetryOperation op = retryQueue.poll(10, TimeUnit.SECONDS);
                if (op != null) {
                    log.info("🔄 后台重试: {}", op.name);
                    try {
                        op.operation.get();
                        log.info("✅ 后台重试成功: {}", op.name);
                    } catch (Exception e) {
                        // 再次失败，延迟后重新入队
                        op.retryCount++;
                        if (op.retryCount < 10) { // 最多重试10次
                            scheduler.schedule(() -> retryQueue.offer(op), 5 * op.retryCount, TimeUnit.MINUTES);
                            log.warn("⚠️  后台重试失败 {} (已重试{}次，稍后再试)", op.name, op.retryCount);
                        } else {
                            // 超过10次，发出警报
                            log.error("🚨 关键操作连续失败超过10次！需要人工处理: {}", op.name);
                            // 这里可以接入告警通知
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("重试队列处理异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 启动恢复 - 检查是否有未完成的操作
     */
    public void recoverOnStartup(ExchangeClient client, Map<String, Position> positions) {
        log.info("🔧 启动恢复检查...");

        try {
            // 检查每个币种是否有异常持仓
            for (String symbol : Config.TRADING_SYMBOLS) {
                try {
                    BigDecimal currentPos = client.getCurrentPosition(symbol);
                    Position localPos = positions.get(symbol);

                    // 如果实际有持仓但本地没有记录，说明上次异常退出
                    if (currentPos.abs().compareTo(BigDecimal.valueOf(0.001)) > 0 
                            && (localPos == null || !localPos.hasPosition())) {
                        
                        log.warn("⚠️  发现未记录的持仓: {} = {}", symbol, currentPos);
                        
                        // 尝试恢复状态
                        BigDecimal currentPrice = client.getCurrentPrice(symbol);
                        BigDecimal currentRate = client.getFundingRate(symbol);
                        String side = currentPos.compareTo(BigDecimal.ZERO) > 0 ? "LONG" : "SHORT";
                        
                        if (localPos != null) {
                            localPos.open(currentPos.abs(), currentPrice, currentRate, side);
                            log.info("✅ 已恢复持仓状态: {} {}", symbol, side);
                        }
                    }
                } catch (Exception e) {
                    log.warn("检查{}持仓状态失败: {}", symbol, e.getMessage());
                }
            }
            
            log.info("✅ 启动恢复完成");
            
        } catch (Exception e) {
            log.error("启动恢复失败: {}", e.getMessage());
        }
    }

    /**
     * 获取待重试的操作数量
     */
    public int getPendingRetryCount() {
        return retryQueue.size();
    }

    // ===== 内部类 =====

    private static class RetryOperation {
        final String name;
        final Supplier<?> operation;
        final int maxRetries;
        final long retryDelayMs;
        int retryCount = 0;

        RetryOperation(String name, Supplier<?> operation, int maxRetries, long retryDelayMs) {
            this.name = name;
            this.operation = operation;
            this.maxRetries = maxRetries;
            this.retryDelayMs = retryDelayMs;
        }
    }
}
