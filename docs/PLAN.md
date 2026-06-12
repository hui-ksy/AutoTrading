# AutoTrading 리팩토링 계획

> 작성일: 2026-06-12  
> 현황: 48개 Java 파일, 8,238줄

---

## 현황 분석

### 파일 크기 현황

| 파일 | 라인 수 | 문제 |
|------|---------|------|
| `backtest/BacktestRunner.java` | 2,022줄 | 스윕 / 리포트 / 실행 모두 혼재 |
| `AutoTrader.java` | 631줄 | 진입 / 청산 / 모니터링 / 전략 실행 뒤섞임 |
| `BitgetTradingBot.java` | 625줄 | 메인 루프 + 텔레그램 + 설정 + 기록 모두 담당 |
| `bitget/BitgetFuturesApiClient.java` | 635줄 | HTTP 통신 + 주문 + 서명 + 포지션 조회 혼재 |
| `indicator/TechnicalIndicators.java` | 485줄 | 모든 지표 계산을 단일 파일에 집중 |
| `job/DailyOptimizer.java` | ~300줄 | 최적화 + 백테스트 + 설정 업데이트 + 알림 |

### 핵심 문제 목록

1. **God Class** — BitgetTradingBot, AutoTrader, BacktestRunner가 너무 많은 책임 보유
2. **전역 static 상태** — `sharedTotalEquity`, `sharedAvailableBalance`로 인한 강한 결합 및 테스트 불가
3. **중복 코드** — `formatPrice()` / `fmt()` 유사 로직이 5곳 산재
4. **설정 업데이트 로직 분산** — `BitgetTradingBot` 3곳 + `DailyOptimizer` 1곳
5. **스케줄러 분산 관리** — `BitgetTradingBot` 4개 + `AutoTrader` 2개, graceful shutdown 미흡
6. **의존성 결합** — AutoTrader가 BitgetTradingBot의 static 변수에 직접 접근
7. **패키지 구조 미흡** — 계층 분리 없음, strategy 17개 파일 과포화

---

## 단계별 리팩토링 계획

### P0 — 즉시 (기반 정리)

목표: 실거래에 영향 없이 안전하게 수행 가능한 기반 작업

#### [ ] 1. 공통 포맷팅 유틸리티 추출

- 신규 파일: `util/PriceFormatter.java`
- 대상: `BitgetTradingBot.formatPrice()`, `BollingerBandReversionStrategy.fmt()` 외 3곳
- 방법: 기존 메서드를 `PriceFormatter.format(double)` 호출로 교체

#### [ ] 2. 설정 파일 업데이트 로직 통합

- 신규 파일: `util/ConfigFileUpdater.java`
- 대상: `BitgetTradingBot.updateConfigPercent/HoldTime/HoldEnabled()`, `DailyOptimizer.updateConfigFile()`
- 방법: 공통 키-값 교체 메서드로 추상화 후 각 호출부에서 위임

#### [ ] 3. 전역 static 상태 제거

- 신규 파일: `account/AccountBalanceProvider.java` (인터페이스)
- 신규 파일: `account/LiveAccountBalanceProvider.java` (구현체)
- 대상: `BitgetTradingBot.sharedTotalEquity`, `sharedAvailableBalance`
- 방법: AutoTrader 생성자에 `AccountBalanceProvider` 주입, static 직접 접근 제거

---

### P1 — 1주 내 (핵심 분할)

목표: God Class 분해, 스케줄러 관리 통합

#### [ ] 4. BitgetTradingBot 분할

현재 625줄 → 아래 클래스들로 분리

| 신규 클래스 | 책임 | 예상 라인 수 |
|------------|------|-------------|
| `BitgetTradingBot` | 진입점만 유지 (main + start 호출) | ~50줄 |
| `TradingBotOrchestrator` | AutoTrader 목록 관리, 전체 기동/정지 | ~150줄 |
| `TelegramCommandHandler` | 텔레그램 명령어 파싱 및 처리 | ~150줄 |
| `TradeRecorder` | 거래 기록, 누적 통계 계산 | ~100줄 |

#### [ ] 5. AutoTrader 분할

현재 631줄 → 아래 클래스들로 분리

| 신규 클래스 | 책임 | 예상 라인 수 |
|------------|------|-------------|
| `AutoTrader` | 조율자 역할로 축소 | ~150줄 |
| `PositionManager` | 포지션 진입 / 청산 실행 | ~150줄 |
| `TakeProfitStopLossManager` | TP/SL 주문 관리 | ~100줄 |
| `EntrySignalProcessor` | 진입 신호 처리 및 검증 | ~100줄 |

#### [ ] 6. 스케줄러 관리 통합

