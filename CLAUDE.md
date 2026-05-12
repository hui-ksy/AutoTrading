# AutoTrading — CLAUDE.md

## 프로젝트 개요

Bitget 선물 거래소 자동매매 봇 (Java 21, Gradle). 전략을 백테스트로 검증 후 실거래 적용.

## 빌드 & 실행

```bash
./gradlew backtest   # 백테스트 (현재 main → runMultiPairLongSweep)
./gradlew run        # 실거래 봇
./gradlew candleTest # 캔들 API 디버그
```

## 프로젝트 구조

```
src/main/java/main/
├── AutoTrader.java                        # 트레이딩 루프 (캔들/티커 스케줄러)
├── BitgetTradingBot.java                  # 진입점 ★ (main 여기)
├── backtest/
│   ├── BacktestRunner.java                # main() → runMultiPairLongSweep() ★
│   ├── Backtester.java                    # 백테스트 엔진 (레버리지 지원)
│   └── BacktestResult.java
├── bitget/
│   ├── BitgetFuturesApiClient.java        # 캔들 수집 (페이지네이션)
│   ├── TradeClient.java / MarketDataClient.java
│   └── PaperTradingClient.java
├── indicator/
│   └── TechnicalIndicators.java           # SMA, EMA, RSI, ATR, CTI, EWO
├── strategy/
│   ├── BollingerBandReversionStrategy.java  # 현재 활성 전략 ★
│   ├── AltcoinEmaScalpingStrategy.java
│   ├── StrategyFactory.java
│   └── ... (기타)
├── model/
│   ├── TradingConfig.java                 # application.conf 로드
│   ├── SymbolConfig.java                  # 심볼별 파라미터 컨테이너 (신규)
│   └── OptimizationProposal.java
└── job/
    ├── TelegramNotifier.java
    ├── DailyOptimizer.java                # 일일 파라미터 자동 최적화 ★
    └── WindowsPowerManager.java
```

---

## 현재 활성 전략: BollingerBandReversionStrategy ★ (알트코인 15m)

- **심볼**: PEPEUSDT / SOLUSDT / AVAXUSDT / BNBUSDT (멀티페어, DOGE 제외)
- **진입**: BB(17, 2.6σ) 이탈 + RSI 극단 (Long: RSI<30, Short: RSI>70)
- **청산**: SL/TP = ATR 배수 기반, 포지션 보유 중 전략은 HOLD 반환
- **레버리지**: 10x, **타임프레임**: 15m

### 백테스트 결과

**PEPE 정밀 스윕 (4536 combos, 15m, 10x, 30.6일)**

| 후보 | 수익률 | 승률 | 건/일 | MDD | 파라미터 |
|------|--------|------|-------|-----|---------|
| 공격형 ★ (현재) | **+172.49%** | 72.4% | 0.95 | 25.45% | bbP=17, bbSD=2.6, rsiOS=30, rsiOB=70, SL=1.5, TP=2.0 |
| 보수형 | **+101.80%** | 75.0% | 0.52 | 13.41% | bbP=17, bbSD=2.7, rsiOS=25, rsiOB=70, SL=1.2, TP=2.0 |

**멀티페어 1차 스윕 (486 combos, 15m, 10x, ~31일)**

| 심볼 | 수익률 | 승률 | 건/일 | 파라미터 |
|------|--------|------|-------|---------|
| PEPEUSDT | +52.17% | 56.7% | 0.98 | bbP=15, bbSD=2.5, rsiOS=30, rsiOB=70, SL=1.5, TP=2.5 |
| DOGEUSDT | +26.99% | 42.9% | 0.46 | bbP=10, bbSD=2.5, rsiOS=20, rsiOB=70, SL=1.0, TP=3.0 |

**멀티페어 장기 스윕 (BB17/2.6 고정, 15m, 10x, 90일)**
- 대상: DOGE / PEPE / SOL / LTC
- LTC: 수익 조합 2개뿐 (max +11%) → 제외, DOGE: 성과 부진 → 제외

**멀티페어 후보 스윕 (BB17/2.6 고정, 15m, 10x, 30.6일) — XRP/BNB/AVAX/LINK/SUI/WIF/PEPE/SOL**
- XRP·WIF·LINK: 플러스 조합 거의 없음 → 탈락

