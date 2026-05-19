package com.quant;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
    public void sendOpenPosition(String symbol, String side, BigDecimal quantity, BigDecimal rate, BigDecimal annualized, BigDecimal balance, BigDecimal expectedEarning, BigDecimal fee) {
        if (!enabled) return;
        
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String text = String.format(
            "🚀 【开仓通知】\n" +
            "━━━━━━━━━━━━━━━━\n" +
            "发生时间: %s\n" +
            "币种: %s\n" +
            "方向: %s\n" +
            "数量: %s\n" +
            "费率: %s%%\n" +
            "费率预期收益: %s USDT\n" +
            "预计年化: %s%%\n" +
            "开仓手续费: %s USDT\n" +
            "当前余额: %s USDT\n" +
            "━━━━━━━━━━━━━━━━",
            time,
            symbol, side, 
            quantity.setScale(2, RoundingMode.HALF_UP),
            rate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
            expectedEarning.setScale(4, RoundingMode.HALF_UP),
            annualized.setScale(2, RoundingMode.HALF_UP),
            fee.setScale(4, RoundingMode.HALF_UP),
            balance.setScale(2, RoundingMode.HALF_UP)
        );
        sendText(text);
    }

    /**
     * 发送平仓通知
     */
    public void sendClosePosition(String symbol, BigDecimal fundingEarning, BigDecimal tradingPnl, String reason, BigDecimal balance, BigDecimal totalFees) {
        if (!enabled) return;
        
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String text = String.format(
            "📉 【平仓通知】\n" +
            "━━━━━━━━━━━━━━━━\n" +
            "发生时间: %s\n" +
            "币种: %s\n" +
            "资金费收益: %s USDT\n" +
            "买卖盈亏: %s USDT\n" +
            "累计手续费: %s USDT\n" +
            "平仓原因: %s\n" +
            "当前余额: %s USDT\n" +
            "━━━━━━━━━━━━━━━━",
            time,
            symbol,
            fundingEarning.setScale(4, RoundingMode.HALF_UP),
            tradingPnl.setScale(4, RoundingMode.HALF_UP),
            totalFees.setScale(4, RoundingMode.HALF_UP),
            reason,
            balance.setScale(2, RoundingMode.HALF_UP)
        );
        sendText(text);
    }

    /**
     * 发送资金费结算通知
     */
    public void sendFundingSettlement(String symbol, BigDecimal earning, BigDecimal rate, int count, BigDecimal balance, BigDecimal expectedEarning) {
        if (!enabled) return;
        
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String text = String.format(
            "💰 【资金费结算】\n" +
            "━━━━━━━━━━━━━━━━\n" +
            "发生时间: %s\n" +
            "币种: %s\n" +
            "本次收益: %s USDT\n" +
            "当前费率: %s%%\n" +
            "费率预期收益: %s USDT\n" +
            "累计结算次数: 第%d次\n" +
            "当前余额: %s USDT\n" +
            "━━━━━━━━━━━━━━━━",
            time,
            symbol,
            earning.setScale(4, RoundingMode.HALF_UP),
            rate.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_UP),
            expectedEarning.setScale(4, RoundingMode.HALF_UP),
            count,
            balance.setScale(2, RoundingMode.HALF_UP)
        );
        sendText(text);
    }

    /**
     * 发送日报
     */
    public void sendDailyReport(String reportContent) {
        if (!enabled) return;
        // 日报内容比较长，提取关键信息发送，避免消息过长
        StringBuilder summary = new StringBuilder();
        summary.append("📊 【每日收益报告】\n");
        summary.append("━━━━━━━━━━━━━━━━\n");
        
        // 提取关键指标
        String[] lines = reportContent.split("\n");
        boolean inStats = false;
        int count = 0;
        for (String line : lines) {
            if (line.contains("当日统计摘要")) {
                inStats = true;
                continue;
            }
            if (inStats && line.contains("|")) {
                if (line.contains("开仓次数") || line.contains("平仓次数") || 
                    line.contains("资金费收益") || line.contains("余额变动") ||
                    line.contains("当前持仓数")) {
                    // 格式化统计行
                    String clean = line.replace("|", " ").trim();
                    if (!clean.contains("指标") && !clean.contains("-")) {
                        summary.append(clean).append("\n");
                    }
                }
            }
            if (inStats && line.contains("当前持仓详情")) {
                break;
            }
        }
        
        summary.append("━━━━━━━━━━━━━━━━\n");
        summary.append("💡 完整日报已保存到文件");
        
        sendText(summary.toString());
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
