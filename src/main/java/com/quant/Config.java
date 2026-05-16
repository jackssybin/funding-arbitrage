package com.quant;

import io.github.cdimascio.dotenv.Dotenv;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 配置类 - 支持 Binance / OKX 双交易所切换
 */
public class Config {

    private static final Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();

    // ========== 交易所选择 ==========
    /** 使用哪家交易所：binance 或 okx */
    public static final String EXCHANGE = getEnv("EXCHANGE", "binance").toLowerCase();

    // ========== Binance API 配置 ==========
    public static final String API_KEY    = getEnv("API_KEY",    "");
    public static final String SECRET_KEY = getEnv("SECRET_KEY", "");

    // ========== OKX API 配置 ==========
    public static final String OKX_API_KEY    = getEnv("OKX_API_KEY",    "");
    public static final String OKX_SECRET_KEY = getEnv("OKX_SECRET_KEY", "");
    /** OKX 独有的 API 密码（创建 API 时自行设置的短语） */
    public static final String OKX_PASSPHRASE = getEnv("OKX_PASSPHRASE", "");
    /** 是否使用 OKX 官方模拟盘（true=模拟盘，false=实盘） */
    public static final boolean OKX_SIMULATED_TRADING = Boolean.parseBoolean(getEnv("OKX_SIMULATED_TRADING", "false"));

    // ========== 多币种配置 [功能1] ==========
    // 监控的交易对列表
    public static final List<String> TRADING_SYMBOLS = Arrays.asList(
            getEnv("TRADING_SYMBOLS", "BTCUSDT,ETHUSDT,SOLUSDT,XRPUSDT,DOGEUSDT").split(",")
    );
    
    // 同时持仓的最大币种数量
    public static final int MAX_POSITIONS = Integer.parseInt(
            getEnv("MAX_POSITIONS", "3")
    );
    
    // ========== 杠杆配置 [功能2] ==========
    // 杠杆倍数 (默认 3倍)
    public static final int LEVERAGE = Integer.parseInt(
            getEnv("LEVERAGE", "3")
    );
    
    // 单币种仓位价值 (USDT，例如 1000 USDT 等值的币)
    public static final BigDecimal POSITION_VALUE_USDT = new BigDecimal(
            getEnv("POSITION_VALUE_USDT", "1000")
    );
    
    // 资金费率阈值：超过这个值开仓 (默认 0.05%)
    public static final BigDecimal FUNDING_RATE_THRESHOLD = new BigDecimal(
            getEnv("FUNDING_RATE_THRESHOLD", "0.0005")
    );
    
    // 平仓阈值：低于这个值平仓 (默认 0.02%)
    public static final BigDecimal FUNDING_RATE_CLOSE_THRESHOLD = new BigDecimal(
            getEnv("FUNDING_RATE_CLOSE_THRESHOLD", "0.0002")
    );
    
    // ========== 自动移仓配置 [功能3] ==========
    // 移仓阈值：新币种费率比当前持仓高多少时移仓 (0.03% = 0.0003)
    public static final BigDecimal SWITCH_THRESHOLD = new BigDecimal(
            getEnv("SWITCH_THRESHOLD", "0.0003")
    );

    // ========== 网格交易配置 [功能4] ==========
    // Bug-6修复: 网格默认关闭——网格与纯合约资金费索利策略不兼容，默认不启用
    public static final boolean GRID_ENABLED = Boolean.parseBoolean(
            getEnv("GRID_ENABLED", "false")
    );
    
    // 网格层数
    public static final int GRID_LEVELS = Integer.parseInt(
            getEnv("GRID_LEVELS", "5")
    );
    
    // 网格间距百分比 (0.3% = 0.003)
    public static final BigDecimal GRID_SPACING = new BigDecimal(
            getEnv("GRID_SPACING", "0.003")
    );
    
    // 每网格订单占主仓位的比例 (10% = 0.1)
    public static final BigDecimal GRID_ORDER_RATIO = new BigDecimal(
            getEnv("GRID_ORDER_RATIO", "0.1")
    );

    // ========== 风控配置 ==========
    // 单日最大亏损 (USDT)
    public static final BigDecimal MAX_DAILY_LOSS = new BigDecimal(
            getEnv("MAX_DAILY_LOSS", "100")
    );
    
    // 最小账户余额 (USDT)
    public static final BigDecimal MIN_BALANCE = new BigDecimal(
            getEnv("MIN_BALANCE", "500")
    );
    
    // 止损百分比
    public static final BigDecimal STOP_LOSS_PERCENT = new BigDecimal(
            getEnv("STOP_LOSS_PERCENT", "0.02")
    );
    
    // 最大回撤百分比
    public static final BigDecimal MAX_DRAWDOWN_PERCENT = new BigDecimal(
            getEnv("MAX_DRAWDOWN_PERCENT", "0.1")
    );

    // ========== API 地址 ==========
    public static final String BINANCE_FUTURES_API = "https://fapi.binance.com";
    public static final String BINANCE_SPOT_API = "https://api.binance.com";

    // ========== 运行配置 ==========
    // 检查间隔（毫秒），默认10分钟
    public static final long CHECK_INTERVAL_MS = Long.parseLong(
            getEnv("CHECK_INTERVAL_MS", "600000")
    );
    
    // 资金费率更新间隔（毫秒），默认30分钟
    public static final long RATE_UPDATE_INTERVAL_MS = Long.parseLong(
            getEnv("RATE_UPDATE_INTERVAL_MS", "1800000")
    );
    
    // Web仪表盘端口
    public static final int DASHBOARD_PORT = Integer.parseInt(
            getEnv("DASHBOARD_PORT", "8080")
    );
    
    // 是否使用模拟模式（不下单）
    public static final boolean SIMULATION_MODE = Boolean.parseBoolean(
            getEnv("SIMULATION_MODE", "true")
    );

    // ========== 飞书推送配置 ==========
    public static final String FEISHU_WEBHOOK = getEnv("FEISHU_WEBHOOK", "");
    public static final String NOTIFY_FREQUENCY = getEnv("NOTIFY_FREQUENCY", "daily");

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return dotenv.get(key, defaultValue);
    }

    /**
     * 检查配置是否完整
     */
    public static boolean validate() {
        if (SIMULATION_MODE) {
            System.out.println("⚠️  当前为模拟模式，不需要真实API Key");
            return true;
        }
        if ("okx".equals(EXCHANGE)) {
            if (OKX_API_KEY.isEmpty() || OKX_SECRET_KEY.isEmpty() || OKX_PASSPHRASE.isEmpty()) {
                System.err.println("❌ 请配置 OKX_API_KEY, OKX_SECRET_KEY, OKX_PASSPHRASE");
                return false;
            }
            return true;
        }
        // binance
        if (API_KEY.isEmpty() || SECRET_KEY.isEmpty()) {
            System.err.println("❌ 请配置 API_KEY 和 SECRET_KEY");
            return false;
        }
        return true;
    }

    /**
     * 工厂方法：根据 EXCHANGE 配置创建对应的交易所客户端
     */
    public static ExchangeClient createExchangeClient() {
        if ("okx".equals(EXCHANGE)) {
            System.out.println("🔌 使用交易所：OKX" + (OKX_SIMULATED_TRADING ? " (官方模拟盘)" : " (实盘)"));
            return new OkxClient(OKX_API_KEY, OKX_SECRET_KEY, OKX_PASSPHRASE, OKX_SIMULATED_TRADING);
        } else {
            System.out.println("🔌 使用交易所：Binance");
            return new BinanceFuturesClient(API_KEY, SECRET_KEY);
        }
    }
}