- 신규 파일: `job/SchedulerManager.java`
- 대상: BitgetTradingBot 4개 + AutoTrader 2개 스케줄러
- 방법: 이름으로 등록/조회, 종료 시 일괄 shutdown 보장

---

### P2 — 2주 내 (세부 분할)

목표: API 클라이언트 및 백테스트 분리, 기술 지표 계층화

#### [ ] 7. BacktestRunner 분할

현재 2,022줄 → 아래 클래스들로 분리

| 신규 클래스 | 책임 | 예상 라인 수 |
|------------|------|-------------|
| `BacktestRunner` | 진입점, 실행 조율 | ~150줄 |
| `ParameterSweeper` | 파라미터 조합 생성 및 스윕 실행 | ~250줄 |
| `BacktestReporter` | 결과 포맷팅 및 출력 | ~200줄 |

#### [ ] 8. BitgetFuturesApiClient 분할

현재 635줄 → 아래 클래스들로 분리

| 신규 클래스 | 책임 | 예상 라인 수 |
|------------|------|-------------|
| `BitgetFuturesApiClient` | 퍼사드 역할 유지 | ~100줄 |
| `HttpApiClient` | HTTP 요청/응답 추상화 | ~150줄 |
| `OrderExecutor` | 주문 생성 / 취소 | ~150줄 |
| `PositionQuerier` | 포지션 및 잔고 조회 | ~100줄 |
| `HmacSignatureGenerator` | HMAC-SHA256 서명 | ~50줄 |

#### [ ] 9. TechnicalIndicators 분할

현재 485줄 → 지표별 클래스로 분리

| 신규 클래스 | 담당 지표 |
|------------|----------|
| `BollingerBandCalculator` | BB upper / lower / width |
| `RSICalculator` | RSI |
| `ATRCalculator` | ATR |
| `EMACalculator` | EMA, SMA |
| `MomentumCalculator` | CTI, EWO, Stochastic |

---

### P3 — 마무리 (구조 및 정리)

목표: 패키지 재정비, 미사용 코드 제거, 명명 일관성 확보

#### [ ] 10. 패키지 구조 재설계

```
main/
├── account/          # 계좌 잔고 추상화
├── analysis/
│   └── indicator/    # 지표 계산기들
├── api/
│   └── bitget/       # HTTP 클라이언트, 주문, 포지션
├── backtest/
│   ├── engine/
│   └── reporter/
├── core/
│   └── trading/      # AutoTrader, PositionManager 등
├── job/              # DailyOptimizer, SchedulerManager, TelegramNotifier
├── model/
│   ├── domain/       # 순수 데이터 모델
│   └── config/       # 설정 모델
├── strategy/
│   ├── core/         # TradingStrategy 인터페이스, StrategyFactory
│   └── impl/         # 전략 구현체들
└── util/             # PriceFormatter, ConfigFileUpdater
```

#### [ ] 11. 미사용 전략 클래스 정리

- strategy/ 하위 17개 파일 중 현재 활성화되지 않은 전략 파악
- 사용 중인 전략: `BollingerBandReversionStrategy`
- 보존 대상: `StrategyFactory`에서 참조 중인 전략
- 제거 또는 `archive/` 이동 대상: 참조 없는 전략

#### [ ] 12. 재시도 로직 표준화

- 신규 파일: `util/RetryPolicy.java`
- 대상: AutoTrader 내 하드코딩된 `for (int i = 0; i < 3; i++)` + `Thread.sleep(2000)` 패턴 (6곳 이상)
- 방법: `RetryPolicy.execute(Supplier, maxAttempts, delayMs)` 패턴으로 통일

#### [ ] 13. 명명 일관성 정리

- 약자 변수명 전체 이름으로 변경 (`sc` → `symbolConfig`, `p` → `proposal`)
- Boolean 필드 prefix 통일 (`directionFilterEnabled` → `isDirectionFilterEnabled`)
- 메서드 동사 통일 (`start/stop` vs `begin/end` 혼용 정리)

---

## 목표 메트릭

| 메트릭 | 현재 | 목표 |
|--------|------|------|
| 최대 클래스 라인 수 | 2,022줄 | 300줄 이하 |
| 평균 메서드 길이 | ~30줄 | ~15줄 |
| 전역 static 상태 | 6개 변수 | 0개 |
| 중복 포맷팅 로직 | 5곳 | 1곳 (PriceFormatter) |
| 설정 업데이트 로직 | 4곳 | 1곳 (ConfigFileUpdater) |

---

## 진행 원칙

- 각 단계는 독립적으로 커밋 (빌드 통과 상태 유지)
- P0 → P1 순서 준수 (기반 없이 분할 금지)
- 실거래 중 P0, P1 수행 가능 / P2부터는 봇 중단 후 진행 권장
- 리팩토링 중 기능 추가 금지 (별도 브랜치로 분리)