| 심볼 | 채택 파라미터 | 수익% | 승률 | MDD |
|------|-------------|-------|------|-----|
| PEPEUSDT | bbP=17, bbSD=2.6, rsiOS=35, rsiOB=65, SL=3.5, TP=4.0 | +363% | 63% | 44.7% |
| SOLUSDT  | bbP=17, bbSD=2.6, rsiOS=30, rsiOB=70, SL=4.0, TP=7.0 | +239% | 60% | 53.1% |
| AVAXUSDT | bbP=17, bbSD=2.6, rsiOS=25, rsiOB=65, SL=4.0, TP=6.0 | +702% | 75% | 21.8% ★ |
| BNBUSDT  | bbP=17, bbSD=2.6, rsiOS=35, rsiOB=65, SL=4.0, TP=5.0 | +116% | 64% | 31.3% |

---

## application.conf 현재 설정

```hocon
trading {
  mode = "LIVE"   # ★ 실거래 모드
  strategy = "BOLLINGER_BAND_REVERSION"
  timeframe = "15m"
  pair = "PEPEUSDT"
  pairs = ["PEPEUSDT", "SOLUSDT", "AVAXUSDT", "BNBUSDT"]
  futures.leverage = 10
  candleIntervalSeconds = 15
}
risk {
  orderSizingStrategy = "PERCENT_OF_EQUITY"
  orderPercentOfBalance = 10.0   # 잔고의 10%씩 진입
  atrSlMultiplier = 4.0
  atrTpMultiplier = 6.0
}
bollingerBands { period = 17, stdDev = 2.6, rsiPeriod = 14, rsiOversold = 30, rsiOverbought = 70 }
symbols {
  PEPEUSDT  { bbPeriod = 17, bbStdDev = 2.6, rsiOversold = 35, rsiOverbought = 65, slMult = 3.5, tpMult = 4.0 }
  SOLUSDT   { bbPeriod = 17, bbStdDev = 2.6, rsiOversold = 30, rsiOverbought = 70, slMult = 4.0, tpMult = 7.0 }
  AVAXUSDT  { bbPeriod = 17, bbStdDev = 2.6, rsiOversold = 25, rsiOverbought = 65, slMult = 4.0, tpMult = 6.0 }
  BNBUSDT   { bbPeriod = 17, bbStdDev = 2.6, rsiOversold = 35, rsiOverbought = 65, slMult = 4.0, tpMult = 5.0 }
}
```

---

## 일일 파라미터 자동 최적화 (DailyOptimizer)

- **자동 실행**: 매일 낮 12시 DOGE/PEPE/SOL 전체 15m 30일치 스윕 (코인별 108 조합)
- **스윕 범위**: BB17/2.6σ 고정 | rsiOS=[25,30,35,40] × rsiOB=[60,65,70] × SL=[1.2,1.5,1.8] × TP=[2.0,2.5,3.0]
- **제안 흐름**: 코인별 수익 상위 3개 표시 → `"1 2 0"` 형식으로 일괄 응답 → 즉시 적용 (인메모리 + application.conf)
- **30분 타임아웃**: 응답 없으면 자동 취소
- **수동 실행**: `/optimize` (전체) / `/optimize PEPE` (특정 코인)

---

## 텔레그램 명령어

`/status` `/balance` `/stop` `/start` `/add [SYM]` `/close [SYM]` `/close all`
`/long on/off` `/short on/off` `/tp [%]` `/sl [%]` `/stop [SYM]` `/start [SYM]`
`/direction on/off` `/optimize` `/optimize [코인]` `/percent [N]` `/help`

- `/percent [N]` — 진입 비율 변경 (예: `/percent 10`). 인메모리 + application.conf 동시 업데이트.

---

## 텔레그램 알림 주기

- **거래 상황 요약** (`/status` 형태): 5분마다 자동 발송 (포지션 있을 때)
- **분석 현황** (HOLD 상태 지표): 30분마다 자동 발송

---

## 방향성 필터 (DirectionFilter)

- **기본값**: ON — 한 코인 LONG 시 다른 코인 SHORT 차단 (역방향 헤지 방지)
- **구현**: `AutoTrader.DirectionChecker` → `BitgetTradingBot.isDirectionBlocked()`
- **토글**: `/direction on/off`

