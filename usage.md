# Funding Arbitrage 项目使用说明

## 1. 项目是做什么的

本项目是一个面向数字货币永续合约资金费率套利的自动化交易程序。

它的核心思路是：当某个交易对的资金费率足够高时，系统尝试开对应方向的永续合约仓位，以获取资金费收益；当费率下降、风险升高、达到止盈止损条件，或发现更优交易机会时，系统会尝试平仓或移仓。

项目的最终目标是盈利，但它不是“稳赚程序”。资金费收益可能被手续费、滑点、价格波动、现货对冲失败、交易所接口异常、极端行情和参数过拟合吞掉。因此，本项目更准确地说是一个“资金费率套利研究、回测、模拟盘验证、小资金实盘灰度”的交易系统。

## 2. 项目能做哪些事

当前项目主要支持以下能力：

1. 多币种资金费率监控

   系统会读取配置中的交易对列表，例如 `BTCUSDT,ETHUSDT`，定期获取当前资金费率，并按费率绝对值排序，优先评估更有机会的币种。

2. 正费率开空、负费率开多

   当资金费率为正时，多头支付空头，系统倾向于开空合约获取资金费。

   当资金费率为负时，空头支付多头，系统倾向于开多合约获取资金费。

3. 实盘市场状态过滤

   系统会检查盘口价差、高低价波动、成交量异常、预测费率方向和偏离度。如果关键行情快照缺失，会拒绝开仓，避免在数据不完整时误入场。

4. 币种准入和剔除

   可以通过配置剔除高波动、低稳定性或回测表现差的币种，例如 `SOLUSDT,XRPUSDT,DOGEUSDT`。这对盈利稳定性很重要。

5. 现货对冲模式

   当开启 `SPOT_HEDGE_ENABLED=true` 时，正费率开空合约时可以同步买入现货，降低单边价格暴露。该功能需要交易所现货 API、余额查询、现货成交回报和异常补偿配合使用。

6. OKX 和 Binance 交易所适配

   项目支持 Binance 和 OKX。近期重点修复了 OKX 实盘关键问题，包括合约张数和币数量转换、动态精度、预测费率、现货余额、账户划转、单双向持仓模式适配。

7. 真实资金费账单读取

   程序会尽量从交易所账单中读取真实资金费收入，而不是只用理论值估算。

8. 成本核算

   系统会统计开仓手续费、平仓手续费、现货手续费、滑点估算和成交回报中的实际费用，避免收益统计过度乐观。

9. 回测和参数验证

   项目包含回测框架，可以使用历史 K 线和资金费率数据评估策略表现，并输出手续费、滑点、最大回撤、胜率、资金费收益占比、不同币种表现和滚动窗口稳定性。

10. 风控和熔断

    支持最大持仓数量、净敞口限制、单日亏损暂停、连续亏损暂停、最大回撤保护、极端行情过滤、保证金守护和人工控制等能力。

11. Web 仪表盘和通知

    项目包含本地 Web 仪表盘和飞书通知能力，用于查看运行状态、仓位、收益、告警和部分控制操作。

## 3. 适合谁使用

适合：

- 想研究资金费率套利策略的人。
- 想基于真实交易所 API 搭建量化交易原型的人。
- 想做回测、模拟盘、小资金灰度验证的人。
- 能理解交易风险、会看日志、能人工处理异常仓位的人。

不适合：

- 期望无脑运行、稳定赚钱的人。
- 不理解合约、杠杆、爆仓、资金费、手续费和滑点的人。
- 没有能力处理 API 异常、残腿、网络故障和交易所风控的人。
- 所在地区不允许使用相关交易所合约服务的人。

## 4. 项目目录中常见文件

- `README.md`：项目概览。
- `usage.md`：当前这份新手使用说明。
- `check.md`：项目评分和检查清单。
- `现货对冲功能说明.md`：现货对冲方案说明。
- `RISK_AND_OPS_GUIDE.md`：运维和风险控制说明。
- `MONETIZATION_GUIDE.md`：项目商业化和变现方向说明。
- `src/main/java/com/quant`：主程序源码。
- `src/test/java/com/quant`：单元测试。
- `data`：历史交易、回测或运行数据目录。
- `.env`：本地运行配置文件，通常不应提交到 Git。

## 5. 环境要求

建议环境：

- Java 8 或更高版本。
- Maven 3.6 或更高版本。
- 能访问目标交易所 API 的网络环境。
- Windows、Linux、macOS 均可运行。

常用命令：

