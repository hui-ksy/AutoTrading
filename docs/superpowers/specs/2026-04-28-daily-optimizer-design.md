# Daily Parameter Auto-Optimization — Design Spec

**Date:** 2026-04-28  
**Feature:** 매일 낮 12시 백테스트 스윕 → Telegram 제안 → 사용자 선택 → config 자동 적용

---

## Goal

현재 파라미터가 시장 변화에 뒤처지지 않도록 매일 최근 30일 데이터로 소규모 스윕을 실행하고, 상위 3개 후보를 Telegram으로 전송한다. 사용자가 번호(1/2/3/0)로 응답하면 해당 파라미터를 즉시 적용한다.

---

## Architecture

### New class: `DailyOptimizer`

`src/main/java/main/job/DailyOptimizer.java`

- `ScheduledExecutorService` 1개 — 매일 낮 12시 실행
- `AtomicReference<List<ProposalEntry>>` — 대기 중인 제안 상태 보관
- `start()` — 다음 낮 12시까지 초기 딜레이 계산 후 24시간 주기 스케줄
- `runAndPropose()` — 스윕 실행 → 상위 3개 선별 → Telegram 전송 → 대기 상태
- `handleReply(String)` — "0"/"1"/"2"/"3" 처리
- `applyProposal(ProposalEntry)` — TradingConfig 메모리 업데이트 + application.conf 파일 업데이트

### Inner record: `ProposalEntry`

```java
record ProposalEntry(int rsiOS, int rsiOB, double slMult, double tpMult,
                     double returnPct, double winRate, double mdd) {}
```

---

## Sweep Scope

**고정:** BB period=17, stdDev=2.6σ, symbol=PEPEUSDT, timeframe=15m, leverage=10x, 기간=최근 30일  
**가변:**

| 파라미터 | 후보 |
|---------|------|
| rsiOS   | 25, 30, 35, 40 |
| rsiOB   | 60, 65, 70 |
| SL(ATR×)| 1.2, 1.5, 1.8 |
| TP(ATR×)| 2.0, 2.5, 3.0 |

총 4×3×3×3 = **108 combos**, 예상 소요 ~2분

DOGE/XRP는 BB17/2.6σ에서 수익이 없는 것이 스윕으로 확인됐으므로 최적화 대상에서 제외.

---

## Telegram Flow

### 발송 (낮 12시)

```
📊 일일 최적화 리포트 (PEPEUSDT 15m, 10x)
기간: MM-DD ~ MM-DD

현재 설정: rsiOS=30, rsiOB=70, SL=1.5×, TP=2.0×

1️⃣ rsiOS=35 / rsiOB=70 / SL=1.5 / TP=2.0 → +162%, WR 68%, MDD 24%
2️⃣ rsiOS=30 / rsiOB=65 / SL=1.5 / TP=2.5 → +155%, WR 65%, MDD 27%
3️⃣ rsiOS=40 / rsiOB=70 / SL=1.5 / TP=2.0 → +148%, WR 62%, MDD 30%
0️⃣ 현재 유지

번호를 입력해 주세요.
```

### 수신 (사용자 응답)

- `"1"` / `"2"` / `"3"` → 해당 제안 적용 → 확인 메시지 발송
- `"0"` → 현재 유지 → 확인 메시지 발송
- 그 외 / pendingProposals == null → 무시 (기존 커맨드 핸들러에 위임)

---

## Config Update

### In-memory (즉시)

```java
config.setBollingerBandsRsiOversold(entry.rsiOS());
config.setBollingerBandsRsiOverbought(entry.rsiOB());
config.setAtrSlMultiplier(entry.slMult());
config.setAtrTpMultiplier(entry.tpMult());
```

### application.conf (파일 치환)

파일을 읽어 4개 정규식 치환 후 덮어쓰기:

```
rsiOversold\s*=\s*\d+    →  rsiOversold = <new>
rsiOverbought\s*=\s*\d+  →  rsiOverbought = <new>
atrSlMultiplier\s*=\s*[\d.]+  →  atrSlMultiplier = <new>
atrTpMultiplier\s*=\s*[\d.]+  →  atrTpMultiplier = <new>
```

---

## Scheduling

```java
LocalDateTime now = LocalDateTime.now();
LocalDateTime noon = now.toLocalDate().atTime(12, 0);
if (!now.isBefore(noon)) noon = noon.plusDays(1);
long initialDelaySeconds = Duration.between(now, noon).getSeconds();
scheduler.scheduleAtFixedRate(this::runAndPropose, initialDelaySeconds, 86400, SECONDS);
```

낮 12시 기준은 JVM 로컬 시간 (서버가 KST면 KST 12시).

---

## BacktestRunner Changes

새 메서드 `runSweepForOptimizer(String symbol, int candleCount)`:
- 기존 `runRsiRelaxedSweep()` 로직을 재활용 (내부 루프 추출)
- `List<BB1mSweepResult>` 반환 (출력 없이 순수 결과만)
- 기존 메서드는 유지 (백테스트 직접 실행용)

---

## Files Modified

| 파일 | 변경 |
|------|------|
| **NEW** `main/job/DailyOptimizer.java` | 핵심 로직 전체 (~180줄) |
| `main/backtest/BacktestRunner.java` | `runSweepForOptimizer()` 추가 |
| `main/job/TelegramNotifier.java` | `sendRawMessage(String)` public 추가 |
| `main/BitgetTradingBot.java` | DailyOptimizer 생성, commandHandler 라우팅 추가 |

---

## Out of Scope

- DOGE/XRP 별도 파라미터 최적화
- BB period/stdDev 스윕
- 자동 적용 (사람의 확인 없이) — 항상 사용자 응답 필요