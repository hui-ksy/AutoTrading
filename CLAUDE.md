# AutoTrading — CLAUDE.md

## 프로젝트 개요

Bitget 선물 거래소 자동매매 봇 (Java 21, Gradle). 전략을 백테스트로 검증 후 실거래 적용.

## 빌드 & 실행

```bash
./gradlew backtest   # 백테스트
./gradlew run        # 실거래 봇
./gradlew candleTest # 캔들 API 디버그
```

## 프로젝트 구조

```
src/main/java/main/
├── AutoTrader.java                        # 트레이딩 루프 (캔들/티커 스케줄러)
├── BitgetTradingBot.java                  # 진입점 ★ (main 여기)
├── backtest/
│   ├── BacktestRunner.java                # main() → runRecentDayCheck() ★
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
│   ├── AltcoinEmaScalpingStrategy.java      # 검증 중
│   ├── StrategyFactory.java
│   └── ... (기타)
├── model/
│   ├── TradingConfig.java                 # application.conf 로드
│   └── OptimizationProposal.java          # 일일 최적화 제안 레코드
└── job/
    ├── TelegramNotifier.java
    ├── DailyOptimizer.java                # 일일 파라미터 자동 최적화 ★
    └── WindowsPowerManager.java           # Windows 절전 모드 차단
```

---

## 현재 활성 전략: BollingerBandReversionStrategy ★ (알트코인 15m)

- **심볼**: PEPEUSDT / DOGEUSDT / XRPUSDT (멀티페어)
- **진입**: BB(17, 2.6σ) 이탈 + RSI 극단 (Long: RSI<30, Short: RSI>70)
- **청산**: SL = entry ± ATR×1.5, TP = entry ± ATR×2.0
- **레버리지**: 10x, **타임프레임**: 15m
- **포지션 보유 중**: 전략은 항상 HOLD 반환 → SL/TP는 AutoTrader(페이퍼) / 거래소 주문(LIVE)이 처리

### PEPE 정밀 스윕 결과 (4536 combos, 15m, 10x, 30.6일)

| 후보 | 수익률 | 승률 | 건/일 | MDD | Sharpe | 파라미터 |
|------|--------|------|-------|-----|--------|---------|
| 공격형 ★ (현재) | **+172.49%** | 72.4% | 0.95 | 25.45% | 0.48 | bbP=17, bbSD=2.6, rsiOS=30, rsiOB=70, SL=1.5, TP=2.0 |
| 보수형 | **+101.80%** | 75.0% | 0.52 | 13.41% | — | bbP=17, bbSD=2.7, rsiOS=25, rsiOB=70, SL=1.2, TP=2.0 |

### 1차 스윕 결과 (486 combos, 15m, 10x, 약 31일)

| 심볼 | 수익률 | 승률 | 건/일 | 파라미터 |
|------|--------|------|-------|---------|
| PEPEUSDT | +52.17% | 56.7% | 0.98 | bbP=15, bbSD=2.5, rsiOS=30, rsiOB=70, SL=1.5, TP=2.5 |
| DOGEUSDT | +26.99% | 42.9% | 0.46 | bbP=10, bbSD=2.5, rsiOS=20, rsiOB=70, SL=1.0, TP=3.0 |
| XRPUSDT  | +26.11% | 35.7% | 1.37 | bbP=20, bbSD=2.5, rsiOS=20, rsiOB=65, SL=1.0, TP=3.0 |

---

## application.conf 현재 설정

```hocon
trading {
  mode = "PAPER"
  strategy = "BOLLINGER_BAND_REVERSION"
  timeframe = "15m"
  pair = "PEPEUSDT"
  pairs = ["XRPUSDT", "DOGEUSDT", "PEPEUSDT"]
  futures.leverage = 10
  candleIntervalSeconds = 15
}
bollingerBands { period = 17, stdDev = 2.6, rsiPeriod = 14, rsiOversold = 30, rsiOverbought = 70 }
risk { atrSlMultiplier = 1.5, atrTpMultiplier = 2.0 }
```

---

## 일일 파라미터 자동 최적화 (DailyOptimizer)

- **자동 실행**: 매일 낮 12시(로컬 시간) PEPEUSDT 15m 30일치 스윕 (108 조합)
- **스윕 범위**: BB17/2.6σ 고정 | rsiOS=[25,30,35,40] × rsiOB=[60,65,70] × SL=[1.2,1.5,1.8] × TP=[2.0,2.5,3.0]
- **제안 흐름**: 수익 플러스 상위 3개 → 텔레그램 1️⃣/2️⃣/3️⃣ 제안 → 번호 응답 시 즉시 적용
- **적용 범위**: TradingConfig 인메모리 + `AutoTrader.reloadStrategy()` (다음 캔들부터 반영) + `application.conf` 파일 (재시작 후 유지)
- **수동 실행**: `/optimize` 텔레그램 명령어로 즉시 트리거 가능

---

## 텔레그램 명령어

`/status` `/balance` `/stop` `/start` `/add [SYM]` `/close [SYM]` `/close all`  
`/long on/off` `/short on/off` `/tp [%]` `/sl [%]` `/stop [SYM]` `/start [SYM]` `/optimize` `/help`

---

## Bitget 캔들 API 핵심 사항

- `/api/v2/mix/market/candles` 는 **오름차순(oldest first)** 반환 — `Collections.reverse()` 금지
- 페이지네이션: `endTime` 없이 최신 200개 → `batch[0].timestamp - 1ms` 로 반복

---

## 주요 라이브러리

OkHttp 4.12, Gson 2.10, Logback, Typesafe Config, Telegram Bot API

---

## TODO

- [x] PEPE 파라미터 정밀 스윕 완료 (4536 combos) → 공격형 적용
- [x] 일일 파라미터 자동 최적화 (DailyOptimizer) 구현 완료
- [ ] 페이퍼 트레이딩으로 실제 신호 확인 (`./gradlew run`)
- [ ] DOGE/XRP 정밀 스윕 (1차 스윕 최적 파라미터 중심)
- [ ] 실거래 전환 전 `mode = "LIVE"` 재확인

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