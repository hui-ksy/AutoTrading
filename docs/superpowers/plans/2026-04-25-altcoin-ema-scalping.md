# AltcoinEmaScalping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 고레버리지(25x) 알트코인 1m EMA 크로스 스캘핑 전략 구현 및 백테스트

**Architecture:** `AltcoinEmaScalpingStrategy`를 신규 작성하여 EMA5/EMA20 크로스 + RSI50 필터로 진입하고, 역크로스 발생 시 EXIT 시그널을 반환한다. SL/TP는 ATR 배수 기반. `StrategyType` enum과 `StrategyFactory`에 등록 후 `BacktestRunner`에 멀티심볼 파라미터 스윕 메서드를 추가한다.

**Tech Stack:** Java 21, Typesafe Config, Gradle, Bitget API (OkHttp)

---

## File Map

| 파일 | 작업 |
|------|------|
| `src/main/java/main/model/StrategyType.java` | `ALTCOIN_EMA_SCALPING` 추가 |
| `src/main/java/main/strategy/AltcoinEmaScalpingStrategy.java` | **신규 생성** — EMA 크로스 + RSI 진입, 역크로스 EXIT |
| `src/main/java/main/strategy/StrategyFactory.java` | 새 케이스 등록 |
| `src/main/java/main/backtest/BacktestRunner.java` | `runAltcoinEmaSweep()` 추가, `main()` 변경 |
| `src/main/resources/application.conf` | pairs, timeframe, leverage, strategy 변경 |

---

## Task 1: StrategyType 열거형에 ALTCOIN_EMA_SCALPING 추가

**Files:**
- Modify: `src/main/java/main/model/StrategyType.java`

- [ ] **Step 1: 파일 열기 및 확인**

현재 내용:
```java
public enum StrategyType {
    BOLLINGER_BANDS_SCALPING,
    DONCHIAN_BREAKOUT,
    DOWNTREND_RSI,
    EMA_STOCHASTIC_SCALPING,
    TRIPLE_EMA,
    ELLIOTT_WAVE,
    ORDER_BLOCK,
    MACD_ATR_TREND,
    HEIKIN_ASHI_ICHIMOKU,
    SUPER_TREND,
    DYNAMIC_EXIT_SCALPING,
    BOLLINGER_BAND_REVERSION // BB 평균회귀 (현재 활성)
}
```

- [ ] **Step 2: ALTCOIN_EMA_SCALPING 추가**

`BOLLINGER_BAND_REVERSION` 뒤에 한 줄 추가:
```java
    BOLLINGER_BAND_REVERSION, // BB 평균회귀
    ALTCOIN_EMA_SCALPING      // 알트코인 EMA 크로스 스캘핑 (현재 활성)
```

최종 파일:
```java
package main.model;

public enum StrategyType {
    BOLLINGER_BANDS_SCALPING,
    DONCHIAN_BREAKOUT,
    DOWNTREND_RSI,
    EMA_STOCHASTIC_SCALPING,
    TRIPLE_EMA,
    ELLIOTT_WAVE,
    ORDER_BLOCK,
    MACD_ATR_TREND,
    HEIKIN_ASHI_ICHIMOKU,
    SUPER_TREND,
    DYNAMIC_EXIT_SCALPING,
    BOLLINGER_BAND_REVERSION,
    ALTCOIN_EMA_SCALPING
}
```

- [ ] **Step 3: 빌드 확인**

```bash
./gradlew classes -q
```
Expected: BUILD SUCCESSFUL (컴파일 에러 없음)

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/main/model/StrategyType.java
git commit -m "feat: add ALTCOIN_EMA_SCALPING to StrategyType"
```

---

## Task 2: AltcoinEmaScalpingStrategy 신규 작성

**Files:**
- Create: `src/main/java/main/strategy/AltcoinEmaScalpingStrategy.java`

**핵심 로직:**
- 포지션 없음 → EMA 크로스 + RSI 필터로 진입 신호 생성
- 포지션 있음 → EMA 역크로스 감지 시 EXIT 반환, 아니면 HOLD
- SL = close ± ATR × atrSlMult, TP = close ± ATR × atrTpMult

- [ ] **Step 1: 파일 생성**

```java
package main.strategy;

