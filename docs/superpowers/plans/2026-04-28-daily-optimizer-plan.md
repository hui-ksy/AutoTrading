# 일일 파라미터 자동 최적화 — 구현 플랜

> **에이전트 작업자용:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (권장) 또는 superpowers:executing-plans 를 사용해 태스크 단위로 구현하세요. 각 단계는 체크박스(`- [ ]`) 형식으로 추적합니다.

**목표:** 매일 낮 12시(및 `/optimize` 명령 시 즉시), PEPE 15m 108-조합 백테스트 스윕을 실행해 상위 3개 제안을 Telegram으로 전송하고, 사용자가 선택한 번호에 따라 in-memory TradingConfig와 application.conf를 즉시 갱신한다.

**아키텍처:** `main.model`에 새 `OptimizationProposal` 레코드를 두어 BacktestRunner(데이터 생산자)와 DailyOptimizer(스케줄러/핸들러)를 연결한다. DailyOptimizer는 `AtomicReference`로 대기 중인 제안 상태를 관리하고, BitgetTradingBot의 기존 commandHandler 라우팅을 통해 Telegram 응답을 수신한다.

**기술 스택:** Java 21 record, `ScheduledExecutorService`, `AtomicReference`, `java.nio.file.Files`, 기존 `Backtester` + `BollingerBandReversionStrategy`.

---

## 파일 구성

| 작업 | 경로 | 역할 |
|------|------|------|
| **신규** | `src/main/java/main/model/OptimizationProposal.java` | 파라미터 + 성능 지표 공유 레코드 |
| **수정** | `src/main/java/main/job/TelegramNotifier.java` | `sendRawMessage()` 공개 메서드 추가 |
| **수정** | `src/main/java/main/backtest/BacktestRunner.java` | `runSweepForOptimizer()` 추가 |
| **신규** | `src/main/java/main/job/DailyOptimizer.java` | 스케줄러 + 제안 상태 + 적용 로직 |
| **수정** | `src/main/java/main/BitgetTradingBot.java` | DailyOptimizer 연결, `/optimize` 명령어 + 숫자 응답 라우팅 |

---

## 태스크 1: OptimizationProposal 레코드 추가

**파일:**
- 신규: `src/main/java/main/model/OptimizationProposal.java`

- [ ] **단계 1: 레코드 생성**

```java
package main.model;

public record OptimizationProposal(
    int rsiOS, int rsiOB,
    double slMult, double tpMult,
    double returnPct, double winRate, double mdd
) {}
```

- [ ] **단계 2: 컴파일 확인**

실행: `./gradlew compileJava`  
기대 결과: `BUILD SUCCESSFUL`

- [ ] **단계 3: 커밋**

```bash
git add src/main/java/main/model/OptimizationProposal.java
git commit -m "feat: add OptimizationProposal record for daily optimizer"
```

---

## 태스크 2: TelegramNotifier — sendRawMessage 추가

**파일:**
- 수정: `src/main/java/main/job/TelegramNotifier.java`

- [ ] **단계 1: public 메서드 추가**

`TelegramNotifier.java`에서 `notifyPeriodicStatus` 메서드와 `private void sendMessage` 사이에 아래를 삽입:

```java
public void sendRawMessage(String text) {
    sendMessage(text);
}
```

- [ ] **단계 2: 컴파일 확인**

실행: `./gradlew compileJava`  
기대 결과: `BUILD SUCCESSFUL`

- [ ] **단계 3: 커밋**

```bash
git add src/main/java/main/job/TelegramNotifier.java
git commit -m "feat: expose sendRawMessage in TelegramNotifier"
```

---

## 태스크 3: BacktestRunner — runSweepForOptimizer 추가

**파일:**
- 수정: `src/main/java/main/backtest/BacktestRunner.java`

- [ ] **단계 1: import 추가**

`BacktestRunner.java`의 import 블록에 (`import main.model.TradingConfig;` 아래에) 추가:

```java
import main.model.OptimizationProposal;
```

- [ ] **단계 2: 메서드 추가**

클래스 닫는 `}` 바로 위에 아래 메서드를 추가한다. 출력 없이 결과만 반환한다.

