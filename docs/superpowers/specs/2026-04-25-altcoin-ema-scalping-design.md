
# AltcoinEmaScalpingStrategy — 설계 스펙

**날짜**: 2026-04-25  
**상태**: 승인됨  
**목표**: 고레버리지(25x) 알트코인 1m 스캘핑 전략 — BTC BB 평균회귀 전략에서 전환

---

## 1. 배경 및 목적

기존 `BollingerBandReversionStrategy`(5m, BTC, 10x)는 하루 0.87건으로 거래 빈도가 너무 낮고,
PAPER 모드 재시작 시 포지션 복원 버그로 TP/SL이 체크되지 않는 문제가 있었다.

새 전략은:
- 거래량이 활발한 알트코인(XRP, DOGE, PEPE)에서 1m 단위 빠른 진입/청산
- 25x 레버리지로 작은 가격 움직임에서 고수익 추구
- 백테스트 후 파라미터가 안 좋으면 다른 전략으로 교체 예정

---

## 2. 대상 종목 및 환경

| 항목 | 값 |
|------|-----|
| 종목 | XRPUSDT, DOGEUSDT, PEPEUSDT |
| 타임프레임 | 1m |
| 레버리지 | 25x |
| 마진 모드 | isolated |
| 거래 모드 | PAPER (검증 후 LIVE 전환) |
| 수수료 | 0.06% (Bitget taker) |

---

## 3. 진입 조건

### 롱 진입
- EMA5가 EMA20을 **상향 크로스** (이전 봉: EMA5 ≤ EMA20, 현재 봉: EMA5 > EMA20)
- RSI(14) > 50

### 숏 진입
- EMA5가 EMA20을 **하향 크로스** (이전 봉: EMA5 ≥ EMA20, 현재 봉: EMA5 < EMA20)
- RSI(14) < 50

> allowLong / allowShort 설정으로 방향별 on/off 가능

---

## 4. 청산 조건 (우선순위 순)

| 우선순위 | 조건 | 설명 |
|---------|------|------|
| 1 | **SL** | 진입가 ± ATR(14) × 0.8 — 강제청산 방어, 타이트 |
| 2 | **TP** | 진입가 ± ATR(14) × 2.5 — 추세 충분히 탐 |
| 3 | **지표반전 EXIT** | EMA 역크로스 발생 시 청산 (TP 미달해도) |

SL/TP는 `AutoTrader.tickerLoop()`에서 1초마다 체크.  
지표반전 EXIT는 `generateSignal()`에서 포지션 보유 중 EMA 역크로스 감지 시 `Signal.Action.EXIT` 반환.

---

## 5. 포지션 사이징

- `orderSizingStrategy = "PERCENT_OF_EQUITY"`
- `orderPercentOfBalance = 100.0` (기존 방식 유지)
- 코인당 독립 포지션 — 동시 최대 3개 (종목 수만큼)

---

## 6. 구현 범위

### 6-1. 신규 파일
- `src/main/java/main/strategy/AltcoinEmaScalpingStrategy.java`
  - `TradingConfig` 생성자 + 파라미터 스윕용 생성자 (두 개)
  - EMA5, EMA20 크로스 감지
  - RSI 필터
  - 포지션 보유 중 EMA 역크로스 → EXIT 반환

### 6-2. 수정 파일
- `src/main/resources/application.conf`
  - `strategy = "ALTCOIN_EMA_SCALPING"`
  - `pairs = ["XRPUSDT", "DOGEUSDT", "PEPEUSDT"]`
  - `timeframe = "1m"`
  - `futures.leverage = 25`
  - `risk.atrSlMultiplier = 0.8`
  - `risk.atrTpMultiplier = 2.5`

- `src/main/java/main/strategy/StrategyFactory.java`
  - `"ALTCOIN_EMA_SCALPING"` 케이스 추가

- `src/main/java/main/backtest/BacktestRunner.java`
  - `runAltcoinEmaSweep()` 메서드 추가
    - EMA 단기(3,5,7) × 장기(10,15,20) × ATR SL(0.5~1.2) × ATR TP(1.5~3.0) 스윕
    - 종목별 개별 백테스트 후 결과 출력

---

## 7. 파라미터 스윕 계획

| 파라미터 | 범위 | 스텝 |
|---------|------|------|
| EMA 단기 | 3, 5, 7 | — |
| EMA 장기 | 10, 15, 20 | — |
| ATR SL 배수 | 0.5 ~ 1.2 | 0.1 |
| ATR TP 배수 | 1.5 ~ 3.0 | 0.5 |

3(EMA단기) × 3(EMA장기) × 8(SL) × 4(TP) = **288 조합**  
종목 3개 × 백테스트 기간 7일 = 864회 실행

---

## 8. 성공 기준

| 지표 | 최소 기준 |
|------|---------|
| Sharpe | > 0.5 |
| MDD (1x) | < 5% |
| 거래 빈도 | > 5건/일/종목 |
| 승률 | > 45% |

기준 미달 시 → 볼륨 스파이크 브레이크아웃 전략으로 재검토

---

## 9. 미해결 사항 (범위 외)

- PAPER 모드 재시작 시 포지션 복원 버그 (`checkAndLoadExistingPosition()`) — 별도 이슈로 추후 수정