import main.indicator.TechnicalIndicators;
import main.model.Candle;
import main.model.Position;
import main.model.Signal;
import main.model.TradingConfig;

import java.util.List;

public class AltcoinEmaScalpingStrategy implements TradingStrategy {

    private final int    fastEmaPeriod;
    private final int    slowEmaPeriod;
    private final int    rsiPeriod;
    private final double atrSlMult;
    private final double atrTpMult;
    private static final int ATR_PERIOD = 14;

    /** application.conf 기반 실거래/페이퍼 트레이딩용 생성자
     *  emaTrend.emaShortPeriod → fastEma (기본 5)
     *  emaTrend.emaMediumPeriod → slowEma (기본 20)
     *  risk.atrSlMultiplier, risk.atrTpMultiplier
     */
    public AltcoinEmaScalpingStrategy(TradingConfig config) {
        this.fastEmaPeriod = config.getEmaShortPeriod()  > 0 ? config.getEmaShortPeriod()  : 5;
        this.slowEmaPeriod = config.getEmaMediumPeriod() > 0 ? config.getEmaMediumPeriod() : 20;
        this.rsiPeriod     = 14;
        this.atrSlMult     = config.getAtrSlMultiplier() > 0 ? config.getAtrSlMultiplier() : 0.8;
        this.atrTpMult     = config.getAtrTpMultiplier() > 0 ? config.getAtrTpMultiplier() : 2.5;
    }

    /** 파라미터 스윕용 생성자 */
    public AltcoinEmaScalpingStrategy(int fastEmaPeriod, int slowEmaPeriod,
                                      double atrSlMult, double atrTpMult) {
        this.fastEmaPeriod = fastEmaPeriod;
        this.slowEmaPeriod = slowEmaPeriod;
        this.rsiPeriod     = 14;
        this.atrSlMult     = atrSlMult;
        this.atrTpMult     = atrTpMult;
    }

    @Override
    public Signal generateSignal(List<Candle> candles, String pair, Position position) {
        int minNeeded = slowEmaPeriod + rsiPeriod + 5;
        if (candles.size() < minNeeded) return hold("데이터 부족");

        List<Double> fastEmaList = TechnicalIndicators.calculateEMA(candles, fastEmaPeriod);
        List<Double> slowEmaList = TechnicalIndicators.calculateEMA(candles, slowEmaPeriod);
        if (fastEmaList.size() < 2 || slowEmaList.size() < 2) return hold("EMA 계산 불가");

        double fastNow  = fastEmaList.get(fastEmaList.size() - 1);
        double fastPrev = fastEmaList.get(fastEmaList.size() - 2);
        double slowNow  = slowEmaList.get(slowEmaList.size() - 1);
        double slowPrev = slowEmaList.get(slowEmaList.size() - 2);

        List<Double> rsiList = TechnicalIndicators.calculateRSI(candles, rsiPeriod);
        if (rsiList.isEmpty()) return hold("RSI 계산 불가");
        double rsi   = rsiList.get(rsiList.size() - 1);
        double close = candles.get(candles.size() - 1).getClose();
        double atr   = TechnicalIndicators.calculateATR(candles, ATR_PERIOD);

        boolean bullishCross = fastPrev <= slowPrev && fastNow > slowNow;
        boolean bearishCross = fastPrev >= slowPrev && fastNow < slowNow;

        // ── 포지션 보유 중: 역크로스 시 EXIT ─────────────────────────
        if (position != null) {
            boolean isLong = "BUY".equals(position.getSide());
            if (isLong && bearishCross) {
                return Signal.builder()
                    .action(Signal.Action.EXIT)
                    .reason(String.format("EMA 역크로스 → Long 청산 | EMA%d=%.4f < EMA%d=%.4f | RSI=%.1f",
                        fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi))
                    .build();
            }
            if (!isLong && bullishCross) {
                return Signal.builder()
                    .action(Signal.Action.EXIT)
                    .reason(String.format("EMA 역크로스 → Short 청산 | EMA%d=%.4f > EMA%d=%.4f | RSI=%.1f",
                        fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi))
                    .build();
            }
            return hold(String.format("포지션 유지 | EMA%d=%.4f EMA%d=%.4f | RSI=%.1f",
                fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi));
        }

        // ── 롱 진입: 상향 크로스 + RSI > 50 ──────────────────────────
        if (bullishCross && rsi > 50) {
            double sl = close - atr * atrSlMult;
            double tp = close + atr * atrTpMult;
            return Signal.builder()
                .action(Signal.Action.BUY)
                .entryPrice(close)
                .stopLoss(sl)
                .takeProfit(tp)
                .reason(String.format("EMA Long | EMA%d=%.4f > EMA%d=%.4f | RSI=%.1f | ATR=%.4f",
                    fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi, atr))
                .build();
        }

        // ── 숏 진입: 하향 크로스 + RSI < 50 ──────────────────────────
        if (bearishCross && rsi < 50) {
            double sl = close + atr * atrSlMult;
            double tp = close - atr * atrTpMult;
            return Signal.builder()
                .action(Signal.Action.SHORT)
                .entryPrice(close)
                .stopLoss(sl)
                .takeProfit(tp)
                .reason(String.format("EMA Short | EMA%d=%.4f < EMA%d=%.4f | RSI=%.1f | ATR=%.4f",
                    fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi, atr))
                .build();
        }

        return hold(String.format("조건 미충족 | EMA%d=%.4f EMA%d=%.4f | RSI=%.1f",
            fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi));
    }

