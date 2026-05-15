package com.quant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 策略仪表盘 - 内置Web服务器，提供可视化监控
 * 
 * 访问地址: http://localhost:8080
 * 
 * 接口:
 * GET /api/status     - 获取当前状态
 * GET /api/positions  - 获取持仓列表
 * GET /api/control    - 策略控制（暂停/恢复/平仓）
 * GET /               - 仪表盘页面
 */
public class StrategyDashboard {

    private static final Logger log = LoggerFactory.getLogger(StrategyDashboard.class);
    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final int port;
    private final FundingArbitrageBot bot;
    private final StrategyControl control;
    private final StrategyPersistence persistence;
    private final ObjectMapper mapper;

    private com.sun.net.httpserver.HttpServer server;

    public StrategyDashboard(int port, FundingArbitrageBot bot, 
                            StrategyControl control, StrategyPersistence persistence) {
        this.port = port;
        this.bot = bot;
        this.control = control;
        this.persistence = persistence;
        this.mapper = new ObjectMapper();
    }

    public void start() {
        try {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
            
            // API路由
            server.createContext("/", this::handleIndex);
            server.createContext("/api/status", this::handleStatus);
            server.createContext("/api/positions", this::handlePositions);
            server.createContext("/api/control", this::handleControl);
            server.createContext("/api/params", this::handleParams);

            server.setExecutor(null); // 默认执行器
            server.start();

            log.info("🌐 仪表盘已启动: http://localhost:{}", port);
        } catch (Exception e) {
            log.error("启动仪表盘失败: {}", e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("🛑 仪表盘已停止");
        }
    }

    // ===== HTTP处理方法 =====

    private void handleIndex(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String html = buildDashboardHtml();
        sendResponse(exchange, 200, "text/html; charset=utf-8", html.getBytes("UTF-8"));
    }

    private void handleStatus(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        ObjectNode json = mapper.createObjectNode();
        json.put("time", LocalDateTime.now().format(dtf));
        json.put("running", control.isRunning());
        json.put("tradingPaused", control.isTradingPaused());
        json.put("totalPnl", bot.getTotalPnl().doubleValue());
        json.put("totalTrades", bot.getTotalTrades());
        json.put("positionCount", (int) bot.getPositions().values().stream().filter(Position::hasPosition).count());
        
        StrategyPersistence.DailyStats stats = persistence.getTodayStats();
        ObjectNode today = mapper.createObjectNode();
        today.put("openCount", stats.openCount);
        today.put("closeCount", stats.closeCount);
        today.put("fundingCount", stats.fundingCount);
        json.set("today", today);

        sendJsonResponse(exchange, json);
    }

    private void handlePositions(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        ObjectNode json = mapper.createObjectNode();
        
        for (Position pos : bot.getPositions().values()) {
            if (pos.hasPosition()) {
                ObjectNode posNode = mapper.createObjectNode();
                posNode.put("side", pos.getPositionSide());
                posNode.put("size", pos.getPositionSize().doubleValue());
                posNode.put("entryPrice", pos.getEntryPrice().doubleValue());
                posNode.put("fundingRate", pos.getLastFundingRate().doubleValue());
                posNode.put("fundingCount", pos.getFundingCount());
                posNode.put("earned", pos.getTotalFundingEarned().doubleValue());
                posNode.put("holdingHours", pos.getHoldingHours());
                
                if (pos.getUnrealizedPnlRatio() != null) {
                    posNode.put("pnlRatio", pos.getUnrealizedPnlRatio().doubleValue());
                }
                
                json.set(pos.getSymbol(), posNode);
            }
        }
        
        sendJsonResponse(exchange, json);
    }

    private void handleControl(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String action = getQueryParam(query, "action");

        ObjectNode json = mapper.createObjectNode();
        
        try {
            switch (action) {
                case "pause":
                    control.pauseTrading();
                    json.put("success", true);
                    json.put("message", "交易已暂停");
                    break;
                case "resume":
                    control.resumeTrading();
                    json.put("success", true);
                    json.put("message", "交易已恢复");
                    break;
                case "closeAll":
                    control.emergencyCloseAll();
                    json.put("success", true);
                    json.put("message", "已平仓所有仓位");
                    break;
                default:
                    json.put("success", false);
                    json.put("message", "未知操作: " + action);
            }
        } catch (Exception e) {
            json.put("success", false);
            json.put("message", e.getMessage());
        }

        sendJsonResponse(exchange, json);
    }

    private void handleParams(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        ObjectNode json = mapper.createObjectNode();

        try {
            String minRate = getQueryParam(query, "minFundingRate");
            String stopLoss = getQueryParam(query, "stopLossRatio");
            String maxPos = getQueryParam(query, "maxPositions");

            if (minRate != null) {
                control.setMinFundingRate(new BigDecimal(minRate));
            }
            if (stopLoss != null) {
                control.setStopLossRatio(new BigDecimal(stopLoss));
            }
            if (maxPos != null) {
                control.setMaxPositions(Integer.parseInt(maxPos));
            }

            json.put("success", true);
            json.put("currentParams", control.getStatusSummary());
        } catch (Exception e) {
            json.put("success", false);
            json.put("message", e.getMessage());
        }

        sendJsonResponse(exchange, json);
    }

    // ===== 工具方法 =====

    private String getQueryParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=");
            if (parts.length == 2 && parts[0].equals(key)) {
                return parts[1];
            }
        }
        return null;
    }

    private void sendJsonResponse(com.sun.net.httpserver.HttpExchange exchange, ObjectNode json) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(json);
        sendResponse(exchange, 200, "application/json; charset=utf-8", bytes);
    }

    private void sendResponse(com.sun.net.httpserver.HttpExchange exchange, int code, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /**
     * 构建仪表盘HTML页面
     */
    private String buildDashboardHtml() {
        return "<!DOCTYPE html>\n" +
"<html lang='zh-CN'>\n" +
"<head>\n" +
"    <meta charset='UTF-8'>\n" +
"    <meta name='viewport' content='width=device-width, initial-scale=1.0'>\n" +
"    <title>资金费率套利机器人 - 监控仪表盘</title>\n" +
"    <style>\n" +
"        * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
"        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #1a1a2e; color: #eee; padding: 20px; }\n" +
"        .container { max-width: 1200px; margin: 0 auto; }\n" +
"        h1 { text-align: center; margin-bottom: 30px; color: #00d4ff; }\n" +
"        .card { background: #16213e; border-radius: 12px; padding: 20px; margin-bottom: 20px; box-shadow: 0 4px 6px rgba(0,0,0,0.3); }\n" +
"        .card h2 { color: #00d4ff; margin-bottom: 15px; font-size: 1.2rem; border-bottom: 1px solid #0f3460; padding-bottom: 10px; }\n" +
"        .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 15px; }\n" +
"        .stat-item { background: #0f3460; padding: 15px; border-radius: 8px; text-align: center; }\n" +
"        .stat-value { font-size: 2rem; font-weight: bold; color: #00ff88; }\n" +
"        .stat-label { color: #888; font-size: 0.9rem; margin-top: 5px; }\n" +
"        .controls { display: flex; gap: 10px; flex-wrap: wrap; }\n" +
"        .btn { padding: 12px 24px; border: none; border-radius: 8px; cursor: pointer; font-size: 1rem; font-weight: 500; transition: all 0.3s; }\n" +
"        .btn-green { background: #00ff88; color: #000; }\n" +
"        .btn-yellow { background: #ffc107; color: #000; }\n" +
"        .btn-red { background: #ff4757; color: #fff; }\n" +
"        .btn:hover { transform: translateY(-2px); box-shadow: 0 4px 12px rgba(0,0,0,0.3); }\n" +
"        .position { background: #0f3460; padding: 15px; border-radius: 8px; margin-bottom: 10px; display: flex; justify-content: space-between; align-items: center; }\n" +
"        .pos-info { display: flex; gap: 20px; }\n" +
"        .pos-symbol { font-weight: bold; font-size: 1.2rem; color: #00d4ff; }\n" +
"        .pos-side { padding: 4px 12px; border-radius: 4px; font-size: 0.85rem; }\n" +
"        .side-long { background: #2ed573; color: #000; }\n" +
"        .side-short { background: #ff4757; color: #fff; }\n" +
"        .pnl-positive { color: #00ff88; }\n" +
"        .pnl-negative { color: #ff4757; }\n" +
"        .status-badge { padding: 4px 12px; border-radius: 4px; font-size: 0.85rem; }\n" +
"        .status-running { background: #00ff88; color: #000; }\n" +
"        .status-paused { background: #ffc107; color: #000; }\n" +
"        .refresh { text-align: center; color: #666; font-size: 0.9rem; margin-top: 20px; }\n" +
"    </style>\n" +
"</head>\n" +
"<body>\n" +
"    <div class='container'>\n" +
"        <h1>💰 资金费率套利机器人</h1>\n" +
"\n" +
"        <div class='card'>\n" +
"            <h2>📊 运行状态</h2>\n" +
"            <div class='stats' id='statusStats'>\n" +
"                <div class='stat-item'><div class='stat-value' id='totalPnl'>-</div><div class='stat-label'>累计收益 (USDT)</div></div>\n" +
"                <div class='stat-item'><div class='stat-value' id='positionCount'>-</div><div class='stat-label'>持仓数量</div></div>\n" +
"                <div class='stat-item'><div class='stat-value' id='fundingCount'>-</div><div class='stat-label'>今日结算次数</div></div>\n" +
"                <div class='stat-item'><div class='stat-value' id='statusBadge'>-</div><div class='stat-label'>运行状态</div></div>\n" +
"            </div>\n" +
"        </div>\n" +
"\n" +
"        <div class='card'>\n" +
"            <h2>🎮 策略控制</h2>\n" +
"            <div class='controls'>\n" +
"                <button class='btn btn-yellow' onclick='pauseTrading()'>⏸️ 暂停交易</button>\n" +
"                <button class='btn btn-green' onclick='resumeTrading()'>▶️ 恢复交易</button>\n" +
"                <button class='btn btn-red' onclick='closeAll()'>🔴 紧急平仓</button>\n" +
"            </div>\n" +
"        </div>\n" +
"\n" +
"        <div class='card'>\n" +
"            <h2>📈 当前持仓</h2>\n" +
"            <div id='positionsList'>\n" +
"                <p style='color:#666;text-align:center;padding:20px;'>暂无持仓</p>\n" +
"            </div>\n" +
"        </div>\n" +
"\n" +
"        <div class='refresh'>\n" +
"            最后更新: <span id='lastUpdate'>-</span> | 每30秒自动刷新\n" +
"        </div>\n" +
"    </div>\n" +
"\n" +
"    <script>\n" +
"        function refreshData() {\n" +
"            fetch('/api/status')\n" +
"                .then(r => r.json())\n" +
"                .then(data => {\n" +
"                    document.getElementById('totalPnl').textContent = data.totalPnl.toFixed(2);\n" +
"                    document.getElementById('positionCount').textContent = data.positionCount;\n" +
"                    document.getElementById('fundingCount').textContent = data.today.fundingCount;\n" +
"                    document.getElementById('lastUpdate').textContent = data.time;\n" +
"                    \n" +
"                    let badge = document.getElementById('statusBadge');\n" +
"                    if (data.tradingPaused) {\n" +
"                        badge.textContent = '已暂停';\n" +
"                        badge.className = 'stat-value status-paused';\n" +
"                    } else {\n" +
"                        badge.textContent = '运行中';\n" +
"                        badge.className = 'stat-value status-running';\n" +
"                    }\n" +
"                });\n" +
"\n" +
"            fetch('/api/positions')\n" +
"                .then(r => r.json())\n" +
"                .then(data => {\n" +
"                    let html = '';\n" +
"                    for (let symbol in data) {\n" +
"                        let pos = data[symbol];\n" +
"                        let sideClass = pos.side === 'LONG' ? 'side-long' : 'side-short';\n" +
"                        let sideText = pos.side === 'LONG' ? '做多' : '做空';\n" +
"                        let pnlClass = (pos.pnlRatio || 0) >= 0 ? 'pnl-positive' : 'pnl-negative';\n" +
"                        let pnlText = ((pos.pnlRatio || 0) * 100).toFixed(2) + '%';\n" +
"                        \n" +
"                        html += `\n" +
"                            <div class='position'>\n" +
"                                <div class='pos-info'>\n" +
"                                    <span class='pos-symbol'>${symbol}</span>\n" +
"                                    <span class='pos-side ${sideClass}'>${sideText}</span>\n" +
"                                    <span>数量: ${pos.size.toFixed(4)}</span>\n" +
"                                    <span>开仓价: $${pos.entryPrice.toFixed(2)}</span>\n" +
"                                    <span>费率: ${(Math.abs(pos.fundingRate) * 100).toFixed(4)}%</span>\n" +
"                                    <span>已结算: ${pos.fundingCount}次</span>\n" +
"                                    <span>持仓: ${pos.holdingHours}小时</span>\n" +
"                                </div>\n" +
"                                <div>\n" +
"                                    <span>已赚: $${pos.earned.toFixed(4)}</span>\n" +
"                                    <span class='${pnlClass}' style='margin-left:15px;'>浮亏: ${pnlText}</span>\n" +
"                                </div>\n" +
"                            </div>\n" +
"                        `;\n" +
"                    }\n" +
"                    if (html === '') {\n" +
"                        html = \"<p style='color:#666;text-align:center;padding:20px;'>暂无持仓</p>\";\n" +
"                    }\n" +
"                    document.getElementById('positionsList').innerHTML = html;\n" +
"                });\n" +
"        }\n" +
"\n" +
"        function pauseTrading() { if(confirm('确定暂停交易？')) fetch('/api/control?action=pause').then(refreshData); }\n" +
"        function resumeTrading() { fetch('/api/control?action=resume').then(refreshData); }\n" +
"        function closeAll() { if(confirm('确定平仓所有仓位？')) fetch('/api/control?action=closeAll').then(refreshData); }\n" +
"\n" +
"        // 立即刷新 + 30秒自动刷新\n" +
"        refreshData();\n" +
"        setInterval(refreshData, 30000);\n" +
"    </script>\n" +
"</body>\n" +
"</html>";
    }
}
