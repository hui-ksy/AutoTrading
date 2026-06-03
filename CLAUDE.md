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

- **심볼**: PEPEUSDT / SOLUSDT / XRPUSDT / WIFUSDT (멀티페어)
- **진입**: BB(17, 2.6σ) 이탈 + RSI 극단
- **청산**: SL/TP = ATR 배수 기반 (코인별 상이), 포지션 보유 중 HOLD 반환
- **레버리지**: 10x, **타임프레임**: 15m

### 채택 파라미터 (멀티페어 스윕 기반, DailyOptimizer가 매일 갱신)

| 심볼 | rsiOS | rsiOB | SL× | TP× | BBW× |
|------|-------|-------|-----|-----|------|
| PEPEUSDT | 35 | 60 | 3.5 | 6.0 | OFF |
| SOLUSDT  | 40 | 60 | 4.0 | 3.5 | 2.5 |
| XRPUSDT  | 30 | 65 | 3.0 | 7.0 | 2.5 |
| WIFUSDT  | 25 | 70 | 4.0 | 6.0 | 2.5 |

---

## 일일 파라미터 자동 최적화 (DailyOptimizer)

- **자동 실행**: 매일 낮 12시 전체 심볼 15m 30일치 스윕 (코인별 1500 조합)
- **스윕 범위**: BB17/2.6σ 고정 | rsiOS=[25,30,35,40] × rsiOB=[60,65,70] × SL=[1.5,2.0,2.5,3.5,4.0] × TP=[2.5,3.5,4.0,5.0,6.0] × BBW=[0.0,1.5,2.0,2.5,3.0]
- **적용 흐름**: 수익률 최고 조합 자동 적용 (인메모리 + application.conf)
- **수동 실행**: `/optimize` (전체) / `/optimize PEPE` (특정 코인)

---

## 텔레그램 명령어

| 명령어 | 설명 |
|--------|------|
| `/balance` | 계좌 잔고 현황 |
| `/stop` | 모든 봇 정지 |
| `/stop [심볼]` | 특정 봇 정지 (예: `/stop xrp`) |
| `/start` | 모든 봇 재시작 |
| `/start [심볼]` | 특정 봇 재시작 (예: `/start xrp`) |
| `/close all` | 모든 포지션 청산 |
| `/close [심볼]` | 특정 포지션 청산 (예: `/close xrp`) |
| `/direction on/off` | 방향성 필터 활성화/비활성화 |
| `/percent [N]` | 진입 비율 변경 — 인메모리 + application.conf 동시 업데이트 |
| `/holdtime [N]` | 최대 보유 시간 변경 — 인메모리 + application.conf 동시 업데이트 |
| `/holdtime on` | 최대 보유 시간 활성화 (12시간 기본값) |
| `/holdtime off` | 최대 보유 시간 비활성화 |
| `/optimize` | 전체 코인 파라미터 최적화 |
| `/optimize [코인]` | 특정 코인만 최적화 (예: `/optimize pepe`) |
| `/help` | 명령어 목록 표시 |

**심볼 입력**: 대소문자 무관, `usdt` 생략 가능 (예: `xrp` → `XRPUSDT`)

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

## 코인별 개별 설정 (SymbolConfig)

- `SymbolConfig.java`: 심볼별 BB/RSI/SL/TP/candleInterval/tickerInterval 컨테이너
- `TradingConfig`: `symbols` 블록 파싱 → `ConcurrentHashMap<String, SymbolConfig>`
- `DailyOptimizer`: 코인별 스윕 → **자동으로 최고 수익률 파라미터 적용** (질의 없음) → `reloadStrategy()` 호출

---

## 최대 포지션 보유 시간 (maxHoldHours)

- **기본값**: 12시간 (`trading.maxHoldHours = 12`, 0=비활성화)
- **동작**: `AutoTrader.tickerLoop()`에서 `entryTimestamp` 초과 시 자동 청산 + 텔레그램 알림

---

## 작업 마무리 규칙

- 작업 완료 후 CLAUDE.md 정리를 지시받으면, 수정 완료 즉시 모든 변경사항을 커밋하고 푸시할 것
- 커밋 메시지는 Lore 형식 사용 (lore-commit 규칙 참조)

---

## TODO

- [ ] 실거래 결과 모니터링 후 orderPercentOfBalance 조정 (현재 25%)
- [ ] **[다음 탐구]** 새로운 전략 알고리즘 백테스트 탐색 (아래 완료된 실험 참고)

---

## 완료된 실험 기록

### EMA200 추세 필터 (2026-05-18) — **미채택**

`BollingerBandReversionStrategy`에 `trendFilterEma` 파라미터 추가 (0=비활성, 하위 호환).
`BacktestRunner.runTrendFilterComparison()`으로 필터 ON/OFF 28.8일 비교.

| 심볼 | OFF 최고수익% | ON 최고수익% | 플러스 OFF/ON | 결론 |
|------|-------------|------------|--------------|------|
| PEPEUSDT | **888%** | 123% (3건) | 58/15 | OFF 압승 |
| SOLUSDT  | 63% | **86%** | 13/30 | ON 유리 |
| AVAXUSDT | **812%** | 37% | 118/45 | OFF 압승 |
| BNBUSDT  | 61% | 41% | 42/68 | ON 안정적(MDD 4% vs 31%) |

**판단**: BB+RSI 극단 진입 전략은 추세와 무관하게 작동하므로 EMA 필터가 전반적으로 수익을 낮춤. 실거래 미적용. `trendFilterEma = 0` 기본값 유지.

### 심볼 교체 — AVAX/BNB → XRP/WIF (2026-05-27) — **채택**

실거래 승률 분석(05-21~05-27): AVAXUSDT 승률 20% / -$107 손실로 전체 손실 93% 유발. BNB 승률 75%이나 순손익 -$13.60으로 의미 없는 수준. 대체 후보 7종 30일 BB17/2.6σ 스윕.

| 심볼 | 최고수익% | 플러스 조합 | 최적 파라미터 | 채택 |
|------|-----------|-------------|---------------|------|
| XRPUSDT  | 221% | **108/132** | rsiOS=30, rsiOB=65, SL=3.0, TP=7.0 | ✅ 채택 |
| WIFUSDT  | 988% | 74/132 | rsiOS=25, rsiOB=70, SL=4.0, TP=6.0 | ✅ 채택 |
| LINKUSDT | 267% | 34/132 | rsiOS=30, rsiOB=70, SL=3.5, TP=7.0 | 차선 |
| DOGEUSDT | 134% | 26/132 | - | ❌ 부적합 |
| SUIUSDT  | 507% | 25/132 | - | ❌ MDD 89% |

**판단**: XRP는 132조합 중 81.8%가 플러스로 전략 적합성 최고. WIF는 수익률 및 플러스 조합 모두 우수. DOGE는 기대 이하, SUI는 MDD 위험.

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