    private Signal hold(String reason) {
        return Signal.builder().action(Signal.Action.HOLD).reason(reason).build();
    }
}
```

- [ ] **Step 2: 빌드 확인**

```bash
./gradlew classes -q
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/main/strategy/AltcoinEmaScalpingStrategy.java
git commit -m "feat: add AltcoinEmaScalpingStrategy with EMA cross + RSI entry and reversal exit"
```

---

## Task 3: StrategyFactory에 새 전략 등록

**Files:**
- Modify: `src/main/java/main/strategy/StrategyFactory.java`

- [ ] **Step 1: switch 블록에 케이스 추가**

`BOLLINGER_BAND_REVERSION` 케이스 아래, `default:` 위에 추가:
```java
            case ALTCOIN_EMA_SCALPING:
                return new AltcoinEmaScalpingStrategy(config);
```

최종 switch 블록:
```java
        switch (type) {
            case BOLLINGER_BANDS_SCALPING:
                return new BollingerBandsScalpingStrategy(config);
            case DONCHIAN_BREAKOUT:
                return new DonchianBreakoutStrategy(config);
            case DOWNTREND_RSI:
                return new DowntrendRsiStrategy(config);
            case EMA_STOCHASTIC_SCALPING:
                return new EmaStochasticScalpingStrategy(config);
            case TRIPLE_EMA:
                return new TripleEMAStrategy(config);
            case ELLIOTT_WAVE:
                return new ElliottWaveStrategy(config);
            case ORDER_BLOCK:
                return new OrderBlockStrategy(config);
            case MACD_ATR_TREND:
                return new MacdAtrTrendStrategy(config);
            case HEIKIN_ASHI_ICHIMOKU:
                return new HeikinAshiIchimokuStrategy(config);
            case SUPER_TREND:
                return new SuperTrendStrategy(config);
            case DYNAMIC_EXIT_SCALPING:
                return new DynamicExitScalpingStrategy(config);
            case BOLLINGER_BAND_REVERSION:
                return new BollingerBandReversionStrategy(config);
            case ALTCOIN_EMA_SCALPING:
                return new AltcoinEmaScalpingStrategy(config);
            default:
                throw new IllegalArgumentException("Unknown strategy type: " + type);
        }
```

- [ ] **Step 2: 빌드 확인**

```bash
./gradlew classes -q
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/main/strategy/StrategyFactory.java
git commit -m "feat: register AltcoinEmaScalpingStrategy in StrategyFactory"
```

---

## Task 4: BacktestRunner에 runAltcoinEmaSweep() 추가

**Files:**
- Modify: `src/main/java/main/backtest/BacktestRunner.java`

새 메서드와 결과 컨테이너 클래스를 파일 끝 `formatTs()` 메서드 바로 위에 삽입한다.

- [ ] **Step 1: main() 메서드를 runAltcoinEmaSweep() 호출로 변경**

현재:
```java
    public static void main(String[] args) {
        runRecentDayCheck();
    }