```bash
mvn clean test
mvn clean package -DskipTests
java -jar target/funding-arbitrage-1.0.0.jar
```

Windows 下也可以使用项目中的启动脚本，具体以仓库当前文件为准。

## 6. 第一次如何运行

### 6.1 克隆和编译

```bash
git clone <your-repo-url>
cd funding-arbitrage
mvn clean test
mvn clean package -DskipTests
```

如果测试或编译失败，不要继续实盘。先修复失败原因。

### 6.2 准备 `.env`

在项目根目录创建 `.env` 文件。不要把真实 API Key 提交到仓库。

最小模拟运行示例：

```env
EXCHANGE=okx
SIMULATION_MODE=true
OKX_SIMULATED_TRADING=false

TRADING_SYMBOLS=BTCUSDT,ETHUSDT
EXCLUDED_SYMBOLS=SOLUSDT,XRPUSDT,DOGEUSDT

LEVERAGE=3
POSITION_VALUE_USDT=100
SPOT_HEDGE_ENABLED=false
```

说明：

- `SIMULATION_MODE=true`：项目内部模拟，不会真实下单。
- `OKX_SIMULATED_TRADING=true`：OKX 官方模拟盘，需要真实 OKX 模拟盘 API Key。
- `SIMULATION_MODE=false` 且 `OKX_SIMULATED_TRADING=false`：真实交易所实盘，下单会真实发生。

第一次接触项目时，必须先使用 `SIMULATION_MODE=true`。

### 6.3 启动程序

```bash
java -jar target/funding-arbitrage-1.0.0.jar
```

启动后重点观察：

- 是否成功连接交易所。
- 是否成功加载精度规则。
- 是否成功更新资金费率。
- 是否有行情快照缺失、盘口过宽、预测费率反向等告警。
- 是否出现异常下单、异常补偿或余额读取失败。

## 7. 主要配置说明

### 7.1 交易所配置

```env
EXCHANGE=okx
OKX_API_KEY=
OKX_SECRET_KEY=
OKX_PASSPHRASE=
OKX_SIMULATED_TRADING=false
```

也可以使用 Binance：

```env
EXCHANGE=binance
API_KEY=
SECRET_KEY=
```

注意：不同国家和地区对合约交易的合规要求不同。只有在你所在地区允许使用相关服务时，才可以配置实盘。

### 7.2 运行模式

```env
SIMULATION_MODE=true
```

建议顺序：

1. `SIMULATION_MODE=true`，本地模拟。
2. `SIMULATION_MODE=false` 且 `OKX_SIMULATED_TRADING=true`，交易所官方模拟盘。
3. `SIMULATION_MODE=false` 且 `OKX_SIMULATED_TRADING=false`，极小资金实盘灰度。
4. 连续稳定后，再逐步扩大资金。

### 7.3 交易币种

```env
TRADING_SYMBOLS=BTCUSDT,ETHUSDT
EXCLUDED_SYMBOLS=SOLUSDT,XRPUSDT,DOGEUSDT
```

新手建议只跑 BTC 和 ETH。不要一开始就跑高波动币种。

### 7.4 仓位和杠杆

```env
POSITION_VALUE_USDT=100
LEVERAGE=3
MAX_POSITIONS=2
```

`POSITION_VALUE_USDT` 是单个币种的名义仓位价值，不建议一开始设置过大。

### 7.5 开仓和平仓阈值

```env
MIN_FUNDING_RATE_POSITIVE=0.0010
MIN_FUNDING_RATE_NEGATIVE=0.0015
FUNDING_RATE_CLOSE_THRESHOLD=0.0002
CLOSE_FUNDING_RATE=0.0005
```

负费率做多通常价格风险更高，所以负费率阈值建议更保守。

### 7.6 现货对冲

```env
SPOT_HEDGE_ENABLED=true
SPOT_HEDGE_RATIO=1.0
SPOT_HEDGE_MAX_DEVIATION_RATIO=0.10
SPOT_HEDGE_STOP_LOSS_RATIO=0.03
```

现货对冲可以降低价格方向暴露，但会增加现货手续费、现货成交失败、残腿和资金划转风险。开启前必须确认：

- 现货余额充足。
- 现货 API 可以真实下单。
- 现货成交回报能正常读取。
- 合约腿和现货腿数量匹配。

## 8. 回测如何使用

项目支持基于 CSV 或交易所历史数据的回测。常见入口是 `FundingBacktestRunner`。

示例：