---

## 코인별 개별 설정 (SymbolConfig) ✅

- `SymbolConfig.java`: 심볼별 BB/RSI/SL/TP/candleInterval/tickerInterval 컨테이너, `defaults(symbol)` 팩토리
- `TradingConfig`: `symbols` 블록 파싱 → `ConcurrentHashMap<String, SymbolConfig>`
- `StrategyFactory`: `createStrategy(config, SymbolConfig sc)` 오버로드
- `DailyOptimizer`: 코인별 스윕 → 각 SymbolConfig 업데이트 → `reloadStrategy()` 호출

---

## LIVE 모드 SL/TP 버그 수정 ✅

- **증상**: LIVE 모드에서 전략이 계산한 ATR 기반 SL/TP가 무시되고 항상 고정 0.15%/0.3%가 적용됨 → 짧은 SL로 급격한 연속 청산 발생
- **원인**: `AutoTrader.confirmPositionEntered()`가 `setFixedTpSl()` 호출, `entrySignal`의 SL/TP를 미사용
- **수정**:
  - `confirmPositionEntered()`: `entrySignal.getStopLoss()` / `entrySignal.getTakeProfit()` 우선 사용, 없을 때만 고정값 폴백
  - `checkAndLoadExistingPosition()`: 봇 재시작 시 `setAtrTpSl()` 호출 (캔들 fetch → ATR 계산 → SymbolConfig slMult/tpMult 적용)

---

## TODO

- [x] PEPE 정밀 스윕 (4536 combos) → 공격형 파라미터 적용
- [x] 일일 파라미터 자동 최적화 (DailyOptimizer) — 코인별 스윕 + 30분 타임아웃
- [x] PAPER 모드 SL/TP 버그 수정
- [x] LIVE 모드 SL/TP 버그 수정 — `confirmPositionEntered()` ATR 기반 신호값 우선 사용
- [x] 방향성 필터 (DirectionChecker, `/direction on/off`)
- [x] 코인별 개별 설정 전체 완료 (SymbolConfig + application.conf symbols 블록)
- [x] 멀티페어 장기 스윕 (90일) → DOGE/PEPE/SOL 파라미터 확정, LTC 제외
- [x] PEPE/DOGE 가격 표시 소수점 버그 수정 (formatPrice, fmt 헬퍼)
- [x] LIVE 모드 전환, 잔고 10% 진입
- [x] `/percent [N]` 명령어 추가 — 진입 비율 동적 변경 (인메모리 + conf)
- [ ] 실거래 결과 모니터링 후 orderPercentOfBalance 조정 (현재 10%)

---

<!-- AUTOPUS:BEGIN -->
# Autopus-ADK Harness

> 이 섹션은 Autopus-ADK에 의해 자동 생성됩니다. 수동으로 편집하지 마세요.

- **프로젝트**: AutoTrading
- **모드**: full
- **플랫폼**: claude-code, gemini-cli

## 설치된 구성 요소

- Rules: .claude/rules/autopus/
- Skills: .claude/skills/autopus/
- Commands: .claude/skills/auto/SKILL.md
- Agents: .claude/agents/autopus/

## Rule Isolation

IMPORTANT: This project uses this directory's Autopus-ADK instructions ONLY. You MUST ignore any Autopus or non-Autopus rules loaded from parent directories, and any parent Autopus-generated CLAUDE.md guidance is lower priority than this project's instructions.

## Core Guidelines

### Subagent Delegation

IMPORTANT: Use subagents for complex tasks that modify 3+ files, span multiple domains, or exceed 200 lines of new code. Define clear scope, provide full context, review output before integrating.

### File Size Limit

IMPORTANT: No source code file may exceed 300 lines. Target under 200 lines. Split by type, concern, or layer when approaching the limit. Excluded: generated files (*_generated.go, *.pb.go), documentation (*.md), and config files (*.yaml, *.json).

### Code Review

During review, verify:
- No file exceeds 300 lines (REQUIRED)
- Complex changes use subagent delegation (SUGGESTED)
- See .claude/rules/autopus/ for detailed guidelines

<!-- AUTOPUS:END -->