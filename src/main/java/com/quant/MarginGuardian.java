package com.quant;
import java.math.RoundingMode;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * 保证金守护线程 - 堵住第三个利润黑洞：杠杆爆仓
 * 
 * 功能：
 * 1. 后台线程持续监控合约账户保证金率
 * 2. 当保证金率低于安全阈值时，自动从现货账户划转 USDT 到合约
 * 3. 极端插针行情下自动补仓，避免 3 倍杠杆也不会爆
 */
public class MarginGuardian {

    private static final Logger log = LoggerFactory.getLogger(MarginGuardian.class);
    
    private static final BigDecimal WARNING_RATIO = new BigDecimal("0.1");
    private static final BigDecimal EMERGENCY_TOPUP_RATIO = new BigDecimal("0.05");
    private static final BigDecimal TOPUP_AMOUNT = new BigDecimal("500");
    private static final long CHECK_INTERVAL_MS = 30_000;

    private BinanceFuturesClient client;
    private volatile boolean running = false;
    private Thread guardianThread;

    /** 当 client 为 null（如使用 OKX），守护线程仅输出日志，不执行划转 */
    private OkxClient okxClient;

    /** 通用构造函数 - 传入具体交易所实现 */
    private MarginGuardian() {
    }

    /** Binance 构造函数 */
    public static MarginGuardian forBinance(BinanceFuturesClient client) {
        MarginGuardian mg = new MarginGuardian();
        mg.client = client;
        return mg;
    }

    /** OKX 构造函数 */
    public static MarginGuardian forOkx(OkxClient okxClient) {
        MarginGuardian mg = new MarginGuardian();
        mg.okxClient = okxClient;
        log.info("🛡️  使用 OKX 保证金守护");
        return mg;
    }

    public void start() {
        if (running) {
            log.warn("保证金守护线程已在运行");
            return;
        }
        
        running = true;
        guardianThread = new Thread(this::runGuardian, "Margin-Guardian");
        guardianThread.setDaemon(true);
        guardianThread.start();
        
        log.info("🛡️  保证金守护线程已启动！");
        log.info("   - 预警阈值: {}%", WARNING_RATIO.multiply(BigDecimal.valueOf(100)));
        log.info("   - 紧急补仓阈值: {}%", EMERGENCY_TOPUP_RATIO.multiply(BigDecimal.valueOf(100)));
        log.info("   - 单次补仓金额: {} USDT", TOPUP_AMOUNT);
    }

    public void stop() {
        running = false;
        if (guardianThread != null) {
            guardianThread.interrupt();
        }
        log.info("保证金守护线程已停止");
    }

    private void runGuardian() {
        log.info("🛡️  保证金守护开始巡逻中... (每 {} 秒检查一次)", CHECK_INTERVAL_MS / 1000);
        
        while (running) {
            try {
                checkAndTopupIfNeeded();
                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("守护线程检查异常: {}", e.getMessage());
            }
        }
    }

    public void checkAndTopupIfNeeded() {
        if (client == null) {
            if (okxClient != null) {
                checkOkxAndTopupIfNeeded();
                return;
            }
            log.debug("[OKX模式] 保证金守护暂不支持自动划转，跳过检查");
            return;
        }
        try {
            AccountInfo accountInfo = getFuturesAccountInfo();
            if (accountInfo == null) {
                return;
            }

            BigDecimal marginRatio = accountInfo.marginRatio;
            
            if (marginRatio.compareTo(EMERGENCY_TOPUP_RATIO) < 0) {
                log.warn("🚨 紧急！保证金率 {}% 低于阈值 {}%，立即补仓！",
                        marginRatio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                        EMERGENCY_TOPUP_RATIO.multiply(BigDecimal.valueOf(100)));
                
                boolean success = transferFromSpotToFutures(TOPUP_AMOUNT);
                if (success) {
                    log.info("✅ 紧急补仓成功！划转 {} USDT 到合约账户", TOPUP_AMOUNT);
                } else {
                    log.error("❌ 紧急补仓失败！请人工检查！");
                }
            } else if (marginRatio.compareTo(WARNING_RATIO) < 0) {
                log.warn("⚠️ 预警！保证金率 {}% 低于预警阈值 {}%，请注意风险",
                        marginRatio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                        WARNING_RATIO.multiply(BigDecimal.valueOf(100)));
            }

        } catch (Exception e) {
            log.error("检查保证金失败: {}", e.getMessage());
        }
    }

