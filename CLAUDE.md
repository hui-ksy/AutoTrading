# AutoTrading — CLAUDE.md

## 프로젝트 개요

Bitget 선물 거래소를 대상으로 하는 자동매매 봇 (Java 17, Gradle).
전략을 백테스트로 검증한 후 실거래에 적용하는 구조.

## 빌드 & 실행

```bash
# 백테스트 실행
./gradlew backtest

# 캔들 API 페이지네이션 디버그
./gradlew candleTest

# 실거래 봇 실행
./gradlew run
```

## 프로젝트 구조

```
src/
├── main/java/main/
│   ├── AutoTrader.java              # 실거래 진입점
│   ├── BitgetTradingBot.java
│   ├── backtest/
│   │   ├── BacktestRunner.java      # 백테스트 진입점
│   │   │     main() → runMultiCoinComparison()  ← 현재 실행 함수
│   │   │     runBBSweep()              # 15m 파라미터 스윕
│   │   │     runBB5mSweep()            # 5m 파라미터 스윕
│   │   │     runBBLeverageComparison() # 레버리지별 수익 비교
│   │   │     runMultiCoinComparison()  # 멀티코인 비교 ★ (BTC만 유효)
│   │   ├── Backtester.java          # 백테스트 엔진 (레버리지 지원)
│   │   └── BacktestResult.java      # 결과 + TradeRecord
│   ├── bitget/
│   │   ├── BitgetFuturesApiClient.java   # 캔들 수집 (페이지네이션 포함)
│   │   ├── BitgetApiClient.java
│   │   ├── TradeClient.java
│   │   ├── MarketDataClient.java
│   │   └── PaperTradingClient.java
│   ├── indicator/
│   │   └── TechnicalIndicators.java     # SMA, EMA, RSI, ATR, CTI, EWO 등
│   ├── strategy/
│   │   ├── TradingStrategy.java              # 인터페이스
│   │   ├── BollingerBandReversionStrategy.java  # 현재 활성 전략 ★
│   │   ├── DynamicExitScalpingStrategy.java  # 이전 전략 (한계 도달)
│   │   ├── StrategyFactory.java
│   │   └── ... (기타 전략들)
│   ├── model/
│   │   ├── Candle.java
│   │   ├── TradingConfig.java           # application.conf 로드
│   │   └── ...
│   └── job/
│       └── TelegramNotifier.java
└── test/java/main/
    └── backtest/
        └── CandleDebugTest.java         # 캔들 API 디버그 (공개 엔드포인트)
```

## 현재 활성 전략: BollingerBandReversionStrategy ★

### 핵심 아이디어
SMA200 추세 필터 없이 볼린저 밴드 2σ 이탈 + RSI 극단 → 평균 회귀 진입.
하락장/횡보장에서도 롱/숏 모두 진입 가능.

### 진입 조건 (30일 스윕 최적값 — 과적합 해소 버전)
- **롱**: Close < BB하단 AND RSI < 20
- **숏**: Close > BB상단 AND RSI > 80
- **BB**: period=10, stdDev=2.0

### 청산 조건
- **SL**: entry ± ATR × 1.0
- **TP**: entry ± ATR × 2.5

### 백테스트 결과 (5m, 30.9일 전체 데이터, 레버리지 1x)
- 거래수: 30건 (0.97건/일)
- 승률: 60.0%
- 수익률: +5.35%
- MDD: 0.77%
- Sharpe: 0.52

> **주의**: 하루 1건 미만으로 거래 빈도가 낮음. 레버리지로 수익률 증폭 권장.
> MDD가 0.77%(1x)로 매우 낮아 10x에서도 MDD ~7.7% 수준으로 관리 가능.

### 이전 파라미터 실패 이력 (과적합 확인됨)
- BB(10, 2.0) rsiOS=35 rsiOB=75 SL=1.5 TP=3.0
- 14.9일: +2.22%, 48.6% 승률 → 30.9일: -8.75%, 38.3% 승률 (과적합)

