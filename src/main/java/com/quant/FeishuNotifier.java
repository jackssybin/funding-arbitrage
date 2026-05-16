package com.quant;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 飞书消息推送工具类
 */
public class FeishuNotifier {
    private static final Logger log = LoggerFactory.getLogger(FeishuNotifier.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    
    private final String webhookUrl;
    private final OkHttpClient client;
    private final boolean enabled;

    public FeishuNotifier() {
        this.webhookUrl = Config.FEISHU_WEBHOOK;
        this.client = new OkHttpClient();
        this.enabled = webhookUrl != null && !webhookUrl.isEmpty() && !webhookUrl.contains("your_webhook");
        if (enabled) {
            log.info("✅ 飞书推送已启用");
        }
    }

    /**
     * 发送文本消息
     */
    public void sendText(String text) {
        if (!enabled) return;
        
        String json = String.format("{\"msg_type\":\"text\",\"content\":{\"text\":\"%s\"}}", escapeJson(text));
        send(json);
    }

    /**
     * 发送开仓通知
     */
    public void sendOpenPosition(String symbol, String side, BigDecimal quantity, BigDecimal rate, BigDecimal annualized) {
        if (!enabled) return;
        
        String text = String.format(
            "🚀 【开仓通知】\n" +
            "━━━━━━━━━━━━━━━━\n" +
            "币种: %s\n" +
            "方向: %s\n" +
            "数量: %s\n" +
            "费率: %s%%\n" +
            "预计年化: %s%%\n" +
            "━━━━━━━━━━━━━━━━",
            symbol, side, 
            quantity.setScale(2, RoundingMode.HALF_UP),
            rate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
            annualized.setScale(2, RoundingMode.HALF_UP)
        );
        sendText(text);
    }

    /**
     * 发送平仓通知
     */
    public void sendClosePosition(String symbol, BigDecimal fundingEarning, BigDecimal tradingPnl, String reason) {
        if (!enabled) return;
        
        String text = String.format(
            "📉 【平仓通知】\n" +
            "━━━━━━━━━━━━━━━━\n" +
            "币种: %s\n" +
            "资金费收益: %s USDT\n" +
            "买卖盈亏: %s USDT\n" +
            "平仓原因: %s\n" +
            "━━━━━━━━━━━━━━━━",
            symbol,
            fundingEarning.setScale(4, RoundingMode.HALF_UP),
            tradingPnl.setScale(4, RoundingMode.HALF_UP),
            reason
        );
        sendText(text);
    }

    /**
     * 发送资金费结算通知
     */
    public void sendFundingSettlement(String symbol, BigDecimal earning, BigDecimal rate, int count) {
        if (!enabled) return;
        
        String text = String.format(
            "💰 【资金费结算】\n" +
            "━━━━━━━━━━━━━━━━\n" +
            "币种: %s\n" +
            "本次收益: %s USDT\n" +
            "当前费率: %s%%\n" +
            "累计结算次数: 第%d次\n" +
            "━━━━━━━━━━━━━━━━",
            symbol,
            earning.setScale(4, RoundingMode.HALF_UP),
            rate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
            count
        );
        sendText(text);
    }

    /**
     * 发送日报
     */
    public void sendDailyReport(String reportContent) {
        if (!enabled) return;
        sendText(reportContent);
    }

    /**
     * 发送异常告警
     */
    public void sendAlert(String message, Exception e) {
        if (!enabled) return;
        
        String text = String.format(
            "🚨 【异常告警】\n" +
            "━━━━━━━━━━━━━━━━\n" +
            "错误信息: %s\n" +
            "异常类型: %s\n" +
            "━━━━━━━━━━━━━━━━",
            message,
            e != null ? e.getClass().getSimpleName() : "未知"
        );
        sendText(text);
    }

    private void send(String json) {
        try {
            RequestBody body = RequestBody.create(json, JSON);
            Request request = new Request.Builder()
                .url(webhookUrl)
                .post(body)
                .build();
            
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("❌ 飞书推送失败: {}", response.code());
                }
            }
        } catch (Exception e) {
            log.warn("❌ 飞书推送异常: {}", e.getMessage());
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