    public static class AccountInfo {
        public BigDecimal availableBalance;
        public BigDecimal totalMarginBalance;
        public BigDecimal marginRatio;
    }

    private void checkOkxAndTopupIfNeeded() {
        try {
            BigDecimal tradingBalance = okxClient.getBalance();
            if (tradingBalance.compareTo(TOPUP_AMOUNT) < 0) {
                log.warn("OKX trading balance {} USDT is below {}; transfer from funding account",
                        tradingBalance, TOPUP_AMOUNT);
                boolean success = transferFromSpotToFutures(TOPUP_AMOUNT);
                if (!success) {
                    log.error("OKX emergency transfer failed; manual margin check required");
                }
            }
        } catch (Exception e) {
            log.error("OKX margin guardian check failed: {}", e.getMessage());
        }
    }

    private AccountInfo getFuturesAccountInfo() throws IOException {
        if (Config.SIMULATION_MODE) {
            AccountInfo info = new AccountInfo();
            info.availableBalance = new BigDecimal("10000");
            info.totalMarginBalance = new BigDecimal("15000");
            info.marginRatio = new BigDecimal("0.5");
            return info;
        }

        long timestamp = System.currentTimeMillis();
        String params = "timestamp=" + timestamp;
        String signature = sign(params);
        String url = "https://fapi.binance.com/fapi/v2/account?" + params + "&signature=" + signature;

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", Config.API_KEY)
                .get()
                .build();

        try (okhttp3.Response response = client.getHttpClient().newCall(request).execute()) {
            String body = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("获取合约账户失败: " + body);
            }
            
            JsonNode json = client.getMapper().readTree(body);
            
            AccountInfo info = new AccountInfo();
            info.availableBalance = new BigDecimal(json.get("availableBalance").asText());
            info.totalMarginBalance = new BigDecimal(json.get("totalMarginBalance").asText());
            
            JsonNode positions = json.get("positions");
            BigDecimal totalMaintenanceMargin = BigDecimal.ZERO;
            for (JsonNode pos : positions) {
                if (new BigDecimal(pos.get("positionAmt").asText()).abs().compareTo(BigDecimal.ZERO) > 0) {
                    totalMaintenanceMargin = totalMaintenanceMargin.add(
                            new BigDecimal(pos.get("maintMargin").asText())
                    );
                }
            }
            
            if (totalMaintenanceMargin.compareTo(BigDecimal.ZERO) > 0 
                    && info.totalMarginBalance.compareTo(BigDecimal.ZERO) > 0) {
                info.marginRatio = totalMaintenanceMargin.divide(
                        info.totalMarginBalance, 6, RoundingMode.HALF_UP);
            } else {
                info.marginRatio = BigDecimal.ONE;
            }
            
            return info;
        }
    }

    public boolean transferFromSpotToFutures(BigDecimal amount) throws IOException {
        if (Config.SIMULATION_MODE) {
            log.info("[模拟模式] 从现货划转 {} USDT 到合约", amount);
            return true;
        }
        if (okxClient != null) {
            return okxClient.transferFromSpotToFutures(amount);
        }

        long timestamp = System.currentTimeMillis();
        String params = "asset=USDT&amount=" + amount + "&type=1&timestamp=" + timestamp;
        String signature = sign(params);
        String url = "https://api.binance.com/sapi/v1/asset/transfer?" + params + "&signature=" + signature;

        okhttp3.RequestBody body = okhttp3.RequestBody.create("",
                okhttp3.MediaType.parse("application/json"));
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", Config.API_KEY)
                .post(body)
                .build();

        try (okhttp3.Response response = client.getHttpClient().newCall(request).execute()) {
            return response.isSuccessful();
        }
    }

    private String sign(String data) {
        try {
            javax.crypto.Mac sha256HMAC = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                    Config.SECRET_KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            sha256HMAC.init(keySpec);
            byte[] hash = sha256HMAC.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("签名失败", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