## application.conf 현재 설정

```hocon
trading {
  mode = "PAPER"          # 페이퍼 트레이딩 중
  strategy = "BOLLINGER_BAND_REVERSION"
  timeframe = "5m"
  pairs = ["BTCUSDT"]    # BTC 단독 (ETH/SOL/XRP 멀티코인 실패 확인됨)
  futures.leverage = 10
}
bollingerBands { period = 10, stdDev = 2.0, rsiPeriod = 14, rsiOversold = 20, rsiOverbought = 80 }
risk {
  atrSlMultiplier = 1.0
  atrTpMultiplier = 2.5
}
```

## Backtester 레버리지 지원 (추가됨)

```java
// 레버리지 지정 생성자
new Backtester(strategy, candles, INIT_BAL, FEE_PCT, warmup, SYMBOL, leverage)

// 레버리지 적용 방식:
// - 진입 수수료: balance × feeRate × leverage
// - PnL: rawPnlPct × leverage - exitFeePct × 100
// - 강제청산: 손실 -100% 캡 적용
```

## DynamicExitScalpingStrategy 한계 (참고용)

- **원인**: `close > SMA(200)` 필터 → 하락장에서 롱 신호 없음
- **최선 결과**: 승률 62.5%, 1.29건/일, +3.19% (목표 미달)

## Bitget 캔들 API 핵심 사항

### API 응답 순서
- `/api/v2/mix/market/candles` 는 **오름차순(oldest first)** 으로 반환
- `Collections.reverse()` 하면 안 됨 — 페이지네이션 버그 발생

### 페이지네이션 방법
```
1. endTime 없이 최신 200개 조회 → oldest 캔들 = batch[0]
2. endTime = batch[0].timestamp - 1ms 로 다음 조회
3. 반복하며 oldest-first 리스트에 prepend
```

## 의존성 주요 라이브러리

- OkHttp 4.12 — HTTP 클라이언트
- Gson 2.10 — JSON 파싱
- Logback — 로깅
- Typesafe Config — 설정 파일
- Telegram Bot API — 알림

## 목표

- 수익 극대화 (레버리지 활용), 하루 1건 내외 고품질 신호

## 멀티코인 백테스트 결과 (BTC 튜닝 파라미터 기준, 30일)

| 심볼 | 승률 | 수익률 | MDD | 결론 |
|------|------|--------|-----|------|
| BTCUSDT | 60.0% | +5.35% (1x) | 0.77% | ✅ 채택 |
| ETHUSDT | 38.5% | 실패 | 21%+ | ❌ 기각 |
| SOLUSDT | 32.x% | 실패 | 30%+ | ❌ 기각 |
| XRPUSDT | 36.x% | 실패 | 39%+ | ❌ 기각 |

> BTC 파라미터는 다른 코인에 과적합. BTC 단독 운용 결정.

## AutoTrader SL/TP 수정 이력

- **기존**: `setFixedTpSl()` — 고정 0.15% SL / 0.30% TP (백테스트와 불일치)
- **수정**: signal의 `stopLoss`/`takeProfit` 값이 있으면 ATR 기반 사용, 없으면 폴백
- **위치**: `AutoTrader.executeEnterPosition()` L247

## 실행 방법

```bash
# 페이퍼 트레이딩 (자동 재시작 + 로그 저장)
run_bot.bat   # Windows 더블클릭 또는 Task Scheduler 등록

# 또는
./gradlew run
```

## 다음 작업 (TODO)

- [x] 레버리지 결정: 10x 선택 (MDD ~7.7%)
- [x] SL/TP 백테스트 일치 수정
- [ ] 1-2주 페이퍼 트레이딩 모니터링 (텔레그램 알림 확인)
- [ ] 실거래 전환 전 `mode = "LIVE"`, `leverage = 10` 재확인

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