```java
/**
 * DailyOptimizer 전용 스윕: BB17/2.6σ 고정, RSI/SL/TP 108 조합, 출력 없이 결과 반환.
 *
 * @param symbol      예: "PEPEUSDT"
 * @param candleCount 15m 캔들 수 (30일 ≈ 2880)
 */
public static List<OptimizationProposal> runSweepForOptimizer(String symbol, int candleCount) {
    final String timeframe = "15m";
    final int    warmup    = 40;
    final int    leverage  = 10;
    final int    bbPeriod  = 17;
    final double bbStdDev  = 2.6;

    int[]    rsiOversolds   = {25, 30, 35, 40};
    int[]    rsiOverboughts = {60, 65, 70};
    double[] slMults        = {1.2, 1.5, 1.8};
    double[] tpMults        = {2.0, 2.5, 3.0};

    TradingConfig cfg = TradingConfig.getInstance();
    List<Candle> candles = fetchCandles(cfg, symbol, timeframe, candleCount);
    if (candles.isEmpty()) return List.of();

    double totalDays = (candles.get(candles.size() - 1).getTimestamp()
        - candles.get(warmup).getTimestamp()) / (1000.0 * 60 * 60 * 24);

    List<OptimizationProposal> results = new ArrayList<>();

    for (int rsiOS : rsiOversolds) {
        for (int rsiOB : rsiOverboughts) {
            for (double sl : slMults) {
                for (double tp : tpMults) {
                    TradingStrategy strategy = new BollingerBandReversionStrategy(
                        bbPeriod, bbStdDev, rsiOS, rsiOB, sl, tp);
                    Backtester bt = new Backtester(
                        strategy, candles, INIT_BAL, FEE_PCT, warmup, symbol, leverage);
                    BacktestResult r = bt.run();
                    results.add(new OptimizationProposal(
                        rsiOS, rsiOB, sl, tp,
                        r.getTotalReturnPct(), r.getWinRate(), r.getMaxDrawdownPct()));
                }
            }
        }
    }

    log.info("runSweepForOptimizer 완료: {} 조합, {}일",
        results.size(), String.format("%.1f", totalDays));
    return results;
}
```

- [ ] **단계 3: 컴파일 확인**

실행: `./gradlew compileJava`  
기대 결과: `BUILD SUCCESSFUL`

- [ ] **단계 4: 커밋**

```bash
git add src/main/java/main/backtest/BacktestRunner.java
git commit -m "feat: add runSweepForOptimizer to BacktestRunner (108-combo PEPE sweep, no output)"
```

---

## 태스크 4: DailyOptimizer 신규 생성

**파일:**
- 신규: `src/main/java/main/job/DailyOptimizer.java`

- [ ] **단계 1: 파일 생성**