```

변경 후:
```java
    public static void main(String[] args) {
        runAltcoinEmaSweep();
    }
```

- [ ] **Step 2: 파일 끝에 멀티심볼 스윕 메서드 추가**

`formatTs()` 메서드 바로 위에 아래 코드를 삽입한다:

```java
    // ── 알트코인 EMA 스캘핑 멀티심볼 파라미터 스윕 (1m, 7일) ───────────────
    public static void runAltcoinEmaSweep() {
        final String   timeframe  = "1m";
        final int      candleCnt  = 10_080;  // 1m × 10080 = 7일
        final int      warmup     = 35;
        final int      leverage   = 25;
        final String[] symbols    = {"XRPUSDT", "DOGEUSDT", "PEPEUSDT"};

        int[]    fastEmas = {3, 5, 7};
        double[] slMults  = {0.5, 0.8, 1.0, 1.2};
        double[] tpMults  = {1.5, 2.0, 2.5, 3.0};
        int[]    slowEmas = {10, 15, 20};

        log.info("===== 알트코인 EMA 스캘핑 멀티심볼 스윕 시작 =====");

        TradingConfig config = TradingConfig.getInstance();

        for (String symbol : symbols) {
            List<Candle> candles = fetchCandles(config, symbol, timeframe, candleCnt);
            if (candles.isEmpty()) {
                log.warn("캔들 수집 실패: {}", symbol);
                continue;
            }

            double totalDays  = (candles.get(candles.size() - 1).getTimestamp()
                - candles.get(warmup).getTimestamp()) / (1000.0 * 60 * 60 * 24);
            double totalHours = totalDays * 24;

            log.info("[{}] 캔들 {}개, {}일 ({}시간)", symbol, candles.size(),
                String.format("%.1f", totalDays), String.format("%.0f", totalHours));

            List<AltcoinEmaSweepResult> results = new ArrayList<>();
            int total = fastEmas.length * slowEmas.length * slMults.length * tpMults.length;
            int count = 0;

            for (int fast : fastEmas) {
                for (int slow : slowEmas) {
                    if (fast >= slow) continue;
                    for (double sl : slMults) {
                        for (double tp : tpMults) {
                            if (tp <= sl) continue;

                            TradingStrategy strategy =
                                new main.strategy.AltcoinEmaScalpingStrategy(fast, slow, sl, tp);
                            Backtester bt = new Backtester(
                                strategy, candles, INIT_BAL, FEE_PCT, warmup, symbol, leverage);
                            BacktestResult r = bt.run();

                            double tradesPerDay  = totalDays  > 0 ? r.getTotalTrades() / totalDays  : 0;
                            double tradesPerHour = totalHours > 0 ? r.getTotalTrades() / totalHours : 0;
                            results.add(new AltcoinEmaSweepResult(
                                fast, slow, sl, tp, r, tradesPerDay, tradesPerHour));

                            count++;
                            if (count % 20 == 0) log.info("[{}] 스윕 진행: {}/{}", symbol, count, total);
                        }
                    }
                }
            }

            printAltcoinEmaSweepResults(symbol, results, totalDays, totalHours);
        }

        log.info("===== 알트코인 EMA 스캘핑 스윕 종료 =====");
    }

    private static void printAltcoinEmaSweepResults(String symbol,
                                                     List<AltcoinEmaSweepResult> results,
                                                     double totalDays, double totalHours) {
        System.out.printf("%n══════════════════════════════════════════════════════════════════%n");
        System.out.printf("  [%s] EMA 스캘핑 스윕 결과 (Top 20 — 수익률 기준, 레버리지 25x)%n", symbol);
        System.out.printf("══════════════════════════════════════════════════════════════════%n");
        System.out.printf("%-6s %-6s %-5s %-5s │ %5s %6s %7s %7s %7s %6s %6s%n",
            "fast", "slow", "SL×", "TP×",
            "거래수", "승률%", "건/시간", "건/일", "수익률%", "MDD%", "Sharpe");
        System.out.println("──────────────────────────────────────────────────────────────────");

        results.stream()
            .filter(r -> r.result.getTotalTrades() >= 5)
            .sorted(Comparator.comparingDouble((AltcoinEmaSweepResult r) ->
                r.result.getTotalReturnPct()).reversed())
            .limit(20)
            .forEach(r -> {
                BacktestResult br = r.result;
                String flag = r.tradesPerHour >= 1.0 && br.getTotalReturnPct() > 0 ? " ★" : "";
                System.out.printf("%-6d %-6d %-5.1f %-5.1f │ %5d %6.1f %7.2f %7.2f %7.2f %6.2f %6.2f%s%n",
                    r.fastEma, r.slowEma, r.sl, r.tp,
                    br.getTotalTrades(), br.getWinRate(),
                    r.tradesPerHour, r.tradesPerDay,
                    br.getTotalReturnPct(), br.getMaxDrawdownPct(), br.getSharpeRatio(),
                    flag);
            });

        System.out.println("──────────────────────────────────────────────────────────────────");
        System.out.println("★ = 시간당 1건 이상 AND 플러스 수익");

        long hitTarget = results.stream()
            .filter(r -> r.tradesPerHour >= 1.0 && r.result.getTotalReturnPct() > 0)
            .count();
        System.out.printf("목표(시간당 1건+ AND 플러스 수익) 달성 조합: %d개 / 전체 %d개%n",
            hitTarget, results.size());
        System.out.printf("기간: %.1f일 (%.0f시간)  초기잔고: $%.0f  레버리지: 25x%n%n",
            totalDays, totalHours, INIT_BAL);
    }

    private static class AltcoinEmaSweepResult {
        final int fastEma, slowEma;
        final double sl, tp;
        final BacktestResult result;
        final double tradesPerDay, tradesPerHour;

        AltcoinEmaSweepResult(int fastEma, int slowEma, double sl, double tp,
                              BacktestResult result, double tradesPerDay, double tradesPerHour) {
            this.fastEma = fastEma;
            this.slowEma = slowEma;
            this.sl = sl;
            this.tp = tp;
            this.result = result;
            this.tradesPerDay = tradesPerDay;
            this.tradesPerHour = tradesPerHour;
        }
    }