```bash
mvn clean package -DskipTests
java -cp target/funding-arbitrage-1.0.0.jar com.quant.FundingBacktestRunner csv <klineCsv> <fundingCsv>
java -cp target/funding-arbitrage-1.0.0.jar com.quant.FundingBacktestRunner optimize-csv <klineCsv> <fundingCsv>
java -cp target/funding-arbitrage-1.0.0.jar com.quant.FundingBacktestRunner binance BTCUSDT,ETHUSDT 2025-01-01T00:00:00 2026-01-01T00:00:00 8h
```

回测重点不要只看总收益，还要看：

- 最大回撤。
- 胜率。
- 手续费和滑点后的净收益。
- 资金费收益占比。
- 单币种表现。
- 滚动窗口表现。
- 是否存在“总体盈利，但多数窗口亏损”的假稳定性。

## 9. 实盘前必须完成的准备

在真实下单前，至少完成以下检查：

1. 代码编译和测试通过。

   ```bash
   mvn clean test
   mvn clean package -DskipTests
   ```

2. API Key 权限最小化。

   - 只开启读取和交易权限。
   - 不开启提现权限。
   - 尽量设置 IP 白名单。

3. 先跑官方模拟盘。

   对 OKX：

   ```env
   EXCHANGE=okx
   SIMULATION_MODE=false
   OKX_SIMULATED_TRADING=true
   ```

4. 小资金灰度。

   初始建议：

   ```env
   TRADING_SYMBOLS=BTCUSDT,ETHUSDT
   EXCLUDED_SYMBOLS=SOLUSDT,XRPUSDT,DOGEUSDT
   POSITION_VALUE_USDT=50
   LEVERAGE=2
   MAX_POSITIONS=1
   ```

5. 人工验证第一笔交易。

   检查：

   - 合约下单数量是否正确。
   - OKX 合约张数和币数量是否匹配。
   - 现货对冲数量是否匹配。
   - 手续费是否进入收益统计。
   - 平仓是否能顺利执行。
   - 本地记录和交易所账单是否一致。

6. 准备人工应急方案。

   你必须知道如何在交易所页面手动：

   - 查看仓位。
   - 平合约。
   - 卖出现货。
   - 撤销挂单。
   - 停止 API Key。
   - 暂停程序。

## 10. 推荐使用哪个平台

当前项目优先建议使用 OKX 做模拟盘和小资金灰度，因为最近的实盘关键修复主要围绕 OKX 完成：

- OKX 合约 `sz` 使用张数，项目已做币数量到张数转换。
- OKX 支持从 `public/instruments` 获取 `ctVal`、`lotSz`、`minSz`。
- OKX 支持 `nextFundingRate` 预测费率。
- OKX 现货余额和资金划转接口已接入。
- OKX 单向/双向持仓模式已自动适配。

如果你所在地区不允许使用 OKX 或 Binance Global 的合约服务，不要绕过限制上实盘。合规风险本身就是交易风险的一部分。

## 11. 常见风险和限制

1. 资金费套利不是无风险套利。

   价格波动可能远大于资金费收益。

2. 回测盈利不代表实盘盈利。

   回测可能低估滑点、盘口冲击、API 延迟、成交失败和交易所风控。

3. 现货对冲不是绝对安全。

   如果现货腿失败、部分成交、数量不匹配或平仓失败，仍可能产生裸露价格风险。

4. 极端行情下程序可能来不及处理。

   交易所接口延迟、网络故障、限频、维护、风控拒单都可能发生。

5. 配置错误可能直接导致亏损。

   例如仓位过大、杠杆过高、币种过多、关闭过滤器、API Key 权限过大等。

## 12. 新手推荐流程

推荐按下面顺序推进：

1. 阅读 `usage.md`、`check.md`、`现货对冲功能说明.md`、`RISK_AND_OPS_GUIDE.md`。
2. 使用 `SIMULATION_MODE=true` 跑通本地模拟。
3. 跑最近 6 到 12 个月 BTC/ETH 历史回测。
4. 做参数寻优和滚动窗口验证。
5. 使用 OKX 官方模拟盘跑至少 1 到 2 周。
6. 使用 50 到 100 USDT 名义仓位做极小资金实盘灰度。
7. 每天核对交易所账单、本地记录、资金费收入和手续费。
8. 稳定后再逐步扩大资金，但每次扩大都要重新观察。

## 13. 一句话总结

这个项目是一个资金费率套利交易系统，能做监控、过滤、开平仓、现货对冲、成本核算、回测和风控，但它不能保证盈利。正确使用方式是：先回测，再模拟盘，再小资金灰度，最后才考虑扩大规模。