```java
package main.job;

import lombok.extern.slf4j.Slf4j;
import main.backtest.BacktestRunner;
import main.model.OptimizationProposal;
import main.model.TradingConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class DailyOptimizer {

    private static final String SYMBOL       = "PEPEUSDT";
    private static final int    CANDLE_COUNT = 2_880; // 15m × 2880 ≈ 30일
    private static final int    TOP_N        = 3;

    private final TradingConfig    config;
    private final TelegramNotifier telegram;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "daily-optimizer");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<List<OptimizationProposal>> pendingProposals =
        new AtomicReference<>(null);

    public DailyOptimizer(TradingConfig config, TelegramNotifier telegram) {
        this.config   = config;
        this.telegram = telegram;
    }

    /** 다음 낮 12시부터 24시간 주기로 스윕 스케줄 등록 */
    public void start() {
        LocalDateTime now  = LocalDateTime.now();
        LocalDateTime noon = now.toLocalDate().atTime(12, 0);
        if (!now.isBefore(noon)) noon = noon.plusDays(1);
        long initialDelay = Duration.between(now, noon).getSeconds();
        scheduler.scheduleAtFixedRate(this::runAndPropose, initialDelay, 86_400, TimeUnit.SECONDS);
        log.info("DailyOptimizer 등록 완료 — 다음 실행: {}", noon);
    }

    /** /optimize 명령어: 즉시 스윕 실행 (non-blocking) */
    public void triggerNow() {
        scheduler.submit(this::runAndPropose);
    }

    private void runAndPropose() {
        log.info("DailyOptimizer: {} 스윕 시작", SYMBOL);
        try {
            List<OptimizationProposal> top = BacktestRunner.runSweepForOptimizer(SYMBOL, CANDLE_COUNT)
                .stream()
                .filter(p -> p.returnPct() > 0)
                .sorted((a, b) -> Double.compare(b.returnPct(), a.returnPct()))
                .limit(TOP_N)
                .toList();

            if (top.isEmpty()) {
                telegram.sendRawMessage("📊 일일 최적화: 수익 양수 조합 없음. 현재 설정 유지.");
                return;
            }
            pendingProposals.set(top);
            telegram.sendRawMessage(buildProposalMessage(top));
        } catch (Exception e) {
            log.error("DailyOptimizer 스윕 실패", e);
            telegram.sendRawMessage("⚠️ 일일 최적화 스윕 실패: " + e.getMessage());
        }
    }

    /**
     * Telegram 응답 "0"/"1"/"2"/"3" 처리.
     *
     * @return 응답 메시지(처리됨), null(미처리 — 기존 commandHandler에 위임)
     */
    public String handleReply(String text) {
        List<OptimizationProposal> proposals = pendingProposals.get();
        if (proposals == null) return null;

        String trimmed = text.trim();
        if (!trimmed.matches("[0-3]")) return null;

        pendingProposals.set(null);

        if ("0".equals(trimmed)) {
            return "✅ 현재 설정을 유지합니다.";
        }

        int idx = Integer.parseInt(trimmed) - 1;
        if (idx >= proposals.size()) {
            return "❌ 잘못된 번호입니다.";
        }

        OptimizationProposal chosen = proposals.get(idx);
        applyProposal(chosen);
        return String.format(
            "✅ 설정 적용 완료\nrsiOS=%d / rsiOB=%d / SL=%.1f / TP=%.1f",
            chosen.rsiOS(), chosen.rsiOB(), chosen.slMult(), chosen.tpMult());
    }

    private void applyProposal(OptimizationProposal p) {
        config.setBollingerBandsRsiOversold(p.rsiOS());
        config.setBollingerBandsRsiOverbought(p.rsiOB());
        config.setAtrSlMultiplier(p.slMult());
        config.setAtrTpMultiplier(p.tpMult());
        updateConfigFile(p);
        log.info("파라미터 적용: rsiOS={} rsiOB={} SL={} TP={}",
            p.rsiOS(), p.rsiOB(), p.slMult(), p.tpMult());
    }

    private void updateConfigFile(OptimizationProposal p) {
        Path path = Path.of("src/main/resources/application.conf");
        try {
            String content = Files.readString(path);
            content = content.replaceAll("rsiOversold\\s*=\\s*\\d+",
                "rsiOversold = " + p.rsiOS());
            content = content.replaceAll("rsiOverbought\\s*=\\s*\\d+",
                "rsiOverbought = " + p.rsiOB());
            content = content.replaceAll("atrSlMultiplier\\s*=\\s*[\\d.]+",
                "atrSlMultiplier = " + p.slMult());
            content = content.replaceAll("atrTpMultiplier\\s*=\\s*[\\d.]+",
                "atrTpMultiplier = " + p.tpMult());
            Files.writeString(path, content);
            log.info("application.conf 업데이트 완료");
        } catch (IOException e) {
            log.error("application.conf 업데이트 실패", e);
        }
    }

    private String buildProposalMessage(List<OptimizationProposal> proposals) {
        LocalDate today = LocalDate.now();
        LocalDate from  = today.minusDays(30);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "📊 <b>일일 최적화 리포트</b> (PEPEUSDT 15m, 10x)\n기간: %s ~ %s\n\n",
            from.toString().substring(5), today.toString().substring(5)));
        sb.append(String.format(
            "현재 설정: rsiOS=%d, rsiOB=%d, SL=%.1f×, TP=%.1f×\n\n",
            (int) config.getBollingerBandsRsiOversold(),
            (int) config.getBollingerBandsRsiOverbought(),
            config.getAtrSlMultiplier(),
            config.getAtrTpMultiplier()));

        String[] nums = {"1️⃣", "2️⃣", "3️⃣"};
        for (int i = 0; i < proposals.size(); i++) {
            OptimizationProposal p = proposals.get(i);
            sb.append(String.format(
                "%s rsiOS=%d / rsiOB=%d / SL=%.1f / TP=%.1f → +%.0f%%, WR %.0f%%, MDD %.0f%%\n",
                nums[i], p.rsiOS(), p.rsiOB(), p.slMult(), p.tpMult(),
                p.returnPct(), p.winRate(), p.mdd()));
        }
        sb.append("0️⃣ 현재 유지\n\n번호를 입력해 주세요.");
        return sb.toString();
    }
}
```