```

- [ ] **Step 3: 빌드 확인**

```bash
./gradlew classes -q
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/main/backtest/BacktestRunner.java
git commit -m "feat: add runAltcoinEmaSweep() to BacktestRunner for XRP/DOGE/PEPE 1m sweep"
```

---

## Task 5: application.conf 업데이트

**Files:**
- Modify: `src/main/resources/application.conf`

- [ ] **Step 1: trading 블록 변경**

현재 설정:
```hocon
trading {
  mode = "PAPER"
  type = "FUTURES"
  strategy = "BOLLINGER_BAND_REVERSION"
  pair = "BTCUSDT"
  pairs = ["BTCUSDT"]
  timeframe = "5m"
  ...
  futures {
    leverage = 10
    ...
  }
}
```

변경 후:
```hocon
trading {
  mode = "PAPER"
  type = "FUTURES"

  strategy = "ALTCOIN_EMA_SCALPING"

  pair = "XRPUSDT"
  pairs = ["XRPUSDT", "DOGEUSDT", "PEPEUSDT"]

  timeframe = "1m"

  candleLimit = 100
  candleIntervalSeconds = 15
  tickerIntervalSeconds = 1
  positionStatusMinutes = 5

  allowLong = true
  allowShort = true

  futures {
    leverage = 25
    marginMode = "isolated"
    productType = "USDT-FUTURES"
  }

  paper {
    initialBalance = 10000.0
  }
}
```

- [ ] **Step 2: risk 블록 변경**

현재:
```hocon
risk {
  stopLossPercent = 0.15
  takeProfitPercent = 0.3
  atrPeriod = 14
  atrSlMultiplier = 1.0
  atrTpMultiplier = 2.5
  ...
}
```

변경 후 (atrSlMultiplier, atrTpMultiplier만 수정):
```hocon
risk {
  stopLossPercent = 0.15
  takeProfitPercent = 0.3
  atrPeriod = 14
  atrSlMultiplier = 0.8
  atrTpMultiplier = 2.5
  maxPositionSize = 10000
  dailyMaxLossPercent = 5.0
  orderPercentOfBalance = 100.0
  maxOpenPositions = 5
  orderSizingStrategy = "PERCENT_OF_EQUITY"
  fixedOrderAmount = 200.0
}
```

- [ ] **Step 3: emaTrend 블록 확인**

`emaTrend.emaShortPeriod = 5`, `emaTrend.emaMediumPeriod = 20` 이 이미 설정되어 있음을 확인한다 (AltcoinEmaScalpingStrategy의 TradingConfig 생성자가 이 값을 사용).

현재 application.conf의 emaTrend 블록:
```hocon
emaTrend { emaShortPeriod = 20, emaMediumPeriod = 50, emaLongPeriod = 200, ... }
```

→ fast=5, slow=20이 백테스트에서 좋은 결과를 보이면 아래로 수정:
```hocon
emaTrend { emaShortPeriod = 5, emaMediumPeriod = 20, emaLongPeriod = 200, adxPeriod = 14, adxThreshold = 25.0, stochK = 14, stochD = 3, stochSmooth = 3 }
```

*주의: 백테스트 스윕 결과를 보고 최적 파라미터로 맞출 것. 스윕 전에는 일단 5/20으로 설정.*

- [ ] **Step 4: 빌드 + 설정 로드 확인**

```bash
./gradlew classes -q
```
Expected: BUILD SUCCESSFUL (설정 오타 시 런타임 에러 발생하므로 주의)

- [ ] **Step 5: 커밋**

```bash
git add src/main/resources/application.conf
git commit -m "config: switch to ALTCOIN_EMA_SCALPING, pairs=[XRP/DOGE/PEPE], 1m, 25x leverage"
```

---

## Task 6: 백테스트 실행 및 결과 확인

- [ ] **Step 1: 백테스트 실행**

```bash
./gradlew backtest
```

Expected: 3개 심볼(XRPUSDT, DOGEUSDT, PEPEUSDT) 각각 Top 20 결과 출력.
목표: 시간당 1건 이상 + 플러스 수익 조합이 존재할 것.

- [ ] **Step 2: 최적 파라미터 확인**

출력 결과에서 ★ 표시 조합을 확인한다. 각 심볼별로 공통으로 좋은 fast/slow EMA, SL×, TP× 조합을 선택한다.

기준:
- 수익률 > 0
- Sharpe > 0.5
- MDD% < 5%
- 시간당 1건 이상

- [ ] **Step 3: application.conf 파라미터 반영**

백테스트 최적값이 기본값(fast=5, slow=20)과 다르면 `emaTrend.emaShortPeriod` / `emaTrend.emaMediumPeriod`를 업데이트하고, `risk.atrSlMultiplier` / `risk.atrTpMultiplier`도 조정한다.

- [ ] **Step 4: CLAUDE.md 업데이트**

CLAUDE.md의 "현재 활성 전략" 섹션을 새 전략 정보로 교체한다.

---

## 성공 기준 체크리스트

- [ ] `./gradlew classes -q` → BUILD SUCCESSFUL
- [ ] `./gradlew backtest` → 3개 심볼 스윕 결과 출력
- [ ] 최소 1개 심볼에서 Sharpe > 0.5, 수익률 > 0, 시간당 1건+ 달성
- [ ] `application.conf` strategy = "ALTCOIN_EMA_SCALPING" 반영
- [ ] PAPER 모드로 `./gradlew run` 정상 기동 확인

## 실패 시 대안

백테스트 결과가 모든 심볼에서 기준 미달이면:
1. SL 범위를 0.3~0.6으로 더 타이트하게 재스윕
2. 또는 볼륨 스파이크 + 가격 돌파 전략으로 전환 (별도 설계)