- [ ] **단계 2: 컴파일 확인**

실행: `./gradlew compileJava`  
기대 결과: `BUILD SUCCESSFUL`

- [ ] **단계 3: 커밋**

```bash
git add src/main/java/main/job/DailyOptimizer.java
git commit -m "feat: add DailyOptimizer — 매일 낮 12시 스윕, Telegram 제안, 번호 응답 시 적용"
```

---

## 태스크 5: BitgetTradingBot에 DailyOptimizer 연결

**파일:**
- 수정: `src/main/java/main/BitgetTradingBot.java`

- [ ] **단계 1: import 추가**

`BitgetTradingBot.java` import 블록에 추가:

```java
import main.job.DailyOptimizer;
```

- [ ] **단계 2: static 필드 추가**

기존 static 필드들(`List<AutoTrader> autoTraders` 등) 아래에 추가:

```java
private static DailyOptimizer dailyOptimizer;
```

- [ ] **단계 3: DailyOptimizer 인스턴스 생성**

`main()` 안에서 `telegram.sendStartupMessage();` 직후에 추가:

```java
dailyOptimizer = new DailyOptimizer(config, telegram);
```

- [ ] **단계 4: commandHandler에 /optimize 및 숫자 응답 추가**

commandHandler 람다 안에서 마지막 `return "알 수 없는 명령어입니다..."` 바로 위에 추가:

```java
if (command.equals("/optimize")) {
    dailyOptimizer.triggerNow();
    return "🔍 PEPE 30일 스윕 시작 중... 약 2분 후 제안을 전송합니다.";
}

String optimizerReply = dailyOptimizer.handleReply(command);
if (optimizerReply != null) return optimizerReply;
```

- [ ] **단계 5: 도움말 메시지에 /optimize 추가**

`getHelpMessage()` 안에서 `"/help - 명령어 목록 표시"` 바로 앞에 추가:

```java
"/optimize - 지금 파라미터 최적화 실행 (PEPE 30일 스윕)\n" +
```

- [ ] **단계 6: DailyOptimizer 스케줄 시작**

`main()` 안에서 `startSummaryLoggingThread(...)` 호출 직후에 추가:

```java
dailyOptimizer.start();
```

- [ ] **단계 7: 컴파일 확인**

실행: `./gradlew compileJava`  
기대 결과: `BUILD SUCCESSFUL`

- [ ] **단계 8: 커밋**

```bash
git add src/main/java/main/BitgetTradingBot.java
git commit -m "feat: wire DailyOptimizer — /optimize 명령어 + 숫자 응답 라우팅"
```

---

## 태스크 6: 동작 검증

- [ ] **단계 1: 봇 실행**

실행: `./gradlew run`  
기대 로그: `DailyOptimizer 등록 완료 — 다음 실행: <내일 낮 12시>`

- [ ] **단계 2: Telegram에서 /optimize 전송**

텔레그램에서 `/optimize` 전송.  
즉시 응답: `🔍 PEPE 30일 스윕 시작 중... 약 2분 후 제안을 전송합니다.`  
약 2분 후: 1️⃣/2️⃣/3️⃣ + 0️⃣ 번호 선택 메시지 수신 확인.

- [ ] **단계 3: "1" 응답으로 적용 확인**

텔레그램에서 `1` 전송.  
기대 응답: `✅ 설정 적용 완료\nrsiOS=... / rsiOB=... / SL=... / TP=...`  
`src/main/resources/application.conf` 에서 해당 값이 변경됐는지 직접 확인.

- [ ] **단계 4: 수정 사항이 있으면 최종 커밋**

```bash
git add -p
git commit -m "fix: <수정 내용>"
```